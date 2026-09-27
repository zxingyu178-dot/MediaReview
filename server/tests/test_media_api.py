"""媒体数据库分页/排序/筛选接口测试。

1.1 起 GET /media 只读 SQLite；Jellyfin 采集由后台 media_refresh 任务负责。
"""

from __future__ import annotations

import time
from collections.abc import AsyncIterator
from datetime import datetime

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
    """图片返回 MediaReview 安全代理 URL；视频 original_url 为 None。"""
    client, _transport = jellyfin_api_client
    _seed_mock_library(client, "lib-photos")
    _seed_mock_library(client, "lib-movies")
    resp = client.get("/api/v1/media", params={"library_id": "lib-photos"})
    assert resp.status_code == 200
    images = resp.json()["data"]["items"]
    assert images, "照片库应返回图片"
    for img in images:
        assert img["media_type"] == "image"
        assert img["cover_url"].startswith(f"/api/v1/media/{img['media_id']}/thumbnail")
        # 阶段 8A.1.1 §6: 客户端缓存失效所需的 source_version
        assert "?v=" in img["cover_url"]
        assert img["original_url"] == f"/api/v1/media/{img['media_id']}/original"
        assert "api_key=" not in str(img)

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


# ---------------------------------------------------------------------------
# Task B: 文件夹辅助视图(folder_id 是服务器派生 ID,绝不下发 Windows 路径)
# ---------------------------------------------------------------------------


def _folder_row(media_id: str, library_id: str, media_path: str | None) -> dict:
    return {
        "media_id": media_id,
        "jellyfin_id": media_id,
        "library_id": library_id,
        "name": f"{media_id}.mp4",
        "media_type": "video",
        "size_bytes": 1000,
        "duration_ms": 6000,
        "width": 1920,
        "height": 1080,
        "fingerprint": f"fp-{media_id}",
        "is_available": True,
        "media_path": media_path,
    }


def _seed_folder_library(client: TestClient, library_id: str = "lib-folders") -> None:
    backslash = chr(92)  # Windows 路径分隔符,显式构造避免转义歧义
    rows = [
        *(
            _folder_row(
                f"fa-{i}",
                library_id,
                "D:" + backslash + f"Media{backslash}MoviesA{backslash}file{i}.mp4",
            )
            for i in range(3)
        ),
        *(_folder_row(f"fb-{i}", library_id, f"D:/Media/MoviesB/file{i}.mp4") for i in range(2)),
        _folder_row(
            "root-in-media", library_id, "D:" + backslash + "Media" + backslash + "root.mp4"
        ),
        _folder_row("drive-root", library_id, "D:" + backslash + "top.mp4"),
        _folder_row("no-path", library_id, None),
    ]
    with client.app.state.database.session() as session:
        session.execute(sa.insert(models.MediaCacheIndex), rows)
        session.commit()


def _folder_view(resp) -> list[dict]:
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["success"] is True
    return body["data"]


