package com.mediareview.app.feature.v2.nsfw

import org.junit.Assert.assertEquals
import org.junit.Test

class NsfwImageUrlTest {

    private val base = "http://192.168.1.20:8767"

    @Test
    fun `只接受同源mobile images相对路径`() {
        assertEquals(
            "http://192.168.1.20:8767/mobile/images/img_a/thumbnail",
            safeNsfwImageUrl(base, "/mobile/images/img_a/thumbnail"),
        )
    }

    @Test
    fun `拒绝外部绝对URL与协议相对URL`() {
        assertEquals("", safeNsfwImageUrl(base, "https://evil.example.com/mobile/images/a.png"))
        assertEquals("", safeNsfwImageUrl(base, "http://192.168.1.20:8767/mobile/images/a.png"))
        assertEquals("", safeNsfwImageUrl(base, "//evil.example.com/mobile/images/a.png"))
        assertEquals("", safeNsfwImageUrl(base, "javascript:alert(1)"))
        assertEquals("", safeNsfwImageUrl(base, "file:///etc/passwd"))
    }

    @Test
    fun `拒绝非白名单路径与路径穿越`() {
        assertEquals("", safeNsfwImageUrl(base, "/api/v1/prompts"))
        assertEquals("", safeNsfwImageUrl(base, "/mobile/images/../../api/v1/prompts"))
        assertEquals("", safeNsfwImageUrl(base, "/mobile/images/a\\b"))
        assertEquals("", safeNsfwImageUrl(base, "/mobile/images/\u0000x"))
    }

    @Test
    fun `拒绝查询片段参数注入`() {
        assertEquals("", safeNsfwImageUrl(base, "/mobile/images/a?url=http://evil"))
        assertEquals("", safeNsfwImageUrl(base, "/mobile/images/a#fragment"))
    }

    @Test
    fun `空URL与非法base返回空串`() {
        assertEquals("", safeNsfwImageUrl(base, ""))
        assertEquals("", safeNsfwImageUrl("not a url", "/mobile/images/a"))
    }

    @Test
    fun `https Bridge仍然可用`() {
        assertEquals(
            "https://nsfw.example:8767/mobile/images/a/preview",
            safeNsfwImageUrl("https://nsfw.example:8767", "/mobile/images/a/preview"),
        )
    }
}
