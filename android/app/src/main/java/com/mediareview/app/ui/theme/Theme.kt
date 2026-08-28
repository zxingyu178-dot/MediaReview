package com.mediareview.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = MediaAccent,
    onPrimary = MediaBackground,
    secondary = MediaSuccess,
    onSecondary = MediaBackground,
    background = MediaBackground,
    onBackground = MediaTextPrimary,
    surface = MediaSurface,
    onSurface = MediaTextPrimary,
    surfaceVariant = MediaSurfaceRaised,
    onSurfaceVariant = MediaTextSecondary,
    outline = MediaOutline,
    error = MediaDanger,
    onError = MediaBackground,
)

private val MediaShapes = Shapes(
    small = RoundedCornerShape(MediaRadii.Small),
    medium = RoundedCornerShape(MediaRadii.Medium),
    large = RoundedCornerShape(MediaRadii.Large),
)

@Composable
fun MediaReviewTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        shapes = MediaShapes,
        typography = MediaTypography,
        content = content,
    )
}
