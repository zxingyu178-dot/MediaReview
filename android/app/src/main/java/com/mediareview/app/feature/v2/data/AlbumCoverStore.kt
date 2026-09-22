package com.mediareview.app.feature.v2.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.albumCoverDataStore by preferencesDataStore(name = "v2_album_covers")

/**
 * 用户自选相册封面持久化（DataStore Preferences）。
 * 保存 folderId → coverMediaId，App 重启后封面选择仍然保留；
 * 未来接入 Server 时作为用户本地偏好，不修改原媒体文件。
 */
@Singleton
open class AlbumCoverStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun key(folderId: String) = stringPreferencesKey("cover_$folderId")

    /** 读取用户手动设置的封面（无则为 null）。 */
    suspend fun coverFor(folderId: String): String? =
        context.albumCoverDataStore.data.first()[key(folderId)]

    /** 读取全部已设置封面（folderId → mediaId）。 */
    suspend fun allCovers(): Map<String, String> {
        val prefs = context.albumCoverDataStore.data.first()
        return prefs.asMap().mapNotNull { (k, v) ->
            val s = k.name
            if (s.startsWith("cover_")) s.removePrefix("cover_") to v.toString() else null
        }.toMap()
    }

    /** 设置封面；mediaId 为 null 表示清除。 */
    suspend fun setCover(folderId: String, mediaId: String?) {
        context.albumCoverDataStore.edit { prefs ->
            if (mediaId == null) prefs.remove(key(folderId)) else prefs[key(folderId)] = mediaId
        }
    }
}