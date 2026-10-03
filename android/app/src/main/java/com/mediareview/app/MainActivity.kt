package com.mediareview.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
        // Stage 8D.1 §27/§28：App 是深色主题，必须用**深色系统栏样式**（浅色图标）。
        // 默认 enableEdgeToEdge() 会按系统主题推导，深色背景下可能出现黑色状态栏/导航栏
        // 图标（时间/信号/电量看不见）。这里显式声明 dark 样式：
        // - 状态栏 / 导航栏图标 = 浅色；
        // - 透明 scrim，保持 edge-to-edge；Player 全屏时由播放器自行隐藏系统栏，
        //   退出全屏回到本 Activity 默认样式（浅色图标）不变。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
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