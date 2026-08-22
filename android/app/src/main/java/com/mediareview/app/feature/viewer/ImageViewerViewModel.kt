package com.mediareview.app.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediareview.app.core.model.MediaSummary
import com.mediareview.app.feature.home.data.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ImageViewerUiState(
    val loading: Boolean = true,
    val media: MediaSummary? = null,
    val error: String? = null,
) {
    /** 查看器优先读原图,缺失时退回封面缩略图。 */
    val imageUrl: String?
        get() = media?.original_url?.ifBlank { null } ?: media?.cover_url?.ifBlank { null }
}

/**
 * 图片查看器:只接收 mediaId,自行拉取详情(原图优先),不依赖路由里塞长 URL。
 */
@HiltViewModel
class ImageViewerViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ImageViewerUiState())
    val ui: StateFlow<ImageViewerUiState> = _ui.asStateFlow()

    fun load(mediaId: String) {
        viewModelScope.launch {
            _ui.value = ImageViewerUiState(loading = true)
            val media = repository.loadDetail(mediaId)
            _ui.value = if (media != null) {
                ImageViewerUiState(loading = false, media = media)
            } else {
                ImageViewerUiState(loading = false, error = "无法加载图片详情")
            }
        }
    }
}

/**
 * 平移边界约束:缩到 1x 时归零;放大时不允许图片被拖出屏幕。
 * 纯函数便于单测。
 */
fun clampPanOffset(
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    containerW: Float,
    containerH: Float,
): Pair<Float, Float> {
    if (scale <= 1f) return 0f to 0f
    val maxX = (containerW * (scale - 1f)) / 2f
    val maxY = (containerH * (scale - 1f)) / 2f
    return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
}
