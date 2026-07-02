package com.kjyeop.babygallery.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BabyGalleryColorScheme = lightColorScheme(
    primary = Color(0xFF2F6F73),
    onPrimary = Color.White,
    secondary = Color(0xFF7A5C41),
    onSecondary = Color.White,
    tertiary = Color(0xFFD08B4C),
    background = Color(0xFFF7FAF9),
    onBackground = Color(0xFF182321),
    surface = Color.White,
    onSurface = Color(0xFF182321),
    surfaceVariant = Color(0xFFE0E7E4),
    onSurfaceVariant = Color(0xFF44504D),
    outline = Color(0xFF73807C),
)

@Composable
fun BabyGalleryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BabyGalleryColorScheme,
        typography = Typography(),
        content = content,
    )
}
