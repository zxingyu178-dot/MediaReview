"""Jellyfin REST 客户端。

- httpx.AsyncClient,超时与异常统一映射为 AppError 体系
- 只暴露 JF* 模型与统一 DTO,Jellyfin 原始 JSON 不出本包
- 直连 URL(视频流/原图/缩略图)由本类构造,Android 用其直连 Jellyfin,
  中间层不转发大流量(AGENTS.md 禁止事项)
"""

from __future__ import annotations

from typing import Any

import httpx

from app import __version__
from app.adapters.jellyfin.mapper import (
    PLAYABLE_ITEM_TYPES,
    map_library,
    map_media_item,
)
from app.adapters.jellyfin.models import (
    JFItem,
    JFItemsPage,
    JFSystemInfo,
    JFUser,
    Library,
    MediaItem,
)
from app.core.config import JellyfinConfig
from app.core.errors import JellyfinError

_TIMEOUT = httpx.Timeout(10.0, connect=5.0)
# 拉取大列表时的单页上限,防止一次请求过重
_MAX_PAGE_LIMIT = 1000


def _strip_host(host: str) -> str:
    return host.rstrip("/")


def item_stream_url(host: str, api_key: str, item_id: str) -> str:
    return f"{_strip_host(host)}/Videos/{item_id}/stream?static=true&api_key={api_key}"


def item_original_url(host: str, api_key: str, item_id: str) -> str:
    return f"{_strip_host(host)}/Items/{item_id}/Download?api_key={api_key}"


def item_thumbnail_url(host: str, api_key: str, item_id: str, max_width: int = 480) -> str:
    return (
        f"{_strip_host(host)}/Items/{item_id}/Images/Primary"
        f"?maxWidth={max_width}&quality=80&api_key={api_key}"
    )


class JellyfinAuthError(JellyfinError):
    code = "JELLYFIN_AUTH_FAILED"
    default_message = "Jellyfin 认证失败,请检查 API Key"


class JellyfinClient:
    def __init__(self, config: JellyfinConfig, transport: httpx.AsyncBaseTransport | None = None):
        self._config = config
        self._base_url = config.host
        self._api_key = config.api_key.get_secret_value()
        self._http = httpx.AsyncClient(
            base_url=self._base_url,
            headers=self._auth_headers(),
            timeout=_TIMEOUT,
            transport=transport,
            follow_redirects=True,
        )

    def _auth_headers(self) -> dict[str, str]:
        auth = (
            f'MediaBrowser Token="{self._api_key}", '
            f'Client="MediaReview", Device="MediaReview Server", Version="{__version__}"'
        )
        return {"Authorization": auth, "Accept": "application/json"}

    async def __aenter__(self) -> JellyfinClient:
        return self

    async def __aexit__(self, *_exc: object) -> None:
        await self.close()

    async def close(self) -> None:
        await self._http.aclose()

    # ---- 内部请求 ----

    async def _get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        return await self._request("GET", path, params=params)

    async def _post(self, path: str, json_body: dict[str, Any] | None = None) -> Any:
        return await self._request("POST", path, json_body=json_body)

    async def _request(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        json_body: dict[str, Any] | None = None,
    ) -> Any:
        try:
            response = await self._http.request(method, path, params=params, json=json_body)
        except httpx.HTTPError as exc:
            raise JellyfinError("无法连接 Jellyfin 服务,请确认其已启动且地址正确") from exc
        if response.status_code in (401, 403):
            raise JellyfinAuthError()
        if response.status_code >= 400:
            raise JellyfinError(
                f"Jellyfin 接口返回异常状态 {response.status_code}",
                details={"path": path},
            )
        if not response.content:
            return None
        try:
            return response.json()
        except ValueError as exc:
            raise JellyfinError("Jellyfin 返回了无法解析的内容") from exc

    # ---- 系统与用户 ----

    async def system_info(self) -> JFSystemInfo:
        raw = await self._get("/System/Info")
        assert isinstance(raw, dict)
        return JFSystemInfo.from_raw(raw)

    async def users(self) -> list[JFUser]:
        raw = await self._get("/Users")
        assert isinstance(raw, list)
        return [JFUser.from_raw(user) for user in raw]

    # ---- 媒体库与媒体 ----

    async def libraries(self, user_id: str) -> list[Library]:
        raw = await self._get(f"/Users/{user_id}/Views")
        assert isinstance(raw, dict)
        return [map_library(JFItem.from_raw(item)) for item in raw.get("Items", [])]

    async def items_page(
        self,
        user_id: str,
        *,
        parent_id: str | None = None,
        include_types: str | None = None,
        start_index: int = 0,
        limit: int = 200,
        sort_by: str | None = None,
        sort_order: str | None = None,
        search_term: str | None = None,
    ) -> JFItemsPage:
        params: dict[str, Any] = {
            "Recursive": "true",
            "StartIndex": max(0, start_index),
            "Limit": min(max(1, limit), _MAX_PAGE_LIMIT),
            "Fields": "Path,Size,DateModified,Container,MediaSources",
        }
        types = include_types or ",".join(sorted(PLAYABLE_ITEM_TYPES))
        params["IncludeItemTypes"] = types
        if parent_id:
            params["ParentId"] = parent_id
        if sort_by:
            params["SortBy"] = sort_by
        if sort_order:
            params["SortOrder"] = sort_order
        if search_term:
            params["SearchTerm"] = search_term
        raw = await self._get(f"/Users/{user_id}/Items", params=params)
        assert isinstance(raw, dict)
        return JFItemsPage.from_raw(raw)

    async def media_page(
        self, user_id: str, *, parent_id: str | None = None, **kwargs: Any
    ) -> tuple[list[MediaItem], int]:
        """分页拉取并转换为统一 MediaItem。返回 (items, total)。"""
        page = await self.items_page(user_id, parent_id=parent_id, **kwargs)
        items = [
            map_media_item(raw, library_id=parent_id or "") for raw in page.items if raw.jellyfin_id
        ]
        return items, page.total_record_count

    async def media_item(self, user_id: str, item_id: str, library_id: str = "") -> MediaItem:
        raw = await self._get(f"/Users/{user_id}/Items/{item_id}")
        assert isinstance(raw, dict)
        item = JFItem.from_raw(raw)
        if not item.jellyfin_id:
            raise JellyfinError("Jellyfin 返回的媒体项缺少 ID")
        return map_media_item(item, library_id=library_id)

    # ---- 直连 URL(客户端直连 Jellyfin,中间层不转发) ----

    def video_stream_url(self, item_id: str) -> str:
        return item_stream_url(self._base_url, self._api_key, item_id)

    def image_original_url(self, item_id: str) -> str:
        return item_original_url(self._base_url, self._api_key, item_id)

    def thumbnail_url(self, item_id: str, max_width: int = 480) -> str:
        return item_thumbnail_url(self._base_url, self._api_key, item_id, max_width)

    # ---- 播放进度上报 ----

    async def report_progress(
        self, *, user_id: str, item_id: str, position_ms: int, is_paused: bool
    ) -> None:
        body = {
            "UserId": user_id,
            "ItemId": item_id,
            "PositionTicks": max(0, position_ms) * 10_000,
            "IsPaused": is_paused,
        }
        await self._post("/Sessions/Playing/Progress", json_body=body)


__all__ = [
    "JellyfinAuthError",
    "JellyfinClient",
    "item_original_url",
    "item_stream_url",
    "item_thumbnail_url",
]
