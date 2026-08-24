"""MediaReview 1.1 媒体刷新与通用任务 API 验收。"""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import httpx
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.adapters.jellyfin.client import JellyfinClient
from app.api.v1.jellyfin import jellyfin_client
from app.core.config import AppConfig, SecurityConfig, StorageConfig
from app.db import models
from app.main import create_app


def _configured_app(data_root: Path, handler, *, pairing_required: bool = False):
    config = AppConfig(
        storage=StorageConfig(data_root=str(data_root)),
        security=SecurityConfig(pairing_required=pairing_required),
    )
    config.jellyfin.api_key = SecretStr("test-api-key")
    config.jellyfin.user_id = "user-a"
    transport = httpx.MockTransport(handler)
    app = create_app(config)

    async def override_client() -> AsyncIterator[JellyfinClient]:
        async with JellyfinClient(config.jellyfin, transport=transport) as client:
            yield client

    app.dependency_overrides[jellyfin_client] = override_client
    return app


def _seed_selected(client: TestClient, library_id: str = "lib-a") -> None:
    with client.app.state.database.session() as session:
        session.add(
            models.LibrarySelection(
                jellyfin_id=library_id,
                name="媒体库",
                selected=True,
            )
        )
        session.commit()


def _cached_row(media_id: str = "a" * 24) -> models.MediaCacheIndex:
    return models.MediaCacheIndex(
        media_id=media_id,
        jellyfin_id=f"jf-{media_id}",
        library_id="lib-a",
        name="缓存媒体",
        media_type="video",
        fingerprint="fingerprint",
    )


def test_get_media_uses_cache_and_makes_zero_jellyfin_items_calls(data_root: Path) -> None:
    calls = {"items": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/Items"):
            calls["items"] += 1
        return httpx.Response(200, json={"Items": [], "TotalRecordCount": 0})

    app = _configured_app(data_root, handler)
    with TestClient(app) as client:
        _seed_selected(client)
        with client.app.state.database.session() as session:
            session.add(_cached_row())
            session.commit()

        response = client.get("/api/v1/media")

        assert response.status_code == 200
        data = response.json()["data"]
        assert data["total"] == 1
        assert data["items"][0]["name"] == "缓存媒体"
        assert data["sync"]["state"] == "idle"
        assert calls["items"] == 0


def test_empty_cache_returns_immediately_and_enqueues_once(data_root: Path) -> None:
    calls = {"items": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/Items"):
            calls["items"] += 1
        return httpx.Response(200, json={"Items": [], "TotalRecordCount": 0})

    app = _configured_app(data_root, handler)
    with TestClient(app) as client:
        _seed_selected(client)
        first = client.get("/api/v1/media")
        second = client.get("/api/v1/media")

        assert first.status_code == 200 and second.status_code == 200
        assert first.json()["data"]["items"] == []
        assert first.json()["data"]["sync"]["state"] in {"pending", "running"}
        assert second.json()["data"]["sync"]["task_id"] == first.json()["data"]["sync"]["task_id"]
        assert calls["items"] == 0
        with client.app.state.database.session() as session:
            tasks = session.query(models.BackgroundTask).filter_by(type="media_refresh").all()
            assert len(tasks) == 1


def test_repeated_refresh_including_force_returns_existing_task(data_root: Path) -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"Items": [], "TotalRecordCount": 0})

    app = _configured_app(data_root, handler)
    with TestClient(app) as client:
        _seed_selected(client)
        first = client.post(
            "/api/v1/media/refresh", json={"library_ids": ["lib-a"], "force": False}
        )
        second = client.post(
            "/api/v1/media/refresh", json={"library_ids": ["lib-a"], "force": True}
        )

        assert first.status_code == 202 and second.status_code == 202
        assert first.json()["data"]["task_id"] == second.json()["data"]["task_id"]


def test_refresh_rejects_unknown_or_unselected_library(data_root: Path) -> None:
    app = _configured_app(
        data_root,
        lambda _request: httpx.Response(200, json={"Items": [], "TotalRecordCount": 0}),
    )
    with TestClient(app) as client:
        _seed_selected(client)
        with client.app.state.database.session() as session:
            session.add(
                models.LibrarySelection(
                    jellyfin_id="lib-off",
                    name="未选媒体库",
                    selected=False,
                )
            )
            session.commit()

        unknown = client.post("/api/v1/media/refresh", json={"library_ids": ["missing"]})
        unselected = client.post("/api/v1/media/refresh", json={"library_ids": ["lib-off"]})
        assert unknown.status_code == 422
        assert unselected.status_code == 422


def test_task_list_get_and_cancel_are_idempotent_and_sanitized(data_root: Path) -> None:
    app = _configured_app(
        data_root,
        lambda _request: httpx.Response(200, json={"Items": [], "TotalRecordCount": 0}),
    )
    with TestClient(app) as client:
        with client.app.state.database.session() as session:
            session.add(
                models.BackgroundTask(
                    task_id="task-a",
                    type="media_refresh",
                    status="pending",
                    params='{"secret":"must-not-return"}',
                    error="raw traceback must-not-return",
                )
            )
            session.commit()

        listed = client.get("/api/v1/tasks")
        fetched = client.get("/api/v1/tasks/task-a")
        cancelled = client.post("/api/v1/tasks/task-a/cancel")
        cancelled_again = client.post("/api/v1/tasks/task-a/cancel")

        assert listed.status_code == 200
        assert fetched.status_code == 200
        assert cancelled.status_code == 200 and cancelled_again.status_code == 200
        assert cancelled.json()["data"]["status"] == "cancelled"
        serialized = str(listed.json()) + str(fetched.json())
        assert "secret" not in serialized
        assert "traceback" not in serialized


def test_task_endpoints_require_pairing_auth(data_root: Path) -> None:
    app = _configured_app(
        data_root,
        lambda _request: httpx.Response(200, json={"Items": [], "TotalRecordCount": 0}),
        pairing_required=True,
    )
    with TestClient(app) as client:
        assert client.get("/api/v1/tasks").status_code == 401
        assert client.get("/api/v1/tasks/missing").status_code == 401
        assert client.post("/api/v1/tasks/missing/cancel").status_code == 401
