"""MediaReview 1.1 批阅数据库建队与稳定分页验收。"""

from __future__ import annotations

import time
from datetime import datetime
from pathlib import Path

import pytest
import sqlalchemy as sa

from app.adapters.jellyfin.client import JellyfinClient
from app.db import models
from app.db.session import Database
from app.services import review
from app.services.hash_contract import full_sha256_sql_predicate, is_full_sha256


def _media(
    media_id: str,
    *,
    name: str,
    library_id: str = "lib-a",
    media_type: str = "video",
    available: bool = True,
    sha256: str | None = None,
    quick_hash: str | None = None,
) -> models.MediaCacheIndex:
    return models.MediaCacheIndex(
        media_id=media_id,
        jellyfin_id=f"jf-{media_id}",
        library_id=library_id,
        name=name,
        media_type=media_type,
        duration_ms=1_000,
        size_bytes=10_000,
        width=1920,
        height=1080,
        fingerprint=f"fp-{media_id}",
        is_available=available,
        sha256=sha256,
        quick_hash=quick_hash,
    )


def _select_library(db: Database, library_id: str = "lib-a") -> None:
    with db.session() as session:
        session.add(
            models.LibrarySelection(
                jellyfin_id=library_id,
                name=library_id,
                selected=True,
            )
        )
        session.commit()


def test_create_session_is_sqlite_only_and_uses_sql_queue_contract(
    app, client, monkeypatch
) -> None:
    """建队不接触 Jellyfin，并在 SQL 中筛选、稳定排序及选完全重复代表项。"""
    db: Database = app.state.database
    _select_library(db)
    with db.session() as session:
        session.add_all(
            [
                _media("dup-a", name="Clip A.mp4", sha256="a" * 64),
                _media("dup-z", name="Clip Z.mp4", sha256="a" * 64),
                _media("sus-a", name="Clip Y.mp4", quick_hash="suspected"),
                _media("sus-b", name="Clip X.mp4", quick_hash="suspected"),
                _media("photo", name="Clip Photo.jpg", media_type="image"),
                _media("other", name="Clip Other.mp4", library_id="lib-b"),
                _media("gone", name="Clip Gone.mp4", available=False),
                _media("miss", name="Unrelated.mp4"),
            ]
        )
        session.commit()

    def forbidden_jellyfin(*_args, **_kwargs):
        raise AssertionError("创建批阅会话不得构造或访问 Jellyfin 客户端")

    monkeypatch.setattr(JellyfinClient, "__init__", forbidden_jellyfin)
    response = client.post(
        "/api/v1/review/sessions",
        json={
            "source": {
                "filter": {"media_type": "video", "search": "clip"},
                "sort": {"sort_by": "name", "sort_order": "desc"},
            }
        },
    )

    assert response.status_code == 200
    created = response.json()["data"]
    assert created["total_count"] == 3
    queue = client.get(f"/api/v1/review/sessions/{created['session_id']}/queue").json()["data"]
    assert [item["media"]["media_id"] for item in queue["items"]] == [
        "dup-z",
        "sus-a",
        "sus-b",
    ]
    assert [item["index"] for item in queue["items"]] == [0, 1, 2]
    with db.session() as session:
        assert session.scalar(sa.select(sa.func.count()).select_from(models.MediaCacheIndex)) == 8


@pytest.mark.parametrize(
    ("sha256", "quick_hash", "expected_total"),
    [
        ("a" * 64, None, 1),
        ("partial-hash", None, 2),
        ("g" * 64, None, 2),
        ("unreadable-io", None, 2),
        (None, "b" * 64, 2),
    ],
)
def test_review_only_folds_strict_full_sha256(
    app, client, sha256: str | None, quick_hash: str | None, expected_total: int
) -> None:
    db: Database = app.state.database
    _select_library(db)
    with db.session() as session:
        session.add_all(
            [
                _media("hash-a", name="A.mp4", sha256=sha256, quick_hash=quick_hash),
                _media("hash-b", name="B.mp4", sha256=sha256, quick_hash=quick_hash),
            ]
        )
        session.commit()
    response = client.post(
        "/api/v1/review/sessions",
        json={"source": {"filter": {}, "sort": {"sort_by": "name"}}},
    )
    assert response.status_code == 200
    assert response.json()["data"]["total_count"] == expected_total


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("a" * 64, True),
        ("ABCDEF0123456789" * 4, True),
        ("\0" + "a" * 63, False),
        ("a" * 31 + "\0" + "a" * 32, False),
        ("a" * 63 + "\0", False),
        ("a" * 64 + "\0suffix", False),
        (b"a" * 64, False),
        ("é" * 64, False),
        ("g" * 64, False),
    ],
)
def test_sql_full_sha256_predicate_matches_python_for_sqlite_edge_values(
    tmp_path: Path, value: object, expected: bool
) -> None:
    db = Database(tmp_path / "database" / "mediareview.db")
    db.create_all()
    with db.session() as session:
        sql_result = session.scalar(sa.select(full_sha256_sql_predicate(sa.literal(value))))
    assert is_full_sha256(value) is expected  # type: ignore[arg-type]
    assert bool(sql_result) is expected
    db.dispose()


