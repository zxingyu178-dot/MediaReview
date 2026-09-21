package com.mediareview.app.feature.v2.data

import android.content.Context
import com.mediareview.app.feature.v2.AppMode
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
) : MediaRepository {

    private val allFolders: List<V2Folder> = DemoMediaCatalog.buildFolders()
    private val allMedia: List<V2Media> = DemoMediaCatalog.buildMedia()
    private val mediaById: Map<String, V2Media> = allMedia.associateBy { it.id }

    private val favoriteState = mutableMapOf<String, Boolean>()
    private val reviewedState = mutableMapOf<String, Boolean>()

    override val mode: AppMode = AppMode.DEMO

    override suspend fun folders(): List<V2Folder> = allFolders

    override suspend fun media(): List<V2Media> = allMedia

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

    override fun playbackUri(mediaId: String): String {
        val m = mediaById[mediaId] ?: return ""
        return DemoAssets.playbackUri(m)
    }

    override fun thumbUri(media: V2Media): String = DemoAssets.thumbUri(media)

    override fun imageUri(media: V2Media): String = DemoAssets.imageUri(media)

    override fun spriteUri(media: V2Media): String? = DemoAssets.spriteUri(media)

    override fun spriteManifest(media: V2Media): V2SpriteManifest? =
        media.spriteManifestPath?.let { DemoAssets.readSpriteManifest(context, it) }

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
