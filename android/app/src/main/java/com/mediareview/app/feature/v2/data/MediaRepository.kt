package com.mediareview.app.feature.v2.data

import com.mediareview.app.feature.v2.model.V2Album
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaPage
import com.mediareview.app.feature.v2.model.V2MediaQuery
import com.mediareview.app.feature.v2.model.V2PlaybackSource
import com.mediareview.app.feature.v2.model.V2SortSpec
import com.mediareview.app.feature.v2.model.V2SpriteManifest

/**
 * V2 统一媒体数据接口。
 *
 * UI 层只依赖本接口取数：Demo（APK 内置离线）与真实 Server 的区别只是实现不同，
 * HomeScreen / MediaCard / Shelf / Player / Viewer / Review 均无需感知。
 * 运行时选择由 [V2MediaRepositoryRouter] 按 [V2DataMode] 决定。
 */
interface MediaRepository {
    /** 当前生效的数据源模式。 */
    val mode: V2DataMode

    /**
     * 失效辅助数据缓存（文件夹/书架）。
     *
     * 阶段 8A.1.1 §4：切换数据源、切换服务器地址、断开连接、重新配对时必须调用，
     * 否则可能命中另一个服务器的缓存。Demo 实现无远程缓存，默认空实现。
     */
    fun invalidateAuxiliaryCache() {}

    suspend fun folders(): List<V2Folder>

    /**
     * 统一分页查询（Stage 8A 正式列表路径）：
     * Server 模式下 search / sort / media_type / folder_id / page 全部由服务端执行，
     * App 侧不做全量下载与本地排序。
     */
    suspend fun mediaPage(query: V2MediaQuery): V2MediaPage

    /**
     * 全量媒体（Demo / 兼容路径）。
     * Server 模式返回**有界**的首屏窗口（绝不全量读取），正式列表请使用 [mediaPage]。
     */
    suspend fun media(): List<V2Media>

    /** 应用排序/过滤并返回结果。Server 模式同样返回有界首屏窗口，正式列表请使用 [mediaPage]。 */
    suspend fun media(spec: V2SortSpec): List<V2Media>

    /** 指定文件夹内媒体，已应用排序/过滤（Server 模式为有界首屏窗口）。 */
    suspend fun mediaInFolder(folderId: String, spec: V2SortSpec): List<V2Media>

    /** 搜索名称/编号/文件夹（Server 模式由服务端执行搜索，返回有界首屏窗口）。 */
    suspend fun search(query: String, spec: V2SortSpec): List<V2Media>

    fun mediaById(id: String): V2Media?

    /**
     * 收藏 / 取消收藏。
     * 返回 true 表示**数据源已确认**（Server 模式必须服务器成功），
     * false 表示失败：UI 必须保持/恢复原状态并提示，禁止假成功。
     */
    suspend fun setFavorite(mediaId: String, favorite: Boolean): Boolean

    /** 收藏列表（Server：`GET /favorites`；Demo：内存收藏状态过滤）。 */
    suspend fun favorites(): List<V2Media>

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

    /**
     * 异步解析播放源（Stage 8A 正式播放路径的唯一入口）。
     *
     * Demo：本地 `android.resource://` + 空 headers；
     * Server：`GET /media/{id}/playback` 的 Direct + 唯一一次 HLS 回退 + 设备级 headers。
     * 必须在 ViewModel 内调用并收敛成 UiState，Player 组合函数不得直接触发网络请求。
     */
    suspend fun resolvePlayback(mediaId: String): V2PlaybackSource

    /**
     * 播放进度上报（Stage 8A §27）。
     *
     * Demo 没有进度接口（本默认实现即空操作）；Server 实现
     * `POST /api/v1/media/{id}/progress`。节流由调用方负责：
     * 开始播放 / 暂停 / 退出播放器 / 每 10~15 秒，禁止每几百毫秒高频上报。
     */
    suspend fun reportProgress(mediaId: String, positionMs: Long, isPaused: Boolean) = Unit

    /**
     * 同步播放地址：**仅 Review/Demo 兼容路径保留**。
     * 正式播放路径已改为 [resolvePlayback]；Server 模式调用本方法会直接抛错（fail-fast）。
     */
    fun playbackUri(mediaId: String): String

    /** 同步播放请求头：仅 Review/Demo 兼容路径保留（Server 模式抛错，见 [playbackUri]）。 */
    fun playbackHeaders(mediaId: String): Map<String, String> = emptyMap()

    /** 封面 URI（Demo 为本地）。 */
    fun thumbUri(media: V2Media): String

    /**
     * 统一封面 URI：UI 一律调用本方法（图片→缩略图，视频→Poster）；
     * UI 不感知 assets / res-raw / Server URL 的差异。
     */
    fun coverUri(media: V2Media): String

    /** 图片原图 URI（Demo 为 asset/res-raw；Server 为原图代理 URL）。 */
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