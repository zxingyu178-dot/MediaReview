"""本地来源登记仅允许电脑回环访问；手机只得到无路径的来源摘要。"""
import ipaddress

import sqlalchemy as sa
from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.adapters.media_source import can_delete
from app.api.v1.auth import require_auth
from app.core.errors import ForbiddenError, ValidationFailedError
from app.core.responses import ok
from app.db.models import LocalRoot
from app.db.session import get_db
from app.services import native_sources
from app.services.tasks import safe_task_view

router = APIRouter(prefix="/sources", tags=["sources"])


def _loopback(host):
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def require_local_admin(request: Request):
    if (
        not request.client
        or not _loopback(request.client.host)
        or not _loopback(request.url.hostname)
    ):
        raise ForbiddenError("请在电脑本机管理后台登记或扫描本地来源")
    origin = request.headers.get("origin")
    if origin and origin != str(request.base_url).rstrip("/"):
        raise ForbiddenError("禁止跨站修改本地来源")
    if request.headers.get("sec-fetch-site") == "cross-site":
        raise ForbiddenError("禁止跨站修改本地来源")


class RegisterRoot(BaseModel):
    name: str = Field(min_length=1, max_length=256)
    path: str = Field(min_length=1, max_length=1024)


def root_view(root):
    # 与媒体/库摘要同源：跟随能力表，本地源自 native_delete_v1 起可两阶段删除。
    return {"source_id": root.root_id, "source_type": "local", "name": root.name,
            "status": root.status, "enabled": root.enabled, "can_delete": can_delete("local"),
            "last_scan_at": root.last_scan_at}


@router.get("")
def list_sources(_auth=Depends(require_auth), db: Session = Depends(get_db)):
    return ok([root_view(r) for r in db.scalars(sa.select(LocalRoot))])


@router.post("/local", status_code=202, dependencies=[Depends(require_local_admin)])
def register_local(body: RegisterRoot, request: Request, db: Session = Depends(get_db)):
    candidate = native_sources.validate_root(body.path).resolve()
    managed = request.app.state.paths.data_root.resolve()
    if candidate.is_relative_to(managed) or managed.is_relative_to(candidate):
        raise ValidationFailedError("媒体来源必须与服务器数据库和缓存目录分开")
    root = native_sources.register_root(db, body.name, body.path)
    task = native_sources.schedule_scan(db, root.root_id)
    # 实际HTTP响应可早于yield依赖收尾；成功前提交，下一请求立即可见任务。
    db.commit()
    return ok({"source": root_view(root), "task": safe_task_view(task)})


@router.post("/{root_id}/scan", status_code=202, dependencies=[Depends(require_local_admin)])
def scan_local(root_id: str, db: Session = Depends(get_db)):
    task = native_sources.schedule_scan(db, root_id)
    db.commit()
    return ok(safe_task_view(task))
