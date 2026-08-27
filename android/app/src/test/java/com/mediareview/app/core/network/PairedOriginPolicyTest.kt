package com.mediareview.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairedOriginPolicyTest {
    @Test
    fun `bearer 只返回给规范化后精确匹配的 paired origin`() {
        val provider = TokenProvider()
        provider.set("secret", "HTTP://192.168.31.20:8766/")

        assertEquals("secret", provider.tokenFor("http://192.168.31.20:8766/api/v1/media"))
        assertNull(provider.tokenFor("http://192.168.31.20:8767/api/v1/media"))
        assertNull(provider.tokenFor("http://other.example:8766/api/v1/media"))
        assertNull(provider.tokenFor("https://192.168.31.20:8766/api/v1/media"))
    }

    @Test
    fun `清除同时移除 token 与 paired origin`() {
        val provider = TokenProvider().apply { set("secret", "http://server.example:8766") }
        provider.clear()
        assertEquals("", provider.token)
        assertEquals("", provider.pairedOrigin)
    }
}
