"""Add per-device Jellyfin playback key columns to paired devices.

Revision ID: 0013
Revises: 0012
Create Date: 2026-08-30

设备级播放凭据:服务端为每台已配对设备维护一个命名 Jellyfin key
(名称 `mediareview-<installation_id>`,可撤销),value 只保存在服务端,
仅在播放响应的 headers 中下发给该设备,绝不进入 URL 与日志。
后续迁移必须从本迁移延伸,保持单一线性 head。
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0013"
down_revision = "0012"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("paired_device") as batch:
        batch.add_column(sa.Column("jellyfin_key_name", sa.String(length=160), nullable=True))
        batch.add_column(sa.Column("jellyfin_key_value", sa.String(length=128), nullable=True))
        batch.add_column(sa.Column("jellyfin_key_created_at", sa.DateTime(), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table("paired_device") as batch:
        batch.drop_column("jellyfin_key_created_at")
        batch.drop_column("jellyfin_key_value")
        batch.drop_column("jellyfin_key_name")
