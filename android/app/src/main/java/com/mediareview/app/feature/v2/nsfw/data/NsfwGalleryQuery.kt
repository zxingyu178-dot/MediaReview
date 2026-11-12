package com.mediareview.app.feature.v2.nsfw.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val NSFW_GALLERY_CAPABILITY = "mobile_gallery_query_v1"
const val NSFW_GALLERY_SEARCH_CAPABILITY = "mobile_gallery_search_v1"
const val NSFW_GALLERY_FILTERS_CAPABILITY = "mobile_gallery_filters_v1"
const val NSFW_GALLERY_REVIEW_FILTER_CAPABILITY = "mobile_gallery_review_filter_v1"
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
    val promptId: String? = null,
    val versionId: String? = null,
    val status: String? = null,
    val category: String? = null,
    val favorite: Boolean? = null,
    val search: String? = null,
    val tag: String? = null,
) {
    val selection: NsfwGallerySelection get() = NsfwGallerySelection(promptId, versionId, status, category, favorite, search, tag)
    val label: String get() = if (dates == NsfwGalleryDates.CUSTOM) "$startDate 至 $endDate" else dates.label

    fun resolve(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): NsfwGalleryQuery {
        require((search?.length ?: 0) <= 200) { "搜索关键词最多200字" }
        require((tag?.length ?: 0) <= 100) { "标签最多100字" }
        val normalized = copy(search = search?.trim()?.takeIf { it.isNotEmpty() }, tag = tag?.trim()?.takeIf { it.isNotEmpty() })
        if (normalized != this) return normalized.resolve(now, zone)
        require(promptId == null || promptId.isNotBlank())
        require(versionId == null || (versionId.isNotBlank() && promptId != null)) { "提示词版本需要所属提示词" }
        require(status == null || status in setOf("NEW", "KEEP", "REJECT", "ARCHIVED", "FAVORITE", "NOT_REJECTED"))
        require(category == null || category in setOf("anime", "imported"))
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
    /** null表示桥未提供分类证据，不等同于不属于任何分类。 */
    fun matches(item: NsfwFeedItem): Boolean? {
        if ((fromUtc != null || beforeUtc != null) && !contains(item.createdAt)) return false
        if (filter.promptId != null && item.prompt?.promptId != filter.promptId) return false
        if (filter.versionId != null && (item.prompt?.isExact != true || item.prompt.promptVersionId != filter.versionId)) return false
        if (filter.status == "NOT_REJECTED") {
            if (item.status == "REJECT") return false
            if (item.status !in setOf("NEW", "KEEP", "ARCHIVED")) return null
        } else if (filter.status != null && if (filter.status == "FAVORITE") !item.isFavorite else item.status != filter.status) return false
        if (filter.favorite != null && item.isFavorite != filter.favorite) return false
        if (filter.category != null) {
            val categories = item.categories ?: return null
            if (filter.category !in categories) return false
        }
        return if (filter.search != null || filter.tag != null) null else true
    }

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
data class NsfwGallerySelection(
    @SerialName("prompt_id") val promptId: String? = null,
    @SerialName("version_id") val versionId: String? = null,
    val status: String? = null,
    val category: String? = null,
    val favorite: Boolean? = null,
    val search: String? = null,
    val tag: String? = null,
) {
    val active: Boolean get() = promptId != null || versionId != null || status != null || category != null || favorite != null || search != null || tag != null
}

@Serializable
data class NsfwGalleryPage(
    val filters: NsfwGallerySelection? = null,
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
