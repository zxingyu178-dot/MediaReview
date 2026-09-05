"""MediaReview Server 启动入口(供 uvicorn 直接运行或 PyInstaller 打包)。"""

from __future__ import annotations

import uvicorn

from app.core.config import load_config


def main() -> None:
    cfg = load_config()
    uvicorn.run(
        "app.main:app",
        host=cfg.server.host,
        port=cfg.server.port,
        log_level="info",
        access_log=False,
        # 不让 uvicorn 的 dictConfig 覆盖应用日志配置: 默认 log_config 会
        # disable_existing_loggers=True, 把 configure_logging 挂载的 "app"
        # logger 禁用, 导致 server.log 不落盘(服务健康但无日志)。
        log_config=None,
    )


if __name__ == "__main__":
    main()
