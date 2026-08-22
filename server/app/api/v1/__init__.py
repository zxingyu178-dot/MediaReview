"""v1 路由聚合。"""

from __future__ import annotations

from fastapi import APIRouter

from app.api.v1 import (
    cache,
    delete_queue,
    duplicates,
    favorites,
    jellyfin,
    libraries,
    media,
    pairing,
    review,
    system,
)

api_router = APIRouter(prefix="/api/v1")
api_router.include_router(system.router)
api_router.include_router(jellyfin.router)
api_router.include_router(libraries.router)
api_router.include_router(media.router)
api_router.include_router(cache.router)
api_router.include_router(review.router)
api_router.include_router(favorites.router)
api_router.include_router(delete_queue.router)
api_router.include_router(duplicates.router)
api_router.include_router(pairing.router)

__all__ = ["api_router"]
