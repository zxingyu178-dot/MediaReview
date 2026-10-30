"""待删除队列服务: 入队(未删) -> 确认(commit 后才真实删除)。

遵循 AGENTS.md: 删除必须两步(先入待删除,commit 才永久删除),禁止点击即删。
删除目标为 media_cache_index 持久化的真实文件路径;删除成功后再清理索引/收藏。

Task D 起最终删除采用一次性 nonce 合同:
- ``prepare_commit`` 生成绑定当前队列快照的 nonce(仅一次、10 分钟过期);
- ``commit_with_nonce`` 只删除快照内的媒体(TOCTOU 防护),逐项独立处理并写审计。
"""

from __future__ import annotations

import json
import logging
import os
import secrets
import stat
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import Enum
from pathlib import Path

import sqlalchemy as sa
from sqlalchemy.orm import Session

from app.core.errors import AppError
from app.db.models import (
    DeleteCommitNonce,
    DeleteQueue,
    Favorite,
    LocalRoot,
    MediaCacheIndex,
    MediaLibraryMembership,
    utc_now,
)
from app.services import audit
from app.services import media_index as mi

# 删除确认 nonce 默认有效期(秒)
NONCE_TTL_SECONDS = 10 * 60
logger = logging.getLogger(__name__)


class NonceError(Exception):
    """删除确认 nonce 无效的基类。"""


class NonceMissingError(NonceError):
    """nonce 不存在或已失效。"""


class NonceReusedError(NonceError):
    """nonce 已被使用。"""


class NonceExpiredError(NonceError):
    """nonce 已过期。"""


@dataclass(frozen=True)
class DeleteCommitPrep:
    """删除确认的预备信息:一次性 nonce + 队列快照摘要。"""

    nonce: str
    expires_at: datetime
    count: int
    total_bytes: int
    media_ids: list[str]
    unknown_size_count: int = 0


@dataclass(frozen=True)
class _SnapshotEntry:
    media_id: str
    queue_token: str | None


@dataclass(frozen=True)
class _ClaimResult:
    entry: _SnapshotEntry
    action: str


class _RecoveryObservation(Enum):
    MISSING = "missing"
    SAME_ORIGINAL_PRESENT = "same_original_present"
    REPLACED_OR_ORIGINAL_GONE = "replaced_or_original_gone"
    UNKNOWN = "unknown"


class DeleteReceipt(dict[str, str]):
    """保留旧字典调用合同，并携带本次真实删除字节数。"""

    def __init__(self) -> None:
        super().__init__()
        self.deleted_bytes = 0

    @property
    def deleted_count(self) -> int:
        return sum(value == "success" for value in self.values())

    @property
    def missing_count(self) -> int:
        return sum(value == "missing" for value in self.values())

    @property
    def failed_count(self) -> int:
        return sum(value == "failed" for value in self.values())

def enqueue(session: Session, media_id: str, *, size_bytes: int | None = None) -> bool:
    """加入待删除队列。已 committed 的不再入队。返回是否为新增入队。"""
    from app.db.models import MediaCacheIndex
    from app.services.native_sources import require_writable_source

    require_writable_source(session, session.get(MediaCacheIndex, media_id))
    row = session.get(DeleteQueue, media_id)
    if row is not None:
        return False
    session.add(
        DeleteQueue(
            media_id=media_id,
            queue_token=secrets.token_hex(16),
            size_bytes=size_bytes,
            status="pending",
        )
    )
    session.flush()
    audit.log_action(session, "delete_enqueue", media_id)
    return True


def dequeue(session: Session, media_id: str) -> bool:
    """撤销尚未执行的 pending/failed 项；committing 不可撤销。"""
    row = session.get(DeleteQueue, media_id)
    if row is None or row.status not in ("pending", "failed"):
        return False
    session.delete(row)
    session.flush()
    audit.log_action(session, "delete_dequeue", media_id)
    return True


def list_queue(session: Session) -> list[DeleteQueue]:
    backfill_pending_sizes(session)
    return list(session.scalars(sa.select(DeleteQueue).order_by(DeleteQueue.added_at.asc())).all())


def backfill_pending_sizes(session: Session) -> None:
    """仅从持久索引补未知 pending 尺寸，绝不访问文件系统。"""
    from app.db.models import MediaCacheIndex

    size = (
        sa.select(MediaCacheIndex.size_bytes)
        .where(
            MediaCacheIndex.media_id == DeleteQueue.media_id,
            MediaCacheIndex.size_bytes > 0,
        )
        .scalar_subquery()
    )
    session.execute(
        sa.update(DeleteQueue)
        .where(
            DeleteQueue.status == "pending",
            sa.or_(DeleteQueue.size_bytes.is_(None), DeleteQueue.size_bytes <= 0),
            size.is_not(None),
        )
        .values(size_bytes=size),
        execution_options={"synchronize_session": "fetch"},
    )


