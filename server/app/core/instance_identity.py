"""实例身份识别：区分 Windows Service 正式实例与手动/交互实例。

背景(p5cb.1 Task10-12):
    手动 MediaReviewServer.exe 会占用 8766 端口并返回 HTTP 200。若 Control
    Hub 只探测 HTTP 200,当 AIHome.MediaReview 服务启动失败/立即退出时,可能
    误认为服务已就绪。因此 health 必须携带实例身份,由 Control Hub 交叉校验:

        instance_mode == windows_service 且 lifecycle_target == AIHome.MediaReview

    才视为"正式服务实例"就绪。手动/交互实例绝不能伪装成服务身份。

身份来源(WinSW 正式服务环境注入的进程环境变量):
    MEDIAREVIEW_INSTANCE_MODE    = windows_service
    MEDIAREVIEW_LIFECYCLE_TARGET = AIHome.MediaReview

契约:
    - 未设置服务模式环境变量            → instance_mode=interactive, lifecycle_target=interactive
    - 设置 windows_service 且 target 有效 → instance_mode=windows_service, target 原样返回
    - 设置 windows_service 但未提供 target → instance_mode=invalid(不静默制造错误身份)
"""

from __future__ import annotations

import os

from pydantic import BaseModel

SERVICE_MODE = "windows_service"
INTERACTIVE_MODE = "interactive"
INVALID_MODE = "invalid"

_INSTANCE_MODE_ENV = "MEDIAREVIEW_INSTANCE_MODE"
_LIFECYCLE_TARGET_ENV = "MEDIAREVIEW_LIFECYCLE_TARGET"


class InstanceIdentity(BaseModel):
    service: str = "MediaReview"
    instance_mode: str = INTERACTIVE_MODE
    lifecycle_target: str = INTERACTIVE_MODE


def get_instance_identity() -> InstanceIdentity:
    """根据进程环境变量判定当前实例身份(每次调用实时读取,便于测试注入)。"""
    mode = os.environ.get(_INSTANCE_MODE_ENV, "").strip()
    target = os.environ.get(_LIFECYCLE_TARGET_ENV, "").strip()
    if mode == SERVICE_MODE:
        if target:
            # 仅显式设置了 service 模式与目标时才报告为正式服务实例
            return InstanceIdentity(instance_mode=SERVICE_MODE, lifecycle_target=target)
        # 声称是服务却没给 target: 明确标记 invalid,不让 Control Hub 被半截配置欺骗
        return InstanceIdentity(instance_mode=INVALID_MODE, lifecycle_target="")
    # 非服务模式(默认/手动/未知): 一律视为交互实例
    return InstanceIdentity(instance_mode=INTERACTIVE_MODE, lifecycle_target=INTERACTIVE_MODE)


__all__ = [
    "InstanceIdentity",
    "INTERACTIVE_MODE",
    "INVALID_MODE",
    "SERVICE_MODE",
    "get_instance_identity",
]
