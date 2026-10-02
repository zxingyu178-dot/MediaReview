package com.mediareview.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生产源码契约测试（Stage 8D 精简）。
 *
 * 旧 1.x 页面（MainShell / mediawall / player / connect 等）已随 Legacy 清理删除，
 * 因此这里只保留仍然有意义的**全量生产源码**约束：主题配色/令牌与"不得用 Emoji
 * 当控件占位"。针对已删除旧页面文件的逐文件检查一并移除，不在无意义对象上做假校验。
 */
class Task4SourceContractTest {
    private fun projectFile(relative: String): File {
        val base = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(base, relative), File(base, "app/$relative"))
            .first { it.exists() }
    }

    @Test
    fun themeUsesOnlyTheApprovedDarkPaletteAndReusableTokens() {
        val colors = projectFile("src/main/java/com/mediareview/app/ui/theme/Color.kt").readText()
        val theme = projectFile("src/main/java/com/mediareview/app/ui/theme/Theme.kt").readText()
        val dimensions = projectFile("src/main/java/com/mediareview/app/ui/theme/Dimensions.kt")

        listOf(
            "0xFF0B1118", "0xFF141D27", "0xFF47D7E8", "0xFFF4F7FA",
            "0xFFA9B4C0", "0xFF39D98A", "0xFFFF6B6B",
        ).forEach { assertTrue("缺少批准色值 $it", colors.contains(it)) }
        assertTrue(theme.contains("darkColorScheme"))
        assertFalse(theme.contains("lightColorScheme"))
        assertFalse(theme.contains("dynamicDarkColorScheme"))
        assertTrue(dimensions.exists())
        assertTrue(dimensions.readText().contains("MinimumTouchTarget = 48.dp"))
    }

    @Test
    fun productionKotlinHasNoKnownEmojiOrSymbolControlPlaceholders() {
        val sourceRoot = projectFile("src/main/java")
        val quoted = Regex("\\\"(?:[^\\\"\\\\]|\\\\.)*\\\"")
        val forbidden = listOf(
            "❤", "♥", "🗑", "⋯", "▶", "◀", "▲", "▼", "↑", "↓", "⏸",
            "🔒", "🔓", "🔇", "🔊", "🎞", "🔄", "🤍",
            "✓",
        )
        val hits = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                quoted.findAll(file.readText()).flatMap { literal ->
                    forbidden.asSequence()
                        .filter { literal.value.contains(it) }
                        .map { "${file.name}:$it" }
                }
            }
            .toList()

        assertEquals("仍有 Emoji/符号控件占位: $hits", emptyList<String>(), hits)
    }
}
