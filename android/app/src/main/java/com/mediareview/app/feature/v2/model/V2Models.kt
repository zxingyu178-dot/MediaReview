package com.mediareview.app.feature.v2.model

import kotlinx.serialization.Serializable

/** 媒体类型。 */
enum class V2MediaType { VIDEO, IMAGE }

/** 排序字段。 */
enum class V2SortField { RECENT, NAME, DURATION, SIZE }

/** 排序顺序。 */
enum class V2SortOrder { ASC, DESC }

/** 媒体类型过滤。 */
enum class V2TypeFilter { ALL, VIDEO, IMAGE }

/** V2 媒体条目（Demo 数据模型，后续可映射为 Server 返回的真实模型）。 */
data class V2Media(
    val id: String,
    val code: String,          // 编号，如 "001"
    val name: String,          // 展示名称
    val folderId: String,
    val folderName: String,
    val type: V2MediaType,
    val durationMs: Long,      // 视频时长；图片为 0
    val sizeBytes: Long,
    val dateMillis: Long,      // 添加日期
    val isFavorite: Boolean,
    val isReviewed: Boolean,   // 已批阅
    val assetPath: String,     // 相对 assets 的路径，如 "demo_media/videos/01_landscape.mp4"
    val thumbPath: String,     // 封面相对 assets 路径（视频取 sprite 或视频帧，图片取自身）
    val spritePath: String?,   // 雪碧图 webp 相对 assets 路径
    val spriteManifestPath: String?, // 雪碧图 manifest json 相对 assets 路径
    val naturalWidth: Int,
    val naturalHeight: Int,
) {
    val isVideo: Boolean get() = type == V2MediaType.VIDEO
    val durationSeconds: Int get() = (durationMs / 1000L).toInt()
}

/** V2 文件夹（媒体文件夹，非书架封面来源）。 */
data class V2Folder(
    val id: String,
    val name: String,
    val description: String,
    /** 封面媒体 id 列表：1 个为单封面，4 个为四宫格封面。（书架已弃用，保留供媒体筛选） */
    val coverMediaIds: List<String>,
    /**
     * 该文件夹内媒体数量：
     * - Demo：内存统计；Server：来自 `GET /media/folders` 的 count（服务端聚合，避免 App 端 N+1）。
     */
    val count: Int = 0,
    /** 该文件夹内图片数量（书架相册用；Server 来自 `image_count`，无图片文件夹为 0）。 */
    val imageCount: Int = 0,
)

/** 照片相册：书架只按此模型展示，仅统计 / 封面 / 内容均只允许 IMAGE。 */
data class V2Album(
    val id: String,          // 相册 id（第一版 = folderId）
    val folderId: String,
    val name: String,        // 第一版直接使用 folderName
    val imageCount: Int,
    val coverImageId: String?, // 只能指向 IMAGE（默认最新照片；用户手动设置后持久化）
)

/** 雪碧图 manifest（对应 assets 内 0X_sprite.json）。 */
@Serializable
data class V2SpriteManifest(
    val source: String,
    val columns: Int,
    val rows: Int,
    val cell_width: Int,
    val cell_height: Int,
    val frame_interval_s: Double,
    val duration_s: Double,
    val frame_count: Int,
)

/** 页面上下文队列：进入播放器/查看器时携带所在文件夹、排序与索引。 */
data class V2ContextQueue(
    val folderId: String?,
    val sortField: V2SortField,
    val sortOrder: V2SortOrder,
    val typeFilter: V2TypeFilter,
    val mediaIds: List<String>,
    val currentIndex: Int,
)

/** 统一排序参数。 */
data class V2SortSpec(
    val field: V2SortField = V2SortField.RECENT,
    val order: V2SortOrder = V2SortOrder.DESC,
    val typeFilter: V2TypeFilter = V2TypeFilter.ALL,
)

/**
 * 统一分页查询（Stage 8A）：
 *
 * - Server：search / sort / filter / page 全部作为 query 参数交给 `GET /media`，
 *   由 Server 在 SQLite 内执行（禁止把全量媒体下载到手机再本地排序）；
 * - Demo：内存排序/过滤后按 [page] / [pageSize] 切片（行为与旧实现一致）。
 */
data class V2MediaQuery(
    val page: Int = 1,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val folderId: String? = null,
    val search: String? = null,
    val spec: V2SortSpec = V2SortSpec(),
) {
    companion object {
        /** 默认每页条数：真实库可能上万条，必须分页滚动加载。 */
        const val DEFAULT_PAGE_SIZE = 50
    }
}

/** 统一分页结果：page / pageSize / total 是 Server 与 Demo 共同的分页合同。 */
data class V2MediaPage(
    val items: List<V2Media>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
) {
    /** 是否还有下一页（以 total 为准，不依赖"页是否满"）。 */
    val hasMore: Boolean get() = page * pageSize < total
}
