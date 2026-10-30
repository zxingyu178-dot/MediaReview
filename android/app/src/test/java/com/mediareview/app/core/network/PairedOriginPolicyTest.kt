package com.mediareview.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairedOriginPolicyTest {
    @Test fun `视频缓存命名空间绑定配对且兼容IPv6不泄露凭据`() {
        val provider = TokenProvider()
        assertNull(provider.playbackCacheNamespace())
        provider.set("sample-secret", "http://[::1]:8766")
        val first = provider.playbackCacheNamespace()!!
        org.junit.Assert.assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        provider.set("other", "http://[::1]:8766")
        org.junit.Assert.assertNotEquals(first, provider.playbackCacheNamespace())
        provider.set("sample-secret", "http://[::1]:8767")
        org.junit.Assert.assertNotEquals(first, provider.playbackCacheNamespace())
        provider.clear()
        assertNull(provider.playbackCacheNamespace())
    }
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
