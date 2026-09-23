package com.mediareview.app.feature.v2.data

import com.mediareview.app.feature.v2.AppMode
import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2SpriteManifest

/**
 * V2 统一媒体数据接口。
 *
 * UI 层只依赖本接口取数；未来接入真实 Server 时，用 MediaReviewServerRepository
 * 替换 DemoMediaRepository 即可，HomeScreen / MediaCard / Shelf / Player / Viewer
 * 无需改动。
 */
interface MediaRepository {
    val mode: AppMode

    suspend fun folders(): List<V2Folder>

    suspend fun media(): List<V2Media>

    /** 应用排序/过滤并返回结果（纯内存排序）。 */
    suspend fun media(spec: V2SortSpec): List<V2Media>

    /** 指定文件夹内媒体，已应用排序/过滤。 */
    suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media>

    /** 搜索名称/编号/文件夹。 */
    suspend fun search(query: String, spec: V2SortSpec): List<V2Media>

    fun mediaById(id: String): V2Media?

    suspend fun setFavorite(mediaId: String, favorite: Boolean)

    suspend fun markReviewed(mediaId: String)

    /**
     * 待删除集合（Review 用）：与 isReviewed 相互独立——
     * 一条视频可以"已批阅"但与"待删除"无关；撤销删除时删除集合里移除即可。
     */
    suspend fun pendingDeleteIds(): Set<String>

    /** 加入 / 移除待删除集合（Review 里的 🗑 / 撤销）。 */
    suspend fun setPendingDelete(mediaId: String, pending: Boolean)

    /** 重新批阅：清除已批阅标记（Review complete 页的"重新批阅"入口）。 */
    suspend fun unmarkReviewed(mediaId: String)

    /** 返回可播放 URI（Demo 为本地；Production 为服务器直连 URL）。 */
    fun playbackUri(mediaId: String): String

    /**
     * 播放请求头（Review / Player 播放源抽象的一部分）：
     * Demo 为 emptyMap()；Production 接 Jellyfin Direct Play / HLS 时
     * 传 X-Emby-Token 等头，Review / Player UI 不感知差异。
     */
    fun playbackHeaders(mediaId: String): Map<String, String> = emptyMap()

    /** 封面 URI（Demo 为本地）。 */
    fun thumbUri(media: V2Media): String

    /**
     * 统一封面 URI：UI 一律调用本方法（图片→缩略图，视频→Poster）；
     * UI 不感知 assets / res-raw / Server URL 的差异。
     */
    fun coverUri(media: V2Media): String

    /** 图片原图 URI（Demo 为 asset:///demo_media/images/...；Production 为 Server/Jellyfin 原图 URL）。 */
    fun imageUri(media: V2Media): String

    fun spriteUri(media: V2Media): String?

    fun spriteManifest(media: V2Media): V2SpriteManifest?

    // ---------- 相册（书架）能力 ----------

    /** 照片相册列表：只能由 IMAGE 构成；无照片的文件夹不出现在书架。 */
    suspend fun albums(): List<V2Album>

    /** 相册内照片（IMAGE ONLY，已应用排序；不受首页过滤状态污染）。 */
    suspend fun imagesInAlbum(albumId: String, spec: V2SortSpec): List<V2Media>

    /**
     * 用户设置相册封面。
     * 校验：media.type == IMAGE 且 media.folderId == album.folderId；
     * 否则忽略（封面只能指向本相册内的照片）。
     */
    suspend fun setAlbumCover(albumId: String, mediaId: String)
}
