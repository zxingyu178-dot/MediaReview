package com.mediareview.app.feature.v2.data

import com.mediareview.app.feature.v2.data.server.V2ServerMediaRepository
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaPage
import com.mediareview.app.feature.v2.model.V2MediaQuery
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2SpriteManifest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * V2 数据源路由（Stage 8A §5）：UI 永远只依赖 [MediaRepository]，
 * 由本路由器按 [V2DataModeStore] 的运行时模式把调用委托给 Demo 或 Server 实现。
 *
 * - DEMO   → [DemoMediaRepository]（APK 内置离线数据）
 * - SERVER → [V2ServerMediaRepository]（真实 MediaReview Server）
 *
 * 关键约束：
 * - 不把 Demo 实现硬替换成 Server 实现（Hilt 绑定始终是路由器）；
 * - 模式切换只改变委托对象，UI / ViewModel / Player / Viewer 全部不需要 if/else。
 */
@Singleton
class V2MediaRepositoryRouter @Inject constructor(
    private val demo: DemoMediaRepository,
    private val server: V2ServerMediaRepository,
    private val modeStore: V2DataModeStore,
) : MediaRepository {

    override val mode: V2DataMode get() = modeStore.mode.value

    private fun active(): MediaRepository =
        if (modeStore.mode.value == V2DataMode.SERVER) server else demo

    override suspend fun folders(): List<V2Folder> = active().folders()

    override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage = active().mediaPage(query)

    override suspend fun media(): List<V2Media> = active().media()

    override suspend fun media(spec: V2SortSpec): List<V2Media> = active().media(spec)

    override suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media> =
        active().mediaInFolder(folderId, spec)

    override suspend fun search(query: String, spec: V2SortSpec): List<V2Media> =
        active().search(query, spec)

    override fun mediaById(id: String): V2Media? = active().mediaById(id)

    override suspend fun setFavorite(mediaId: String, favorite: Boolean): Boolean =
        active().setFavorite(mediaId, favorite)

    override suspend fun favorites(): List<V2Media> = active().favorites()

    override suspend fun markReviewed(mediaId: String) = active().markReviewed(mediaId)

    override suspend fun pendingDeleteIds(): Set<String> = active().pendingDeleteIds()

    override suspend fun setPendingDelete(mediaId: String, pending: Boolean) =
        active().setPendingDelete(mediaId, pending)

    override suspend fun unmarkReviewed(mediaId: String) = active().unmarkReviewed(mediaId)

    override suspend fun resolvePlayback(mediaId: String): V2PlaybackSource =
        active().resolvePlayback(mediaId)

    override fun playbackUri(mediaId: String): String = active().playbackUri(mediaId)

    override fun playbackHeaders(mediaId: String): Map<String, String> = active().playbackHeaders(mediaId)

    override fun thumbUri(media: V2Media): String = active().thumbUri(media)

    override fun coverUri(media: V2Media): String = active().coverUri(media)

    override fun imageUri(media: V2Media): String = active().imageUri(media)

    override fun spriteUri(media: V2Media): String? = active().spriteUri(media)

    override fun spriteManifest(media: V2Media): V2SpriteManifest? = active().spriteManifest(media)

    override suspend fun albums(): List<V2Album> = active().albums()

    override suspend fun imagesInAlbum(albumId: String, spec: V2SortSpec): List<V2Media> =
        active().imagesInAlbum(albumId, spec)

    override suspend fun setAlbumCover(albumId: String, mediaId: String) =
        active().setAlbumCover(albumId, mediaId)
}