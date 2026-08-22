"""Add background_task and sprite_manifest tables.

Revision ID: 0003
Revises: 0002
Create Date: 2026-08-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "background_task",
        sa.Column("task_id", sa.String(32), primary_key=True),
        sa.Column("type", sa.String(32), nullable=False, index=True),
        sa.Column("status", sa.String(16), nullable=False, server_default="pending"),
        sa.Column("params", sa.Text(), nullable=True),
        sa.Column("progress", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("result", sa.Text(), nullable=True),
        sa.Column("error", sa.Text(), nullable=True),
        sa.Column("media_id", sa.String(24), nullable=True, index=True),
        sa.Column("created_at", sa.DateTime(), nullable=True),
        sa.Column("started_at", sa.DateTime(), nullable=True),
        sa.Column("finished_at", sa.DateTime(), nullable=True),
    )
    op.create_table(
        "sprite_manifest",
        sa.Column("media_id", sa.String(24), primary_key=True),
        sa.Column("status", sa.String(16), nullable=False, server_default="pending"),
        sa.Column("columns", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("rows", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("tile_width", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("tile_height", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("interval_ms", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("total_duration_ms", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("video_width", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("video_height", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("sprite_file", sa.String(512), nullable=True),
        sa.Column("fingerprint", sa.String(64), nullable=False, index=True),
        sa.Column("error", sa.Text(), nullable=True),
        sa.Column("updated_at", sa.DateTime(), nullable=True),
    )


def downgrade() -> None:
    op.drop_table("sprite_manifest")
    op.drop_table("background_task")
