"""阶段 8A.1.1 加载管线收口测试(服务端)。

覆盖：
- Grid 缩略图几何: 16:9 / 9:16 / 4:3 / 1:1 四种媒体都必须由 Jellyfin
  以 fillWidth/fillHeight 生成 480x270 的 16:9 grid cover，
  不再走 maxWidth/maxHeight 等比缩放（竖图会只剩约 152x270 再被客户端放大）。
- cover_url 的 source_version：指纹不变 → 版本不变；指纹变化 → 版本变化；
  版本串不含任何敏感信息。
- 缓存统计与淘汰：stats() 能给出文件数与字节数；prune() 能按 mtime 淘汰到上限。
- 耗时字段：MISS 必须带 upstream_ms 与 disk_write_ms；HIT 必须带 disk_read_ms。
"""

from __future__ import annotations

import os
import time
from collections.abc import Iterator

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.adapters.jellyfin.client import JellyfinClient
from app.api.v1.jellyfin import jellyfin_client
from app.core.config import AppConfig, SecurityConfig, StorageConfig
from app.core.paths import PathManager
from app.db import models
from app.db.session import Database
from app.main import create_app
from app.services.thumbnail_cache import (
    DEFAULT_MAX_BYTES,
    ThumbnailCacheService,
    media_source_fingerprint,
    source_version,
    thumbnail_cache_key,
)

# (media_id, width, height) —— 覆盖四种典型宽高比
_ASPECTS = (
    ("m-16-9", 1920, 1080),
    ("m-9-16", 1080, 1920),
    ("m-4-3", 1600, 1200),
    ("m-1-1", 1200, 1200),
)


class _Upstream:
    def __init__(self) -> None:
        self.params: list[dict[str, str]] = []
        self.calls = 0


@pytest.fixture
def closure_client(tmp_path) -> Iterator[tuple[TestClient, str, _Upstream]]:
    upstream = _Upstream()

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/Images/Primary"):
            upstream.calls += 1
            upstream.params.append(dict(request.url.params))
            return httpx.Response(
                200, content=b"jpeg-bytes", headers={"Content-Type": "image/jpeg"}
            )
        return httpx.Response(404, json={"error": "unexpected " + request.url.path})

    config = AppConfig(
        storage=StorageConfig(data_root=str(tmp_path)),
        security=SecurityConfig(pairing_required=True, pairing_code_remote_allowed=True),
    )
    config.jellyfin.api_key = SecretStr("closure-key")
    config.jellyfin.user_id = "user-closure"
    transport = httpx.MockTransport(handler)
    app = create_app(config)

    async def override_client() -> JellyfinClient:
        existing = getattr(app.state, "_closure_jf", None)
        if existing is None:
            existing = JellyfinClient(config.jellyfin, transport=transport)
            app.state._closure_jf = existing
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
                        media_id=media_id,
                        jellyfin_id="jf-" + media_id,
                        library_id="lib-a",
                        name=media_id + ".jpg",
                        media_type="image",
                        fingerprint="fp-" + media_id,
                        width=width,
                        height=height,
                        size_bytes=1024,
                        is_available=True,
                    )
                    for media_id, width, height in _ASPECTS
                ]
            )
            session.commit()
        code = client.post("/api/v1/pairing/code").json()["data"]["code"]
        token = client.post(
            "/api/v1/pairing/verify", json={"device_id": "phone-closure", "code": code}
        ).json()["data"]["token"]
        yield client, token, upstream


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---------- 缩略图几何 ----------


def test_grid_thumbnail_requests_16_9_fill_for_all_aspect_ratios(closure_client) -> None:
    """16:9 / 9:16 / 4:3 / 1:1 都必须请求 Jellyfin fill 480x270。"""
    client, token, upstream = closure_client
    headers = _auth(token)
    for media_id, _w, _h in _ASPECTS:
        resp = client.get(f"/api/v1/media/{media_id}/thumbnail", headers=headers)
        assert resp.status_code == 200, media_id

    assert len(upstream.params) == len(_ASPECTS)
    for params in upstream.params:
        assert params.get("fillWidth") == "480", params
        assert params.get("fillHeight") == "270", params
        assert params.get("quality") == "80", params
        # 回归点: 不得再使用等比缩放参数(竖图会只剩约 152x270)
        assert "maxWidth" not in params, params
        assert "maxHeight" not in params, params


# ---------- source_version ----------


def test_cover_url_version_is_stable_for_same_fingerprint(closure_client) -> None:
    client, token, _upstream = closure_client
    headers = _auth(token)
    first = client.get("/api/v1/media/m-9-16", headers=headers).json()["data"]["cover_url"]
    second = client.get("/api/v1/media/m-9-16", headers=headers).json()["data"]["cover_url"]
    assert first == second
    assert "?v=" in first


