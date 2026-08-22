package com.mediareview.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Blush,
    onPrimary = White,
    secondary = Cocoa,
    onSecondary = Cream,
    background = Cream,
    onBackground = TextMain,
    surface = Cream,
    onSurface = TextMain,
    error = Danger,
    onError = White,
)

private val MediaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun MediaReviewTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        shapes = MediaShapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}