"""文件哈希工具: quick hash(采样) 与 full SHA-256(整文件)。

这些函数只做磁盘 I/O,不得在 FastAPI 请求事件循环中直接调用。
必须经由后台任务(asyncio.to_thread)执行,避免阻塞普通 HTTP 请求与 SQLite 写锁。
"""

from __future__ import annotations

import hashlib
from collections.abc import Callable
from pathlib import Path

# 采样读块: quick hash 每个采样点的读取长度
_QUICK_CHUNK = 64 * 1024
# 全文读块: full SHA-256 分批读取大小
_FULL_CHUNK = 1024 * 1024
# quick hash 采样点数(要求: 头部、25%、50%、75%、尾部)
_QUICK_SAMPLES = 5


def file_size(path: str | Path) -> int:
    return Path(path).stat().st_size


def _sample_offsets(size: int, chunk: int) -> list[int]:
    """快速采样位置: 头部、25%、50%、75% 与最后一个整块起始处(覆盖文件尾)。"""
    if size <= 0:
        return []
    if size <= chunk:
        return [0]
    return [0, int(size * 0.25), int(size * 0.5), int(size * 0.75), max(0, size - chunk)]


def quick_hash(path: str | Path, *, chunk_bytes: int = _QUICK_CHUNK) -> str:
    """读取头部、25%、50%、75%、尾部固定长度数据,组合计算 SHA-256。

    组合时按固定采样偏移排序,并混入偏移量做域分隔,降低异序碰撞风险。
    仅用于"高度可信重复"候选判定;byte-identical 必须用 full_sha256。
    """
    size = file_size(path)
    digest = hashlib.sha256()
    if size <= 0:
        # 空文件: 直接哈希空内容
        digest.update(b"")
        return digest.hexdigest()
    offsets = _sample_offsets(size, chunk_bytes)
    with Path(path).open("rb") as fp:
        for off in offsets:
            fp.seek(off)
            data = fp.read(chunk_bytes)
            if not data:
                continue
            # 混入偏移量,防止不同顺序/定位下碰撞
            digest.update(off.to_bytes(8, "big"))
            digest.update(data)
    return digest.hexdigest()


def full_sha256(
    path: str | Path,
    *,
    chunk_bytes: int = _FULL_CHUNK,
    progress_cb: Callable[[int, int], None] | None = None,
) -> str:
    """整文件 SHA-256(byte-identical 判定标准)。

    progress_cb(done_bytes, total_bytes) 在读取过程中回调,供后台任务更新进度。
    """
    size = file_size(path)
    digest = hashlib.sha256()
    done = 0
    with Path(path).open("rb") as fp:
        while True:
            data = fp.read(chunk_bytes)
            if not data:
                break
            digest.update(data)
            done += len(data)
            if progress_cb is not None:
                progress_cb(done, size)
    return digest.hexdigest()


def quick_hash_samples() -> int:
    """返回 quick hash 采样点数量(供文档/日志/测试引用)。"""
    return _QUICK_SAMPLES


__all__ = ["file_size", "full_sha256", "quick_hash", "quick_hash_samples"]
