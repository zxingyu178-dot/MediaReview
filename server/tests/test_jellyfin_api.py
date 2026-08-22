"""Jellyfin API 端点测试(依赖注入 mock,不访问真实 Jellyfin)。"""

from __future__ import annotations

from fastapi.testclient import TestClient
from httpx import MockTransport

from tests.conftest import JELLYFIN_USER_ID


def test_status(jellyfin_api_client: tuple[TestClient, MockTransport]) -> None:
    client, _ = jellyfin_api_client
    resp = client.get("/api/v1/jellyfin/status")
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    assert body["data"] == {
        "server_name": "HomeServer",
        "version": "10.9.11",
        "jellyfin_id": "srv-1",
    }


def test_users(jellyfin_api_client: tuple[TestClient, MockTransport]) -> None:
    client, _ = jellyfin_api_client
    resp = client.get("/api/v1/jellyfin/users")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert len(data) == 2
    assert data[0]["name"] == "jp"


def test_libraries_requires_user_id(jellyfin_api_client: tuple[TestClient, MockTransport]) -> None:
    client, _ = jellyfin_api_client
    resp = client.get("/api/v1/jellyfin/libraries")
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "VALIDATION_ERROR"


def test_libraries(jellyfin_api_client: tuple[TestClient, MockTransport]) -> None:
    client, _ = jellyfin_api_client
    resp = client.get("/api/v1/jellyfin/libraries", params={"user_id": JELLYFIN_USER_ID})
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert [lib["name"] for lib in data] == ["电影", "照片"]


def test_upstream_error_returns_502_envelope(app_config, monkeypatch) -> None:
    """上游连接失败时,端点应返回统一 502 包而不是 500。"""
    import httpx
    from pydantic import SecretStr

    from app.adapters.jellyfin.client import JellyfinClient
    from app.api.v1.jellyfin import jellyfin_client
    from app.main import create_app

    app_config.jellyfin.api_key = SecretStr("k")

    def raiser(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    transport = httpx.MockTransport(raiser)
    app = create_app(app_config)

    async def override():
        async with JellyfinClient(app_config.jellyfin, transport=transport) as c:
            yield c

    app.dependency_overrides[jellyfin_client] = override
    with TestClient(app) as c:
        resp = c.get("/api/v1/jellyfin/status")
    assert resp.status_code == 502
    body = resp.json()
    assert body["success"] is False
    assert body["error"]["code"] == "JELLYFIN_ERROR"
    assert body["error"]["message"] == "无法连接 Jellyfin 服务,请确认其已启动且地址正确"


def test_not_configured_returns_config_error(client: TestClient) -> None:
    """未配置 API Key 时应返回 CONFIG_ERROR,而不是去连 127.0.0.1。"""
    resp = client.get("/api/v1/jellyfin/status")
    assert resp.status_code == 500
    body = resp.json()
    assert body["error"]["code"] == "CONFIG_ERROR"
    assert "API Key" in body["error"]["message"]
