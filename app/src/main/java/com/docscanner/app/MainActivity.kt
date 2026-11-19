package com.docscanner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.graphics.toColorInt
import com.docscanner.app.designsystem.components.LocalImageLoader
import com.docscanner.app.designsystem.theme.ScannerTheme
import com.docscanner.app.navigation.ScannerNavHost

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, "#80000000".toColorInt()),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, "#80000000".toColorInt()),
        )
        super.onCreate(savedInstanceState)
        val container = (application as ScannerApp).container
        setContent {
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalImageLoader provides container.images,
            ) {
                ScannerTheme {
                    ScannerNavHost()
                }
            }
        }
    }
}
