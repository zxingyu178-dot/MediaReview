"""Task C: Direct Play 与单次 HLS 回退的播放合同(安全/序列化)。

合同(GET /media/{id}/playback):
- ``direct`` / ``fallback_hls``: {"url", "headers"};headers 携带设备级
  ``X-Emby-Token``(由服务端按设备签发/复用的 Jellyfin 命名 key,可撤销),
  凭据只出现在 headers,绝不进入 URL;
- ``fallback_hls`` 是唯一一次回退(master.m3u8),Android 状态机不得二次回退;
- ``stream_url`` 为一版兼容字段,恒等于 ``direct.url``;
- ``resume_position_ms`` 来自 Jellyfin UserData,Jellyfin 不可用时为 0;
- 全部序列化输出不得包含服务端 API key;
- 非视频 400;凭据签发失败 fail-closed(中文错误,不下发任何直连地址)。
"""

from __future__ import annotations

from pathlib import Path

import httpx
from conftest import JELLYFIN_USER_ID
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.adapters.jellyfin.client import JellyfinClient
from app.api.v1.jellyfin import jellyfin_client
from app.api.v1.pairing import optional_jellyfin_client
from app.core.config import AppConfig, SecurityConfig, StorageConfig
from app.db import models
from app.main import create_app

SERVER_KEY = "test-server-key"
DEVICE_KEY = "device-jf-key-1"
CLIENT_BASE = "http://192.168.31.20:8096/jellyfin"


