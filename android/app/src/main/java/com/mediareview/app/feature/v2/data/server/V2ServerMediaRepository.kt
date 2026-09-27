package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.datastore.ServerProfileStore
import com.mediareview.app.core.model.Envelope
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.model.ProgressRequest
import com.mediareview.app.core.network.ApiFactory
import com.mediareview.app.core.network.MediaReviewApi
import com.mediareview.app.feature.v2.data.AlbumCoverStore
import com.mediareview.app.feature.v2.data.MediaRepository
import com.mediareview.app.feature.v2.data.V2DataMode
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaPage
import com.mediareview.app.feature.v2.model.V2MediaQuery
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import com.mediareview.app.feature.v2.model.V2TypeFilter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/**
 * 服务器配置读取端口（生产实现读 DataStore；合同测试注入假实现，无需 Android Context）。
 */
internal interface V2ServerProfilePort {
    suspend fun baseUrl(): String
}

/** 相册自选封面端口（生产实现为本地 DataStore；测试注入内存实现）。 */
internal interface V2ServerAlbumCoverPort {
    suspend fun allCovers(): Map<String, String>
    suspend fun setCover(folderId: String, mediaId: String?)
}

/**
 * Production（真实 Server）媒体仓库 —— Stage 8A。
 *
 * 架构位置：
 * ```
 * V2 UI → V2MediaRepositoryRouter → V2ServerMediaRepository → 现有 MediaReviewApi → FastAPI → Jellyfin
 * ```
 *
 * 复用既有网络层（ApiFactory / MediaReviewApi / ServerProfileStore / TokenProvider / MediaUrlResolver），
 * 不新建 Retrofit、不新建 Token 体系、不新建 Jellyfin 客户端。
 *
 * 明确不做（Stage 8B 范围）：Review Session（创建/恢复队列、seen、position）。
 *
 * 已知边界：
 * - [media] / [mediaInFolder] / [search] 是 Demo/Review 兼容路径，Server 下只取
 *   **有界首屏**（绝不全量读取）；正式列表一律走 [mediaPage]；
 * - [playbackUri] / [playbackHeaders] 在 Server 模式 fail-fast（正式播放路径是 [resolvePlayback]）。
 */
