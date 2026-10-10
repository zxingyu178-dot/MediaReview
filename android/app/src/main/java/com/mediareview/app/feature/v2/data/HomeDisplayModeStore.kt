package com.mediareview.app.feature.v2.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediareview.app.feature.v2.home.HomeDisplayMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private val Context.homeDisplayDataStore by preferencesDataStore(name = "v2_home_display")
private val homeDisplayModeKey = stringPreferencesKey("display_mode")

@Singleton
class HomeDisplayModeStore internal constructor(
    private val read: suspend () -> String?,
    private val write: suspend (String) -> Unit,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        read = { context.homeDisplayDataStore.data.first()[homeDisplayModeKey] },
        write = { raw -> context.homeDisplayDataStore.edit { it[homeDisplayModeKey] = raw }; Unit },
    )
    private val mutable = MutableStateFlow(HomeDisplayMode.TWO_COLUMN)
    private val stateLock = Any()
    private var selectionRevision = 0L
    val mode = mutable.asStateFlow()

    suspend fun bootstrap() {
        val initialRevision = synchronized(stateLock) { selectionRevision }
        val storedMode = HomeDisplayMode.fromStored(read())
        synchronized(stateLock) {
            // 启动读取期间的新选择优先，旧快照不能把布局切回去。
            if (selectionRevision == initialRevision) mutable.value = storedMode
        }
    }

    suspend fun set(mode: HomeDisplayMode) {
        // UI 当帧切换；持久化失败由调用方提示，不阻塞媒体浏览。
        synchronized(stateLock) {
            selectionRevision++
            mutable.value = mode
        }
        write(mode.name)
    }
}
