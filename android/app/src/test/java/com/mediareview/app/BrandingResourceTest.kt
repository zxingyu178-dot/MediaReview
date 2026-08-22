package com.mediareview.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandingResourceTest {
    private fun projectFile(relative: String): File {
        val base = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(base, relative), File(base, "app/$relative"))
            .first { it.exists() }
    }

    @Test
    fun appUsesApprovedChineseNameAndVersion() {
        val strings = projectFile("src/main/res/values/strings.xml").readText()
        val gradle = projectFile("build.gradle.kts").readText()
        val home = projectFile(
            "src/main/java/com/mediareview/app/feature/home/HomeScreen.kt",
        ).readText()
        val connect = projectFile(
            "src/main/java/com/mediareview/app/feature/connect/ConnectScreen.kt",
        ).readText()

        assertTrue(strings.contains("<string name=\"app_name\">家庭媒体管家</string>"))
        assertTrue(gradle.contains("versionCode = 4"))
        assertTrue(gradle.contains("versionName = \"1.0.0-rc2\""))
        assertTrue(home.contains("Text(\"家庭媒体管家\""))
        assertTrue(!home.contains("媒研批阅"))
        assertTrue(connect.contains("家庭媒体管家 · 局域网个人工具"))
        assertTrue(!connect.contains("媒研批阅"))
    }

    @Test
    fun adaptiveIconReferencesApprovedLayers() {
        val icon = projectFile("src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        val themedIcon = projectFile("src/main/res/mipmap-anydpi-v33/ic_launcher.xml").readText()
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()

        assertTrue(icon.contains("@color/ic_launcher_background"))
        assertTrue(icon.contains("@drawable/ic_launcher_foreground"))
        assertTrue(themedIcon.contains("<monochrome"))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
        assertTrue(projectFile("src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml").exists())
    }
}
