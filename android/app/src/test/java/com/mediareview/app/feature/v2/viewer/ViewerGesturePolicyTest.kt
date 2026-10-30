package com.mediareview.app.feature.v2.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerGesturePolicyTest {
    @Test fun `down swipe closes only at resting zoom`() {
        assertTrue(ViewerGesturePolicy.shouldDismiss(scale = 1f, dragY = 320f, velocityY = 0f, viewportHeight = 1000f))
        assertTrue(ViewerGesturePolicy.shouldDismiss(scale = 1f, dragY = 90f, velocityY = 1_500f, viewportHeight = 1000f))
        assertFalse(ViewerGesturePolicy.shouldDismiss(scale = 1.01f, dragY = 500f, velocityY = 2_000f, viewportHeight = 1000f))
    }

    @Test fun `upward and short downward gestures rebound`() {
        assertFalse(ViewerGesturePolicy.shouldDismiss(1f, -400f, -1_500f, 1000f))
        assertFalse(ViewerGesturePolicy.shouldDismiss(1f, 80f, 100f, 1000f))
    }

    @Test fun `dismiss progress is bounded`() {
        assertEquals(0f, ViewerGesturePolicy.dismissProgress(-10f, 1000f), 0.001f)
        assertEquals(0.5f, ViewerGesturePolicy.dismissProgress(250f, 1000f), 0.001f)
        assertEquals(1f, ViewerGesturePolicy.dismissProgress(900f, 1000f), 0.001f)
    }

    @Test fun `information path never exposes absolute path`() {
        assertEquals("照片/海边.jpg", safeViewerRelativePath("D:\\媒体\\照片\\海边.jpg", "照片/海边.jpg"))
        assertEquals("照片/海边.jpg", safeViewerRelativePath("/srv/media/照片/海边.jpg", "照片/海边.jpg"))
        assertEquals("demo_media/images/a.jpg", safeViewerRelativePath("demo_media/images/a.jpg", ""))
        assertEquals("未提供", safeViewerRelativePath("C:\\secret\\a.jpg", ""))
    }
}
