package com.mediareview.app.ui.shell

import com.mediareview.app.feature.connect.data.AuthenticationState
import com.mediareview.app.feature.connect.data.ConnectionState
import com.mediareview.app.feature.connect.data.OnlineState
import com.mediareview.app.feature.connect.data.SyncState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationContractTest {
    private fun projectFile(relative: String): File {
        val base = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(base, relative), File(base, "app/$relative"))
            .first { it.exists() }
    }

    @Test
    fun rootsHaveTheApprovedOrderAndMediaDefault() {
        assertEquals(listOf("媒体", "批阅", "收藏", "整理"), MainRoot.entries.map { it.label })
        assertEquals(MainRoot.Media, DEFAULT_MAIN_ROOT)
    }

    @Test
    fun selectingTheCurrentRootIsDeduplicated() {
        assertFalse(rootSelection(MainRoot.Media, MainRoot.Media).changed)
        assertTrue(rootSelection(MainRoot.Media, MainRoot.Review).changed)
        assertEquals(MainRoot.Review, rootSelection(MainRoot.Media, MainRoot.Review).root)
    }

    @Test
    fun chromeBelongsOnlyToTheSingleMainShell() {
        assertTrue(shouldShowMainChrome(MainShellDestinations.ROUTE))
        listOf("connect", "settings", "player/42", "image_viewer/42")
            .forEach { assertFalse(shouldShowMainChrome(it)) }
    }

    @Test
    fun connectionBannerUsesAllFourTask3StatesWithoutPretendingErrorsAreSyncing() {
        assertEquals(
            ShellBannerKind.Syncing,
            shellBanner(ConnectionState(sync = SyncState.Syncing))?.kind,
        )
        assertEquals(
            ShellBannerKind.Attention,
            shellBanner(ConnectionState(mediaReview = OnlineState.Offline))?.kind,
        )
        assertEquals(
            ShellBannerKind.Attention,
            shellBanner(ConnectionState(authentication = AuthenticationState.Rejected))?.kind,
        )
        assertEquals(
            ShellBannerKind.Attention,
            shellBanner(ConnectionState(jellyfin = OnlineState.Offline))?.kind,
        )
    }

    @Test
    fun reconnectReplacesTheExistingShellAndPairingCannotDuplicateIt() {
        val settings = projectFile(
            "src/main/java/com/mediareview/app/feature/settings/SettingsScreen.kt",
        ).readText()
        val connect = projectFile(
            "src/main/java/com/mediareview/app/feature/connect/ConnectScreen.kt",
        ).readText()

        assertTrue(settings.contains("popUpTo(MainShellDestinations.ROUTE) { inclusive = true }"))
        assertTrue(connect.contains("launchSingleTop = true"))
    }

    @Test
    fun organizerFormatsExistingCountsWithoutReimplementingBusinessRules() {
        val statuses = organizerStatuses(
            selectedLibraries = 2,
            pendingDeletes = 3,
            exactGroups = 4,
            similarGroups = 5,
        )

        assertEquals("已选择 2 个媒体库", statuses.library)
        assertEquals("3 项待确认删除", statuses.deleteQueue)
        assertEquals("4 组完全重复，5 组疑似重复", statuses.duplicates)
    }
}
