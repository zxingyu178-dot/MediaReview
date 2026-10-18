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


class JFMediaSource(BaseModel):
    id: str = ""
    size: int | None = None
    run_time_ticks: int | None = None
    container: str | None = None
    path: str | None = None

    @classmethod
    def from_raw(cls, raw: dict[str, Any]) -> JFMediaSource:
        return cls(
            id=raw.get("Id") or "",
            size=_positive_int(raw.get("Size")),
            run_time_ticks=_optional_int(raw.get("RunTimeTicks")),
            container=raw.get("Container"),
            path=raw.get("Path"),
        )


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
    media_sources: list[JFMediaSource] = Field(default_factory=list)

    @classmethod
    def from_raw(cls, raw: dict[str, Any]) -> JFItem:
        return cls(
            jellyfin_id=raw.get("Id", ""),
            name=raw.get("Name", ""),
            item_type=raw.get("Type", ""),
            path=raw.get("Path"),
            size_bytes=_positive_int(raw.get("Size")),
            run_time_ticks=_optional_int(raw.get("RunTimeTicks")),
            width=_optional_int(raw.get("Width")),
            height=_optional_int(raw.get("Height")),
            container=raw.get("Container"),
            parent_id=raw.get("ParentId"),
            collection_type=raw.get("CollectionType"),
            date_created=_parse_datetime(raw.get("DateCreated")),
            date_modified=_parse_datetime(raw.get("DateModified")),
            location_type=raw.get("LocationType"),
            media_sources=[
                JFMediaSource.from_raw(source)
                for source in (raw.get("MediaSources") or [])
                if isinstance(source, dict)
            ],
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


# 历史导入路径保留为兼容再导出；DTO由来源中立模块拥有。
from app.adapters.media_source import LibrarySource as Library, MediaItem  # noqa: E402,F401


def _optional_int(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None

    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _positive_int(value: Any) -> int | None:
    parsed = _optional_int(value)
    return parsed if parsed is not None and parsed > 0 else None


__all__ = [
    "JFItem",
    "JFMediaSource",
    "JFItemsPage",
    "JFSystemInfo",
    "JFUser",
    "Library",
    "MediaItem",
    "MediaType",
]