def pending_summary(session: Session, *, include_unknown: bool = False) -> tuple:
    """可最终删除队列摘要 ``(count, total_bytes)``: 只统计 pending。

    Stage 8C §11: Organize 首页只需要数字,不得为此拉取整个队列;
    单条 SQL 聚合;failed 项(删除失败仍留在队列)不计入"预计释放"。
    """
    backfill_pending_sizes(session)
    count, total, unknown = session.execute(
        sa.select(
            sa.func.count(DeleteQueue.media_id),
            sa.func.coalesce(sa.func.sum(DeleteQueue.size_bytes), 0),
            sa.func.coalesce(
                sa.func.sum(
                    sa.case(
                        (sa.or_(DeleteQueue.size_bytes.is_(None), DeleteQueue.size_bytes <= 0), 1),
                        else_=0,
                    )
                ),
                0,
            ),
        ).where(DeleteQueue.status == "pending")
    ).one()
    result = (int(count), int(total or 0), int(unknown))
    return result if include_unknown else result[:2]


# 拒绝删除的身份校验详情前缀,用于区分失败原因(写入审计与队列 error)
_GUARD_LIBRARY = "library_not_allowed"
_GUARD_SIZE = "file_size_changed"
_GUARD_MTIME = "file_modified_changed"
_GUARD_STAT = "stat_failed"


class _RecoveryPathUnavailableError(OSError):
    """恢复观察时卷根或父链不可见，不能据此证明叶文件缺失。"""


@dataclass(frozen=True)
class _WindowsRecoveryFileInfo:
    size: int
    mtime_ms: int


def _validated_deletion_path(media_path: str) -> Path:
    path = Path(media_path)
    if not path.is_absolute() or ".." in path.parts or media_path.startswith(("\\\\", "//")):
        raise ValueError(f"{_GUARD_STAT}:unsafe_path")
    return path


