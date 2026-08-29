package com.mediareview.app.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentInvalidationStoreTest {
    @Test
    fun unchangedRevisionDoesNotReloadButRelevantInvalidationDoes() {
        val store = ContentInvalidationStore()
        val gate = RevisionLoadGate()

        assertTrue(gate.claim(store.revision(ContentArea.Favorites)))
        assertFalse(gate.claim(store.revision(ContentArea.Favorites)))

        store.invalidate(ContentArea.DeleteQueue)
        assertFalse(gate.claim(store.revision(ContentArea.Favorites)))

        store.invalidate(ContentArea.Favorites)
        assertTrue(gate.claim(store.revision(ContentArea.Favorites)))
        assertFalse(gate.claim(store.revision(ContentArea.Favorites)))
    }

    @Test
    fun organizerAreasAdvanceIndependently() {
        val store = ContentInvalidationStore()

        store.invalidate(ContentArea.Libraries)
        store.invalidate(ContentArea.DeleteQueue)
        store.invalidate(ContentArea.DeleteQueue)

        assertEquals(1L, store.revision(ContentArea.Libraries))
        assertEquals(2L, store.revision(ContentArea.DeleteQueue))
        assertEquals(0L, store.revision(ContentArea.Duplicates))
    }
}
