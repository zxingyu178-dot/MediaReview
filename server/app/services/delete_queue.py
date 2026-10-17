"""待删除队列服务: 入队(未删) -> 确认(commit 后才真实删除)。

遵循 AGENTS.md: 删除必须两步(先入待删除,commit 才永久删除),禁止点击即删。
删除目标为 media_cache_index 持久化的真实文件路径;删除成功后再清理索引/收藏。

Task D 起最终删除采用一次性 nonce 合同:
- ``prepare_commit`` 生成绑定当前队列快照的 nonce(仅一次、10 分钟过期);
- ``commit_with_nonce`` 只删除快照内的媒体(TOCTOU 防护),逐项独立处理并写审计。
"""

from __future__ import annotations

import json
import os
import secrets
import stat
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

import sqlalchemy as sa
from sqlalchemy.orm import Session

from app.db.models import DeleteCommitNonce, DeleteQueue, Favorite, utc_now
from app.services import audit
from app.services import media_index as mi

# 删除确认 nonce 默认有效期(秒)
NONCE_TTL_SECONDS = 10 * 60


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
    row = session.get(DeleteQueue, media_id)
    if row is not None:
        return False
    session.add(DeleteQueue(media_id=media_id, size_bytes=size_bytes, status="pending"))
    session.flush()
    audit.log_action(session, "delete_enqueue", media_id)
    return True


def dequeue(session: Session, media_id: str) -> bool:
    """从未确认(pending)队列中撤销。已 committed 的不可撤销。"""
    row = session.get(DeleteQueue, media_id)
    if row is None or row.status == "committed":
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
            sa.func.coalesce(sa.func.sum(sa.case(
                (sa.or_(DeleteQueue.size_bytes.is_(None), DeleteQueue.size_bytes <= 0), 1),
                else_=0,
            )), 0),
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
    if media.library_id not in mi.selected_library_ids(session):
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
    total_bytes = sum(row.size_bytes or 0 for row in pending)
    nonce = secrets.token_hex(16)
    expires_at = utc_now() + timedelta(seconds=NONCE_TTL_SECONDS)
    session.add(
        DeleteCommitNonce(
            nonce=nonce,
            expires_at=expires_at,
            used=False,
            media_ids_json=json.dumps(media_ids),
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


def commit_with_nonce(session: Session, nonce: str) -> DeleteReceipt:
    """使用一次性 nonce 确认并删除其快照内的媒体(逐项 success/missing/failed)。"""
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
    session.expire(row, ["used"])
    snapshot_ids: list[str] = json.loads(row.media_ids_json)
    outcome = _commit_media_ids(session, snapshot_ids)
    session.flush()
    return outcome


def commit_all(session: Session) -> DeleteReceipt:
    """确认并真实删除全部 pending 项(内部/测试入口;生产走 nonce 合同)。"""
    pending = list(
        session.scalars(sa.select(DeleteQueue).where(DeleteQueue.status == "pending")).all()
    )
    return _commit_media_ids(session, [row.media_id for row in pending])


def _commit_media_ids(session: Session, media_ids: list[str]) -> DeleteReceipt:
    """对指定媒体逐项确认删除,返回 {media_id: result}。

    result ∈ success(已删除) / missing(文件系统确认不存在,仍清理索引) / failed。
    删除前重新校验媒体归属与文件身份;文件被替换/修改/不再属于允许库时拒绝删除,
    并记录审计与失败原因。逐项处理,失败不影响其余项;成功后清理索引、收藏与队列记录。
    """
    outcome = DeleteReceipt()
    for media_id in media_ids:
        row = session.get(DeleteQueue, media_id)
        if row is None:
            # 队列中已无此项(prepare 后被撤销),跳过且不计入结果
            continue
        if row.status != "pending":
            # An older prepared snapshot must never silently retry a failed item.
            outcome[media_id] = "failed"
            continue
        row.status = "committed"
        row.committed_at = utc_now()
        media = mi.get_cached_media(session, media_id)
        media_path = media.media_path if media else None
        guard_reason: str | None = None
        try:
            if media is None or not media_path:
                guard_reason = "media_identity_unavailable"
                raise ValueError(guard_reason)
            else:
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
                result = "success"
                outcome.deleted_bytes += actual_size
        except FileNotFoundError:
            result = "missing"
        except (OSError, ValueError) as exc:
            row.status = "failed"
            row.error = str(exc)
            result = "failed"
        outcome[media_id] = result
        detail = result if guard_reason is None else f"{result}:{guard_reason}"
        audit.log_action(session, "delete_commit", media_id, detail=detail)
        if result in ("success", "missing"):
            row.status = "succeeded"
            if media is not None:
                session.delete(media)
            fav = session.get(Favorite, media_id)
            if fav is not None:
                session.delete(fav)
            session.delete(row)
        session.flush()
    return outcome


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
]
