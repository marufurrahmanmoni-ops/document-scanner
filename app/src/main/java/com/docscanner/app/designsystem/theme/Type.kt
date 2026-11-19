package com.docscanner.app.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Type scale from tokens.json "type". The token font is Inter + Noto Sans Bengali (OFL), bundled in res/font.
 * The font files are not in the repo yet (see build-log.md), so this uses the platform sans
 * (Roboto + Noto Sans Bengali system fallback). Swap FontFamily.Default for the bundled family later.
 */
private val Sans = FontFamily.Default

private val Base = TextStyle(
    fontFamily = Sans,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0) = Base.copy(
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.sp,
)

internal val ScannerTypography = Typography(
    displaySmall = style(32, 40, 700, -0.5),
    headlineSmall = style(24, 32, 700, -0.25),
    titleLarge = style(20, 28, 600),
    titleMedium = style(16, 24, 600),
    titleSmall = style(14, 20, 600, 0.1),
    bodyLarge = style(16, 24, 400),
    bodyMedium = style(14, 20, 400),
    bodySmall = style(12, 16, 400, 0.2),
    labelLarge = style(14, 20, 600, 0.1),
    labelMedium = style(12, 16, 600, 0.2),
    labelSmall = style(11, 16, 600, 0.8),
)
