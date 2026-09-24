package com.mediareview.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.hilt.navigation.compose.hiltViewModel
import com.mediareview.app.feature.v2.V2MainScreen
import com.mediareview.app.feature.v2.home.V2HomeViewModel
import com.mediareview.app.ui.theme.MediaReviewTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediaReviewTheme {
                // Stage 8A：V2 是唯一正式 UI。
                // 数据源（DEMO / SERVER）是运行时状态（DataStore）：
                // - 启动直接进入 V2，不被 ConnectScreen / 健康检查 / 配对流程阻塞；
                // - 接真实 Server 时只替换 Repository 实现，绝不回退到 1.1 旧 UI。
                val vm: V2HomeViewModel = hiltViewModel()
                V2MainScreen(vm)
            }
        }
    }
}