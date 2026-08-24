"""媒体数据库分页/排序/筛选接口测试。

1.1 起 GET /media 只读 SQLite；Jellyfin 采集由后台 media_refresh 任务负责。
"""

from __future__ import annotations

from collections.abc import AsyncIterator

import httpx
import sqlalchemy as sa
from conftest import (
    JELLYFIN_USER_ID,
    _movie_item,
    _photo_item,
    make_jellyfin_mock_transport,
)
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.adapters.jellyfin.client import JellyfinClient
from app.adapters.jellyfin.mapper import compute_media_id, map_media_item
from app.adapters.jellyfin.models import JFItem
from app.api.v1.jellyfin import jellyfin_client
from app.core.config import AppConfig, SecurityConfig, StorageConfig
from app.db import models
from app.main import create_app
from app.services import media_index


def _names(resp) -> list[str]:
    body = resp.json()
    assert body["success"] is True
    return [item["name"] for item in body["data"]["items"]]


def _seed_mock_library(client: TestClient, library_id: str) -> None:
    if library_id == "lib-movies":
        raw_items = [
            _movie_item(1, "B.mp4"),
            _movie_item(2, "a.mp4"),
            _movie_item(3, "C.mp4"),
            _movie_item(4, "d.mp4"),
            _movie_item(5, "E.mp4"),
        ]
    elif library_id == "lib-photos":
        raw_items = [_photo_item(1, "P1.jpg"), _photo_item(2, "P2.jpg")]
    else:
        raise AssertionError(f"unsupported mock library {library_id}")
    items = [map_media_item(JFItem.from_raw(raw), library_id=library_id) for raw in raw_items]
    with client.app.state.database.session() as session:
        media_index.upsert_media_items(session, items)
        session.commit()


