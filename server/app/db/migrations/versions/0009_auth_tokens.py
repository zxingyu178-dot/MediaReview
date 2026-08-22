"""Add device auth token hash to paired_device.

Revision ID: 0009
Revises: 0008
Create Date: 2026-08-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0009"
down_revision = "0008"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # SQLite 无法直接 ALTER 增加唯一约束,使用 batch(重建表)策略
    with op.batch_alter_table("paired_device") as batch_op:
        batch_op.add_column(sa.Column("token_hash", sa.String(64), nullable=True))
        batch_op.create_unique_constraint("uq_paired_device_token_hash", ["token_hash"])
        batch_op.add_column(sa.Column("revoked", sa.Boolean(), nullable=False, server_default="0"))
        batch_op.add_column(sa.Column("last_seen_at", sa.DateTime(), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table("paired_device") as batch_op:
        batch_op.drop_column("last_seen_at")
        batch_op.drop_column("revoked")
        batch_op.drop_constraint("uq_paired_device_token_hash", type_="unique")
        batch_op.drop_column("token_hash")
