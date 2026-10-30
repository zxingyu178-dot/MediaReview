"""来源中立的索引DTO和能力边界；不把Jellyfin原始对象传播到本地来源。"""
from dataclasses import dataclass
from datetime import datetime
from typing import Literal

from pydantic import BaseModel

MediaType = Literal["video", "image"]


class LibrarySource(BaseModel):
    # 兼容既有API/SQLite字段；本地来源填local_不透明身份。
    jellyfin_id: str
    name: str
    collection_type: str | None = None
    source_type: str = "jellyfin"


class MediaItem(BaseModel):
    source_type: str = "jellyfin"
    media_id: str
    jellyfin_id: str
    name: str
    media_type: MediaType
    library_id: str
    duration_ms: int | None = None
    size_bytes: int | None = None
    width: int | None = None
    height: int | None = None
    container: str | None = None
    created_at: datetime | None = None
    modified_at: datetime | None = None
    media_path: str | None = None
    fingerprint: str = ""


@dataclass(frozen=True)
class MediaSource:
    """来源策略；发现/读取实现由各自adapter/service承担，不复制队列/缓存。"""
    source_type: str
    can_delete: bool
    discovery: str
    images: str
    playback: str
    progress: str


SOURCES = {
    "jellyfin": MediaSource("jellyfin", True, "jellyfin", "jellyfin", "direct_hls", "jellyfin"),
    # 2026-10-24 起本地源支持"两阶段安全删除"(能力名 native_delete_v1)。
    # 历史能力名 native_read_only_v1 仅在 Server 能力表中作为兼容名保留，
    # 删除安全边界由 native_sources/delete_queue 的路径与指纹复验保证。
    "local": MediaSource(
        "local", True, "local_scan", "local_thumbnail", "authenticated_range", "sqlite"
    ),
}


def can_delete(source_type):
    source = SOURCES.get(source_type)
    return source is not None and source.can_delete
