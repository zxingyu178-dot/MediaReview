package com.mediareview.app.feature.v2.data

import android.content.Context
import com.mediareview.app.feature.v2.AppMode
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaType
import com.mediareview.app.feature.v2.model.V2SortField
import com.mediareview.app.feature.v2.model.V2SortOrder
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import com.mediareview.app.feature.v2.model.V2TypeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Demo 数据仓库：全部数据来自 APK 内置 assets，离线可用。
 * 与 [MediaRepository] 接口对应，未来可被真实 Server 仓库替换。
 */
@Singleton
class DemoMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val albumCoverStore: AlbumCoverStore,
) : MediaRepository {

    private val allFolders: List<V2Folder> = DemoMediaCatalog.buildFolders()
    private val allMedia: List<V2Media> = DemoMediaCatalog.buildMedia()
    private val mediaById: Map<String, V2Media> = allMedia.associateBy { it.id }

    private val favoriteState = mutableMapOf<String, Boolean>()
    private val reviewedState = mutableMapOf<String, Boolean>()
    private val pendingDeleteState = mutableSetOf<String>()

    // Stage6：雪碧图 manifest 解析缓存。
    // 禁止在 Compose 组合路径重复做 assets IO / JSON 解析——每个 manifest 只解析一次，
    // 之后全部命中内存缓存（key = manifest asset 路径）。
    private val spriteManifestCache = mutableMapOf<String, V2SpriteManifest?>()

    override val mode: AppMode = AppMode.DEMO

    override suspend fun folders(): List<V2Folder> = allFolders

    override suspend fun media(): List<V2Media> = allMedia.applyOverridesState()

    override suspend fun media(spec: V2SortSpec): List<V2Media> =
        allMedia.applyOverridesState().sorted(spec)

    override suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media> =
        allMedia.applyOverridesState().filter { it.folderId == folderId }.sorted(spec)

    override suspend fun search(query: String, spec: V2SortSpec): List<V2Media> {
        val q = query.trim()
        if (q.isEmpty()) return media(spec)
        return allMedia.applyOverridesState()
            .filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.code.contains(q, ignoreCase = true) ||
                    it.folderName.contains(q, ignoreCase = true)
            }
            .sorted(spec)
    }

    override fun mediaById(id: String): V2Media? =
        mediaById[id]?.let { applyOverrides(listOf(it)).firstOrNull() }

    override suspend fun setFavorite(mediaId: String, favorite: Boolean) {
        favoriteState[mediaId] = favorite
    }

    override suspend fun markReviewed(mediaId: String) {
        reviewedState[mediaId] = true
    }

    override suspend fun pendingDeleteIds(): Set<String> = pendingDeleteState.toSet()

    override suspend fun setPendingDelete(mediaId: String, pending: Boolean) {
        if (pending) pendingDeleteState += mediaId else pendingDeleteState -= mediaId
    }

    override suspend fun unmarkReviewed(mediaId: String) {
        reviewedState[mediaId] = false
    }

    override fun playbackUri(mediaId: String): String {
        val m = mediaById[mediaId] ?: return ""
        return DemoAssets.playbackUri(m)
    }

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

    override suspend fun imagesInAlbum(albumId: String, spec: V2SortSpec): List<V2Media> =
        allMedia.applyOverridesState()
            .filter { it.folderId == albumId && it.type == V2MediaType.IMAGE }
            .sorted(spec)

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
