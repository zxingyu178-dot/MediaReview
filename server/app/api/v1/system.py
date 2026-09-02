"""系统接口: 健康检查、运行信息、存储统计、诊断导出。"""

from __future__ import annotations

import datetime as dt
import io
import json
import os
import re
import shutil
import socket
import zipfile
from typing import Any

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import PlainTextResponse, StreamingResponse
from pydantic import BaseModel
from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from app.adapters.jellyfin.client import JellyfinClient
from app.api.v1.auth import require_localhost_or_auth
from app.core.errors import BadRequestError
from app.core.responses import Envelope, ok
from app.db.models import MediaCacheIndex, MediaSyncState, SpriteManifest
from app.db.session import get_db
from app.services import media_index, sprite

router = APIRouter(prefix="/system", tags=["system"])

# 日志脱敏: 绝不暴露 token / 配对码 / api_key 等敏感字段
_SENSITIVE_PATTERNS = (
    # 本项目 bearer token: mr_ + 40 位字母数字
    (re.compile(r"\bmr_[A-Za-z0-9]{20,}\b"), "mr_***"),
    # Authorization 头中的 Bearer 明文
    (re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._~+/=-]{8,}"), r"\1***"),
    # Jellyfin API Key 常见形态(带引号或不带引号的整段值)
    (re.compile(r"(?i)(api[_-]?key\s*[=:]\s*)(?:\"[^\"]*\"|[^\s\"'&]+)"), r"\1***"),
    # JSON 字段值脱敏: "api_key": "xxx" / "code": "123456"
    (re.compile(r'("api[_-]?key"\s*:\s*")[^"]*(")'), r"\1***\2"),
    (re.compile(r'("code"\s*:\s*")[^"]*(")'), r"\1***\2"),
    # 日志文本形态: code="654321" / code=654321 / pairing code 123456
    (re.compile(r"(?i)(\bcode\s*=\s*[\"']?)[0-9]{6}"), r"\1***"),
    # 媒体/数据绝对路径只留根标记: Windows 盘符、UNC、类 Unix 挂载点
    (re.compile(r"(?i)([a-z]:\\)[^\s\r\n\"'<>]*"), r"\1***"),
    (re.compile(r"(?i)\\\\[^\s\r\n\"']+"), r"\\\\***"),
    (re.compile(r"(?i)(/(?:mnt|media|home|data|opt|var)/)[^\s\r\n\"']*"), r"\1***"),
)


def _mask_log_text(text: str) -> str:
    for pattern, repl in _SENSITIVE_PATTERNS:
        text = pattern.sub(repl, text)
    return text


class HealthData(BaseModel):
    status: str
    version: str
    components: dict[str, str]


class StorageData(BaseModel):
    data_root: str
    cache_total_bytes: int
    cache_breakdown: dict[str, int]
    disk_free_bytes: int
    disk_total_bytes: int


def _directory_size(path: Any) -> int:
    total = 0
    if not os.path.isdir(path):
        return total
    with os.scandir(path) as entries:
        for entry in entries:
            if entry.is_symlink():
                continue
            if entry.is_file(follow_symlinks=False):
                total += entry.stat(follow_symlinks=False).st_size
            elif entry.is_dir(follow_symlinks=False):
                total += _directory_size(entry.path)
    return total


def _database_status(request: Request) -> str:
    database = request.app.state.database
    try:
        with database.engine.connect() as connection:
            connection.execute(text("SELECT 1"))
    except Exception:  # noqa: BLE001 健康检查需要吞掉一切数据库异常
        return "error"
    return "ok"


def _lan_ipv4() -> str:
    """返回本机首个非回环 IPv4 地址(尽力而为,失败返回空串)。"""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))  # 仅取本地出站地址,不实际发包
            return sock.getsockname()[0]
    except OSError:
        pass
    try:
        for entry in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = entry[4][0]
            if not ip.startswith("127."):
                return ip
    except OSError:
        pass
    return ""


def _recent_log_lines(
    request: Request, *, levels: tuple[str, ...] | None, tail_bytes: int
) -> list[str]:
    """从滚动日志读取最近行;返回前已做敏感字段脱敏。"""
    logs_dir = request.app.state.paths.logs_dir
    lines: list[str] = []
    if not logs_dir.is_dir():
        return lines
    for log_file in sorted(logs_dir.glob("*.log")):
        try:
            text_content = log_file.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if len(text_content) > tail_bytes:
            text_content = text_content[-tail_bytes:]
        for raw in text_content.splitlines():
            if levels is not None and not any(level in raw for level in levels):
                continue
            lines.append(_mask_log_text(raw))
    return lines


