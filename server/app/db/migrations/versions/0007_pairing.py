"""Add paired_device and pairing_code tables.

Revision ID: 0007
Revises: 0006
Create Date: 2026-08-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "paired_device",
        sa.Column("device_id", sa.String(128), primary_key=True),
        sa.Column("name", sa.String(128), nullable=True),
        sa.Column("paired_at", sa.DateTime(), nullable=True),
    )
    op.create_table(
        "pairing_code",
        sa.Column("code", sa.String(16), primary_key=True),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("used", sa.Boolean(), nullable=False, server_default="0"),
        sa.Column("used_at", sa.DateTime(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=True),
    )


def downgrade() -> None:
    op.drop_table("pairing_code")
    op.drop_table("paired_device")
