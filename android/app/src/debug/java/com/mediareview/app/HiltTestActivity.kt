package com.mediareview.app

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Compose instrumentation 测试宿主（Stage 8B.1 §25）。
 *
 * 渲染 `V2MainScreen` 会解析 `hiltViewModel()`（批阅 / 播放器），
 * 旧的裸 `ComponentActivity` 没有 Hilt 组件工厂，会直接抛错
 * （Stage 8B 里 favoritesOpenPlayerAndBackReturnsToFavorites 等两个失败）。
 *
 * 关键设计（本阶段踩过的两个坑都记录在这里）：
 * - 必须位于 **debug 源集** 并声明在 debug manifest：测试 Activity 与 App 同进程
 *   （放在 androidTest manifest 会被解析到测试 APK 进程，报
 *   "Intent ... resolved to different process com.mediareview.app.test"）；
 * - **不使用 HiltTestApplication**：instrumentation 保持生产 [MediaReviewApp]
 *   （它负责 `GsyPlayerInitializer` 把 GSY 内核固定为 Exo2；换成 HiltTestApplication
 *   会让 GSY 回退到 IjkPlayerManager 并因缺失 libijkffmpeg.so 崩溃）。
 *
 * Release APK 不包含本类（debug 源集），不影响正式产物。
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()