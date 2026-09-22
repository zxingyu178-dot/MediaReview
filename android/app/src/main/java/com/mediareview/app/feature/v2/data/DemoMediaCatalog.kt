package com.mediareview.app.feature.v2.data

import com.mediareview.app.feature.v2.model.V2Folder
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.model.V2MediaType
import java.util.concurrent.TimeUnit

/**
 * Demo 媒体目录：用 6 个内置视频 + 14 张内置图片循环构造约 60 条 Mock 媒体，
 * 模拟真实大型媒体库（不同名称/编号/文件夹/日期/时长/大小/收藏/已批阅状态）。
 *
 * 所有资源均来自 assets/demo_media（FFmpeg 公开测试源生成，见 docs/DEMO_MEDIA_SOURCES.md）。
 */
object DemoMediaCatalog {

    /**
     * Stage6 性能演示模式（默认关闭，交付包保持真实数据量）。
     * 置 true 构建时，会把内置示例媒体复制扩展到 500 条用于性能验证
     * （dumpsys gfxinfo Before/After、列表流畅度），无需改任何 UI/仓库代码。
     */
    const val PERF_DEMO_MODE = false

    /** 性能模式目标媒体条数。 */
    private const val PERF_TARGET_COUNT = 500

    private const val V = "demo_media/videos"
    private const val I = "demo_media/images"
    private const val S = "demo_media/sprites"

    /** 内置视频资源元数据（名称 -> 时长秒/宽/高）。 */
    private val videoAssets = listOf(
        Triple("01_landscape.mp4", 24.0, 1280 to 720),
        Triple("02_landscape.mp4", 22.5, 1280 to 720),
        Triple("03_portrait.mp4", 18.0, 720 to 1280),
        Triple("04_short.mp4", 10.0, 1280 to 720),
        Triple("05_longer.mp4", 40.0, 1280 to 720),
        Triple("06_wide.mp4", 22.0, 1024 to 768),
    )

    /** 内置图片资源元数据（名称 -> 宽/高）。 */
    private val imageAssets = listOf(
        "img_landscape_01.jpg" to (1920 to 1080),
        "img_landscape_02.jpg" to (1920 to 1080),
        "img_landscape_03.jpg" to (1920 to 1080),
        "img_landscape_04.jpg" to (1920 to 1080),
        "img_portrait_01.jpg" to (1080 to 1920),
        "img_portrait_02.jpg" to (1080 to 1920),
        "img_square_01.jpg" to (1080 to 1080),
        "img_square_02.jpg" to (1080 to 1080),
        "img_square_03.jpg" to (1080 to 1080),
        "img_hd_01.jpg" to (2560 to 1440),
        "img_hd_02.jpg" to (2560 to 1440),
        "img_art_01.jpg" to (1280 to 720),
        "img_art_02.jpg" to (1280 to 720),
        "img_art_03.jpg" to (1280 to 720),
    )

