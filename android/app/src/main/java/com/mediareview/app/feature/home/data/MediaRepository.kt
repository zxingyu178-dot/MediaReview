package com.mediareview.app.feature.home.data

import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.MediaPage
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.network.ApiFactory
import javax.inject.Inject
import javax.inject.Singleton

/** 枚举:类型筛选。 */
enum class MediaTypeFilter(val wire: String?, val label: String) {
    All(null, "全部"),
    Video("video", "视频"),
    Image("image", "图片");

    companion object {
        fun fromWire(v: String?): MediaTypeFilter =
            entries.firstOrNull { it.wire == v } ?: All
    }
}

/** 枚举:排序字段。 */
enum class SortField(val wire: String, val label: String) {
    Name("name", "名称"),
    Created("created", "添加时间"),
    Size("size", "大小"),
    Duration("duration", "时长"),
    Resolution("resolution", "分辨率"),
    Random("random", "随机"),
}

/** 排序方向。 */
enum class SortOrder(val wire: String) {
    Asc("asc"),
    Desc("desc"),
}

/**
 * 媒体墙数据源:加载媒体库勾选与分页媒体(依赖 token 的受保护接口)。
 * 服务器地址取自已配对配置。
 */
@Singleton
class MediaRepository @Inject constructor(
    private val store: ServerProfileStore,
    private val apiFactory: ApiFactory,
) {

    private suspend fun api(): com.mediareview.app.core.network.MediaReviewApi {
        val profile = store.current()
        if (profile.baseUrl.isBlank()) {
            throw IllegalStateException("尚未配置服务器")
        }
        return apiFactory.create(profile.baseUrl)
    }

    private fun <T> unwrap(resp: com.mediareview.app.core.model.Envelope<T>): T? {
        if (!resp.success) {
            throw RuntimeException(resp.error?.message ?: "服务器返回错误")
        }
        return resp.data
    }

    suspend fun loadLibraries(): List<LibraryItem> =
        unwrap(api().libraries()) ?: emptyList()

    suspend fun saveSelection(selectedIds: List<String>): List<LibraryItem> =
        unwrap(
            api().saveLibrariesSelection(
                com.mediareview.app.core.network.SelectionRequest(selected = selectedIds)
            )
        ) ?: emptyList()

    suspend fun loadMedia(
        libraryId: String? = null,
        type: MediaTypeFilter = MediaTypeFilter.All,
        sortBy: SortField = SortField.Name,
        sortOrder: SortOrder = SortOrder.Asc,
        page: Int = 1,
        pageSize: Int = 50,
        search: String? = null,
        excludeFavorites: Boolean = false,
    ): MediaPage = unwrap(
        api().media(
            libraryId = libraryId,
            mediaType = type.wire,
            sortBy = sortBy.wire,
            sortOrder = sortOrder.wire,
            page = page,
            pageSize = pageSize,
            search = search,
            excludeFavorites = excludeFavorites,
        )
    ) ?: MediaPage()

    suspend fun loadDetail(mediaId: String): MediaSummary? =
        runCatching { unwrap(api().mediaDetail(mediaId)) }.getOrNull()

    /**
     * 读取雪碧图清单;url 为服务端相对路径,补全为绝对地址供 Coil 加载。
     * 未生成/未就绪时服务端返回 404,抛异常由调用方决定触发后台生成。
     */
    suspend fun loadSpriteManifest(mediaId: String): com.mediareview.app.core.model.SpriteManifestDto {
        val m = unwrap(api().spriteManifest(mediaId))
            ?: throw IllegalStateException("雪碧图尚未生成")
        if (m.url.isNullOrBlank()) return m
        val base = store.current().baseUrl.trimEnd('/')
        return m.copy(url = "$base${m.url}")
    }

    /** 触发雪碧图后台生成(服务端幂等,已就绪则直返)。 */
    suspend fun ensureSprite(mediaId: String) {
        runCatching { unwrap(api().ensureSprite(mediaId)) }
    }

    /** 获取播放信息(Jellyfin 直连流地址)。 */
    suspend fun loadPlayback(mediaId: String): com.mediareview.app.core.model.PlaybackInfoDto? =
        runCatching { unwrap(api().playback(mediaId)) }.getOrNull()

    /** 创建批阅会话:只提交 source(筛选/排序),队列由服务端按已选媒体库构建。 */
    suspend fun createReviewSession(): com.mediareview.app.core.model.ReviewSessionDto? =
        runCatching {
            unwrap(api().createReviewSession(com.mediareview.app.core.model.ReviewCreateRequest()))
        }.getOrNull()

    /** 最近活动的批阅会话(断点恢复)。 */
    suspend fun latestReviewSession(): com.mediareview.app.core.model.ReviewSessionDto? =
        runCatching { unwrap(api().latestReviewSession()) }.getOrNull()

    /** 批阅队列分页(按会话顺序,含媒体摘要)。 */
    suspend fun reviewQueue(
        sessionId: String,
        page: Int = 1,
        pageSize: Int = 50,
    ): com.mediareview.app.core.model.ReviewQueuePageDto =
        runCatching { unwrap(api().reviewQueue(sessionId, page, pageSize)) }.getOrNull()
            ?: com.mediareview.app.core.model.ReviewQueuePageDto()

    /** 标记当前会话中某媒体已看(什么都不操作也记录 session_seen)。 */
    suspend fun markSeen(sessionId: String, mediaId: String) {
        runCatching {
            unwrap(api().markSeen(sessionId, com.mediareview.app.core.model.ReviewSeenRequest(media_id = mediaId)))
        }
    }

    /** 点赞(只写本项目 SQLite,不改原文件)。成功才返回 true。 */
    suspend fun addFavorite(mediaId: String): Boolean =
        runCatching { unwrap(api().addFavorite(mediaId)) != null }.getOrDefault(false)

    /** 取消点赞。成功才返回 true。 */
    suspend fun removeFavorite(mediaId: String): Boolean =
        runCatching { unwrap(api().removeFavorite(mediaId)) != null }.getOrDefault(false)

    /** 加入待删除队列(可撤销,不立即删除)。 */
    suspend fun enqueueDelete(mediaId: String): Boolean =
        runCatching { unwrap(api().enqueueDelete(mediaId)) != null }.getOrDefault(false)

    /** 从待删除队列撤销。服务器成功才返回 true。 */
    suspend fun dequeueDelete(mediaId: String): Boolean =
        runCatching { unwrap(api().dequeueDelete(mediaId)) != null }.getOrDefault(false)

    /** 随批阅位置移动更新 current_index(断点恢复依据)。 */
    suspend fun setReviewPosition(sessionId: String, index: Int) {
        runCatching {
            unwrap(
                api().setReviewPosition(
                    sessionId,
                    com.mediareview.app.core.model.ReviewPositionRequest(index = index),
                )
            )
        }
    }

    /** 收藏列表(供喜欢页/批阅启动恢复)。 */
    suspend fun listFavorites(): List<com.mediareview.app.core.model.FavoriteItemDto> =
        runCatching { unwrap(api().listFavorites()) }.getOrNull() ?: emptyList()

    /** 待删除队列(供待删除页/批阅启动恢复)。 */
    suspend fun listDeleteQueue(): List<com.mediareview.app.core.model.DeleteQueueItemDto> =
        runCatching { unwrap(api().listDeleteQueue()) }.getOrNull() ?: emptyList()

    /** 最终确认删除待删除队列(两阶段删除的最后一步)。 */
    suspend fun commitDeleteQueue(): com.mediareview.app.core.model.CommitResultDto? =
        runCatching { unwrap(api().commitDeleteQueue()) }.getOrNull()

    /** 完全重复分组。 */
    suspend fun loadDuplicatesExact(): List<com.mediareview.app.core.model.DuplicateGroupDto> =
        runCatching { unwrap(api().duplicatesExact()) }.getOrNull() ?: emptyList()

    /** 疑似重复分组。 */
    suspend fun loadDuplicatesSimilar(): List<com.mediareview.app.core.model.DuplicateGroupDto> =
        runCatching { unwrap(api().duplicatesSimilar()) }.getOrNull() ?: emptyList()

    /** 回传播放进度到 Jellyfin(普通播放器/批阅播放器周期性调用)。 */
    suspend fun reportProgress(mediaId: String, positionMs: Long, isPaused: Boolean) {
        runCatching {
            unwrap(
                api().reportProgress(
                    mediaId,
                    com.mediareview.app.core.model.ProgressRequest(
                        position_ms = positionMs,
                        is_paused = isPaused,
                    ),
                )
            )
        }
    }
}