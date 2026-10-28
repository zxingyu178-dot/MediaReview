package com.mediareview.app.feature.v2.nsfw

import org.junit.Assert.*
import org.junit.Test

class GeneratedImageQueueTest {
    private fun image(id: String, prompt: String? = null) = GeneratedImage(id, "2026-10-28T00:00:00Z", 1024, 1536, prompt)

    @Test fun newlyDiscoveredImagesAppendWithoutReorderingExistingQueue() {
        val queue = GeneratedImageQueue(listOf(image("b"), image("a")))
            .append(listOf(image("c"), image("d")))
        assertEquals(listOf("b", "a", "c", "d"), queue.items.map { it.id })
    }
    @Test fun overlappingPagesUpdateMetadataInPlaceAndDoNotDuplicate() {
        val queue = GeneratedImageQueue(listOf(image("a"), image("b")))
            .append(listOf(image("b", "生成快照"), image("c"), image("c")))
        assertEquals(listOf("a", "b", "c"), queue.items.map { it.id })
        assertEquals("生成快照", queue.items[1].prompt)
    }
    @Test fun appendingOrEmptyPollKeepsCurrentImageAndDoesNotDropOldItems() {
        val queue = GeneratedImageQueue(listOf(image("a"), image("b")), currentImageId = "b")
            .append(listOf(image("c"))).append(emptyList())
        assertEquals("b", queue.currentImageId)
        assertEquals(1, queue.currentIndex)
        assertEquals(3, queue.items.size)
    }
    @Test fun initialQueueSelectsFirstImageAndDistinguishesUnavailablePromptFromEmptyPrompt() {
        val queue = GeneratedImageQueue().append(listOf(image("a"), image("b", "")))
        assertEquals("a", queue.currentImageId)
        assertNull(queue.items[0].prompt)
        assertEquals("", queue.items[1].prompt)
        assertNull(GeneratedImageQueue().currentIndex)
    }
}
