package com.mediareview.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaUrlResolverTest {
    private val resolver = MediaUrlResolver()

    @Test
    fun `相对图片和雪碧图地址使用已配对服务器`() {
        assertEquals(
            "http://192.168.31.20:8766/api/v1/media/a/thumbnail",
            resolver.resolve("/api/v1/media/a/thumbnail", "http://192.168.31.20:8766"),
        )
    }

    @Test
    fun `Jellyfin 回环地址只替换 host 并保留端口路径与 query`() {
        assertEquals(
            "http://192.168.31.20:8096/jellyfin/Videos/a/stream?static=true",
            resolver.resolve(
                "http://127.0.0.1:8096/jellyfin/Videos/a/stream?static=true",
                "http://192.168.31.20:8766",
            ),
        )
    }

    @Test
    fun `默认替换保留的 server-only hostname`() {
        assertEquals(
            "http://192.168.31.20:8096/Videos/a/stream",
            resolver.resolve(
                "http://jellyfin.internal:8096/Videos/a/stream",
                "http://192.168.31.20:8766",
            ),
        )
    }

    @Test
    fun `拒绝畸形非 http userinfo 和凭据参数`() {
        for (url in listOf(
            "ftp://jf.local/file",
            "http://user:pass@jf.local/file",
            "http://jf.local/file?api_key=secret",
            "//evil.example/file",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                resolver.resolve(url, "http://192.168.31.20:8766")
            }
        }
    }
}