@Singleton
class V2ServerMediaRepository internal constructor(
    private val profilePort: V2ServerProfilePort,
    private val apiFactory: ApiFactory,
    private val mapper: V2MediaMapper,
    private val playbackResolver: V2PlaybackResolver,
    private val albumCoverPort: V2ServerAlbumCoverPort,
    private val statusStore: V2ServerStatusStore,
    /**
     * Server 资源缓存（封面 / 原图 / 已映射媒体）。
     *
     * 阶段 8B 起由 Hilt 提供**单例**并被 Review 会话仓库共享：批阅队列里的媒体
     * 也必须出现在同一份缓存里，完整播放器才能通过 `mediaById` 打开当前媒体。
     */
    private val resources: V2ServerResourceCache = V2ServerResourceCache(),
) : MediaRepository {

    @Inject
    constructor(
        profileStore: ServerProfileStore,
        apiFactory: ApiFactory,
        mapper: V2MediaMapper,
        playbackResolver: V2PlaybackResolver,
        albumCoverStore: AlbumCoverStore,
        statusStore: V2ServerStatusStore,
        resources: V2ServerResourceCache,
    ) : this(
        profilePort = object : V2ServerProfilePort {
            override suspend fun baseUrl(): String = profileStore.current().baseUrl
        },
        apiFactory = apiFactory,
        mapper = mapper,
        playbackResolver = playbackResolver,
        albumCoverPort = object : V2ServerAlbumCoverPort {
            override suspend fun allCovers(): Map<String, String> = albumCoverStore.allCovers()
            override suspend fun setCover(folderId: String, mediaId: String?) =
                albumCoverStore.setCover(folderId, mediaId)
        },
        statusStore = statusStore,
        resources = resources,
    )

    override val mode: V2DataMode = V2DataMode.SERVER

    /**
     * 文件夹短时缓存（Stage 8A.1 / 8A.1.1）。
     *
     * 背景：首页启动会同时需要 folders（文件夹栏）与 albums（书架），
     * 而 `albums()` 内部也调用 `folders()`，导致启动过程重复请求 `GET /media/folders`。
     *
     * 方案：短 TTL + 单飞（mutex 跨网络请求持有）。
     *
     * 阶段 8A.1.1 §4：缓存**必须绑定 server 身份** —— 只靠 TTL 不够，
     * 用户从 Server A 切到 Server B 时（仍在 TTL 内）也必须重新请求。
     */
    private data class FoldersCacheEntry(
        val baseUrl: String,
        val loadedAtMs: Long,
        val data: List<V2Folder>,
    )

    private val foldersMutex = Mutex()
    private var foldersCacheEntry: FoldersCacheEntry? = null

    // ---------- 访问层 ----------

    private suspend fun apiWithBase(): Pair<MediaReviewApi, String> {
        val baseUrl = profilePort.baseUrl()
        require(baseUrl.isNotBlank()) { "尚未连接服务器" }
        return apiFactory.create(baseUrl) to baseUrl
    }

    /** 统一请求包装：成功标记在线、失败按错误类型更新连接状态，异常继续上抛给调用方。 */
    private suspend fun <T> call(block: suspend (MediaReviewApi, String) -> T): T {
        return try {
            val (api, base) = apiWithBase()
            val result = block(api, base)
            statusStore.onRequestSuccess()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            statusStore.onRequestFailure(error)
            throw error
        }
    }

    private fun <T> unwrap(resp: Envelope<T>): T {
        if (!resp.success) {
            throw IllegalStateException(resp.error?.message ?: "服务器返回错误")
        }
        return resp.data ?: throw IllegalStateException("服务器返回空数据")
    }

    // ---------- 列表 / 文件夹 ----------

    override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage = call { api, base ->
        // Stage 8A.1: 收藏状态随 MediaSummary 一起下发,不再先拉取整个收藏列表
        val envelope = api.media(
            libraryId = null,
            mediaType = V2ServerWire.mediaType(query.spec),
            sortBy = V2ServerWire.sortBy(query.spec),
            sortOrder = V2ServerWire.sortOrder(query.spec),
            page = query.page,
            pageSize = query.pageSize,
            search = query.search?.takeIf { it.isNotBlank() },
            excludeFavorites = false,
            folderId = query.folderId,
        )
        val page = unwrap(envelope)
        V2MediaPage(
            items = mapAndCache(page.items, base),
            page = page.page,
            pageSize = page.page_size,
            total = page.total,
        )
    }

    override suspend fun folders(): List<V2Folder> {
        val baseUrl = profilePort.baseUrl()
        require(baseUrl.isNotBlank()) { "尚未连接服务器" }
        foldersMutex.lock()
        try {
            val cached = foldersCacheEntry
            // TTL 只对同一个服务器有效：换服务器后即使仍在 TTL 内也必须重新请求
            if (cached != null &&
                cached.baseUrl == baseUrl &&
                nowMs() - cached.loadedAtMs <= FOLDERS_CACHE_TTL_MS
            ) {
                return cached.data
            }
            val loaded = call { api, _ -> unwrap(api.mediaFolders()).map(mapper::mapFolder) }
            foldersCacheEntry = FoldersCacheEntry(baseUrl, nowMs(), loaded)
            return loaded
        } finally {
            foldersMutex.unlock()
        }
    }

    /**
     * 失效辅助数据缓存（文件夹/书架）。
     *
     * 阶段 8A.1.1 §4：切换数据源 / 切换服务器地址 / 断开连接 / 重新配对时必须调用，
     * 否则可能命中另一个服务器的缓存。
     */
    override fun invalidateAuxiliaryCache() {
        foldersCacheEntry = null
    }

    override suspend fun media(): List<V2Media> =
        mediaPage(V2MediaQuery(page = 1, pageSize = COMPAT_PAGE_SIZE)).items

    override suspend fun media(spec: V2SortSpec): List<V2Media> =
        mediaPage(V2MediaQuery(page = 1, pageSize = COMPAT_PAGE_SIZE, spec = spec)).items

    override suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media> =
        mediaPage(
            V2MediaQuery(page = 1, pageSize = COMPAT_PAGE_SIZE, folderId = folderId, spec = spec),
        ).items

    override suspend fun search(query: String, spec: V2SortSpec): List<V2Media> =
        mediaPage(
            V2MediaQuery(page = 1, pageSize = COMPAT_PAGE_SIZE, search = query, spec = spec),
        ).items

    override fun mediaById(id: String): V2Media? = resources.media(id)

    // ---------- 收藏（服务器确认制） ----------

    override suspend fun setFavorite(mediaId: String, favorite: Boolean): Boolean {
        val confirmed = try {
            call { api, _ ->
                if (favorite) unwrap(api.addFavorite(mediaId)) else unwrap(api.removeFavorite(mediaId))
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // 失败：不改内存状态（UI 保持原状态 + Snackbar），禁止假成功
            false
        }
        if (!confirmed) return false
        // 只更新本地已缓存条目;后续 mediaPage 会以服务端 is_favorite 为准
        resources.updateFavorite(mediaId, favorite)
        return true
    }

    override suspend fun favorites(): List<V2Media> = call { api, base ->
        // 仅在用户首次进入收藏页时调用(不再阻塞首页);收藏状态由服务端随摘要下发
        val items = unwrap(api.listFavorites()).mapNotNull { it.media }
        mapAndCache(items, base)
    }

    // ---------- Review 待删除（Stage 8B §35：Server 模式真正接通 delete-queue） ----------

    override suspend fun markReviewed(mediaId: String) {
        // 批阅"已看"属于 Review Session（Stage 8B 起由 V2ReviewSessionRepository 走
        // POST /review/sessions/{id}/seen），这里保持空实现，不伪造本地批阅状态。
    }

    /**
     * 待删除集合：`GET /delete-queue`。
     *
     * 失败时上抛（调用方决定提示与重试），**绝不返回空集假装成功** ——
     * 空集会让 UI 认为"没有任何待删除"，从而丢失服务端已有的待删除标记。
     */
    override suspend fun pendingDeleteIds(): Set<String> = call { api, _ ->
        unwrap(api.listDeleteQueue()).mapNotNull { it.media_id.ifBlank { null } }.toSet()
    }

    /**
     * 加入 / 移除待删除：`POST /delete-queue/{id}` / `DELETE /delete-queue/{id}`。
     * 只有服务器确认成功才返回 true（§36：失败 UI 不变 + Snackbar）。
     */
    override suspend fun setPendingDelete(mediaId: String, pending: Boolean): Boolean = try {
        call { api, _ ->
            if (pending) unwrap(api.enqueueDelete(mediaId)) else unwrap(api.dequeueDelete(mediaId))
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    override suspend fun unmarkReviewed(mediaId: String) {
        // 重新批阅在 Server 模式 = 新建 Review Session（由 V2ReviewSessionRepository 处理），
        // 不逐项重置旧会话（§39）。
    }

    // ---------- 播放 ----------

    override suspend fun resolvePlayback(mediaId: String): V2PlaybackSource = call { api, base ->
        val dto = unwrap(api.playback(mediaId))
        playbackResolver.resolve(dto, base)
    }

    /** 播放进度上报：失败不打扰用户（静默），但不伪造成功。 */
    override suspend fun reportProgress(mediaId: String, positionMs: Long, isPaused: Boolean) {
        try {
            call { api, _ ->
                unwrap(
                    api.reportProgress(
                        mediaId,
                        ProgressRequest(position_ms = positionMs.coerceAtLeast(0L), is_paused = isPaused),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // 进度上报属于后台闭环，失败只影响 Jellyfin 续播位置，不打断播放
        }
    }

    override fun playbackUri(mediaId: String): String =
        throw IllegalStateException("Server 模式禁止同步播放地址，请使用 resolvePlayback()")

    override fun playbackHeaders(mediaId: String): Map<String, String> =
        throw IllegalStateException("Server 模式禁止同步播放请求头，请使用 resolvePlayback()")

    // ---------- 资源 URI ----------

    override fun thumbUri(media: V2Media): String = coverUri(media)

    override fun coverUri(media: V2Media): String = resources.coverUrl(media.id).orEmpty()

    override fun imageUri(media: V2Media): String =
        resources.originalUrl(media.id) ?: resources.coverUrl(media.id).orEmpty()

    override fun spriteUri(media: V2Media): String? = null

    override fun spriteManifest(media: V2Media): V2SpriteManifest? = null

    // ---------- 相册（书架） ----------

    override suspend fun albums(): List<V2Album> {
        val folders = folders()
        val userCovers = albumCoverPort.allCovers()
        return folders.mapNotNull { folder ->
            if (folder.imageCount <= 0) return@mapNotNull null
            val serverCover = folder.coverMediaIds.firstOrNull()
            val userCover = userCovers[folder.id]?.takeIf { candidate ->
                val cached = resources.media(candidate)
                cached == null || (cached.type == V2MediaType.IMAGE && cached.folderId == folder.id)
            }
            V2Album(
                id = folder.id,
                folderId = folder.id,
                name = folder.name,
                imageCount = folder.imageCount,
                coverImageId = userCover ?: serverCover,
            )
        }
    }

    /**
     * 相册分页（阶段 8B §12 / §13）：复用统一分页接口
     * `GET /media?folder_id=…&media_type=image&page=…&page_size=…`，
     * 服务端执行排序与分页；相册再也不受旧的 200 条上限截断。
     */
    override suspend fun albumPage(
        albumId: String,
        page: Int,
        pageSize: Int,
        spec: V2SortSpec,
    ): V2MediaPage = mediaPage(
        V2MediaQuery(
            page = page,
            pageSize = pageSize,
            folderId = albumId,
            spec = spec.copy(typeFilter = V2TypeFilter.IMAGE),
        ),
    )

    override suspend fun setAlbumCover(albumId: String, mediaId: String) {
        val cached = resources.media(mediaId) ?: return
        // 封面只能指向本相册内的照片（与 Demo 相同约束）
        if (cached.type != V2MediaType.IMAGE || cached.folderId != albumId) return
        albumCoverPort.setCover(albumId, mediaId)
    }

    // ---------- helpers ----------

    private fun mapAndCache(
        summaries: List<MediaSummary>,
        baseUrl: String,
    ): List<V2Media> {
        val entries = summaries.map { summary ->
            mapper.mapMedia(summary = summary, baseUrl = baseUrl)
        }
        resources.putAll(entries)
        return entries.map { it.media }
    }

    /** 单调时钟（毫秒）：缓存新鲜度禁止用 wall clock 计算。 */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    private companion object {
        /**
         * 兼容路径的有界窗口：Demo/Review 兼容方法一次最多取这么多条，
         * 正式列表（mediaPage + 滚动加载）不受此限制。真实库再大也不会被一次性拉全量。
         */
        const val COMPAT_PAGE_SIZE = 200

        /**
         * 文件夹短时缓存 TTL。首页启动时 folders 与 albums 会先后调用 `folders()`，
         * 该窗口内共享同一次 HTTP（消除重复的 `GET /media/folders`）。
         */
        const val FOLDERS_CACHE_TTL_MS = 5_000L
    }
}