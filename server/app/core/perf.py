"""MR_PERF 性能日志(阶段 8A.1 加载管线专用)。

约定:
- 统一前缀 ``MR_PERF``,便于从日志里机械提取耗时做前后对比;
- **只用单调时钟**(``time.perf_counter``)计算耗时,禁止用 wall clock;
- 只记录耗时 / 命中情况 / 字节数等指标,绝不记录 Token、凭据、文件路径。
"""

from __future__ import annotations

import time
from typing import Any

from app.core.logging import get_logger

logger = get_logger("perf")
_PREFIX = "MR_PERF"


def perf_now() -> float:
    """单调时钟(秒)。"""
    return time.perf_counter()


def elapsed_ms(start: float) -> float:
    return round((perf_now() - start) * 1000, 1)


def log_perf(event: str, *, start: float | None = None, **fields: Any) -> float:
    """记录一条 MR_PERF 事件,返回当前时刻以便链式打点。"""
    now = perf_now()
    parts: list[str] = []
    if start is not None:
        parts.append(f"duration_ms={round((now - start) * 1000, 1)}")
    parts.extend(f"{key}={value}" for key, value in fields.items())
    logger.info("%s %s %s", _PREFIX, event, " ".join(parts))
    return now


__all__ = ["elapsed_ms", "log_perf", "perf_now"]
