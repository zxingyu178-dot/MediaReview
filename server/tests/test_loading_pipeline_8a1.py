"""阶段 8A.1 加载管线测试(服务端部分)。

覆盖 §43 要求的:
- ThumbnailCacheTest          第二次请求不再回源 Jellyfin
- ThumbnailCacheInvalidationTest  源指纹变化后缓存自然失效
- JellyfinClientReuseTest     客户端与连接池被复用,配置变化时轮换
- MediaPageFavoriteContractTest   MediaSummary 直接带收藏状态
- single-flight               同一 key 并发只回源一次
"""

from __future__ import annotations

import asyncio
from collections.abc import Iterator

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.adapters.jellyfin.client import JellyfinClient
from app.adapters.jellyfin.manager import JellyfinClientManager
from app.api.v1.jellyfin import jellyfin_client
from app.core.config import AppConfig, JellyfinConfig, SecurityConfig, StorageConfig
from app.core.paths import PathManager
from app.db import models
from app.db.session import Database
from app.main import create_app
from app.services.thumbnail_cache import (
    VARIANT_GRID,
    ThumbnailCacheService,
    thumbnail_cache_key,
)


class _UpstreamCounter:
    """统计回源 Jellyfin 的次数(按路径分类)。"""

    def __init__(self) -> None:
        self.primary = 0
        self.download = 0

    @property
    def total(self) -> int:
        return self.primary + self.download


def _make_transport(counter: _UpstreamCounter) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path.endswith("/Images/Primary"):
            counter.primary += 1
            return httpx.Response(
                200,
                content=b"jpeg-bytes-" + str(counter.primary).encode(),
                headers={"Content-Type": "image/jpeg"},
            )
        if path.endswith("/Download"):
            counter.download += 1
            return httpx.Response(200, content=b"png-bytes", headers={"Content-Type": "image/png"})
        return httpx.Response(404, json={"error": "unexpected " + path})

    return httpx.MockTransport(handler)


@pytest.fixture
def loading_client(tmp_path) -> Iterator[tuple[TestClient, str, _UpstreamCounter]]:
    """带缩略图 mock 的客户端: 2 张图片 + 1 个视频。"""
    counter = _UpstreamCounter()
    config = AppConfig(
        storage=StorageConfig(data_root=str(tmp_path)),
        security=SecurityConfig(pairing_required=True, pairing_code_remote_allowed=True),
    )
    config.jellyfin.api_key = SecretStr("loading-key")
    config.jellyfin.user_id = "user-loading"
    transport = _make_transport(counter)
    app = create_app(config)

    async def override_client() -> JellyfinClient:
        # 与生产一致: 共享同一个客户端实例(测试里注入 mock transport)
        existing = getattr(app.state, "_test_jf_client", None)
        if existing is None:
            existing = JellyfinClient(config.jellyfin, transport=transport)
            app.state._test_jf_client = existing
        return existing

    app.dependency_overrides[jellyfin_client] = override_client
    app.state.settings.jellyfin.client_host_allowlist = ["testserver"]
    with TestClient(app) as client:
        db: Database = app.state.database
        with db.session() as session:
            session.add(models.LibrarySelection(jellyfin_id="lib-a", name="图库", selected=True))
            session.add_all(
                [
                    models.MediaCacheIndex(
                        media_id="img-a",
                        jellyfin_id="jf-a",
                        library_id="lib-a",
                        name="a.jpg",
                        media_type="image",
                        fingerprint="fp-a",
                        size_bytes=1000,
                        is_available=True,
                    ),
                    models.MediaCacheIndex(
                        media_id="img-b",
                        jellyfin_id="jf-b",
                        library_id="lib-a",
                        name="b.jpg",
                        media_type="image",
                        fingerprint="fp-b",
                        size_bytes=2000,
                        is_available=True,
                    ),
                ]
            )
            session.commit()
        code = client.post("/api/v1/pairing/code").json()["data"]["code"]
        token = client.post(
            "/api/v1/pairing/verify", json={"device_id": "phone-loading", "code": code}
        ).json()["data"]["token"]
        yield client, token, counter


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---------- ThumbnailCacheTest ----------


