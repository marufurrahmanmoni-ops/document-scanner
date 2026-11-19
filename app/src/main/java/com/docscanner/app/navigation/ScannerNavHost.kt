package com.docscanner.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.docscanner.app.designsystem.theme.Motion
import com.docscanner.app.feature.camera.CameraScreen
import com.docscanner.app.feature.document.DocumentScreen
import com.docscanner.app.feature.editor.CropScreen
import com.docscanner.app.feature.editor.EnhanceScreen
import com.docscanner.app.feature.editor.ReviewScreen
import com.docscanner.app.feature.home.FilesScreen
import com.docscanner.app.feature.home.HomeScreen
import com.docscanner.app.feature.home.ToolsScreen
import com.docscanner.app.feature.settings.SettingsScreen
import com.docscanner.app.model.ScanMode

@Composable
fun ScannerNavHost(nav: NavHostController = rememberNavController()) {
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val tab = when {
        dest?.hasRoute<HomeRoute>() == true -> Tab.HOME
        dest?.hasRoute<FilesRoute>() == true -> Tab.FILES
        dest?.hasRoute<ToolsRoute>() == true -> Tab.TOOLS
        dest?.hasRoute<SettingsRoute>() == true -> Tab.SETTINGS
        else -> null
    }

    val actions = NavActions(nav)

    AppScaffold(
        currentTab = tab,
        onTab = actions::openTab,
        onScan = { actions.openCamera(null) },
    ) { contentPadding ->
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            enterTransition = { fadeIn(tween(Motion.BASE)) },
            exitTransition = { fadeOut(tween(Motion.FAST)) },
            popEnterTransition = { fadeIn(tween(Motion.BASE)) },
            popExitTransition = { fadeOut(tween(Motion.FAST)) },
        ) {
            composable<HomeRoute> {
                HomeScreen(contentPadding = contentPadding, actions = actions)
            }
            composable<FilesRoute> {
                FilesScreen(contentPadding = contentPadding, actions = actions)
            }
            composable<ToolsRoute> {
                ToolsScreen(contentPadding = contentPadding, actions = actions)
            }
            composable<SettingsRoute> {
                SettingsScreen(contentPadding = contentPadding)
            }
            composable<CameraRoute>(
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) { entry ->
                CameraScreen(route = entry.toRoute(), actions = actions)
            }
            composable<CropRoute> { entry ->
                CropScreen(route = entry.toRoute(), actions = actions)
            }
            composable<EnhanceRoute> { entry ->
                EnhanceScreen(route = entry.toRoute(), actions = actions)
            }
            composable<ReviewRoute> { entry ->
                ReviewScreen(route = entry.toRoute(), actions = actions)
            }
            composable<DocumentRoute> { entry ->
                DocumentScreen(route = entry.toRoute(), actions = actions)
            }
        }
    }
}

enum class Tab { HOME, FILES, TOOLS, SETTINGS }

/** Every navigation the app does, in one place, so screens never build back stacks themselves. */
class NavActions(private val nav: NavHostController) {

    fun openTab(tab: Tab) {
        val route: Any = when (tab) {
            Tab.HOME -> HomeRoute
            Tab.FILES -> FilesRoute
            Tab.TOOLS -> ToolsRoute
            Tab.SETTINGS -> SettingsRoute
        }
        nav.navigate(route) {
            popUpTo(HomeRoute) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /** [mode] null = the default mode from Settings. */
    fun openCamera(mode: ScanMode?, targetDocId: String? = null, replacePageId: String? = null) {
        nav.navigate(CameraRoute(mode = mode?.id ?: "", targetDocId = targetDocId, replacePageId = replacePageId))
    }

    fun openCrop(docId: String, pageId: String, flow: String, replaceCamera: Boolean = false) {
        nav.navigate(CropRoute(docId, pageId, flow)) {
            if (replaceCamera) popUpTo<CameraRoute> { inclusive = true }
        }
    }

    fun openEnhance(docId: String, pageId: String, flow: String) {
        nav.navigate(EnhanceRoute(docId, pageId, flow))
    }

    /** Opens Review for [docId], replacing any camera/crop/enhance screens of this session. */
    fun openReview(docId: String) {
        val returned = nav.popBackStack<ReviewRoute>(inclusive = false)
        if (!returned) {
            nav.navigate(ReviewRoute(docId)) {
                popUpTo<CameraRoute> { inclusive = true }
            }
        }
    }

    /** After an edit started from Review, go back to it. */
    fun backToReview(docId: String) {
        if (!nav.popBackStack<ReviewRoute>(inclusive = false)) openReview(docId)
    }

    /** Opens a saved document, clearing the scan session from the back stack. */
    fun openSavedDocument(docId: String) {
        nav.navigate(DocumentRoute(docId)) {
            popUpTo(HomeRoute) { inclusive = false }
        }
    }

    fun openDocument(docId: String) {
        nav.navigate(DocumentRoute(docId))
    }

    /** Where a gallery import lands: one new page goes to Crop, several (or into an existing doc) go to Review. */
    fun routeImport(outcome: com.docscanner.app.feature.common.ImportOutcome) {
        if (outcome.count == 1 && !outcome.intoExisting) {
            openCrop(outcome.docId, outcome.firstPageId, EditFlow.SINGLE, replaceCamera = true)
        } else {
            openReview(outcome.docId)
        }
    }

    fun back() {
        nav.popBackStack()
    }

    /** Leaves the camera. If the app was opened straight into it, falls back to Home. */
    fun closeCamera() {
        if (!nav.popBackStack()) nav.navigate(HomeRoute)
    }
}
