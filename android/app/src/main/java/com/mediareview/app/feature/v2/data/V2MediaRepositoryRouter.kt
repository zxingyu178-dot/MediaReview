package com.mediareview.app.feature.v2.data

import com.mediareview.app.feature.v2.data.server.ServerIncompatibleException
import com.mediareview.app.feature.v2.data.server.V2ServerMediaRepository
import com.mediareview.app.feature.v2.data.server.V2ServerStatus
import com.mediareview.app.feature.v2.data.server.V2ServerStatusStore
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
    private val statusStore: V2ServerStatusStore,
) : MediaRepository {

    override val mode: V2DataMode get() = modeStore.mode.value

    private fun active(): MediaRepository =
        if (modeStore.mode.value == V2DataMode.SERVER) server else demo

    /**
     * §17 fail-fast：已知 Server 版本过旧时,读取类接口立即失败,**不再**用一串请求
     * 去"发现"电脑端过旧(避免首页出现 404/404/404)。UI 组合期使用的纯取值接口
     * (`mediaById` / `*Uri`) 仍走 [active],避免在 Composition 中抛异常。
     */
    private fun dataActive(): MediaRepository {
        if (modeStore.mode.value != V2DataMode.SERVER) return demo
        if (statusStore.status.value == V2ServerStatus.Incompatible) {
            throw ServerIncompatibleException(statusStore.serverVersion.value)
        }
        return server
    }

    /** 切换数据源/服务器时必须两侧都失效，避免命中另一个服务器的缓存。 */
    override fun invalidateAuxiliaryCache() {
        demo.invalidateAuxiliaryCache()
        server.invalidateAuxiliaryCache()
    }

    override fun dropCachedMedia(mediaIds: List<String>) {
        // 两侧都丢弃：删除后缓存条目在任何模式下都不应继续命中
        demo.dropCachedMedia(mediaIds)
        server.dropCachedMedia(mediaIds)
    }

    override suspend fun folders(): List<V2Folder> = dataActive().folders()

    override suspend fun mediaPage(query: V2MediaQuery): V2MediaPage = dataActive().mediaPage(query)

    override suspend fun media(): List<V2Media> = dataActive().media()

    override suspend fun media(spec: V2SortSpec): List<V2Media> = dataActive().media(spec)

    override suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media> =
        dataActive().mediaInFolder(folderId, spec)

    override suspend fun search(query: String, spec: V2SortSpec): List<V2Media> =
        dataActive().search(query, spec)

    override fun mediaById(id: String): V2Media? = active().mediaById(id)

    // Stage 8D.2 §17：凡会发网络请求的 suspend 方法，SERVER 模式都必须经过兼容 Gate。
    override suspend fun setFavorite(mediaId: String, favorite: Boolean): Boolean =
        dataActive().setFavorite(mediaId, favorite)

    override suspend fun favorites(): List<V2Media> = dataActive().favorites()

    override suspend fun markReviewed(mediaId: String) = dataActive().markReviewed(mediaId)

    override suspend fun pendingDeleteIds(): Set<String> = dataActive().pendingDeleteIds()

    override suspend fun setPendingDelete(mediaId: String, pending: Boolean): Boolean =
        dataActive().setPendingDelete(mediaId, pending)

    override suspend fun unmarkReviewed(mediaId: String) = dataActive().unmarkReviewed(mediaId)

    override suspend fun resolvePlayback(mediaId: String): V2PlaybackSource =
        dataActive().resolvePlayback(mediaId)

    override fun playbackUri(mediaId: String): String = active().playbackUri(mediaId)

    override fun playbackHeaders(mediaId: String): Map<String, String> = active().playbackHeaders(mediaId)

    override fun thumbUri(media: V2Media): String = active().thumbUri(media)

    override fun coverUri(media: V2Media): String = active().coverUri(media)

    override fun imageUri(media: V2Media): String = active().imageUri(media)

    override fun spriteUri(media: V2Media): String? = active().spriteUri(media)

    override fun spriteManifest(media: V2Media): V2SpriteManifest? = active().spriteManifest(media)

    override suspend fun albums(): List<V2Album> = dataActive().albums()

    override suspend fun albumPage(
        albumId: String,
        page: Int,
        pageSize: Int,
        spec: V2SortSpec,
    ): V2MediaPage = dataActive().albumPage(albumId, page, pageSize, spec)

    override suspend fun setAlbumCover(albumId: String, mediaId: String) =
        active().setAlbumCover(albumId, mediaId)
}