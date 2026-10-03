package com.mediareview.app

import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore

/**
 * Stage 8D.2 §13/§15：Server 业务 Guard 要求状态为 **Online** 才允许发请求。
 *
 * 直接调用 Server 仓储的合同测试用它构造"已确认兼容"的状态；
 * 需要验证未就绪 / 不兼容 / 离线语义的测试则自行构造并显式设置状态。
 */
internal fun onlineServerStatus(
    version: String = "1.2.1",
    contract: Int = 2,
): V2ServerStatusStore = V2ServerStatusStore().apply { recordCompatible(version, contract) }
