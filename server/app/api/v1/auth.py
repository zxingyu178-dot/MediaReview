"""统一认证依赖: 校验受保护 API 的 bearer token。

- 读取 Authorization: Bearer <token>(服务端向不记录明文 token)
- 通过服务端只存的 token_hash 解析已配对且未撤销的设备
- 失败抛出 UnauthorizedError(401)

注意: 当配置 security.pairing_required=False(开发/内网关闭配对)时,该依赖放行。
"""

from __future__ import annotations

import sqlite3
from datetime import timedelta

from fastapi import Depends, Request
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session

from app.core.errors import UnauthorizedError
from app.db.models import utc_now
from app.db.session import get_db
from app.services import pairing

SCHEME = "bearer"


def _extract_bearer(request: Request) -> str:
    header = request.headers.get("Authorization", "")
    if not header:
        return ""
    scheme, _, value = header.partition(" ")
    if scheme.lower() != SCHEME:
        return ""
    return value.strip()


def require_auth(request: Request, db: Session = Depends(get_db)):
    """FastAPI 依赖: 返回当前已认证设备(PairedDevice);未认证抛 401。

    当服务器配置关闭配对要求时,视为开发模式放行(返回 None)。
    """
    settings = request.app.state.settings
    if not settings.security.pairing_required:
        return None
    token = _extract_bearer(request)
    device = pairing.authenticate(db, token, touch=False)
    if device is None:
        raise UnauthorizedError()
    now = utc_now()
    if device.last_seen_at is None or device.last_seen_at < now - timedelta(minutes=1):
        # The auth dependency runs before endpoint work. Finish this tiny heartbeat
        # transaction here, never hold its writer lock across upstream IO/streaming.
        connection = db.connection()
        busy_timeout = connection.exec_driver_sql("PRAGMA busy_timeout").scalar_one()
        try:
            try:
                connection.exec_driver_sql("PRAGMA busy_timeout=0")
                device.last_seen_at = now
                db.flush()
            finally:
                connection.exec_driver_sql(f"PRAGMA busy_timeout={int(busy_timeout)}")
            db.commit()
        except OperationalError as exc:
            db.rollback()
            code = getattr(exc.orig, "sqlite_errorcode", 0) & 0xFF
            if code not in (sqlite3.SQLITE_BUSY, sqlite3.SQLITE_LOCKED):
                raise
            # Recheck after rollback; a skipped heartbeat must never bypass revoke.
            device = pairing.authenticate(db, token, touch=False)
            if device is None:
                raise UnauthorizedError() from None
    return device


def require_localhost_or_auth(request: Request, db: Session = Depends(get_db)):
    """本地回环放行,否则走统一认证(localhost-or-auth)。

    用于系统信息/存储/诊断等敏感接口与 Web 管理后台:本机(管理后台)可直连,
    局域网其他设备必须携带合法 token,避免敏感信息对局域网裸奔。
    当 pairing_required=False(开发模式)时由 require_auth 放行。
    """
    host = request.client.host if request.client else ""
    if host == "::1" or host.startswith("127."):
        return None
    return require_auth(request, db)


__all__ = ["require_auth", "require_localhost_or_auth"]
