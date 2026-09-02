"""JellyfinClient 测试: 全部走 MockTransport,不访问真实服务。"""

from __future__ import annotations

import httpx
import pytest
from pydantic import SecretStr

from app.adapters.jellyfin.client import (
    JellyfinAuthError,
    JellyfinClient,
    JellyfinError,
    item_stream_url,
)
from app.core.config import JellyfinConfig
from tests.conftest import JELLYFIN_USER_ID, make_jellyfin_mock_transport


@pytest.fixture
def client(jellyfin_config: JellyfinConfig) -> JellyfinClient:
    return JellyfinClient(jellyfin_config, transport=make_jellyfin_mock_transport())


@pytest.mark.anyio
async def test_system_info(client: JellyfinClient) -> None:
    info = await client.system_info()
    assert info.server_name == "HomeServer"
    assert info.version == "10.9.11"


@pytest.mark.anyio
async def test_users(client: JellyfinClient) -> None:
    users = await client.users()
    assert [u.name for u in users] == ["jp", "guest"]


@pytest.mark.anyio
async def test_libraries(client: JellyfinClient) -> None:
    libraries = await client.libraries(JELLYFIN_USER_ID)
    assert len(libraries) == 2
    assert libraries[0].name == "电影"
    assert libraries[1].collection_type == "homevideos"


@pytest.mark.anyio
async def test_media_page_maps_to_unified_dto(client: JellyfinClient) -> None:
    items, total = await client.media_page(JELLYFIN_USER_ID, parent_id="lib-movies")
    assert total == 5
    video = items[0]
    assert video.media_type == "video"
    assert video.duration_ms == 182
    assert video.library_id == "lib-movies"

    photo_items, photo_total = await client.media_page(JELLYFIN_USER_ID, parent_id="lib-photos")
    assert photo_total == 2
    assert all(i.media_type == "image" for i in photo_items)
    assert all(i.library_id == "lib-photos" for i in photo_items)


@pytest.mark.anyio
async def test_media_item_single(client: JellyfinClient) -> None:
    media = await client.media_item(JELLYFIN_USER_ID, "it-video")
    assert media.jellyfin_id == "it-video"
    assert media.media_type == "video"


@pytest.mark.anyio
async def test_auth_header_sent(jellyfin_config: JellyfinConfig) -> None:
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["authorization"] = request.headers.get("Authorization", "")
        return httpx.Response(200, json={})

    jf = JellyfinClient(jellyfin_config, transport=httpx.MockTransport(handler))
    await jf.system_info()
    await jf.close()
    assert 'Token="test-api-key"' in seen["authorization"]
    assert 'Client="MediaReview"' in seen["authorization"]


@pytest.mark.anyio
async def test_client_ignores_env_proxy_trust_env_false(
    jellyfin_config: JellyfinConfig, monkeypatch: pytest.MonkeyPatch
) -> None:
    """部署机存在 SOCKS/HTTP 代理环境变量时,client 不得采信(trust_env=False)。

    回归: 机器上 all_proxy=socks5://… 时,httpx 缺 socksio 扩展会在构造期抛
    ImportError,导致 /libraries 等一切 Jellyfin 调用 500。V1 仅局域网直连,
    不应被环境代理劫持。
    """
    monkeypatch.setenv("all_proxy", "socks5://127.0.0.1:33210")
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:33210")

    jf = JellyfinClient(
        jellyfin_config,
        transport=httpx.MockTransport(lambda req: httpx.Response(200, json={})),
    )
    try:
        # 构造成功即证明未被环境代理劫持(缺 socksio 时旧代码抛 ImportError)
        assert jf._http.trust_env is False
    finally:
        await jf.close()


@pytest.mark.anyio
async def test_401_raises_auth_error(jellyfin_config: JellyfinConfig) -> None:
    jf = JellyfinClient(
        jellyfin_config,
        transport=httpx.MockTransport(lambda req: httpx.Response(401, json={})),
    )
    with pytest.raises(JellyfinAuthError):
        await jf.system_info()
    await jf.close()


@pytest.mark.anyio
async def test_connection_error_mapped(jellyfin_config: JellyfinConfig) -> None:
    def raiser(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    jf = JellyfinClient(jellyfin_config, transport=httpx.MockTransport(raiser))
    with pytest.raises(JellyfinError, match="无法连接"):
        await jf.system_info()
    await jf.close()


@pytest.mark.anyio
async def test_report_progress_payload(jellyfin_config: JellyfinConfig) -> None:
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["path"] = request.url.path
        captured["json"] = request.read()
        return httpx.Response(204)

    jf = JellyfinClient(jellyfin_config, transport=httpx.MockTransport(handler))
    await jf.report_progress(
        user_id=JELLYFIN_USER_ID, item_id="it-video", position_ms=1824, is_paused=False
    )
    await jf.close()
    import json

    assert captured["path"] == "/Sessions/Playing/Progress"
    body = json.loads(captured["json"])  # type: ignore[arg-type]
    assert body["ItemId"] == "it-video"
    assert body["PositionTicks"] == 18_240_000
    assert body["IsPaused"] is False


def test_direct_video_url_never_contains_server_key(jellyfin_config: JellyfinConfig) -> None:
    jf = JellyfinClient(jellyfin_config, transport=make_jellyfin_mock_transport())
    stream = jf.video_stream_url("it-video")
    assert stream.startswith("http://127.0.0.1:8096/Videos/it-video/stream")
    assert "static=true" in stream
    assert "test-api-key" not in stream
    assert "api_key" not in stream.casefold()


def test_direct_video_url_normalizes_base_path_and_encodes_item_id() -> None:
    stream = item_stream_url(
        "HTTPS://JF.Example:9443/jellyfin%20home/",
        "folder/video ?#%",
    )

    assert stream == (
        "https://jf.example:9443/jellyfin%20home/"
        "Videos/folder%2Fvideo%20%3F%23%25/stream?static=true"
    )


def test_client_rejects_bypassed_config_when_url_contains_server_key() -> None:
    bypassed = JellyfinConfig.model_construct(
        url="http://jf.local:8096/base/server-only-config-test-key",
        api_key=SecretStr("server-only-config-test-key"),
        user_id="",
    )

    with pytest.raises(ValueError) as caught:
        JellyfinClient(bypassed, transport=make_jellyfin_mock_transport())

    assert "server-only-config-test-key" not in str(caught.value)


def test_client_stream_builder_rechecks_mutated_base_url(jellyfin_config: JellyfinConfig) -> None:
    client = JellyfinClient(jellyfin_config, transport=make_jellyfin_mock_transport())
    client._base_url = "http://jf.local:8096/base/test-api-key"

    with pytest.raises(ValueError) as caught:
        client.video_stream_url("it-video")

    assert "test-api-key" not in str(caught.value)


def test_unconfigured_key_rejected() -> None:
    config = JellyfinConfig(url="http://127.0.0.1:8096", api_key=SecretStr(""))
    assert not config.is_configured()
