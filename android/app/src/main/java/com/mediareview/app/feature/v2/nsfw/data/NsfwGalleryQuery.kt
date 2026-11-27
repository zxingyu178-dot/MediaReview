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

/** 搜索关键词上限：输入框截断与服务端校验共用，避免提交后才报错。 */
const val NSFW_GALLERY_SEARCH_MAX_LENGTH = 200

/** 版本标签上限（精确匹配，非生成版本标签）。 */
const val NSFW_GALLERY_TAG_MAX_LENGTH = 100

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
        require((search?.length ?: 0) <= NSFW_GALLERY_SEARCH_MAX_LENGTH) { "搜索关键词最多${NSFW_GALLERY_SEARCH_MAX_LENGTH}字" }
        require((tag?.length ?: 0) <= NSFW_GALLERY_TAG_MAX_LENGTH) { "标签最多${NSFW_GALLERY_TAG_MAX_LENGTH}字" }
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

/** 空结果是否由筛选（含搜索/标签/日期）造成；排序不算筛选。 */
val NsfwGalleryFilter.emptyStateNarrowed: Boolean
    get() = selection.active || dates != NsfwGalleryDates.ALL

/** 空结果标题：有筛选说“无匹配图片”，否则是图库本身为空。 */
val NsfwGalleryFilter.emptyStateLabel: String
    get() = if (emptyStateNarrowed) "无匹配图片" else "暂无图片"

/** 空结果直达动作：有筛选一键清除，否则重新增量同步。 */
val NsfwGalleryFilter.emptyStateActionLabel: String
    get() = if (emptyStateNarrowed) "清除筛选" else "刷新图库"

/** 摘要里关键词保留的开头长度；超出部分用省略号，避免大字体下挤占“清除”按钮。 */
private const val GALLERY_SUMMARY_KEYWORD_MAX = 12

/**
 * 顶部筛选条摘要：直接说明当前生效条件，不必重新打开筛选面板。
 * 顺序固定为搜索、标签、日期、状态、分类、收藏、提示词；排序不算筛选。
 */
val NsfwGalleryFilter.activeSummary: String
    get() {
        fun keyword(value: String): String =
            value.take(GALLERY_SUMMARY_KEYWORD_MAX) + if (value.length > GALLERY_SUMMARY_KEYWORD_MAX) "…" else ""
        val parts = buildList {
            search?.trim()?.takeIf { it.isNotEmpty() }?.let { add("搜索「${keyword(it)}」") }
            tag?.trim()?.takeIf { it.isNotEmpty() }?.let { add("标签「${keyword(it)}」") }
            if (dates != NsfwGalleryDates.ALL) add(label)
            status?.let { add(statusSummaryLabel(it)) }
            category?.let { add(if (it == "anime") "动漫" else "导入") }
            favorite?.let { add(if (it) "已收藏" else "未收藏") }
            promptId?.let { add(if (versionId != null) "提示词版本" else "提示词") }
        }
        return parts.joinToString(" · ").ifEmpty { "筛选已启用" }
    }

private fun statusSummaryLabel(status: String): String = when (status) {
    "NEW" -> "只看未审核"
    "NOT_REJECTED" -> "隐藏已淘汰"
    "KEEP" -> "已保留"
    "REJECT" -> "已淘汰"
    "ARCHIVED" -> "已归档"
    "FAVORITE" -> "已收藏"
    else -> "状态筛选"
}

/** 自选日期区间是否可应用：两端都已选且结束不早于开始（与服务端 resolve 校验一致）。 */
fun isValidGalleryDateRange(startMillis: Long?, endMillis: Long?): Boolean =
    startMillis != null && endMillis != null && endMillis >= startMillis

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
