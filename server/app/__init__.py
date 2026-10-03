"""MediaReview 中间层服务。"""

__version__ = "1.2.0"

# API Contract:给 App 判断兼容性的正式字段(与人类可读的 __version__ 分开管理)。
# - Server 版本给"给人看 / 部署 / 回滚"用;
# - API Contract 给"App 判断兼容性"用;
# 只修 Bug 的小版本(1.2.1 / 1.2.2)可保持同一 Contract,不强制 App 升级。
# 变更契约(新增/修改 App 依赖的接口语义)时才递增。
SERVER_API_CONTRACT = 2

# 本 Contract 下 Server 明确提供的正式能力(供 App 未来做渐进功能判断;
# 当前兼容 Gate 仍以 SERVER_API_CONTRACT 为主,不实现复杂 Feature Flag)。
SERVER_CAPABILITIES: tuple[str, ...] = (
    "review_session",
    "review_nearest",
    "organize",
    "delete_nonce",
    "duplicates_paged",
    "library_selection",
)
