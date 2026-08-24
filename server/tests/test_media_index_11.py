"""MediaReview 1.1 数据库优先媒体索引验收。"""

from __future__ import annotations

import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Barrier

import sqlalchemy as sa

from app.adapters.jellyfin.models import MediaItem
from app.db import models
from app.db.session import Database
from app.services import media_index
from app.services import tasks as task_service
from app.services.tasks import TaskManager


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


def test_legacy_upsert_chunks_16k_items_below_sqlite_variable_limit(tmp_path: Path) -> None:
    database = _database(tmp_path)
    try:
        items = [_item(f"{idx:024x}") for idx in range(16_000)]
        with database.session() as session:
            inserted = media_index.upsert_media_items(session, items)
            session.commit()
        with database.session() as session:
            assert inserted == 16_000
            assert (
                session.scalar(sa.select(sa.func.count()).select_from(models.MediaCacheIndex))
                == 16_000
            )
            assert session.get(models.MediaCacheIndex, f"{15_999:024x}") is not None
            assert media_index.upsert_media_items(session, items) == 0
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


def test_cancel_racing_after_last_status_check_cannot_hide_unseen_cache(
    tmp_path: Path, monkeypatch
) -> None:
    """最终失效必须与 running→succeeded CAS 同一事务，封住末页取消竞态。"""
    database = _database(tmp_path)
    try:
        seen_id = "c" * 24
        unseen_id = "d" * 24
        with database.session() as session:
            session.add_all([_row(seen_id), _row(unseen_id)])
            session.commit()
        task_id = _scheduled_refresh(database, ["lib-a"])
        fake = _PagedClient([([_item(seen_id)], 1)])
        real_is_cancelled = media_index._is_cancelled
        checks = 0

        def cancel_after_last_check(db, checked_task_id):
            nonlocal checks
            checks += 1
            if checks == 3:
                with db.session() as session:
                    task = session.get(models.BackgroundTask, checked_task_id)
                    assert task is not None
                    task.status = "cancelled"
                    session.commit()
                # 模拟取消恰好落在旧实现的 check 与失效 UPDATE 之间。
                return False
            return real_is_cancelled(db, checked_task_id)

        monkeypatch.setattr(media_index, "_is_cancelled", cancel_after_last_check)
        media_index.refresh_media_index(
            database,
            task_id,
            user_id="user-a",
            client_factory=lambda: fake,
        )

        with database.session() as session:
            unseen = session.get(models.MediaCacheIndex, unseen_id)
            task = session.get(models.BackgroundTask, task_id)
            assert checks >= 3
            assert unseen is not None and unseen.is_available is True
            assert task is not None and task.status == "cancelled"
    finally:
        database.dispose()


class _SecondLibraryFailsClient:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *_exc: object) -> None:
        return None

    async def media_page(self, _user_id, *, parent_id, **_kwargs):
        if parent_id == "lib-a":
            return [_item("e" * 24, library_id="lib-a")], 1
        raise RuntimeError("second library failed")


def test_second_library_failure_does_not_invalidate_first_library(tmp_path: Path) -> None:
    """任一目标库失败时，本轮所有库都不得提交 unseen 失效。"""
    database = _database(tmp_path)
    try:
        unseen_a = "f" * 24
        unseen_b = "1" * 24
        with database.session() as session:
            session.add_all(
                [
                    _row(unseen_a, library_id="lib-a"),
                    _row(unseen_b, library_id="lib-b"),
                ]
            )
            session.commit()
        task_id = _scheduled_refresh(database, ["lib-a", "lib-b"])

        media_index.refresh_media_index(
            database,
            task_id,
            user_id="user-a",
            client_factory=_SecondLibraryFailsClient,
        )

        with database.session() as session:
            task = session.get(models.BackgroundTask, task_id)
            states = {
                row.library_id: row.state
                for row in session.scalars(
                    sa.select(models.MediaSyncState).where(models.MediaSyncState.task_id == task_id)
                )
            }
            assert task is not None and task.status == "failed"
            assert session.get(models.MediaCacheIndex, unseen_a).is_available is True
            assert session.get(models.MediaCacheIndex, unseen_b).is_available is True
            assert states == {"lib-a": "failed", "lib-b": "failed"}
    finally:
        database.dispose()


def test_two_task_managers_atomically_claim_one_pending_task(tmp_path: Path) -> None:
    """两个进程式 manager 并发 claim 时只能有一个获得同一 task。"""
    database = _database(tmp_path)
    try:
        with database.session() as session:
            session.add(
                models.BackgroundTask(
                    task_id="claim-once",
                    type="media_refresh",
                    status="pending",
                )
            )
            session.commit()

        managers = [TaskManager(database), TaskManager(database)]
        select_barrier = Barrier(2)

        @sa.event.listens_for(database.engine, "before_cursor_execute")
        def synchronize_legacy_select(
            _conn, _cursor, statement, _parameters, _context, _executemany
        ):
            normalized = " ".join(statement.lower().split())
            if normalized.startswith("select") and "from background_task" in normalized:
                select_barrier.wait(timeout=5)

        with ThreadPoolExecutor(max_workers=2) as pool:
            claims = list(pool.map(lambda manager: manager._claim_next(), managers))

        winners = [claim for claim in claims if claim is not None]
        assert len(winners) == 1
        assert winners[0][0] == "claim-once"
    finally:
        database.dispose()


