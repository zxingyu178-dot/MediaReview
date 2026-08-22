"""系统接口: 健康检查、运行信息、存储统计、诊断导出。"""

from __future__ import annotations

import datetime as dt
import io
import json
import os
import re
import shutil
import zipfile
from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from sqlalchemy import text

from app.api.v1.auth import require_localhost_or_auth
from app.core.responses import Envelope, ok

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
