"""Stage 9 additive source identity; preserve existing keys and relationships."""
import sqlalchemy as sa
from alembic import op

revision = "0015"
down_revision = "0014"
branch_labels = None
depends_on = None


def upgrade():
    for table in ("library_selection", "media_cache_index"):
        op.add_column(table, sa.Column("source_type", sa.String(16), nullable=False, server_default="jellyfin"))
    op.create_table("local_root",
        sa.Column("root_id", sa.String(64), primary_key=True),
        sa.Column("name", sa.String(256), nullable=False),
        sa.Column("root_path", sa.Text(), nullable=False, unique=True),
        sa.Column("enabled", sa.Boolean(), nullable=False),
        sa.Column("status", sa.String(24), nullable=False),
        sa.Column("last_scan_at", sa.DateTime(), nullable=True))
    op.create_table("local_playback_progress",
        sa.Column("media_id", sa.String(24), primary_key=True),
        sa.Column("position_ms", sa.BigInteger(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False))


def downgrade():
    bind = op.get_bind()
    if any(bind.scalar(sa.text(query)) for query in (
        "SELECT count(*) FROM local_root",
        "SELECT count(*) FROM media_cache_index WHERE source_type != 'jellyfin'",
        "SELECT count(*) FROM library_selection WHERE source_type != 'jellyfin'",
        "SELECT count(*) FROM local_playback_progress",
    )):
        raise RuntimeError("存在本地来源数据；请恢复升级前数据库备份，禁止有损降级")
    op.drop_table("local_playback_progress")
    op.drop_table("local_root")
    for table in ("media_cache_index", "library_selection"):
        op.drop_column(table, "source_type")
