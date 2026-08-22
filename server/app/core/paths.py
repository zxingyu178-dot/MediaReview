"""路径管理系统。

所有 MediaReview 自产文件(数据库、缓存、日志)只能存在于数据根目录下,
绝对禁止写入媒体所在目录。结构见 docs/ARCHITECTURE.md 第 9 节。
"""

from __future__ import annotations

import re
from pathlib import Path

_CACHE_CATEGORY_RE = re.compile(r"^[a-z][a-z0-9_]{0,31}$")
_CACHE_KEY_RE = re.compile(r"^[0-9a-f]{2,128}$")


class PathManager:
    """统一管理数据根目录下的子目录结构。"""

    def __init__(self, data_root: Path) -> None:
        self.data_root = Path(data_root)

    # ---- 目录 ----

    @property
    def config_dir(self) -> Path:
        return self.data_root / "config"

    @property
    def database_dir(self) -> Path:
        return self.data_root / "database"

    @property
    def database_path(self) -> Path:
        return self.database_dir / "mediareview.db"

    @property
    def cache_dir(self) -> Path:
        return self.data_root / "cache"

    @property
    def thumbnails_dir(self) -> Path:
        return self.cache_dir / "thumbnails"

    @property
    def sprites_dir(self) -> Path:
        return self.cache_dir / "sprites"

    @property
    def previews_dir(self) -> Path:
        return self.cache_dir / "previews"

    @property
    def temp_dir(self) -> Path:
        return self.cache_dir / "temp"

    @property
    def logs_dir(self) -> Path:
        return self.data_root / "logs"

    @property
    def runtime_dir(self) -> Path:
        return self.data_root / "runtime"

    @property
    def backups_dir(self) -> Path:
        return self.data_root / "backups"

    @property
    def config_file(self) -> Path:
        return self.config_dir / "config.json"

    def ensure(self) -> None:
        """创建全部运行目录(已存在则跳过)。"""
        for directory in (
            self.config_dir,
            self.database_dir,
            self.thumbnails_dir,
            self.sprites_dir,
            self.previews_dir,
            self.temp_dir,
            self.logs_dir,
            self.runtime_dir,
            self.backups_dir,
        ):
            directory.mkdir(parents=True, exist_ok=True)

    # ---- 缓存分片 ----

    def cache_shard_dir(self, category: str, key: str) -> Path:
        """返回缓存分片目录: cache/<category>/<key前两位>/<key>/。

        category 用于区分 thumbnails/sprites 等类型,key 为小写十六进制指纹。
        """
        if not _CACHE_CATEGORY_RE.match(category):
            raise ValueError(f"非法缓存类别: {category!r}")
        if not _CACHE_KEY_RE.match(key):
            raise ValueError(f"非法缓存键: {key!r}")
        return self.cache_dir / category / key[:2] / key
