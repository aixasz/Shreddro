package com.shreddro.app.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Shreddro brand tokens (docs/UI-UX.md): teal seed, M3 Expressive shapes.
 * Dynamic (wallpaper) color is used on Android 12+ when available; the teal
 * palette is the brand fallback.
 */
private val ShreddroLightColors = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA7F0E0),
    onPrimaryContainer = Color(0xFF00201B),
    secondaryContainer = Color(0xFFDCE9E3),
    onSecondaryContainer = Color(0xFF10403A),
    tertiaryContainer = Color(0xFFFFDF9E),
    onTertiaryContainer = Color(0xFF5C4300),
    surface = Color(0xFFF4FAF7),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFE8F0EC),
    onSurfaceVariant = Color(0xFF3F4946),
    background = Color(0xFFF4FAF7),
    onBackground = Color(0xFF171D1B),
    error = Color(0xFFB3261E),
    outline = Color(0xFF6F7975),
)

private val ShreddroShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ShreddroTheme(useDynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(LocalContext.current)
    } else {
        ShreddroLightColors
    }
    MaterialTheme(colorScheme = colors, shapes = ShreddroShapes, content = content)
}
