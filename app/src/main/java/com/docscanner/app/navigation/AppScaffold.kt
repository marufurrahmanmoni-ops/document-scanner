package com.docscanner.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.docscanner.app.R
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.designsystem.theme.ScannerTheme

private data class TabSpec(val tab: Tab, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    TabSpec(Tab.HOME, R.string.tab_home, Icons.Outlined.Home, Icons.Rounded.Home),
    TabSpec(Tab.FILES, R.string.tab_files, Icons.Outlined.Folder, Icons.Rounded.Folder),
    TabSpec(Tab.TOOLS, R.string.tab_tools, Icons.Outlined.Widgets, Icons.Rounded.Widgets),
    TabSpec(Tab.SETTINGS, R.string.tab_settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
)

/**
 * screens.md "Global layout": bottom bar + docked Scan button on compact widths,
 * navigation rail with the Scan button on top from 600dp. Hidden on full-screen flows (camera, editor, document).
 */
@Composable
fun AppScaffold(
    currentTab: Tab?,
    onTab: (Tab) -> Unit,
    onScan: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= Dimens.medium
        val showNav = currentTab != null
        // The content slot (the NavHost) is always called from the same place in the tree, so switching between
        // tab screens and full-screen flows never recreates it or its back stack.
        Row(Modifier.fillMaxSize()) {
            if (showNav && wide) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    header = { ScanFab(onScan, Modifier.padding(vertical = Dimens.space4)) },
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    TABS.forEach { spec ->
                        val selected = spec.tab == currentTab
                        NavigationRailItem(
                            selected = selected,
                            onClick = { onTab(spec.tab) },
                            icon = { Icon(if (selected) spec.selectedIcon else spec.icon, contentDescription = null) },
                            label = { Text(stringResource(spec.label)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
                VerticalDivider(color = ScannerTheme.colors.border)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    content(if (showNav && !wide) PaddingValues(bottom = Dimens.space4) else PaddingValues())
                }
                if (showNav && !wide) {
                    HorizontalDivider(color = ScannerTheme.colors.border)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = Dimens.space0,
                        windowInsets = WindowInsets.navigationBars,
                    ) {
                        TABS.take(2).forEach { BarItem(it, currentTab, onTab) }
                        // Gap for the docked Scan button.
                        Spacer(Modifier.weight(1f))
                        TABS.drop(2).forEach { BarItem(it, currentTab, onTab) }
                    }
                }
            }
        }
        if (showNav && !wide) {
            ScanFab(
                onScan,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .offset(y = -(Dimens.bottomNav - Dimens.scanFab / 2 - Dimens.space3)),
            )
        }
    }
}

@Composable
private fun RowScope.BarItem(spec: TabSpec, current: Tab?, onTab: (Tab) -> Unit) {
    val selected = spec.tab == current
    NavigationBarItem(
        selected = selected,
        onClick = { onTab(spec.tab) },
        icon = { Icon(if (selected) spec.selectedIcon else spec.icon, contentDescription = null) },
        label = { Text(stringResource(spec.label), style = MaterialTheme.typography.labelMedium) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/** components.md "ScanFab": the one-tap path to the camera. */
@Composable
fun ScanFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.scan_fab_a11y)
    LargeFloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .size(Dimens.scanFab)
            .semantics { contentDescription = label },
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = Dimens.elevFab),
    ) {
        Icon(Icons.Rounded.DocumentScanner, contentDescription = null, modifier = Modifier.size(Dimens.iconLg))
    }
}

