package com.mediareview.app.core.network

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前已配对 token 的内存单例。
 * 由 [com.mediareview.app.feature.connect.data.PairingRepository] 在配对成功后更新;
 * [AuthInterceptor] 每次请求读取最新值,避免为每个 token 重建 HttpClient。
 */
@Singleton
class TokenProvider @Inject constructor() {
    @Volatile
    var token: String = ""

    fun set(newToken: String) {
        token = newToken
    }

    fun clear() {
        token = ""
    }
}