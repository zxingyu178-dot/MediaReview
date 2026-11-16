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
    primaryContainer = MediaAccentContainer,
    onPrimaryContainer = MediaOnAccentContainer,
    secondary = MediaSuccess,
    onSecondary = MediaBackground,
    secondaryContainer = MediaSurfaceRaised,
    onSecondaryContainer = MediaTextPrimary,
    tertiary = MediaAccent,
    onTertiary = MediaBackground,
    tertiaryContainer = MediaAccentContainer,
    onTertiaryContainer = MediaOnAccentContainer,
    background = MediaBackground,
    onBackground = MediaTextPrimary,
    surface = MediaSurface,
    onSurface = MediaTextPrimary,
    surfaceVariant = MediaSurfaceRaised,
    onSurfaceVariant = MediaTextSecondary,
    surfaceTint = MediaAccent,
    inverseSurface = MediaTextPrimary,
    inverseOnSurface = MediaBackground,
    inversePrimary = MediaAccentContainer,
    outline = MediaOutline,
    outlineVariant = MediaOutline.copy(alpha = 0.45f),
    error = MediaDanger,
    onError = MediaBackground,
    errorContainer = MediaDangerContainer,
    onErrorContainer = MediaDanger,
)

private val MediaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(MediaRadii.Small),
    medium = RoundedCornerShape(MediaRadii.Medium),
    large = RoundedCornerShape(MediaRadii.Large),
    extraLarge = RoundedCornerShape(24.dp),
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
