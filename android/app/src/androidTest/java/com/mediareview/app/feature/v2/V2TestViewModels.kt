package com.mediareview.app.feature.v2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.TokenProvider
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
 * 数据源默认 DEMO（DataStore 未写入），Server 相关依赖只做本地装配，测试期间不会发起任何网络请求。
 */
internal fun testHomeViewModel(
    repository: MediaRepository,
    searchHistory: SearchHistoryStore,
    statusStore: V2ServerStatusStore = V2ServerStatusStore(),
): V2HomeViewModel {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val profileStore = ServerProfileStore(context)
    val tokenProvider = TokenProvider()
    val apiFactory = ApiFactory(
        OkHttpClient(),
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
    )
}