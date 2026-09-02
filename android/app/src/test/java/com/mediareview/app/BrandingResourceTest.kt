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
        assertTrue(strings.contains("<string name=\"app_name\">家庭媒体管家</string>"))
        assertTrue(gradle.contains("versionCode = 6"))
        assertTrue(gradle.contains("versionName = \"1.1.0\""))
    }

    @Test
    fun adaptiveIconReferencesApprovedLayers() {
        val icon = projectFile("src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        val themedIcon = projectFile("src/main/res/mipmap-anydpi-v33/ic_launcher.xml").readText()
        val foreground = projectFile("src/main/res/drawable/ic_launcher_foreground.xml").readText()
        val monochrome = projectFile("src/main/res/drawable/ic_launcher_monochrome.xml").readText()
        val colors = projectFile("src/main/res/values/colors.xml").readText()
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()

        assertTrue(icon.contains("@color/ic_launcher_background"))
        assertTrue(icon.contains("@drawable/ic_launcher_foreground"))
        assertTrue(themedIcon.contains("@drawable/ic_launcher_monochrome"))
        assertTrue(colors.contains("#0B1118"))
        assertTrue(foreground.contains("<vector"))
        assertTrue(foreground.contains("#47D7E8"))
        assertTrue(monochrome.contains("<vector"))
        assertTrue(!foreground.contains("ic_launcher_art"))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
        assertTrue(projectFile("src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml").exists())
    }
}
