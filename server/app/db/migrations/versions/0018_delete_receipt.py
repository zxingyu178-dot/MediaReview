"""持久化删除批次回执与所属设备；旧批次不伪造回执。"""

import sqlalchemy as sa
from alembic import op

revision = "0018"
down_revision = "0017"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("delete_commit_nonce", sa.Column("owner_device_id", sa.String(128)))
    op.add_column("delete_commit_nonce", sa.Column("receipt_state", sa.String(16)))
    op.add_column("delete_commit_nonce", sa.Column("result_json", sa.Text()))


def downgrade():
    active = op.get_bind().execute(sa.text(
        "SELECT count(*) FROM delete_commit_nonce WHERE receipt_state = 'running'"
    )).scalar_one()
    if active:
        raise RuntimeError("删除批次仍在处理中，禁止降级；请先核对结果并备份")
    with op.batch_alter_table("delete_commit_nonce") as batch:
        batch.drop_column("result_json")
        batch.drop_column("receipt_state")
        batch.drop_column("owner_device_id")
