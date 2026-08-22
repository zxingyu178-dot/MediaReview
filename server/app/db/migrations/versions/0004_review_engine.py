"""Add review_session and review_session_item tables.

Revision ID: 0004
Revises: 0003
Create Date: 2026-08-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "review_session",
        sa.Column("session_id", sa.String(32), primary_key=True),
        sa.Column("status", sa.String(16), nullable=False, server_default="active"),
        sa.Column("filter_snapshot", sa.Text(), nullable=False, server_default="{}"),
        sa.Column("sort_snapshot", sa.Text(), nullable=False, server_default="{}"),
        sa.Column("current_index", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("total_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("seen_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("created_at", sa.DateTime(), nullable=True),
        sa.Column("updated_at", sa.DateTime(), nullable=True),
        sa.Column("completed_at", sa.DateTime(), nullable=True),
    )
    op.create_table(
        "review_session_item",
        sa.Column("id", sa.Integer(), autoincrement=True, primary_key=True),
        sa.Column(
            "session_id",
            sa.String(32),
            nullable=False,
            index=True,
        ),
        sa.Column("media_id", sa.String(24), nullable=False),
        sa.Column("index", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("seen", sa.Boolean(), nullable=False, server_default="0"),
        sa.UniqueConstraint("session_id", "media_id", name="uq_review_session_item_sm"),
    )


def downgrade() -> None:
    op.drop_table("review_session_item")
    op.drop_table("review_session")
