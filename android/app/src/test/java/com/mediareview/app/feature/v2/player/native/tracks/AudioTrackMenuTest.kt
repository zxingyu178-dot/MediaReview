package com.mediareview.app.feature.v2.player.native.tracks

import org.junit.Assert.*
import org.junit.Test

class AudioTrackMenuTest {
    @Test fun excludesUnsupportedAndNonAudioTracksWithoutLosingKernelIndexes() {
        val menu = audioTrackMenu("source", listOf(
            AudioTrackDescriptor(AudioTrackKey(0, 0), isAudio = false),
            AudioTrackDescriptor(AudioTrackKey(1, 0), supported = false),
            AudioTrackDescriptor(AudioTrackKey(1, 1), language = "eng", selected = true),
            AudioTrackDescriptor(AudioTrackKey(3, 0), language = "jpn"),
        ))
        assertEquals(listOf(AudioTrackKey(1, 1), AudioTrackKey(3, 0)), menu.choices.map { it.key })
        assertTrue(menu.canSelect)
        assertTrue(menu.choices.first().selected)
        assertFalse(menu.choices.last().selected)
    }

    @Test fun languageMetadataAndChannelCountProduceDistinctChineseLabels() {
        val menu = audioTrackMenu("source", listOf(
            AudioTrackDescriptor(AudioTrackKey(0, 0), language = "eng", channels = 2),
            AudioTrackDescriptor(AudioTrackKey(0, 1), language = "jpn", channels = 6),
        ))
        assertEquals("音轨 1 · 英语 · 2 声道", menu.choices[0].label)
        assertEquals("音轨 2 · 日语 · 6 声道", menu.choices[1].label)
    }

    @Test fun missingOrUndeterminedLanguageNeverExposesProtocolSentinel() {
        val menu = audioTrackMenu("source", listOf(
            AudioTrackDescriptor(AudioTrackKey(0, 0), language = "und"),
            AudioTrackDescriptor(AudioTrackKey(0, 1), language = " ", label = "导演解说"),
        ))
        assertEquals("音轨 1", menu.choices[0].label)
        assertEquals("音轨 2 · 导演解说", menu.choices[1].label)
    }

    @Test fun zeroOrOneUsableTrackHasNoSelectionEntry() {
        assertFalse(audioTrackMenu("a", emptyList()).canSelect)
        assertFalse(audioTrackMenu("a", listOf(AudioTrackDescriptor(AudioTrackKey(0, 0)))).canSelect)
    }

    @Test fun duplicateDisplayLanguagesStillHaveDistinctIdentityAndLabels() {
        val menu = audioTrackMenu("source", listOf(
            AudioTrackDescriptor(AudioTrackKey(2, 0), language = "zh", label = "普通话"),
            AudioTrackDescriptor(AudioTrackKey(2, 1), language = "zh", label = "粤语"),
        ))
        assertNotEquals(menu.choices[0].key, menu.choices[1].key)
        assertNotEquals(menu.choices[0].label, menu.choices[1].label)
        assertTrue(menu.choices[1].label.contains("粤语"))
        assertEquals("source", menu.sourceKey)
    }
}
