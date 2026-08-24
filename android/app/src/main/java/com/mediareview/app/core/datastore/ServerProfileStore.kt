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
 * token 只以 Android Keystore-backed AES-GCM 密文持久化；base URL 与 installation ID 留在 DataStore。
 */
data class ServerProfile(
    val baseUrl: String = "",
    val token: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
    val isPaired: Boolean get() = token.isNotBlank()
}

class ServerProfileStore(
    private val context: Context,
    private val crypto: CredentialCipher = AndroidKeystoreCredentialCipher(),
) {

    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyToken = stringPreferencesKey("token")
    private val keyEncryptedToken = stringPreferencesKey("token_ciphertext")
    private val keyDeviceId = stringPreferencesKey("device_id")

    val profile: Flow<ServerProfile> = context.serverDataStore.data.map { prefs ->
        ServerProfile(
            baseUrl = prefs[keyBaseUrl].orEmpty(),
            token = prefs[keyEncryptedToken]?.let { runCatching { crypto.decrypt(it) }.getOrDefault("") }.orEmpty(),
        )
    }

    suspend fun current(): ServerProfile {
        val prefs = context.serverDataStore.data.first()
        val migration = migrateCredential(
            encryptedToken = prefs[keyEncryptedToken].orEmpty(),
            plaintextToken = prefs[keyToken].orEmpty(),
            crypto = crypto,
        )
        if (migration.removePlaintext) {
            context.serverDataStore.edit { mutable ->
                if (migration.encryptedToken.isNotBlank()) {
                    mutable[keyEncryptedToken] = migration.encryptedToken
                }
                mutable.remove(keyToken)
            }
        }
        return ServerProfile(prefs[keyBaseUrl].orEmpty(), migration.token)
    }

    suspend fun saveBaseUrl(baseUrl: String) {
        context.serverDataStore.edit { prefs ->
            prefs[keyBaseUrl] = baseUrl
        }
    }

    suspend fun savePairing(token: String) {
        context.serverDataStore.edit { prefs ->
            prefs[keyEncryptedToken] = crypto.encrypt(token)
            prefs.remove(keyToken)
        }
    }

    suspend fun deviceId(): String {
        // 首次调用若无 ID,生成稳定 UUID 并持久化,此后恒定。deviceId 不属于"配对凭据",
        // 仅用于标识本客户端;即使取消配对也不随之清除。
        var id = context.serverDataStore.data.map { it[keyDeviceId].orEmpty() }.first()
        if (id.isBlank()) {
            id = stableInstallationId(id) { java.util.UUID.randomUUID().toString() }
            context.serverDataStore.edit { prefs -> prefs[keyDeviceId] = id }
        }
        return id
    }

    suspend fun clear() {
        context.serverDataStore.edit { prefs ->
            prefs.remove(keyBaseUrl)
            prefs.remove(keyToken)
            prefs.remove(keyEncryptedToken)
        }
    }
}
