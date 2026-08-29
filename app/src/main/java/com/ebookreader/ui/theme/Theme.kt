package com.ebookreader.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4636),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0D5C7),
    surface = Color.White,
    onSurface = Color(0xFF2C1810),
    onSurfaceVariant = Color(0xFF8B6F47),
    background = Color(0xFFFFF8F0),
    onBackground = Color(0xFF2C1810),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8A88A),
    onPrimary = Color(0xFF2C1810),
    primaryContainer = Color(0xFF4A3728),
    surface = Color(0xFF2A2A2A),
    onSurface = Color(0xFFC8C0B0),
    onSurfaceVariant = Color(0xFF8A8070),
    background = Color(0xFF1A1A1A),
    onBackground = Color(0xFFC8C0B0),
)

/** 「已阅」标签 chip 的配色，按系统深浅主题区分（深色用木纹棕，浅色用暖白底）。 */
data class ReadTagChipColors(
    val container: Color,
    val label: Color,
    val selectedContainer: Color,
    val selectedLabel: Color,
)

@Composable
fun readTagChipColors(): ReadTagChipColors {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        ReadTagChipColors(
            container = Color(0xFF363029),
            label = Color(0xFFC9BFA8),
            selectedContainer = Color(0xFFBFA77D),
            selectedLabel = Color(0xFF3E2723),
        )
    } else {
        ReadTagChipColors(
            container = Color(0xFFEDE3D2),
            label = Color(0xFF6B4E2E),
            selectedContainer = Color(0xFFBFA77D),
            selectedLabel = Color(0xFF3E2723),
        )
    }
}

@Composable
fun EBookReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
