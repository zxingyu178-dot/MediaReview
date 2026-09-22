package com.mediareview.app.feature.v2.data

import com.mediareview.app.feature.v2.AppMode
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

    /** 返回可播放 URI（Demo 为 asset:///...；Production 为服务器直连 URL）。 */
    fun playbackUri(mediaId: String): String

    /** 封面 URI（Demo 为 asset:///...）。 */
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
}
