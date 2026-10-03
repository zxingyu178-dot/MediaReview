package com.mediareview.app.feature.v2

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediareview.app.HiltTestActivity
import com.mediareview.app.feature.v2.data.AlbumCoverStore
import com.mediareview.app.feature.v2.data.DemoMediaRepository
import com.mediareview.app.feature.v2.data.SearchHistoryStore
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.data.V2DataModeStore
import com.mediareview.app.feature.v2.data.server.V2ServerStatus
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
import com.mediareview.app.feature.v2.settings.V2DataSourceSheet
import com.mediareview.app.ui.theme.MediaReviewTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 8D.1 §13/§15/§36/§45：旧 Server 的 UI 提示（设备侧）。
 *
 * 兼容性逻辑本身由 JVM 合同测试覆盖；这里验证用户真正看到的是
 * 「服务器版本过旧」而不是「服务器离线 / 认证失效」，并给出双方版本。
 */
@RunWith(AndroidJUnit4::class)
class Stage8D1CompatibilityUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<HiltTestActivity>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 其它测试默认 DEMO；本类需要 SERVER 才显示服务器分区。
        runBlocking { V2DataModeStore(context).set(V2DataMode.SERVER) }
    }

    @After
    fun tearDown() {
        runBlocking { V2DataModeStore(context).set(V2DataMode.DEMO) }
    }

    @Test
    fun outdatedServerShowsExplicitMessageWithVersions() {
        val statusStore = V2ServerStatusStore()
        val repository = DemoMediaRepository(context, AlbumCoverStore(context))
        val vm = testHomeViewModel(repository, SearchHistoryStore(context), statusStore)

        compose.setContent {
            MediaReviewTheme {
                V2DataSourceSheet(vm = vm, onDismiss = {})
            }
        }

        // 等待 ViewModel 的启动探测结束，再把状态置为"版本过旧"（避免被 Unconfigured 覆盖）。
        compose.waitUntil(timeoutMillis = 10_000) { vm.dataMode.value == V2DataMode.SERVER }
        compose.waitUntil(timeoutMillis = 10_000) {
            statusStore.status.value != V2ServerStatus.Probing
        }
        statusStore.recordIncompatible("1.1.0", 1)
        compose.waitUntil(timeoutMillis = 5_000) {
            statusStore.status.value == V2ServerStatus.Incompatible
        }

        // 「服务器版本过旧」同时出现在状态标签与提示卡片中，这里断言"至少存在并可显示"。
        compose.onAllNodesWithText("服务器版本过旧").onFirst().assertIsDisplayed()
        compose.onNodeWithText("当前电脑端：1.1.0").assertIsDisplayed()
        compose.onNodeWithText("手机需要：API Contract 2").assertIsDisplayed()
        compose.onNodeWithText("请升级 MediaReview Server").assertIsDisplayed()
    }
}
