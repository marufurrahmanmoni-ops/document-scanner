package com.docscanner.app.designsystem.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Design tokens from replica/design/tokens.json. This is the ONLY file that holds colour values.
 * Components read roles (MaterialTheme.colorScheme.* or ScannerTheme.colors.*), never hex.
 */
internal object Tokens {
    val bg = Color(0xFFFFFFFF)
    val surface = Color(0xFFF5F7FA)
    val surfaceSunken = Color(0xFFEBEEF3)
    val border = Color(0xFFE2E6EC)
    val borderStrong = Color(0xFFC9CFD8)
    val borderInput = Color(0xFF7F8896)
    val text = Color(0xFF111827)
    val textMuted = Color(0xFF4B5563)
    val textSubtle = Color(0xFF666F7C)
    val textDisabled = Color(0xFF9AA3AF)
    val accent = Color(0xFF1F5FD6)
    val accentPressed = Color(0xFF174BB0)
    val onAccent = Color(0xFFFFFFFF)
    val accentContainer = Color(0xFFE8F0FE)
    val onAccentContainer = Color(0xFF0E3A8C)
    val danger = Color(0xFFC42B2B)
    val dangerContainer = Color(0xFFFDECEC)
    val onDanger = Color(0xFFFFFFFF)
    val success = Color(0xFF1B7A45)
    val successContainer = Color(0xFFE6F4EC)
    val warning = Color(0xFF9A5800)
    val warningContainer = Color(0xFFFFF4E0)
    val cameraBg = Color(0xFF0B0D10)
    val cameraSurface = Color(0xFF1A1D22)
    val cameraText = Color(0xFFFFFFFF)
    val cameraTextMuted = Color(0xFFB8C0CC)
    val edgeSearching = Color(0xFFFFFFFF)
    val edgeDetected = Color(0xFF5B9BFF)
    val edgeLocked = Color(0xFF3DD68C)
    val page = Color(0xFFFFFFFF)
    // overlay.* tokens
    val scrim = Color(0x7A111827)          // rgba(17,24,39,0.48)
    val cameraScrim = Color(0x8C000000)    // rgba(0,0,0,0.55)
    val edgeFill = Color(0x2E5B9BFF)       // rgba(91,155,255,0.18)
    val edgeHalo = Color(0x59000000)       // rgba(0,0,0,0.35)
    val selected = Color(0x1A1F5FD6)       // rgba(31,95,214,0.10)
}

@Immutable
data class ScannerColors(
    val textSubtle: Color = Tokens.textSubtle,
    val textDisabled: Color = Tokens.textDisabled,
    val border: Color = Tokens.border,
    val borderStrong: Color = Tokens.borderStrong,
    val surfaceSunken: Color = Tokens.surfaceSunken,
    val accentPressed: Color = Tokens.accentPressed,
    val success: Color = Tokens.success,
    val successContainer: Color = Tokens.successContainer,
    val warning: Color = Tokens.warning,
    val warningContainer: Color = Tokens.warningContainer,
    val cameraBg: Color = Tokens.cameraBg,
    val cameraSurface: Color = Tokens.cameraSurface,
    val cameraText: Color = Tokens.cameraText,
    val cameraTextMuted: Color = Tokens.cameraTextMuted,
    val cameraScrim: Color = Tokens.cameraScrim,
    val edgeSearching: Color = Tokens.edgeSearching,
    val edgeDetected: Color = Tokens.edgeDetected,
    val edgeLocked: Color = Tokens.edgeLocked,
    val edgeFill: Color = Tokens.edgeFill,
    val edgeHalo: Color = Tokens.edgeHalo,
    val page: Color = Tokens.page,
    val selected: Color = Tokens.selected,
)

val LocalScannerColors = staticCompositionLocalOf { ScannerColors() }

internal val ScannerColorScheme = lightColorScheme(
    primary = Tokens.accent,
    onPrimary = Tokens.onAccent,
    primaryContainer = Tokens.accentContainer,
    onPrimaryContainer = Tokens.onAccentContainer,
    secondary = Tokens.textMuted,
    onSecondary = Tokens.bg,
    secondaryContainer = Tokens.surfaceSunken,
    onSecondaryContainer = Tokens.text,
    tertiary = Tokens.success,
    onTertiary = Tokens.onAccent,
    background = Tokens.bg,
    onBackground = Tokens.text,
    surface = Tokens.bg,
    onSurface = Tokens.text,
    surfaceVariant = Tokens.surface,
    onSurfaceVariant = Tokens.textMuted,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Tokens.bg,
    surfaceContainerLow = Tokens.surface,
    surfaceContainer = Tokens.surface,
    surfaceContainerHigh = Tokens.surfaceSunken,
    surfaceContainerHighest = Tokens.surfaceSunken,
    surfaceBright = Tokens.bg,
    surfaceDim = Tokens.surfaceSunken,
    inverseSurface = Tokens.text,
    inverseOnSurface = Tokens.bg,
    inversePrimary = Tokens.accentContainer,
    outline = Tokens.borderInput,
    outlineVariant = Tokens.border,
    error = Tokens.danger,
    onError = Tokens.onDanger,
    errorContainer = Tokens.dangerContainer,
    onErrorContainer = Tokens.danger,
    scrim = Tokens.scrim,
)
