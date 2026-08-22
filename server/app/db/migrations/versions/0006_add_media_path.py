"""Add media_path column to media_cache_index.

服务端内部使用真实文件路径(ffprobe/雪碧图/删除),绝不暴露给客户端。
Revision ID: 0006
Revises: 0005
Create Date: 2026-08-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "media_cache_index",
        sa.Column("media_path", sa.String(1024), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("media_cache_index", "media_path")
