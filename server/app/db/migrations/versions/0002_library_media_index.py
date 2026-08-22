"""Add library_selection and media_cache_index tables.

Revision ID: 0002
Revises: 0001
Create Date: 2026-08-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "library_selection",
        sa.Column("jellyfin_id", sa.String(64), primary_key=True),
        sa.Column("name", sa.String(256), nullable=False),
        sa.Column("collection_type", sa.String(64), nullable=True),
        sa.Column("selected", sa.Boolean(), nullable=False, server_default="1"),
        sa.Column("sort_order", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("updated_at", sa.DateTime(), nullable=True),
    )
    op.create_table(
        "media_cache_index",
        sa.Column("media_id", sa.String(24), primary_key=True),
        sa.Column("jellyfin_id", sa.String(64), nullable=False, index=True),
        sa.Column("library_id", sa.String(64), nullable=False, index=True),
        sa.Column("name", sa.String(512), nullable=False),
        sa.Column("media_type", sa.String(16), nullable=False),
        sa.Column("duration_ms", sa.Integer(), nullable=True),
        sa.Column("size_bytes", sa.Integer(), nullable=True),
        sa.Column("width", sa.Integer(), nullable=True),
        sa.Column("height", sa.Integer(), nullable=True),
        sa.Column("container", sa.String(32), nullable=True),
        sa.Column("fingerprint", sa.String(64), nullable=False, index=True),
        sa.Column("created_at", sa.DateTime(), nullable=True),
        sa.Column("modified_at", sa.DateTime(), nullable=True),
        sa.Column("synced_at", sa.DateTime(), nullable=True),
    )


def downgrade() -> None:
    op.drop_table("media_cache_index")
    op.drop_table("library_selection")
