package com.mediareview.app.feature.v2.home

import com.mediareview.app.feature.v2.model.V2SpriteManifest
import com.mediareview.app.feature.v2.data.SpritePrepareResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** 真正驱动生产控制器，不依赖 Android ViewModel 构造。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpritePreviewControllerTest {
    private fun controller(scope: CoroutineScope, loader: suspend (String) -> SpritePrepareResult) = SpritePreviewController(scope, loader)
    private fun state(controller: SpritePreviewController): SpritePreviewState = controller.state.value
    private fun start(controller: SpritePreviewController, id: String = "m1") = controller.start(id)
    private fun stop(controller: SpritePreviewController) = controller.stop()
    private fun ready() = SpritePrepareResult.Ready("fixture://sprite", V2SpriteManifest("m1", 1, 1, 16, 9, 1.0, 1.0, 1))
    private fun failed() = SpritePrepareResult.Failed("预览失败")

    @Test fun `start prepares then ready and stop returns idle`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val control = controller(backgroundScope) { gate.await(); ready() }
        assertEquals("Idle", state(control).javaClass.simpleName)
        start(control); assertEquals("Preparing", state(control).javaClass.simpleName)
        runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals("Ready", state(control).javaClass.simpleName)
        stop(control); assertEquals("Idle", state(control).javaClass.simpleName)
    }
    @Test fun `failure reaches error and can retry`() = runTest {
        var calls = 0
        val control = controller(backgroundScope) { if (++calls == 1) failed() else ready() }
        start(control); runCurrent(); assertEquals("Error", state(control).javaClass.simpleName)
        start(control); runCurrent(); assertEquals("Ready", state(control).javaClass.simpleName)
    }
    @Test fun `cancelled late result cannot restore stopped preview`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val control = controller(backgroundScope) { withContext(NonCancellable) { gate.await() }; ready() }
        start(control); runCurrent(); stop(control); gate.complete(Unit); runCurrent()
        assertEquals("Idle", state(control).javaClass.simpleName)
    }
    @Test fun `switching media ignores older completion`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val control = controller(backgroundScope) { id ->
            if (id == "m1") withContext(NonCancellable) { gate.await() }
            ready()
        }
        start(control); runCurrent(); start(control, "m2"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("m2", state(control).mediaId)
    }

    @Test fun `sprite file failure becomes error but previous decoder cannot fail new request`() = runTest {
        val control = controller(backgroundScope) { ready() }
        start(control); runCurrent()
        val old = state(control) as SpritePreviewState.Ready
        start(control); runCurrent()
        val current = state(control) as SpritePreviewState.Ready
        control.fail(old)
        assertEquals("Ready", state(control).javaClass.simpleName)
        control.fail(current)
        assertEquals("Error", state(control).javaClass.simpleName)
    }
}
