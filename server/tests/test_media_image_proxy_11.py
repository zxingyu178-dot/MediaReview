"""Task 2 安全修复：配对认证图片代理与 API Key 不泄露。"""

from __future__ import annotations

from collections.abc import AsyncIterator, Iterator

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.adapters.jellyfin.client import JellyfinClient
from app.api.v1.jellyfin import jellyfin_client
from app.core.config import AppConfig, SecurityConfig, StorageConfig
from app.db import models
from app.db.session import Database
from app.main import create_app

SERVER_KEY = "server-only-test-key"
MAX_IMAGE_BYTES = 25 * 1024 * 1024


@pytest.fixture
def secure_image_client(data_root) -> Iterator[tuple[TestClient, str, list[httpx.Request]]]:
    requests: list[httpx.Request] = []

    known_keys: dict[str, str] = {"mediareview-shared-playback": "device-jf-key-secure-1"}

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.method == "GET" and request.url.path.endswith("/Auth/Keys"):
            return httpx.Response(
                200,
                json={"Items": [{"Name": n, "AccessToken": v} for n, v in known_keys.items()]},
            )
        if request.method == "POST" and request.url.path.endswith("/Auth/Keys"):
            name = request.url.params.get("Name", "")
            known_keys.setdefault(name, f"device-jf-key-{name}")
            return httpx.Response(200)
        if request.url.host in {"evil.example", "169.254.169.254"}:
            return httpx.Response(
                200, content=b"redirected-image", headers={"Content-Type": "image/png"}
            )
        if request.url.path == "/redirect-target":
            return httpx.Response(
                200, content=b"same-origin-image", headers={"Content-Type": "image/png"}
            )
        item_id = request.url.path.split("/")[2]
        if item_id == "jf-redirect-cross":
            return httpx.Response(302, headers={"Location": "http://evil.example/image.png"})
        if item_id == "jf-redirect-link":
            return httpx.Response(
                302, headers={"Location": "http://169.254.169.254/latest/meta-data"}
            )
        if item_id == "jf-redirect-same":
            return httpx.Response(
                302, headers={"Location": "http://127.0.0.1:8096/redirect-target"}
            )
        if item_id == "jf-redirect-loop":
            return httpx.Response(302, headers={"Location": str(request.url)})
        if item_id == "jf-error":
            return httpx.Response(500, json={"error": "upstream-secret-body"})
        if item_id == "jf-text":
            return httpx.Response(
                200, content=b"not-an-image", headers={"Content-Type": "text/plain"}
            )
        if item_id == "jf-large":
            return httpx.Response(
                200,
                content=b"small-body",
                headers={
                    "Content-Type": "image/jpeg",
                    "Content-Length": str(MAX_IMAGE_BYTES + 1),
                },
            )
        if request.url.path.endswith("/Download"):
            return httpx.Response(200, content=b"png-bytes", headers={"Content-Type": "image/png"})
        if request.url.path.endswith("/Images/Primary"):
            return httpx.Response(
                200, content=b"jpeg-bytes", headers={"Content-Type": "image/jpeg"}
            )
        return httpx.Response(404, json={"error": "unexpected"})

    config = AppConfig(
        storage=StorageConfig(data_root=str(data_root)),
        security=SecurityConfig(pairing_required=True, pairing_code_remote_allowed=True),
    )
    config.jellyfin.api_key = SecretStr(SERVER_KEY)
    config.jellyfin.user_id = "user-a"
    transport = httpx.MockTransport(handler)
    app = create_app(config)

    async def override_client() -> AsyncIterator[JellyfinClient]:
        async with JellyfinClient(config.jellyfin, transport=transport) as client:
            yield client

    app.dependency_overrides[jellyfin_client] = override_client
    app.state.settings.jellyfin.client_host_allowlist = ["testserver"]
    with TestClient(app) as client:
        db: Database = app.state.database
        with db.session() as session:
            session.add(models.LibrarySelection(jellyfin_id="lib-a", name="图库", selected=True))
            session.add_all(
                [
                    models.MediaCacheIndex(
                        media_id=media_id,
                        jellyfin_id=jellyfin_id,
                        library_id="lib-a",
                        name=f"{media_id}.jpg" if media_type == "image" else f"{media_id}.mp4",
                        media_type=media_type,
                        fingerprint=f"fp-{media_id}",
                        is_available=True,
                    )
                    for media_id, jellyfin_id, media_type in (
                        ("img-ok", "jf-ok", "image"),
                        ("img-error", "jf-error", "image"),
                        ("img-text", "jf-text", "image"),
                        ("img-large", "jf-large", "image"),
                        ("img-redirect-cross", "jf-redirect-cross", "image"),
                        ("img-redirect-link", "jf-redirect-link", "image"),
                        ("img-redirect-same", "jf-redirect-same", "image"),
                        ("img-redirect-loop", "jf-redirect-loop", "image"),
                        ("video", "jf-video", "video"),
                    )
                ]
            )
            session.commit()
        code = client.post("/api/v1/pairing/code").json()["data"]["code"]
        token = client.post(
            "/api/v1/pairing/verify",
            json={"device_id": "phone-image", "code": code},
        ).json()["data"]["token"]
        yield client, token, requests


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def test_review_and_media_json_only_return_safe_relative_image_urls(secure_image_client) -> None:
    client, token, requests = secure_image_client
    headers = _auth(token)
    created = client.post(
        "/api/v1/review/sessions",
        json={"source": {"filter": {}, "sort": {"sort_by": "name"}}},
        headers=headers,
    ).json()["data"]
    queue = client.get(f"/api/v1/review/sessions/{created['session_id']}/queue", headers=headers)
    media_page = client.get("/api/v1/media", headers=headers)
    detail = client.get("/api/v1/media/img-ok", headers=headers)
    playback = client.get("/api/v1/media/video/playback", headers=headers)

    assert (
        queue.status_code
        == media_page.status_code
        == detail.status_code
        == playback.status_code
        == 200
    )
    for response in (queue, media_page, detail, playback):
        assert SERVER_KEY not in response.text
        assert SERVER_KEY not in "\n".join(
            f"{name}: {value}" for name, value in response.headers.items()
        )
    queue_media = [item["media"] for item in queue.json()["data"]["items"]]
    assert all(
        item["cover_url"] == f"/api/v1/media/{item['media_id']}/thumbnail" for item in queue_media
    )
    assert next(item for item in queue_media if item["media_id"] == "img-ok")["original_url"] == (
        "/api/v1/media/img-ok/original"
    )
    assert detail.json()["data"]["cover_url"] == "/api/v1/media/img-ok/thumbnail"
    assert detail.json()["data"]["original_url"] == "/api/v1/media/img-ok/original"
    playback_data = playback.json()["data"]
    assert playback_data["requires_jellyfin_auth"] is False
    assert "凭据" in playback_data["message"]
    assert "api_key" not in playback_data["stream_url"].casefold()
    device_token = playback_data["direct"]["headers"]["X-Emby-Token"]
    assert device_token.startswith("device-jf-key-")
    assert playback_data["fallback_hls"]["headers"] == {"X-Emby-Token": device_token}
    assert device_token not in playback_data["direct"]["url"]
    assert device_token not in playback_data["fallback_hls"]["url"]
    # 中间层仍然不得代理视频流:上游调用只允许凭据签发与元数据,不允许 /Videos
    assert all("/Videos/" not in r.url.path for r in requests)


