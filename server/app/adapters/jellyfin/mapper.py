"""Jellyfin 原始 Item 到统一 DTO 的映射。

media_id 规则(见 docs/ARCHITECTURE.md 第 8 节):
- 保留 jellyfin_id(Jellyfin Item ID,库重建前稳定)
- 另生成项目稳定 media_id,基于 jellyfin_id 哈希
- fingerprint 由 path + size + modified 生成,用于缓存失效与重扫关联
"""

from __future__ import annotations

import hashlib

from app.adapters.jellyfin.models import JFItem, Library, MediaItem, MediaType

_VIDEO_TYPES = {"Movie", "Episode", "Video", "MusicVideo"}
_IMAGE_TYPES = {"Photo"}

# 参与媒体墙/批阅的 Jellyfin Item 类型
PLAYABLE_ITEM_TYPES = _VIDEO_TYPES | _IMAGE_TYPES


def media_type_of(item_type: str) -> MediaType | None:
    if item_type in _VIDEO_TYPES:
        return "video"
    if item_type in _IMAGE_TYPES:
        return "image"
    return None


def include_types_for(media_type: MediaType | None) -> str | None:
    """按统一 media_type 映射 Jellyfin IncludeItemTypes,None 表示全部可播放类型。"""
    if media_type == "video":
        return ",".join(sorted(_VIDEO_TYPES))
    if media_type == "image":
        return ",".join(sorted(_IMAGE_TYPES))
    return None


def compute_media_id(jellyfin_id: str) -> str:
    return hashlib.sha256(jellyfin_id.encode("utf-8")).hexdigest()[:24]


def compute_fingerprint(item: JFItem) -> str:
    canonical_path = (item.path or "").replace("\\", "/").lower()
    modified = item.date_modified.isoformat() if item.date_modified else ""
    raw = f"{canonical_path}|{item.size_bytes or 0}|{modified}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def map_library(view: JFItem) -> Library:
    return Library(
        jellyfin_id=view.jellyfin_id,
        name=view.name or "未命名媒体库",
        collection_type=view.collection_type,
    )


def map_media_item(item: JFItem, library_id: str) -> MediaItem:
    media_type = media_type_of(item.item_type)
    if media_type is None:
        raise ValueError(f"不支持的 Jellyfin 类型: {item.item_type!r}")
    duration_ms = item.run_time_ticks // 10_000 if item.run_time_ticks else None
    return MediaItem(
        media_id=compute_media_id(item.jellyfin_id),
        jellyfin_id=item.jellyfin_id,
        name=item.name or "未命名",
        media_type=media_type,
        library_id=library_id,
        duration_ms=duration_ms,
        size_bytes=item.size_bytes,
        width=item.width,
        height=item.height,
        container=item.container,
        created_at=item.date_created,
        modified_at=item.date_modified,
        media_path=item.path,
        fingerprint=compute_fingerprint(item),
    )


__all__ = [
    "PLAYABLE_ITEM_TYPES",
    "compute_fingerprint",
    "compute_media_id",
    "include_types_for",
    "map_library",
    "map_media_item",
    "media_type_of",
]
