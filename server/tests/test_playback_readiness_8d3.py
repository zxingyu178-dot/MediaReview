"""Stage 8D.3: 恢复设备凭据、有限 Range 及安全诊断。"""

import asyncio
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from test_playback_contract_11 import SERVER_KEY, _KeyStore, _make_app, _seed_video, _settings

from app.adapters.jellyfin.client import JellyfinClient
from app.api.v1.auth import require_auth
from app.api.v1.media import _resolve_stream_key
from app.db.models import PairedDevice
from app.main import create_app


@pytest.mark.parametrize("upstream", [None, "rotated-device-key"])
def test_cached_device_key_is_reconciled(tmp_path: Path, upstream: str | None) -> None:
    store = _KeyStore()
    if upstream:
        store.items["mediareview-install-a"] = upstream
    app = create_app(_settings(tmp_path))
    with TestClient(app) as c, c.app.state.database.session() as db:
        device = PairedDevice(
            device_id="device-a",
            installation_id="install-a",
            name="测试设备",
            token_hash="test-hash",
            jellyfin_key_name="mediareview-install-a",
            jellyfin_key_value="stale-device-key",
        )
        db.add(device)
        db.commit()

        async def run():
            async with JellyfinClient(
                _settings(tmp_path).jellyfin, transport=httpx.MockTransport(store.handler)
            ) as jf:
                return await _resolve_stream_key(db, device, jf)

        key = asyncio.run(run())
        assert key != "stale-device-key"
        assert key == store.items["mediareview-install-a"]
        assert device.jellyfin_key_value == key
        assert store.create_calls == (0 if upstream else 1)
        assert asyncio.run(run()) == key
        assert store.create_calls == (0 if upstream else 1)


def test_cached_key_does_not_bypass_upstream_failure(tmp_path: Path) -> None:
    store = _KeyStore()
    store.fail = True
    c = _make_app(tmp_path, store)
    c.app.dependency_overrides[require_auth] = lambda: PairedDevice(
        device_id="a", name="测试", installation_id="install-a", jellyfin_key_value="old-key"
    )
    with c:
        _seed_video(c)
        response = c.get("/api/v1/media/pb-1/playback")
    assert response.status_code != 200
    assert "old-key" not in response.text
    assert SERVER_KEY not in response.text


@pytest.mark.parametrize(
    "status,expected",
    [
        (200, "readable"),
        (206, "readable"),
        (401, "auth_failed"),
        (403, "forbidden"),
        (404, "not_found"),
        (500, "server_error"),
        (302, "redirect"),
    ],
)
def test_direct_probe_uses_only_device_header_and_bounded_range(
    tmp_path: Path, status: int, expected: str
):
    def handler(request: httpx.Request):
        assert request.headers["X-Emby-Token"] == "test-device-token"
        assert "Authorization" not in request.headers
        assert request.headers["Range"] == "bytes=0-1023"
        assert request.url.path == "/jellyfin/Videos/video-1/stream"
        return httpx.Response(
            status,
            headers={"Content-Type": "video/mp4", "Content-Range": "bytes 0-1023/4096"},
            content=b"a" * (1024 if status == 206 else 4096),
        )

    async def run():
        async with JellyfinClient(
            _settings(tmp_path).jellyfin, transport=httpx.MockTransport(handler)
        ) as jf:
            return await jf.direct_stream_probe("video-1", "test-device-token")

    result = asyncio.run(run())
    assert result["http_status"] == status
    assert result["bytes_read"] <= 1024
    assert result["status"] == expected
    assert "test-device-token" not in str(result)


@pytest.mark.parametrize("has_user_id", [True, False])
def test_readiness_reports_fallback_and_no_credentials(tmp_path: Path, has_user_id):
    store = _KeyStore()
    original = store.handler

    def handler(request: httpx.Request):
        if request.url.path.endswith("/System/Info"):
            return httpx.Response(
                200, json={"Id": "jf", "ServerName": "fixture", "Version": "test"}
            )
        if "/Users/" in request.url.path and "/Items/" in request.url.path:
            return httpx.Response(200, json={"Id": "video-1", "Type": "Video"})
        if request.url.path.endswith("/stream"):
            return httpx.Response(
                206,
                headers={"Content-Type": "video/mp4", "Content-Range": "bytes 0-3/4"},
                content=b"test",
            )
        return original(request)

    store.handler = handler
    with _make_app(tmp_path, store) as c:
        if not has_user_id:
            c.app.state.settings.jellyfin.user_id = ""
        _seed_video(c)
        response = c.get("/api/v1/system/playback-readiness")
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["jellyfin_server_reachable"] is True
    assert data["client_stream_base"] == "http://192.168.31.20:8096/jellyfin"
    assert data["client_stream_source"] == "request_host_fallback"
    assert data["client_stream_authoritative"] is False
    assert data["device_playback_key_status"] == "valid"
    assert data["media_valid"] is True
    assert data["direct_stream"]["status"] == "readable"
    assert data["phone_reachability"] == "not_tested"
    assert SERVER_KEY not in response.text
    assert "device-jf-key-1" not in response.text


def test_readiness_requires_auth_even_on_loopback(tmp_path: Path):
    settings = _settings(tmp_path)
    settings.security.pairing_required = True
    with TestClient(create_app(settings)) as c:
        assert c.get("/api/v1/system/playback-readiness").status_code == 401


@pytest.mark.parametrize(
    "content_type,content_range,body",
    [
        ("video/private-device-token", "bytes 0-3/4", b"test"),
        ("video/mp4", "bytes 0-9999/1", b"x"),
        ("video/mp4", "bytes 0-3/4", b"x"),
        ("video/mp4", "bytes 0-1024/2048", b"x" * 1025),
    ],
)
def test_probe_rejects_secret_reflection_and_invalid_range(
    tmp_path, content_type, content_range, body
):
    async def run():
        async with JellyfinClient(
            _settings(tmp_path).jellyfin,
            transport=httpx.MockTransport(
                lambda _: httpx.Response(
                    206,
                    headers={"Content-Type": content_type, "Content-Range": content_range},
                    content=body,
                )
            ),
        ) as jf:
            return await jf.direct_stream_probe("video-1", "private-device-token")

    result = asyncio.run(run())
    assert result["status"] == "invalid_stream"
    assert "private-device-token" not in str(result)


@pytest.mark.parametrize("source_id", ["source-different-from-item", None])
def test_hls_uses_confirmed_media_source_or_is_omitted(tmp_path: Path, source_id):
    store = _KeyStore()
    original = store.handler

    def handler(request: httpx.Request):
        if request.url.path.endswith("/PlaybackInfo"):
            return httpx.Response(
                200, json={"MediaSources": [{"Id": source_id}] if source_id else []}
            )
        return original(request)

    store.handler = handler
    with _make_app(tmp_path, store) as c:
        _seed_video(c)
        response = c.get("/api/v1/media/pb-1/playback")
    assert response.status_code == 200
    endpoint = response.json()["data"]["fallback_hls"]
    if source_id:
        assert f"MediaSourceId={source_id}" in endpoint["url"]
        assert "MediaSourceId=video-1" not in endpoint["url"]
    else:
        assert endpoint is None
