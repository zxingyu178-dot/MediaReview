package com.mediareview.app.feature.v2.nsfw

import com.mediareview.app.feature.v2.nsfw.data.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class NsfwGalleryQueryTest {
    @Test fun libraryMembershipMatchesAllFiltersAndRequiresCategoryEvidence() {
        val filter = NsfwGalleryFilter(promptId = "p", versionId = "v", status = "NEW", favorite = false, category = "anime")
        val query = filter.resolve()
        val image = nsfwItem("a").copy(createdAt = "2026-11-10T00:00:00Z", prompt = NsfwPromptLink("p", "v", "EXACT"), categories = setOf("anime", "imported"))
        assertEquals(true, query.matches(image))
        assertEquals(false, query.matches(image.copy(status = "KEEP")))
        assertEquals(false, query.matches(image.copy(isFavorite = true)))
        assertEquals(false, query.matches(image.copy(prompt = NsfwPromptLink("p", "other", "EXACT"))))
        assertNull(query.matches(image.copy(categories = null)))
        assertEquals(false, query.matches(image.copy(categories = emptySet())))
        assertEquals(true, NsfwGalleryFilter(category = "imported").resolve().matches(image))
    }

    @Test fun todayUsesPhoneCalendarAndExclusiveNextDay() {
        val query = NsfwGalleryFilter(dates = NsfwGalleryDates.TODAY)
            .resolve(Instant.parse("2026-11-09T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-11-08T16:00:00.000Z", query.fromUtc)
        assertEquals("2026-11-09T16:00:00.000Z", query.beforeUtc)
        assertTrue(query.contains("2026-11-08T16:00:00.000Z"))
        assertFalse(query.contains("2026-11-09T16:00:00.000Z"))
        assertEquals("created_desc", query.filter.sort.wire)
    }

    @Test fun lastSevenDaysIncludesTodayAndCrossesMonth() {
        val query = NsfwGalleryFilter(dates = NsfwGalleryDates.LAST_SEVEN)
            .resolve(Instant.parse("2026-03-01T12:00:00Z"), ZoneId.of("UTC"))
        assertEquals("2026-02-23T00:00:00.000Z", query.fromUtc)
        assertEquals("2026-03-02T00:00:00.000Z", query.beforeUtc)
    }

    @Test fun customDateIncludesWholeDstDay() {
        val query = NsfwGalleryFilter(dates = NsfwGalleryDates.CUSTOM, startDate = "2026-03-08", endDate = "2026-03-08")
            .resolve(Instant.EPOCH, ZoneId.of("America/New_York"))
        assertEquals(23L, Duration.between(Instant.parse(query.fromUtc), Instant.parse(query.beforeUtc)).toHours())
    }

    @Test fun reversedCustomRangeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NsfwGalleryFilter(dates = NsfwGalleryDates.CUSTOM, startDate = "2026-11-10", endDate = "2026-11-09").resolve()
        }
    }
}