def test_playback_final_serialization_rejects_key_from_client_builder(
    secure_image_client, monkeypatch
) -> None:
    client, token, requests = secure_image_client

    monkeypatch.setattr(
        JellyfinClient,
        "video_stream_url",
        lambda _self, _item_id, **_kwargs: f"http://jf.local:8096/base/{SERVER_KEY}/stream",
    )
    response = client.get("/api/v1/media/video/playback", headers=_auth(token))

    assert response.status_code == 500
    assert response.json()["error"]["code"] == "CONFIG_ERROR"
    assert SERVER_KEY not in response.text
    assert SERVER_KEY not in "\n".join(
        f"{name}: {value}" for name, value in response.headers.items()
    )
    # 泄漏检查必须发生在任何 /Videos 上游调用之前
    assert all("/Videos/" not in r.url.path for r in requests)


def test_image_proxy_requires_pairing_and_keeps_server_key_upstream(secure_image_client) -> None:
    client, token, requests = secure_image_client
    assert client.get("/api/v1/media/img-ok/thumbnail").status_code == 401
    assert requests == []

    thumbnail = client.get("/api/v1/media/img-ok/thumbnail", headers=_auth(token))
    original = client.get("/api/v1/media/img-ok/original", headers=_auth(token))
    assert thumbnail.status_code == 200 and thumbnail.content == b"jpeg-bytes"
    assert thumbnail.headers["content-type"].startswith("image/jpeg")
    assert thumbnail.headers["x-content-type-options"] == "nosniff"
    assert original.status_code == 200 and original.content == b"png-bytes"
    assert original.headers["content-type"].startswith("image/png")
    assert SERVER_KEY not in thumbnail.text and SERVER_KEY not in original.text
    assert len(requests) == 2
    assert all(SERVER_KEY in request.headers["Authorization"] for request in requests)
    assert all(SERVER_KEY not in str(request.url) for request in requests)


def test_image_proxy_validates_media_type_content_and_size(secure_image_client) -> None:
    client, token, _requests = secure_image_client
    headers = _auth(token)
    assert client.get("/api/v1/media/ghost/thumbnail", headers=headers).status_code == 404
    assert client.get("/api/v1/media/video/original", headers=headers).status_code == 422
    for media_id in ("img-error", "img-text", "img-large"):
        response = client.get(f"/api/v1/media/{media_id}/original", headers=headers)
        assert response.status_code == 502
        assert SERVER_KEY not in response.text
        assert "upstream-secret-body" not in response.text


@pytest.mark.parametrize(
    "media_id",
    (
        "img-redirect-cross",
        "img-redirect-link",
        "img-redirect-same",
        "img-redirect-loop",
    ),
)
def test_image_proxy_never_follows_upstream_redirects(secure_image_client, media_id: str) -> None:
    client, token, requests = secure_image_client
    before = len(requests)

    response = client.get(f"/api/v1/media/{media_id}/original", headers=_auth(token))

    assert response.status_code == 502
    assert len(requests) == before + 1
    assert SERVER_KEY not in response.text
    assert "evil.example" not in response.text
    assert "169.254.169.254" not in response.text
    assert "redirect-target" not in response.text
    assert "location" not in response.headers