def _lstat_deletion_component(component: Path) -> os.stat_result:
    info = os.lstat(component)
    if stat.S_ISLNK(info.st_mode) or (
        getattr(info, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    ):
        raise ValueError(f"{_GUARD_STAT}:link_or_reparse_point")
    return info


def _lstat_deletion_path(media_path: str) -> os.stat_result:
    """检查整条路径链，避免跟随叶链接或父目录 junction 删除目标文件。"""
    path = _validated_deletion_path(media_path)
    leaf_stat: os.stat_result | None = None
    for component in [*reversed(path.parents), path]:
        leaf_stat = _lstat_deletion_component(component)
    assert leaf_stat is not None
    if not stat.S_ISREG(leaf_stat.st_mode):
        raise ValueError(f"{_GUARD_STAT}:not_a_regular_file")
    return leaf_stat


def _lstat_recovery_path(media_path: str) -> os.stat_result:
    """先确认父链可观察，再单独 lstat 叶，以区分父链不可见与叶缺失。"""
    path = _validated_deletion_path(media_path)
    try:
        for component in reversed(path.parents):
            _lstat_deletion_component(component)
    except OSError as exc:
        raise _RecoveryPathUnavailableError from exc

    leaf_stat = _lstat_deletion_component(path)
    if not stat.S_ISREG(leaf_stat.st_mode):
        raise ValueError(f"{_GUARD_STAT}:not_a_regular_file")
    return leaf_stat


def _windows_recovery_file_info(
    media_path: str,
    *,
    after_parent_validation: Callable[[], None] | None = None,
    _ntstatus_override: int | None = None,
) -> _WindowsRecoveryFileInfo:
    """绑定父目录句柄并相对观察叶，避免路径父链与叶观察间的 TOCTOU。"""
    import ctypes
    from ctypes import wintypes

    file_list_directory = 0x00000001
    file_traverse = 0x00000020
    file_read_attributes = 0x00000080
    synchronize = 0x00100000
    share_all = 0x00000001 | 0x00000002 | 0x00000004
    open_existing = 3
    backup_semantics = 0x02000000
    win32_open_reparse_point = 0x00200000
    nt_file_open = 1
    nt_file_non_directory_file = 0x00000040
    nt_file_open_reparse_point = 0x00200000
    obj_case_insensitive = 0x00000040
    status_object_name_not_found = 0xC0000034
    reparse_attribute = 0x00000400
    directory_attribute = 0x00000010
    windows_epoch_ticks = 116_444_736_000_000_000

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

    class UnicodeString(ctypes.Structure):
        _fields_ = [
            ("Length", wintypes.USHORT),
            ("MaximumLength", wintypes.USHORT),
            ("Buffer", wintypes.LPWSTR),
        ]

    class ObjectAttributes(ctypes.Structure):
        _fields_ = [
            ("Length", wintypes.ULONG),
            ("RootDirectory", wintypes.HANDLE),
            ("ObjectName", ctypes.POINTER(UnicodeString)),
            ("Attributes", wintypes.ULONG),
            ("SecurityDescriptor", wintypes.LPVOID),
            ("SecurityQualityOfService", wintypes.LPVOID),
        ]

    class IoStatusBlock(ctypes.Structure):
        _fields_ = [("StatusOrPointer", wintypes.LPVOID), ("Information", ctypes.c_size_t)]

    path = _validated_deletion_path(media_path)
    try:
        parent_stat: os.stat_result | None = None
        for component in reversed(path.parents):
            parent_stat = _lstat_deletion_component(component)
    except OSError as exc:
        raise _RecoveryPathUnavailableError from exc
    if parent_stat is None or not stat.S_ISDIR(parent_stat.st_mode):
        raise _RecoveryPathUnavailableError()

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    ntdll = ctypes.WinDLL("ntdll")
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
    close_handle = kernel32.CloseHandle
    close_handle.argtypes = [wintypes.HANDLE]
    close_handle.restype = wintypes.BOOL
    nt_create_file = ntdll.NtCreateFile
    nt_create_file.argtypes = [
        ctypes.POINTER(wintypes.HANDLE),
        wintypes.DWORD,
        ctypes.POINTER(ObjectAttributes),
        ctypes.POINTER(IoStatusBlock),
        wintypes.LPVOID,
        wintypes.ULONG,
        wintypes.ULONG,
        wintypes.ULONG,
        wintypes.ULONG,
        wintypes.LPVOID,
        wintypes.ULONG,
    ]
    nt_create_file.restype = wintypes.LONG

    invalid_handle = wintypes.HANDLE(-1).value
    parent_handle = create_file(
        str(path.parent),
        file_list_directory | file_traverse | synchronize,
        share_all,
        None,
        open_existing,
        backup_semantics | win32_open_reparse_point,
        None,
    )
    if parent_handle == invalid_handle:
        raise _RecoveryPathUnavailableError()

    leaf_handle = wintypes.HANDLE()
    try:
        parent_info = ByHandleFileInformation()
        if not get_info(parent_handle, ctypes.byref(parent_info)):
            raise _RecoveryPathUnavailableError()
        parent_identity = (
            int(parent_info.dwVolumeSerialNumber),
            (int(parent_info.nFileIndexHigh) << 32) | int(parent_info.nFileIndexLow),
        )
        expected_parent_identity = (
            int(parent_stat.st_dev) & 0xFFFFFFFF,
            int(parent_stat.st_ino),
        )
        if parent_identity != expected_parent_identity:
            raise _RecoveryPathUnavailableError()

        if after_parent_validation is not None:
            after_parent_validation()

        leaf_buffer = ctypes.create_unicode_buffer(path.name)
        leaf_name = UnicodeString(
            len(path.name) * ctypes.sizeof(ctypes.c_wchar),
            ctypes.sizeof(leaf_buffer),
            ctypes.cast(leaf_buffer, wintypes.LPWSTR),
        )
        object_attributes = ObjectAttributes(
            ctypes.sizeof(ObjectAttributes),
            parent_handle,
            ctypes.pointer(leaf_name),
            obj_case_insensitive,
            None,
            None,
        )
        io_status = IoStatusBlock()
        status = nt_create_file(
            ctypes.byref(leaf_handle),
            file_read_attributes | synchronize,
            ctypes.byref(object_attributes),
            ctypes.byref(io_status),
            None,
            0,
            share_all,
            nt_file_open,
            nt_file_non_directory_file | nt_file_open_reparse_point,
            None,
            0,
        )
        unsigned_status = int(status) & 0xFFFFFFFF
        if _ntstatus_override is not None:
            # 测试注入：仅用于验证失败分支的 fail-closed 分类，不改变生产调用路径。
            status = ctypes.c_long(_ntstatus_override).value
            unsigned_status = int(status) & 0xFFFFFFFF
        if unsigned_status == status_object_name_not_found:
            # 绑定父句柄下只认 object name not found 为叶缺失证据；
            # path not found 及其他非成功状态一律不可判定。
            raise FileNotFoundError() from None
        if status < 0:
            raise _RecoveryPathUnavailableError()

        leaf_info = ByHandleFileInformation()
        if not get_info(leaf_handle, ctypes.byref(leaf_info)):
            raise _RecoveryPathUnavailableError()
        if leaf_info.dwFileAttributes & (reparse_attribute | directory_attribute):
            raise ValueError(f"{_GUARD_STAT}:link_reparse_or_directory")
        size = (int(leaf_info.nFileSizeHigh) << 32) | int(leaf_info.nFileSizeLow)
        mtime_ticks = (int(leaf_info.ftLastWriteTime.dwHighDateTime) << 32) | int(
            leaf_info.ftLastWriteTime.dwLowDateTime
        )
        return _WindowsRecoveryFileInfo(
            size=size,
            mtime_ms=(mtime_ticks - windows_epoch_ticks) // 10_000,
        )
    finally:
        if leaf_handle.value not in (None, invalid_handle):
            close_handle(leaf_handle)
        close_handle(parent_handle)


def _mtime_ms(media) -> int | None:
    """把入库的 modified_at 转为毫秒时间戳(naive UTC)。"""
    if media.modified_at is None:
        return None
    import calendar

    # 列存的 modified_at 为 naive UTC
    from datetime import datetime

    value = media.modified_at
    if isinstance(value, datetime):
        return int(calendar.timegm(value.utctimetuple()) * 1000)
    return None


def _deletion_guard(
    session: Session, media, media_path: str, *, file_stat: os.stat_result | None = None
) -> str | None:
    """在真实删除前校验媒体身份,返回拒绝原因;通过校验返回 None。

    校验项:
    - 媒体仍属于当前允许管理的 Jellyfin Library
    - 当前文件是普通文件且存在
    - 文件大小/mtime 与入库索引一致(身份未变,防止删错被替换/修改后的文件)

    任何一项不满足即拒绝,禁止仅凭历史 media_path 直接 os.remove。
    """
    if not session.scalar(
        sa.select(MediaCacheIndex.media_id).where(
            MediaCacheIndex.media_id == media.media_id,
            mi.library_membership_condition(mi.selected_library_ids(session)),
        )
    ):
        return _GUARD_LIBRARY

    try:
        st = file_stat if file_stat is not None else os.stat(media_path)
    except FileNotFoundError:
        raise
    except OSError as exc:
        return f"{_GUARD_STAT}:{exc}"

    if not stat.S_ISREG(st.st_mode):
        return f"{_GUARD_STAT}:not_a_regular_file"

    if media.size_bytes is not None and st.st_size != media.size_bytes:
        return _GUARD_SIZE

    stored_mtime_ms = _mtime_ms(media)
    if stored_mtime_ms is not None:
        actual_ms = int(st.st_mtime * 1000)
        # 容差 3s,容忍 DB/文件系统时间精度差异,同时仍能识别被替换/修改
        if abs(actual_ms - stored_mtime_ms) > 3000:
            return _GUARD_MTIME
    return None


def prepare_commit(session: Session, *, owner_device_id: str | None = None) -> DeleteCommitPrep:
    """生成一次性删除确认 nonce,绑定当前 pending 队列快照。

    返回的 nonce 只能使用一次、10 分钟过期;media_ids 快照防止 nonce 生成后
    新入队的媒体被"顺手"删除(TOCTOU)。
    """
    backfill_pending_sizes(session)
    pending = list(
        session.scalars(sa.select(DeleteQueue).where(DeleteQueue.status == "pending")).all()
    )
    media_ids = [row.media_id for row in pending]
    snapshot = [{"media_id": row.media_id, "queue_token": row.queue_token} for row in pending]
    total_bytes = sum(row.size_bytes or 0 for row in pending)
    nonce = secrets.token_hex(16)
    expires_at = utc_now() + timedelta(seconds=NONCE_TTL_SECONDS)
    session.add(
        DeleteCommitNonce(
            nonce=nonce,
            expires_at=expires_at,
            used=False,
            media_ids_json=json.dumps(snapshot),
            total_bytes=total_bytes,
            owner_device_id=owner_device_id,
            receipt_state="prepared",
        )
    )
    session.flush()
    return DeleteCommitPrep(
        nonce=nonce,
        expires_at=expires_at,
        count=len(media_ids),
        total_bytes=total_bytes,
        media_ids=media_ids,
        unknown_size_count=sum(row.size_bytes is None or row.size_bytes <= 0 for row in pending),
    )


def _decode_snapshot(raw: str) -> list[_SnapshotEntry]:
    """读取新旧 nonce 快照；旧格式缺少 token，后续按不匹配 fail-closed。"""
    result: list[_SnapshotEntry] = []
    for item in json.loads(raw):
        if isinstance(item, str):
            result.append(_SnapshotEntry(media_id=item, queue_token=None))
        elif isinstance(item, dict):
            result.append(
                _SnapshotEntry(
                    media_id=str(item.get("media_id", "")),
                    queue_token=(
                        str(item["queue_token"]) if item.get("queue_token") is not None else None
                    ),
                )
            )
        elif isinstance(item, list) and item:
            result.append(
                _SnapshotEntry(
                    media_id=str(item[0]),
                    queue_token=str(item[1]) if len(item) > 1 and item[1] is not None else None,
                )
            )
    return [item for item in result if item.media_id]


def _claim_nonce(session: Session, nonce: str) -> list[_ClaimResult]:
    """原子持久化 nonce claim 与所有仍匹配的 committing 意图。"""
    row = session.get(DeleteCommitNonce, nonce)
    if row is None:
        raise NonceMissingError()
    if row.used:
        raise NonceReusedError()
    if row.expires_at < utc_now():
        raise NonceExpiredError()
    # Claim in SQLite before touching files. An ORM identity-map value can be stale
    # in another request; a conditional write serializes competing committers.
    claimed = session.execute(
        sa.update(DeleteCommitNonce)
        .where(
            DeleteCommitNonce.nonce == nonce,
            DeleteCommitNonce.used.is_(False),
            DeleteCommitNonce.expires_at >= utc_now(),
        )
        .values(used=True, receipt_state="running"),
        execution_options={"synchronize_session": False},
    ).rowcount
    if claimed != 1:
        session.refresh(row)
        if row.used:
            raise NonceReusedError()
        raise NonceExpiredError()

    claims: list[_ClaimResult] = []
    for entry in _decode_snapshot(row.media_ids_json):
        queued = session.get(DeleteQueue, entry.media_id)
        if queued is None:
            claims.append(_ClaimResult(entry=entry, action="queue_entry_missing"))
        elif entry.queue_token is None or queued.queue_token != entry.queue_token:
            claims.append(_ClaimResult(entry=entry, action="queue_entry_superseded"))
        elif queued.status != "pending":
            claims.append(_ClaimResult(entry=entry, action="not_pending"))
        else:
            queued.status = "committing"
            queued.error = None
            queued.committed_at = utc_now()
            claims.append(_ClaimResult(entry=entry, action="process"))
    # 非执行分支的审计与 nonce 单次消费/committing intent 必须同一事务提交；
    # 任一审计写入失败时全部回滚，调用方得到错误且 nonce 仍可重试。
    try:
        _add_snapshot_audits(session, claims)
        # 文件系统操作前必须先耐久化 nonce 单次消费与 deleting intent。
        session.commit()
    except Exception:
        session.rollback()
        raise
    return claims


def commit_with_nonce(
    session: Session,
    nonce: str,
    *,
    _local_delete_hook=None,
    _force_posix_unsupported: bool = False,
) -> DeleteReceipt:
    """使用 nonce 删除快照媒体，逐项返回 success/missing/failed。"""
    from app.core.errors import ConflictError
    from app.services.delete_execution import exclusive_deletion

    with exclusive_deletion(session) as acquired:
        if not acquired:
            raise ConflictError(message="已有删除正在处理，请稍后核对结果")
        claims = _claim_nonce(session, nonce)
        outcome = _commit_claims(
            session,
            claims,
            _local_delete_hook=_local_delete_hook,
            _force_posix_unsupported=_force_posix_unsupported,
            receipt_nonce=nonce,
        )
        row = session.get(DeleteCommitNonce, nonce)
        row.result_json = json.dumps(receipt_payload(outcome))
        if row.receipt_state != "unknown":
            row.receipt_state = "completed"
        session.commit()
        return outcome


def receipt_payload(outcome: DeleteReceipt) -> dict:
    return {
        "outcome": dict(outcome),
        "deleted_count": outcome.deleted_count,
        "deleted_bytes": outcome.deleted_bytes,
        "missing_count": outcome.missing_count,
        "failed_count": outcome.failed_count,
    }


def _commit_media_ids(
    session: Session, media_ids: list[str], *, _local_delete_hook=None
) -> DeleteReceipt:
    """兼容内部测试入口：只 claim 当前 pending 代次，再走相同逐项状态机。"""
    claims: list[_ClaimResult] = []
    for media_id in media_ids:
        row = session.get(DeleteQueue, media_id)
        if row is None:
            claims.append(_ClaimResult(_SnapshotEntry(media_id, None), "queue_entry_missing"))
        elif row.status != "pending":
            claims.append(_ClaimResult(_SnapshotEntry(media_id, row.queue_token), "not_pending"))
        else:
            row.status = "committing"
            row.error = None
            row.committed_at = utc_now()
            claims.append(_ClaimResult(_SnapshotEntry(media_id, row.queue_token), "process"))
    try:
        _add_snapshot_audits(session, claims)
        session.commit()
    except Exception:
        session.rollback()
        raise
    return _commit_claims(session, claims, _local_delete_hook=_local_delete_hook)


def commit_all(session: Session) -> DeleteReceipt:
    """确认并真实删除全部 pending 项(内部/测试入口;生产走 nonce 合同)。"""
    pending = list(
        session.scalars(sa.select(DeleteQueue).where(DeleteQueue.status == "pending")).all()
    )
    claims: list[_ClaimResult] = []
    for row in pending:
        row.status = "committing"
        row.error = None
        row.committed_at = utc_now()
        claims.append(
            _ClaimResult(
                entry=_SnapshotEntry(media_id=row.media_id, queue_token=row.queue_token),
                action="process",
            )
        )
    session.commit()
    return _commit_claims(session, claims)


def _commit_local_media(
    session: Session,
    media,
    *,
    _before_delete_hook=None,
    _force_posix_unsupported: bool = False,
) -> tuple[str, int]:
    """本地源单条安全删除；返回 ``(result, deleted_bytes)``。

    顺序：登记根/路径链/句柄身份复验(native_sources) → ``_lstat_deletion_path``
    再复核整链 → ``_deletion_guard`` 复核归属/尺寸/mtime → 平台身份绑定删除 →
    删除结果复验。叶文件已不存在时返回 missing；其余拒绝抛异常，由调用方映射为
    不含绝对路径的稳定失败码。逐条调用，单条失败不影响其余项。
    """
    from app.services.native_sources import delete_local_media, resolve_deletable_local

    path = resolve_deletable_local(session, media, allow_missing=True)
    if path is None:
        return "missing", 0
    media_path = str(path)
    file_stat = _lstat_deletion_path(media_path)
    guard_reason = _deletion_guard(session, media, media_path, file_stat=file_stat)
    if guard_reason is not None:
        # stat_failed:* 的 OSError 文本可能含绝对路径，本地源只保留稳定码。
        if guard_reason.startswith(_GUARD_STAT + ":"):
            guard_reason = _GUARD_STAT
        raise ValueError(guard_reason)
    deletion = delete_local_media(
        session,
        media,
        allow_missing=True,
        before_identity_binding=_before_delete_hook,
        force_posix_unsupported=_force_posix_unsupported,
    )
    if deletion is None:
        return "missing", 0
    return deletion


def _cleanup_deleted_metadata(
    session: Session, row: DeleteQueue, media: MediaCacheIndex | None
) -> None:
    """清理已确认不存在的媒体元数据；调用方负责事务提交。"""
    media_id = row.media_id
    row.status = "succeeded"
    if media is not None:
        session.execute(
            sa.delete(MediaLibraryMembership).where(MediaLibraryMembership.media_id == media_id)
        )
        session.delete(media)
    favorite = session.get(Favorite, media_id)
    if favorite is not None:
        session.delete(favorite)
    session.delete(row)


def _record_internal_error(session: Session, media_id: str, queue_token: str | None) -> None:
    """尽力给可恢复的 committing intent 标记稳定错误，不泄露异常详情。"""
    try:
        row = session.get(DeleteQueue, media_id)
        if (
            row is not None
            and row.status == "committing"
            and (queue_token is None or row.queue_token == queue_token)
        ):
            row.error = "internal_error"
            session.commit()
    except Exception:
        session.rollback()


def _persist_delete_state(
    session: Session,
    row: DeleteQueue,
    *,
    status: str,
    audit_detail: str | None = None,
) -> None:
    """在元数据清理前独立耐久化物理删除事实或待观察事实。"""
    row.status = status
    row.error = None
    if audit_detail is not None:
        audit.log_action(
            session,
            "delete_commit",
            row.media_id,
            detail=audit_detail,
        )
    session.flush()
    session.commit()


def _finish_metadata_cleanup(
    session: Session,
    row: DeleteQueue,
    media: MediaCacheIndex | None,
    *,
    audit_detail: str,
) -> None:
    """清理索引/收藏/队列并写审计；整个清理是一个独立事务。"""
    _cleanup_deleted_metadata(session, row, media)
    audit.log_action(session, "delete_commit", row.media_id, detail=audit_detail)
    session.flush()
    session.commit()


def _add_snapshot_audits(session: Session, claims: list[_ClaimResult]) -> None:
    """把非执行快照事实加入当前 claim 事务，不在此处独立提交或吞异常。"""
    detail_by_action = {
        "queue_entry_missing": "skipped:queue_entry_missing",
        "queue_entry_superseded": "failed:queue_entry_superseded",
        "not_pending": "failed:not_pending",
    }
    for claim in claims:
        detail = detail_by_action.get(claim.action)
        if detail is not None:
            audit.log_action(
                session,
                "delete_commit",
                claim.entry.media_id,
                detail=detail,
            )


def _delete_one(
    session: Session,
    media: MediaCacheIndex,
    *,
    _local_delete_hook=None,
    _force_posix_unsupported: bool = False,
) -> tuple[str, int]:
    """执行单条物理删除，不写数据库 outcome。"""
    media_path = media.media_path
    if not media_path:
        raise ValueError("media_identity_unavailable")
    if media.source_type == "local":
        return _commit_local_media(
            session,
            media,
            _before_delete_hook=_local_delete_hook,
            _force_posix_unsupported=_force_posix_unsupported,
        )
    if media.source_type != "jellyfin":
        raise ValueError("unsupported_source_type")

    file_stat = _lstat_deletion_path(media_path)
    guard_reason = _deletion_guard(session, media, media_path, file_stat=file_stat)
    if guard_reason is not None:
        raise ValueError(guard_reason)
    actual_size = file_stat.st_size
    os.remove(media_path)
    try:
        os.lstat(media_path)
    except FileNotFoundError:
        pass
    else:
        raise ValueError("delete_not_verified")
    return "success", actual_size


def _commit_one(
    session: Session,
    entry: _SnapshotEntry,
    *,
    _local_delete_hook=None,
    _force_posix_unsupported: bool = False,
) -> tuple[str, int]:
    """处理一条 committing intent，并在独立事务中收敛。"""
    media_id = entry.media_id
    row = session.get(DeleteQueue, media_id)
    if (
        row is None
        or row.status != "committing"
        or entry.queue_token is None
        or row.queue_token != entry.queue_token
    ):
        session.rollback()
        return "failed", 0

    media = mi.get_cached_media(session, media_id)
    failure_code: str | None = None
    try:
        if media is None:
            raise ValueError("media_identity_unavailable")
        result, actual_size = _delete_one(
            session,
            media,
            _local_delete_hook=_local_delete_hook,
            _force_posix_unsupported=_force_posix_unsupported,
        )
    except FileNotFoundError:
        result, actual_size = "missing", 0
    except AppError as exc:
        from app.services.native_sources import deletion_failure_code

        failure_code = deletion_failure_code(exc)
        result, actual_size = "failed", 0
    except ValueError as exc:
        failure_code = str(exc)
        if failure_code.startswith(_GUARD_STAT + ":"):
            failure_code = _GUARD_STAT
        result, actual_size = "failed", 0
    except OSError:
        failure_code = _GUARD_STAT if media and media.source_type == "local" else "delete_failed"
        result, actual_size = "failed", 0
    except Exception:
        session.rollback()
        _record_internal_error(session, media_id, entry.queue_token)
        return "failed", 0

    detail = result if failure_code is None else f"{result}:{failure_code}"
    try:
        if result == "failed":
            row.status = "failed"
            row.error = failure_code
            audit.log_action(session, "delete_commit", media_id, detail=detail)
            session.flush()
            session.commit()
            return result, 0
        # 文件名已确认消失。先独立提交 delete_done，再尝试不可回滚的元数据清理。
        _persist_delete_state(session, row, status="delete_done")
    except Exception:
        session.rollback()
        # POSIX 名字已由物理删除路径确认消失；SQLite 仍保留已耐久提交的
        # committing intent，下一次恢复会转 delete_done 并补清理/审计。
        logger.warning("已确认物理删除，但删除状态未持久化；保留 committing 等待恢复")
        return result, actual_size if result == "success" else 0

    try:
        row = session.get(DeleteQueue, media_id)
        media = mi.get_cached_media(session, media_id)
        if row is None or row.status != "delete_done":
            session.rollback()
            return result, actual_size if result == "success" else 0
        _finish_metadata_cleanup(session, row, media, audit_detail=detail)
    except Exception:
        # delete_done 已耐久提交；清理失败不能把已确认的物理删除改写成 failed。
        session.rollback()
        logger.warning("已确认物理删除，但元数据清理未完成；保留 delete_done 等待恢复")
    return result, actual_size if result == "success" else 0


def _commit_claims(
    session: Session,
    claims: list[_ClaimResult],
    *,
    _local_delete_hook=None,
    _force_posix_unsupported: bool = False,
    receipt_nonce: str | None = None,
) -> DeleteReceipt:
    """逐项执行已持久化 claim；任一异常均不得中断其余项。"""
    outcome = DeleteReceipt()
    for claim in claims:
        media_id = claim.entry.media_id
        if claim.action == "queue_entry_missing":
            continue
        if claim.action == "queue_entry_superseded":
            outcome[media_id] = "failed"
            continue
        if claim.action == "not_pending":
            outcome[media_id] = "failed"
            continue
        try:
            result, deleted_bytes = _commit_one(
                session,
                claim.entry,
                _local_delete_hook=_local_delete_hook,
                _force_posix_unsupported=_force_posix_unsupported,
            )
        except Exception:
            session.rollback()
            _record_internal_error(session, media_id, claim.entry.queue_token)
            result, deleted_bytes = "failed", 0
        if receipt_nonce is not None and result == "failed":
            queued = session.get(DeleteQueue, media_id)
            if queued is not None and queued.status == "committing":
                # Unexpected exceptions can occur after unlink: failed is not a
                # confirmed filesystem fact while the intent is unresolved.
                receipt_row = session.get(DeleteCommitNonce, receipt_nonce)
                receipt_row.receipt_state = "unknown"
                receipt_row.result_json = json.dumps(receipt_payload(outcome))
                session.commit()
                continue
        outcome[media_id] = result
        outcome.deleted_bytes += deleted_bytes
        if receipt_nonce is not None:
            # 已返回的逐项事实才能写入回执；崩溃窗口中缺失事实保持未知。
            receipt_row = session.get(DeleteCommitNonce, receipt_nonce)
            receipt_row.result_json = json.dumps(receipt_payload(outcome))
            session.commit()
    return outcome


def _observe_recovery_state(
    session: Session,
    media: MediaCacheIndex | None,
    *,
    _jellyfin_observation_hook: Callable[[], None] | None = None,
    _jellyfin_ntstatus_override: int | None = None,
) -> _RecoveryObservation:
    """只读观察原对象是否仍在；不确定与身份替换必须分开。"""
    if media is None:
        return _RecoveryObservation.UNKNOWN

    if media.source_type == "local":
        from app.core.errors import ConflictError
        from app.services.native_sources import SourcePathError, open_media, safe_path

        root = session.get(LocalRoot, media.library_id)
        if root is None or not root.enabled:
            return _RecoveryObservation.UNKNOWN
        try:
            safe_path(root, media.media_path or "", allow_missing_leaf=True)
            handle, _file_stat = open_media(session, media)
        except FileNotFoundError:
            return _RecoveryObservation.MISSING
        except ConflictError:
            return _RecoveryObservation.REPLACED_OR_ORIGINAL_GONE
        except (SourcePathError, OSError):
            return _RecoveryObservation.UNKNOWN
        except AppError:
            return _RecoveryObservation.UNKNOWN
        handle.close()
        return _RecoveryObservation.SAME_ORIGINAL_PRESENT

    if media.source_type != "jellyfin" or not media.media_path:
        return _RecoveryObservation.UNKNOWN
    try:
        if os.name == "nt":
            file_info = _windows_recovery_file_info(
                media.media_path,
                after_parent_validation=_jellyfin_observation_hook,
                _ntstatus_override=_jellyfin_ntstatus_override,
            )
            actual_size = file_info.size
            actual_mtime_ms = file_info.mtime_ms
        else:
            file_stat = _lstat_recovery_path(media.media_path)
            actual_size = file_stat.st_size
            actual_mtime_ms = int(file_stat.st_mtime * 1000)
    except FileNotFoundError:
        return _RecoveryObservation.MISSING
    except (OSError, ValueError):
        return _RecoveryObservation.UNKNOWN

    if media.size_bytes is not None and actual_size != media.size_bytes:
        return _RecoveryObservation.REPLACED_OR_ORIGINAL_GONE
    stored_mtime_ms = _mtime_ms(media)
    if stored_mtime_ms is not None:
        if abs(actual_mtime_ms - stored_mtime_ms) > 3000:
            return _RecoveryObservation.REPLACED_OR_ORIGINAL_GONE
    if media.size_bytes is None or stored_mtime_ms is None:
        return _RecoveryObservation.UNKNOWN
    return _RecoveryObservation.SAME_ORIGINAL_PRESENT


def recover_interrupted_deletes(
    session: Session,
    *,
    _jellyfin_observation_hook: Callable[[], None] | None = None,
    _jellyfin_ntstatus_override: int | None = None,
) -> None:
    from app.services.delete_execution import exclusive_deletion

    with exclusive_deletion(session) as acquired:
        if not acquired:
            return
        session.execute(
            sa.update(DeleteCommitNonce)
            .where(DeleteCommitNonce.receipt_state == "running")
            .values(receipt_state="unknown")
        )
        session.commit()
        _recover_interrupted_deletes_locked(
            session,
            _jellyfin_observation_hook=_jellyfin_observation_hook,
            _jellyfin_ntstatus_override=_jellyfin_ntstatus_override,
        )


def _recover_interrupted_deletes_locked(
    session: Session,
    *,
    _jellyfin_observation_hook: Callable[[], None] | None = None,
    _jellyfin_ntstatus_override: int | None = None,
) -> None:
    """只观察并收敛可恢复删除状态；绝不再次执行物理删除。"""
    recoverable = list(
        session.execute(
            sa.select(DeleteQueue.media_id, DeleteQueue.status)
            .where(
                DeleteQueue.status.in_(("committing", "delete_done", "delete_pending"))
            )
            .order_by(DeleteQueue.media_id)
        ).all()
    )
    session.rollback()
    for media_id, expected_status in recoverable:
        row = session.get(DeleteQueue, media_id)
        if row is None or row.status != expected_status:
            session.rollback()
            continue
        media = mi.get_cached_media(session, media_id)
        if row.status == "delete_done":
            observation = None
            audit_detail = "metadata_cleanup_recovered"
        else:
            observation = _observe_recovery_state(
                session,
                media,
                _jellyfin_observation_hook=_jellyfin_observation_hook,
                _jellyfin_ntstatus_override=_jellyfin_ntstatus_override,
            )
            if observation is _RecoveryObservation.UNKNOWN:
                session.rollback()
                logger.warning("删除恢复观察结果不确定；保留原状态等待重试")
                continue
            if observation is _RecoveryObservation.SAME_ORIGINAL_PRESENT:
                if row.status == "committing":
                    row.status = "failed"
                    row.error = "interrupted_not_deleted"
                    audit.log_action(
                        session,
                        "delete_commit",
                        media_id,
                        detail="failed:interrupted_not_deleted",
                    )
                    session.flush()
                    session.commit()
                else:
                    session.rollback()
                continue
            audit_detail = (
                "recovered_deleted"
                if observation is _RecoveryObservation.MISSING
                else "recovered_original_gone"
            )
        if row.status != "delete_done":
            try:
                _persist_delete_state(session, row, status="delete_done")
            except Exception:
                session.rollback()
                logger.warning("删除恢复无法持久化 delete_done；保留原状态等待重试")
                continue
            row = session.get(DeleteQueue, media_id)
            media = mi.get_cached_media(session, media_id)
            if row is None or row.status != "delete_done":
                session.rollback()
                continue
        try:
            _finish_metadata_cleanup(
                session,
                row,
                media,
                audit_detail=audit_detail,
            )
        except Exception:
            session.rollback()
            logger.warning("删除恢复的元数据清理未完成；保留 delete_done 等待重试")


__all__ = [
    "DeleteCommitPrep",
    "NonceError",
    "NonceExpiredError",
    "NonceMissingError",
    "NonceReusedError",
    "commit_all",
    "commit_with_nonce",
    "dequeue",
    "enqueue",
    "list_queue",
    "pending_summary",
    "prepare_commit",
    "recover_interrupted_deletes",
]


