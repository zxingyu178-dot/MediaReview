package com.mediareview.app.feature.connect

import org.junit.Assert.assertEquals
import org.junit.Test

/** 阶段 9:手动 IP 规范化的端口补全逻辑。 */
class UrlNormalizeTest {

    @Test
    fun `无端口自动补 8765`() {
        assertEquals("http://192.168.1.10:8765", normalizeBaseUrl("192.168.1.10"))
        assertEquals("http://192.168.1.10:8765", normalizeBaseUrl("http://192.168.1.10"))
    }

    @Test
    fun `已有端口保留用户输入`() {
        assertEquals("http://192.168.1.10:9000", normalizeBaseUrl("192.168.1.10:9000"))
        assertEquals("http://example.com:8096", normalizeBaseUrl("http://example.com:8096"))
    }

    @Test
    fun `末尾斜杠被清除`() {
        assertEquals("http://192.168.1.10:8765", normalizeBaseUrl("192.168.1.10/"))
    }

    @Test
    fun `https 前缀保留`() {
        assertEquals("https://192.168.1.10:8765", normalizeBaseUrl("https://192.168.1.10"))
    }
}