package com.mediareview.app.feature.v2.organize.delete

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediareview.app.core.datastore.ServerProfileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import okio.ByteString.Companion.encodeUtf8

private val Context.pendingDeleteData by preferencesDataStore("pending_delete_batches")

interface DeleteRecoveryJournal {
    suspend fun scope(): String
    suspend fun read(scope: String): String?
    suspend fun save(scope: String, nonce: String)
    suspend fun acknowledge(scope: String, nonce: String)
}

/** 只保存nonce；服务器+凭据的哈希隔离批次，凭据明文不落入此存储。 */
@Singleton
class PendingDeleteStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: ServerProfileStore,
) : DeleteRecoveryJournal {
    override suspend fun scope(): String {
        val profile = profiles.current()
        return (profile.baseUrl.trimEnd('/') + "\u0000" + profile.token).encodeUtf8().sha256().hex()
    }

    override suspend fun read(scope: String): String? =
        context.pendingDeleteData.data.first()[stringPreferencesKey(scope)]

    override suspend fun save(scope: String, nonce: String) {
        context.pendingDeleteData.edit {
            val key = stringPreferencesKey(scope)
            check(it[key] == null || it[key] == nonce) { "请先核对上一批删除结果" }
            it[key] = nonce
        }
    }

    override suspend fun acknowledge(scope: String, nonce: String) {
        context.pendingDeleteData.edit {
            val key = stringPreferencesKey(scope)
            if (it[key] == nonce) it.remove(key)
        }
    }
}
