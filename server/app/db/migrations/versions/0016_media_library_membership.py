"""按库保存媒体归属，不改变媒体 ID 或用户记录。"""
import sqlalchemy as sa
from alembic import op

revision = "0016"
down_revision = "0015"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "media_library_membership",
        sa.Column("media_id", sa.String(24), primary_key=True),
        sa.Column("library_id", sa.String(64), primary_key=True),
        sa.Column("is_available", sa.Boolean(), nullable=False),
        sa.Column("sync_generation", sa.String(32), nullable=True),
    )
    op.create_index("ix_membership_library_available_media", "media_library_membership",
                    ["library_id", "is_available", "media_id"])
    op.execute(sa.text(
        "INSERT INTO media_library_membership "
        "(media_id, library_id, is_available, sync_generation) "
        "SELECT media_id, library_id, is_available, sync_generation FROM media_cache_index "
        "WHERE source_type = 'jellyfin'"
    ))


def downgrade():
    if op.get_bind().scalar(sa.text(
        "SELECT count(*) FROM media_library_membership m "
        "JOIN media_cache_index c ON c.media_id = m.media_id "
        "WHERE m.is_available = 1 AND m.library_id != c.library_id"
    )):
        raise RuntimeError("存在跨库归属；请恢复升级前备份，禁止有损降级")
    op.drop_index("ix_membership_library_available_media", table_name="media_library_membership")
    op.drop_table("media_library_membership")
