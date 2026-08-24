"""重复检测哈希的共享有效性合同。"""

from __future__ import annotations

import re

import sqlalchemy as sa

_FULL_SHA256_PATTERN = re.compile(r"[0-9a-fA-F]{64}")


def is_full_sha256(value: object) -> bool:
    """只有精确 64 位十六进制文本才是已完成的 full SHA-256。"""
    return isinstance(value, str) and _FULL_SHA256_PATTERN.fullmatch(value) is not None


def full_sha256_sql_predicate(column) -> sa.ColumnElement:
    """返回与 ``is_full_sha256`` 等价的 SQLite SQL 谓词。"""
    return sa.and_(
        sa.func.typeof(column) == "text",
        sa.func.length(sa.cast(column, sa.LargeBinary)) == 64,
        sa.func.mediareview_is_full_sha256(column) == 1,
    )


__all__ = ["full_sha256_sql_predicate", "is_full_sha256"]
