"""Add favorite, delete_queue and audit_log tables.

Revision ID: 0005
Revises: 0004
Create Date: 2026-08-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "favorite",
        sa.Column("media_id", sa.String(24), primary_key=True),
        sa.Column("created_at", sa.DateTime(), nullable=True),
    )
    op.create_table(
        "delete_queue",
        sa.Column("media_id", sa.String(24), primary_key=True),
        sa.Column("size_bytes", sa.BigInteger(), nullable=True),
        sa.Column("status", sa.String(16), nullable=False, server_default="pending"),
        sa.Column("error", sa.Text(), nullable=True),
        sa.Column("added_at", sa.DateTime(), nullable=True),
        sa.Column("committed_at", sa.DateTime(), nullable=True),
    )
    op.create_table(
        "audit_log",
        sa.Column("id", sa.Integer(), autoincrement=True, primary_key=True),
        sa.Column("action", sa.String(32), nullable=False, index=True),
        sa.Column("media_id", sa.String(24), nullable=True),
        sa.Column("detail", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=True),
    )


def downgrade() -> None:
    op.drop_table("audit_log")
    op.drop_table("delete_queue")
    op.drop_table("favorite")
