package com.mediareview.app.feature.mediawall

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.model.SpriteManifestDto
import com.mediareview.app.feature.home.data.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest

/** 单个视频卡片的雪碧图预览状态。 */
data class SpriteScrubState(
    val loading: Boolean = false,
    /** 清单已就绪且雪碧图位图已加载,可开始横向滑动预览。 */
    val ready: Boolean = false,
    /** 未就绪,已触发后台生成,等待生成完成后再次长按。 */
    val pending: Boolean = false,
    /** 后台生成进度(0-100);null 表示服务端未报告。 */
    val progress: Int? = null,
    /** 生成任务终态(failed/cancelled);用于区分失败与进行中。 */
    val taskStatus: String? = null,
    /** 生成任务引用(POST /cache/sprites 返回的 task_id),供协作取消。 */
    val taskId: String? = null,
    val manifest: SpriteManifestDto? = null,
    val bitmap: ImageBitmap? = null,
    val tileIndex: Int = 0,
    val fraction: Float = 0f,
    val scrubbing: Boolean = false,
) {
    /** 预览覆盖层应显示的内容类型。 */
    val showScrub: Boolean get() = scrubbing && ready && manifest != null && bitmap != null
    val showWaiting: Boolean get() = scrubbing && !ready
}

/** 横向位置 [0f,1f] 映射到雪碧图格子索引(帧),再由 interval_ms 对应时间。 */
fun tileIndexFor(fraction: Float, count: Int): Int {
    if (count <= 0) return 0
    return (fraction.coerceIn(0f, 1f) * count).toInt().coerceIn(0, count - 1)
}

/** 等待覆盖层的中文进度/终态文案。 */
fun spriteProgressLabel(progress: Int?, taskStatus: String? = null): String = when (taskStatus) {
    "failed" -> "雪碧图生成失败,可重新长按重试"
    "cancelled" -> "已取消生成"
    else -> if (progress == null) "雪碧图生成中…" else "雪碧图生成中 $progress%"
}

/** 雪碧图清单已就绪但未生成,低频轮询到就绪为止的最大次数(2s × 15 = 30s)。 */
private const val MAX_POLL_COUNT = 15
/** 同时缓存的完整雪碧图位图上限(LRU 淘汰,防止内存持续上涨)。 */
private const val MAX_BITMAPS = 12

/**
 * 管理媒体墙中每个视频卡片的雪碧图清单拉取、位图加载与滑动位置。
 * 以 media_id 为键缓存状态,长按只拉取一次;未就绪时触发生成并低频轮询,生成完成自动进入 ready。
 */