@router.get("/health", response_model=Envelope[HealthData])
def health(request: Request) -> Envelope[HealthData]:
    """健康检查。database 异常时整体 status=degraded。"""
    from app import __version__

    database_status = _database_status(request)
    jellyfin_configured = request.app.state.settings.jellyfin.is_configured()
    data = HealthData(
        status="ok" if database_status == "ok" else "degraded",
        version=__version__,
        components={
            "database": database_status,
            "jellyfin": "not_configured" if not jellyfin_configured else "configured",
        },
    )
    return ok(data)


@router.get("/info", response_model=Envelope[dict[str, Any]])
def info(request: Request, _auth=Depends(require_localhost_or_auth)) -> Envelope[dict[str, Any]]:
    """运行信息(配置脱敏,不暴露 API Key)。敏感接口:本机或已认证设备。"""
    from app import __version__

    settings = request.app.state.settings
    data: dict[str, Any] = {
        "app": "MediaReview Server",
        "version": __version__,
        "data_root": str(request.app.state.paths.data_root),
        "config": settings.masked_dict(),
    }
    return ok(data)


@router.get("/storage", response_model=Envelope[StorageData])
def storage(request: Request, _auth=Depends(require_localhost_or_auth)) -> Envelope[StorageData]:
    """存储统计: 缓存占用与磁盘剩余空间。敏感接口:本机或已认证设备。"""
    paths = request.app.state.paths
    breakdown = {
        "thumbnails": _directory_size(paths.thumbnails_dir),
        "sprites": _directory_size(paths.sprites_dir),
        "previews": _directory_size(paths.previews_dir),
        "temp": _directory_size(paths.temp_dir),
    }
    usage = shutil.disk_usage(paths.data_root)
    data = StorageData(
        data_root=str(paths.data_root),
        cache_total_bytes=sum(breakdown.values()),
        cache_breakdown=breakdown,
        disk_free_bytes=usage.free,
        disk_total_bytes=usage.total,
    )
    return ok(data)


