"""Add persistent media sync state, availability and query indexes.

Revision ID: 0010
Revises: 0009
Create Date: 2026-08-24
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0010"
down_revision = "0009"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("media_cache_index") as batch_op:
        batch_op.add_column(
            sa.Column("is_available", sa.Boolean(), nullable=False, server_default=sa.true())
        )
        batch_op.add_column(sa.Column("sync_generation", sa.String(32), nullable=True))
        batch_op.add_column(sa.Column("last_seen_at", sa.DateTime(), nullable=True))

    op.create_table(
        "media_sync_state",
        sa.Column("library_id", sa.String(64), primary_key=True),
        sa.Column("state", sa.String(16), nullable=False, server_default="idle"),
        sa.Column("task_id", sa.String(32), nullable=True),
        sa.Column("processed", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("total", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("last_started_at", sa.DateTime(), nullable=True),
        sa.Column("last_success_at", sa.DateTime(), nullable=True),
        sa.Column("last_error", sa.Text(), nullable=True),
    )
    op.create_index("ix_media_sync_state_task_id", "media_sync_state", ["task_id"])
    op.execute(
        "CREATE UNIQUE INDEX uq_background_task_active_target "
        "ON background_task (type, media_id) "
        "WHERE type = 'media_refresh' AND media_id IS NOT NULL "
        "AND status IN ('pending', 'running')"
    )

    # 每种排序同时提供有/无 media_type 的路径，并包含稳定 tie-break media_id。
    op.execute(
        "CREATE INDEX ix_media_cache_available_library_name "
        "ON media_cache_index (is_available, library_id, name COLLATE NOCASE, media_id)"
    )
    op.execute(
        "CREATE INDEX ix_media_cache_available_library_type_name "
        "ON media_cache_index "
        "(is_available, library_id, media_type, name COLLATE NOCASE, media_id)"
    )
    op.create_index(
        "ix_media_cache_available_library_created",
        "media_cache_index",
        ["is_available", "library_id", "created_at", "media_id"],
    )
    op.create_index(
        "ix_media_cache_available_library_type_created",
        "media_cache_index",
        ["is_available", "library_id", "media_type", "created_at", "media_id"],
    )
    op.create_index(
        "ix_media_cache_available_library_size",
        "media_cache_index",
        ["is_available", "library_id", "size_bytes", "media_id"],
    )
    op.create_index(
        "ix_media_cache_available_library_type_size",
        "media_cache_index",
        ["is_available", "library_id", "media_type", "size_bytes", "media_id"],
    )
    op.create_index(
        "ix_media_cache_available_library_duration",
        "media_cache_index",
        ["is_available", "library_id", "duration_ms", "media_id"],
    )
    op.create_index(
        "ix_media_cache_available_library_type_duration",
        "media_cache_index",
        ["is_available", "library_id", "media_type", "duration_ms", "media_id"],
    )
    op.execute(
        "CREATE INDEX ix_media_cache_available_library_resolution "
        "ON media_cache_index "
        "(is_available, library_id, (width * height), media_id)"
    )
    op.execute(
        "CREATE INDEX ix_media_cache_available_library_type_resolution "
        "ON media_cache_index "
        "(is_available, library_id, media_type, (width * height), media_id)"
    )


def downgrade() -> None:
    op.drop_index("uq_background_task_active_target", table_name="background_task")
    op.drop_index(
        "ix_media_cache_available_library_type_resolution", table_name="media_cache_index"
    )
    op.drop_index("ix_media_cache_available_library_resolution", table_name="media_cache_index")
    op.drop_index("ix_media_cache_available_library_type_duration", table_name="media_cache_index")
    op.drop_index("ix_media_cache_available_library_duration", table_name="media_cache_index")
    op.drop_index("ix_media_cache_available_library_type_size", table_name="media_cache_index")
    op.drop_index("ix_media_cache_available_library_size", table_name="media_cache_index")
    op.drop_index("ix_media_cache_available_library_type_created", table_name="media_cache_index")
    op.drop_index("ix_media_cache_available_library_created", table_name="media_cache_index")
    op.drop_index("ix_media_cache_available_library_type_name", table_name="media_cache_index")
    op.drop_index("ix_media_cache_available_library_name", table_name="media_cache_index")
    op.drop_index("ix_media_sync_state_task_id", table_name="media_sync_state")
    op.drop_table("media_sync_state")
    with op.batch_alter_table("media_cache_index") as batch_op:
        batch_op.drop_column("last_seen_at")
        batch_op.drop_column("sync_generation")
        batch_op.drop_column("is_available")
