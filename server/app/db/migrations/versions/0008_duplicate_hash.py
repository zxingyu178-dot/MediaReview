"""Add duplicate-detection hash columns to media_cache_index.

Revision ID: 0008
Revises: 0007
Create Date: 2026-08-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0008"
down_revision = "0007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "media_cache_index",
        sa.Column("quick_hash", sa.String(64), nullable=True),
    )
    op.create_index("ix_media_cache_index_quick_hash", "media_cache_index", ["quick_hash"])
    op.add_column(
        "media_cache_index",
        sa.Column("sha256", sa.String(64), nullable=True),
    )
    op.create_index("ix_media_cache_index_sha256", "media_cache_index", ["sha256"])


def downgrade() -> None:
    op.drop_index("ix_media_cache_index_sha256", table_name="media_cache_index")
    op.drop_column("media_cache_index", "sha256")
    op.drop_index("ix_media_cache_index_quick_hash", table_name="media_cache_index")
    op.drop_column("media_cache_index", "quick_hash")
