package com.mediareview.app.feature.v2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.TokenProvider
import com.mediareview.app.core.network.AuthInterceptor
import com.mediareview.app.core.pairing.PairingRepository
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.data.V2DataModeStore
import com.mediareview.app.feature.v2.data.server.V2ServerHealthMonitor
import com.mediareview.app.feature.v2.data.server.V2ServerSessionBootstrap
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * androidTest 共用的 V2HomeViewModel 构造：
 * 数据源模式从真实 DataStore bootstrap；测试须自行保存/恢复持久化状态。
 * authenticatedApi=true 时使用正式 AuthInterceptor，凭据由正式 bootstrap 恢复。
 */
internal fun testHomeViewModel(
    repository: MediaRepository,
    searchHistory: SearchHistoryStore,
    statusStore: V2ServerStatusStore = V2ServerStatusStore(),
    authenticatedApi: Boolean = false,
): V2HomeViewModel {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val profileStore = ServerProfileStore(context)
    val tokenProvider = TokenProvider()
    val authenticatedHttp = if (authenticatedApi) {
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(tokenProvider)).build()
    } else OkHttpClient()
    val apiFactory = ApiFactory(
        authenticatedHttp,
        OkHttpClient(),
        Json { ignoreUnknownKeys = true },
    )
    return V2HomeViewModel(
        repository = repository,
        searchHistory = searchHistory,
        modeStore = V2DataModeStore(context),
        bootstrap = V2ServerSessionBootstrap(profileStore, tokenProvider),
        healthMonitor = V2ServerHealthMonitor(profileStore, apiFactory, statusStore),
        statusStore = statusStore,
        pairingRepository = PairingRepository(profileStore, tokenProvider, apiFactory),
        homeDisplayModeStore = com.mediareview.app.feature.v2.data.HomeDisplayModeStore(context),
    )
}
