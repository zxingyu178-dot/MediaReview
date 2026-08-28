package com.mediareview.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

        listOf("0xFF0B1118", "0xFF141D27", "0xFF47D7E8", "0xFFF4F7FA", "0xFFA9B4C0", "0xFF39D98A", "0xFFFF6B6B")
            .forEach { assertTrue("缺少批准色值 $it", colors.contains(it)) }
        assertTrue(theme.contains("darkColorScheme"))
        assertFalse(theme.contains("lightColorScheme"))
        assertFalse(theme.contains("dynamicDarkColorScheme"))
        assertTrue(dimensions.exists())
        assertTrue(dimensions.readText().contains("MinimumTouchTarget = 48.dp"))
    }

    @Test
    fun mainSurfaceDoesNotExposeTechnicalProfileIdentifiers() {
        val shell = projectFile("src/main/java/com/mediareview/app/ui/shell/MainShell.kt").readText()
        val media = projectFile(
            "src/main/java/com/mediareview/app/feature/mediawall/MediaWallScreen.kt",
        ).readText()
        val visibleSurface = shell + media

        assertFalse(visibleSurface.contains("ui.baseUrl"))
        assertFalse(visibleSurface.contains("ui.deviceId"))
        assertFalse(visibleSurface.contains("服务器地址"))
        assertFalse(visibleSurface.contains("设备编号"))
    }

    @Test
    fun productionKotlinHasNoKnownEmojiOrSymbolControlPlaceholders() {
        val sourceRoot = projectFile("src/main/java")
        val quoted = Regex("\\\"(?:[^\\\"\\\\]|\\\\.)*\\\"")
        val forbidden = listOf(
            "❤", "♥", "🗑", "⋯", "▶", "◀", "▲", "▼", "↑", "↓", "⏸",
            "🔒", "🔓", "🔇", "🔊", "🎞", "🔄", "🤍",
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

    @Test
    fun playerTextControlsUseMaterialButtonsWithMinimumTargets() {
        val player = projectFile(
            "src/main/java/com/mediareview/app/feature/player/PlayerScreen.kt",
        ).readText()

        assertFalse(player.contains("Modifier.clickable { expanded = true }.padding(6.dp)"))
        assertTrue(Regex("PlayerTextMenuButton\\(").findAll(player).count() >= 3)
        assertTrue(player.contains("minHeight = MediaDimensions.MinimumTouchTarget"))
    }
}
