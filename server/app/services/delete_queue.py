"""待删除队列服务: 入队(未删) -> 确认(commit 后才真实删除)。

遵循 AGENTS.md: 删除必须两步(先入待删除,commit 才永久删除),禁止点击即删。
删除目标为 media_cache_index 持久化的真实文件路径;删除成功后再清理索引/收藏。
"""

from __future__ import annotations

import os
import stat

import sqlalchemy as sa
from sqlalchemy.orm import Session

from app.db.models import DeleteQueue, Favorite, utc_now
from app.services import audit
from app.services import media_index as mi


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
    return list(session.scalars(sa.select(DeleteQueue).order_by(DeleteQueue.added_at.asc())).all())


# 拒绝删除的身份校验详情前缀,用于区分失败原因(写入审计与队列 error)
_GUARD_LIBRARY = "library_not_allowed"
_GUARD_SIZE = "file_size_changed"
_GUARD_MTIME = "file_modified_changed"
_GUARD_STAT = "stat_failed"


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


def _deletion_guard(session: Session, media, media_path: str) -> str | None:
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
        st = os.stat(media_path)
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


def commit_all(session: Session) -> dict[str, str]:
    """确认并真实删除全部 pending 项。返回 {media_id: result}。

    result ∈ success(已删除) / missing(文件路径缺失或已不存在,仍清理索引) / failed。
    删除前重新校验媒体归属与文件身份;文件被替换/修改/不再属于允许库时拒绝删除,
    并记录审计与失败原因。逐项处理,失败不影响其余项;成功后清理索引、收藏与队列记录。
    """
    pending = list(
        session.scalars(sa.select(DeleteQueue).where(DeleteQueue.status == "pending")).all()
    )
    outcome: dict[str, str] = {}
    for row in pending:
        row.status = "committed"
        row.committed_at = utc_now()
        media = mi.get_cached_media(session, row.media_id)
        media_path = media.media_path if media else None
        guard_reason: str | None = None
        try:
            if media is None or not media_path:
                result = "missing"
            elif not os.path.isfile(media_path):
                # 文件已不存在 -> 视为成功并清理索引(幂等,不误删新文件)
                result = "missing"
            else:
                guard_reason = _deletion_guard(session, media, media_path)
                if guard_reason is not None:
                    raise ValueError(guard_reason)
                os.remove(media_path)
                result = "success"
        except FileNotFoundError:
            result = "missing"
        except (OSError, ValueError) as exc:
            row.status = "failed"
            row.error = str(exc)
            result = "failed"
        outcome[row.media_id] = result
        detail = result if guard_reason is None else f"{result}:{guard_reason}"
        audit.log_action(session, "delete_commit", row.media_id, detail=detail)
        if result in ("success", "missing"):
            row.status = "succeeded"
            if media is not None:
                session.delete(media)
            fav = session.get(Favorite, row.media_id)
            if fav is not None:
                session.delete(fav)
            session.delete(row)
        session.flush()
    return outcome


__all__ = ["commit_all", "dequeue", "enqueue", "list_queue"]
