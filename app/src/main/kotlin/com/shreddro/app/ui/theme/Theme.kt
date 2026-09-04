package com.shreddro.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Shreddro brand tokens (docs/UI-UX.md): teal seed, M3 Expressive shapes.
 * Follows the system light/dark setting; dynamic (wallpaper) color is used on
 * Android 12+ when requested, the teal palettes are the brand fallback.
 *
 * Screens only ever reference `MaterialTheme.colorScheme` roles (no literal
 * colors), so both palettes below are the complete theming surface.
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

/** Same teal seed, M3 dark tonal mapping (containers ~tone 30, surfaces ~tone 6–12). */
private val ShreddroDarkColors = darkColorScheme(
    primary = Color(0xFF8BD4C4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFA7F0E0),
    secondaryContainer = Color(0xFF2E4A44),
    onSecondaryContainer = Color(0xFFDCE9E3),
    tertiaryContainer = Color(0xFF5C4300),
    onTertiaryContainer = Color(0xFFFFDF9E),
    surface = Color(0xFF0F1513),
    onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C4),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B29),
    surfaceContainerHighest = Color(0xFF303634),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDEE4E0),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    outline = Color(0xFF89938F),
)

private val ShreddroShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ShreddroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ShreddroDarkColors
        else -> ShreddroLightColors
    }
    MaterialTheme(colorScheme = colors, shapes = ShreddroShapes, content = content)
}
