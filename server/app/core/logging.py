"""日志系统。

- 控制台 + 滚动文件双输出,文件写入数据根目录 logs/ 下
- 每条日志自动附带当前请求的 request_id(无请求上下文时为 "-")
"""

from __future__ import annotations

import logging
import logging.handlers
from pathlib import Path

from app.core.request_id import get_request_id

_LOG_FORMAT = "%(asctime)s %(levelname)-7s [%(request_id)s] %(name)s: %(message)s"
_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"


class RequestContextFilter(logging.Filter):
    """为每条日志记录注入 request_id 字段。"""

    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = get_request_id()
        return True


def configure_logging(logs_dir: Path, level: str = "INFO") -> None:
    """初始化应用日志。重复调用时只保留一套 handler。"""
    root = logging.getLogger("app")
    root.setLevel(level.upper())
    for handler in list(root.handlers):
        root.removeHandler(handler)

    formatter = logging.Formatter(_LOG_FORMAT, datefmt=_DATE_FORMAT)
    context_filter = RequestContextFilter()

    console = logging.StreamHandler()
    console.setFormatter(formatter)
    console.addFilter(context_filter)
    root.addHandler(console)

    file_handler = logging.handlers.RotatingFileHandler(
        logs_dir / "server.log",
        maxBytes=10 * 1024 * 1024,
        backupCount=5,
        encoding="utf-8",
    )
    file_handler.setFormatter(formatter)
    file_handler.addFilter(context_filter)
    root.addHandler(file_handler)

    root.propagate = False


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(f"app.{name}")
