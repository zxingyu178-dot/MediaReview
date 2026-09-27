"""Server 侧缩略图磁盘缓存(媒体墙封面)。

背景: ``cache/thumbnails/`` 目录早已存在,但媒体墙从未使用它 ——
每个封面请求都要回源 Jellyfin。首屏 8~10 张封面就是 8~10 次上游请求,
这正是"封面一张张慢慢冒出来"的直接原因。

硬约束(阶段 8A.1):
- cache key **至少**包含 media_id + 源指纹 + 变体,绝不允许只按 media_id,
  否则媒体内容更新后仍会命中旧封面;
- 写入必须**原子化**: 先写临时文件,再 ``os.replace``,避免并发下留下半张图;
- 同一 key 的并发 MISS 做 **single-flight**: 第一个请求回源,其余等待同一结果;
- HIT 时**绝不**访问 Jellyfin。
"""

from __future__ import annotations

import asyncio
import hashlib
import os
import uuid
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

from app.core.logging import get_logger
from app.core.paths import PathManager

logger = get_logger("thumbnail_cache")

CATEGORY = "thumbnails"

_EXTENSIONS = {
    "image/jpeg": ".jpg",
    "image/jpg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
    "image/gif": ".gif",
    "image/bmp": ".bmp",
    "image/avif": ".avif",
}
_CONTENT_TYPES = {ext: content_type for content_type, ext in _EXTENSIONS.items()}
_FALLBACK_EXTENSION = ".img"

# 变体名参与 cache key: 尺寸/裁剪方式变化时必须自然失效
VARIANT_GRID = "grid_480"


def thumbnail_cache_key(*, media_id: str, fingerprint: str, variant: str) -> str:
    """sha256(media_id + 源指纹 + 变体)。"""
    raw = f"{media_id}\n{fingerprint}\n{variant}".encode()
    return hashlib.sha256(raw).hexdigest()


def media_source_fingerprint(row: Any) -> str:
    """媒体源指纹: 上游内容变化的判定依据(不涉及任何文件路径)。"""
    modified = getattr(row, "modified_at", None)
    return "|".join(
        (
            str(getattr(row, "jellyfin_id", "") or ""),
            modified.isoformat() if modified else "",
            str(getattr(row, "size_bytes", None) or 0),
        )
    )


class ThumbnailCacheService:
    """缩略图磁盘缓存 + 并发合并。"""

    def __init__(self, paths: PathManager) -> None:
        self._paths = paths
        self._locks: dict[str, asyncio.Lock] = {}
        self.hits = 0
        self.misses = 0
        self.singleflight_waits = 0

    # ---- 路径 ----

    def _shard(self, key: str) -> Path:
        return self._paths.cache_shard_dir(CATEGORY, key)

    def _locate(self, key: str) -> tuple[Path, str] | None:
        directory = self._shard(key)
        try:
            entries = list(directory.iterdir())
        except (FileNotFoundError, NotADirectoryError):
            return None
        for entry in entries:
            content_type = _CONTENT_TYPES.get(entry.suffix.lower())
            if content_type is None or not entry.is_file():
                continue
            try:
                if entry.stat().st_size <= 0:
                    continue
            except OSError:
                continue
            return entry, content_type
        return None

    # ---- 读 / 写 ----

    def read(self, key: str) -> tuple[bytes, str] | None:
        located = self._locate(key)
        if located is None:
            return None
        path, content_type = located
        try:
            return path.read_bytes(), content_type
        except OSError:
            return None

    def write(self, key: str, payload: bytes, content_type: str) -> None:
        """原子写入: 临时文件写完再 replace,避免并发读到半张图。"""
        if not payload:
            return
        directory = self._shard(key)
        directory.mkdir(parents=True, exist_ok=True)
        extension = _EXTENSIONS.get(content_type.lower(), _FALLBACK_EXTENSION)
        final_path = directory / f"{key}{extension}"
        temp_path = directory / f".{key}.{uuid.uuid4().hex}.tmp"
        try:
            with temp_path.open("wb") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_path, final_path)
        except OSError as exc:  # 缓存失败不能影响主流程
            logger.warning("缩略图缓存写入失败: %s", exc)
            try:
                temp_path.unlink(missing_ok=True)
            except OSError:
                pass

    # ---- single-flight ----

    async def get_or_create(
        self,
        key: str,
        loader: Callable[[], Awaitable[tuple[bytes, str]]],
    ) -> tuple[bytes, str, bool]:
        """返回 (payload, content_type, hit)。并发同 key 只回源一次。"""
        cached = self.read(key)
        if cached is not None:
            self.hits += 1
            return cached[0], cached[1], True

        lock = self._locks.get(key)
        if lock is None:
            lock = asyncio.Lock()
            self._locks[key] = lock
        else:
            self.singleflight_waits += 1

        async with lock:
            cached = self.read(key)
            if cached is not None:
                self.hits += 1
                return cached[0], cached[1], True
            payload, content_type = await loader()
            self.write(key, payload, content_type)
            self.misses += 1
            return payload, content_type, False

    def prune_locks(self, limit: int = 512) -> None:
        """丢弃已释放的锁,避免长跑进程里锁表无限增长。"""
        if len(self._locks) <= limit:
            return
        self._locks = {key: lock for key, lock in self._locks.items() if lock.locked()}


__all__ = [
    "CATEGORY",
    "VARIANT_GRID",
    "ThumbnailCacheService",
    "media_source_fingerprint",
    "thumbnail_cache_key",
]
