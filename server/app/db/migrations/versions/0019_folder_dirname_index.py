"""对现有 SQLite 目录表达式建立索引，不改写媒体记录。"""

import sqlalchemy as sa
from alembic import op

revision = "0019"
down_revision = "0018"
branch_labels = None
depends_on = None

_INDEX = "ix_media_cache_dirname_available"
# 固定迁移时的表达式，即使应用目录规则将来演化也保留可重放性。
_NORMALIZED = "replace(replace(media_path, char(92), '/'), '//', '/')"
_DIRNAME = f"rtrim({_NORMALIZED}, replace({_NORMALIZED}, '/', ''))"


def upgrade():
    op.create_index(_INDEX, "media_cache_index", [sa.text(_DIRNAME), "is_available"])


def downgrade():
    op.drop_index(_INDEX, table_name="media_cache_index")
