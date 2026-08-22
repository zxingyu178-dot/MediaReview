"""收藏服务: 收藏/取消收藏 + 收藏列表(带媒体摘要)。"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.orm import Session

from app.db.models import Favorite
from app.services import audit


def is_favorite(session: Session, media_id: str) -> bool:
    return session.get(Favorite, media_id) is not None


def add(session: Session, media_id: str) -> bool:
    """收藏媒体,返回是否为新收藏(已收藏则幂等返回 False)。"""
    row = session.get(Favorite, media_id)
    if row is not None:
        return False
    session.add(Favorite(media_id=media_id))
    session.flush()
    audit.log_action(session, "favorite_add", media_id)
    return True


def remove(session: Session, media_id: str) -> bool:
    """取消收藏,返回是否存在过收藏。"""
    row = session.get(Favorite, media_id)
    if row is None:
        return False
    session.delete(row)
    session.flush()
    audit.log_action(session, "favorite_remove", media_id)
    return True


def list_ids(session: Session) -> list[str]:
    rows = session.scalars(sa.select(Favorite).order_by(Favorite.created_at.desc())).all()
    return [row.media_id for row in rows]


__all__ = ["add", "is_favorite", "list_ids", "remove"]