@pytest.mark.parametrize(
    "invalid_sha256",
    [
        "\0" + "a" * 63,
        "a" * 31 + "\0" + "a" * 32,
        "a" * 63 + "\0",
        "a" * 64 + "\0suffix",
        b"a" * 64,
        "é" * 64,
        "g" * 64,
    ],
)
def test_review_queue_never_folds_sqlite_values_rejected_by_python_contract(
    app, client, invalid_sha256: object
) -> None:
    db: Database = app.state.database
    _select_library(db)
    with db.session() as session:
        first = _media("invalid-a", name="A.mp4")
        second = _media("invalid-b", name="B.mp4")
        first.sha256 = invalid_sha256  # type: ignore[assignment]
        second.sha256 = invalid_sha256  # type: ignore[assignment]
        session.add_all([first, second])
        session.commit()

    response = client.post(
        "/api/v1/review/sessions",
        json={"source": {"filter": {}, "sort": {"sort_by": "name"}}},
    )

    assert response.status_code == 200
    assert response.json()["data"]["total_count"] == 2


def test_invalid_or_failed_create_rolls_back_old_active_session(app, client, monkeypatch) -> None:
    db: Database = app.state.database
    _select_library(db)
    with db.session() as session:
        session.add(_media("valid", name="Valid.mp4"))
        old = review.create_session(session, ["valid"])
        old_id = old.session_id
        session.commit()

    invalid = client.post(
        "/api/v1/review/sessions",
        json={"source": {"filter": {}, "sort": {"sort_by": "path", "sort_order": "asc"}}},
    )
    assert invalid.status_code == 422

    real_execute = sa.orm.Session.execute

    def fail_queue_insert(self, statement, *args, **kwargs):
        if "INSERT INTO review_session_item" in str(statement):
            raise RuntimeError("isolated insert failure")
        return real_execute(self, statement, *args, **kwargs)

    monkeypatch.setattr(sa.orm.Session, "execute", fail_queue_insert)
    with pytest.raises(RuntimeError, match="isolated insert failure"):
        client.post(
            "/api/v1/review/sessions",
            json={"source": {"filter": {}, "sort": {"sort_by": "name"}}},
        )
    with db.session() as session:
        sessions = list(session.scalars(sa.select(models.ReviewSession)).all())
        assert [(row.session_id, row.status) for row in sessions] == [(old_id, "active")]
        assert session.scalar(sa.select(sa.func.count()).select_from(models.ReviewSessionItem)) == 1


def test_random_queue_order_is_saved_with_a_reusable_seed(app, client) -> None:
    db: Database = app.state.database
    _select_library(db)
    with db.session() as session:
        session.add_all([_media(f"m{i:02d}", name=f"Movie {i:02d}") for i in range(20)])
        session.commit()

    first = client.post(
        "/api/v1/review/sessions",
        json={"source": {"filter": {}, "sort": {"sort_by": "random"}}},
    ).json()["data"]
    seed = first["source"]["sort"]["random_seed"]
    first_ids = [
        item["media"]["media_id"]
        for item in client.get(f"/api/v1/review/sessions/{first['session_id']}/queue").json()[
            "data"
        ]["items"]
    ]
    second = client.post(
        "/api/v1/review/sessions",
        json={
            "source": {
                "filter": {},
                "sort": {"sort_by": "random", "random_seed": seed},
            }
        },
    ).json()["data"]
    second_ids = [
        item["media"]["media_id"]
        for item in client.get(f"/api/v1/review/sessions/{second['session_id']}/queue").json()[
            "data"
        ]["items"]
    ]
    assert second["source"]["sort"]["random_seed"] == seed
    assert second_ids == first_ids


