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
    )


if __name__ == "__main__":
    main()
