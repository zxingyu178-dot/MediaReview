"""Jellyfin 客户端生命周期管理: 进程级共享连接池。

阶段 8A.1 背景: 原实现把 ``JellyfinClient`` 放在 FastAPI 依赖里
(``async with JellyfinClient(...) as client: yield client``),
导致**每个 thumbnail 请求都新建一个 httpx.AsyncClient**,
连接池无法跨请求复用,媒体墙的数十张封面会反复做 TCP + 认证握手。

约定:
- 应用启动时创建一次,所有请求共享同一个 ``httpx.AsyncClient``(Keep-Alive 连接池);
- Jellyfin host / api key 运行时变化时,关闭旧客户端并按新配置重建,绝不继续用旧凭据;
- 服务关闭时释放。
"""

from __future__ import annotations

import asyncio

from app.adapters.jellyfin.client import JellyfinClient
from app.core.config import JellyfinConfig


def _fingerprint(config: JellyfinConfig) -> tuple[str, str, str]:
    return (config.host, config.api_key.get_secret_value(), config.user_id or "")


class JellyfinClientManager:
    """持有唯一的 Jellyfin 客户端;配置变化时原子替换。"""

    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._client: JellyfinClient | None = None
        self._fingerprint: tuple[str, str, str] | None = None
        # 诊断/测试用: 实际创建过的客户端数量。共享复用时应保持为 1。
        self.created_clients = 0
        self.closed_clients = 0

    async def get(self, config: JellyfinConfig) -> JellyfinClient:
        fingerprint = _fingerprint(config)
        async with self._lock:
            if self._client is not None and self._fingerprint == fingerprint:
                return self._client
            if self._client is not None:
                await self._client.close()
                self.closed_clients += 1
            self._client = JellyfinClient(config)
            self._fingerprint = fingerprint
            self.created_clients += 1
            return self._client

    async def aclose(self) -> None:
        async with self._lock:
            if self._client is not None:
                await self._client.close()
                self.closed_clients += 1
                self._client = None
                self._fingerprint = None


__all__ = ["JellyfinClientManager"]
