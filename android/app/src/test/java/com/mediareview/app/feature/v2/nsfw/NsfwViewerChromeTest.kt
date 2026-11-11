package com.mediareview.app.feature.v2.nsfw

import org.junit.Assert.*
import org.junit.Test

class NsfwViewerChromeTest {
    @Test fun zoomHidesChromeAndNearFitJitterDoesNotFlashIt() {
        val zoomed = NsfwViewerChrome().scaleChanged(1.1f)
        assertFalse(zoomed.visible)
        assertFalse(zoomed.scaleChanged(1.03f).visible)
        assertTrue(zoomed.scaleChanged(1.005f).visible)
    }
    @Test fun manualHiddenStateSurvivesZoomRoundTrip() {
        val hidden = NsfwViewerChrome().toggle()
        assertFalse(hidden.scaleChanged(2.5f).scaleChanged(1f).visible)
    }
}