@router.get("/diagnostics/export")
def diagnostics_export(
    request: Request, _auth=Depends(require_localhost_or_auth)
) -> StreamingResponse:
    """一键导出诊断包: 日志 + 脱敏配置 + 版本信息 + 缓存统计 + 关键表计数。

    绝不包含真实 API Key / token / 原始媒体文件;日志在打包前做敏感字段脱敏。
    """
    from app import __version__

    paths = request.app.state.paths
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
        info = {
            "app": "MediaReview Server",
            "version": __version__,
            "data_root": str(paths.data_root),
            "config": request.app.state.settings.masked_dict(),
            "generated_at": dt.datetime.now(dt.UTC).isoformat(),
        }
        z.writestr(
            "system_info.json",
            json.dumps(info, ensure_ascii=False, indent=2, default=str),
        )

        breakdown = {
            "thumbnails": _directory_size(paths.thumbnails_dir),
            "sprites": _directory_size(paths.sprites_dir),
            "previews": _directory_size(paths.previews_dir),
            "temp": _directory_size(paths.temp_dir),
        }
        usage = shutil.disk_usage(paths.data_root)
        z.writestr(
            "storage.json",
            json.dumps(
                {
                    "cache_total_bytes": sum(breakdown.values()),
                    "cache_breakdown": breakdown,
                    "disk_free_bytes": usage.free,
                    "disk_total_bytes": usage.total,
                },
                ensure_ascii=False,
                indent=2,
            ),
        )

        counts: dict[str, Any] = {}
        db = request.app.state.database
        for table in (
            "media_cache_index",
            "favorite",
            "delete_queue",
            "review_session",
            "review_session_item",
            "sprite_manifest",
            "background_task",
            "paired_device",
        ):
            try:
                with db.engine.connect() as connection:
                    counts[table] = connection.execute(
                        text(f"SELECT COUNT(*) FROM {table}")  # noqa: S608 - 表名来自固定白名单
                    ).scalar()
            except Exception:  # noqa: BLE001 诊断导出不因单表失败中断
                counts[table] = "?"
        z.writestr("table_counts.json", json.dumps(counts, ensure_ascii=False, indent=2))

        logs_dir = paths.logs_dir
        if logs_dir.is_dir():
            for log_file in sorted(logs_dir.glob("*.log"))[-10:]:
                if log_file.is_file() and log_file.stat().st_size < 2 * 1024 * 1024:
                    masked = _mask_log_text(log_file.read_text(encoding="utf-8", errors="replace"))
                    z.writestr(f"logs/{log_file.name}", masked)

    buffer.seek(0)
    filename = f"mediareview-diagnostics-{dt.datetime.now().strftime('%Y%m%d_%H%M')}.zip"
    return StreamingResponse(
        buffer,
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@router.get("/dashboard", response_model=Envelope[dict])
async def dashboard(
    request: Request,
    _auth=Depends(require_localhost_or_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    """运维面板聚合状态: 服务/Jellyfin/媒体库/索引/同步(尽力而为,绝不外泄敏感值)。"""
    from app import __version__

    settings = request.app.state.settings
    jellyfin: dict[str, Any] = {
        "configured": settings.jellyfin.is_configured(),
        "reachable": False,
        "name": None,
        "version": None,
        "error": None,
    }
    if jellyfin["configured"]:
        try:
            async with JellyfinClient(settings.jellyfin) as client:
                info = await client.system_info()
            jellyfin.update(reachable=True, name=info.server_name, version=info.version)
        except Exception:  # noqa: BLE001 面板尽力而为,不因上游不可达失败
            jellyfin["error"] = "Jellyfin 不可达,请检查服务器与 API Key"

    library_rows = media_index.library_selection_rows(db)
    selected = [row.jellyfin_id for row in library_rows if row.selected]
    available = media_index.available_media_count(db, selected) if selected else 0
    if selected:
        sync = media_index.media_sync_view(db, selected, available_count=available)
    else:
        sync = {
            "state": "idle",
            "stale": False,
            "task_id": None,
            "processed": 0,
            "total": 0,
            "last_success_at": None,
            "message": "尚未勾选媒体库",
        }
    failed = db.scalar(
        select(func.count()).select_from(MediaSyncState).where(MediaSyncState.state == "failed")
    )
    sync["has_error"] = bool(failed)
    data = {
        "version": __version__,
        "host": settings.server.host,
        "port": settings.server.port,
        "lan_address": _lan_ipv4(),
        "jellyfin": jellyfin,
        "libraries": {"total": len(library_rows), "selected": len(selected)},
        "index": {
            "media_count": db.scalar(select(func.count()).select_from(MediaCacheIndex)),
            "video_count": db.scalar(
                select(func.count())
                .select_from(MediaCacheIndex)
                .where(MediaCacheIndex.media_type == "video")
            ),
            "photo_count": db.scalar(
                select(func.count())
                .select_from(MediaCacheIndex)
                .where(MediaCacheIndex.media_type == "photo")
            ),
        },
        "sync": sync,
    }
    return ok(data)


@router.post("/cache/clear", response_model=Envelope[dict])
def cache_clear(
    request: Request,
    confirm: str | None = Query(default=None, max_length=8),
    _auth=Depends(require_localhost_or_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    """清理可再生成的缓存文件(缩略图/雪碧图/预览/临时)。

    危险操作: 必须显式 confirm=true/1,否则拒绝,防止误触与 CSRF 式误删。
    只清 cache/ 下文件,绝不触碰数据库、配置与媒体原文件。
    """
    if confirm not in ("true", "1"):
        raise BadRequestError("危险操作已被拒绝: 请确认后再次清理缓存")
    paths = request.app.state.paths
    removed_files = 0
    removed_bytes = 0
    for category in ("thumbnails", "sprites", "previews", "temp"):
        directory = paths.cache_dir / category
        if not directory.is_dir():
            continue
        for entry in sorted(directory.rglob("*"), reverse=True):
            if entry.is_file():
                try:
                    removed_bytes += entry.stat().st_size
                    entry.unlink()
                    removed_files += 1
                except OSError:
                    pass
    # 雪碧图清单与文件同步失效,避免 ready 但文件缺失的不一致
    ready = db.scalars(select(SpriteManifest).where(SpriteManifest.status == "ready")).all()
    for manifest in ready:
        sprite.invalidate_manifest(db, manifest.media_id, paths.sprites_dir)
    db.commit()
    return ok({"removed_files": removed_files, "removed_bytes": removed_bytes})


@router.get("/errors", response_model=Envelope[list[str]])
def recent_errors(
    request: Request,
    limit: int = Query(default=50, ge=1, le=200),
    _auth=Depends(require_localhost_or_auth),
) -> Envelope[list[str]]:
    """最近脱敏错误行(来自滚动日志,绝不外泄 token/配对码/绝对路径)。"""
    lines = _recent_log_lines(request, levels=("ERROR", "CRITICAL"), tail_bytes=512 * 1024)
    return ok(lines[-limit:])


@router.get("/logs")
def logs_download(
    request: Request,
    _auth=Depends(require_localhost_or_auth),
) -> PlainTextResponse:
    """下载最近脱敏日志(纯文本,不含任何敏感值)。"""
    body = "\n".join(_recent_log_lines(request, levels=None, tail_bytes=512 * 1024))
    return PlainTextResponse(
        body,
        headers={"Content-Disposition": 'attachment; filename="mediareview-server.log"'},
    )
