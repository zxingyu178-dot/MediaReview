package com.mediareview.app.feature.v2.nsfw.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediareview.app.core.datastore.AndroidKeystoreCredentialCipher
import com.mediareview.app.core.datastore.CredentialCipher
import com.mediareview.app.core.datastore.InstallationIdCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
    private val json: Json = Json { ignoreUnknownKeys = true },
) : NsfwEvaluationJournal, NsfwBrowsePositionStore, com.mediareview.app.feature.v2.nsfw.NsfwBatchJournal {
    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyTokenCiphertext = stringPreferencesKey("token_ciphertext")
    private val keyInstallationId = stringPreferencesKey("installation_id")
    private val keyPendingEvaluations = stringPreferencesKey("pending_evaluations")

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

    private suspend fun batchKey(origin: String): androidx.datastore.preferences.core.Preferences.Key<String> {
        require(origin.isNotBlank())
        return stringPreferencesKey("batch_pending_" + browseKey(origin + "\n" + installationId()).name)
    }

    override suspend fun load(origin: String): NsfwFrozenBatch? {
        val raw = context.nsfwStudioDataStore.data.first()[batchKey(origin)] ?: return null
        return json.decodeFromString(NsfwFrozenBatch.serializer(), raw)
    }

    override suspend fun save(origin: String, batch: NsfwFrozenBatch) {
        val key = batchKey(origin)
        val raw = json.encodeToString(NsfwFrozenBatch.serializer(), batch)
        context.nsfwStudioDataStore.edit { prefs ->
            check(prefs[key] == null) { "已有待核对批次，不能覆盖" }
            prefs[key] = raw
        }
    }

    override suspend fun clear(origin: String, requestId: String) {
        val key = batchKey(origin)
        context.nsfwStudioDataStore.edit { prefs ->
            val raw = prefs[key]
            if (raw != null && json.decodeFromString(NsfwFrozenBatch.serializer(), raw).requestId == requestId) prefs.remove(key)
        }
    }

    suspend fun clear() {
        context.nsfwStudioDataStore.edit { prefs ->
            prefs.remove(keyBaseUrl)
            prefs.remove(keyTokenCiphertext)
        }
    }

    private fun browseKey(origin: String) = stringPreferencesKey("browse_" +
        java.security.MessageDigest.getInstance("SHA-256").digest(origin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) })

    override suspend fun loadPosition(origin: String): String? =
        context.nsfwStudioDataStore.data.first()[browseKey(origin)]?.takeIf { it.isNotBlank() }

    override suspend fun loadGalleryReview(origin: String): NsfwSavedGalleryReview? {
        val raw = context.nsfwStudioDataStore.data.first()[stringPreferencesKey("gallery_review_" + browseKey(origin).name)] ?: return null
        return json.decodeFromString(NsfwSavedGalleryReview.serializer(), raw).also {
            require(it.scopeCursor.isNotBlank() && it.imageId.isNotBlank())
        }
    }

    override suspend fun saveGalleryReview(origin: String, review: NsfwSavedGalleryReview) {
        require(origin.isNotBlank() && review.scopeCursor.isNotBlank() && review.imageId.isNotBlank())
        context.nsfwStudioDataStore.edit {
            it[stringPreferencesKey("gallery_review_" + browseKey(origin).name)] = json.encodeToString(NsfwSavedGalleryReview.serializer(), review)
        }
    }

    override suspend fun savePosition(origin: String, imageId: String) {
        require(origin.isNotBlank() && imageId.isNotBlank())
        context.nsfwStudioDataStore.edit { it[browseKey(origin)] = imageId }
    }

    override suspend fun loadGalleryAnchor(origin: String): NsfwGalleryAnchor? {
        val prefs = context.nsfwStudioDataStore.data.first()
        val id = prefs[stringPreferencesKey("gallery_" + browseKey(origin).name)]?.takeIf { it.isNotBlank() } ?: return null
        return NsfwGalleryAnchor(id, (prefs[intPreferencesKey("gallery_offset_" + browseKey(origin).name)] ?: 0).coerceAtLeast(0))
    }

    override suspend fun saveGalleryAnchor(origin: String, anchor: NsfwGalleryAnchor) {
        require(origin.isNotBlank() && anchor.imageId.isNotBlank())
        context.nsfwStudioDataStore.edit {
            it[stringPreferencesKey("gallery_" + browseKey(origin).name)] = anchor.imageId
            it[intPreferencesKey("gallery_offset_" + browseKey(origin).name)] = anchor.offset.coerceAtLeast(0)
        }
    }

    override suspend fun loadPendingEvaluations(): List<NsfwPendingEvaluation> {
        val raw = context.nsfwStudioDataStore.data.first()[keyPendingEvaluations].orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(NsfwPendingEvaluation.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    override suspend fun savePendingEvaluations(items: List<NsfwPendingEvaluation>) {
        val raw = json.encodeToString(ListSerializer(NsfwPendingEvaluation.serializer()), items)
        context.nsfwStudioDataStore.edit { prefs -> prefs[keyPendingEvaluations] = raw }
    }
}