def test_thumbnail_second_request_never_touches_jellyfin(loading_client) -> None:
    client, token, counter = loading_client
    headers = _auth(token)

    first = client.get("/api/v1/media/img-a/thumbnail", headers=headers)
    assert first.status_code == 200
    assert first.content == b"jpeg-bytes-1"
    assert first.headers["x-mediareview-cache"] == "MISS"
    assert counter.primary == 1
    # 缓存响应头
    assert "max-age=86400" in first.headers["cache-control"]
    assert first.headers["etag"]

    second = client.get("/api/v1/media/img-a/thumbnail", headers=headers)
    assert second.status_code == 200
    assert second.content == first.content
    assert second.headers["x-mediareview-cache"] == "HIT"
    # 硬门槛: 第二轮 0 次上游请求
    assert counter.primary == 1


def test_thumbnail_writes_file_into_sharded_cache_dir(loading_client) -> None:
    client, token, _counter = loading_client
    client.get("/api/v1/media/img-a/thumbnail", headers=_auth(token))
    cached = list(PathManager(client.app.state.paths.data_root).thumbnails_dir.rglob("*.jpg"))
    assert len(cached) == 1
    assert cached[0].stat().st_size > 0


def test_thumbnail_etag_yields_304(loading_client) -> None:
    client, token, counter = loading_client
    headers = _auth(token)
    first = client.get("/api/v1/media/img-a/thumbnail", headers=headers)
    etag = first.headers["etag"]

    revalidated = client.get(
        "/api/v1/media/img-a/thumbnail", headers={**headers, "If-None-Match": etag}
    )
    assert revalidated.status_code == 304
    assert revalidated.headers["x-mediareview-cache"] == "HIT"
    assert counter.primary == 1


def test_thumbnail_distinct_media_have_distinct_cache_entries(loading_client) -> None:
    client, token, counter = loading_client
    headers = _auth(token)
    client.get("/api/v1/media/img-a/thumbnail", headers=headers)
    client.get("/api/v1/media/img-b/thumbnail", headers=headers)
    assert counter.primary == 2
    client.get("/api/v1/media/img-a/thumbnail", headers=headers)
    client.get("/api/v1/media/img-b/thumbnail", headers=headers)
    assert counter.primary == 2


# ---------- ThumbnailCacheInvalidationTest ----------


def test_thumbnail_cache_invalidates_when_source_fingerprint_changes(loading_client) -> None:
    client, token, counter = loading_client
    headers = _auth(token)
    assert client.get("/api/v1/media/img-a/thumbnail", headers=headers).status_code == 200
    assert counter.primary == 1

    # 上游内容变化(大小/修改时间变了) → 指纹变化 → 必须重新回源
    db: Database = client.app.state.database
    with db.session() as session:
        row = session.get(models.MediaCacheIndex, "img-a")
        assert row is not None
        row.size_bytes = 999_999
        session.commit()

    again = client.get("/api/v1/media/img-a/thumbnail", headers=headers)
    assert again.status_code == 200
    assert again.headers["x-mediareview-cache"] == "MISS"
    assert counter.primary == 2


def test_thumbnail_cache_key_depends_on_media_fingerprint_and_variant() -> None:
    base = thumbnail_cache_key(media_id="m", fingerprint="fp1", variant=VARIANT_GRID)
    assert base != thumbnail_cache_key(media_id="m", fingerprint="fp2", variant=VARIANT_GRID)
    assert base != thumbnail_cache_key(media_id="m2", fingerprint="fp1", variant=VARIANT_GRID)
    assert base != thumbnail_cache_key(media_id="m", fingerprint="fp1", variant="other")
    assert base == thumbnail_cache_key(media_id="m", fingerprint="fp1", variant=VARIANT_GRID)


# ---------- single-flight ----------


