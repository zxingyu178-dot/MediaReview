package com.mediareview.app.feature.v2.home

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.mediareview.app.feature.v2.data.HomeDisplayModeStore
import com.mediareview.app.feature.v2.data.DemoMediaCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 真实 pointer 操作生产共用 Grid / Overlay，记录实际布局坐标，不能替代家庭 LAN 验收。 */
class HomeLayoutTouchTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var grid: LazyGridState
    private lateinit var scope: CoroutineScope
    private val ids = (0 until 100).map { "m$it" }
    private var mode by mutableStateOf(HomeDisplayMode.TWO_COLUMN)
    private val fixture = DemoMediaCatalog.buildMedia().first { !it.isVideo }

    private fun mount() {
        compose.setContent {
            grid = rememberLazyGridState()
            scope = rememberCoroutineScope()
            val toggle: @Composable (String) -> Unit = { tag ->
                TextButton(onClick = {
                    mode = if (mode == HomeDisplayMode.TWO_COLUMN) HomeDisplayMode.ONE_COLUMN else HomeDisplayMode.TWO_COLUMN
                }, modifier = Modifier.testTag(tag)) { Text("切换布局") }
            }
            HomeHeaderOverlay(expandedHeader = { toggle("toggle_expanded") }, compactHeader = { toggle("toggle_compact") }) { inset ->
                AnchoredMediaGrid(ids, { it }, mode, grid, contentPadding = inset) { id ->
                    MediaCard(fixture.copy(id = id, name = id), "file:///android_asset/${fixture.thumbPath}",
                        spriteUri = null, manifest = null, isSpritePreviewing = false,
                        onSpritePreviewRequest = {}, onClick = {}, displayMode = mode,
                        modifier = Modifier.testTag(id))
                }
            }
        }
        compose.runOnIdle { scope.launch { grid.scrollToItem(40, 30) } }
        compose.waitForIdle()
    }

    @Test fun actualPointerSwitchKeepsIdentityOffsetAndCoordinates() {
        mount()
        val before = compose.runOnIdle { Triple(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, grid.layoutInfo.visibleItemsInfo.first { it.key == "m40" }.offset.y) }
        compose.onNodeWithTag("toggle_expanded").performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(HomeDisplayMode.ONE_COLUMN, mode)
            assertEquals(before.first, grid.firstVisibleItemIndex)
            assertEquals(before.second, grid.firstVisibleItemScrollOffset)
            assertEquals(before.third, grid.layoutInfo.visibleItemsInfo.first { it.key == "m40" }.offset.y)
            Log.i("Stage8HHome", "TWO_TO_ONE media=m40 offset=${grid.firstVisibleItemScrollOffset} y=${before.third}")
        }
        compose.onNodeWithTag("toggle_expanded").performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(HomeDisplayMode.TWO_COLUMN, mode)
            assertEquals(before.first, grid.firstVisibleItemIndex)
            assertEquals(before.second, grid.firstVisibleItemScrollOffset)
            assertEquals(before.third, grid.layoutInfo.visibleItemsInfo.first { it.key == "m40" }.offset.y)
            Log.i("Stage8HHome", "ONE_TO_TWO media=m40 offset=${grid.firstVisibleItemScrollOffset} y=${before.third}")
        }
    }

    @Test fun pointerCollapseAndExpandAwayFromTopDoNotAddGridDisplacement() {
        mount()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        fun position() = compose.runOnIdle { grid.firstVisibleItemScrollOffset to grid.layoutInfo.visibleItemsInfo.first { it.key == "m40" }.offset.y }
        val before = position()
        compose.onNodeWithTag("home_grid").performTouchInput {
            down(Offset(width * .5f, height * .7f))
            moveBy(Offset(0f, -50f * density), 300)
            advanceEventTime(500)
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("toggle_compact").assertIsDisplayed()
        val collapsed = position()
        assertEquals(before.second - (collapsed.first - before.first), collapsed.second)
        compose.runOnIdle { assertEquals(40, grid.firstVisibleItemIndex) }
        compose.onNodeWithTag("home_grid").performTouchInput {
            down(Offset(width * .5f, height * .7f))
            moveBy(Offset(0f, 50f * density), 300)
            advanceEventTime(500)
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("toggle_expanded").assertIsDisplayed()
        val expanded = position()
        assertEquals(collapsed.second - (expanded.first - collapsed.first), expanded.second)
        compose.runOnIdle { assertEquals(40, grid.firstVisibleItemIndex) }
        Log.i("Stage8HHome", "HEADER_POINTER anchor=m40 before=$before collapsed=$collapsed expanded=$expanded; displacement=scroll-only")
    }

    @Test fun displayPreferenceSurvivesNewStoreInstanceWithoutClearingUserData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = HomeDisplayModeStore(context)
        store.bootstrap()
        val original = store.mode.value
        try {
            store.set(HomeDisplayMode.ONE_COLUMN)
            val reopened = HomeDisplayModeStore(context)
            reopened.bootstrap()
            assertEquals(HomeDisplayMode.ONE_COLUMN, reopened.mode.value)
        } finally { store.set(original) }
    }
}