def test_stale_cancel_cannot_overwrite_successful_refresh_cas(tmp_path: Path) -> None:
    database = _database(tmp_path)
    stale_session = database.session()
    try:
        old_id = "7" * 24
        with database.session() as session:
            row = _row(old_id)
            row.sync_generation = "old-generation"
            session.add(row)
            session.commit()
        task_id = _scheduled_refresh(database, ["lib-a"])
        stale_task = stale_session.get(models.BackgroundTask, task_id)
        assert stale_task is not None and stale_task.status == "running"

        committed = media_index._commit_refresh_success(
            database,
            task_id,
            ["lib-a"],
            generation="new-generation",
            totals={"lib-a": 0},
        )
        task_service.cancel_task(stale_session, stale_task)
        stale_session.commit()

        with database.session() as session:
            task = session.get(models.BackgroundTask, task_id)
            state = session.get(models.MediaSyncState, "lib-a")
            assert committed is True
            assert task is not None and task.status == "succeeded"
            assert state is not None and state.state == "succeeded"
            assert session.get(models.MediaCacheIndex, old_id).is_available is False
    finally:
        stale_session.close()
        database.dispose()


def test_overlapping_refresh_targets_are_decomposed_into_disjoint_active_tasks(
    tmp_path: Path,
) -> None:
    database = _database(tmp_path)
    try:
        with database.session() as session:
            task_a = media_index.schedule_media_refresh(session, ["lib-a"])
            task_b = media_index.schedule_media_refresh(session, ["lib-a", "lib-b"])
            session.commit()
            targets_a = set(media_index._task_targets(task_a))
            targets_b = set(media_index._task_targets(task_b))

        assert targets_a == {"lib-a"}
        assert targets_b == {"lib-b"}
        assert targets_a.isdisjoint(targets_b)

        with database.session() as session:
            task_a.status = "running"
            task_b.status = "running"
            session.merge(task_a)
            session.merge(task_b)
            media_index.upsert_media_items(
                session,
                [_item("8" * 24, library_id="lib-a")],
                generation="gen-a",
            )
            media_index.upsert_media_items(
                session,
                [_item("9" * 24, library_id="lib-b")],
                generation="gen-b",
            )
            session.commit()

        assert media_index._commit_refresh_success(
            database,
            task_a.task_id,
            targets_a,
            generation="gen-a",
            totals={"lib-a": 1},
        )
        assert media_index._commit_refresh_success(
            database,
            task_b.task_id,
            targets_b,
            generation="gen-b",
            totals={"lib-b": 1},
        )
        with database.session() as session:
            assert session.get(models.MediaCacheIndex, "8" * 24).is_available is True
            assert session.get(models.MediaCacheIndex, "9" * 24).is_available is True
    finally:
        database.dispose()


def test_database_rejects_two_active_claims_for_same_library(tmp_path: Path) -> None:
    database = _database(tmp_path)
    try:
        with database.session() as session:
            session.add_all(
                [
                    models.MediaRefreshTarget(library_id="lib-a", task_id="task-a"),
                    models.MediaRefreshTarget(library_id="lib-a", task_id="task-ab"),
                ]
            )
            try:
                session.commit()
            except sa.exc.IntegrityError:
                session.rollback()
            else:
                raise AssertionError("duplicate active library claim was accepted")
    finally:
        database.dispose()


def test_concurrent_overlapping_schedulers_leave_only_disjoint_claims(tmp_path: Path) -> None:
    database = _database(tmp_path)
    barrier = Barrier(2)
    try:

        def schedule(targets: list[str]) -> str:
            with database.session() as session:
                barrier.wait(timeout=5)
                task = media_index.schedule_media_refresh(session, targets)
                session.commit()
                return task.task_id

        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [
                pool.submit(schedule, ["lib-a"]),
                pool.submit(schedule, ["lib-a", "lib-b"]),
            ]
            returned_ids = {future.result(timeout=10) for future in futures}

        with database.session() as session:
            claims = list(session.scalars(sa.select(models.MediaRefreshTarget)).all())
            active_tasks = list(
                session.scalars(
                    sa.select(models.BackgroundTask).where(
                        models.BackgroundTask.task_id.in_({claim.task_id for claim in claims})
                    )
                ).all()
            )
            target_sets = [set(media_index._task_targets(task)) for task in active_tasks]
            assert {claim.library_id for claim in claims} == {"lib-a", "lib-b"}
            assert returned_ids <= {task.task_id for task in active_tasks}
            assert all(
                left.isdisjoint(right)
                for index, left in enumerate(target_sets)
                for right in target_sets[index + 1 :]
            )
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
