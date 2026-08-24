"""MediaReview 1.1 数据库优先媒体索引验收。"""

from __future__ import annotations

import time
from pathlib import Path

import sqlalchemy as sa

from app.adapters.jellyfin.models import MediaItem
from app.db import models
from app.db.session import Database
from app.services import media_index


def _database(tmp_path: Path) -> Database:
    database = Database(tmp_path / "mediareview.db")
    database.create_all()
    return database


def _row(
    media_id: str,
    *,
    library_id: str = "lib-a",
    name: str | None = None,
    size_bytes: int | None = 1,
    duration_ms: int | None = 1,
    width: int | None = 1,
    height: int | None = 1,
) -> models.MediaCacheIndex:
    return models.MediaCacheIndex(
        media_id=media_id,
        jellyfin_id=f"jf-{media_id}",
        library_id=library_id,
        name=name or media_id,
        media_type="video",
        size_bytes=size_bytes,
        duration_ms=duration_ms,
        width=width,
        height=height,
        fingerprint=f"fp-{media_id}",
    )


def _item(media_id: str, *, library_id: str = "lib-a") -> MediaItem:
    return MediaItem(
        media_id=media_id,
        jellyfin_id=f"jf-{media_id}",
        library_id=library_id,
        name=media_id,
        media_type="video",
        fingerprint=f"fp-{media_id}",
    )


def test_list_cached_media_filters_counts_and_keeps_missing_values_last(tmp_path: Path) -> None:
    database = _database(tmp_path)
    try:
        with database.session() as session:
            session.add_all(
                [
                    _row("a" * 24, name="Alpha", size_bytes=10),
                    _row("b" * 24, name="Beta", size_bytes=None),
                    _row("c" * 24, name="Gamma", size_bytes=30),
                    _row("d" * 24, library_id="lib-b", name="Other", size_bytes=99),
                ]
            )
            session.add(models.Favorite(media_id="c" * 24))
            session.commit()

            rows, total = media_index.list_cached_media(
                session,
                library_ids=["lib-a"],
                media_type="video",
                search="a",
                exclude_favorites=True,
                sort_by="size",
                sort_order="desc",
                page=1,
                page_size=50,
            )
            assert total == 2
            assert [row.media_id for row in rows] == ["a" * 24, "b" * 24]

            rows, _ = media_index.list_cached_media(
                session,
                library_ids=["lib-a"],
                sort_by="size",
                sort_order="asc",
                page=1,
                page_size=50,
            )
            assert rows[-1].media_id == "b" * 24
    finally:
        database.dispose()


def test_list_cached_media_excludes_unavailable_and_random_is_page_stable(tmp_path: Path) -> None:
    database = _database(tmp_path)
    try:
        with database.session() as session:
            rows = [_row(f"{idx:024x}") for idx in range(30)]
            rows[-1].is_available = False
            session.add_all(rows)
            session.commit()

            page1, total = media_index.list_cached_media(
                session,
                library_ids=["lib-a"],
                sort_by="random",
                sort_order="asc",
                random_seed="fixed-seed",
                page=1,
                page_size=10,
            )
            page2, _ = media_index.list_cached_media(
                session,
                library_ids=["lib-a"],
                sort_by="random",
                sort_order="asc",
                random_seed="fixed-seed",
                page=2,
                page_size=10,
            )
            again, _ = media_index.list_cached_media(
                session,
                library_ids=["lib-a"],
                sort_by="random",
                sort_order="asc",
                random_seed="fixed-seed",
                page=1,
                page_size=10,
            )

            assert total == 29
            assert [row.media_id for row in page1] == [row.media_id for row in again]
            assert {row.media_id for row in page1}.isdisjoint(row.media_id for row in page2)
            assert rows[-1].media_id not in {row.media_id for row in page1 + page2}
    finally:
        database.dispose()


def test_list_cached_media_100k_page_under_one_second(tmp_path: Path) -> None:
    database = _database(tmp_path)
    try:
        payload = [
            {
                "media_id": f"{idx:024x}",
                "jellyfin_id": f"jf-{idx}",
                "library_id": "lib-big",
                "name": f"movie-{idx:06d}",
                "media_type": "video",
                "fingerprint": f"fp-{idx}",
                "is_available": True,
            }
            for idx in range(100_000)
        ]
        with database.engine.begin() as connection:
            connection.execute(sa.insert(models.MediaCacheIndex), payload)

        with database.session() as session:
            started = time.perf_counter()
            rows, total = media_index.list_cached_media(
                session,
                library_ids=["lib-big"],
                sort_by="name",
                sort_order="asc",
                page=1000,
                page_size=50,
            )
            elapsed = time.perf_counter() - started

        assert total == 100_000
        assert len(rows) == 50
        assert elapsed < 1.0, f"100,000-row SQL page took {elapsed:.3f}s"
    finally:
        database.dispose()


def test_legacy_upsert_does_not_clear_active_sync_generation(tmp_path: Path) -> None:
    database = _database(tmp_path)
    try:
        media_id = "a" * 24
        with database.session() as session:
            row = _row(media_id)
            row.sync_generation = "active-generation"
            session.add(row)
            session.commit()

            media_index.upsert_media_items(session, [_item(media_id)])
            session.commit()
        with database.session() as session:
            refreshed = session.get(models.MediaCacheIndex, media_id)
            assert refreshed is not None
            assert refreshed.sync_generation == "active-generation"
    finally:
        database.dispose()


