package org.zotero.android.screens.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.zotero.android.architecture.ui.CustomLayoutSize
import org.zotero.android.screens.reader.sidebar.ReaderSidebar

// On a tall, narrow phone screen a full-width sidebar is fine. On a near-square
// (unfolded foldable) or landscape screen it would bury the whole page, so the
// sidebar floats over the left of the reading area instead. We can't rely on the
// (min-dimension) tablet classification — a foldable's near-1:1 inner display is
// often small in dp yet still "small" (overlay mode). Aspect ratio is the robust
// signal; a large width also forces floating even when squarish.
private const val SIDEBAR_FLOAT_TALL_NARROW_RATIO = 1.4f
private const val SIDEBAR_FLOAT_MIN_SCREEN_WIDTH_DP = 600
private val SIDEBAR_FLOATING_MAX_WIDTH = 360.dp
// Cap the floating sidebar to a fraction of the width so even a small near-square
// screen keeps a usable strip of the page visible beside it.
private const val SIDEBAR_FLOATING_WIDTH_FRACTION = 0.66f

@Composable
internal fun ReaderOverlayMode(
    viewModel: ReaderViewModel,
    viewState: ReaderViewState,
    annotationsLazyListState: LazyListState,
    outlineLazyListState: LazyListState,
    thumbnailsLazyListState: LazyListState,
    layoutType: CustomLayoutSize.LayoutType,
    annotationMaxSideSize: Int,
) {
    val isPdfOrHtml = viewState.isPdfOrHtml()
    var boxModifier = Modifier.fillMaxSize()

    // PDF/HTML reader is full-bleed: no system-bar insets, so content fills the
    // display-cutout strip and the navigation-bar area. EPUB keeps its insets.
    if (!isPdfOrHtml) {
        val density = LocalDensity.current
        val systemBarsInsets = WindowInsets.systemBarsIgnoringVisibility
        val insetBottom = with(density) { systemBarsInsets.getBottom(this).toDp() }
        val statusBarTop = with(density) {
            systemBarsInsets.getTop(this).toDp()
        } + TopAppBarDefaults.TopAppBarExpandedHeight
        boxModifier = boxModifier
            .padding(
                top = statusBarTop,
                bottom = insetBottom,
            )
    }
    Box(
        modifier = boxModifier
    ) {
        ReaderBox(
            viewState = viewState,
            viewModel = viewModel,
            isOverlayMode = true,
        )
        AnimatedContent(
            targetState = viewState.showSideBar,
            transitionSpec = {
                readerSidebarTransitionSpec()
            }, label = ""
        ) { showSideBar ->
            if (showSideBar) {
                val isTablet = layoutType.isTablet()

                var modifier = Modifier
                    .fillMaxHeight()
                // The PDF/HTML reader is full-bleed (no parent insets) and the top
                // app bar floats over the content, so the sidebar's tab row would be
                // hidden under both the status bar and the top app bar. Inset the
                // sidebar panel below them so its tabs stay visible.
                if (isPdfOrHtml) {
                    val density = LocalDensity.current
                    val sidebarTop = with(density) {
                        WindowInsets.systemBarsIgnoringVisibility.getTop(this).toDp()
                    } + TopAppBarDefaults.TopAppBarExpandedHeight
                    modifier = modifier.padding(top = sidebarTop)
                }
                if (isTablet) {
                    modifier = modifier.width(330.dp)
                } else {
                    val configuration = LocalConfiguration.current
                    val widthDp = configuration.screenWidthDp
                    val heightDp = configuration.screenHeightDp
                    val isTallNarrowPhone = heightDp >= widthDp * SIDEBAR_FLOAT_TALL_NARROW_RATIO &&
                        widthDp < SIDEBAR_FLOAT_MIN_SCREEN_WIDTH_DP
                    modifier = if (isTallNarrowPhone) {
                        modifier.fillMaxWidth()
                    } else {
                        val floatingWidth = minOf(
                            SIDEBAR_FLOATING_MAX_WIDTH.value,
                            widthDp * SIDEBAR_FLOATING_WIDTH_FRACTION
                        ).dp
                        modifier.width(floatingWidth)
                    }
                }
                Box(modifier = modifier) {
                    ReaderSidebar(
                        viewState = viewState,
                        viewModel = viewModel,
                        annotationsLazyListState = annotationsLazyListState,
                        outlineLazyListState = outlineLazyListState,
                        thumbnailsLazyListState = thumbnailsLazyListState,
                        annotationMaxSideSize = annotationMaxSideSize,
                    )
                }


            }
        }
    }
}
