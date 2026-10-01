package com.mediareview.app.feature.v2.releasenotes

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.releaseNotesDataStore by preferencesDataStore(name = "release_notes")

/**
 * 更新日志展示记录的持久化入口（Stage 8C.1 §40）。
 *
 * 用项目已有 DataStore 持久化 `last_seen_version_code`：
 * - null = 该设备从未展示过（首次安装 -> 允许展示一次，§41）；
 * - 等于当前 versionCode = 同版本第二次启动不再弹（§39）。
 */
interface ReleaseNotesDataSource {

    /** 上次已展示的版本号；null = 从未展示。 */
    suspend fun lastSeenVersionCode(): Int?

    /** 记录当前版本已展示（用户关闭更新日志后调用）。 */
    suspend fun saveLastSeenVersionCode(versionCode: Int)
}

/** DataStore 实现（禁止 SharedPreferences 临时写一下，§40）。 */
class ReleaseNotesStore(private val context: Context) : ReleaseNotesDataSource {

    private val keyLastSeenVersionCode = intPreferencesKey("last_seen_version_code")

    override suspend fun lastSeenVersionCode(): Int? =
        context.releaseNotesDataStore.data.map { it[keyLastSeenVersionCode] }.first()

    override suspend fun saveLastSeenVersionCode(versionCode: Int) {
        context.releaseNotesDataStore.edit { prefs ->
            prefs[keyLastSeenVersionCode] = versionCode
        }
    }
}