package com.glazkov.outagewatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Compass design tokens: an iOS-native palette in light and dark. Chosen over
 * MaterialTheme's scheme because the whole app leans on grouped inset lists,
 * system blue, and iOS status colors that don't map cleanly onto Material.
 */
data class CompassColors(
    val dark: Boolean,
    val background: Color,
    val card: Color,
    val cardPressed: Color,
    val separator: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val accent: Color,
    val outage: Color,
    val outageTint: Color,
    val clear: Color,
    val clearTint: Color,
)

private val LightColors = CompassColors(
    dark = false,
    background = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    cardPressed = Color(0xFFE5E5EA),
    separator = Color(0xFFECECF0),
    label = Color(0xFF1C1C1E),
    secondary = Color(0xFF8A8A8E),
    tertiary = Color(0xFFC4C4C9),
    accent = Color(0xFF007AFF),
    outage = Color(0xFFFF3B30),
    outageTint = Color(0xFFFFE5DE),
    clear = Color(0xFF34C759),
    clearTint = Color(0xFFE2F6EA),
)

private val DarkColors = CompassColors(
    dark = true,
    background = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    cardPressed = Color(0xFF2C2C2E),
    separator = Color(0xFF2C2C2E),
    label = Color(0xFFFFFFFF),
    secondary = Color(0xFF8E8E93),
    tertiary = Color(0xFF48484A),
    accent = Color(0xFF0A84FF),
    outage = Color(0xFFFF453A),
    outageTint = Color(0xFF3A1E1A),
    clear = Color(0xFF30D158),
    clearTint = Color(0xFF12321F),
)

val LocalCompass = staticCompositionLocalOf { LightColors }

@Composable
fun CompassTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    CompositionLocalProvider(LocalCompass provides colors, content = content)
}
