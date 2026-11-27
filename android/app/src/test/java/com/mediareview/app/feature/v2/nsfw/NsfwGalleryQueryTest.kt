package com.mediareview.app.feature.v2.nsfw

import com.mediareview.app.feature.v2.nsfw.data.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class NsfwGalleryQueryTest {
    @Test fun hideRejectedUsesWholeQueryAndPreservesOtherStates() {
        val filter = NsfwGalleryFilter(status = "NOT_REJECTED", sort = NsfwGallerySort.OLDEST)
        val query = filter.resolve()
        for (status in listOf("NEW", "KEEP", "ARCHIVED")) {
            assertEquals(true, query.matches(nsfwItem("a").copy(status = status)))
        }
        assertEquals(false, query.matches(nsfwItem("a").copy(status = "REJECT")))
        assertNull(query.matches(nsfwItem("a").copy(status = "")))
        val json = kotlinx.serialization.json.Json
        val restored = json.decodeFromString(NsfwGalleryQuery.serializer(), json.encodeToString(NsfwGalleryQuery.serializer(), query))
        assertEquals(query, restored)
        assertEquals("NOT_REJECTED", restored.filter.selection.status)
        assertTrue(restored.filter.selection.active)
    }

    @Test fun emptyGalleryActionFollowsActiveFilters() {
        val plain = NsfwGalleryFilter()
        assertFalse(plain.emptyStateNarrowed)
        assertEquals("暂无图片", plain.emptyStateLabel)
        assertEquals("刷新图库", plain.emptyStateActionLabel)
        // 排序不是筛选，空图库仍应提示刷新而不是清除筛选。
        assertFalse(NsfwGalleryFilter(sort = NsfwGallerySort.OLDEST).emptyStateNarrowed)
        listOf(
            NsfwGalleryFilter(search = "needle"),
            NsfwGalleryFilter(tag = "curated"),
            NsfwGalleryFilter(dates = NsfwGalleryDates.TODAY),
            NsfwGalleryFilter(status = "NEW"),
            NsfwGalleryFilter(favorite = true),
        ).forEach { filter ->
            assertTrue(filter.emptyStateNarrowed)
            assertEquals("无匹配图片", filter.emptyStateLabel)
            assertEquals("清除筛选", filter.emptyStateActionLabel)
        }
    }

    @Test fun textQueryExpandsSearchSectionOnlyWhenMeaningful() {
        // 空白关键词不算“已启用”，面板保持收起；有实际关键词时直接展开。
        assertFalse(NsfwGalleryFilter().hasTextQuery)
        assertFalse(NsfwGalleryFilter(search = "   ").hasTextQuery)
        assertFalse(NsfwGalleryFilter(tag = "").hasTextQuery)
        assertTrue(NsfwGalleryFilter(search = "cat").hasTextQuery)
        assertTrue(NsfwGalleryFilter(tag = "v1").hasTextQuery)
    }

    @Test fun activeSummaryNamesEveryNarrowingDimension() {
        // 排序不是筛选：无其他条件时摘要保持默认文案。
        assertEquals("筛选已启用", NsfwGalleryFilter().activeSummary)
        assertEquals("筛选已启用", NsfwGalleryFilter(sort = NsfwGallerySort.OLDEST).activeSummary)
        assertEquals("只看未审核", NsfwGalleryFilter(status = "NEW").activeSummary)
        assertEquals("隐藏已淘汰", NsfwGalleryFilter(status = "NOT_REJECTED").activeSummary)
        assertEquals("搜索「needle」", NsfwGalleryFilter(search = "needle").activeSummary)
        assertEquals("标签「curated」", NsfwGalleryFilter(tag = "curated").activeSummary)
        assertEquals("今天", NsfwGalleryFilter(dates = NsfwGalleryDates.TODAY).activeSummary)
        assertEquals("动漫", NsfwGalleryFilter(category = "anime").activeSummary)
        assertEquals("未收藏", NsfwGalleryFilter(favorite = false).activeSummary)
        assertEquals("提示词", NsfwGalleryFilter(promptId = "p").activeSummary)
        assertEquals("提示词版本", NsfwGalleryFilter(promptId = "p", versionId = "v").activeSummary)
        // 关键词超长时截断，避免摘要把“清除”挤出屏幕。
        assertEquals("搜索「${"a".repeat(12)}…」", NsfwGalleryFilter(search = "a".repeat(20)).activeSummary)
        assertEquals(
            "搜索「cat」 · 标签「v1」 · 今天 · 只看未审核 · 导入 · 已收藏",
            NsfwGalleryFilter(search = "cat", tag = "v1", dates = NsfwGalleryDates.TODAY, status = "NEW",
                category = "imported", favorite = true).activeSummary,
        )
    }

    @Test fun searchAndTagLimitsAreSharedWithInputCap() {
        // 输入框与服务端校验共用同一上限：刚好等于上限可用，超一个字符拒绝。
        NsfwGalleryFilter(search = "a".repeat(NSFW_GALLERY_SEARCH_MAX_LENGTH)).resolve()
        try {
            NsfwGalleryFilter(search = "a".repeat(NSFW_GALLERY_SEARCH_MAX_LENGTH + 1)).resolve()
            fail("超长搜索应被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertEquals("搜索关键词最多200字", expected.message)
        }
        NsfwGalleryFilter(tag = "t".repeat(NSFW_GALLERY_TAG_MAX_LENGTH)).resolve()
        try {
            NsfwGalleryFilter(tag = "t".repeat(NSFW_GALLERY_TAG_MAX_LENGTH + 1)).resolve()
            fail("超长标签应被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertEquals("标签最多100字", expected.message)
        }
    }

    @Test fun customDateRangeRequiresOrderedBounds() {
        assertFalse(isValidGalleryDateRange(null, null))
        assertFalse(isValidGalleryDateRange(1L, null))
        assertFalse(isValidGalleryDateRange(null, 1L))
        assertTrue(isValidGalleryDateRange(1L, 1L))
        assertTrue(isValidGalleryDateRange(1L, 2L))
        assertFalse(isValidGalleryDateRange(2L, 1L))
    }

    @Test fun unreviewedMembershipDropsBothDecisionOutcomes() {
        val query = NsfwGalleryFilter(status = "NEW").resolve()
        assertEquals(true, query.matches(nsfwItem("a").copy(status = "NEW")))
        assertEquals(false, query.matches(nsfwItem("a").copy(status = "KEEP")))
        assertEquals(false, query.matches(nsfwItem("a").copy(status = "REJECT")))
    }

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
        assertNull(filter.copy(search = "needle").resolve().matches(image))
        assertNull(filter.copy(tag = "curated").resolve().matches(image))
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
