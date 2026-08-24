package com.mediareview.app.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialPolicyTest {
    private val crypto = object : CredentialCipher {
        override fun encrypt(plaintext: String): String = plaintext.reversed()
        override fun decrypt(ciphertext: String): String = ciphertext.reversed()
    }

    @Test
    fun `installation id 只生成一次并复用已有值`() {
        var generated = 0
        val first = stableInstallationId("") { generated += 1; "install-1" }
        val second = stableInstallationId(first) { generated += 1; "install-2" }
        assertEquals("install-1", second)
        assertEquals(1, generated)
    }

    @Test
    fun `首次读取迁移明文 token 并要求删除旧 key`() {
        val migration = migrateCredential(
            encryptedToken = "",
            plaintextToken = "mr_plaintext",
            crypto = crypto,
        )
        assertEquals("mr_plaintext", migration.token)
        assertEquals("txetnialp_rm", migration.encryptedToken)
        assertTrue(migration.removePlaintext)
        assertFalse(migration.encryptedToken.contains("mr_plaintext"))
    }

    @Test
    fun `已有密文优先且仍删除残留明文`() {
        val migration = migrateCredential(
            encryptedToken = "wen_rm",
            plaintextToken = "mr_old",
            crypto = crypto,
        )
        assertEquals("mr_new", migration.token)
        assertEquals("wen_rm", migration.encryptedToken)
        assertTrue(migration.removePlaintext)
    }
}
