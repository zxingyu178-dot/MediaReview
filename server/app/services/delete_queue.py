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


def _lstat_deletion_path(media_path: str) -> os.stat_result:
    """检查整条路径链，避免跟随叶链接或父目录 junction 删除目标文件。"""
    path = Path(media_path)
    if not path.is_absolute() or ".." in path.parts or media_path.startswith(("\\\\", "//")):
        raise ValueError(f"{_GUARD_STAT}:unsafe_path")
    leaf_stat: os.stat_result | None = None
    for component in [*reversed(path.parents), path]:
        info = os.lstat(component)
        if stat.S_ISLNK(info.st_mode) or (
            getattr(info, "st_file_attributes", 0)
            & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
        ):
            raise ValueError(f"{_GUARD_STAT}:link_or_reparse_point")
        leaf_stat = info
    assert leaf_stat is not None
    if not stat.S_ISREG(leaf_stat.st_mode):
        raise ValueError(f"{_GUARD_STAT}:not_a_regular_file")
    return leaf_stat


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


def prepare_commit(session: Session) -> DeleteCommitPrep:
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
        .values(used=True),
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
    claims = _claim_nonce(session, nonce)
    return _commit_claims(
        session,
        claims,
        _local_delete_hook=_local_delete_hook,
        _force_posix_unsupported=_force_posix_unsupported,
    )


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
        outcome[media_id] = result
        outcome.deleted_bytes += deleted_bytes
    return outcome


def _observe_recovery_state(
    session: Session, media: MediaCacheIndex | None
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
        file_stat = _lstat_deletion_path(media.media_path)
    except FileNotFoundError:
        return _RecoveryObservation.MISSING
    except (OSError, ValueError):
        return _RecoveryObservation.UNKNOWN

    if media.size_bytes is not None and file_stat.st_size != media.size_bytes:
        return _RecoveryObservation.REPLACED_OR_ORIGINAL_GONE
    stored_mtime_ms = _mtime_ms(media)
    if stored_mtime_ms is not None:
        actual_ms = int(file_stat.st_mtime * 1000)
        if abs(actual_ms - stored_mtime_ms) > 3000:
            return _RecoveryObservation.REPLACED_OR_ORIGINAL_GONE
    return _RecoveryObservation.SAME_ORIGINAL_PRESENT


def recover_interrupted_deletes(session: Session) -> None:
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
            observation = _observe_recovery_state(session, media)
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
