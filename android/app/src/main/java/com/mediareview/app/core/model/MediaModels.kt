package com.mediareview.app.core.model

import kotlinx.serialization.Serializable

/** 媒体库勾选项(GET/PUT /libraries)。 */
@Serializable
data class LibraryItem(
    val jellyfin_id: String = "",
    val name: String = "",
    val collection_type: String? = null,
    val selected: Boolean = false,
    val sort_order: Int = 0,
)

/** 媒体墙分页请求体集合。 */
@Serializable
data class MediaPage(
    val items: List<MediaSummary> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val page_size: Int = 50,
    val sync: MediaSyncDto = MediaSyncDto(),
)

@Serializable
data class MediaSyncDto(
    val state: String = "unknown",
)

/** 文件夹辅助视图条目(GET /media/folders)。folder_id 是服务器派生的不透明 ID,不是文件路径。 */
@Serializable
data class MediaFolderItem(
    val folder_id: String = "",
    val name: String = "",
    val count: Int = 0,
    /** 代表封面(最新一张图片)的 media_id;该文件夹无图片时为 null。 */
    val cover_media_id: String? = null,
    /** 代表封面的缩略图代理 URL(相对地址,客户端用 MediaUrlResolver 补全);无封面时为 null。 */
    val cover_url: String? = null,
    /** 该文件夹内图片数量(书架相册"N 张照片";无图片为 0)。 */
    val image_count: Int = 0,
)

@Serializable
data class MediaSummary(
    val media_id: String = "",
    val name: String = "",
    val media_type: String = "video",
    val library_id: String = "",
    val duration_ms: Long? = null,
    val size_bytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val container: String? = null,
    val created_at: String? = null,
    val modified_at: String? = null,
    val cover_url: String? = null,
    val original_url: String? = null,
    /** 所属文件夹的不透明 ID(与 /media/folders 同源);无路径信息时为 null。 */
    val folder_id: String? = null,
    /** 所属文件夹显示名(仅显示名,绝不是文件系统路径);无路径信息时为 null。 */
    val folder_name: String? = null,
) {
    val isVideo: Boolean get() = media_type == "video"
}
