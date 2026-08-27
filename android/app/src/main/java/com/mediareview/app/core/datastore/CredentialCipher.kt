package com.mediareview.app.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

data class CredentialMigration(
    val token: String,
    val encryptedToken: String,
    val removePlaintext: Boolean,
    val removeEncrypted: Boolean = false,
    val credentialRejected: Boolean = false,
)

fun migrateCredential(
    encryptedToken: String,
    plaintextToken: String,
    crypto: CredentialCipher,
): CredentialMigration = try {
    when {
        encryptedToken.isNotBlank() -> CredentialMigration(
            token = crypto.decrypt(encryptedToken),
            encryptedToken = encryptedToken,
            removePlaintext = plaintextToken.isNotBlank(),
        )
        plaintextToken.isNotBlank() -> CredentialMigration(
            token = plaintextToken,
            encryptedToken = crypto.encrypt(plaintextToken),
            removePlaintext = true,
        )
        else -> CredentialMigration("", "", false)
    }
} catch (_: Exception) {
    CredentialMigration(
        token = "",
        encryptedToken = "",
        removePlaintext = plaintextToken.isNotBlank(),
        removeEncrypted = encryptedToken.isNotBlank(),
        credentialRejected = true,
    )
}

fun stableInstallationId(existing: String, generator: () -> String): String =
    existing.trim().ifBlank(generator)

/** minSdk 26: Android Keystore 持有 AES key，DataStore 仅保存 AES-GCM 密文。 */
class AndroidKeystoreCredentialCipher : CredentialCipher {
    private val alias = "mediareview_pairing_token_v1"

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return listOf(cipher.iv, cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
            .joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    override fun decrypt(ciphertext: String): String {
        val parts = ciphertext.split('.')
        require(parts.size == 2) { "配对凭据密文损坏" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}
