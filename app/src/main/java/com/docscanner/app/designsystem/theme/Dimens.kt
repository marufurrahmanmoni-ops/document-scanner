package com.docscanner.app.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** space, size, radius and elevation tokens (tokens.json), in dp. */
object Dimens {
    // space
    val space0 = 0.dp
    val space0_5 = 2.dp
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 20.dp
    val space6 = 24.dp
    val space8 = 32.dp
    val space10 = 40.dp
    val space12 = 48.dp
    val space16 = 64.dp
    val gutterCompact = 16.dp
    val gutterMedium = 24.dp
    val gridGap = 12.dp

    // size
    val touchMin = 48.dp
    val iconSm = 20.dp
    val icon = 24.dp
    val iconLg = 32.dp
    val topBar = 64.dp
    val bottomNav = 80.dp
    val scanFab = 64.dp
    val shutter = 76.dp
    val shutterInner = 62.dp
    val buttonSm = 36.dp
    val buttonMd = 44.dp
    val buttonLg = 52.dp
    val listRow = 72.dp
    val docCardMinWidth = 156.dp
    val pageThumb = 64.dp
    val cropHandle = 24.dp
    val cropHandleHit = 48.dp
    val navRail = 80.dp
    val contentMax = 840.dp
    val filterTileW = 72.dp
    val filterTileH = 96.dp
    val stackThumbW = 42.dp
    val stackThumbH = 56.dp
    val galleryThumb = 48.dp
    val navPillW = 64.dp
    val navPillH = 32.dp
    val edgeStroke = 3.dp
    val edgeHaloStroke = 5.dp
    val cornerDot = 8.dp
    val focusRing = 2.dp
    val hairline = 1.dp
    val quickActionH = 72.dp

    // radius
    val radiusXs = 4.dp
    val radiusSm = 8.dp
    val radiusMd = 12.dp
    val radiusLg = 16.dp
    val radiusXl = 24.dp

    // elevation
    val elevPage = 2.dp
    val elevRaised = 3.dp
    val elevFab = 6.dp
    val elevSheet = 8.dp

    // breakpoints (window width)
    val medium = 600.dp
    val expanded = 840.dp
}

object Motion {
    const val FAST = 100
    const val BASE = 200
    const val SLOW = 300
    const val PAGE = 350
    const val LOCK_HOLD_MS = 600L
}

internal val ScannerShapes = Shapes(
    extraSmall = RoundedCornerShape(Dimens.radiusXs),
    small = RoundedCornerShape(Dimens.radiusSm),
    medium = RoundedCornerShape(Dimens.radiusMd),
    large = RoundedCornerShape(Dimens.radiusLg),
    extraLarge = RoundedCornerShape(Dimens.radiusXl),
)
