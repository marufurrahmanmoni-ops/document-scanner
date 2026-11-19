package com.docscanner.app.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Light only, dynamic colour off: the brand blue stays the same on every device (compose-theme.md). */
@Composable
fun ScannerTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalScannerColors provides ScannerColors()) {
        MaterialTheme(
            colorScheme = ScannerColorScheme,
            typography = ScannerTypography,
            shapes = ScannerShapes,
            content = content,
        )
    }
}

object ScannerTheme {
    val colors: ScannerColors
        @Composable @ReadOnlyComposable get() = LocalScannerColors.current
}
