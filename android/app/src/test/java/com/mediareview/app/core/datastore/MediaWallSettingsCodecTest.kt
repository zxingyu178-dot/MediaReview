package com.mediareview.app.core.datastore

import com.mediareview.app.feature.home.data.MediaTypeFilter
import com.mediareview.app.feature.home.data.SortField
import com.mediareview.app.feature.home.data.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaWallSettingsCodecTest {
    @Test
    fun invalidPersistedValuesFallBackToSafeDefaults() {
        assertEquals(SortField.Name, MediaWallSettingsCodec.sortField("unknown"))
        assertEquals(SortOrder.Asc, MediaWallSettingsCodec.sortOrder("unknown"))
        assertEquals(MediaTypeFilter.All, MediaWallSettingsCodec.mediaType("unknown"))
    }

    @Test
    fun validWireValuesRoundTrip() {
        assertEquals(SortField.Created, MediaWallSettingsCodec.sortField("created"))
        assertEquals(SortOrder.Desc, MediaWallSettingsCodec.sortOrder("desc"))
        assertEquals(MediaTypeFilter.Video, MediaWallSettingsCodec.mediaType("video"))
    }
}
