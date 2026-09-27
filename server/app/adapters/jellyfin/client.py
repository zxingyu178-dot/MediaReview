"""Jellyfin REST 客户端。

- httpx.AsyncClient,超时与异常统一映射为 AppError 体系
- 只暴露 JF* 模型与统一 DTO,Jellyfin 原始 JSON 不出本包
- 视频只返回不含服务器凭据的 Jellyfin 直连 URL；图片由受控代理读取。
- 中间层绝不代理视频流。
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import quote

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
from app.core.config import (
    JellyfinConfig,
    ensure_jellyfin_url_excludes_api_key,
    normalize_jellyfin_base_url,
)
from app.core.errors import JellyfinError

_TIMEOUT = httpx.Timeout(10.0, connect=5.0)
# 拉取大列表时的单页上限,防止一次请求过重
_MAX_PAGE_LIMIT = 1000
_MAX_IMAGE_BYTES = 25 * 1024 * 1024
# 连接池: 媒体墙缩略图会连续发起数十个请求,必须复用到 Jellyfin 的 Keep-Alive 连接,
# 否则每个 thumbnail 都要重新 TCP + 认证握手。
_LIMITS = httpx.Limits(max_connections=32, max_keepalive_connections=16, keepalive_expiry=30.0)


def _path_segment(value: str) -> str:
    if not value:
        raise ValueError("Jellyfin ID 不能为空")
    return quote(value, safe="")


def item_stream_url(host: str, item_id: str) -> str:
    """构造不含凭据的 Jellyfin 视频地址；调用方必须明确认证仍未满足。"""
    base_url = normalize_jellyfin_base_url(host)
    return f"{base_url}/Videos/{_path_segment(item_id)}/stream?static=true"


def item_hls_url(host: str, item_id: str) -> str:
    """构造不含凭据的 HLS 回退播放列表地址(Direct Play 失败时的唯一一次回退)。

    H.264/AAC 转码参数保持最小集;认证由调用方通过 headers 携带设备级凭据。
    """
    base_url = normalize_jellyfin_base_url(host)
    return (
        f"{base_url}/Videos/{_path_segment(item_id)}/master.m3u8"
        f"?MediaSourceId={_path_segment(item_id)}&VideoCodec=h264&AudioCodec=aac"
    )


class JellyfinAuthError(JellyfinError):
    code = "JELLYFIN_AUTH_FAILED"
    default_message = "Jellyfin 认证失败,请检查 API Key"


@dataclass
class ImageStream:
    """上游图片流: 已通过状态/类型/长度校验,可边读边转发给客户端。

    安全性约束与 ``_image_request`` 完全一致(状态码、非图片内容、重定向、大小上限),
    区别只是不再把整张图缓冲进内存。
    """

    content_type: str
    content_length: int | None = None
    _response: Any = field(default=None, repr=False)
    _context: Any = field(default=None, repr=False)
    _max_bytes: int = _MAX_IMAGE_BYTES

    async def aiter_bytes(self) -> AsyncIterator[bytes]:
        total = 0
        async for chunk in self._response.aiter_bytes():
            total += len(chunk)
            if total > self._max_bytes:
                raise JellyfinError("Jellyfin 图片超过允许的大小")
            yield chunk

    async def aclose(self) -> None:
        if self._context is not None:
            await self._context.__aexit__(None, None, None)
            self._context = None


class JellyfinClient:
    def __init__(self, config: JellyfinConfig, transport: httpx.AsyncBaseTransport | None = None):
        self._config = config
        self._base_url = normalize_jellyfin_base_url(config.host)
        self._api_key = config.api_key.get_secret_value()
        ensure_jellyfin_url_excludes_api_key(self._base_url, self._api_key)
        self._http = httpx.AsyncClient(
            base_url=self._base_url,
            headers=self._auth_headers(),
            timeout=_TIMEOUT,
            transport=transport,
            limits=_LIMITS,
            follow_redirects=False,
            # V1 仅局域网/回环直连 Jellyfin,不采信环境代理(如 all_proxy=socks5),
            # 避免部署机存在代理环境变量时因缺少 socksio 等扩展而启动即失败。
            trust_env=False,
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

    async def _post(
        self,
        path: str,
        json_body: dict[str, Any] | None = None,
        params: dict[str, Any] | None = None,
    ) -> Any:
        return await self._request("POST", path, params=params, json_body=json_body)

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

    async def _image_request(
        self,
        path: str,
        *,
        params: dict[str, Any] | None = None,
    ) -> tuple[bytes, str]:
        """受控读取上游图片；凭据只在 Authorization header 中发送。"""
        try:
            async with self._http.stream(
                "GET",
                path,
                params=params,
                headers={"Accept": "image/*"},
                follow_redirects=False,
            ) as response:
                if response.status_code in (401, 403):
                    raise JellyfinAuthError()
                if 300 <= response.status_code < 400:
                    raise JellyfinError("Jellyfin 图片接口不允许重定向")
                if response.status_code >= 400:
                    raise JellyfinError(f"Jellyfin 图片接口返回异常状态 {response.status_code}")
                content_type = response.headers.get("Content-Type", "").partition(";")[0].strip()
                if not content_type.lower().startswith("image/"):
                    raise JellyfinError("Jellyfin 图片接口返回了非图片内容")
                content_length = response.headers.get("Content-Length")
                if content_length:
                    try:
                        if int(content_length) > _MAX_IMAGE_BYTES:
                            raise JellyfinError("Jellyfin 图片超过允许的大小")
                    except ValueError:
                        pass
                payload = bytearray()
                async for chunk in response.aiter_bytes():
                    payload.extend(chunk)
                    if len(payload) > _MAX_IMAGE_BYTES:
                        raise JellyfinError("Jellyfin 图片超过允许的大小")
                return bytes(payload), content_type
        except JellyfinError:
            raise
        except httpx.HTTPError as exc:
            raise JellyfinError("无法读取 Jellyfin 图片,请确认服务连接正常") from exc

    async def _open_image_stream(self, path: str) -> ImageStream:
        """打开上游图片流(不缓冲整图),校验规则与 _image_request 一致。"""
        context = self._http.stream(
            "GET",
            path,
            headers={"Accept": "image/*"},
            follow_redirects=False,
        )
        try:
            response = await context.__aenter__()
        except httpx.HTTPError as exc:
            raise JellyfinError("无法读取 Jellyfin 图片,请确认服务连接正常") from exc
        try:
            if response.status_code in (401, 403):
                raise JellyfinAuthError()
            if 300 <= response.status_code < 400:
                raise JellyfinError("Jellyfin 图片接口不允许重定向")
            if response.status_code >= 400:
                raise JellyfinError(f"Jellyfin 图片接口返回异常状态 {response.status_code}")
            content_type = response.headers.get("Content-Type", "").partition(";")[0].strip()
            if not content_type.lower().startswith("image/"):
                raise JellyfinError("Jellyfin 图片接口返回了非图片内容")
            declared = response.headers.get("Content-Length")
            content_length: int | None = None
            if declared:
                try:
                    content_length = int(declared)
                except ValueError:
                    content_length = None
                if content_length is not None and content_length > _MAX_IMAGE_BYTES:
                    raise JellyfinError("Jellyfin 图片超过允许的大小")
        except BaseException:
            await context.__aexit__(None, None, None)
            raise
        return ImageStream(
            content_type=content_type,
            content_length=content_length,
            _response=response,
            _context=context,
        )

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
        raw = await self._get(f"/Users/{_path_segment(user_id)}/Views")
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
        raw = await self._get(f"/Users/{_path_segment(user_id)}/Items", params=params)
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
        raw = await self._get(f"/Users/{_path_segment(user_id)}/Items/{_path_segment(item_id)}")
        assert isinstance(raw, dict)
        item = JFItem.from_raw(raw)
        if not item.jellyfin_id:
            raise JellyfinError("Jellyfin 返回的媒体项缺少 ID")
        return map_media_item(item, library_id=library_id)

    # ---- 不含凭据的视频直连 URL（中间层绝不转发视频） ----

    def video_stream_url(self, item_id: str, *, base_url: str | None = None) -> str:
        ensure_jellyfin_url_excludes_api_key(self._base_url, self._api_key)
        stream_url = item_stream_url(base_url or self._base_url, item_id)
        ensure_jellyfin_url_excludes_api_key(stream_url, self._api_key)
        return stream_url

    def hls_stream_url(self, item_id: str, *, base_url: str | None = None) -> str:
        ensure_jellyfin_url_excludes_api_key(self._base_url, self._api_key)
        url = item_hls_url(base_url or self._base_url, item_id)
        ensure_jellyfin_url_excludes_api_key(url, self._api_key)
        return url

    # ---- 设备级播放凭据(Task C) ----

    async def ensure_device_stream_key(self, key_name: str) -> str:
        """按名称读取/创建 Jellyfin 命名 key(幂等),返回 AccessToken。

        - 已存在:直接返回其 AccessToken,不重复创建;
        - 不存在:POST /Auth/Keys(app=key_name) 创建后回读;
        - 任何失败抛 JellyfinError,调用方必须 fail-closed。
        密钥值只在内存与调用方持久化中流转,不写日志。

        契约对照真实 Jellyfin:POST /Auth/Keys 的必需参数是 ``app``;
        GET /Auth/Keys 返回 ``Items[]`` 的字段是 ``AppName``。
        """
        keys = await self._get("/Auth/Keys")
        for item in (keys or {}).get("Items", []):
            if item.get("AppName") == key_name and item.get("AccessToken"):
                return str(item["AccessToken"])
        await self._post("/Auth/Keys", params={"app": key_name})
        keys = await self._get("/Auth/Keys")
        for item in (keys or {}).get("Items", []):
            if item.get("AppName") == key_name and item.get("AccessToken"):
                return str(item["AccessToken"])
        raise JellyfinError("Jellyfin 已创建播放凭据但无法回读,请检查其版本与权限")

    async def revoke_device_stream_key(self, key_name: str) -> bool:
        """按名称撤销 Jellyfin 命名 key;不存在时返回 False(幂等)。"""
        keys = await self._get("/Auth/Keys")
        for item in (keys or {}).get("Items", []):
            if item.get("AppName") == key_name and item.get("AccessToken"):
                token = str(item["AccessToken"])
                await self._request("DELETE", f"/Auth/Keys/{_path_segment(token)}")
                return True
        return False

    async def item_resume_position_ms(self, user_id: str, item_id: str) -> int:
        """读取 Jellyfin 侧续播位置(毫秒);任何失败返回 0,不阻塞播放。"""
        try:
            item = await self._get(
                f"/Users/{_path_segment(user_id)}/Items/{_path_segment(item_id)}"
            )
        except JellyfinError:
            return 0
        ticks = ((item or {}).get("UserData") or {}).get("PlaybackPositionTicks") or 0
        try:
            return max(0, int(ticks) // 10_000)
        except (TypeError, ValueError):
            return 0

    def _thumbnail_params(
        self, *, max_width: int, max_height: int | None, fill: bool
    ) -> dict[str, Any]:
        """缩略图尺寸参数: 媒体卡约 480x270,竖图不应传输 480x850 再由客户端裁剪。

        - 默认 maxWidth/maxHeight: Jellyfin 按比例缩放,不会放大;
        - fill=True 时改用 fillWidth/fillHeight,由 Jellyfin 生成真正贴合卡片的构图。
        """
        params: dict[str, Any] = {"quality": 80}
        if fill:
            params["fillWidth"] = max_width
            if max_height:
                params["fillHeight"] = max_height
        else:
            params["maxWidth"] = max_width
            if max_height:
                params["maxHeight"] = max_height
        return params

    async def thumbnail_image(
        self,
        item_id: str,
        *,
        max_width: int = 480,
        max_height: int | None = None,
        fill: bool = False,
    ) -> tuple[bytes, str]:
        return await self._image_request(
            f"/Items/{_path_segment(item_id)}/Images/Primary",
            params=self._thumbnail_params(max_width=max_width, max_height=max_height, fill=fill),
        )

    async def original_image(self, item_id: str) -> tuple[bytes, str]:
        return await self._image_request(f"/Items/{_path_segment(item_id)}/Download")

    async def open_original_stream(self, item_id: str) -> ImageStream:
        """打开原图上游流: 边读边转发,避免整张大图先缓冲进内存。"""
        return await self._open_image_stream(f"/Items/{_path_segment(item_id)}/Download")

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
    "ImageStream",
    "JellyfinAuthError",
    "JellyfinClient",
    "item_hls_url",
    "item_stream_url",
]
