package com.mediareview.app.feature.v2.nsfw.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val NSFW_GALLERY_CAPABILITY = "mobile_gallery_query_v1"
const val NSFW_BIDIRECTIONAL_CAPABILITY = "mobile_gallery_bidirectional_v1"

@Serializable
enum class NsfwGallerySort(val wire: String, val label: String) {
    NEWEST("created_desc", "最新在前"), OLDEST("created_asc", "最早在前")
}

@Serializable
enum class NsfwGalleryDates(val label: String) {
    ALL("全部日期"), TODAY("今天"), YESTERDAY("昨天"), LAST_SEVEN("近7天"), CUSTOM("自选日期")
}

@Serializable
data class NsfwGalleryFilter(
    val sort: NsfwGallerySort = NsfwGallerySort.NEWEST,
    val dates: NsfwGalleryDates = NsfwGalleryDates.ALL,
    val startDate: String? = null,
    val endDate: String? = null,
) {
    val label: String get() = if (dates == NsfwGalleryDates.CUSTOM) "$startDate 至 $endDate" else dates.label

    fun resolve(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): NsfwGalleryQuery {
        if (dates == NsfwGalleryDates.ALL) return NsfwGalleryQuery(this)
        val today = now.atZone(zone).toLocalDate()
        val (start, end) = when (dates) {
            NsfwGalleryDates.TODAY -> today to today
            NsfwGalleryDates.YESTERDAY -> today.minusDays(1) to today.minusDays(1)
            NsfwGalleryDates.LAST_SEVEN -> today.minusDays(6) to today
            NsfwGalleryDates.CUSTOM -> LocalDate.parse(requireNotNull(startDate)) to LocalDate.parse(requireNotNull(endDate))
            else -> error("无效日期范围")
        }
        require(!end.isBefore(start)) { "结束日期不能早于开始日期" }
        val format = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        return NsfwGalleryQuery(this, format.format(start.atStartOfDay(zone).toInstant()),
            format.format(end.plusDays(1).atStartOfDay(zone).toInstant()))
    }
}

@Serializable
data class NsfwGalleryQuery(
    val filter: NsfwGalleryFilter = NsfwGalleryFilter(),
    val fromUtc: String? = null,
    val beforeUtc: String? = null,
) {
    fun contains(timestamp: String): Boolean = runCatching {
        val instant = Instant.parse(timestamp)
        (fromUtc == null || !instant.isBefore(Instant.parse(fromUtc))) &&
            (beforeUtc == null || instant.isBefore(Instant.parse(beforeUtc)))
    }.getOrDefault(false)
}

@Serializable
data class NsfwGalleryRange(
    @SerialName("from_utc") val fromUtc: String? = null,
    @SerialName("before_utc") val beforeUtc: String? = null,
)

@Serializable
data class NsfwGalleryPage(
    val items: List<NsfwFeedItem> = emptyList(),
    @SerialName("previous_page_cursor") val previousPageCursor: String? = null,
    @SerialName("start_index") val startIndex: Int? = null,
    @SerialName("next_page_cursor") val nextPageCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    val total: Int = 0,
    @SerialName("sync_cursor") val syncCursor: String = "",
    @SerialName("scope_cursor") val scopeCursor: String = "",
    val sort: String = "created_desc",
    val range: NsfwGalleryRange = NsfwGalleryRange(),
)

@Serializable
data class NsfwSavedGalleryReview(val query: NsfwGalleryQuery, val scopeCursor: String, val imageId: String)
