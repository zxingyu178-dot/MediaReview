"""Jellyfin 映射器测试: 类型识别、ticks 换算、指纹稳定性。"""

from __future__ import annotations

import pytest

from app.adapters.jellyfin.mapper import (
    compute_fingerprint,
    compute_media_id,
    map_library,
    map_media_item,
    media_type_of,
)
from app.adapters.jellyfin.models import JFItem


def _video_item() -> JFItem:
    return JFItem.from_raw(
        {
            "Id": "it-1",
            "Name": "Clip.mp4",
            "Type": "Video",
            "Path": "D:\\Media\\Clip.mp4",
            "Size": 1024,
            "RunTimeTicks": 18_240_000,  # 1 tick = 100ns → 1824ms
            "DateModified": "2025-01-02T03:04:05.000Z",
        }
    )


@pytest.mark.parametrize(
    ("item_type", "expected"),
    [
        ("Movie", "video"),
        ("Episode", "video"),
        ("Video", "video"),
        ("MusicVideo", "video"),
        ("Photo", "image"),
        ("Folder", None),
        ("Audio", None),
    ],
)
def test_media_type_of(item_type: str, expected: str | None) -> None:
    assert media_type_of(item_type) == expected


def test_ticks_converted_to_ms() -> None:
    media = map_media_item(_video_item(), library_id="lib-1")
    assert media.duration_ms == 1824


def test_media_id_stable_and_no_path_leak() -> None:
    media = map_media_item(_video_item(), library_id="lib-1")
    assert media.media_id == compute_media_id("it-1")
    assert len(media.media_id) == 24
    # media_id 不含路径片段
    assert "Clip" not in media.media_id


def test_fingerprint_normalizes_path() -> None:
    """反斜杠/正斜杠与大小写差异不应改变指纹。"""
    base = _video_item()
    variant = JFItem.from_raw(
        {
            "Id": "it-1",
            "Name": "Clip.mp4",
            "Type": "Video",
            "Path": "d:/media/clip.mp4",
            "Size": 1024,
            "RunTimeTicks": 1,
            "DateModified": "2025-01-02T03:04:05",
        }
    )
    assert compute_fingerprint(base) == compute_fingerprint(variant)


def test_fingerprint_changes_with_size() -> None:
    item = _video_item()
    bigger = item.model_copy(update={"size_bytes": (item.size_bytes or 0) + 1})
    assert compute_fingerprint(item) != compute_fingerprint(bigger)


def test_map_media_item_rejects_unsupported_type() -> None:
    folder = JFItem.from_raw({"Id": "f-1", "Type": "Folder"})
    with pytest.raises(ValueError, match="不支持的 Jellyfin 类型"):
        map_media_item(folder, library_id="lib-1")


def test_map_library_defaults_name() -> None:
    view = JFItem.from_raw({"Id": "lib-9", "Type": "CollectionFolder"})
    library = map_library(view)
    assert library.name == "未命名媒体库"
    assert library.collection_type is None


def test_datetime_parsed_as_naive_utc() -> None:
    media = map_media_item(_video_item(), library_id="lib-1")
    assert media.modified_at is not None
    assert media.modified_at.tzinfo is None
    assert media.modified_at.year == 2025
