package com.mediareview.app.feature.v2.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mediareview.app.feature.v2.model.V2Media
import com.mediareview.app.feature.v2.ui.components.MediaThumb

/** 兼容现有调用与 testTag 的薄封装；视觉统一由 [MediaThumb] 提供。 */
@Composable
fun MediaCard(
    media: V2Media,
    coverUri: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    displayMode: HomeDisplayMode = HomeDisplayMode.TWO_COLUMN,
    pendingDelete: Boolean = false,
) {
    MediaThumb(
        media = media,
        coverUri = coverUri,
        onClick = onClick,
        modifier = modifier,
        displayMode = displayMode,
        pendingDelete = pendingDelete,
    )
}
