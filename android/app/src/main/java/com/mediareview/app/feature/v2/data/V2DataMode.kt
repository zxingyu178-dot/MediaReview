package com.mediareview.app.feature.v2.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.v2ModeDataStore by preferencesDataStore(name = "mediareview_v2_data_source")

/**
 * V2 运行时数据源模式（Stage 8A 取代 Stage 1 写死的 `V2AppMode.CURRENT`）：
 *
 * - [DEMO]：APK 内置离线媒体（assets / res-raw），不连接任何电脑 Server 也能进入 App；
 * - [SERVER]：真实 MediaReview Server（UI 仍然完全不变，只有 Repository 实现不同）。
 */
enum class V2DataMode { DEMO, SERVER }

/**
 * 数据源模式的本地持久化（DataStore）+ 内存单例状态。
 *
 * 设计约束（Stage 8A）：
 * - 默认 [V2DataMode.DEMO]：安装 APK 后无需任何配置即可直接进入 App；
 * - 启动只读本地持久化值，不做任何网络请求、不等待 Server、不阻塞首页；
 * - [mode] 是同步可读的 StateFlow：Repository Router 用它决定委托对象，UI 用它决定展示。
 */
@Singleton
class V2DataModeStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyMode = stringPreferencesKey("data_mode")

    private val _mode = MutableStateFlow(V2DataMode.DEMO)
    val mode: StateFlow<V2DataMode> = _mode.asStateFlow()

    private val _resolved = MutableStateFlow(false)

    /** 是否已从持久化读取过（false 时 App 仍以默认 DEMO 渲染，不阻塞）。 */
    val resolved: StateFlow<Boolean> = _resolved.asStateFlow()

    /** 启动时读取持久化模式（只读本地，无网络）。 */
    suspend fun bootstrap(): V2DataMode {
        val stored = readPersisted()
        _mode.value = stored
        _resolved.value = true
        return stored
    }

    /** 当前模式：优先内存值（始终可用），未解析时读一次持久化。 */
    suspend fun current(): V2DataMode {
        if (_resolved.value) return _mode.value
        return bootstrap()
    }

    /** 切换数据源并持久化。 */
    suspend fun set(mode: V2DataMode) {
        context.v2ModeDataStore.edit { prefs -> prefs[keyMode] = mode.name }
        _mode.value = mode
        _resolved.value = true
    }

    private suspend fun readPersisted(): V2DataMode =
        context.v2ModeDataStore.data
            .map { prefs -> parse(prefs[keyMode]) }
            .first()

    private fun parse(raw: String?): V2DataMode =
        V2DataMode.entries.firstOrNull { it.name == raw } ?: V2DataMode.DEMO
}