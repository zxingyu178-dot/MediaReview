"""设备配对接口(自动发现失败时的手动 IP 兜底 + 一次配对码 + bearer token)。

- GET  /pairing/status           配对要求与已配对设备概览(开放)
- POST /pairing/code             生成一次性配对码(仅本机/管理后台;远程默认 403)
- POST /pairing/verify           客户端提交设备标识+配对码,成功签发 bearer token
- GET  /pairing/devices          已配对设备列表(受保护)
- POST /pairing/revoke           撤销某设备(token 立即失效)(受保护)
- POST /pairing/codes/clean      清理已用/过期码(受保护)
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.api.v1.auth import require_auth
from app.core.errors import ForbiddenError
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.services import pairing

router = APIRouter(prefix="/pairing", tags=["pairing"])


class PairingStatus(BaseModel):
    pairing_required: bool
    device_count: int


class PairingCodeOut(BaseModel):
    code: str
    expires_in_seconds: int


class VerifyIn(BaseModel):
    device_id: str
    code: str


class VerifyOut(BaseModel):
    paired: bool
    token: str = ""


class RevokeIn(BaseModel):
    device_id: str


def _is_loopback(request: Request) -> bool:
    """是否来自本机(127.0.0.1 / ::1 或 127.x 网段)。"""
    host = request.client.host if request.client else ""
    return host == "::1" or host.startswith("127.")


@router.get("/status", response_model=Envelope[PairingStatus])
def status(request: Request, db: Session = Depends(get_db)) -> Envelope[PairingStatus]:
    settings = request.app.state.settings
    return ok(
        PairingStatus(
            pairing_required=settings.security.pairing_required,
            device_count=len(pairing.paired_devices(db)),
        )
    )


@router.get("/devices", response_model=Envelope[list[dict]])
def devices(_auth=Depends(require_auth), db: Session = Depends(get_db)) -> Envelope[list[dict]]:
    rows = [
        {
            "device_id": d.device_id,
            "installation_id": d.installation_id,
            "name": d.name,
            "paired_at": d.paired_at,
            "revoked": d.revoked,
            "last_seen_at": d.last_seen_at,
        }
        for d in pairing.paired_devices(db)
    ]
    return ok(rows)


@router.post("/code", response_model=Envelope[PairingCodeOut])
def create_code(request: Request, db: Session = Depends(get_db)) -> Envelope[PairingCodeOut]:
    """生成一次性配对码。

    安全约束: 默认仅允许本机(127.0.0.1/::1)或管理后台调用,防止局域网任意设备
    自行获取配对码绕过配对机制。开发环境可用 security.pairing_code_remote_allowed=True
    开放远程生成。
    """
    settings = request.app.state.settings
    if not settings.security.pairing_code_remote_allowed and not _is_loopback(request):
        raise ForbiddenError()
    code = pairing.generate_pairing_code(db)
    db.commit()
    # 常量与生成时一致(默认 10 分钟);返回秒数供客户端倒计时
    return ok(PairingCodeOut(code=code, expires_in_seconds=10 * 60))


@router.post("/verify", response_model=Envelope[VerifyOut])
def verify(body: VerifyIn, db: Session = Depends(get_db)) -> Envelope[VerifyOut]:
    token = pairing.verify_and_pair(db, body.code, body.device_id)
    db.commit()
    return ok(VerifyOut(paired=bool(token), token=token))


@router.post("/revoke", response_model=Envelope[dict])
def revoke(
    body: RevokeIn, _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[dict]:
    removed = pairing.revoke_device(db, body.device_id)
    db.commit()
    return ok({"device_id": body.device_id, "revoked": removed})


@router.post("/codes/clean", response_model=Envelope[dict])
def clean_codes(_auth=Depends(require_auth), db: Session = Depends(get_db)) -> Envelope[dict]:
    removed = pairing.clear_codes(db)
    db.commit()
    return ok({"removed": removed})


__all__ = ["router"]
