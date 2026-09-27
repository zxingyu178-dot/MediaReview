package com.mediareview.app.feature.v2.data

import android.content.Context
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaPage
import com.mediareview.app.feature.v2.model.V2MediaQuery
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.model.V2PlaybackEndpoint
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.model.V2SortField
import com.mediareview.app.feature.v2.model.V2SortOrder
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import com.mediareview.app.feature.v2.model.V2TypeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Demo 数据仓库：全部数据来自 APK 内置 assets / res-raw，离线可用。
 * 与 [MediaRepository] 接口对应；运行时是否生效由 [V2MediaRepositoryRouter] 决定。
 */
@Singleton
class DemoMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val albumCoverStore: AlbumCoverStore,
) : MediaRepository {

    private val allFolders: List<V2Folder> = DemoMediaCatalog.buildFolders()
    private val allMedia: List<V2Media> = DemoMediaCatalog.buildMedia()
    private val mediaById: Map<String, V2Media> = allMedia.associateBy { it.id }

    /** 文件夹 + 内置统计（Demo 端内存统计；Server 端为服务端聚合）。 */
    private val foldersWithCounts: List<V2Folder> by lazy {
        allFolders.map { folder ->
            val inFolder = allMedia.filter { it.folderId == folder.id }
            folder.copy(
                count = inFolder.size,
                imageCount = inFolder.count { it.type == V2MediaType.IMAGE },
            )
        }
    }

    private val favoriteState = mutableMapOf<String, Boolean>()
    private val reviewedState = mutableMapOf<String, Boolean>()
    private val pendingDeleteState = mutableSetOf<String>()

    // Stage6：雪碧图 manifest 解析缓存。
    // 禁止在 Compose 组合路径重复做 assets IO / JSON 解析——每个 manifest 只解析一次，
    // 之后全部命中内存缓存（key = manifest asset 路径）。
    private val spriteManifestCache = mutableMapOf<String, V2SpriteManifest?>()

    override val mode: V2DataMode = V2DataMode.DEMO

    override suspend fun folders(): List<V2Folder> = foldersWithCounts

    override suspend fun media(): List<V2Media> = allMedia.applyOverridesState()

    /** Demo 分页：内存排序/过滤后切片（Server 模式由服务端执行同样的查询语义）。 */
    override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage {
        val q = query.search?.trim().orEmpty()
        val filtered = allMedia.applyOverridesState()
            .filter { query.folderId == null || it.folderId == query.folderId }
            .filter { q.isEmpty() || it.matches(q) }
            .sorted(query.spec)
        val from = ((query.page - 1).coerceAtLeast(0)) * query.pageSize
        return V2MediaPage(
            items = filtered.drop(from).take(query.pageSize),
            page = query.page,
            pageSize = query.pageSize,
            total = filtered.size,
        )
    }

    override suspend fun media(spec: V2SortSpec): List<V2Media> =
        allMedia.applyOverridesState().sorted(spec)

    override suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media> =
        allMedia.applyOverridesState().filter { it.folderId == folderId }.sorted(spec)

    override suspend fun search(query: String, spec: V2SortSpec): List<V2Media> {
        val q = query.trim()
        if (q.isEmpty()) return media(spec)
        return allMedia.applyOverridesState().filter { it.matches(q) }.sorted(spec)
    }

    override fun mediaById(id: String): V2Media? =
        mediaById[id]?.let { applyOverrides(listOf(it)).firstOrNull() }

    override suspend fun setFavorite(mediaId: String, favorite: Boolean): Boolean {
        favoriteState[mediaId] = favorite
        return true
    }

    override suspend fun favorites(): List<V2Media> =
        allMedia.applyOverridesState().filter { it.isFavorite }

    override suspend fun markReviewed(mediaId: String) {
        reviewedState[mediaId] = true
    }

    override suspend fun pendingDeleteIds(): Set<String> = pendingDeleteState.toSet()

    /** Demo 的待删除是内存状态：本地必然成功（无网络确认环节）。 */
    override suspend fun setPendingDelete(mediaId: String, pending: Boolean): Boolean {
        if (pending) pendingDeleteState += mediaId else pendingDeleteState -= mediaId
        return true
    }

    override suspend fun unmarkReviewed(mediaId: String) {
        reviewedState[mediaId] = false
    }

    /** Demo 播放源：本地 res-raw + 空 headers，无续播位置（resume = 0）。 */
    override suspend fun resolvePlayback(mediaId: String): V2PlaybackSource {
        val m = mediaById[mediaId] ?: throw IllegalStateException("媒体不存在：$mediaId")
        return V2PlaybackSource(
            mediaId = m.id,
            title = m.name,
            direct = V2PlaybackEndpoint(url = DemoAssets.playbackUri(m), headers = emptyMap()),
            fallbackHls = null,
            resumePositionMs = 0L,
            durationMs = m.durationMs.takeIf { it > 0L },
            width = m.naturalWidth.takeIf { it > 0 },
            height = m.naturalHeight.takeIf { it > 0 },
        )
    }

    /** 兼容路径（Review / Demo）：本地资源同步可解析，无需网络。 */
    override fun playbackUri(mediaId: String): String {
        val m = mediaById[mediaId] ?: return ""
        return DemoAssets.playbackUri(m)
    }

    override fun playbackHeaders(mediaId: String): Map<String, String> = emptyMap()

    override fun thumbUri(media: V2Media): String = DemoAssets.thumbUri(media)

    override fun coverUri(media: V2Media): String = DemoAssets.coverUri(media)

    override fun imageUri(media: V2Media): String = DemoAssets.imageUri(media)

    override fun spriteUri(media: V2Media): String? = DemoAssets.spriteUri(media)

    override fun spriteManifest(media: V2Media): V2SpriteManifest? {
        val path = media.spriteManifestPath ?: return null
        // 组合路径只查缓存，命中即返回，不触碰 assets（IO/解析只发生一次）
        return spriteManifestCache.getOrPut(path) { DemoAssets.readSpriteManifest(context, path) }
    }

    // ---------- 相册（书架） ----------

    override suspend fun albums(): List<V2Album> {
        val state = allMedia.applyOverridesState()
        val userCovers = albumCoverStore.allCovers()
        return buildAlbums(allFolders, state, userCovers)
    }

    /**
     * 相册分页（阶段 8B §12）：Demo 为内存排序后切片。
     * 结束判断以 total 为准，与 Server 合同一致（绝不本地截断）。
     */
    override suspend fun albumPage(
        albumId: String,
        page: Int,
        pageSize: Int,
        spec: V2SortSpec,
    ): V2MediaPage {
        val all = allMedia.applyOverridesState()
            .filter { it.folderId == albumId && it.type == V2MediaType.IMAGE }
            .sorted(spec)
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceAtLeast(1)
        val from = ((safePage - 1) * safeSize).coerceAtMost(all.size)
        val to = (from + safeSize).coerceAtMost(all.size)
        return V2MediaPage(
            items = all.subList(from, to),
            page = safePage,
            pageSize = safeSize,
            total = all.size,
        )
    }

    override suspend fun setAlbumCover(albumId: String, mediaId: String) {
        val media = mediaById[mediaId] ?: return
        // 封面只能指向本相册内的照片
        if (media.type != V2MediaType.IMAGE || media.folderId != albumId) return
        albumCoverStore.setCover(albumId, mediaId)
    }

    // ---------- helpers ----------

    private fun applyOverrides(source: List<V2Media> = allMedia): List<V2Media> =
        source.map { m ->
            val fav = favoriteState[m.id]
            val rev = reviewedState[m.id]
            if (fav != null || rev != null) {
                m.copy(
                    isFavorite = fav ?: m.isFavorite,
                    isReviewed = rev ?: m.isReviewed,
                )
            } else m
        }

    private fun List<V2Media>.applyOverridesState(): List<V2Media> = applyOverrides(this)

    /** 搜索匹配（名称/编号/文件夹名）；与 Server 端 search 语义保持一致。 */
    private fun V2Media.matches(query: String): Boolean =
        name.contains(query, ignoreCase = true) ||
            code.contains(query, ignoreCase = true) ||
            folderName.contains(query, ignoreCase = true)

    private fun List<V2Media>.sorted(spec: V2SortSpec): List<V2Media> {
        var list = when (spec.typeFilter) {
            V2TypeFilter.ALL -> this
            V2TypeFilter.VIDEO -> filter { it.type == V2MediaType.VIDEO }
            V2TypeFilter.IMAGE -> filter { it.type == V2MediaType.IMAGE }
        }
        val order = spec.order
        list = when (spec.field) {
            V2SortField.RECENT -> if (order == V2SortOrder.DESC)
                list.sortedByDescending { it.dateMillis } else list.sortedBy { it.dateMillis }
            V2SortField.NAME -> if (order == V2SortOrder.ASC)
                list.sortedWith(compareBy({ it.name }, { it.code })) else
                list.sortedWith(compareByDescending<V2Media> { it.name }.thenByDescending { it.code })
            V2SortField.DURATION -> if (order == V2SortOrder.DESC)
                list.sortedByDescending { it.durationMs } else list.sortedBy { it.durationMs }
            V2SortField.SIZE -> if (order == V2SortOrder.DESC)
                list.sortedByDescending { it.sizeBytes } else list.sortedBy { it.sizeBytes }
        }
        return list
    }
}

/**
 * 由文件夹 + 媒体（已应用状态覆盖）+ 用户自选封面构建相册列表（纯函数，可 JVM 单测）。
 * 规则：
 * - 只看 IMAGE；某文件夹无照片则不出现在书架；
 * - 默认封面 = 该文件夹最新照片（dateMillis 最大）；
 * - 用户手动设置优先，但只能指向本文件夹内的照片（否则回退默认）。
 */
fun buildAlbums(
    folders: List<V2Folder>,
    media: List<V2Media>,
    userCovers: Map<String, String>,
): List<V2Album> = folders.mapNotNull { folder ->
    val images = media.filter { it.folderId == folder.id && it.type == V2MediaType.IMAGE }
    if (images.isEmpty()) {
        null
    } else {
        val defaultCover = images.maxByOrNull { it.dateMillis }?.id
        val cover = userCovers[folder.id]
            ?.takeIf { id -> images.any { it.id == id } }
            ?: defaultCover
        V2Album(
            id = folder.id,
            folderId = folder.id,
            name = folder.name,
            imageCount = images.size,
            coverImageId = cover,
        )
    }
}
