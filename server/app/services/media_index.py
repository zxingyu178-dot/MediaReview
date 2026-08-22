"""媒体索引服务: 媒体库勾选 + 媒体缓存索引 + 媒体快照缓存。

阶段 3 职责:
- 媒体库勾选状态持久化(library_selection)
- 媒体元数据缓存索引写入/读取(media_cache_index)
- 媒体快照缓存(内存 LRU + TTL):避免媒体墙每翻一页就重新扫描 Jellyfin 全库

只缓存本项目需要的字段,不复制 Jellyfin 数据库(见 ARCHITECTURE 第 7 节)。
所有写操作在请求会话内完成,避免阻塞普通 HTTP。大库媒体采集由媒体 API 分页进行,
本模块只负责落地与查询。
"""

from __future__ import annotations

import time
from collections.abc import Iterable
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.adapters.jellyfin.client import JellyfinClient
from app.adapters.jellyfin.models import Library, MediaItem
from app.db.models import LibrarySelection, MediaCacheIndex, utc_now

# 单次从 Jellyfin 拉取媒体列表的分页上限,防止一次请求过重
_LIBRARY_PAGE = 500

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
    existing = {row.jellyfin_id: row for row in session.scalars(select(LibrarySelection)).all()}
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
        session.scalars(select(LibrarySelection).order_by(LibrarySelection.sort_order)).all()
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
            select(LibrarySelection)
            .where(LibrarySelection.selected.is_(True))
            .order_by(LibrarySelection.sort_order)
        ).all()
    ]


# ---- 媒体缓存索引 ----


def _to_index_row(session: Session, item: MediaItem, *, now) -> MediaCacheIndex:
    return MediaCacheIndex(
        media_id=item.media_id,
        jellyfin_id=item.jellyfin_id,
        library_id=item.library_id,
        name=item.name,
        media_type=item.media_type,
        duration_ms=item.duration_ms,
        size_bytes=item.size_bytes,
        width=item.width,
        height=item.height,
        container=item.container,
        fingerprint=item.fingerprint,
        created_at=item.created_at,
        modified_at=item.modified_at,
        synced_at=now,
        media_path=item.media_path,
    )


def upsert_media_items(session: Session, items: Iterable[MediaItem]) -> int:
    """把统一 MediaItem 写入 media_cache_index(按 media_id upsert)。返回新增条数。"""
    existing = {row.media_id: row for row in session.scalars(select(MediaCacheIndex)).all()}
    now = utc_now()
    inserted = 0
    for item in items:
        if not item.media_id:
            continue
        row = existing.get(item.media_id)
        if row is None:
            session.add(_to_index_row(session, item, now=now))
            inserted += 1
        else:
            row.jellyfin_id = item.jellyfin_id
            row.library_id = item.library_id
            row.name = item.name
            row.media_type = item.media_type
            row.duration_ms = item.duration_ms
            row.size_bytes = item.size_bytes
            row.width = item.width
            row.height = item.height
            row.container = item.container
            row.media_path = item.media_path
            row.fingerprint = item.fingerprint
            row.created_at = item.created_at
            row.modified_at = item.modified_at
            row.synced_at = now
    session.flush()
    return inserted


def get_cached_media(session: Session, media_id: str) -> MediaCacheIndex | None:
    return session.get(MediaCacheIndex, media_id)


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
