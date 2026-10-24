"""原生本地来源：根身份、路径边界、增量扫描与两阶段安全删除的前置复验。"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import stat
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path, PurePosixPath

import sqlalchemy as sa

from app.adapters.media_source import MediaItem, can_delete
from app.core.errors import (
    AppError,
    ConflictError,
    ForbiddenError,
    MediaNotFoundError,
    ValidationFailedError,
)
from app.db.models import BackgroundTask, LibrarySelection, LocalRoot, MediaCacheIndex, utc_now
from app.services.media_index import upsert_media_items

TASK_TYPE = "local_scan"
# 常见容器白名单：容器分类跟随 media_type，未知扩展名跳过(不猜类型)。
EXTENSIONS = {
    ".jpg": "image", ".jpeg": "image", ".png": "image", ".webp": "image",
    ".gif": "image", ".bmp": "image", ".tiff": "image", ".tif": "image",
    ".mp4": "video", ".mkv": "video", ".mov": "video", ".webm": "video",
    ".m4v": "video", ".ts": "video", ".m2ts": "video", ".avi": "video",
    ".wmv": "video", ".flv": "video", ".mpg": "video", ".mpeg": "video",
}
SKIP_DIRS = {"system volume information", "$recycle.bin", "windows", "program files",
             "program files (x86)", "programdata", ".git", ".venv"}
BATCH_SIZE = 128
MAX_ENTRIES = 250_000
MAX_DEPTH = 128
logger = logging.getLogger(__name__)


class SourcePathError(ValidationFailedError):
    default_message = "本地来源路径无效、不可用或包含链接，请检查电脑端目录"


class LocalDeleteError(AppError):
    """本地源永久删除被拒绝；``delete_code`` 是写入队列 error/审计的稳定失败码(不含路径)。"""

    status_code = 403
    code = "LOCAL_DELETE_REJECTED"
    delete_code = "path_rejected"
    default_message = "本地来源删除被拒绝，请检查电脑端目录"


class LocalRootMissingError(LocalDeleteError):
    status_code = 404
    code = "LOCAL_ROOT_MISSING"
    delete_code = "local_root_missing"
    default_message = "本地来源不存在或已被移除，请重新登记本地来源"


class LocalRootDisabledError(LocalDeleteError):
    code = "LOCAL_ROOT_DISABLED"
    delete_code = "local_root_disabled"
    default_message = "本地来源已停用，禁止永久删除；请先在电脑管理后台启用该来源"


class LocalFileInUseError(LocalDeleteError):
    status_code = 409
    code = "LOCAL_FILE_IN_USE"
    delete_code = "file_in_use"
    default_message = "文件正在被播放或占用，请关闭后重试"


class LocalDeleteAccessDeniedError(LocalDeleteError):
    code = "LOCAL_DELETE_ACCESS_DENIED"
    delete_code = "delete_access_denied"
    default_message = "没有权限删除该文件"


class LocalIdentityBindingError(LocalDeleteError):
    status_code = 409
    code = "LOCAL_IDENTITY_BINDING_FAILED"
    delete_code = "identity_binding_failed"
    default_message = "文件身份已变化，已拒绝永久删除；请重新扫描本地来源"


class LocalDeleteNotVerifiedError(LocalDeleteError):
    status_code = 409
    code = "LOCAL_DELETE_NOT_VERIFIED"
    delete_code = "delete_not_verified"
    default_message = "无法确认文件已删除，已停止本次操作"


@dataclass(frozen=True)
class VerifiedLocalDelete:
    root: LocalRoot
    path: Path
    info: os.stat_result


def _plain(st):
    return not (stat.S_ISLNK(st.st_mode) or getattr(st, "st_file_attributes", 0) & 0x400)


def validate_root(raw: str) -> Path:
    if raw.startswith(("\\\\", "//")):
        raise SourcePathError()
    path = Path(raw)
    if not path.is_absolute() or ".." in path.parts or path == Path(path.anchor):
        raise SourcePathError()
    try:
        for part in (*reversed(path.parents), path):
            if part.name.casefold() in SKIP_DIRS or not _plain(part.lstat()):
                raise SourcePathError()
        if not path.is_dir():
            raise SourcePathError()
    except OSError:
        raise SourcePathError() from None
    return path


def safe_path(root: LocalRoot, relative: str, *, allow_missing_leaf: bool = False) -> Path:
    """每次读取重验全部组件；不追随 symlink/junction/reparse。

    ``allow_missing_leaf`` 仅用于删除阶段复验：当且仅当路径链合法、但叶文件本身
    已不存在时抛 ``FileNotFoundError``，以保留删除回执的 missing 语义；父级缺失、
    越界或链接/重解析点一律仍视为路径拒绝。
    """
    if not root.enabled:
        raise SourcePathError()
    base = validate_root(root.root_path)
    rel = PurePosixPath(relative.replace("\\", "/"))
    if rel.is_absolute() or not rel.parts or any(x in ("..", ".") or ":" in x for x in rel.parts):
        raise SourcePathError()
    current = base
    parts = rel.parts
    try:
        for index, part in enumerate(parts):
            current = current / part
            try:
                info = current.lstat()
            except FileNotFoundError:
                if allow_missing_leaf and index == len(parts) - 1:
                    raise
                raise SourcePathError() from None
            if not _plain(info):
                raise SourcePathError()
        current.resolve().relative_to(base.resolve())
    except FileNotFoundError:
        raise
    except (OSError, ValueError):
        raise SourcePathError() from None
    return current



def local_media_id(root_id: str, relative: str) -> str:
    key = root_id + "\0" + relative.replace("\\", "/")
    return "loc_" + hashlib.sha256(key.encode()).hexdigest()[:20]


def entity_fingerprint(mid, info):
    key = f"{mid}:{info.st_size}:{info.st_mtime_ns}:{info.st_dev}:{info.st_ino}"
    return hashlib.sha256(key.encode()).hexdigest()


def open_media(session, row):
    """返回验证后的只读句柄；句柄身份/最终路径复验防止组件检查后的换链。"""
    root = session.get(LocalRoot, row.library_id)
    if row.source_type != "local" or not root or not row.is_available:
        raise MediaNotFoundError("本地媒体不可用，请在电脑端重新扫描")
    path = safe_path(root, row.media_path or "")
    handle = None
    try:
        handle = path.open("rb")
        info = os.fstat(handle.fileno())
        if not stat.S_ISREG(info.st_mode) or (
            entity_fingerprint(row.media_id, info) != row.fingerprint
        ):
            raise ConflictError("本地文件已变化，请在电脑端重新扫描来源")
        if os.name == "nt":
            import ctypes
            import msvcrt
            from ctypes import wintypes
            get_final = ctypes.WinDLL("kernel32", use_last_error=True).GetFinalPathNameByHandleW
            get_final.argtypes = [wintypes.HANDLE, wintypes.LPWSTR, wintypes.DWORD, wintypes.DWORD]
            get_final.restype = wintypes.DWORD
            buffer = ctypes.create_unicode_buffer(32768)
            length = get_final(msvcrt.get_osfhandle(handle.fileno()), buffer, len(buffer), 0)
            if not length or length >= len(buffer):
                raise SourcePathError()
            final = buffer.value.removeprefix("\\\\?\\")
            if os.path.normcase(final) != os.path.normcase(str(path.absolute())):
                raise SourcePathError()
        checked = safe_path(root, row.media_path or "")
        if checked.stat().st_ino != info.st_ino:
            raise ConflictError("本地文件已变化，请重新扫描")
        return handle, info
    except Exception:
        if handle:
            handle.close()
        raise


def register_root(session, name: str, raw: str) -> LocalRoot:
    path = validate_root(raw)
    canonical = os.path.normcase(str(path.resolve()))
    existing = session.scalar(sa.select(LocalRoot).where(LocalRoot.root_path == canonical))
    if existing:
        return existing
    if session.scalar(sa.select(sa.func.count()).select_from(LocalRoot)) >= 64:
        raise SourcePathError("本地来源已达64个上限")
    # 不允许重叠登记，避免相同文件被多个本地根意外重复呈现。
    for other in session.scalars(sa.select(LocalRoot)):
        old = Path(other.root_path)
        if path.is_relative_to(old) or old.is_relative_to(path):
            raise SourcePathError("目录与已登记来源重叠，请选择独立根目录")
    root_id = "local_" + uuid.uuid4().hex
    root = LocalRoot(root_id=root_id, name=name, root_path=canonical)
    session.add(root)
    session.add(LibrarySelection(jellyfin_id=root_id, name=name, collection_type="mixed",
                                 source_type="local", selected=True))
    session.flush()
    return root


def schedule_scan(session, root_id: str) -> BackgroundTask:
    if not session.get(LocalRoot, root_id):
        raise MediaNotFoundError("本地来源不存在")
    # SQLite 的写锁串行化“查找或建立”避免并发重复排队。
    session.execute(
        sa.update(LocalRoot).where(LocalRoot.root_id == root_id).values(status="pending")
    )
    for task in session.scalars(sa.select(BackgroundTask).where(
            BackgroundTask.type == TASK_TYPE, BackgroundTask.status.in_(("pending", "running")))):
        if json.loads(task.params)["root_id"] == root_id:
            return task
    task = BackgroundTask(task_id=uuid.uuid4().hex, type=TASK_TYPE, status="pending",
                          params=json.dumps({"root_id": root_id}))
    session.add(task)
    session.flush()
    return task


def recover_interrupted_scans(database):
    """独占 Server 启动时重排中断任务；重新扫描可安全覆盖未完成批次。"""
    with database.session() as session:
        session.execute(sa.update(BackgroundTask).where(
            BackgroundTask.type.in_((TASK_TYPE, "local_thumbnail", "local_probe")),
            BackgroundTask.status == "running",
        ).values(status="pending", started_at=None))
        session.commit()


def _walk(root: Path):
    """迭代 scandir，不缓存整棵树；深度/总条目上限防止目录炸弹。"""
    count = 0
    stack = [(root, 0)]
    while stack:
        folder, depth = stack.pop()
        if depth > MAX_DEPTH:
            raise SourcePathError("目录层级超过扫描限制")
        # 进入目录前重新验根及组件，避免目录在排队期间被换成链接。
        relative_dir = folder.relative_to(root).as_posix()
        if relative_dir != ".":
            safe_path(LocalRoot(root_path=str(root), enabled=True), relative_dir)
        with os.scandir(folder) as entries:
            for entry in entries:
                count += 1
                if count > MAX_ENTRIES:
                    raise SourcePathError("目录超过单次扫描条目限制")
                info = entry.stat(follow_symlinks=False)
                if not _plain(info):
                    yield None, None
                    continue
                if stat.S_ISDIR(info.st_mode):
                    if entry.name.casefold() not in SKIP_DIRS:
                        stack.append((Path(entry.path), depth + 1))
                    yield None, None
                elif stat.S_ISREG(info.st_mode) and Path(entry.name).suffix.lower() in EXTENSIONS:
                    path = Path(entry.path)
                    # Windows DirEntry.stat 缓存的 st_ino/st_dev 为0；独立 lstat 才有句柄可比身份。
                    full_info = path.lstat()
                    if _plain(full_info) and stat.S_ISREG(full_info.st_mode):
                        yield path, full_info
                else:
                    # 无媒体目录也必须能及时检查取消。
                    yield None, None
        yield None, None


def _save_batch(database, task_id, root_id, generation, batch):
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        if not task or task.status != "running":
            root = session.get(LocalRoot, root_id)
            if root and task:
                root.status = task.status
                session.commit()
            return None
        root = session.get(LocalRoot, root_id)
        if not root or not root.enabled:
            return None
        ids = [item.media_id for item in batch]
        existing = {r.media_id: r for r in session.scalars(sa.select(MediaCacheIndex).where(
            MediaCacheIndex.media_id.in_(ids)))}
        changed = [
            i for i in batch
            if i.media_id not in existing or existing[i.media_id].fingerprint != i.fingerprint
        ]
        upsert_media_items(session, changed, generation=generation)
        if ids:
            session.execute(sa.update(MediaCacheIndex).where(MediaCacheIndex.media_id.in_(ids)).values(
                sync_generation=generation, is_available=True, last_seen_at=utc_now()))
        session.commit()
        return len(changed)


def run_scan(database, task_id):
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        if not task or task.status != "running":
            return
        root_id = json.loads(task.params)["root_id"]
        root = session.get(LocalRoot, root_id)
        raw_root = root.root_path
    generation = uuid.uuid4().hex
    changed = seen = checks = 0
    batch = []
    offline = False
    try:
        try:
            base = validate_root(raw_root)
        except SourcePathError:
            offline = True
            raise
        for path, info in _walk(base):
            checks += 1
            if path is not None:
                relative = path.relative_to(base).as_posix()
                mid = local_media_id(root_id, relative)
                fingerprint = entity_fingerprint(mid, info)
                batch.append(MediaItem(media_id=mid, jellyfin_id="local:" + mid,
                    library_id=root_id, source_type="local", name=path.name,
                    media_type=EXTENSIONS[path.suffix.lower()], container=path.suffix[1:].lower(),
                    media_path=relative, size_bytes=info.st_size, fingerprint=fingerprint,
                    created_at=datetime.fromtimestamp(info.st_ctime, UTC).replace(tzinfo=None),
                    modified_at=datetime.fromtimestamp(info.st_mtime, UTC).replace(tzinfo=None)))
                seen += 1
            if len(batch) >= BATCH_SIZE or checks >= BATCH_SIZE:
                delta = _save_batch(database, task_id, root_id, generation, batch)
                if delta is None:
                    return
                changed += delta
                batch, checks = [], 0
        delta = _save_batch(database, task_id, root_id, generation, batch)
        if delta is None:
            return
        changed += delta
        with database.session() as session:
            task = session.get(BackgroundTask, task_id)
            if task.status != "running":
                return
            session.execute(sa.update(MediaCacheIndex).where(
                MediaCacheIndex.library_id == root_id,
                sa.or_(MediaCacheIndex.sync_generation != generation,
                       MediaCacheIndex.sync_generation.is_(None)))
                .values(is_available=False))
            root = session.get(LocalRoot, root_id)
            root.status, root.last_scan_at = "available", utc_now()
            task.status, task.progress, task.finished_at = "succeeded", 100, utc_now()
            task.result = json.dumps({"seen": seen, "changed": changed})
            session.commit()
    except Exception:  # 中断/失败均落安全终态，绝不留running孤儿或记录源路径。
        with database.session() as session:
            task = session.get(BackgroundTask, task_id)
            if task.status != "running":
                return
            task.status, task.error, task.finished_at = (
                "failed", "local_source_unavailable", utc_now()
            )
            root = session.get(LocalRoot, root_id)
            root.status = "offline" if offline else "scan_failed"
            if offline:
                session.execute(
                    sa.update(MediaCacheIndex)
                    .where(MediaCacheIndex.library_id == root_id)
                    .values(is_available=False))

            session.commit()


def deletion_failure_code(exc: BaseException) -> str:
    """把本地删除拒绝异常映射为稳定失败码；不返回绝对路径或原始 OSError 文本。"""
    code = getattr(exc, "delete_code", None)
    if code:
        return str(code)
    if isinstance(exc, SourcePathError):
        return "path_rejected"
    if isinstance(exc, ConflictError):
        return "file_identity_changed"
    if isinstance(exc, MediaNotFoundError):
        return "media_identity_unavailable"
    if isinstance(exc, PermissionError):
        return "file_in_use"
    if isinstance(exc, ForbiddenError):
        return "forbidden"
    return "delete_failed"


def _resolve_deletable_local(
    session, media, *, allow_missing: bool = False
) -> VerifiedLocalDelete | None:
    """本地源永久删除前的完整复验(Jellyfin 走原有路径，不经过这里)。

    校验顺序：登记根存在且 enabled → 相对路径落到根内且整链无链接/重解析点/越界 →
    叶必须是普通文件 → ``open_media`` 复用既有句柄身份、指纹与最终路径复验。

    返回经复验的路径及句柄身份；``allow_missing=True`` 且叶文件已不存在时返回 ``None``
    (供 commit 生成 missing 回执)，其余拒绝一律抛带 ``delete_code`` 的 AppError，
    由删除队列映射为不含路径的稳定失败码。
    """
    if media is None or media.source_type != "local" or not media.media_path:
        raise MediaNotFoundError("本地媒体索引不可用，请重新扫描本地来源")
    root = session.get(LocalRoot, media.library_id)
    if root is None:
        raise LocalRootMissingError()
    if not root.enabled:
        raise LocalRootDisabledError()
    try:
        path = safe_path(root, media.media_path, allow_missing_leaf=allow_missing)
        info = path.lstat()
    except FileNotFoundError:
        if allow_missing:
            return None
        raise MediaNotFoundError("本地文件不存在，可能已在电脑端被移动或删除") from None
    except OSError as exc:
        raise SourcePathError() from exc
    if not _plain(info) or not stat.S_ISREG(info.st_mode):
        raise SourcePathError()
    try:
        handle, verified_info = open_media(session, media)
    except FileNotFoundError:
        if allow_missing:
            return None
        raise MediaNotFoundError("本地文件不存在，可能已在电脑端被移动或删除") from None
    handle.close()
    return VerifiedLocalDelete(root=root, path=path, info=verified_info)


def resolve_deletable_local(session, media, *, allow_missing: bool = False) -> Path | None:
    """兼容现有调用方：只返回完成身份复验的路径。"""
    target = _resolve_deletable_local(session, media, allow_missing=allow_missing)
    return None if target is None else target.path


def _close_windows_handle(close_handle, handle) -> bool:
    """单独封装生产 CloseHandle，便于故障注入但仍由同一路径执行。"""
    return bool(close_handle(handle))


def _windows_delete_bound(
    target: VerifiedLocalDelete, *, force_legacy_disposition: bool = False
) -> str:
    """用同一 Windows 句柄完成身份绑定、根边界复验和 disposition 删除。"""
    import ctypes
    from ctypes import wintypes

    generic_read = 0x80000000
    delete_access = 0x00010000
    share_all = 0x00000001 | 0x00000002 | 0x00000004
    open_existing = 3
    open_reparse_point = 0x00200000
    file_disposition_info = 4
    file_disposition_info_ex = 21
    file_disposition_delete = 0x00000001
    file_disposition_posix_semantics = 0x00000002
    error_file_not_found = 2
    unsupported_posix_errors = {50, 87}
    sharing_errors = {32, 33}
    error_access_denied = 5
    reparse_attribute = 0x00000400

    class ByHandleFileInformation(ctypes.Structure):
        _fields_ = [
            ("dwFileAttributes", wintypes.DWORD),
            ("ftCreationTime", wintypes.FILETIME),
            ("ftLastAccessTime", wintypes.FILETIME),
            ("ftLastWriteTime", wintypes.FILETIME),
            ("dwVolumeSerialNumber", wintypes.DWORD),
            ("nFileSizeHigh", wintypes.DWORD),
            ("nFileSizeLow", wintypes.DWORD),
            ("nNumberOfLinks", wintypes.DWORD),
            ("nFileIndexHigh", wintypes.DWORD),
            ("nFileIndexLow", wintypes.DWORD),
        ]

    class FileDispositionInformation(ctypes.Structure):
        _fields_ = [("DeleteFile", wintypes.BOOLEAN)]

    class FileDispositionInformationEx(ctypes.Structure):
        _fields_ = [("Flags", wintypes.DWORD)]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = [
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    ]
    create_file.restype = wintypes.HANDLE
    get_info = kernel32.GetFileInformationByHandle
    get_info.argtypes = [wintypes.HANDLE, ctypes.POINTER(ByHandleFileInformation)]
    get_info.restype = wintypes.BOOL
    get_final = kernel32.GetFinalPathNameByHandleW
    get_final.argtypes = [wintypes.HANDLE, wintypes.LPWSTR, wintypes.DWORD, wintypes.DWORD]
    get_final.restype = wintypes.DWORD
    set_info = kernel32.SetFileInformationByHandle
    set_info.argtypes = [wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID, wintypes.DWORD]
    set_info.restype = wintypes.BOOL
    close_handle = kernel32.CloseHandle
    close_handle.argtypes = [wintypes.HANDLE]
    close_handle.restype = wintypes.BOOL

    handle = create_file(
        str(target.path),
        generic_read | delete_access,
        share_all,
        None,
        open_existing,
        open_reparse_point,
        None,
    )
    if handle == wintypes.HANDLE(-1).value:
        error = ctypes.get_last_error()
        if error == error_file_not_found:
            raise FileNotFoundError() from None
        if error in sharing_errors:
            raise LocalFileInUseError() from None
        if error == error_access_denied:
            raise LocalDeleteAccessDeniedError() from None
        raise LocalIdentityBindingError() from None

    disposition_mode: str | None = None
    close_succeeded = False
    try:
        info = ByHandleFileInformation()
        if not get_info(handle, ctypes.byref(info)):
            raise LocalIdentityBindingError()
        if info.dwFileAttributes & reparse_attribute:
            raise SourcePathError()
        file_index = (int(info.nFileIndexHigh) << 32) | int(info.nFileIndexLow)
        identity = (int(info.dwVolumeSerialNumber), file_index)
        # CPython 3.12 的 Windows st_dev 来自 64-bit VolumeSerialNumber；
        # BY_HANDLE_FILE_INFORMATION 只公开其低 32 位卷序列号。
        expected = (int(target.info.st_dev) & 0xFFFFFFFF, int(target.info.st_ino))
        if identity != expected:
            raise LocalIdentityBindingError()

        buffer = ctypes.create_unicode_buffer(32768)
        length = get_final(handle, buffer, len(buffer), 0)
        if not length or length >= len(buffer):
            raise LocalIdentityBindingError()
        final = buffer.value
        if final.startswith("\\\\?\\UNC\\"):
            final = "\\\\" + final[8:]
        else:
            final = final.removeprefix("\\\\?\\")
        final_path = Path(final)
        # 用登记根的词法绝对路径比较；此处不能 resolve 后跟随刚被换入的 junction。
        root_path = Path(target.root.root_path).absolute()
        try:
            final_path.relative_to(root_path)
        except ValueError:
            raise SourcePathError() from None
        if os.path.normcase(str(final_path)) != os.path.normcase(str(target.path.absolute())):
            raise SourcePathError()

        use_legacy = force_legacy_disposition
        if not use_legacy:
            disposition_ex = FileDispositionInformationEx(
                file_disposition_delete | file_disposition_posix_semantics
            )
            if set_info(
                handle,
                file_disposition_info_ex,
                ctypes.byref(disposition_ex),
                ctypes.sizeof(disposition_ex),
            ):
                disposition_mode = "posix"
            else:
                error = ctypes.get_last_error()
                if error in unsupported_posix_errors:
                    use_legacy = True
                elif error in sharing_errors:
                    raise LocalFileInUseError() from None
                elif error == error_access_denied:
                    raise LocalDeleteAccessDeniedError() from None
                else:
                    raise LocalDeleteNotVerifiedError() from None

        if use_legacy:
            disposition = FileDispositionInformation(True)
            if not set_info(
                handle,
                file_disposition_info,
                ctypes.byref(disposition),
                ctypes.sizeof(disposition),
            ):
                error = ctypes.get_last_error()
                if error in sharing_errors:
                    raise LocalFileInUseError() from None
                if error == error_access_denied:
                    raise LocalDeleteAccessDeniedError() from None
                raise LocalDeleteNotVerifiedError() from None
            disposition_mode = "legacy"
    finally:
        close_succeeded = _close_windows_handle(close_handle, handle)
        if not close_succeeded:
            # 不记录路径/句柄值。POSIX 路径已由 lstat 证明名字消失时仍是 success；
            # 传统路径或未确认消失的 POSIX 路径保持 pending，交给恢复只观察。
            logger.warning("本地删除句柄关闭失败；保留已确认或可恢复的删除语义")
    if disposition_mode is None:
        raise LocalDeleteNotVerifiedError()
    if disposition_mode == "legacy":
        return "pending"

    # POSIX disposition 只有在关闭删除句柄后再由名字复验为不存在，才算物理删除已确认。
    try:
        os.lstat(target.path)
    except FileNotFoundError:
        return "success"
    except OSError:
        return "pending"
    return "pending"


def _posix_delete_bound(target: VerifiedLocalDelete) -> None:
    """POSIX 尽力绑定身份后删除；lstat 与 remove 之间仍存在无法消除的残余竞态。"""
    try:
        current = os.lstat(target.path)
    except FileNotFoundError:
        raise
    if not _plain(current) or not stat.S_ISREG(current.st_mode):
        raise SourcePathError()
    if (current.st_dev, current.st_ino) != (target.info.st_dev, target.info.st_ino):
        raise LocalIdentityBindingError()
    try:
        os.remove(target.path)
    except PermissionError as exc:
        raise LocalFileInUseError() from exc
    try:
        os.lstat(target.path)
    except FileNotFoundError:
        return
    except OSError:
        raise LocalDeleteNotVerifiedError() from None
    raise LocalDeleteNotVerifiedError()


def delete_local_media(
    session,
    media,
    *,
    allow_missing: bool = False,
    before_identity_binding: Callable[[], None] | None = None,
    force_legacy_disposition: bool = False,
) -> tuple[str, int] | None:
    """复验后按身份绑定删除；返回 ``(success|pending, bytes)``，missing 返回 None。"""
    target = _resolve_deletable_local(session, media, allow_missing=allow_missing)
    if target is None:
        return None
    if before_identity_binding is not None:
        before_identity_binding()
    try:
        # hook 后重验路径链，保证父目录换成 junction/reparse 时先 fail-closed。
        checked = safe_path(
            target.root, media.media_path or "", allow_missing_leaf=allow_missing
        )
    except FileNotFoundError:
        if allow_missing:
            return None
        raise
    if checked != target.path:
        raise SourcePathError()
    try:
        if os.name == "nt":
            result = _windows_delete_bound(
                target, force_legacy_disposition=force_legacy_disposition
            )
        else:
            _posix_delete_bound(target)
            result = "success"
    except FileNotFoundError:
        if allow_missing:
            return None
        raise
    return result, int(target.info.st_size)


def require_writable_source(session, media):
    """入队前来源校验：Jellyfin 保持原能力判断；本地源要求能解析到当前真实文件。"""
    if media is None:
        raise MediaNotFoundError()
    if not can_delete(media.source_type):
        raise ForbiddenError("该来源为只读，禁止永久删除")
    if media.source_type == "local":
        # 提前拒绝不可删除项(根停用/缺失、路径越界、文件已变化或不存在)，
        # 避免"入队成功、最终确认才失败"。
        resolve_deletable_local(session, media)

