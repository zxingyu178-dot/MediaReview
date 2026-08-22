"""Jellyfin 响应模型与本项目统一媒体 DTO。

两类模型:
- JF* 前缀:Jellyfin 原始响应的宽松解析,只允许在本包内存在
- Library / MediaItem:统一 DTO,是 adapter 对外的唯一输出
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any, Literal

from pydantic import BaseModel, Field

MediaType = Literal["video", "image"]

# Jellyfin 时间戳(ISO 8601,多数带时区)统一转 UTC


def _parse_datetime(value: Any) -> datetime | None:
    if not value or not isinstance(value, str):
        return None
    text = value.strip()
    if not text:
        return None
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(UTC).replace(tzinfo=None)
    return parsed


class JFSystemInfo(BaseModel):
    server_name: str = ""
    version: str = ""
    jellyfin_id: str = ""

    @classmethod
    def from_raw(cls, raw: dict[str, Any]) -> JFSystemInfo:
        return cls(
            server_name=raw.get("ServerName", ""),
            version=raw.get("Version", ""),
            jellyfin_id=raw.get("Id", ""),
        )


class JFUser(BaseModel):
    jellyfin_id: str
    name: str = ""

    @classmethod
    def from_raw(cls, raw: dict[str, Any]) -> JFUser:
        return cls(jellyfin_id=raw.get("Id", ""), name=raw.get("Name", ""))


class JFItem(BaseModel):
    """Jellyfin Item 的最小字段集,宽松解析,字段缺失一律为 None。"""

    jellyfin_id: str
    name: str = ""
    item_type: str = ""
    path: str | None = None
    size_bytes: int | None = None
    run_time_ticks: int | None = None
    width: int | None = None
    height: int | None = None
    container: str | None = None
    parent_id: str | None = None
    collection_type: str | None = None
    date_created: datetime | None = None
    date_modified: datetime | None = None
    location_type: str | None = None

    @classmethod
    def from_raw(cls, raw: dict[str, Any]) -> JFItem:
        return cls(
            jellyfin_id=raw.get("Id", ""),
            name=raw.get("Name", ""),
            item_type=raw.get("Type", ""),
            path=raw.get("Path"),
            size_bytes=_optional_int(raw.get("Size")),
            run_time_ticks=_optional_int(raw.get("RunTimeTicks")),
            width=_optional_int(raw.get("Width")),
            height=_optional_int(raw.get("Height")),
            container=raw.get("Container"),
            parent_id=raw.get("ParentId"),
            collection_type=raw.get("CollectionType"),
            date_created=_parse_datetime(raw.get("DateCreated")),
            date_modified=_parse_datetime(raw.get("DateModified")),
            location_type=raw.get("LocationType"),
        )


class JFItemsPage(BaseModel):
    items: list[JFItem] = Field(default_factory=list)
    total_record_count: int = 0
    start_index: int = 0

    @classmethod
    def from_raw(cls, raw: dict[str, Any]) -> JFItemsPage:
        return cls(
            items=[JFItem.from_raw(item) for item in raw.get("Items", [])],
            total_record_count=int(raw.get("TotalRecordCount", 0)),
            start_index=int(raw.get("StartIndex", 0)),
        )


# ---- 统一 DTO(adapter 对外唯一输出) ----


class Library(BaseModel):
    jellyfin_id: str
    name: str
    collection_type: str | None = None


class MediaItem(BaseModel):
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
    # 服务端内部使用(ffprobe/雪碧图),绝不暴露给客户端
    media_path: str | None = None
    # 指纹 = canonical path + size + modified,供缓存失效与文件重扫后重新关联
    fingerprint: str = ""


def _optional_int(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


__all__ = [
    "JFItem",
    "JFItemsPage",
    "JFSystemInfo",
    "JFUser",
    "Library",
    "MediaItem",
    "MediaType",
]
