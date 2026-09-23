package com.mediareview.app.feature.v2.review

/**
 * 批阅模式下 UI 唯一能拿到的媒体形态。
 *
 * Stage 7 架构收口：ReviewScreen 只依赖本模型，绝不感知 android.resource /
 * demo_media / raw resource / Jellyfin Direct URL 的差异。播放与封面 URL 由
 * Repository 在构建时解析；下一阶段接真实 Server / Jellyfin 时，只需要改变
 * Repository 的解析实现（[com.mediareview.app.feature.v2.data.MediaRepository]），
 * ReviewScreen 无需修改。
 */
data class ReviewMediaSource(
    val mediaId: String,
    val title: String,
    val code: String,
    val folderName: String,
    val durationMs: Long,
    val naturalWidth: Int,
    val naturalHeight: Int,
    /** 可播放 URL（Demo 为本地；Production 为 Jellyfin Direct / HLS）。 */
    val playbackUrl: String,
    /** 播放请求头（Demo 为空；Production 传 X-Emby-Token 等）。 */
    val headers: Map<String, String>,
    /** 封面 Poster URL（与播放画面同一种显示策略）。 */
    val coverUrl: String,
) {
    val durationSeconds: Int get() = (durationMs / 1000L).toInt()
}