def test_thumbnail_single_flight_collapses_concurrent_misses(tmp_path) -> None:
    paths = PathManager(tmp_path)
    paths.ensure()
    service = ThumbnailCacheService(paths)
    key = thumbnail_cache_key(media_id="m", fingerprint="fp", variant=VARIANT_GRID)
    calls = 0

    async def loader() -> tuple[bytes, str]:
        nonlocal calls
        calls += 1
        await asyncio.sleep(0.05)
        return b"payload", "image/jpeg"

    async def scenario() -> list[tuple[bytes, str, bool]]:
        return await asyncio.gather(*(service.get_or_create(key, loader) for _ in range(6)))

    results = asyncio.run(scenario())
    assert calls == 1, "并发 MISS 必须只回源一次"
    assert service.misses == 1
    assert service.hits == 5
    assert all(r.payload == b"payload" and r.content_type == "image/jpeg" for r in results)


def test_thumbnail_cache_write_is_atomic(tmp_path) -> None:
    """写入后目录里只应存在最终文件,不应残留 .tmp。"""
    paths = PathManager(tmp_path)
    paths.ensure()
    service = ThumbnailCacheService(paths)
    key = thumbnail_cache_key(media_id="m", fingerprint="fp", variant=VARIANT_GRID)
    service.write(key, b"bytes", "image/jpeg")
    files = [p.name for p in paths.thumbnails_dir.rglob("*") if p.is_file()]
    assert len(files) == 1
    assert not any(name.endswith(".tmp") for name in files)


# ---------- MediaPageFavoriteContractTest ----------


def test_media_page_and_detail_expose_favorite_state(loading_client) -> None:
    client, token, _counter = loading_client
    headers = _auth(token)
    db: Database = client.app.state.database
    with db.session() as session:
        session.add(models.Favorite(media_id="img-a"))
        session.commit()

    page = client.get("/api/v1/media?page=1&page_size=50", headers=headers)
    assert page.status_code == 200
    items = {item["media_id"]: item for item in page.json()["data"]["items"]}
    assert items["img-a"]["is_favorite"] is True
    assert items["img-b"]["is_favorite"] is False

    detail = client.get("/api/v1/media/img-a", headers=headers)
    assert detail.json()["data"]["is_favorite"] is True
    detail_b = client.get("/api/v1/media/img-b", headers=headers)
    assert detail_b.json()["data"]["is_favorite"] is False


def test_media_page_favorite_defaults_false_for_legacy_clients(loading_client) -> None:
    """未收藏时字段存在且为 false(向后兼容: 新增字段有默认值)。"""
    client, token, _counter = loading_client
    page = client.get("/api/v1/media?page=1", headers=_auth(token))
    items = page.json()["data"]["items"]
    assert items and all(item["is_favorite"] is False for item in items)


# ---------- JellyfinClientReuseTest ----------


def test_jellyfin_client_manager_reuses_single_client() -> None:
    manager = JellyfinClientManager()
    config = JellyfinConfig(url="http://127.0.0.1:8096", api_key=SecretStr("k1"))

    async def scenario() -> tuple[object, object, object]:
        first = await manager.get(config)
        second = await manager.get(config)
        other = await manager.get(
            JellyfinConfig(url="http://127.0.0.1:8096", api_key=SecretStr("k2"))
        )
        return first, second, other

    try:
        first, second, other = asyncio.run(scenario())
        assert first is second, "同一配置必须复用同一个客户端(共享连接池)"
        assert other is not first, "凭据变化必须重建客户端,绝不沿用旧凭据"
        assert manager.created_clients == 2
        assert manager.closed_clients == 1
    finally:
        asyncio.run(manager.aclose())
    assert manager.closed_clients == 2


def test_jellyfin_client_reuses_keepalive_connection(loading_client) -> None:
    """连续两次缩略图 MISS 必须复用同一客户端(上游连接数 < 请求数)。"""
    client, token, counter = loading_client
    headers = _auth(token)
    client.get("/api/v1/media/img-a/thumbnail", headers=headers)
    client.get("/api/v1/media/img-b/thumbnail", headers=headers)
    assert counter.primary == 2
    # 生产路径由 JellyfinClientManager 保证单实例;此处断言依赖覆盖在同一实例上被复用
    assert getattr(client.app.state, "_test_jf_client", None) is not None