    // 基准日期：2026-09-01
    private val baseDate = java.util.Calendar.getInstance().apply {
        set(2026, 8, 1, 9, 0, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun buildFolders(): List<V2Folder> {
        return listOf(
            V2Folder("f_ai", "AI 视频", "AI 生成与合成内容", listOf("ai-001", "ai-002", "ai-003", "ai-004")),
            V2Folder("f_backup", "手机备份", "手机相册与备份", listOf("bkp-001", "bkp-002", "bkp-003", "bkp-004")),
            V2Folder("f_downloads", "Downloads", "下载内容", listOf("dl-001", "dl-002")),
            V2Folder("f_travel", "旅行", "旅行照片与视频", listOf("trv-001", "trv-002")),
            V2Folder("f_fav", "收藏", "我的收藏", listOf("fav-001", "fav-002")),
            V2Folder("f_temp", "临时整理", "待整理内容", listOf("tmp-001", "tmp-002")),
        )
    }

    fun buildMedia(): List<V2Media> {
        val list = mutableListOf<V2Media>()
        var counter = 0
        fun nextCode(): String = "%03d".format(++counter)

        // ---- AI 视频：10 视频 + 10 图片 ----
        repeat(10) { i ->
            list += makeVideo(
                id = "ai-%03d".format(i + 1), code = nextCode(), name = "AI视频-测试 %03d".format(i + 1),
                folder = "f_ai", folderName = "AI 视频", index = i,
                asset = videoAssets[i % videoAssets.size], counter = i,
            )
        }
        repeat(10) { i ->
            list += makeImage(
                id = "ai-img-%02d".format(i + 1), code = nextCode(), name = "AI图片-效果 %02d".format(i + 1),
                folder = "f_ai", folderName = "AI 视频", index = i + 10,
                asset = imageAssets[i % imageAssets.size], counter = i,
            )
        }

        // ---- 手机备份：14 图片 ----
        repeat(14) { i ->
            list += makeImage(
                id = "bkp-%03d".format(i + 1), code = nextCode(), name = "IMG_%04d".format(20260900 + i + 1),
                folder = "f_backup", folderName = "手机备份", index = i + 20,
                asset = imageAssets[(i + 3) % imageAssets.size], counter = i,
            )
        }

        // ---- Downloads：6 视频 + 4 图片 ----
        repeat(6) { i ->
            list += makeVideo(
                id = "dl-%03d".format(i + 1), code = nextCode(), name = "Downloads-下载 %03d".format(i + 1),
                folder = "f_downloads", folderName = "Downloads", index = i + 34,
                asset = videoAssets[(i + 2) % videoAssets.size], counter = i,
            )
        }
        repeat(4) { i ->
            list += makeImage(
                id = "dl-img-%02d".format(i + 1), code = nextCode(), name = "下载图片 %02d".format(i + 1),
                folder = "f_downloads", folderName = "Downloads", index = i + 40,
                asset = imageAssets[(i + 5) % imageAssets.size], counter = i,
            )
        }

        // ---- 旅行：2 视频 + 6 图片 ----
        repeat(2) { i ->
            list += makeVideo(
                id = "trv-%03d".format(i + 1), code = nextCode(), name = "旅行-视频 %03d".format(i + 1),
                folder = "f_travel", folderName = "旅行", index = i + 44,
                asset = videoAssets[(i + 4) % videoAssets.size], counter = i,
            )
        }
        repeat(6) { i ->
            list += makeImage(
                id = "trv-img-%02d".format(i + 1), code = nextCode(), name = "旅行-照片 %02d".format(i + 1),
                folder = "f_travel", folderName = "旅行", index = i + 46,
                asset = imageAssets[(i + 7) % imageAssets.size], counter = i,
            )
        }

        // ---- 收藏：4（预置收藏态） ----
        repeat(4) { i ->
            val isVid = i % 2 == 0
            val v = if (isVid) makeVideo(
                id = "fav-%03d".format(i + 1), code = nextCode(), name = "收藏-内容 %02d".format(i + 1),
                folder = "f_fav", folderName = "收藏", index = i + 52,
                asset = videoAssets[(i + 1) % videoAssets.size], counter = i,
            ) else makeImage(
                id = "fav-%03d".format(i + 1), code = nextCode(), name = "收藏-图片 %02d".format(i + 1),
                folder = "f_fav", folderName = "收藏", index = i + 52,
                asset = imageAssets[(i + 9) % imageAssets.size], counter = i,
            )
            list += v.copy(isFavorite = true, isReviewed = i < 3)
        }

        // ---- 临时整理：4 ----
        repeat(4) { i ->
            val isVid = i % 2 == 0
            val v = if (isVid) makeVideo(
                id = "tmp-%03d".format(i + 1), code = nextCode(), name = "待整理-视频 %02d".format(i + 1),
                folder = "f_temp", folderName = "临时整理", index = i + 56,
                asset = videoAssets[(i + 5) % videoAssets.size], counter = i,
            ) else makeImage(
                id = "tmp-%03d".format(i + 1), code = nextCode(), name = "待整理-图片 %02d".format(i + 1),
                folder = "f_temp", folderName = "临时整理", index = i + 56,
                asset = imageAssets[(i + 11) % imageAssets.size], counter = i,
            )
            list += v.copy(isReviewed = i < 2)
        }

        val base = list.toList()
        return if (!PERF_DEMO_MODE) base
        // 性能演示：把内置媒体循环复制到目标条数（id/名称/编号唯一），
        // 用于 500 条级别列表滚动性能验证；资源仍指向同一批内置文件。
        else buildPerfVariant(base)
    }

    /**
     * 将内置媒体循环复制扩展到 [PERF_TARGET_COUNT] 条。
     * 每条 id/名称/编号唯一，folderId 按原数据保持（画架/文件夹计数仍正确），
     * 排序字段（dateMillis/size/duration）做微调整避免全部相同。
     */
    private fun buildPerfVariant(base: List<V2Media>): List<V2Media> {
        val out = ArrayList<V2Media>(PERF_TARGET_COUNT)
        var i = 0
        while (out.size < PERF_TARGET_COUNT) {
            val src = base[i % base.size]
            out += src.copy(
                id = "perf-${"%05d".format(i)}",
                code = "%05d".format(i + 1),
                name = src.name,
                // 每批次微调时间/体积，模拟真实库的分布，避免排序时相邻全等
                dateMillis = src.dateMillis - i * 60_000L,
                sizeBytes = src.sizeBytes + (i % 7) * 31_000L,
            )
            i++
        }
        return out
    }

    // ---------- helpers ----------

    private fun makeVideo(
        id: String, code: String, name: String,
        folder: String, folderName: String, index: Int,
        asset: Triple<String, Double, Pair<Int, Int>>, counter: Int,
    ): V2Media {
        val (file, durSec, dim) = asset
        val durationMs = (durSec * 1000L).toLong()
        val width = dim.first
        val height = dim.second
        return V2Media(
            id = id, code = code, name = name,
            folderId = folder, folderName = folderName,
            type = V2MediaType.VIDEO,
            durationMs = durationMs,
            sizeBytes = (durationMs / 1000L) * (3_200_000L + (counter % 4) * 900_000L),
            dateMillis = baseDate - TimeUnit.MINUTES.toMillis((index + 1).toLong()) * 37L,
            isFavorite = (index % 7 == 0) || (index % 11 == 0),
            isReviewed = index % 5 != 0,
            assetPath = "$V/$file",
            thumbPath = "$S/${file.removeSuffix(".mp4")}_sprite.webp",
            spritePath = "$S/${file.removeSuffix(".mp4")}_sprite.webp",
            spriteManifestPath = "$S/${file.removeSuffix(".mp4")}_sprite.json",
            naturalWidth = width, naturalHeight = height,
        )
    }

    private fun makeImage(
        id: String, code: String, name: String,
        folder: String, folderName: String, index: Int,
        asset: Pair<String, Pair<Int, Int>>, counter: Int,
    ): V2Media {
        val (file, dim) = asset
        val (w, h) = dim
        // 模拟不同大小（高清图更大）
        val baseKb = when {
            w >= 2000 -> 420_000L
            h >= 1500 -> 90_000L
            w == h -> 260_000L
            else -> 180_000L
        }
        return V2Media(
            id = id, code = code, name = name,
            folderId = folder, folderName = folderName,
            type = V2MediaType.IMAGE,
            durationMs = 0L,
            sizeBytes = baseKb + (counter % 5) * 21_000L,
            dateMillis = baseDate - TimeUnit.MINUTES.toMillis((index + 1).toLong()) * 41L,
            isFavorite = index % 6 == 0,
            isReviewed = index % 4 != 0,
            assetPath = "$I/$file",
            thumbPath = "$I/$file",
            spritePath = null,
            spriteManifestPath = null,
            naturalWidth = w, naturalHeight = h,
        )
    }
}
