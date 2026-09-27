package com.mediareview.app.feature.v2.data.server

import com.mediareview.app.core.model.MediaFolderItem
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.core.network.MediaUrlResolver
import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaType
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server DTO → V2 UI 模型映射的唯一入口（Stage 8A）。
 *
 * 约束：
 * - Compose 页面永远不接触 [MediaSummary] / [MediaFolderItem]，只认识 V2Media / V2Folder；
 * - Server 资源（cover_url / original_url）解析为绝对地址后只进入 [V2ServerResourceCache]，
 *   绝不写进 V2Media 的 Demo 语义字段（assetPath / thumbPath / spritePath）；
 * - folder_id / folder_name 直接来自 Server（不透明 ID + 显示名），App 不知道真实路径。
 */
@Singleton
class V2MediaMapper @Inject constructor(
    private val urlResolver: MediaUrlResolver,
) {

    /**
     * 单条媒体映射。
     *
     * @param serverOnlyHosts 服务端可控的"仅服务器可达"主机（与 1.1 媒体墙一致，用于本机回环兜底）
     *
     * 收藏状态自 Stage 8A.1 起直接取 [MediaSummary.is_favorite]（服务端随列表批量下发），
     * 不再依赖客户端预取整个收藏列表。
     */
    fun mapMedia(
        summary: MediaSummary,
        baseUrl: String,
        serverOnlyHosts: Set<String> = CONTROLLED_SERVER_ONLY_HOSTS,
    ): V2ServerResourceCache.Entry {
        val coverUrl = summary.cover_url?.takeIf { it.isNotBlank() }
            ?.let { resolveOrNull(it, baseUrl, serverOnlyHosts) }
        val originalUrl = summary.original_url?.takeIf { it.isNotBlank() }
            ?.let { resolveOrNull(it, baseUrl, serverOnlyHosts) }
        val media = V2Media(
            id = summary.media_id,
            // Server 无"编号"概念：留空，UI 侧对空值做拼接裁剪（不伪造编号）
            code = "",
            name = summary.name,
            folderId = summary.folder_id.orEmpty(),
            folderName = summary.folder_name.orEmpty(),
            type = if (summary.media_type == "image") V2MediaType.IMAGE else V2MediaType.VIDEO,
            durationMs = summary.duration_ms ?: 0L,
            sizeBytes = summary.size_bytes ?: 0L,
            dateMillis = parseDateMillis(summary.created_at),
            isFavorite = summary.is_favorite,
            // 批阅状态属于 Review Session（Stage 8B），本阶段 Server 模式不伪造
            isReviewed = false,
            // Demo 语义字段在 Server 模式下保持空：资源 URL 只存在于 Resource Cache
            assetPath = "",
            thumbPath = "",
            spritePath = null,
            spriteManifestPath = null,
            naturalWidth = summary.width ?: 0,
            naturalHeight = summary.height ?: 0,
        )
        return V2ServerResourceCache.Entry(media = media, coverUrl = coverUrl, originalUrl = originalUrl)
    }

    /** 文件夹映射：folder_id 视为不透明 ID；count / image_count / 代表封面全部来自 Server 聚合。 */
    fun mapFolder(item: MediaFolderItem): V2Folder = V2Folder(
        id = item.folder_id,
        name = item.name,
        description = "",
        coverMediaIds = listOfNotNull(item.cover_media_id?.takeIf { it.isNotBlank() }),
        count = item.count,
        imageCount = item.image_count,
    )

    private fun resolveOrNull(raw: String, baseUrl: String, serverOnlyHosts: Set<String>): String? =
        runCatching {
            urlResolver.resolve(raw, baseUrl, serverOnlyHosts)
        }.getOrNull()

    /** ISO-8601 时间 → epoch millis；缺失/无法解析时为 0（排序由 Server 执行，这里只服务展示）。 */
    private fun parseDateMillis(raw: String?): Long {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return 0L
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                java.time.Instant.parse(value).toEpochMilli()
            } catch (_: DateTimeParseException) {
                0L
            }
        }
    }

    companion object {
        /** 与 1.1 媒体墙保持一致：仅服务器可达的主机名，回归到已配对主机。 */
        val CONTROLLED_SERVER_ONLY_HOSTS: Set<String> = setOf("localhost")
    }
}