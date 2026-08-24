"""应用工厂与启动入口。

启动流程:
1. 解析配置(data_root 环境变量 -> config.json -> 默认值)
2. 建立数据目录结构
3. 初始化日志
4. 运行数据库迁移(Alembic 升级到 head)
5. 挂载 API 路由与异常处理

模块级 `app` 惰性构造,避免导入即触碰真实数据目录(测试安全)。
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app import __version__
from app.admin import register_admin
from app.api.v1 import api_router
from app.core.config import AppConfig, load_config, resolve_data_root
from app.core.errors import AppError, ValidationFailedError
from app.core.logging import configure_logging, get_logger
from app.core.paths import PathManager
from app.core.request_id import RequestIdMiddleware, get_request_id
from app.core.responses import Envelope, ErrorBody, error_response
from app.db.migrate import run_migrations
from app.db.session import Database
from app.media.ffmpeg import FfmpegExecutor
from app.services import discovery, hash_tasks, media_index, sprite
from app.services.tasks import TaskManager

logger = get_logger("main")

# 框架层 HTTP 错误到稳定错误码的映射;消息统一中文
_HTTP_STATUS_MAP = {
    400: ("BAD_REQUEST", "请求参数错误"),
    401: ("UNAUTHORIZED", "设备未配对或凭据失效"),
    403: ("FORBIDDEN", "没有权限执行该操作"),
    404: ("NOT_FOUND", "资源不存在"),
    405: ("METHOD_NOT_ALLOWED", "请求方法不支持"),
    413: ("PAYLOAD_TOO_LARGE", "请求体过大"),
    422: ("VALIDATION_ERROR", "请求参数校验失败"),
    429: ("TOO_MANY_REQUESTS", "请求过于频繁"),
    500: ("INTERNAL_ERROR", "服务器内部错误"),
    502: ("UPSTREAM_ERROR", "上游服务异常"),
    503: ("SERVICE_UNAVAILABLE", "服务暂不可用"),
}


def _build_app(settings: AppConfig, *, run_db_migrations: bool) -> FastAPI:
    paths = PathManager(resolve_data_root(settings.storage.data_root))
    paths.ensure()
    configure_logging(paths.logs_dir)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        database = Database(paths.database_path)
        app.state.database = database
        if run_db_migrations:
            run_migrations(f"sqlite:///{paths.database_path}")

        # 后台任务引擎: 注册雪碧图生成处理器;features.sprites 关闭时跳过
        task_manager = TaskManager(database)
        task_manager.register(
            media_index.TASK_TYPE_MEDIA_REFRESH,
            media_index.make_media_refresh_handler(settings.jellyfin),
        )
        if settings.features.sprites:
            executor = FfmpegExecutor(settings.storage.ffmpeg_dir)
            app.state.media_executor = executor
            task_manager.register(
                sprite.TASK_TYPE_SPRITE, sprite.make_sprite_handler(executor, paths.sprites_dir)
            )
        # 重复检测哈希处理器(features.duplicate_scan 关闭时跳过)
        if settings.features.duplicate_scan:
            task_manager.register(
                hash_tasks.TASK_TYPE_DUPLICATE_HASH, hash_tasks.make_hash_handler()
            )
        app.state.task_manager = task_manager
        await task_manager.start()

        # 局域网自动发现应答端(不阻塞主流程;组播不可用时手机可手动 IP 兜底)
        responder = discovery.DiscoveryResponder(port=settings.server.port)
        should_run = (
            settings.features.sprites
            or settings.features.duplicate_scan
            or settings.security.pairing_required
        )
        if should_run:
            responder.start()
        app.state.discovery_responder = responder

        logger.info("MediaReview Server %s 启动完成, 数据目录: %s", __version__, paths.data_root)
        try:
            yield
        finally:
            responder.stop()
            await task_manager.stop()
            database.dispose()
            logger.info("MediaReview Server 已停止")

    app = FastAPI(
        title="MediaReview Server",
        description="个人局域网媒体浏览与批阅工具 · 中间层服务",
        version=__version__,
        lifespan=lifespan,
        docs_url="/api/docs",
        redoc_url=None,
        openapi_url="/api/openapi.json",
    )
    app.state.settings = settings
    app.state.paths = paths

    app.add_middleware(RequestIdMiddleware)
    app.include_router(api_router)
    register_admin(app)
    _install_exception_handlers(app)
    return app


def _install_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def _handle_app_error(_: Request, exc: AppError) -> JSONResponse:
        return error_response(exc)

    @app.exception_handler(StarletteHTTPException)
    async def _handle_http_exception(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        code, message = _HTTP_STATUS_MAP.get(exc.status_code, ("INTERNAL_ERROR", "服务器内部错误"))
        body = Envelope(
            success=False,
            data=None,
            error=ErrorBody(code=code, message=message),
            request_id=get_request_id(),
        )
        return JSONResponse(status_code=exc.status_code, content=body.model_dump())

    @app.exception_handler(RequestValidationError)
    async def _handle_validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        return error_response(
            ValidationFailedError(details={"errors": jsonable_encoder(exc.errors())})
        )

    @app.exception_handler(Exception)
    async def _handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        logger.exception("未处理异常: %s", exc)
        return error_response(AppError())


def create_app(settings: AppConfig | None = None, *, run_db_migrations: bool = True) -> FastAPI:
    """构造 FastAPI 应用。测试可注入自定义 settings 并跳过迁移。"""
    return _build_app(settings or load_config(), run_db_migrations=run_db_migrations)


_app: FastAPI | None = None


def __getattr__(name: str) -> FastAPI:
    # 惰性构造,供 `uvicorn app.main:app` 使用
    if name == "app":
        global _app
        if _app is None:
            _app = create_app()
        return _app
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
