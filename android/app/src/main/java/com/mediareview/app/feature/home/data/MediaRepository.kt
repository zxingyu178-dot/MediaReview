package com.mediareview.app.feature.home.data

import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.model.LibraryItem
import com.mediareview.app.core.model.MediaPage
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaUrlResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}

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
    private val mediaUrlResolver: MediaUrlResolver,
) : MediaDataSource {
    private val controlledServerOnlyHosts = setOf("localhost")

    private suspend fun pairedBaseUrl(): String = store.current().baseUrl

    private fun resolveMedia(media: MediaSummary, baseUrl: String): MediaSummary = media.copy(
        cover_url = media.cover_url?.takeIf { it.isNotBlank() }?.let {
            mediaUrlResolver.resolve(it, baseUrl, controlledServerOnlyHosts)
        },
        original_url = media.original_url?.takeIf { it.isNotBlank() }?.let {
            mediaUrlResolver.resolve(it, baseUrl, controlledServerOnlyHosts)
        },
    )

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

    override suspend fun loadLibraries(): List<LibraryItem> =
        unwrap(api().libraries()) ?: emptyList()

    override suspend fun saveSelection(selectedIds: List<String>): List<LibraryItem> =
        unwrap(
            api().saveLibrariesSelection(
                com.mediareview.app.core.network.SelectionRequest(selected = selectedIds)
            )
        ) ?: emptyList()

    override suspend fun loadMedia(
        libraryId: String?,
        type: MediaTypeFilter,
        sortBy: SortField,
        sortOrder: SortOrder,
        page: Int,
        pageSize: Int,
        search: String?,
        excludeFavorites: Boolean,
        folderId: String?,
    ): MediaPage {
        val result = unwrap(
            api().media(
            libraryId = libraryId,
            mediaType = type.wire,
            sortBy = sortBy.wire,
            sortOrder = sortOrder.wire,
            page = page,
            pageSize = pageSize,
            search = search,
            excludeFavorites = excludeFavorites,
            folderId = folderId,
            ),
        ) ?: MediaPage()
        val baseUrl = pairedBaseUrl()
        return result.copy(items = result.items.map { resolveMedia(it, baseUrl) })
    }

    override suspend fun loadMediaFolders(
        libraryId: String?,
        type: MediaTypeFilter,
        search: String?,
        excludeFavorites: Boolean,
    ): List<com.mediareview.app.core.model.MediaFolderItem> =
        unwrap(
            api().mediaFolders(
                libraryId = libraryId,
                mediaType = type.wire,
                search = search,
                excludeFavorites = excludeFavorites,
            ),
        ) ?: emptyList()

    suspend fun loadDetail(mediaId: String): MediaSummary? = runSuspendCatching {
        unwrap(api().mediaDetail(mediaId))?.let { resolveMedia(it, pairedBaseUrl()) }
    }.getOrNull()

    /** 后台任务状态(雪碧图生成进度等);失败返回 null 由调用方兜底。 */
    suspend fun loadTask(taskId: String): com.mediareview.app.core.model.TaskStateDto? =
        runSuspendCatching { unwrap(api().taskDetail(taskId)) }.getOrNull()

    /** 协作取消后台任务;失败返回 null。 */
    suspend fun cancelTask(taskId: String): com.mediareview.app.core.model.TaskStateDto? =
        runSuspendCatching { unwrap(api().cancelTask(taskId)) }.getOrNull()

    /**
     * 读取雪碧图清单;url 为服务端相对路径,补全为绝对地址供 Coil 加载。
     * 未生成/未就绪时服务端返回 404,抛异常由调用方决定触发后台生成。
     */
    suspend fun loadSpriteManifest(mediaId: String): com.mediareview.app.core.model.SpriteManifestDto {
        val m = unwrap(api().spriteManifest(mediaId))
            ?: throw IllegalStateException("雪碧图尚未生成")
        if (m.url.isNullOrBlank()) return m
        return m.copy(url = mediaUrlResolver.resolve(m.url, pairedBaseUrl(), controlledServerOnlyHosts))
    }

    /** 触发雪碧图后台生成(服务端幂等,已就绪则直返);返回任务引用供进度/取消。 */
    suspend fun ensureSprite(mediaId: String): com.mediareview.app.core.model.SpriteEnsureOut? =
        runSuspendCatching { unwrap(api().ensureSprite(mediaId)) }.getOrNull()

    /** 获取播放信息(Jellyfin 直连流地址)。 */
    override suspend fun loadPlayback(mediaId: String): com.mediareview.app.core.model.PlaybackInfoDto? =
        runSuspendCatching {
            unwrap(api().playback(mediaId))?.let { playback ->
                playback.copy(
                    stream_url = mediaUrlResolver.resolve(
                        playback.stream_url,
                        pairedBaseUrl(),
                        playback.stream_url_rewrite_hosts.toSet(),
                        authoritative = playback.stream_url_authoritative,
                        legacyServerOnlyHeuristics = playback.stream_url_source == "legacy",
                    ),
                )
            }
        }.getOrNull()

    /** 创建批阅会话:只提交 source(筛选/排序),队列由服务端按已选媒体库构建。 */
    override suspend fun createReviewSession(): com.mediareview.app.core.model.ReviewSessionDto? =
        runSuspendCatching {
            unwrap(api().createReviewSession(com.mediareview.app.core.model.ReviewCreateRequest()))
        }.getOrNull()

    /** 最近活动的批阅会话(断点恢复)。 */
    override suspend fun latestReviewSession(): com.mediareview.app.core.model.ReviewSessionDto? =
        runSuspendCatching { unwrap(api().latestReviewSession()) }.getOrNull()

    /** 批阅队列分页(按会话顺序,含媒体摘要)。 */
    override suspend fun reviewQueue(
        sessionId: String,
        page: Int,
        pageSize: Int,
    ): com.mediareview.app.core.model.ReviewQueuePageDto = runSuspendCatching {
        val result = unwrap(api().reviewQueue(sessionId, page, pageSize))
            ?: com.mediareview.app.core.model.ReviewQueuePageDto()
        val baseUrl = pairedBaseUrl()
        result.copy(
            items = result.items.map { item ->
                item.copy(media = item.media?.let { resolveMedia(it, baseUrl) })
            },
        )
    }.getOrDefault(com.mediareview.app.core.model.ReviewQueuePageDto())

    /** 标记当前会话中某媒体已看(什么都不操作也记录 session_seen)。 */
    override suspend fun markSeen(sessionId: String, mediaId: String) {
        runSuspendCatching {
            unwrap(api().markSeen(sessionId, com.mediareview.app.core.model.ReviewSeenRequest(media_id = mediaId)))
        }
    }

    /** 点赞(只写本项目 SQLite,不改原文件)。成功才返回 true。 */
    override suspend fun addFavorite(mediaId: String): Boolean =
        runSuspendCatching { unwrap(api().addFavorite(mediaId)) != null }.getOrDefault(false)

    /** 取消点赞。成功才返回 true。 */
    override suspend fun removeFavorite(mediaId: String): Boolean =
        runSuspendCatching { unwrap(api().removeFavorite(mediaId)) != null }.getOrDefault(false)

    /** 加入待删除队列(可撤销,不立即删除)。 */
    override suspend fun enqueueDelete(mediaId: String): Boolean =
        runSuspendCatching { unwrap(api().enqueueDelete(mediaId)) != null }.getOrDefault(false)

    /** 从待删除队列撤销。服务器成功才返回 true。 */
    override suspend fun dequeueDelete(mediaId: String): Boolean =
        runSuspendCatching { unwrap(api().dequeueDelete(mediaId)) != null }.getOrDefault(false)

    /** 随批阅位置移动更新 current_index(断点恢复依据)。 */
    override suspend fun setReviewPosition(sessionId: String, index: Int) {
        runSuspendCatching {
            unwrap(
                api().setReviewPosition(
                    sessionId,
                    com.mediareview.app.core.model.ReviewPositionRequest(index = index),
                )
            )
        }
    }

    /** 收藏列表(供喜欢页/批阅启动恢复)。 */
    override suspend fun listFavorites(): List<com.mediareview.app.core.model.FavoriteItemDto> = runSuspendCatching {
        val baseUrl = pairedBaseUrl()
        (unwrap(api().listFavorites()) ?: emptyList()).map { item ->
            item.copy(media = item.media?.let { resolveMedia(it, baseUrl) })
        }
    }.getOrDefault(emptyList())

    /** 待删除队列(供待删除页/批阅启动恢复)。 */
    override suspend fun listDeleteQueue(): List<com.mediareview.app.core.model.DeleteQueueItemDto> =
        runSuspendCatching {
            val baseUrl = pairedBaseUrl()
            (unwrap(api().listDeleteQueue()) ?: emptyList()).map { item ->
                item.copy(media = item.media?.let { resolveMedia(it, baseUrl) })
            }
        }.getOrDefault(emptyList())

    /** 两阶段最终删除:先 prepare 取得一次性 nonce(绑定队列快照)。 */
    override suspend fun prepareDeleteCommit(): com.mediareview.app.core.model.DeleteCommitPrepDto? =
        runSuspendCatching { unwrap(api().prepareDeleteCommit()) }.getOrNull()

    /** 携带一次性 nonce 确认并真实删除(两阶段删除的最后一步)。 */
    override suspend fun commitDeleteQueue(nonce: String): com.mediareview.app.core.model.CommitResultDto? =
        runSuspendCatching {
            unwrap(api().commitDeleteQueue(com.mediareview.app.core.model.CommitRequest(nonce)))
        }.getOrNull()

    /** 完全重复分组。 */
    override suspend fun loadDuplicatesExact(): List<com.mediareview.app.core.model.DuplicateGroupDto> =
        runSuspendCatching { unwrap(api().duplicatesExact()) }.getOrNull() ?: emptyList()

    /** 疑似重复分组。 */
    override suspend fun loadDuplicatesSimilar(): List<com.mediareview.app.core.model.DuplicateGroupDto> =
        runSuspendCatching { unwrap(api().duplicatesSimilar()) }.getOrNull() ?: emptyList()

    /** 回传播放进度到 Jellyfin(普通播放器/批阅播放器周期性调用)。 */
    override suspend fun reportProgress(mediaId: String, positionMs: Long, isPaused: Boolean) {
        runSuspendCatching {
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
