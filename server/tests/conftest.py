"""测试夹具: 全部测试使用临时数据目录,不触碰真实 ProgramData。

注意: 默认 app/client 夹具关闭 pairing_required(security.pairing_required=False),
仅作为开发便利,方便既有功能测试不携带 token;认证闭环由 auth-paired 专属夹具
(pairing_required=True)在 test_stage7_fix.py 中显式覆盖,并断言未配对/伪造/吊销行为。
生产默认仍是 True,不受测试影响。
"""

from __future__ import annotations

from collections.abc import AsyncIterator, Iterator
from pathlib import Path

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.adapters.jellyfin.client import JellyfinClient
from app.api.v1.jellyfin import jellyfin_client
from app.core.config import AppConfig, JellyfinConfig, SecurityConfig, StorageConfig
from app.main import create_app


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
def data_root(tmp_path: Path) -> Path:
    return tmp_path / "MediaReviewData"


@pytest.fixture
def app_config(data_root: Path) -> AppConfig:
    # 测试默认关闭配对要求(见文件顶部说明);认证测试用 paired 夹具单独开启
    # 关闭配对即视为开发模式,同步放开远程生成配对码,保持原有便利性
    return AppConfig(
        storage=StorageConfig(data_root=str(data_root)),
        security=SecurityConfig(pairing_required=False, pairing_code_remote_allowed=True),
    )


@pytest.fixture
def app(app_config: AppConfig) -> FastAPI:
    # 保留迁移路径,同时验证"数据库自动初始化"
    return create_app(app_config)


@pytest.fixture
def client(app: FastAPI) -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client


# ---- Jellyfin mock(阶段 2 起) ----

JELLYFIN_USER_ID = "user-0001"


def _movie_item(idx: int, name: str) -> dict:
    return {
        "Id": f"mv-{idx}",
        "Name": name,
        "Type": "Video",
        "Path": f"D:\\Movies\\{name}",
        "Size": idx * 100,
        "RunTimeTicks": idx * 1_820_000,
        "Width": 1920,
        "Height": 1080,
        "Container": "mp4",
        "DateCreated": f"2025-01-0{idx}T03:04:0{idx}.000Z",
        "DateModified": f"2025-01-0{idx}T03:04:0{idx}.000Z",
    }


def _photo_item(idx: int, name: str) -> dict:
    return {
        "Id": f"ph-{idx}",
        "Name": name,
        "Type": "Photo",
        "Path": f"D:\\Photos\\{name}",
        "Size": idx * 1000,
        "Width": 4000,
        "Height": 3000,
        "Container": "jpg",
    }


def make_jellyfin_mock_transport() -> httpx.MockTransport:
    """返回行为固定的 Jellyfin 模拟服务,覆盖阶段 2/3 全部端点。"""

    def _items_page(*items: dict) -> dict:
        return {
            "Items": list(items),
            "TotalRecordCount": len(items),
        }

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if request.method in ("GET", "POST") and path.endswith("/Auth/Keys"):
            return httpx.Response(
                200,
                json={
                    "Items": [
                        {
                            "Name": "mediareview-shared-playback",
                            "AccessToken": "device-jf-key-mock-1",
                        }
                    ]
                },
            )
        if path == "/System/Info":
            return httpx.Response(
                200,
                json={"ServerName": "HomeServer", "Version": "10.9.11", "Id": "srv-1"},
            )
        if path == "/Users":
            return httpx.Response(
                200, json=[{"Id": JELLYFIN_USER_ID, "Name": "jp"}, {"Id": "u2", "Name": "guest"}]
            )
        if path == f"/Users/{JELLYFIN_USER_ID}/Views":
            return httpx.Response(
                200,
                json={
                    "Items": [
                        {"Id": "lib-movies", "Name": "电影", "CollectionType": "movies"},
                        {"Id": "lib-photos", "Name": "照片", "CollectionType": "homevideos"},
                    ]
                },
            )
        if path == f"/Users/{JELLYFIN_USER_ID}/Items":
            parent = request.url.params.get("ParentId")
            if parent == "lib-movies":
                raw_items = [
                    _movie_item(1, "B.mp4"),
                    _movie_item(2, "a.mp4"),
                    _movie_item(3, "C.mp4"),
                    _movie_item(4, "d.mp4"),
                    _movie_item(5, "E.mp4"),
                ]
            elif parent == "lib-photos":
                raw_items = [_photo_item(1, "P1.jpg"), _photo_item(2, "P2.jpg")]
            else:
                raw_items = [
                    {
                        "Id": "it-video",
                        "Name": "Clip.mp4",
                        "Type": "Video",
                        "Path": "D:\\Media\\Clip.mp4",
                        "Size": 1048576,
                        "RunTimeTicks": 18_240_000,
                        "Width": 1920,
                        "Height": 1080,
                        "Container": "mp4",
                        "DateCreated": "2025-01-02T03:04:05.000Z",
                        "DateModified": "2025-01-02T03:04:05.000Z",
                    },
                    {
                        "Id": "it-photo",
                        "Name": "IMG_001.jpg",
                        "Type": "Photo",
                        "Path": "D:\\Photos\\IMG_001.jpg",
                        "Size": 2048,
                        "Width": 4000,
                        "Height": 3000,
                        "Container": "jpg",
                    },
                ]
            # 镜像真实 Jellyfin:按 IncludeItemTypes 过滤并重算总数
            include_types = request.url.params.get("IncludeItemTypes")
            if include_types:
                allowed = set(include_types.split(","))
                raw_items = [it for it in raw_items if it["Type"] in allowed]
            return httpx.Response(200, json=_items_page(*raw_items))
        if path == f"/Users/{JELLYFIN_USER_ID}/Items/it-video":
            return httpx.Response(
                200,
                json={
                    "Id": "it-video",
                    "Name": "Clip.mp4",
                    "Type": "Video",
                    "Path": "D:\\Media\\Clip.mp4",
                    "Size": 1048576,
                    "RunTimeTicks": 18_240_000,
                },
            )
        if path == "/Sessions/Playing/Progress":
            return httpx.Response(204)
        return httpx.Response(404, json={"error": "unexpected path " + path})

    return httpx.MockTransport(handler)


@pytest.fixture
def jellyfin_config() -> JellyfinConfig:
    return JellyfinConfig(url="http://127.0.0.1:8096", api_key=SecretStr("test-api-key"))


@pytest.fixture
def jellyfin_api_client(
    app_config: AppConfig,
) -> Iterator[tuple[TestClient, httpx.MockTransport]]:
    """带 Jellyfin mock 的 API 客户端,同时返回 transport 供断言。"""
    app_config.jellyfin.api_key = SecretStr("test-api-key")
    # Starlette TestClient 的单标签 request host 仅在测试配置中显式允许。
    app_config.jellyfin.client_host_allowlist = ["testserver"]
    # 预设 user_id,供阶段 3 media 接口直接使用(模拟已完成的媒体库配置)
    app_config.jellyfin.user_id = JELLYFIN_USER_ID
    transport = make_jellyfin_mock_transport()
    app = create_app(app_config)

    async def override_client() -> AsyncIterator[JellyfinClient]:
        async with JellyfinClient(app_config.jellyfin, transport=transport) as jf_client:
            yield jf_client

    app.dependency_overrides[jellyfin_client] = override_client
    with TestClient(app) as test_client:
        yield test_client, transport