class _FailingClient:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *_exc: object) -> None:
        return None

    async def media_page(self, *_args, **_kwargs):
        raise RuntimeError("raw traceback token=must-not-leak")


class _PagedClient:
    def __init__(self, pages: list[tuple[list[MediaItem], int]], on_call=None) -> None:
        self.pages = pages
        self.calls = 0
        self.on_call = on_call

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_exc: object) -> None:
        return None

    async def media_page(self, *_args, **_kwargs):
        page = self.pages[self.calls]
        self.calls += 1
        if self.on_call is not None:
            self.on_call(self.calls)
        return page


def _scheduled_refresh(database: Database, library_ids: list[str]) -> str:
    with database.session() as session:
        task = media_index.schedule_media_refresh(session, library_ids)
        task.status = "running"
        session.commit()
        return task.task_id


def test_failed_refresh_keeps_old_cache_available_and_sanitizes_error(tmp_path: Path) -> None:
    database = _database(tmp_path)
    try:
        with database.session() as session:
            session.add(_row("a" * 24))
            session.commit()
        task_id = _scheduled_refresh(database, ["lib-a"])

        media_index.refresh_media_index(
            database,
            task_id,
            user_id="user-a",
            client_factory=_FailingClient,
        )

        with database.session() as session:
            cached = session.get(models.MediaCacheIndex, "a" * 24)
            task = session.get(models.BackgroundTask, task_id)
            sync = session.get(models.MediaSyncState, "lib-a")
            assert cached is not None and cached.is_available is True
            assert task is not None and task.status == "failed"
            assert sync is not None and sync.state == "failed"
            assert sync.last_error == "媒体同步失败，请稍后重试"
            assert "traceback" not in (task.error or "").lower()
            assert "token" not in (task.error or "").lower()
            view = media_index.media_sync_view(session, ["lib-a"], available_count=1)
            assert view["stale"] is True
            assert view["message"] == "媒体同步失败，请稍后重试"
    finally:
        database.dispose()


def test_cancelled_refresh_stops_between_pages_without_hiding_old_cache(tmp_path: Path) -> None:
    database = _database(tmp_path)
    try:
        old_id = "a" * 24
        with database.session() as session:
            session.add(_row(old_id))
            session.commit()
        task_id = _scheduled_refresh(database, ["lib-a"])

        def cancel_after_first_page(call_count: int) -> None:
            if call_count == 1:
                with database.session() as session:
                    task = session.get(models.BackgroundTask, task_id)
                    assert task is not None
                    task.status = "cancelled"
                    session.commit()

        fake = _PagedClient([([_item("b" * 24)], 501)], on_call=cancel_after_first_page)
        media_index.refresh_media_index(
            database,
            task_id,
            user_id="user-a",
            client_factory=lambda: fake,
        )

        with database.session() as session:
            cached = session.get(models.MediaCacheIndex, old_id)
            task = session.get(models.BackgroundTask, task_id)
            sync = session.get(models.MediaSyncState, "lib-a")
            assert fake.calls == 1
            assert cached is not None and cached.is_available is True
            assert task is not None and task.status == "cancelled"
            assert sync is not None and sync.state == "cancelled"
    finally:
        database.dispose()


def test_cancellation_after_final_page_does_not_hide_unseen_cache(
    tmp_path: Path, monkeypatch
) -> None:
    database = _database(tmp_path)
    try:
        seen_id = "a" * 24
        unseen_id = "b" * 24
        with database.session() as session:
            session.add_all([_row(seen_id), _row(unseen_id)])
            session.commit()
        task_id = _scheduled_refresh(database, ["lib-a"])
        fake = _PagedClient([([_item(seen_id)], 1)])
        real_upsert = media_index.upsert_media_items

        def cancel_after_upsert(session, items, **kwargs):
            result = real_upsert(session, items, **kwargs)
            task = session.get(models.BackgroundTask, task_id)
            assert task is not None
            task.status = "cancelled"
            return result

        monkeypatch.setattr(media_index, "upsert_media_items", cancel_after_upsert)
        media_index.refresh_media_index(
            database,
            task_id,
            user_id="user-a",
            client_factory=lambda: fake,
        )

        with database.session() as session:
            unseen = session.get(models.MediaCacheIndex, unseen_id)
            sync = session.get(models.MediaSyncState, "lib-a")
            assert unseen is not None and unseen.is_available is True
            assert sync is not None and sync.state == "cancelled"
    finally:
        database.dispose()


def test_successful_refresh_marks_unseen_rows_unavailable_only_after_completion(
    tmp_path: Path,
) -> None:
    database = _database(tmp_path)
    try:
        seen_id = "a" * 24
        unseen_id = "b" * 24
        with database.session() as session:
            session.add_all([_row(seen_id), _row(unseen_id)])
            session.commit()
        task_id = _scheduled_refresh(database, ["lib-a"])
        fake = _PagedClient([([_item(seen_id)], 1)])

        media_index.refresh_media_index(
            database,
            task_id,
            user_id="user-a",
            client_factory=lambda: fake,
        )

        with database.session() as session:
            seen = session.get(models.MediaCacheIndex, seen_id)
            unseen = session.get(models.MediaCacheIndex, unseen_id)
            sync = session.get(models.MediaSyncState, "lib-a")
            assert seen is not None and seen.is_available is True
            assert unseen is not None and unseen.is_available is False
            assert sync is not None and sync.state == "succeeded"
            assert sync.last_success_at is not None
    finally:
        database.dispose()
