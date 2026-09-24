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
) : MediaRepository {

    @Inject
    constructor(
        profileStore: ServerProfileStore,
        apiFactory: ApiFactory,
        mapper: V2MediaMapper,
        playbackResolver: V2PlaybackResolver,
        albumCoverStore: AlbumCoverStore,
        statusStore: V2ServerStatusStore,
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
    )

    override val mode: V2DataMode = V2DataMode.SERVER

    /** Server 资源缓存：封面 / 原图绝对 URL 与已映射媒体（UI 不接触 DTO）。 */
    private val resources = V2ServerResourceCache()

    /** 收藏 id 集合：null = 尚未从服务器读取（读取一次后由收藏操作维护）。 */
    private var favoriteIds: Set<String>? = null

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

    private suspend fun favoriteIdSet(api: MediaReviewApi): Set<String> {
        favoriteIds?.let { return it }
        val loaded = runCatching { unwrap(api.listFavorites()).map { it.media_id }.toSet() }
            .getOrDefault(emptySet())
        favoriteIds = loaded
        return loaded
    }

    // ---------- 列表 / 文件夹 ----------

    override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage = call { api, base ->
        val favorites = favoriteIdSet(api)
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
            items = mapAndCache(page.items, base, favorites),
            page = page.page,
            pageSize = page.page_size,
            total = page.total,
        )
    }

    override suspend fun folders(): List<V2Folder> = call { api, _ ->
        unwrap(api.mediaFolders()).map(mapper::mapFolder)
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
        favoriteIds = (favoriteIds ?: emptySet()).let {
            if (favorite) it + mediaId else it - mediaId
        }
        resources.updateFavorite(mediaId, favorite)
        return true
    }

    override suspend fun favorites(): List<V2Media> = call { api, base ->
        val items = unwrap(api.listFavorites())
        favoriteIds = items.map { it.media_id }.toSet()
        val mapped = mapAndCache(items.mapNotNull { it.media }, base, favoriteIds.orEmpty())
        mapped.map { it.copy(isFavorite = true) }
    }

    // ---------- Review 兼容路径（Stage 8A 不接服务器 Review Session） ----------

    override suspend fun markReviewed(mediaId: String) {
        // Stage 8B 才接 /review/sessions（Demo 语义 isReviewed 与 Server seen 生命周期不同）。
        // Server 模式下 V2ReviewScreen 显示"真实批阅接入将在 Stage 8B 完成"，不会走到这里。
    }

    override suspend fun pendingDeleteIds(): Set<String> = emptySet()

    override suspend fun setPendingDelete(mediaId: String, pending: Boolean) {
        // 同上：待删除队列属于 Review Session / delete-queue 对接，Stage 8B 处理。
    }

    override suspend fun unmarkReviewed(mediaId: String) {
        // 同上：Stage 8B。
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

    override suspend fun imagesInAlbum(albumId: String, spec: V2SortSpec): List<V2Media> =
        mediaPage(
            V2MediaQuery(
                page = 1,
                pageSize = COMPAT_PAGE_SIZE,
                folderId = albumId,
                spec = spec.copy(typeFilter = V2TypeFilter.IMAGE),
            ),
        ).items

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
        favorites: Set<String>,
    ): List<V2Media> {
        val entries = summaries.map { summary ->
            mapper.mapMedia(
                summary = summary,
                baseUrl = baseUrl,
                isFavorite = summary.media_id in favorites,
            )
        }
        resources.putAll(entries)
        return entries.map { it.media }
    }

    private companion object {
        /**
         * 兼容路径的有界窗口：Demo/Review 兼容方法一次最多取这么多条，
         * 正式列表（mediaPage + 滚动加载）不受此限制。真实库再大也不会被一次性拉全量。
         */
        const val COMPAT_PAGE_SIZE = 200
    }
}