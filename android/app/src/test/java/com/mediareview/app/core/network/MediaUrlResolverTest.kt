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
            "http://jf.example/file?%61pi_key=secret",
            "http://jf.example/file?%2561pi_key=secret",
            "http://jf.example/file?%FF=value",
            "http://jf.example/file?ok=1;token=secret",
            "//evil.example/file",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                resolver.resolve(url, "http://192.168.31.20:8766")
            }
        }
    }

    @Test
    fun `合法 IPv6 字面量不因无点号而改写`() {
        assertEquals(
            "http://[fd00::20]:8096/Videos/a/stream",
            resolver.resolve(
                "http://[fd00::20]:8096/Videos/a/stream",
                "http://192.168.31.20:8766",
                setOf("jellyfin.internal"),
            ),
        )
    }

    @Test
    fun `受控 server only host 集合会实际触发替换`() {
        assertEquals(
            "http://192.168.31.20:8096/Videos/a/stream",
            resolver.resolve(
                "http://jf.private.example:8096/Videos/a/stream",
                "http://192.168.31.20:8766",
                setOf("jf.private.example"),
            ),
        )
    }
}
