package com.mediareview.app.feature.v2.nsfw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 审图页顶栏提示词摘要：只展示生成时快照，空白不占位，过长截断。 */
class NsfwImageDetailsTest {

    @Test
    fun `空白提示词不显示摘要`() {
        assertNull(NsfwImageDetails().promptPreview)
        assertNull(NsfwImageDetails(positivePrompt = "   ").promptPreview)
    }

    @Test
    fun `提示词摘要保留普通长度并截断超长内容`() {
        assertEquals("1girl, solo", NsfwImageDetails(positivePrompt = "  1girl, solo ").promptPreview)
        val long = "a".repeat(200)
        val preview = NsfwImageDetails(positivePrompt = long).promptPreview
        assertEquals(121, preview?.length)
        assertEquals("a".repeat(120) + "…", preview)
    }

    @Test
    fun `审核状态徽标只显示已处理状态`() {
        assertEquals("已保留", nsfwStatusBadgeLabel("KEEP"))
        assertEquals("已保留", nsfwStatusBadgeLabel("kept"))
        assertEquals("已淘汰", nsfwStatusBadgeLabel("REJECT"))
        assertEquals("已归档", nsfwStatusBadgeLabel("ARCHIVED"))
        assertNull(nsfwStatusBadgeLabel("NEW"))
        assertNull(nsfwStatusBadgeLabel(""))
    }
}
