package com.mediareview.app.feature.v2.nsfw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NsfwGalleryInteractionTest {
    @Test fun backgroundReadKeepsQueryNavigationAvailableButPreventsDuplicateRefresh() {
        val idle = nsfwGalleryInteraction(busy = false, syncing = false)
        val reading = nsfwGalleryInteraction(busy = false, syncing = true)
        assertTrue(idle.canChangeQuery)
        assertTrue(reading.canChangeQuery)
        assertTrue(idle.canRefresh)
        assertFalse(reading.canRefresh)
    }

    @Test fun submissionStillBlocksQueryChangesAndRefresh() {
        for (syncing in listOf(false, true)) {
            val submitting = nsfwGalleryInteraction(busy = true, syncing = syncing)
            assertFalse(submitting.canChangeQuery)
            assertFalse(submitting.canRefresh)
        }
    }
}
