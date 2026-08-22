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

    fun state(mediaId: String): SpriteScrubState = _states.value[mediaId] ?: SpriteScrubState()

    /** 长按开始:首次拉取清单并加载位图;未生成则触发生成并轮询,完成后自动就绪。 */
    fun begin(mediaId: String) {
        val cur = state(mediaId)
        if (cur.loading || cur.ready) return
        _states.update { it + (mediaId to cur.copy(loading = true)) }
        viewModelScope.launch {
            val result = runCatching { repository.loadSpriteManifest(mediaId) }
            result.onSuccess { manifest ->
                val bitmap = loadBitmap(manifest.url.orEmpty())
                updateReady(mediaId, manifest, bitmap)
            }.onFailure {
                // 404/未生成:触发一次后台生成,低频轮询直到生成完成
                repository.ensureSprite(mediaId)
                _states.update { map ->
                    map + (mediaId to SpriteScrubState(loading = false, pending = true))
                }
                pollUntilReady(mediaId)
            }
        }
    }

    /** 未就绪时轮询清单,生成完成后把当前卡片自动置为 ready(无需用户再长按一次)。 */
    private suspend fun pollUntilReady(mediaId: String) {
        repeat(MAX_POLL_COUNT) {
            delay(2_000)
            val m = runCatching { repository.loadSpriteManifest(mediaId) }.getOrNull()
            if (m != null && m.status == "ready" && !m.url.isNullOrBlank()) {
                val bitmap = loadBitmap(m.url)
                updateReady(mediaId, m, bitmap)
                return
            }
        }
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
            val idx = if (m != null && m.count > 0) {
                (fraction.coerceIn(0f, 1f) * m.count).toInt().coerceIn(0, m.count - 1)
            } else 0
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
