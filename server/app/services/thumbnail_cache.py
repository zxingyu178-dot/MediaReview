"""Server 侧缩略图磁盘缓存(媒体墙封面)。

背景: ``cache/thumbnails/`` 目录早已存在,但媒体墙从未使用它 ——
每个封面请求都要回源 Jellyfin。首屏 8~10 张封面就是 8~10 次上游请求。

硬约束(阶段 8A.1):
- cache key **至少**包含 media_id + 源指纹 + 变体,绝不允许只按 media_id,
  否则媒体内容更新后仍会命中旧封面;
- 写入必须**原子化**: 先写临时文件,再 ``os.replace``,避免并发下留下半张图;
- 同一 key 的并发 MISS 做 **single-flight**: 第一个请求回源,其余等待同一结果;
- HIT 时**绝不**访问 Jellyfin。

阶段 8A.1.1 收口:
- 增加 `stats()` / `prune()`: 缓存不能无上限增长(默认上限 1 GiB,按 mtime 淘汰);
- 增加磁盘读取 / 磁盘写入 / 上游耗时测量, 用数据决定是否需要 offload 到线程。
"""

from __future__ import annotations

import asyncio
import hashlib
import os
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.core.logging import get_logger
from app.core.paths import PathManager

logger = get_logger("thumbnail_cache")

CATEGORY = "thumbnails"

# 磁盘缓存默认上限(§7): 超过后按最近修改时间淘汰旧文件。
DEFAULT_MAX_BYTES = 1024 * 1024 * 1024  # 1 GiB
# 每写入 N 个文件检查一次容量,避免每次写入都遍历目录。
_PRUNE_CHECK_EVERY = 32

_EXTENSIONS = {
    "image/jpeg": ".jpg",
    "image/jpg": ".jpg",
    "image/png": ".png",
    "image/webp": ".webp",
    "image/gif": ".gif",
    "image/bmp": ".bmp",
    "image/avif": ".avif",
}
_CONTENT_TYPES = {
    ".jpg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
    ".gif": "image/gif",
    ".bmp": "image/bmp",
    ".avif": "image/avif",
}
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


def source_version(fingerprint: str) -> str:
    """客户端缓存失效用的短版本号(§6)。

    **只由指纹派生**,不含 Windows 路径 / Jellyfin API Key / Token 等任何敏感信息。
    """
    return hashlib.sha256(fingerprint.encode()).hexdigest()[:16]


@dataclass
class CacheStats:
    files: int
    bytes: int


@dataclass
class ThumbnailFetchResult:
    payload: bytes
    content_type: str
    hit: bool
    disk_read_ms: float = 0.0
    disk_write_ms: float = 0.0
    upstream_ms: float = 0.0
    pruned_files: int = 0


class ThumbnailCacheService:
    """缩略图磁盘缓存 + 并发合并 + 容量收口。"""

    def __init__(self, paths: PathManager, *, max_bytes: int = DEFAULT_MAX_BYTES) -> None:
        self._paths = paths
        self._max_bytes = max_bytes
        self._locks: dict[str, asyncio.Lock] = {}
        self._writes_since_prune = 0
        self.hits = 0
        self.misses = 0
        self.singleflight_waits = 0

    @property
    def max_bytes(self) -> int:
        return self._max_bytes

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

    def read(self, key: str) -> tuple[bytes, str, float] | None:
        """返回 (payload, content_type, disk_read_ms)。"""
        located = self._locate(key)
        if located is None:
            return None
        path, content_type = located
        started = time.perf_counter()
        try:
            payload = path.read_bytes()
        except OSError:
            return None
        return payload, content_type, _ms(started)

    def write(self, key: str, payload: bytes, content_type: str) -> float:
        """原子写入: 临时文件写完再 replace。返回 disk_write_ms。"""
        if not payload:
            return 0.0
        started = time.perf_counter()
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
        return _ms(started)

    # ---- 容量收口(§7) ----

    def stats(self) -> CacheStats:
        files = 0
        total = 0
        for path in self._iter_files():
            try:
                total += path.stat().st_size
                files += 1
            except OSError:
                continue
        return CacheStats(files=files, bytes=total)

    def prune(self, max_bytes: int | None = None) -> tuple[int, int]:
        """按最近修改时间淘汰,直到总大小不超过上限。

        返回 (removed_files, removed_bytes)。默认上限为构造时的 max_bytes。
        """
        limit = self._max_bytes if max_bytes is None else max_bytes
        entries: list[tuple[float, int, Path]] = []
        total = 0
        for path in self._iter_files():
            try:
                stat = path.stat()
            except OSError:
                continue
            entries.append((stat.st_mtime, stat.st_size, path))
            total += stat.st_size
        if total <= limit:
            return 0, 0
        entries.sort(key=lambda item: item[0])  # 最旧优先
        removed_files = 0
        removed_bytes = 0
        for _mtime, size, path in entries:
            if total <= limit:
                break
            try:
                path.unlink()
            except OSError:
                continue
            total -= size
            removed_files += 1
            removed_bytes += size
        if removed_files:
            logger.info("缩略图缓存淘汰: %s 个文件 / %s 字节", removed_files, removed_bytes)
        return removed_files, removed_bytes

    def _iter_files(self) -> list[Path]:
        root = self._paths.thumbnails_dir
        if not root.is_dir():
            return []
        return [p for p in root.rglob("*") if p.is_file() and not p.name.endswith(".tmp")]

    # ---- single-flight ----

    async def get_or_create(
        self,
        key: str,
        loader: Callable[[], Awaitable[tuple[bytes, str]]],
    ) -> ThumbnailFetchResult:
        """返回完整结果(含磁盘/上游耗时)。并发同 key 只回源一次。"""
        cached = self.read(key)
        if cached is not None:
            self.hits += 1
            return ThumbnailFetchResult(cached[0], cached[1], True, disk_read_ms=cached[2])

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
                return ThumbnailFetchResult(cached[0], cached[1], True, disk_read_ms=cached[2])
            upstream_started = time.perf_counter()
            payload, content_type = await loader()
            upstream_ms = _ms(upstream_started)
            disk_write_ms = self.write(key, payload, content_type)
            self.misses += 1
            pruned = await self._maybe_prune()
            return ThumbnailFetchResult(
                payload,
                content_type,
                False,
                disk_write_ms=disk_write_ms,
                upstream_ms=upstream_ms,
                pruned_files=pruned,
            )

    async def _maybe_prune(self) -> int:
        """每 N 次写入检查一次容量(磁盘遍历放到线程,不阻塞事件循环)。"""
        self._writes_since_prune += 1
        if self._writes_since_prune < _PRUNE_CHECK_EVERY:
            return 0
        self._writes_since_prune = 0
        _removed_files, _removed_bytes = await asyncio.to_thread(self.prune, self._max_bytes)
        return _removed_files

    def prune_locks(self, limit: int = 512) -> None:
        """丢弃已释放的锁,避免长跑进程里锁表无限增长。"""
        if len(self._locks) <= limit:
            return
        self._locks = {key: lock for key, lock in self._locks.items() if lock.locked()}


def _ms(started: float) -> float:
    return round((time.perf_counter() - started) * 1000, 2)


__all__ = [
    "CATEGORY",
    "DEFAULT_MAX_BYTES",
    "VARIANT_GRID",
    "CacheStats",
    "ThumbnailCacheService",
    "ThumbnailFetchResult",
    "media_source_fingerprint",
    "source_version",
    "thumbnail_cache_key",
]