def test_media_folders_group_by_parent_directory(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    _seed_folder_library(client)

    folders = _folder_view(
        client.get("/api/v1/media/folders", params={"library_id": "lib-folders"})
    )
    by_name = {f["name"]: f for f in folders}
    # 反斜杠与正斜杠两种分隔符都归一到同一父目录
    assert by_name["MoviesA"]["count"] == 3
    assert by_name["MoviesB"]["count"] == 2
    assert by_name["Media"]["count"] == 1  # D:\Media\root.mp4 的直接父目录
    assert by_name["(根目录)"]["count"] == 1  # D:\top.mp4 位于盘根
    # folder_id 是不透明服务器 ID;响应任何字段都不含 Windows 路径
    assert len(by_name["MoviesA"]["folder_id"]) == 16
    int(by_name["MoviesA"]["folder_id"], 16)
    for f in folders:
        assert "\\" not in f["name"] and "\\" not in f["folder_id"]
        assert ":/" not in f["name"] and ":/" not in f["folder_id"]


def test_media_folders_respect_current_filters(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    _seed_folder_library(client)

    folders = _folder_view(
        client.get(
            "/api/v1/media/folders",
            params={"library_id": "lib-folders", "search": "fa-1"},
        )
    )
    assert [f["name"] for f in folders] == ["MoviesA"]
    assert folders[0]["count"] == 1

    folders_type = _folder_view(
        client.get(
            "/api/v1/media/folders",
            params={"library_id": "lib-folders", "media_type": "image"},
        )
    )
    assert folders_type == []


def test_media_list_filter_by_folder_id_keeps_paging(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    _seed_folder_library(client)
    folders = _folder_view(
        client.get("/api/v1/media/folders", params={"library_id": "lib-folders"})
    )
    movies_a = next(f for f in folders if f["name"] == "MoviesA")

    resp = client.get(
        "/api/v1/media",
        params={"library_id": "lib-folders", "folder_id": movies_a["folder_id"], "page_size": 2},
    )
    assert resp.status_code == 200
    body = resp.json()["data"]
    assert body["total"] == 3
    assert [i["media_id"] for i in body["items"]] == ["fa-0", "fa-1"]

    page2 = client.get(
        "/api/v1/media",
        params={
            "library_id": "lib-folders",
            "folder_id": movies_a["folder_id"],
            "page": 2,
            "page_size": 2,
        },
    )
    assert [i["media_id"] for i in page2.json()["data"]["items"]] == ["fa-2"]


def test_media_list_unknown_folder_id_rejected(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    _seed_folder_library(client)
    resp = client.get(
        "/api/v1/media",
        params={"library_id": "lib-folders", "folder_id": "0" * 16},
    )
    assert resp.status_code == 404


def test_media_folders_without_path_rows_excluded(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    rows = [_folder_row("np-1", "lib-np", None)]
    with client.app.state.database.session() as session:
        session.execute(sa.insert(models.MediaCacheIndex), rows)
        session.commit()
    folders = _folder_view(client.get("/api/v1/media/folders", params={"library_id": "lib-np"}))
    assert folders == []


# ---- Stage 8A: 文件夹代表封面(服务端下发,客户端不再 N+1 请求) ----


def _cover_row(
    media_id: str, library_id: str, media_type: str, dirname: str, created_at: datetime | None
) -> dict:
    return {
        "media_id": media_id,
        "jellyfin_id": media_id,
        "library_id": library_id,
        "name": f"{media_id}.bin",
        "media_type": media_type,
        "size_bytes": 1000,
        "duration_ms": 6000 if media_type == "video" else None,
        "width": 1920,
        "height": 1080,
        "fingerprint": f"fp-{media_id}",
        "is_available": True,
        "media_path": f"{dirname}/{media_id}.bin",
        "created_at": created_at,
    }


def _seed_cover_library(client: TestClient, library_id: str = "lib-covers") -> None:
    rows = [
        # 图片+视频混合目录:封面应取 created_at 最新的一张图片(img-new)
        _cover_row("img-old", library_id, "image", "D:/Covers/AlbumA", datetime(2025, 1, 1)),
        _cover_row("img-new", library_id, "image", "D:/Covers/AlbumA", datetime(2025, 3, 1)),
        _cover_row("vid-mid", library_id, "video", "D:/Covers/AlbumA", datetime(2025, 4, 1)),
        # 仅视频目录:没有图片封面
        _cover_row("vid-only", library_id, "video", "D:/Covers/AlbumV", datetime(2025, 2, 1)),
        # created_at 相同:用 media_id 升序做稳定 tie-break(a-tie < b-tie)
        _cover_row("b-tie", library_id, "image", "D:/Covers/AlbumT", datetime(2025, 5, 1)),
        _cover_row("a-tie", library_id, "image", "D:/Covers/AlbumT", datetime(2025, 5, 1)),
    ]
    with client.app.state.database.session() as session:
        session.execute(sa.insert(models.MediaCacheIndex), rows)
        session.commit()


def test_media_folders_cover_latest_image(jellyfin_api_client) -> None:
    """有图片的文件夹:返回 created_at 最新图片的 media_id 与 cover_url。"""
    client, _transport = jellyfin_api_client
    _seed_cover_library(client)
    folders = _folder_view(
        client.get("/api/v1/media/folders", params={"library_id": "lib-covers"})
    )
    by_name = {f["name"]: f for f in folders}

    album_a = by_name["AlbumA"]
    assert album_a["count"] == 3
    assert album_a["image_count"] == 2
    assert album_a["cover_media_id"] == "img-new"
    assert album_a["cover_url"].startswith("/api/v1/media/img-new/thumbnail")
    # 响应仍不含 Windows 路径
    assert "D:/" not in str(album_a) and "Covers" not in str(album_a)


def test_media_folders_cover_only_videos_is_none(jellyfin_api_client) -> None:
    """只有视频的文件夹:cover_media_id / cover_url 均为 None,image_count 为 0。"""
    client, _transport = jellyfin_api_client
    _seed_cover_library(client)
    folders = _folder_view(
        client.get("/api/v1/media/folders", params={"library_id": "lib-covers"})
    )
    by_name = {f["name"]: f for f in folders}

    album_v = by_name["AlbumV"]
    assert album_v["count"] == 1
    assert album_v["image_count"] == 0
    assert album_v["cover_media_id"] is None
    assert album_v["cover_url"] is None


def test_media_folders_image_count(jellyfin_api_client) -> None:
    """image_count = 该文件夹内 IMAGE 数量,与 count 同一筛选范围。"""
    client, _transport = jellyfin_api_client
    _seed_cover_library(client)
    folders = _folder_view(
        client.get("/api/v1/media/folders", params={"library_id": "lib-covers"})
    )
    by_name = {f["name"]: f for f in folders}
    assert by_name["AlbumA"]["image_count"] == 2  # 2 图 + 1 视频
    assert by_name["AlbumT"]["image_count"] == 2  # 2 图
    assert by_name["AlbumV"]["image_count"] == 0  # 仅视频


def test_media_folders_cover_tie_break_by_media_id(jellyfin_api_client) -> None:
    """同一文件夹多张图片:created_at 相同时按 media_id 升序取封面。"""
    client, _transport = jellyfin_api_client
    _seed_cover_library(client)
    folders = _folder_view(
        client.get("/api/v1/media/folders", params={"library_id": "lib-covers"})
    )
    by_name = {f["name"]: f for f in folders}
    assert by_name["AlbumT"]["cover_media_id"] == "a-tie"


def test_media_folders_cover_respects_media_type_filter(jellyfin_api_client) -> None:
    """封面与 count 属同一筛选范围:media_type=video 时无图片,封面为 None。"""
    client, _transport = jellyfin_api_client
    _seed_cover_library(client)
    folders = _folder_view(
        client.get(
            "/api/v1/media/folders",
            params={"library_id": "lib-covers", "media_type": "video"},
        )
    )
    by_name = {f["name"]: f for f in folders}
    assert by_name["AlbumA"]["count"] == 1
    assert by_name["AlbumA"]["image_count"] == 0
    assert by_name["AlbumA"]["cover_media_id"] is None
    assert by_name["AlbumA"]["cover_url"] is None


def test_media_folders_existing_fields_still_present(jellyfin_api_client) -> None:
    """新增封面字段为增量:folder_id / name / count 行为不变,老客户端可忽略新字段。"""
    client, _transport = jellyfin_api_client
    _seed_folder_library(client)
    folders = _folder_view(
        client.get("/api/v1/media/folders", params={"library_id": "lib-folders"})
    )
    by_name = {f["name"]: f for f in folders}
    assert by_name["MoviesA"]["count"] == 3
    assert by_name["MoviesB"]["count"] == 2
    assert by_name["(根目录)"]["count"] == 1
    for f in folders:
        assert len(f["folder_id"]) == 16
        assert "cover_media_id" in f and "cover_url" in f and "image_count" in f
        # 这些目录全是视频,故图片数为 0、封面为 None
        assert f["image_count"] == 0
        assert f["cover_media_id"] is None and f["cover_url"] is None


def test_media_list_items_carry_folder_identity(jellyfin_api_client) -> None:
    """列表项带 folder_id/folder_name,且与 /media/folders 的 folder_id 一一对应。"""
    client, _transport = jellyfin_api_client
    _seed_folder_library(client)
    folders = _folder_view(
        client.get("/api/v1/media/folders", params={"library_id": "lib-folders"})
    )
    by_name = {f["name"]: f for f in folders}
    movies_a_id = by_name["MoviesA"]["folder_id"]

    resp = client.get("/api/v1/media", params={"library_id": "lib-folders", "page_size": 50})
    assert resp.status_code == 200
    items = resp.json()["data"]["items"]
    by_media = {i["media_id"]: i for i in items}

    # 同一文件夹下媒体的 folder_id / folder_name 必须等于该文件夹的取值
    for media_id in ("fa-0", "fa-1", "fa-2"):
        assert by_media[media_id]["folder_id"] == movies_a_id
        assert by_media[media_id]["folder_name"] == "MoviesA"
    assert by_media["fb-0"]["folder_name"] == "MoviesB"
    assert by_media["root-in-media"]["folder_name"] == "Media"
    assert by_media["drive-root"]["folder_name"] == "(根目录)"

    # 媒体路径为 NULL → folder_id / folder_name 均为 None
    assert by_media["no-path"]["folder_id"] is None
    assert by_media["no-path"]["folder_name"] is None

    # 所有非 None 的 folder_id 都能在 /media/folders 中找到(一一对应)
    folder_ids = {f["folder_id"] for f in folders}
    for item in items:
        for value in (item["folder_id"], item["folder_name"]):
            assert value is None or ("\\" not in value and ":/" not in value)
        if item["folder_id"] is not None:
            assert item["folder_id"] in folder_ids


def test_media_detail_carries_folder_identity(jellyfin_api_client) -> None:
    """单条媒体详情与列表项同源:返回一致的 folder_id / folder_name。"""
    client, _transport = jellyfin_api_client
    _seed_folder_library(client)
    folders = _folder_view(
        client.get("/api/v1/media/folders", params={"library_id": "lib-folders"})
    )
    movies_a_id = next(f for f in folders if f["name"] == "MoviesA")["folder_id"]

    detail = client.get("/api/v1/media/fa-1").json()["data"]
    assert detail["folder_id"] == movies_a_id
    assert detail["folder_name"] == "MoviesA"


def test_media_pagination_100k_no_rescan_and_responsive(data_root) -> None:
    """100k 索引连续翻页: Jellyfin 零调用,单页响应低于硬上限(目标 P95<1s)。"""

    def big_handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/System/Info":
            return httpx.Response(200, json={"ServerName": "Big", "Version": "10.9.11", "Id": "s1"})
        if path == "/Users":
            return httpx.Response(200, json=[{"Id": JELLYFIN_USER_ID, "Name": "jp"}])
        if path == f"/Users/{JELLYFIN_USER_ID}/Views":
            return httpx.Response(
                200,
                json={"Items": [{"Id": "lib-100k", "Name": "十万库", "CollectionType": "movies"}]},
            )
        return httpx.Response(404, json={"error": "unexpected " + path})

    # 在 client 启动(lifespan + TaskManager 1s 轮询)之前完成迁移与插种,
    # 轮询期间没有任何长写事务,从根上消除 SQLite 锁竞争 flake。
    total = 100_000
    from app.db.migrate import run_migrations
    from app.db.session import Database

    db_path = data_root / "database" / "mediareview.db"
    db_path.parent.mkdir(parents=True, exist_ok=True)
    run_migrations(f"sqlite:///{db_path}")
    seed_db = Database(db_path)
    with seed_db.session() as session:
        session.add(
            models.LibrarySelection(
                jellyfin_id="lib-100k", name="十万库", collection_type="movies", selected=True
            )
        )
        session.commit()
    for chunk_start in range(0, total, 20_000):
        payload = [
            {
                "media_id": compute_media_id(f"k-{i}"),
                "jellyfin_id": f"k-{i}",
                "library_id": "lib-100k",
                "name": f"movie{i:06d}.mp4",
                "media_type": "video",
                "size_bytes": 1000 + i,
                "duration_ms": 6000 + i,
                "width": 1920,
                "height": 1080,
                "fingerprint": f"fp-{i}",
                "is_available": True,
            }
            for i in range(chunk_start, min(chunk_start + 20_000, total))
        ]
        with seed_db.engine.begin() as connection:
            connection.execute(sa.insert(models.MediaCacheIndex), payload)
    seed_db.dispose()

    client, calls = _make_counting_app(big_handler, data_root)
    with client as c:
        durations: list[float] = []
        for page in (1, 2, 3):
            started = time.perf_counter()
            resp = c.get(
                "/api/v1/media",
                params={"library_id": "lib-100k", "page": page, "page_size": 50},
            )
            durations.append(time.perf_counter() - started)
            assert resp.status_code == 200
            body = resp.json()["data"]
            assert body["total"] == total
            assert len(body["items"]) == 50
            assert calls["items"] == 0
        # 目标是 P95<1s / DB<250ms;这里用宽松硬上限防慢机抖动,真实值打印留档
        assert max(durations) < 2.0, f"page too slow: {durations}"
        print(f"100k page durations: {[round(d, 3) for d in durations]}")

        started = time.perf_counter()
        folders_resp = c.get("/api/v1/media/folders", params={"library_id": "lib-100k"})
        folder_elapsed = time.perf_counter() - started
        assert folders_resp.status_code == 200
        assert folder_elapsed < 2.0, f"folders too slow: {folder_elapsed}"
        print(f"100k folders elapsed: {round(folder_elapsed, 3)}")
