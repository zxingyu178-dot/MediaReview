package com.mediareview.app.feature.v2.nsfw

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediareview.app.core.datastore.AndroidKeystoreCredentialCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl

private val Context.generatedBridgeData by preferencesDataStore("generated_review_bridge")

@Singleton
class GeneratedBridgeStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val cipher = AndroidKeystoreCredentialCipher()
    private val urlKey = stringPreferencesKey("bridge_url")
    private val tokenKey = stringPreferencesKey("bridge_token_encrypted")

    suspend fun current(): GeneratedBridgeProfile {
        val data = context.generatedBridgeData.data.first()
        val encrypted = data[tokenKey].orEmpty()
        // Keystore失效时绝不降级读取或保存明文，调用方显示重新配置提示。
        return GeneratedBridgeProfile(data[urlKey].orEmpty(), if (encrypted.isEmpty()) "" else cipher.decrypt(encrypted))
    }

    suspend fun save(baseUrl: String, token: String) {
        val url = baseUrl.trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null &&
            url.fragment == null && url.encodedPath == "/") { "请填写服务根地址，不包含凭据或路径" }
        require(token.isNotBlank()) { "请填写生图服务的独立凭据" }
        val encrypted = cipher.encrypt(token.trim())
        context.generatedBridgeData.edit { it[urlKey] = url.toString(); it[tokenKey] = encrypted }
    }
}
