"""为待删除队列增加不可复用的入队代次。"""

from __future__ import annotations

import secrets

import sqlalchemy as sa
from alembic import op

revision = "0017"
down_revision = "0016"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("delete_queue", sa.Column("queue_token", sa.String(32), nullable=True))
    connection = op.get_bind()
    media_ids = list(connection.execute(sa.text("SELECT media_id FROM delete_queue")).scalars())
    for media_id in media_ids:
        connection.execute(
            sa.text(
                "UPDATE delete_queue SET queue_token = :queue_token WHERE media_id = :media_id"
            ),
            {"queue_token": secrets.token_hex(16), "media_id": media_id},
        )
    with op.batch_alter_table("delete_queue") as batch_op:
        batch_op.alter_column(
            "queue_token",
            existing_type=sa.String(32),
            nullable=False,
        )
    index_names = {
        index["name"] for index in sa.inspect(connection).get_indexes("delete_queue")
    }
    if "ix_delete_queue_status" not in index_names:
        op.create_index("ix_delete_queue_status", "delete_queue", ["status"])


def downgrade() -> None:
    connection = op.get_bind()
    in_flight_count = connection.execute(
        sa.text(
            "SELECT count(*) FROM delete_queue "
            "WHERE status IN ('committing', 'delete_done', 'delete_pending')"
        )
    ).scalar_one()
    if in_flight_count:
        raise RuntimeError(
            "0017 downgrade refused: delete_queue contains recoverable in-flight rows; "
            "请先使用 0017 代码完成恢复并备份数据库"
        )
    with op.batch_alter_table("delete_queue") as batch_op:
        batch_op.drop_column("queue_token")
