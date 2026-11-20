package com.mediareview.app.feature.v2.nsfw.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val NSFW_GENERATION_PROGRESS_CAPABILITY = "mobile_generation_progress_v1"

@Serializable
data class NsfwGenerationProgress(
    @SerialName("current_image") val currentImage: Int? = null,
    @SerialName("images_in_segment") val imagesInSegment: Int? = null,
    @SerialName("remaining_segments") val remainingSegments: Int,
) {
    val valid: Boolean get() = remainingSegments >= 0 &&
        when {
            currentImage == null -> imagesInSegment == null
            imagesInSegment == null -> false
            else -> remainingSegments > 0 && currentImage in 1..imagesInSegment
        }
}
