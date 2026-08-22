"""PathManager 测试。"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.core.paths import PathManager


def test_ensure_creates_full_structure(tmp_path: Path) -> None:
    manager = PathManager(tmp_path / "data")
    manager.ensure()
    for directory in (
        manager.config_dir,
        manager.database_dir,
        manager.thumbnails_dir,
        manager.sprites_dir,
        manager.previews_dir,
        manager.temp_dir,
        manager.logs_dir,
        manager.runtime_dir,
        manager.backups_dir,
    ):
        assert directory.is_dir(), directory


def test_directory_layout_matches_architecture(tmp_path: Path) -> None:
    manager = PathManager(tmp_path / "data")
    assert manager.database_path == tmp_path / "data" / "database" / "mediareview.db"
    assert manager.config_file == tmp_path / "data" / "config" / "config.json"
    assert manager.sprites_dir == tmp_path / "data" / "cache" / "sprites"


def test_cache_shard_dir_layout(tmp_path: Path) -> None:
    manager = PathManager(tmp_path)
    shard = manager.cache_shard_dir("sprites", "a8f37291abcd")
    assert shard == tmp_path / "cache" / "sprites" / "a8" / "a8f37291abcd"


@pytest.mark.parametrize(
    ("category", "key"),
    [
        ("../evil", "aabb"),
        ("Sprites", "aabb"),
        ("sprites", "ZZFF"),
        ("sprites", "x"),
        ("sprites", "aabb cdef"),
    ],
)
def test_cache_shard_dir_rejects_bad_input(tmp_path: Path, category: str, key: str) -> None:
    manager = PathManager(tmp_path)
    with pytest.raises(ValueError):
        manager.cache_shard_dir(category, key)
