"""统一业务错误体系。

错误码是稳定契约,只增不改语义。HTTP 状态码与错误码一一对应。
"""

from __future__ import annotations

from typing import Any


class AppError(Exception):
    """业务错误基类。message 面向用户,必须中文。"""

    status_code: int = 500
    code: str = "INTERNAL_ERROR"
    default_message: str = "服务器内部错误"

    def __init__(
        self, message: str | None = None, *, details: dict[str, Any] | None = None
    ) -> None:
        self.message = message or self.default_message
        self.details = details
        super().__init__(self.message)


class ConfigError(AppError):
    status_code = 500
    code = "CONFIG_ERROR"
    default_message = "服务器配置异常"


class BadRequestError(AppError):
    status_code = 400
    code = "BAD_REQUEST"
    default_message = "请求参数错误"


class ValidationFailedError(BadRequestError):
    status_code = 422
    code = "VALIDATION_ERROR"
    default_message = "请求参数校验失败"


class UnauthorizedError(AppError):
    status_code = 401
    code = "UNAUTHORIZED"
    default_message = "设备未配对或凭据失效"


class ForbiddenError(AppError):
    status_code = 403
    code = "FORBIDDEN"
    default_message = "没有权限执行该操作"


class NotFoundError(AppError):
    status_code = 404
    code = "NOT_FOUND"
    default_message = "资源不存在"


class MediaNotFoundError(NotFoundError):
    code = "MEDIA_NOT_FOUND"
    default_message = "媒体不存在"


class ConflictError(AppError):
    status_code = 409
    code = "CONFLICT"
    default_message = "状态冲突"


class TaskCancelledError(AppError):
    status_code = 409
    code = "TASK_CANCELLED"
    default_message = "任务已取消"


class JellyfinError(AppError):
    """Jellyfin 上游错误(连接失败、响应异常等)。"""

    status_code = 502
    code = "JELLYFIN_ERROR"
    default_message = "Jellyfin 服务不可用"


__all__ = [
    "AppError",
    "BadRequestError",
    "ConfigError",
    "ConflictError",
    "ForbiddenError",
    "JellyfinError",
    "MediaNotFoundError",
    "NotFoundError",
    "TaskCancelledError",
    "UnauthorizedError",
    "ValidationFailedError",
]
