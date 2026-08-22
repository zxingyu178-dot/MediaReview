"""请求级 request_id 上下文与中间件。

每个请求生成或继承一个 request_id,用于:
- 统一响应包 request_id 字段
- 响应头 X-Request-ID
- 日志关联
"""

from __future__ import annotations

import re
import uuid
from contextvars import ContextVar

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

request_id_var: ContextVar[str] = ContextVar("request_id", default="-")

_HEADER = "X-Request-ID"
# 只接受安全的请求头值,防止头注入与日志污染
_VALID_INCOMING = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def new_request_id() -> str:
    return uuid.uuid4().hex[:16]


def get_request_id() -> str:
    return request_id_var.get()


class RequestIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        incoming = request.headers.get(_HEADER, "")
        request_id = incoming if _VALID_INCOMING.match(incoming) else new_request_id()
        token = request_id_var.set(request_id)
        try:
            response = await call_next(request)
        finally:
            request_id_var.reset(token)
        response.headers[_HEADER] = request_id
        return response
