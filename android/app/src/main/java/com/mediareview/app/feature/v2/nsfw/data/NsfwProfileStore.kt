package com.mediareview.app.feature.v2.nsfw.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediareview.app.core.datastore.AndroidKeystoreCredentialCipher
import com.mediareview.app.core.datastore.CredentialCipher
import com.mediareview.app.core.datastore.InstallationIdCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.nsfwStudioDataStore by preferencesDataStore(name = "nsfw_studio_profile")

/**
 * NSFW Studio 独立配对配置（与 MediaReview Server 的 DataStore 完全分开）。
 *
 * - DataStore 名称：`nsfw_studio_profile`（MediaReview 使用 `media_review_server`）；
 * - token 只保存 Android Keystore-backed AES-GCM 密文，且使用独立 key alias；
 * - installation ID 独立生成，不复用 MediaReview 的 device id。
 */
data class NsfwProfile(
    val baseUrl: String = "",
    val token: String = "",
    val credentialRejected: Boolean = false,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
    val isPaired: Boolean get() = token.isNotBlank()
}

class NsfwProfileStore(
    private val context: Context,
    private val crypto: CredentialCipher = AndroidKeystoreCredentialCipher("nsfw_studio_pairing_token_v1"),
) {
    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyTokenCiphertext = stringPreferencesKey("token_ciphertext")
    private val keyInstallationId = stringPreferencesKey("installation_id")

    private val installationIds = InstallationIdCoordinator(
        read = { context.nsfwStudioDataStore.data.map { it[keyInstallationId].orEmpty() }.first() },
        write = { id -> context.nsfwStudioDataStore.edit { prefs -> prefs[keyInstallationId] = id } },
        generator = { java.util.UUID.randomUUID().toString() },
    )

    val profile: Flow<NsfwProfile> = context.nsfwStudioDataStore.data.map { prefs ->
        NsfwProfile(
            baseUrl = prefs[keyBaseUrl].orEmpty(),
            token = prefs[keyTokenCiphertext]?.let { runCatching { crypto.decrypt(it) }.getOrDefault("") }.orEmpty(),
        )
    }

    suspend fun current(): NsfwProfile {
        val prefs = context.nsfwStudioDataStore.data.first()
        val ciphertext = prefs[keyTokenCiphertext].orEmpty()
        val token = if (ciphertext.isBlank()) {
            ""
        } else {
            runCatching { crypto.decrypt(ciphertext) }.getOrDefault("")
        }
        return NsfwProfile(
            baseUrl = prefs[keyBaseUrl].orEmpty(),
            token = token,
            credentialRejected = ciphertext.isNotBlank() && token.isBlank(),
        )
    }

    suspend fun saveBaseUrl(baseUrl: String) {
        context.nsfwStudioDataStore.edit { prefs -> prefs[keyBaseUrl] = baseUrl }
    }

    suspend fun savePairing(token: String) {
        context.nsfwStudioDataStore.edit { prefs -> prefs[keyTokenCiphertext] = crypto.encrypt(token) }
    }

    suspend fun invalidateCredential() {
        context.nsfwStudioDataStore.edit { prefs -> prefs.remove(keyTokenCiphertext) }
    }

    suspend fun installationId(): String = installationIds.getOrCreate()

    suspend fun clear() {
        context.nsfwStudioDataStore.edit { prefs ->
            prefs.remove(keyBaseUrl)
            prefs.remove(keyTokenCiphertext)
        }
    }
}
