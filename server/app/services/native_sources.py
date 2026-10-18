"""原生只读来源：根身份、路径边界和现有单队列增量扫描。"""
from __future__ import annotations

import hashlib
import json
import os
import stat
import uuid
from datetime import UTC, datetime
from pathlib import Path, PurePosixPath

import sqlalchemy as sa

from app.adapters.jellyfin.models import MediaItem
from app.core.errors import ConflictError, ForbiddenError, MediaNotFoundError, ValidationFailedError
from app.db.models import BackgroundTask, LibrarySelection, LocalRoot, MediaCacheIndex, utc_now
from app.services.media_index import upsert_media_items

TASK_TYPE = "local_scan"
EXTENSIONS = {".jpg": "image", ".jpeg": "image", ".png": "image", ".webp": "image",
              ".mp4": "video", ".mkv": "video", ".mov": "video", ".webm": "video"}
SKIP_DIRS = {"system volume information", "$recycle.bin", "windows", "program files",
             "program files (x86)", "programdata", ".git", ".venv"}
BATCH_SIZE = 128
MAX_ENTRIES = 250_000
MAX_DEPTH = 128


class SourcePathError(ValidationFailedError):
    default_message = "本地来源路径无效、不可用或包含链接，请检查电脑端目录"


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


def safe_path(root: LocalRoot, relative: str) -> Path:
    """每次读取重验全部组件；不追随 symlink/junction/reparse。"""
    if not root.enabled:
        raise SourcePathError()
    base = validate_root(root.root_path)
    rel = PurePosixPath(relative.replace("\\", "/"))
    if rel.is_absolute() or not rel.parts or any(x in ("..", ".") or ":" in x for x in rel.parts):
        raise SourcePathError()
    current = base
    try:
        for part in rel.parts:
            current = current / part
            if not _plain(current.lstat()):
                raise SourcePathError()
        current.resolve().relative_to(base.resolve())
    except (OSError, ValueError):
        raise SourcePathError() from None
    return current


def local_media_id(root_id: str, relative: str) -> str:
    key = root_id + "\0" + relative.replace("\\", "/").casefold()
    return "loc_" + hashlib.sha256(key.encode()).hexdigest()[:20]


def entity_fingerprint(mid, info):
    return hashlib.sha256(f"{mid}:{info.st_size}:{info.st_mtime_ns}:{info.st_dev}:{info.st_ino}".encode()).hexdigest()


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
        if not stat.S_ISREG(info.st_mode) or entity_fingerprint(row.media_id, info) != row.fingerprint:
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
    session.execute(sa.update(LocalRoot).where(LocalRoot.root_id == root_id).values(status="pending"))
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
        session.execute(sa.update(BackgroundTask).where(BackgroundTask.type.in_((TASK_TYPE, "local_thumbnail")),
            BackgroundTask.status == "running").values(status="pending", started_at=None))
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
                    continue
                if stat.S_ISDIR(info.st_mode):
                    if entry.name.casefold() not in SKIP_DIRS:
                        stack.append((Path(entry.path), depth + 1))
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
            return None
        root = session.get(LocalRoot, root_id)
        if not root or not root.enabled:
            return None
        ids = [item.media_id for item in batch]
        existing = {r.media_id: r for r in session.scalars(sa.select(MediaCacheIndex).where(
            MediaCacheIndex.media_id.in_(ids)))}
        changed = [i for i in batch if i.media_id not in existing or existing[i.media_id].fingerprint != i.fingerprint]
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
            session.execute(sa.update(MediaCacheIndex).where(MediaCacheIndex.library_id == root_id,
                sa.or_(MediaCacheIndex.sync_generation != generation, MediaCacheIndex.sync_generation.is_(None)))
                .values(is_available=False))
            root = session.get(LocalRoot, root_id)
            root.status, root.last_scan_at = "available", utc_now()
            task.status, task.progress, task.finished_at = "succeeded", 100, utc_now()
            task.result = json.dumps({"seen": seen, "changed": changed})
            session.commit()
    except (OSError, SourcePathError):
        with database.session() as session:
            task = session.get(BackgroundTask, task_id)
            if task.status != "running":
                return
            task.status, task.error, task.finished_at = "failed", "local_source_unavailable", utc_now()
            root = session.get(LocalRoot, root_id)
            root.status = "offline" if offline else "scan_failed"
            if offline:
                session.execute(sa.update(MediaCacheIndex).where(MediaCacheIndex.library_id == root_id)
                                .values(is_available=False))
            session.commit()


def require_writable_source(media):
    if media and media.source_type != "jellyfin":
        raise ForbiddenError("本地来源为只读，禁止永久删除")
