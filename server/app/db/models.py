"""ORM 模型。

约定:
- 只缓存本项目需要的字段,不复制 Jellyfin 数据库
- 表结构变更必须通过 Alembic 迁移,禁止 create_all 修改线上库
- 主键使用本项目稳定 ID(media_id 等),不使用文件路径
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    Index,
    Integer,
    MetaData,
    String,
    Text,
    UniqueConstraint,
    and_,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table)s",
    "pk": "pk_%(table_name)s",
}


def utc_now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)


class AppSetting(Base):
    """键值型应用设置(如: 批阅断点、界面偏好)。"""

    __tablename__ = "app_settings"

    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    value: Mapped[str] = mapped_column(Text, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(default=utc_now, onupdate=utc_now)


class LibrarySelection(Base):
    """用户勾选的 Jellyfin 媒体库。"""

    __tablename__ = "library_selection"

    jellyfin_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    collection_type: Mapped[str | None] = mapped_column(String(64), nullable=True)
    selected: Mapped[bool] = mapped_column(default=True)
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    updated_at: Mapped[datetime] = mapped_column(default=utc_now, onupdate=utc_now)


class MediaCacheIndex(Base):
    """媒体缓存索引:只存本项目需要的字段,不复制 Jellyfin 数据库。

    media_id 是本项目稳定 ID(sha256(jellyfin_id)[:24])。
    fingerprint 由 path+size+modified 生成,用于缓存失效与重扫关联。
    """

    __tablename__ = "media_cache_index"

    media_id: Mapped[str] = mapped_column(String(24), primary_key=True)
    jellyfin_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    library_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    name: Mapped[str] = mapped_column(String(512), nullable=False)
    media_type: Mapped[str] = mapped_column(String(16), nullable=False)  # video | image
    duration_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    size_bytes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    width: Mapped[int | None] = mapped_column(Integer, nullable=True)
    height: Mapped[int | None] = mapped_column(Integer, nullable=True)
    container: Mapped[str | None] = mapped_column(String(32), nullable=True)
    # 服务端内部使用的真实文件路径(ffprobe/雪碧图/删除),绝不暴露给客户端
    media_path: Mapped[str | None] = mapped_column(String(1024), nullable=True)
    fingerprint: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    # 重复检测哈希(由后台任务写入): quick_hash=采样判定, sha256=整文件判定
    quick_hash: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    sha256: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    created_at: Mapped[datetime | None] = mapped_column(nullable=True)
    modified_at: Mapped[datetime | None] = mapped_column(nullable=True)
    is_available: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    sync_generation: Mapped[str | None] = mapped_column(String(32), nullable=True)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    synced_at: Mapped[datetime] = mapped_column(default=utc_now, onupdate=utc_now)


# 媒体墙的 count/filter/order/page 均在 SQLite 中执行。每种排序同时提供有/无
# media_type 的索引前缀，并包含稳定 tie-break media_id。
Index(
    "ix_media_cache_available_library_name",
    MediaCacheIndex.is_available,
    MediaCacheIndex.library_id,
    MediaCacheIndex.name.collate("NOCASE"),
    MediaCacheIndex.media_id,
)
Index(
    "ix_media_cache_available_library_type_name",
    MediaCacheIndex.is_available,
    MediaCacheIndex.library_id,
    MediaCacheIndex.media_type,
    MediaCacheIndex.name.collate("NOCASE"),
    MediaCacheIndex.media_id,
)
Index(
    "ix_media_cache_available_library_created",
    MediaCacheIndex.is_available,
    MediaCacheIndex.library_id,
    MediaCacheIndex.created_at,
    MediaCacheIndex.media_id,
)
Index(
    "ix_media_cache_available_library_type_created",
    MediaCacheIndex.is_available,
    MediaCacheIndex.library_id,
    MediaCacheIndex.media_type,
    MediaCacheIndex.created_at,
    MediaCacheIndex.media_id,
)
Index(
    "ix_media_cache_available_library_size",
    MediaCacheIndex.is_available,
    MediaCacheIndex.library_id,
    MediaCacheIndex.size_bytes,
    MediaCacheIndex.media_id,
)
Index(
    "ix_media_cache_available_library_type_size",
    MediaCacheIndex.is_available,
    MediaCacheIndex.library_id,
    MediaCacheIndex.media_type,
    MediaCacheIndex.size_bytes,
    MediaCacheIndex.media_id,
)
Index(
    "ix_media_cache_available_library_duration",
    MediaCacheIndex.is_available,
    MediaCacheIndex.library_id,
    MediaCacheIndex.duration_ms,
    MediaCacheIndex.media_id,
)
Index(
    "ix_media_cache_available_library_type_duration",
    MediaCacheIndex.is_available,
    MediaCacheIndex.library_id,
    MediaCacheIndex.media_type,
    MediaCacheIndex.duration_ms,
    MediaCacheIndex.media_id,
)
Index(
    "ix_media_cache_available_library_resolution",
    MediaCacheIndex.is_available,
    MediaCacheIndex.library_id,
    MediaCacheIndex.width * MediaCacheIndex.height,
    MediaCacheIndex.media_id,
)
Index(
    "ix_media_cache_available_library_type_resolution",
    MediaCacheIndex.is_available,
    MediaCacheIndex.library_id,
    MediaCacheIndex.media_type,
    MediaCacheIndex.width * MediaCacheIndex.height,
    MediaCacheIndex.media_id,
)

# 默认“全部已选库”路径需要跨 library_id 的全局顺序；library_id 放在稳定排序键后
# 仅用于覆盖筛选，单库路径继续使用上面的 library-prefixed 索引。
Index(
    "ix_media_cache_available_global_name",
    MediaCacheIndex.is_available,
    MediaCacheIndex.name.collate("NOCASE"),
    MediaCacheIndex.media_id,
    MediaCacheIndex.library_id,
)
Index(
    "ix_media_cache_available_type_global_name",
    MediaCacheIndex.is_available,
    MediaCacheIndex.media_type,
    MediaCacheIndex.name.collate("NOCASE"),
    MediaCacheIndex.media_id,
    MediaCacheIndex.library_id,
)
Index(
    "ix_media_cache_available_global_created",
    MediaCacheIndex.is_available,
    MediaCacheIndex.created_at,
    MediaCacheIndex.media_id,
    MediaCacheIndex.library_id,
)
Index(
    "ix_media_cache_available_type_global_created",
    MediaCacheIndex.is_available,
    MediaCacheIndex.media_type,
    MediaCacheIndex.created_at,
    MediaCacheIndex.media_id,
    MediaCacheIndex.library_id,
)
Index(
    "ix_media_cache_available_global_size",
    MediaCacheIndex.is_available,
    MediaCacheIndex.size_bytes,
    MediaCacheIndex.media_id,
    MediaCacheIndex.library_id,
)
Index(
    "ix_media_cache_available_type_global_size",
    MediaCacheIndex.is_available,
    MediaCacheIndex.media_type,
    MediaCacheIndex.size_bytes,
    MediaCacheIndex.media_id,
    MediaCacheIndex.library_id,
)
Index(
    "ix_media_cache_available_global_duration",
    MediaCacheIndex.is_available,
    MediaCacheIndex.duration_ms,
    MediaCacheIndex.media_id,
    MediaCacheIndex.library_id,
)
Index(
    "ix_media_cache_available_type_global_duration",
    MediaCacheIndex.is_available,
    MediaCacheIndex.media_type,
    MediaCacheIndex.duration_ms,
    MediaCacheIndex.media_id,
    MediaCacheIndex.library_id,
)
Index(
    "ix_media_cache_available_global_resolution",
    MediaCacheIndex.is_available,
    MediaCacheIndex.width * MediaCacheIndex.height,
    MediaCacheIndex.media_id,
    MediaCacheIndex.library_id,
)
Index(
    "ix_media_cache_available_type_global_resolution",
    MediaCacheIndex.is_available,
    MediaCacheIndex.media_type,
    MediaCacheIndex.width * MediaCacheIndex.height,
    MediaCacheIndex.media_id,
    MediaCacheIndex.library_id,
)


class MediaSyncState(Base):
    """每个媒体库的持久化同步状态；不保存凭据或上游原始错误。"""

    __tablename__ = "media_sync_state"

    library_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    # idle | pending | running | succeeded | failed | cancelled
    state: Mapped[str] = mapped_column(String(16), nullable=False, default="idle")
    task_id: Mapped[str | None] = mapped_column(String(32), nullable=True, index=True)
    processed: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    total: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    last_started_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    last_success_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    last_error: Mapped[str | None] = mapped_column(Text, nullable=True)


class MediaRefreshTarget(Base):
    """活动媒体刷新按库租约；主键保证重叠 target 不能并发。"""

    __tablename__ = "media_refresh_target"

    library_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    task_id: Mapped[str] = mapped_column(String(32), nullable=False, index=True)


class BackgroundTask(Base):
    """后台任务记录(雪碧图生成、媒体同步等耗时不阻塞 HTTP 的作业)。

    运行线程由 TaskManager 持有;本表只记录状态与进度,供前端轮询。
    """

    __tablename__ = "background_task"

    task_id: Mapped[str] = mapped_column(String(32), primary_key=True)
    type: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    # pending | running | succeeded | failed | cancelled
    status: Mapped[str] = mapped_column(String(16), default="pending", index=True)
    params: Mapped[str | None] = mapped_column(Text, nullable=True)  # json
    progress: Mapped[int] = mapped_column(Integer, default=0)
    result: Mapped[str | None] = mapped_column(Text, nullable=True)
    error: Mapped[str | None] = mapped_column(Text, nullable=True)
    media_id: Mapped[str | None] = mapped_column(String(24), nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(default=utc_now)
    started_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


Index(
    "uq_background_task_active_target",
    BackgroundTask.type,
    BackgroundTask.media_id,
    unique=True,
    sqlite_where=and_(
        BackgroundTask.type == "media_refresh",
        BackgroundTask.media_id.is_not(None),
        BackgroundTask.status.in_(("pending", "running")),
    ),
)


class SpriteManifest(Base):
    """视频雪碧图清单:裁剪所需几何信息 + 生成任务的最终状态。

    sprite_file 为相对 cache 根目录的路径;media fingerprint 变化视为失效。
    """

    __tablename__ = "sprite_manifest"

    media_id: Mapped[str] = mapped_column(String(24), primary_key=True)
    # pending | ready | failed
    status: Mapped[str] = mapped_column(String(16), default="pending")
    columns: Mapped[int] = mapped_column(Integer, default=0)
    rows: Mapped[int] = mapped_column(Integer, default=0)
    count: Mapped[int] = mapped_column(Integer, default=0)
    tile_width: Mapped[int] = mapped_column(Integer, default=0)
    tile_height: Mapped[int] = mapped_column(Integer, default=0)
    interval_ms: Mapped[int] = mapped_column(Integer, default=0)
    total_duration_ms: Mapped[int] = mapped_column(Integer, default=0)
    video_width: Mapped[int] = mapped_column(Integer, default=0)
    video_height: Mapped[int] = mapped_column(Integer, default=0)
    sprite_file: Mapped[str | None] = mapped_column(String(512), nullable=True)
    fingerprint: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    error: Mapped[str | None] = mapped_column(Text, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(default=utc_now, onupdate=utc_now)


class ReviewSession(Base):
    """批阅会话:一次性队列 + 断点恢复快照。

    filter_snapshot / sort_snapshot 保存创建会话时的筛选与排序,
    保证 resume 时媒体集合与排序稳定一致。
    """

    __tablename__ = "review_session"

    session_id: Mapped[str] = mapped_column(String(32), primary_key=True)
    # active | completed | cancelled
    status: Mapped[str] = mapped_column(String(16), default="active", index=True)
    filter_snapshot: Mapped[str] = mapped_column(Text, nullable=False, default="{}")
    sort_snapshot: Mapped[str] = mapped_column(Text, nullable=False, default="{}")
    current_index: Mapped[int] = mapped_column(Integer, default=0)
    total_count: Mapped[int] = mapped_column(Integer, default=0)
    seen_count: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(default=utc_now, onupdate=utc_now)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class ReviewSessionItem(Base):
    """批阅队列单元:会话内媒体顺序 + 已看标记。"""

    __tablename__ = "review_session_item"
    __table_args__ = (UniqueConstraint("session_id", "media_id", name="uq_review_session_item_sm"),)

    id: Mapped[int] = mapped_column(Integer, autoincrement=True, primary_key=True)
    session_id: Mapped[str] = mapped_column(String(32), index=True)
    media_id: Mapped[str] = mapped_column(String(24), nullable=False)
    index: Mapped[int] = mapped_column(Integer, default=0)
    seen: Mapped[bool] = mapped_column(Boolean, default=False)


class Favorite(Base):
    """收藏媒体。"""

    __tablename__ = "favorite"

    media_id: Mapped[str] = mapped_column(String(24), primary_key=True)
    created_at: Mapped[datetime] = mapped_column(default=utc_now)


class DeleteQueue(Base):
    """待删除队列:加入保留,commit 后执行真实删除并移出索引。

    - pending:  已在待删除列表中,可撤销
    - committed: 已确认删除(幂等,防重复删除)
    - succeeded / failed: commit 执行结果
    """

    __tablename__ = "delete_queue"

    media_id: Mapped[str] = mapped_column(String(24), primary_key=True)
    size_bytes: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    # pending | committed | succeeded | failed
    status: Mapped[str] = mapped_column(String(16), default="pending", index=True)
    error: Mapped[str | None] = mapped_column(Text, nullable=True)
    added_at: Mapped[datetime] = mapped_column(default=utc_now)
    committed_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class AuditLog(Base):
    """不可变审计记录:收藏/撤销、入队/撤销/确认删除等关键操作。"""

    __tablename__ = "audit_log"

    id: Mapped[int] = mapped_column(Integer, autoincrement=True, primary_key=True)
    action: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    media_id: Mapped[str | None] = mapped_column(String(24), nullable=True)
    detail: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(default=utc_now)


class PairedDevice(Base):
    """已配对设备: 记录一次配对码绑定的 Android 设备(AGENTS: 一次配对码)。

    配对成功后为设备签发 bearer token;服务端只保存 token 的 SHA-256 哈希
    (不明文),客户端持久化 token 并在后续请求的 Authorization 头中携带。
    revoked 置位后对应 token 立即失效。
    """

    __tablename__ = "paired_device"

    device_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    installation_id: Mapped[str] = mapped_column(String(128), nullable=False, unique=True)
    name: Mapped[str | None] = mapped_column(String(128), nullable=True)
    paired_at: Mapped[datetime] = mapped_column(default=utc_now)
    token_hash: Mapped[str | None] = mapped_column(String(64), nullable=True, unique=True)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    # 设备级 Jellyfin 播放凭据(0013):服务端签发的命名 key,可撤销;
    # value 只在播放响应 headers 中下发给该设备,不进入 URL/日志/诊断。
    jellyfin_key_name: Mapped[str | None] = mapped_column(String(160), nullable=True)
    jellyfin_key_value: Mapped[str | None] = mapped_column(String(128), nullable=True)
    jellyfin_key_created_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class PairingCode(Base):
    """一次性配对码: 短时效、一次性,验证成功后作废。

    用于"自动发现失败时手动 IP 兜底"的配对流程;配对成功后写入 paired_device。
    """

    __tablename__ = "pairing_code"

    code: Mapped[str] = mapped_column(String(16), primary_key=True)
    expires_at: Mapped[datetime] = mapped_column(nullable=False)
    used: Mapped[bool] = mapped_column(Boolean, default=False)
    used_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(default=utc_now)
