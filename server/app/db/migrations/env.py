"""Alembic 迁移环境。

数据库 URL 解析顺序:
1. 环境变量 MEDIAREVIEW_DATABASE_URL(测试用)
2. MEDIAREVIEW_DATA_ROOT 指定数据根目录下的 database/mediareview.db
3. 默认 %ProgramData%\\MediaReview
"""

from __future__ import annotations

import os
from logging.config import fileConfig

from alembic import context
from sqlalchemy import engine_from_config, pool

from app.core.config import resolve_data_root
from app.db import models  # noqa: F401  确保模型注册到 Base.metadata
from app.db.models import Base

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def _database_url() -> str:
    # 优先使用程序注入的 URL,其次环境变量,最后按数据根目录推导
    injected = config.get_main_option("sqlalchemy.url")
    if injected:
        return injected
    env_url = os.environ.get("MEDIAREVIEW_DATABASE_URL")
    if env_url:
        return env_url
    db_path = resolve_data_root() / "database" / "mediareview.db"
    return f"sqlite:///{db_path}"


def run_migrations_offline() -> None:
    context.configure(
        url=_database_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        render_as_batch=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    configuration = config.get_section(config.config_ini_section, {})
    configuration["sqlalchemy.url"] = _database_url()
    connectable = engine_from_config(
        configuration,
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            render_as_batch=True,
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
