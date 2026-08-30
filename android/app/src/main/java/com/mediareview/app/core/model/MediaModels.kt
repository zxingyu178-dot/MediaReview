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
) {
    val isVideo: Boolean get() = media_type == "video"
}