@HiltViewModel
class SpriteViewModel @Inject constructor(
    private val repository: MediaRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _states = MutableStateFlow<Map<String, SpriteScrubState>>(emptyMap())
    val states: StateFlow<Map<String, SpriteScrubState>> = _states.asStateFlow()

    /** 位图缓存顺序(先入先出,超上限时从最旧开始淘汰)。 */
    private val bitmapOrder = ArrayDeque<String>()

    /** 每个媒体在途的生成轮询任务,支持协作取消。 */
    private val pollJobs = mutableMapOf<String, Job>()

    fun state(mediaId: String): SpriteScrubState = _states.value[mediaId] ?: SpriteScrubState()

    /** 长按开始:首次拉取清单并加载位图;未生成则触发生成并轮询进度,完成后自动就绪。 */
    fun begin(mediaId: String) {
        val cur = state(mediaId)
        if (cur.loading || cur.ready || cur.pending) return
        _states.update { it + (mediaId to cur.copy(loading = true)) }
        viewModelScope.launch {
            val result = runCatching { repository.loadSpriteManifest(mediaId) }
            result.onSuccess { manifest ->
                val bitmap = loadBitmap(manifest.url.orEmpty())
                updateReady(mediaId, manifest, bitmap)
            }.onFailure {
                // 404/未生成:触发一次后台生成(幂等),低频轮询任务进度直到完成
                val ensured = runCatching { repository.ensureSprite(mediaId) }.getOrNull()
                val taskId = ensured?.task_id.orEmpty()
                _states.update { map ->
                    map + (mediaId to SpriteScrubState(loading = false, pending = true, taskId = taskId))
                }
                pollUntilReady(mediaId, taskId)
            }
        }
    }

    /** 未就绪时轮询任务进度与清单,生成完成后把当前卡片自动置为 ready。 */
    private fun pollUntilReady(mediaId: String, taskId: String) {
        pollJobs.remove(mediaId)?.cancel()
        pollJobs[mediaId] = viewModelScope.launch {
            repeat(MAX_POLL_COUNT) {
                delay(2_000)
                if (taskId.isNotBlank()) {
                    runCatching { repository.loadTask(taskId) }.getOrNull()?.let { task ->
                        _states.update { map ->
                            val cur = map[mediaId] ?: return@update map
                            map + (mediaId to cur.copy(progress = task.progress, taskStatus = task.status))
                        }
                        if (task.status == "failed" || task.status == "cancelled") {
                            // 终态失败:停止轮询并允许用户重新长按重试
                            pollJobs.remove(mediaId)
                            _states.update { map ->
                                val cur = map[mediaId] ?: return@update map
                                map + (mediaId to cur.copy(pending = false, scrubbing = false))
                            }
                            return@launch
                        }
                    }
                }
                val m = runCatching { repository.loadSpriteManifest(mediaId) }.getOrNull()
                if (m != null && m.status == "ready" && !m.url.isNullOrBlank()) {
                    val bitmap = loadBitmap(m.url)
                    updateReady(mediaId, m, bitmap)
                    pollJobs.remove(mediaId)
                    return@launch
                }
            }
            // 轮询耗尽仍未就绪:回到可重试状态,允许用户再次长按触发幂等 ensure
            pollJobs.remove(mediaId)
            _states.update { map ->
                val cur = map[mediaId] ?: return@update map
                if (cur.pending && !cur.ready) map + (mediaId to SpriteScrubState()) else map
            }
        }
    }

    /** 协作取消该媒体的生成轮询与后台任务,并清除预览状态。 */
    fun cancel(mediaId: String) {
        pollJobs.remove(mediaId)?.cancel()
        val taskId = state(mediaId).taskId
        if (!taskId.isNullOrBlank()) {
            viewModelScope.launch { runCatching { repository.cancelTask(taskId) } }
        }
        _states.update { map -> map + (mediaId to SpriteScrubState()) }
    }

    private fun updateReady(mediaId: String, manifest: SpriteManifestDto, bitmap: ImageBitmap?) {
        bitmapOrder.remove(mediaId)
        bitmapOrder.addLast(mediaId)
        _states.update { map ->
            var result = map + (
                mediaId to SpriteScrubState(
                    loading = false,
                    ready = bitmap != null,
                    pending = bitmap == null,
                    manifest = manifest,
                    bitmap = bitmap,
                )
                )
            while (bitmapOrder.size > MAX_BITMAPS) {
                val evictId = bitmapOrder.removeFirst()
                val st = result[evictId] ?: continue
                result = result + (evictId to st.copy(bitmap = null, ready = false))
            }
            result
        }
    }

    /** 横向位置变化:映射为雪碧图格子索引与时间百分比。 */
    fun setFraction(mediaId: String, fraction: Float) {
        _states.update { map ->
            val st = map[mediaId] ?: return@update map
            val m = st.manifest
            val idx = tileIndexFor(fraction, m?.count ?: 0)
            map + (mediaId to st.copy(
                scrubbing = true,
                tileIndex = idx,
                fraction = fraction.coerceIn(0f, 1f),
            ))
        }
    }

    /** 松手结束预览,恢复普通卡片状态。 */
    fun end(mediaId: String) {
        _states.update { map ->
            val st = map[mediaId] ?: return@update map
            map + (mediaId to st.copy(scrubbing = false, tileIndex = 0, fraction = 0f))
        }
    }

    private suspend fun loadBitmap(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        return runCatching {
            val loader: ImageLoader = context.imageLoader
            val request = ImageRequest.Builder(context).data(url).build()
            val result = loader.execute(request)
            (result.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
        }.getOrNull()
    }
}