def test_media_list_single_library_default_sort(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    _seed_mock_library(client, "lib-movies")
    resp = client.get("/api/v1/media", params={"library_id": "lib-movies"})
    assert resp.status_code == 200
    body = resp.json()["data"]
    assert body["total"] == 5
    # 默认按名称升序(casefold):a, B, C, d, E
    assert [i["name"] for i in body["items"]] == ["a.mp4", "B.mp4", "C.mp4", "d.mp4", "E.mp4"]
    assert body["page"] == 1
    assert body["page_size"] == 50


def test_media_list_sort_by_size_desc(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    _seed_mock_library(client, "lib-movies")
    resp = client.get(
        "/api/v1/media",
        params={"library_id": "lib-movies", "sort_by": "size", "sort_order": "desc"},
    )
    assert resp.status_code == 200
    assert _names(resp) == ["E.mp4", "d.mp4", "C.mp4", "a.mp4", "B.mp4"]


def test_media_list_filter_media_type_video(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    _seed_mock_library(client, "lib-movies")
    resp = client.get("/api/v1/media", params={"library_id": "lib-movies", "media_type": "video"})
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["total"] == 5
    assert all(i["media_type"] == "video" for i in data["items"])


def test_media_list_filter_no_match(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    _seed_mock_library(client, "lib-movies")
    resp = client.get("/api/v1/media", params={"library_id": "lib-movies", "media_type": "image"})
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["total"] == 0
    assert data["items"] == []


def test_media_list_pagination(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    _seed_mock_library(client, "lib-movies")
    resp = client.get(
        "/api/v1/media", params={"library_id": "lib-movies", "page": 2, "page_size": 2}
    )
    assert resp.status_code == 200
    data = resp.json()["data"]
    # 名称排序 [a, B, C, d, E],第 2 页 size=2 → [C, d]
    assert [i["name"] for i in data["items"]] == ["C.mp4", "d.mp4"]
    assert data["total"] == 5
    assert data["page"] == 2


def test_media_list_invalid_sort_field(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    resp = client.get(
        "/api/v1/media",
        params={"library_id": "lib-movies", "sort_by": "path; DROP TABLE"},
    )
    # 排序字段白名单校验失败
    assert resp.status_code == 422


def test_media_list_without_selection_rejected(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    # 未调用 /libraries,也从未勾选 → 拒绝
    resp = client.get("/api/v1/media")
    assert resp.status_code == 422


def test_media_list_all_selected_libraries(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    client.get("/api/v1/libraries", params={"user_id": "user-0001"})
    client.put("/api/v1/libraries/selection", json={"selected": ["lib-movies", "lib-photos"]})
    _seed_mock_library(client, "lib-movies")
    _seed_mock_library(client, "lib-photos")
    resp = client.get("/api/v1/media")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["total"] == 7  # 5 视频 + 2 照片
    assert sorted(i["media_type"] for i in data["items"]) == sorted(["video"] * 5 + ["image"] * 2)


def test_media_detail_roundtrip(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    _seed_mock_library(client, "lib-movies")
    list_resp = client.get("/api/v1/media", params={"library_id": "lib-movies"})
    media_id = list_resp.json()["data"]["items"][0]["media_id"]
    detail = client.get(f"/api/v1/media/{media_id}")
    assert detail.status_code == 200
    body = detail.json()
    assert body["success"] is True
    assert body["data"]["media_id"] == media_id
    # 不泄露真实路径 / Jellyfin 内部 ID
    assert "Path" not in body["data"]
    assert "jellyfin_id" not in body["data"]


def test_media_detail_not_found(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    resp = client.get(f"/api/v1/media/{compute_media_id('ghost')}")
    assert resp.status_code == 404
    body = resp.json()
    assert body["success"] is False
    assert body["error"]["code"] == "MEDIA_NOT_FOUND"


def test_media_original_url_image_present_video_null(jellyfin_api_client) -> None:
    """图片返回原图直连 URL(下载原图);视频原图走播放 API,original_url 为 None。"""
    client, _transport = jellyfin_api_client
    _seed_mock_library(client, "lib-photos")
    _seed_mock_library(client, "lib-movies")
    resp = client.get("/api/v1/media", params={"library_id": "lib-photos"})
    assert resp.status_code == 200
    images = resp.json()["data"]["items"]
    assert images, "照片库应返回图片"
    for img in images:
        assert img["media_type"] == "image"
        assert img["cover_url"] and "Images/Primary" in img["cover_url"]
        assert img["original_url"] and "/Download" in img["original_url"]

    resp2 = client.get("/api/v1/media", params={"library_id": "lib-movies"})
    videos = resp2.json()["data"]["items"]
    assert all(v["media_type"] == "video" and v["original_url"] is None for v in videos)


def test_media_exclude_favorites_filter(jellyfin_api_client) -> None:
    """exclude_favorites=true 时排除已点赞媒体(未点赞筛选)。"""
    client, _transport = jellyfin_api_client
    _seed_mock_library(client, "lib-movies")
    list_resp = client.get("/api/v1/media", params={"library_id": "lib-movies"})
    items = list_resp.json()["data"]["items"]
    assert len(items) == 5
    fav_id = items[0]["media_id"]
    favorited = client.post(f"/api/v1/favorites/{fav_id}")
    assert favorited.status_code == 200

    all_resp = client.get("/api/v1/media", params={"library_id": "lib-movies"})
    assert all_resp.json()["data"]["total"] == 5

    filtered = client.get(
        "/api/v1/media",
        params={"library_id": "lib-movies", "exclude_favorites": "true"},
    )
    assert filtered.status_code == 200
    data = filtered.json()["data"]
    assert data["total"] == 4
    assert all(i["media_id"] != fav_id for i in data["items"])


def test_media_progress_reports_to_jellyfin(jellyfin_api_client) -> None:
    """POST /media/{id}/progress 回传 Jellyfin 播放进度(Sessions/Playing/Progress)。"""
    client, _transport = jellyfin_api_client
    _seed_mock_library(client, "lib-movies")
    list_resp = client.get("/api/v1/media", params={"library_id": "lib-movies"})
    media_id = list_resp.json()["data"]["items"][0]["media_id"]
    resp = client.post(
        f"/api/v1/media/{media_id}/progress",
        json={"position_ms": 15000, "is_paused": False},
    )
    assert resp.status_code == 200
    assert resp.json()["data"]["reported"] is True


# ---- 阶段 16: 媒体快照缓存验收 ----


def _make_counting_app(handler, data_root) -> tuple[TestClient, dict[str, int]]:
    """构造带 Items 调用计数的应用;返回 (client, calls)。"""
    calls = {"items": 0}
    base = handler

    def counting(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/Items"):
            calls["items"] += 1
        return base(request)

    config = AppConfig(
        storage=StorageConfig(data_root=str(data_root)),
        security=SecurityConfig(pairing_required=False),
    )
    config.jellyfin.api_key = SecretStr("test-api-key")
    config.jellyfin.user_id = JELLYFIN_USER_ID
    transport = httpx.MockTransport(counting)
    app = create_app(config)

    async def override_client() -> AsyncIterator[JellyfinClient]:
        async with JellyfinClient(config.jellyfin, transport=transport) as jf:
            yield jf

    app.dependency_overrides[jellyfin_client] = override_client
    return TestClient(app), calls


def test_media_pagination_never_scans_jellyfin(data_root) -> None:
    """连续 page 1/2/3 都只读 SQLite，Jellyfin Items 调用为零。"""
    client, calls = _make_counting_app(make_jellyfin_mock_transport().handler, data_root)
    with client as c:
        c.get("/api/v1/libraries")
        c.put("/api/v1/libraries/selection", json={"selected": ["lib-movies"]})
        _seed_mock_library(c, "lib-movies")

        p1 = c.get("/api/v1/media", params={"library_id": "lib-movies", "page": 1, "page_size": 2})
        assert p1.status_code == 200
        assert calls["items"] == 0

        p2 = c.get("/api/v1/media", params={"library_id": "lib-movies", "page": 2, "page_size": 2})
        p3 = c.get("/api/v1/media", params={"library_id": "lib-movies", "page": 3, "page_size": 2})
        assert p2.status_code == 200 and p3.status_code == 200
        assert calls["items"] == 0


def test_media_pagination_large_library_no_rescan(data_root) -> None:
    """10,000 条 SQLite 索引连续翻页和切排序都不访问 Jellyfin。"""

    def big_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/System/Info":
            return httpx.Response(200, json={"ServerName": "Big", "Version": "10.9.11", "Id": "s1"})
        if path == "/Users":
            return httpx.Response(200, json=[{"Id": JELLYFIN_USER_ID, "Name": "jp"}])
        if path == f"/Users/{JELLYFIN_USER_ID}/Views":
            return httpx.Response(
                200, json={"Items": [{"Id": "lib-big", "Name": "大库", "CollectionType": "movies"}]}
            )
        if path == f"/Users/{JELLYFIN_USER_ID}/Items":
            parent = request.url.params.get("ParentId")
            if parent == "lib-big":
                start = int(request.url.params.get("StartIndex", "0"))
                limit = int(request.url.params.get("Limit", "500"))
                total = 10_000
                # 生成 total 条媒体(start..start+limit),id 稳定
                ids = range(start, min(start + limit, total))
                items = [
                    {
                        "Id": f"big-{i}",
                        "Name": f"movie{i:05d}.mp4",
                        "Type": "Video",
                        "Path": f"D:\\Big\\movie{i:05d}.mp4",
                        "Size": 1000 + i,
                        "RunTimeTicks": (6000 + i) * 10_000,
                        "Width": 1920,
                        "Height": 1080,
                        "Container": "mp4",
                    }
                    for i in ids
                ]
                return httpx.Response(200, json={"Items": items, "TotalRecordCount": total})
        return httpx.Response(404, json={"error": "unexpected " + path})

    client, calls = _make_counting_app(big_handler, data_root)
    with client as c:
        c.get("/api/v1/libraries")
        c.put("/api/v1/libraries/selection", json={"selected": ["lib-big"]})
        payload = [
            {
                "media_id": compute_media_id(f"big-{i}"),
                "jellyfin_id": f"big-{i}",
                "library_id": "lib-big",
                "name": f"movie{i:05d}.mp4",
                "media_type": "video",
                "size_bytes": 1000 + i,
                "duration_ms": 6000 + i,
                "width": 1920,
                "height": 1080,
                "fingerprint": f"fp-{i}",
                "is_available": True,
            }
            for i in range(10_000)
        ]
        with c.app.state.database.engine.begin() as connection:
            connection.execute(sa.insert(models.MediaCacheIndex), payload)

        p1 = c.get("/api/v1/media", params={"library_id": "lib-big", "page": 1, "page_size": 50})
        assert p1.status_code == 200
        data1 = p1.json()["data"]
        assert data1["total"] == 10_000
        assert len(data1["items"]) == 50
        assert calls["items"] == 0

        # 切排序、翻页均不再扫描
        p2 = c.get("/api/v1/media", params={"library_id": "lib-big", "page": 2, "page_size": 50})
        p_sorted = c.get(
            "/api/v1/media",
            params={
                "library_id": "lib-big",
                "page": 1,
                "page_size": 50,
                "sort_by": "size",
                "sort_order": "desc",
            },
        )
        assert p2.status_code == 200 and p_sorted.status_code == 200
        assert p2.json()["data"]["items"][0]["name"] == "movie00050.mp4"
        assert calls["items"] == 0
