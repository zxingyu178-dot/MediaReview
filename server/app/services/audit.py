"""审计记录: 收藏/撤销、入队/撤销/确认删除等关键操作(不可变日志)。"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.orm import Session

from app.db.models import AuditLog


def log_action(
    session: Session, action: str, media_id: str | None = None, detail: str | None = None
) -> None:
    session.add(AuditLog(action=action, media_id=media_id, detail=detail))
    session.flush()


def recent(session: Session, limit: int = 100) -> list[AuditLog]:
    return list(
        session.scalars(
            sa.select(AuditLog).order_by(AuditLog.id.desc()).limit(max(1, min(limit, 500)))
        ).all()
    )


__all__ = ["log_action", "recent"]