class _KeyStore:
    """模拟 Jellyfin /Auth/Keys:命名 key 的创建/读取/删除。"""

    def __init__(self) -> None:
        self.items: dict[str, str] = {}
        self.create_calls = 0
        self.delete_calls: list[str] = []
        self.posted_apps: list[str] = []
        self.fail = False

    def handler(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/Auth/Keys"):
            if self.fail:
                return httpx.Response(500, json={"error": "boom"})
            if request.method == "GET":
                return httpx.Response(
                    200,
                    json={
                        "Items": [{"AppName": n, "AccessToken": v} for n, v in self.items.items()]
                    },
                )
            if request.method == "POST":
                self.create_calls += 1
                name = request.url.params.get("app", "")
                self.posted_apps.append(name)
                self.items.setdefault(name, f"device-jf-key-{len(self.items) + 1}")
                return httpx.Response(200)
        if request.method == "DELETE" and "/Auth/Keys/" in path:
            token = path.rsplit("/", 1)[-1]
            self.delete_calls.append(token)
            if token in self.items.values():
                self.items = {n: v for n, v in self.items.items() if v != token}
                return httpx.Response(204)
            return httpx.Response(404)
        return httpx.Response(404, json={"error": "unexpected " + path})


def _settings(tmp_path: Path, client_url: str | None = None) -> AppConfig:
    jellyfin_kwargs: dict = {
        "url": "http://127.0.0.1:8096/jellyfin",
        "api_key": SecretStr(SERVER_KEY),
        "user_id": JELLYFIN_USER_ID,
    }
    if client_url:
        jellyfin_kwargs["client_url"] = client_url
    return AppConfig(
        jellyfin=__import__("app.core.config", fromlist=["JellyfinConfig"]).JellyfinConfig(
            **jellyfin_kwargs
        ),
        storage=StorageConfig(data_root=str(tmp_path / "data")),
        security=SecurityConfig(pairing_required=False, pairing_code_remote_allowed=True),
    )


def _make_app(tmp_path: Path, store: _KeyStore, client_url: str | None = None):
    settings = _settings(tmp_path, client_url)
    app = create_app(settings)

    async def override_client():
        async with JellyfinClient(
            settings.jellyfin, transport=httpx.MockTransport(store.handler)
        ) as jf:
            yield jf

    app.dependency_overrides[jellyfin_client] = override_client
    return TestClient(app, base_url="http://192.168.31.20:8766")


def _seed_video(client: TestClient, media_id: str = "pb-1", jellyfin_id: str = "video-1") -> None:
    with client.app.state.database.session() as session:
        session.add(
            models.MediaCacheIndex(
                media_id=media_id,
                jellyfin_id=jellyfin_id,
                library_id="library-1",
                name="One.mp4",
                media_type="video",
                fingerprint=f"fp-{media_id}",
                duration_ms=60_000,
                width=1920,
                height=1080,
                container="mp4",
            )
        )
        session.commit()


def test_playback_contract_delivers_device_credential(tmp_path: Path) -> None:
    store = _KeyStore()
    client = _make_app(tmp_path, store)
    with client as c:
        _seed_video(c)
        resp = c.get("/api/v1/media/pb-1/playback")

    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    expected_direct = f"{CLIENT_BASE}/Videos/video-1/stream?static=true"
    assert data["direct"]["url"] == expected_direct
    assert data["direct"]["headers"] == {"X-Emby-Token": DEVICE_KEY}
    assert data["stream_url"] == expected_direct, "legacy 字段必须等于 direct.url"
    assert data["requires_jellyfin_auth"] is False

    hls = data["fallback_hls"]
    assert "master.m3u8" in hls["url"]
    assert "MediaSourceId=video-1" in hls["url"]
    assert hls["url"].startswith(CLIENT_BASE)
    assert hls["headers"] == {"X-Emby-Token": DEVICE_KEY}

    assert data["resume_position_ms"] == 0
    assert data["duration_ms"] == 60_000
    assert data["width"] == 1920 and data["height"] == 1080 and data["container"] == "mp4"

    # 服务端 key 绝不出现在任何序列化输出;设备 key 只出现在 headers,不进 URL
    assert SERVER_KEY not in resp.text
    assert DEVICE_KEY not in data["direct"]["url"]
    assert DEVICE_KEY not in data["fallback_hls"]["url"]


def test_device_key_provisioning_uses_real_jellyfin_app_contract(tmp_path: Path) -> None:
    """锁定 C1:POST /Auth/Keys 必须携带 app 参数(真实 Jellyfin 契约,而非 Name)。

    mock 用 AppName 字段回读;若重构把契约改回 Name,POST 得到的 app 为空、
    回读匹配失败,此测试即失败(防止测试体系再次复刻错误字段)。
    """
    store = _KeyStore()
    client = _make_app(tmp_path, store)
    with client as c:
        _seed_video(c)
        resp = c.get("/api/v1/media/pb-1/playback")

    assert resp.status_code == 200, resp.text
    assert store.posted_apps == ["mediareview-shared-playback"]
    assert store.create_calls == 1
    # 设备 key 经回读(按 AppName)下发到 headers
    assert resp.json()["data"]["direct"]["headers"]["X-Emby-Token"] == "device-jf-key-1"


def test_playback_resume_position_from_jellyfin(tmp_path: Path) -> None:
    store = _KeyStore()
    settings = _settings(tmp_path)
    app = create_app(settings)

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith(f"/Users/{JELLYFIN_USER_ID}/Items/video-1"):
            return httpx.Response(200, json={"UserData": {"PlaybackPositionTicks": 900_000_000}})
        return store.handler(request)

    async def override_client():
        async with JellyfinClient(settings.jellyfin, transport=httpx.MockTransport(handler)) as jf:
            yield jf

    app.dependency_overrides[jellyfin_client] = override_client
    with TestClient(app, base_url="http://192.168.31.20:8766") as c:
        _seed_video(c)
        resp = c.get("/api/v1/media/pb-1/playback")
    assert resp.status_code == 200
    assert resp.json()["data"]["resume_position_ms"] == 90_000


def test_playback_resume_defaults_to_zero_when_jellyfin_item_missing(tmp_path: Path) -> None:
    store = _KeyStore()
    client = _make_app(tmp_path, store)
    with client as c:
        _seed_video(c)
        resp = c.get("/api/v1/media/pb-1/playback")
    assert resp.status_code == 200
    assert resp.json()["data"]["resume_position_ms"] == 0


def test_playback_rejects_non_video(tmp_path: Path) -> None:
    store = _KeyStore()
    client = _make_app(tmp_path, store)
    with client as c:
        with c.app.state.database.session() as session:
            session.add(
                models.MediaCacheIndex(
                    media_id="pic-1",
                    jellyfin_id="image-1",
                    library_id="library-1",
                    name="P.jpg",
                    media_type="image",
                    fingerprint="fp-pic-1",
                )
            )
            session.commit()
        resp = c.get("/api/v1/media/pic-1/playback")
    assert resp.status_code == 422
    assert "视频" in resp.json()["error"]["message"]


def test_playback_fails_closed_when_key_provisioning_fails(tmp_path: Path) -> None:
    store = _KeyStore()
    store.fail = True
    client = _make_app(tmp_path, store)
    with client as c:
        _seed_video(c)
        resp = c.get("/api/v1/media/pb-1/playback")
    assert resp.status_code >= 400
    body = resp.json()
    assert body["data"] is None or "direct" not in (body["data"] or {})
    assert SERVER_KEY not in resp.text
    assert "凭据" in body["error"]["message"]


def test_playback_reuses_provisioned_key_across_requests(tmp_path: Path) -> None:
    store = _KeyStore()
    client = _make_app(tmp_path, store)
    with client as c:
        _seed_video(c)
        first = c.get("/api/v1/media/pb-1/playback")
        second = c.get("/api/v1/media/pb-1/playback")
    assert first.status_code == second.status_code == 200
    assert second.json()["data"]["direct"]["headers"]["X-Emby-Token"] == DEVICE_KEY
    assert store.create_calls == 1, "同一命名 key 必须复用,不得重复创建"


def test_ensure_device_stream_key_is_idempotent_and_revocable(tmp_path: Path) -> None:
    store = _KeyStore()
    settings = _settings(tmp_path)

    async def scenario() -> tuple[str, str, int, bool, bool]:
        async with JellyfinClient(
            settings.jellyfin, transport=httpx.MockTransport(store.handler)
        ) as jf:
            first = await jf.ensure_device_stream_key("mediareview-dev-a")
            second = await jf.ensure_device_stream_key("mediareview-dev-a")
            revoked = await jf.revoke_device_stream_key("mediareview-dev-a")
            revoked_again = await jf.revoke_device_stream_key("mediareview-dev-a")
            return first, second, store.create_calls, revoked, revoked_again

    first, second, create_calls, revoked, revoked_again = __import__("asyncio").run(scenario())
    assert first == second == DEVICE_KEY
    assert create_calls == 1
    assert revoked is True and revoked_again is False
    assert store.delete_calls == [DEVICE_KEY]


def test_revoke_device_clears_jellyfin_key_and_revokes_upstream(tmp_path: Path) -> None:
    """I2:撤销设备时清除本地 jellyfin_key_* 列,并在 Jellyfin 侧尽力撤销命名 key。"""

    class _FakeClient:
        def __init__(self) -> None:
            self.revoked: list[str] = []

        async def revoke_device_stream_key(self, key_name: str) -> bool:
            self.revoked.append(key_name)
            return True

    settings = _settings(tmp_path)
    app = create_app(settings)
    fake = _FakeClient()

    async def override_optional_client():
        yield fake

    app.dependency_overrides[optional_jellyfin_client] = override_optional_client
    with TestClient(app) as c:
        with c.app.state.database.session() as session:
            session.add(
                models.PairedDevice(
                    device_id="d1",
                    installation_id="install-revoke",
                    name="旧手机",
                    jellyfin_key_name="mediareview-install-revoke",
                    jellyfin_key_value="device-jf-key-revoke-1",
                )
            )
            session.commit()
        resp = c.post("/api/v1/pairing/revoke", json={"device_id": "install-revoke"})
        assert resp.status_code == 200, resp.text
        assert resp.json()["data"]["revoked"] is True
        assert fake.revoked == ["mediareview-install-revoke"]

        with c.app.state.database.session() as session:
            row = (
                session.query(models.PairedDevice)
                .filter_by(installation_id="install-revoke")
                .first()
            )
        assert row is not None
        assert row.revoked is True
        assert row.token_hash is None
        assert row.jellyfin_key_value is None
        assert row.jellyfin_key_name is None
        assert row.jellyfin_key_created_at is None


def test_migration_0013_preserves_devices_and_rolls_back(tmp_path: Path) -> None:
    """0013 给 paired_device 增加设备级 Jellyfin key 列;升级/回滚不丢设备行。"""
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, inspect, text

    database_path = tmp_path / "pre-0013.db"
    database_url = f"sqlite:///{database_path}"
    root = Path(__file__).resolve().parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "app" / "db" / "migrations"))
    config.set_main_option("sqlalchemy.url", database_url)
    command.upgrade(config, "0012")

    engine = create_engine(database_url)
    with engine.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO paired_device (device_id, installation_id, name) "
                "VALUES ('dev-1', 'install-1', '手机')"
            )
        )
    engine.dispose()

    command.upgrade(config, "0013")
    engine = create_engine(database_url)
    columns = {c["name"] for c in inspect(engine).get_columns("paired_device")}
    assert {"jellyfin_key_name", "jellyfin_key_value", "jellyfin_key_created_at"} <= columns
    with engine.begin() as connection:
        row = connection.execute(
            text("SELECT name FROM paired_device WHERE device_id = 'dev-1'")
        ).scalar()
    assert row == "手机"
    engine.dispose()

    command.downgrade(config, "0012")
    engine = create_engine(database_url)
    columns = {c["name"] for c in inspect(engine).get_columns("paired_device")}
    assert "jellyfin_key_value" not in columns
    with engine.begin() as connection:
        row = connection.execute(
            text("SELECT name FROM paired_device WHERE device_id = 'dev-1'")
        ).scalar()
    assert row == "手机"
    engine.dispose()
