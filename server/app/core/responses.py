"""统一响应包结构。

成功: {"success": true, "data": ..., "error": null, "request_id": "..."}
失败: {"success": false, "data": null, "error": {code, message, details}, "request_id": "..."}
"""

from __future__ import annotations

from typing import Any

from fastapi.responses import JSONResponse
from pydantic import BaseModel

from app.core.errors import AppError
from app.core.request_id import get_request_id


class ErrorBody(BaseModel):
    code: str
    message: str
    details: dict[str, Any] | None = None


class Envelope[T](BaseModel):
    success: bool = True
    data: T | None = None
    error: ErrorBody | None = None
    request_id: str = ""


def ok(data: Any = None) -> Envelope:
    """构造成功响应包,自动注入当前请求 request_id。"""
    return Envelope(success=True, data=data, error=None, request_id=get_request_id())


def error_body(err: AppError) -> ErrorBody:
    return ErrorBody(code=err.code, message=err.message, details=err.details)


def error_response(err: AppError) -> JSONResponse:
    body = Envelope(
        success=False,
        data=None,
        error=error_body(err),
        request_id=get_request_id(),
    )
    return JSONResponse(status_code=err.status_code, content=body.model_dump())


__all__ = ["Envelope", "ErrorBody", "error_body", "error_response", "ok"]