def test_queue_page_uses_sql_paging_and_preserves_absolute_holes(app, client, monkeypatch) -> None:
    db: Database = app.state.database
    with db.session() as session:
        session.add_all(
            [
                _media("m0", name="M0.mp4"),
                _media("m1", name="M1.mp4", available=False),
                _media("m2", name="M2.mp4"),
            ]
        )
        created = review.create_session(session, ["m0", "m1", "missing", "m2"])
        session_id = created.session_id
        session.commit()

    def forbidden_materialization(*_args, **_kwargs):
        raise AssertionError("队列分页不得调用 session_items 全量物化")

    monkeypatch.setattr(review, "session_items", forbidden_materialization)
    statements: list[str] = []

    @sa.event.listens_for(db.engine, "before_cursor_execute")
    def capture(_conn, _cursor, statement, _parameters, _context, _executemany):
        statements.append(" ".join(statement.lower().split()))

    page1 = client.get(
        f"/api/v1/review/sessions/{session_id}/queue", params={"page": 1, "page_size": 2}
    )
    page2 = client.get(
        f"/api/v1/review/sessions/{session_id}/queue", params={"page": 2, "page_size": 2}
    )
    assert page1.status_code == 200 and page2.status_code == 200
    assert page1.json()["data"]["total"] == 4
    assert [item["index"] for item in page1.json()["data"]["items"]] == [0]
    assert [item["index"] for item in page2.json()["data"]["items"]] == [3]
    queue_selects = [sql for sql in statements if "review_session_item" in sql]
    assert any("count(" in sql for sql in queue_selects)
    assert sum(" join media_cache_index " in sql for sql in queue_selects) == 2
    assert not any("where media_cache_index.media_id =" in sql for sql in statements)


def test_latest_active_session_breaks_timestamp_ties_deterministically(tmp_path: Path) -> None:
    db = Database(tmp_path / "database" / "review-tie.db")
    db.create_all()
    tied = datetime(2026, 8, 24, 12, 0, 0)
    try:
        with db.session() as session:
            session.add_all(
                [
                    models.ReviewSession(
                        session_id="session-a",
                        status="active",
                        created_at=tied,
                        updated_at=tied,
                    ),
                    models.ReviewSession(
                        session_id="session-z",
                        status="active",
                        created_at=tied,
                        updated_at=tied,
                    ),
                ]
            )
            session.commit()
            assert review.latest_active_session(session).session_id == "session-z"
    finally:
        db.dispose()


def test_builds_one_hundred_thousand_queue_rows_in_under_five_seconds(tmp_path: Path) -> None:
    """隔离性能验收：门槛仅针对测试机，不是生产 SLA。"""
    db = Database(tmp_path / "database" / "review-100k.db")
    db.create_all()
    try:
        with db.engine.begin() as connection:
            connection.execute(
                sa.insert(models.LibrarySelection),
                [{"jellyfin_id": "lib-big", "name": "大库", "selected": True}],
            )
            rows = (
                {
                    "media_id": f"{i:024x}",
                    "jellyfin_id": f"jf-{i}",
                    "library_id": "lib-big",
                    "name": f"Movie {i:06d}.mp4",
                    "media_type": "video",
                    "fingerprint": f"fp-{i}",
                    "is_available": True,
                }
                for i in range(100_000)
            )
            for _batch_start in range(0, 100_000, 5_000):
                batch = [next(rows) for _ in range(5_000)]
                connection.execute(sa.insert(models.MediaCacheIndex), batch)

        with db.session() as session:
            started = time.perf_counter()
            created = review.create_session_from_index(
                session,
                library_ids=["lib-big"],
                filter_snapshot={"media_type": "video"},
                sort_snapshot={"sort_by": "name", "sort_order": "asc"},
            )
            session.commit()
            elapsed = time.perf_counter() - started
            assert created.total_count == 100_000
            assert (
                session.scalar(
                    sa.select(sa.func.count())
                    .select_from(models.ReviewSessionItem)
                    .where(models.ReviewSessionItem.session_id == created.session_id)
                )
                == 100_000
            )
        print(f"100k_queue_build_seconds={elapsed:.3f}")
        assert elapsed < 5.0, f"100k 建队耗时 {elapsed:.3f}s，超过测试机 5s 门槛"
    finally:
        db.dispose()
