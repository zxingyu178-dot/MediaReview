"""媒体索引服务: 数据库优先查询、媒体库勾选与后台刷新。

阶段 3 职责:
- 媒体库勾选状态持久化(library_selection)
- 媒体元数据缓存索引写入/读取(media_cache_index)
- 媒体快照缓存(内存 LRU + TTL):避免媒体墙每翻一页就重新扫描 Jellyfin 全库

只缓存本项目需要的字段,不复制 Jellyfin 数据库(见 ARCHITECTURE 第 7 节)。
普通 GET 请求只查询 SQLite。Jellyfin 全库采集仅由 TaskManager 在线程中执行，
每 500 条短事务 upsert；只有本任务全部目标库成功后才原子隐藏未见的旧项目。
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import time
import uuid
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import UTC, datetime

import sqlalchemy as sa
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.adapters.jellyfin.client import JellyfinClient
from app.adapters.jellyfin.models import Library, MediaItem
from app.core.config import JellyfinConfig
from app.db.models import (
    BackgroundTask,
    Favorite,
    LibrarySelection,
    MediaCacheIndex,
    MediaRefreshTarget,
    MediaSyncState,
    utc_now,
)
from app.db.session import Database

# 单次从 Jellyfin 拉取媒体列表的分页上限,防止一次请求过重
_LIBRARY_PAGE = 500
_UPSERT_BATCH_SIZE = 500
TASK_TYPE_MEDIA_REFRESH = "media_refresh"
_SYNC_ERROR = "媒体同步失败，请稍后重试"

# 媒体快照缓存:内存 LRU + TTL(秒)
_MEDIA_SNAPSHOT_TTL_SECONDS = 180  # 3 分钟
_MEDIA_SNAPSHOT_MAX = 8


@dataclass
class _MediaSnapshot:
    items: list
    created_at: float


_media_snapshot_cache: dict[str, _MediaSnapshot] = {}


# ---- 媒体快照缓存(媒体墙分页免全库重扫) ----


def _snapshot_key(targets: Iterable[str], media_type: str | None, search_term: str | None) -> str:
    libs = ",".join(sorted(targets))
    return f"{libs}|{media_type or ''}|{search_term or ''}"


def get_media_snapshot(
    targets: Iterable[str], media_type: str | None, search_term: str | None
) -> list | None:
    """返回未过期的内存媒体快照;未命中或过期返回 None。"""
    key = _snapshot_key(targets, media_type, search_term)
    snap = _media_snapshot_cache.get(key)
    if snap is None:
        return None
    if time.monotonic() - snap.created_at > _MEDIA_SNAPSHOT_TTL_SECONDS:
        _media_snapshot_cache.pop(key, None)
        return None
    return snap.items


def put_media_snapshot(
    targets: Iterable[str],
    media_type: str | None,
    search_term: str | None,
    items: Iterable,
) -> None:
    """写入媒体快照(简单 LRU:按插入序淘汰最旧)。"""
    key = _snapshot_key(targets, media_type, search_term)
    if key not in _media_snapshot_cache and len(_media_snapshot_cache) >= _MEDIA_SNAPSHOT_MAX:
        oldest = next(iter(_media_snapshot_cache))
        _media_snapshot_cache.pop(oldest, None)
    _media_snapshot_cache[key] = _MediaSnapshot(items=list(items), created_at=time.monotonic())


def invalidate_media_snapshots() -> None:
    """媒体库选择变化等场景下立即失效全部快照。"""
    _media_snapshot_cache.clear()


# ---- 媒体库勾选 ----


def upsert_libraries(session: Session, libraries: Iterable[Library]) -> None:
    """把 Jellyfin 返回的媒体库 upsert 进 library_selection。

    新增媒体库默认勾选;已存在媒体库仅同步名称/类型/顺序,保留用户的勾选状态。
    """
    existing = {row.jellyfin_id: row for row in session.scalars(sa.select(LibrarySelection)).all()}
    now = utc_now()
    for order, lib in enumerate(libraries):
        row = existing.get(lib.jellyfin_id)
        if row is None:
            session.add(
                LibrarySelection(
                    jellyfin_id=lib.jellyfin_id,
                    name=lib.name,
                    collection_type=lib.collection_type,
                    selected=True,
                    sort_order=order,
                    updated_at=now,
                )
            )
        else:
            row.name = lib.name
            row.collection_type = lib.collection_type
            row.sort_order = order
            row.updated_at = now
    session.flush()


def library_selection_rows(session: Session) -> list[LibrarySelection]:
    return list(
        session.scalars(sa.select(LibrarySelection).order_by(LibrarySelection.sort_order)).all()
    )


def apply_selection(session: Session, selected_ids: Iterable[str], *, known_ids: set[str]) -> None:
    """保存多库勾选。

    - 仅对 known_ids 内的媒体库生效,其余忽略(防止写入不存在的库)
    - selected_ids 中等同勾选,known_ids 中其余标记为未勾选
    - 媒体库选择变化后立即失效媒体快照缓存,下次媒体墙请求重新采集
    """
    selected = set(selected_ids)
    for row in library_selection_rows(session):
        if row.jellyfin_id in known_ids:
            row.selected = row.jellyfin_id in selected
    invalidate_media_snapshots()
    session.flush()


def selected_library_ids(session: Session) -> list[str]:
    return [
        row.jellyfin_id
        for row in session.scalars(
            sa.select(LibrarySelection)
            .where(LibrarySelection.selected.is_(True))
            .order_by(LibrarySelection.sort_order)
        ).all()
    ]


# ---- 媒体缓存索引 ----


def _index_payload(item: MediaItem, *, generation: str | None, now: datetime) -> dict:
    return {
        "media_id": item.media_id,
        "jellyfin_id": item.jellyfin_id,
        "library_id": item.library_id,
        "name": item.name,
        "media_type": item.media_type,
        "duration_ms": item.duration_ms,
        "size_bytes": item.size_bytes,
        "width": item.width,
        "height": item.height,
        "container": item.container,
        "media_path": item.media_path,
        "fingerprint": item.fingerprint,
        "created_at": item.created_at,
        "modified_at": item.modified_at,
        "is_available": True,
        "sync_generation": generation,
        "last_seen_at": now,
        "synced_at": now,
    }


def upsert_media_items(
    session: Session,
    items: Iterable[MediaItem],
    *,
    generation: str | None = None,
    now: datetime | None = None,
) -> int:
    """用 SQLite ON CONFLICT 批量 upsert，不预载整个媒体索引。"""
    timestamp = now or utc_now()
    payload = [
        _index_payload(item, generation=generation, now=timestamp)
        for item in items
        if item.media_id
    ]
    if not payload:
        return 0
    inserted_count = 0
    for start in range(0, len(payload), _UPSERT_BATCH_SIZE):
        batch = payload[start : start + _UPSERT_BATCH_SIZE]
        ids = [item["media_id"] for item in batch]
        existing_count = int(
            session.scalar(
                sa.select(sa.func.count())
                .select_from(MediaCacheIndex)
                .where(MediaCacheIndex.media_id.in_(ids))
            )
            or 0
        )
        statement = sqlite_insert(MediaCacheIndex).values(batch)
        excluded = statement.excluded
        update_values = {
            "jellyfin_id": excluded.jellyfin_id,
            "library_id": excluded.library_id,
            "name": excluded.name,
            "media_type": excluded.media_type,
            "duration_ms": excluded.duration_ms,
            "size_bytes": excluded.size_bytes,
            "width": excluded.width,
            "height": excluded.height,
            "container": excluded.container,
            "media_path": excluded.media_path,
            "fingerprint": excluded.fingerprint,
            "created_at": excluded.created_at,
            "modified_at": excluded.modified_at,
            "is_available": True,
            "synced_at": excluded.synced_at,
        }
        if generation is not None:
            update_values["sync_generation"] = excluded.sync_generation
            update_values["last_seen_at"] = excluded.last_seen_at
        statement = statement.on_conflict_do_update(
            index_elements=[MediaCacheIndex.media_id],
            set_=update_values,
        )
        session.execute(statement)
        inserted_count += len(batch) - existing_count
    session.flush()
    return inserted_count


def _escaped_like(value: str) -> str:
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _sort_expression(sort_by: str, random_seed: str | None):
    if sort_by == "name":
        return MediaCacheIndex.name.collate("NOCASE")
    if sort_by == "created":
        return MediaCacheIndex.created_at
    if sort_by == "size":
        return MediaCacheIndex.size_bytes
    if sort_by == "duration":
        return MediaCacheIndex.duration_ms
    if sort_by == "resolution":
        # 必须与 ORM/Alembic 的表达式索引逐字等价。
        return MediaCacheIndex.width * MediaCacheIndex.height
    if sort_by == "random":
        seed = random_seed or datetime.now(UTC).date().isoformat()
        rotation = int(hashlib.sha256(seed.encode("utf-8")).hexdigest()[:8], 16) % 24
        # media_id 本身是 SHA-256 派生的均匀十六进制；按 seed 旋转后在 SQL 中排序，
        # 无需 ORDER BY RANDOM()，同一 seed 跨页和跨进程都稳定。
        return sa.func.substr(MediaCacheIndex.media_id, rotation + 1).op("||")(
            sa.func.substr(MediaCacheIndex.media_id, 1, rotation)
        )
    raise ValueError(f"不支持的媒体排序字段: {sort_by}")


def _media_filter_conditions(
    *,
    library_ids: Iterable[str],
    media_type: str | None = None,
    search: str | None = None,
    exclude_favorites: bool = False,
) -> list:
    """列表与文件夹视图共用的基础筛选条件。"""
    conditions = [
        MediaCacheIndex.is_available.is_(True),
        MediaCacheIndex.library_id.in_(sorted(set(library_ids))),
    ]
    if media_type:
        conditions.append(MediaCacheIndex.media_type == media_type)
    if search and search.strip():
        pattern = f"%{_escaped_like(search.strip().casefold())}%"
        conditions.append(sa.func.lower(MediaCacheIndex.name).like(pattern, escape="\\"))
    if exclude_favorites:
        conditions.append(
            ~sa.exists(sa.select(1).where(Favorite.media_id == MediaCacheIndex.media_id))
        )
    return conditions


def _folder_dirname_expression():
    """把 media_path 规范化为"父目录(以 / 结尾)"的 SQL 表达式。

    先统一 \\ 与 / 两种分隔符(连续分隔符折叠为一个),再剥离最后一段文件名;
    结果形如 "D:/Media/子目录/",盘根形如 "D:/"。NULL 输入保持 NULL。
    该表达式只在服务器内部使用,绝不把结果下发客户端。
    """
    path = MediaCacheIndex.media_path
    unified = sa.func.replace(sa.func.replace(path, "\\", "/"), "//", "/")
    return sa.func.rtrim(unified, sa.func.replace(unified, "/", ""))


def folder_id_for_dirname(dirname: str) -> str:
    """文件夹的不透明服务器 ID(SHA-256 前 16 个十六进制字符)。"""
    return hashlib.sha256(dirname.encode("utf-8")).hexdigest()[:16]


def _folder_display_name(dirname: str) -> str:
    stripped = dirname.rstrip("/")
    if not stripped or stripped.endswith(":"):
        return "(根目录)"
    return stripped.rsplit("/", 1)[-1] or "(根目录)"


def list_media_folder_groups(
    session: Session,
    *,
    library_ids: Iterable[str],
    media_type: str | None = None,
    search: str | None = None,
    exclude_favorites: bool = False,
) -> list[tuple[str, int]]:
    """按父目录聚合当前筛选范围内的媒体,返回 (目录名, 数量) 列表(按目录名排序)。"""
    conditions = _media_filter_conditions(
        library_ids=sorted(set(library_ids)),
        media_type=media_type,
        search=search,
        exclude_favorites=exclude_favorites,
    )
    dirname = _folder_dirname_expression()
    rows = session.execute(
        sa.select(dirname.label("dir"), sa.func.count().label("n"))
        .where(*conditions, dirname.is_not(None), dirname != "")
        .group_by(sa.literal_column("dir"))
        .order_by(sa.literal_column("dir"))
    ).all()
    return [(str(row.dir), int(row.n)) for row in rows]


def list_media_folders(
    session: Session,
    *,
    library_ids: Iterable[str],
    media_type: str | None = None,
    search: str | None = None,
    exclude_favorites: bool = False,
) -> list[dict]:
    """文件夹辅助视图: folder_id 是服务器派生的不透明 ID,绝不下发 Windows 路径。"""
    groups = list_media_folder_groups(
        session,
        library_ids=library_ids,
        media_type=media_type,
        search=search,
        exclude_favorites=exclude_favorites,
    )
    return [
        {
            "folder_id": folder_id_for_dirname(dirname),
            "name": _folder_display_name(dirname),
            "count": count,
        }
        for dirname, count in groups
    ]


def resolve_folder_dirname(
    session: Session,
    *,
    library_ids: Iterable[str],
    media_type: str | None = None,
    search: str | None = None,
    exclude_favorites: bool = False,
    folder_id: str,
) -> str | None:
    """在同一筛选范围内把 folder_id 反查为目录名;未知 folder_id 返回 None。"""
    for dirname, _count in list_media_folder_groups(
        session,
        library_ids=library_ids,
        media_type=media_type,
        search=search,
        exclude_favorites=exclude_favorites,
    ):
        if folder_id_for_dirname(dirname) == folder_id:
            return dirname
    return None


def list_cached_media(
    session: Session,
    *,
    library_ids: Iterable[str],
    media_type: str | None = None,
    search: str | None = None,
    exclude_favorites: bool = False,
    sort_by: str = "name",
    sort_order: str = "asc",
    page: int = 1,
    page_size: int = 50,
    random_seed: str | None = None,
    folder_dirname: str | None = None,
) -> tuple[list[MediaCacheIndex], int]:
    """在 SQLite 中完成 count、筛选、排序与分页，不把全表载入 Python。"""
    targets = sorted(set(library_ids))
    if not targets:
        return [], 0
    conditions = _media_filter_conditions(
        library_ids=targets,
        media_type=media_type,
        search=search,
        exclude_favorites=exclude_favorites,
    )
    if folder_dirname:
        conditions.append(_folder_dirname_expression() == folder_dirname)

    total = int(
        session.scalar(sa.select(sa.func.count()).select_from(MediaCacheIndex).where(*conditions))
        or 0
    )
    sort_value = _sort_expression(sort_by, random_seed)
    descending = sort_order == "desc"
    direction = sort_value.desc() if descending else sort_value.asc()
    tie_direction = (
        MediaCacheIndex.media_id.desc() if descending else MediaCacheIndex.media_id.asc()
    )
    offset = (page - 1) * page_size

    if sort_by == "random":
        statement = (
            sa.select(MediaCacheIndex)
            .where(*conditions)
            .order_by(direction, tie_direction)
            .offset(offset)
            .limit(page_size)
        )
        return list(session.scalars(statement).all()), total

    # NULL-last 不能在 ORDER BY 前加 `expr IS NULL`，否则 SQLite 无法使用排序索引。
    # 将 present/missing 分成少量索引查询，仍只取当前页，不加载全表。
    if sort_by == "name":
        present_condition = MediaCacheIndex.name.is_not(None)
        missing_conditions = [(MediaCacheIndex.name.is_(None), True)]
        present_total = total
    elif sort_by == "resolution":
        present_condition = sort_value > 0
        # 分开 NULL 和非正数，避免 OR 破坏表达式索引的 ORDER BY 路径。
        missing_conditions = [(sort_value.is_(None), True), (sort_value <= 0, False)]
        present_total = int(
            session.scalar(
                sa.select(sa.func.count())
                .select_from(MediaCacheIndex)
                .where(*conditions, present_condition)
            )
            or 0
        )
    else:
        present_condition = sort_value.is_not(None)
        missing_conditions = [(sort_value.is_(None), True)]
        present_total = int(
            session.scalar(
                sa.select(sa.func.count())
                .select_from(MediaCacheIndex)
                .where(*conditions, present_condition)
            )
            or 0
        )

    rows: list[MediaCacheIndex] = []
    if offset < present_total:
        present_limit = min(page_size, present_total - offset)
        present_statement = (
            sa.select(MediaCacheIndex)
            .where(*conditions, present_condition)
            .order_by(direction, tie_direction)
            .offset(offset)
            .limit(present_limit)
        )
        rows.extend(session.scalars(present_statement).all())

    if len(rows) < page_size and offset + page_size > present_total:
        missing_offset = max(0, offset - present_total)
        for missing_condition, sort_is_constant in missing_conditions:
            if len(rows) >= page_size:
                break
            missing_total = int(
                session.scalar(
                    sa.select(sa.func.count())
                    .select_from(MediaCacheIndex)
                    .where(*conditions, missing_condition)
                )
                or 0
            )
            if missing_offset >= missing_total:
                missing_offset -= missing_total
                continue
            missing_statement = (
                sa.select(MediaCacheIndex)
                .where(*conditions, missing_condition)
                .order_by(*([tie_direction] if sort_is_constant else [direction, tie_direction]))
                .offset(missing_offset)
                .limit(page_size - len(rows))
            )
            rows.extend(session.scalars(missing_statement).all())
            missing_offset = 0
    return rows, total


def get_cached_media(session: Session, media_id: str) -> MediaCacheIndex | None:
    return session.get(MediaCacheIndex, media_id)


def available_media_count(session: Session, library_ids: Iterable[str]) -> int:
    targets = sorted(set(library_ids))
    if not targets:
        return 0
    return int(
        session.scalar(
            sa.select(sa.func.count())
            .select_from(MediaCacheIndex)
            .where(
                MediaCacheIndex.is_available.is_(True),
                MediaCacheIndex.library_id.in_(targets),
            )
        )
        or 0
    )


# ---- 持久化后台刷新 ----


def _target_key(library_ids: Iterable[str]) -> str:
    canonical = ",".join(sorted(set(library_ids)))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:24]


def schedule_media_refresh(
    session: Session, library_ids: Iterable[str], *, force: bool = False
) -> BackgroundTask:
    """幂等编排目标库集合；force 不会绕过已有 pending/running 任务。"""
    targets = sorted({library_id for library_id in library_ids if library_id})
    if not targets:
        raise ValueError("媒体刷新至少需要一个媒体库")
    # 清理正常终态之外可能遗留的孤儿 claim；该条件 DELETE 同时取得 SQLite 写锁，
    # 让多个 scheduler 在读取/创建 claim 的整个外层请求事务中串行化。
    active_task = sa.exists(
        sa.select(1).where(
            BackgroundTask.task_id == MediaRefreshTarget.task_id,
            BackgroundTask.status.in_(("pending", "running")),
        )
    )
    session.execute(sa.delete(MediaRefreshTarget).where(~active_task))
    for _attempt in range(4):
        active_claims = list(
            session.execute(
                sa.select(MediaRefreshTarget.library_id, BackgroundTask)
                .join(BackgroundTask, BackgroundTask.task_id == MediaRefreshTarget.task_id)
                .where(
                    MediaRefreshTarget.library_id.in_(targets),
                    BackgroundTask.status.in_(("pending", "running")),
                )
                .order_by(BackgroundTask.created_at.desc(), BackgroundTask.task_id.desc())
            ).all()
        )
        claimed_ids = {library_id for library_id, _task in active_claims}
        remaining = [library_id for library_id in targets if library_id not in claimed_ids]
        if not remaining:
            return active_claims[0][1]

        target_key = _target_key(remaining)
        try:
            with session.begin_nested():
                task = BackgroundTask(
                    task_id=uuid.uuid4().hex,
                    type=TASK_TYPE_MEDIA_REFRESH,
                    status="pending",
                    params=json.dumps(
                        {"library_ids": remaining, "force": bool(force)},
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                    progress=0,
                    media_id=target_key,
                )
                session.add(task)
                session.flush()
                for library_id in remaining:
                    session.add(MediaRefreshTarget(library_id=library_id, task_id=task.task_id))
                    state = session.get(MediaSyncState, library_id)
                    if state is None:
                        state = MediaSyncState(library_id=library_id)
                        session.add(state)
                    state.state = "pending"
                    state.task_id = task.task_id
                    state.processed = 0
                    state.total = 0
                    state.last_error = None
                session.flush()
            return task
        except IntegrityError:
            session.expire_all()
            continue
    raise RuntimeError("媒体刷新目标正在被并发调度，请稍后重试")


def media_sync_view(session: Session, library_ids: Iterable[str], *, available_count: int) -> dict:
    targets = sorted(set(library_ids))
    rows = list(
        session.scalars(
            sa.select(MediaSyncState).where(MediaSyncState.library_id.in_(targets))
        ).all()
    )
    by_library = {row.library_id: row for row in rows}
    states = [by_library[target].state if target in by_library else "idle" for target in targets]
    priority = {"running": 0, "pending": 1, "failed": 2, "cancelled": 3, "succeeded": 4, "idle": 5}
    state = min(states, key=lambda value: priority.get(value, 6)) if states else "idle"
    selected = next((row for row in rows if row.state == state), rows[0] if rows else None)
    successes = [by_library[target].last_success_at for target in targets if target in by_library]
    last_success_at = (
        min(value for value in successes if value is not None)
        if len(successes) == len(targets) and all(value is not None for value in successes)
        else None
    )
    messages = {
        "idle": "媒体索引可用" if available_count else "尚未建立媒体索引",
        "pending": "媒体索引已进入后台同步队列",
        "running": "正在后台同步媒体索引",
        "succeeded": "媒体索引同步完成",
        "failed": _SYNC_ERROR,
        "cancelled": "媒体索引同步已取消",
    }
    return {
        "state": state,
        "stale": state in {"failed", "cancelled"}
        or (available_count > 0 and state in {"pending", "running"}),
        "task_id": selected.task_id if selected is not None else None,
        "processed": sum(row.processed for row in rows),
        "total": sum(row.total for row in rows),
        "last_success_at": last_success_at,
        "message": messages.get(state, "媒体索引状态未知"),
    }


def _task_targets(task: BackgroundTask) -> list[str]:
    try:
        payload = json.loads(task.params or "{}")
    except (TypeError, json.JSONDecodeError):
        return []
    values = payload.get("library_ids")
    if not isinstance(values, list):
        return []
    return sorted({value for value in values if isinstance(value, str) and value})


def _is_cancelled(database: Database, task_id: str) -> bool:
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        return task is None or task.status == "cancelled"


def _finish_cancelled(database: Database, task_id: str, targets: Iterable[str]) -> None:
    with database.session() as session:
        session.execute(
            sa.update(BackgroundTask)
            .where(
                BackgroundTask.task_id == task_id,
                BackgroundTask.status.in_(("pending", "running")),
            )
            .values(status="cancelled", finished_at=utc_now())
        )
        status = session.scalar(
            sa.select(BackgroundTask.status).where(BackgroundTask.task_id == task_id)
        )
        if status == "cancelled":
            session.execute(
                sa.update(MediaSyncState)
                .where(
                    MediaSyncState.library_id.in_(list(targets)),
                    MediaSyncState.task_id == task_id,
                    MediaSyncState.state.in_(("pending", "running")),
                )
                .values(state="cancelled", last_error=None)
            )
            session.execute(
                sa.delete(MediaRefreshTarget).where(MediaRefreshTarget.task_id == task_id)
            )
        session.commit()


def _finish_failed(database: Database, task_id: str, targets: Iterable[str]) -> None:
    with database.session() as session:
        claimed = session.execute(
            sa.update(BackgroundTask)
            .where(
                BackgroundTask.task_id == task_id,
                BackgroundTask.status == "running",
            )
            .values(status="failed", error=_SYNC_ERROR, finished_at=utc_now())
            .returning(BackgroundTask.task_id)
        ).first()
        if claimed is not None:
            session.execute(
                sa.update(MediaSyncState)
                .where(
                    MediaSyncState.library_id.in_(list(targets)),
                    MediaSyncState.task_id == task_id,
                )
                .values(state="failed", last_error=_SYNC_ERROR)
            )
            session.execute(
                sa.delete(MediaRefreshTarget).where(MediaRefreshTarget.task_id == task_id)
            )
        session.commit()


def _commit_refresh_success(
    database: Database,
    task_id: str,
    targets: Iterable[str],
    *,
    generation: str,
    totals: dict[str, int],
) -> bool:
    """CAS 抢占最终提交，并在同一写事务中失效 unseen 与完成全部状态。"""
    target_list = list(targets)
    finished_at = utc_now()
    with database.session() as session:
        claimed = session.execute(
            sa.update(BackgroundTask)
            .where(
                BackgroundTask.task_id == task_id,
                BackgroundTask.status == "running",
            )
            .values(
                status="succeeded",
                progress=100,
                result=json.dumps({"libraries": len(target_list)}, separators=(",", ":")),
                error=None,
                finished_at=finished_at,
            )
            .returning(BackgroundTask.task_id)
        ).first()
        if claimed is None:
            session.rollback()
            return False

        session.execute(
            sa.update(MediaCacheIndex)
            .where(
                MediaCacheIndex.library_id.in_(target_list),
                MediaCacheIndex.is_available.is_(True),
                sa.or_(
                    MediaCacheIndex.sync_generation.is_(None),
                    MediaCacheIndex.sync_generation != generation,
                ),
            )
            .values(is_available=False)
        )
        for library_id in target_list:
            total = totals[library_id]
            session.execute(
                sa.update(MediaSyncState)
                .where(
                    MediaSyncState.library_id == library_id,
                    MediaSyncState.task_id == task_id,
                )
                .values(
                    state="succeeded",
                    processed=total,
                    total=total,
                    last_success_at=finished_at,
                    last_error=None,
                )
            )
        session.execute(sa.delete(MediaRefreshTarget).where(MediaRefreshTarget.task_id == task_id))
        session.commit()
        return True


async def _refresh_media_index_async(
    database: Database,
    task_id: str,
    *,
    user_id: str,
    client_factory: Callable[[], JellyfinClient],
) -> None:
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        if task is None:
            return
        targets = _task_targets(task)
        if task.status == "cancelled":
            _finish_cancelled(database, task_id, targets)
            return
        now = utc_now()
        for library_id in targets:
            state = session.get(MediaSyncState, library_id)
            if state is None:
                state = MediaSyncState(library_id=library_id, task_id=task_id)
                session.add(state)
            state.state = "running"
            state.task_id = task_id
            state.processed = 0
            state.total = 0
            state.last_started_at = now
            state.last_error = None
        session.commit()

    generation = uuid.uuid4().hex
    completed = 0
    totals: dict[str, int] = {}
    async with client_factory() as client:
        for library_id in targets:
            start = 0
            total = 0
            while True:
                if _is_cancelled(database, task_id):
                    _finish_cancelled(database, task_id, targets)
                    return
                page, reported_total = await client.media_page(
                    user_id,
                    parent_id=library_id,
                    start_index=start,
                    limit=_LIBRARY_PAGE,
                )
                if _is_cancelled(database, task_id):
                    _finish_cancelled(database, task_id, targets)
                    return
                total = max(0, int(reported_total))
                if not page and start < total:
                    raise RuntimeError("Jellyfin 媒体分页提前结束")
                with database.session() as session:
                    task = session.get(BackgroundTask, task_id)
                    if task is None or task.status == "cancelled":
                        session.rollback()
                        _finish_cancelled(database, task_id, targets)
                        return
                    now = utc_now()
                    upsert_media_items(session, page, generation=generation, now=now)
                    start += len(page)
                    state = session.get(MediaSyncState, library_id)
                    if state is not None:
                        state.processed = start
                        state.total = total
                    session.commit()
                if not page or start >= total:
                    break

            # 所有目标库都成功后才统一隐藏本代未见的旧缓存。
            if _is_cancelled(database, task_id):
                _finish_cancelled(database, task_id, targets)
                return
            with database.session() as session:
                completed += 1
                totals[library_id] = total
                task = session.get(BackgroundTask, task_id)
                if task is not None and task.status == "running":
                    task.progress = min(99, int(completed * 100 / max(1, len(targets))))
                session.commit()

    if not _commit_refresh_success(
        database,
        task_id,
        targets,
        generation=generation,
        totals=totals,
    ):
        _finish_cancelled(database, task_id, targets)


def refresh_media_index(
    database: Database,
    task_id: str,
    *,
    user_id: str,
    client_factory: Callable[[], JellyfinClient],
) -> None:
    """TaskManager 线程处理器入口；所有异常只持久化为安全中文消息。"""
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        targets = _task_targets(task) if task is not None else []
    try:
        if not user_id:
            raise RuntimeError("缺少 Jellyfin 用户")
        asyncio.run(
            _refresh_media_index_async(
                database,
                task_id,
                user_id=user_id,
                client_factory=client_factory,
            )
        )
    except Exception:  # noqa: BLE001 - 上游异常统一脱敏，TaskManager 不暴露 traceback
        if _is_cancelled(database, task_id):
            _finish_cancelled(database, task_id, targets)
        else:
            _finish_failed(database, task_id, targets)


def make_media_refresh_handler(config: JellyfinConfig) -> Callable[[Database, str], None]:
    """按应用配置构造媒体刷新处理器；凭据只留在内存客户端中。

    必须捕获共享配置对象本身(不做深拷贝):首次媒体库配置会把自动发现的
    user_id 就地写回同一 Pydantic 实例;若使用快照,启动后新写入的 user_id
    对本处理器不可见,导致全新部署选库后媒体同步一直失败,直到重启服务。
    """

    def _handler(database: Database, task_id: str) -> None:
        refresh_media_index(
            database,
            task_id,
            user_id=config.user_id,
            client_factory=lambda: JellyfinClient(config),
        )

    return _handler


# ---- 采集辅助 ----


async def collect_library_items(
    client: JellyfinClient,
    user_id: str,
    library_id: str,
    *,
    include_types: str | None = None,
    search_term: str | None = None,
) -> list[MediaItem]:
    """分页拉取单个媒体库的全部统一 MediaItem。"""
    items: list[MediaItem] = []
    start = 0
    while True:
        page, total = await client.media_page(
            user_id,
            parent_id=library_id,
            start_index=start,
            limit=_LIBRARY_PAGE,
            include_types=include_types,
            search_term=search_term,
        )
        items.extend(page)
        start += len(page)
        if not page or start >= total:
            break
    return items


__all__ = [
    "TASK_TYPE_MEDIA_REFRESH",
    "apply_selection",
    "available_media_count",
    "collect_library_items",
    "get_cached_media",
    "get_media_snapshot",
    "invalidate_media_snapshots",
    "library_selection_rows",
    "list_cached_media",
    "list_media_folder_groups",
    "list_media_folders",
    "folder_id_for_dirname",
    "resolve_folder_dirname",
    "make_media_refresh_handler",
    "media_sync_view",
    "put_media_snapshot",
    "refresh_media_index",
    "schedule_media_refresh",
    "selected_library_ids",
    "upsert_libraries",
    "upsert_media_items",
]
