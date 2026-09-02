"""Add Task D schema: delete nonce + persisted duplicate groups.

Revision ID: 0014
Revises: 0013
Create Date: 2026-09-02

Task D 需要的三张新表:
- ``delete_commit_nonce``: 两阶段最终删除的一次性 nonce(绑定提交时队列快照,
  防 TOCTOU / 复用 / 过期);
- ``duplicate_group`` + ``duplicate_group_member``: 持久化重复分组与成员
  (含人工"保留"标记 keep),由后台扫描任务写入。

后续迁移必须从本迁移延伸,保持单一线性 head。
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0014"
down_revision = "0013"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "delete_commit_nonce",
        sa.Column("nonce", sa.String(32), primary_key=True),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("used", sa.Boolean(), nullable=False, server_default=sa.text("0")),
        sa.Column("media_ids_json", sa.Text(), nullable=False),
        sa.Column("total_bytes", sa.BigInteger(), nullable=False, server_default=sa.text("0")),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_table(
        "duplicate_group",
        sa.Column("group_id", sa.String(64), primary_key=True),
        sa.Column("type", sa.String(16), nullable=False),
        sa.Column("size_bytes", sa.BigInteger(), nullable=True),
        sa.Column("duration_ms", sa.Integer(), nullable=True),
        sa.Column("count", sa.Integer(), nullable=False, server_default=sa.text("0")),
        sa.Column("detail", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_duplicate_group_type", "duplicate_group", ["type"])
    op.create_table(
        "duplicate_group_member",
        sa.Column("group_id", sa.String(64), primary_key=True),
        sa.Column("media_id", sa.String(24), primary_key=True),
        sa.Column("name", sa.String(512), nullable=False),
        sa.Column("fingerprint", sa.String(64), nullable=False),
        sa.Column("keep", sa.Boolean(), nullable=False, server_default=sa.text("0")),
    )
    op.create_index("ix_duplicate_group_member_group_id", "duplicate_group_member", ["group_id"])


def downgrade() -> None:
    op.drop_index("ix_duplicate_group_member_group_id", table_name="duplicate_group_member")
    op.drop_table("duplicate_group_member")
    op.drop_index("ix_duplicate_group_type", table_name="duplicate_group")
    op.drop_table("duplicate_group")
    op.drop_table("delete_commit_nonce")
