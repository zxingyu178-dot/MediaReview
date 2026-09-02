"""设备配对服务: 一次性配对码 + bearer token 认证闭环。

配对流程:
1. 服务器生成一次性 6 位配对码(短时效、一次性)。
2. 客户端提交 device_id + 配对码,配对成功即签发 bearer token。
3. 客户端持久化该 token,在后续受保护 API 的 Authorization: Bearer <token> 中携带。
4. 服务端只保存 token 的 SHA-256 哈希(不明文),支持按设备 revoke(立即失效)。

认证: authenticate(token) 通过 token_hash 查找未撤销设备,供统一认证依赖使用。
"""  # noqa: D205

from __future__ import annotations

import hashlib
import random
import secrets
import string
from datetime import timedelta

import sqlalchemy as sa
from sqlalchemy.orm import Session

from app.db.models import PairedDevice, PairingCode, utc_now

# 配对码位数
_CODE_LENGTH = 6
# 默认有效期(分钟)
_DEFAULT_TTL_MINUTES = 10
# bearer token 前缀
_TOKEN_ALPHABET = string.ascii_letters + string.digits


def normalize_installation_id(value: str) -> str:
    """安装身份按 trim + casefold 规范化；不猜测不同历史 ID 的物理归属。"""
    return (value or "").strip().casefold()


def _new_code() -> str:
    return f"{random.SystemRandom().randint(0, 10**_CODE_LENGTH - 1):0{_CODE_LENGTH}d}"


def _new_token() -> str:
    """生成高强度 bearer token(客户端可持久化)。服务端只存其哈希。"""
    raw = "".join(secrets.choice(_TOKEN_ALPHABET) for _ in range(40))
    return f"mr_{raw}"


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def generate_pairing_code(session: Session, *, ttl_minutes: int = _DEFAULT_TTL_MINUTES) -> str:
    """生成新的可用的 6 位配对码并返回(旧未用码不主动清理,验证时按时间过滤)。"""
    code = _new_code()
    session.add(
        PairingCode(
            code=code,
            expires_at=utc_now() + timedelta(minutes=ttl_minutes),
            used=False,
        )
    )
    session.flush()
    return code


def verify_and_pair(session: Session, code: str, device_id: str) -> str:
    """验证配对码并签发 bearer token。

    成功返回新签发 token(客户端需持久化,后续请求携带);失败返回空串。
    校验: 码存在、未使用、未过期。任一项不满足返回 False(客户端应重新获取码)。
    """
    code = (code or "").strip()
    device_id = normalize_installation_id(device_id)
    if not code or not device_id:
        return ""
    row = session.scalars(sa.select(PairingCode).where(PairingCode.code == code)).first()
    if row is None or row.used:
        return ""
    if row.expires_at < utc_now():
        return ""
    row.used = True
    row.used_at = utc_now()

    token = _new_token()
    device = session.scalars(
        sa.select(PairedDevice).where(PairedDevice.installation_id == device_id)
    ).first()
    if device is None:
        session.add(
            PairedDevice(
                device_id=device_id,
                installation_id=device_id,
                paired_at=utc_now(),
                token_hash=_token_hash(token),
                revoked=False,
            )
        )
    else:
        # 重新配对: 更新 token(旧 token 立即失效)并解除撤销
        device.token_hash = _token_hash(token)
        device.revoked = False
        device.last_seen_at = None
    session.flush()
    return token


def authenticate(session: Session, token: str, *, touch: bool = True) -> PairedDevice | None:
    """按 bearer token 解析已配对且未撤销的设备。校验失败返回 None。"""
    token = (token or "").strip()
    if not token:
        return None
    digest = _token_hash(token)
    device = session.scalars(
        sa.select(PairedDevice).where(
            sa.and_(PairedDevice.token_hash == digest, PairedDevice.revoked.is_(False))
        )
    ).first()
    if device is None:
        return None
    if touch:
        device.last_seen_at = utc_now()
        session.flush()
    return device


def device_stream_key_name(session: Session, device_id: str) -> str | None:
    """返回设备当前的 Jellyfin 播放 key 名(供调用方在 Jellyfin 侧撤销);无设备返回 None。"""
    normalized = normalize_installation_id(device_id)
    device = session.scalars(
        sa.select(PairedDevice).where(PairedDevice.installation_id == normalized)
    ).first()
    return device.jellyfin_key_name if device is not None else None


def revoke_device(session: Session, device_id: str) -> bool:
    """撤销某设备的认证(token 立即失效)并清除其 Jellyfin 播放凭据字段。

    Jellyfin 侧的命名 key 撤销由 API 层调用 ``revoke_device_stream_key`` 完成;
    本函数只负责本地数据清理,确保撤销后不再滞留明文播放 key。
    """
    normalized = normalize_installation_id(device_id)
    device = session.scalars(
        sa.select(PairedDevice).where(PairedDevice.installation_id == normalized)
    ).first()
    if device is None:
        return False
    device.revoked = True
    device.token_hash = None
    device.jellyfin_key_name = None
    device.jellyfin_key_value = None
    device.jellyfin_key_created_at = None
    session.flush()
    return True


def is_paired(session: Session, device_id: str) -> bool:
    device_id = normalize_installation_id(device_id)
    if not device_id:
        return False
    row = session.scalars(
        sa.select(PairedDevice).where(PairedDevice.installation_id == device_id)
    ).first()
    return row is not None and not row.revoked


def paired_devices(session: Session) -> list[PairedDevice]:
    return list(
        session.scalars(sa.select(PairedDevice).order_by(PairedDevice.paired_at.asc())).all()
    )


def list_codes(session: Session) -> list[PairingCode]:
    return list(
        session.scalars(sa.select(PairingCode).order_by(PairingCode.created_at.desc())).all()
    )


def clear_codes(session: Session) -> int:
    """清理已过期或已使用的配对码,返回清理条数。"""
    now = utc_now()
    doomed = session.scalars(
        sa.select(PairingCode).where(
            sa.or_(PairingCode.used.is_(True), PairingCode.expires_at < now)
        )
    ).all()
    for row in doomed:
        session.delete(row)
    session.flush()
    return len(doomed)


__all__ = [
    "authenticate",
    "clear_codes",
    "device_stream_key_name",
    "generate_pairing_code",
    "is_paired",
    "list_codes",
    "normalize_installation_id",
    "paired_devices",
    "revoke_device",
    "verify_and_pair",
]