def test_cover_url_version_changes_when_source_changes(closure_client) -> None:
    client, token, _upstream = closure_client
    headers = _auth(token)
    before = client.get("/api/v1/media/m-9-16", headers=headers).json()["data"]["cover_url"]

    db: Database = client.app.state.database
    with db.session() as session:
        row = session.get(models.MediaCacheIndex, "m-9-16")
        assert row is not None
        row.size_bytes = 424242
        session.commit()

    after = client.get("/api/v1/media/m-9-16", headers=headers).json()["data"]["cover_url"]
    assert before != after, "源指纹变化后 URL 版本必须变化,否则客户端会一直用旧缓存"
    assert before.split("?v=")[0] == after.split("?v=")[0]


def test_cover_url_version_contains_no_sensitive_info(closure_client) -> None:
    client, token, _upstream = closure_client
    page = client.get("/api/v1/media?page=1&page_size=50", headers=_auth(token)).json()["data"]
    for item in page["items"]:
        url = item["cover_url"]
        assert "closure-key" not in url
        assert "jf-" not in url  # Jellyfin item id 不下发
        assert "D:" not in url and "C:" not in url and "/" not in url.split("?v=")[1]
        version = url.split("?v=")[1]
        assert len(version) == 16 and all(c in "0123456789abcdef" for c in version)


def test_source_version_is_derived_from_fingerprint_only() -> None:
    fp = "jf-a|2026-01-01T00:00:00|1000"
    version = source_version(fp)
    assert version == source_version(fp)
    assert version != source_version(fp + "x")


# ---------- 缓存统计与淘汰 ----------


def test_thumbnail_cache_stats_reports_files_and_bytes(tmp_path) -> None:
    paths = PathManager(tmp_path)
    paths.ensure()
    service = ThumbnailCacheService(paths)
    assert service.stats().files == 0

    for index in range(3):
        key = thumbnail_cache_key(media_id=f"m{index}", fingerprint="fp", variant="grid_480")
        service.write(key, b"x" * 100, "image/jpeg")

    stats = service.stats()
    assert stats.files == 3
    assert stats.bytes == 300


def test_thumbnail_cache_prune_evicts_oldest_until_under_limit(tmp_path) -> None:
    paths = PathManager(tmp_path)
    paths.ensure()
    service = ThumbnailCacheService(paths)
    for index in range(4):
        key = thumbnail_cache_key(media_id=f"m{index}", fingerprint="fp", variant="grid_480")
        service.write(key, b"x" * 100, "image/jpeg")
        # 保证 mtime 有可比较的先后
        os.utime(next(iter(paths.thumbnails_dir.rglob("*.jpg"))), (time.time() + index,) * 2)

    assert service.stats().bytes == 400
    removed_files, removed_bytes = service.prune(max_bytes=200)
    assert removed_files >= 1
    assert removed_bytes >= 100
    assert service.stats().bytes <= 200


def test_thumbnail_cache_default_cap_is_declared(tmp_path) -> None:
    """容量上限必须显式存在并写明,不允许出现"无上限"的隐式行为。"""
    paths = PathManager(tmp_path)
    paths.ensure()
    service = ThumbnailCacheService(paths)
    assert service.max_bytes == DEFAULT_MAX_BYTES == 1024 * 1024 * 1024


# ---------- 耗时字段 ----------


def test_thumbnail_miss_and_hit_report_timings(closure_client, caplog) -> None:
    client, token, upstream = closure_client
    headers = _auth(token)

    with caplog.at_level("INFO"):
        first = client.get("/api/v1/media/m-16-9/thumbnail", headers=headers)
        second = client.get("/api/v1/media/m-16-9/thumbnail", headers=headers)

    assert first.headers["x-mediareview-cache"] == "MISS"
    assert second.headers["x-mediareview-cache"] == "HIT"
    assert upstream.calls == 1, "HIT 不得回源"

    lines = [rec.getMessage() for rec in caplog.records if "MR_PERF thumbnail" in rec.getMessage()]
    assert lines, "必须产生 MR_PERF thumbnail 打点"
    miss_line = next(line for line in lines if "cache=MISS" in line)
    hit_line = next(line for line in lines if "cache=HIT" in line)
    assert "upstream_ms=" in miss_line
    assert "disk_write_ms=" in miss_line
    assert "disk_read_ms=" in hit_line


def test_media_source_fingerprint_ignores_paths() -> None:
    class Row:
        jellyfin_id = "jf-1"
        modified_at = None
        size_bytes = 10
        media_path = r"D:\Media\secret\a.jpg"

    fingerprint = media_source_fingerprint(Row())
    assert "D:" not in fingerprint and "secret" not in fingerprint
