package com.mediareview.app.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.serverDataStore by preferencesDataStore(name = "media_review_server")

/**
 * 服务器配置本地持久化(DataStore):
 * 记录已连接服务器地址与配对 token,供后续请求携带。
 *
 * token 以密文写入并非强制;V1 局域网安全边界内先持久化,后续可升级到 EncryptedSharedPreferences。
 */
data class ServerProfile(
    val baseUrl: String = "",
    val token: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
    val isPaired: Boolean get() = token.isNotBlank()
}

class ServerProfileStore(private val context: Context) {

    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyToken = stringPreferencesKey("token")
    private val keyDeviceId = stringPreferencesKey("device_id")

    val profile: Flow<ServerProfile> = context.serverDataStore.data.map { prefs ->
        ServerProfile(
            baseUrl = prefs[keyBaseUrl].orEmpty(),
            token = prefs[keyToken].orEmpty(),
        )
    }

    suspend fun current(): ServerProfile = profile.first()

    suspend fun saveBaseUrl(baseUrl: String) {
        context.serverDataStore.edit { prefs ->
            prefs[keyBaseUrl] = baseUrl
        }
    }

    suspend fun savePairing(token: String, deviceId: String) {
        context.serverDataStore.edit { prefs ->
            prefs[keyToken] = token
            prefs[keyDeviceId] = deviceId
        }
    }

    suspend fun deviceId(): String {
        // 首次调用若无 ID,生成稳定 UUID 并持久化,此后恒定。deviceId 不属于"配对凭据",
        // 仅用于标识本客户端;即使取消配对也不随之清除。
        var id = context.serverDataStore.data.map { it[keyDeviceId].orEmpty() }.first()
        if (id.isBlank()) {
            id = java.util.UUID.randomUUID().toString()
            context.serverDataStore.edit { prefs -> prefs[keyDeviceId] = id }
        }
        return id
    }

    suspend fun clear() {
        context.serverDataStore.edit { prefs ->
            prefs.remove(keyBaseUrl)
            prefs.remove(keyToken)
        }
    }
}