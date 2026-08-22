"""request_id 与统一错误结构测试。"""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.config import AppConfig
from app.core.errors import MediaNotFoundError
from app.main import create_app


def test_request_id_generated_when_absent(client: TestClient) -> None:
    resp = client.get("/api/v1/system/health")
    assert len(resp.headers["X-Request-ID"]) == 16


def test_incoming_request_id_reused(client: TestClient) -> None:
    resp = client.get("/api/v1/system/health", headers={"X-Request-ID": "client-abc-123"})
    assert resp.headers["X-Request-ID"] == "client-abc-123"
    assert resp.json()["request_id"] == "client-abc-123"


def test_malformed_request_id_replaced(client: TestClient) -> None:
    resp = client.get("/api/v1/system/health", headers={"X-Request-ID": "bad id; junk!"})
    assert resp.headers["X-Request-ID"] != "bad id; junk!"
    assert len(resp.headers["X-Request-ID"]) == 16


def test_unknown_route_returns_envelope_404(client: TestClient) -> None:
    resp = client.get("/api/v1/no-such-route")
    assert resp.status_code == 404
    body = resp.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "NOT_FOUND"
    assert body["error"]["message"] == "资源不存在"
    assert body["request_id"]


def test_method_not_allowed_returns_envelope(client: TestClient) -> None:
    resp = client.delete("/api/v1/system/health")
    assert resp.status_code == 405
    body = resp.json()
    assert body["success"] is False
    assert body["error"]["code"] == "METHOD_NOT_ALLOWED"


def test_app_error_returns_envelope(app_config: AppConfig) -> None:
    app: FastAPI = create_app(app_config)

    @app.get("/api/v1/__boom")
    def boom() -> None:
        raise MediaNotFoundError(details={"media_id": "m-1"})

    with TestClient(app) as c:
        resp = c.get("/api/v1/__boom")
    assert resp.status_code == 404
    body = resp.json()
    assert body["success"] is False
    assert body["error"]["code"] == "MEDIA_NOT_FOUND"
    assert body["error"]["message"] == "媒体不存在"
    assert body["error"]["details"] == {"media_id": "m-1"}
