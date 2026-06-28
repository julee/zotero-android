package org.zotero.android.screens.reader

import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.zotero.android.screens.reader.data.ReaderFileType
import org.zotero.android.screens.reader.toolbar.ReaderAnnotationCreationToolbar
import org.zotero.android.screens.reader.web.actionmenu.ReaderActionMenuPopup
import org.zotero.android.uicomponents.Drawables
import org.zotero.android.uicomponents.theme.CustomTheme


@Composable
internal fun ReaderBox(
    viewModel: ReaderViewModel,
    viewState: ReaderViewState,
    isOverlayMode: Boolean,
) {
    val density = LocalDensity.current
    val positionalThreshold = { distance: Float -> distance * 0.5f }
    val velocityThreshold = { with(density) { 1000.dp.toPx() } }
    val animationSpec = tween<Float>()

    var shouldShowSnapTargetAreas: Boolean by remember { mutableStateOf(false) }
    val confirmValueChange = { newValue: DragAnchors ->
        shouldShowSnapTargetAreas = false
        true
    }
    val decayAnimationSpec = rememberSplineBasedDecay<Float>()
    val anchoredDraggableState: AnchoredDraggableState<DragAnchors> = rememberSaveable(
        saver = AnchoredDraggableState.Saver(
            snapAnimationSpec = animationSpec,
            positionalThreshold = positionalThreshold,
            velocityThreshold = velocityThreshold,
            confirmValueChange = confirmValueChange,
            decayAnimationSpec = decayAnimationSpec
        )
    ) {
        AnchoredDraggableState(
            initialValue = DragAnchors.Start,
            positionalThreshold = positionalThreshold,
            velocityThreshold = velocityThreshold,
            snapAnimationSpec = animationSpec,
            decayAnimationSpec = decayAnimationSpec,
            confirmValueChange = confirmValueChange,
        )
    }
    val extraRightPadding = if (isOverlayMode && viewState.showSideBar) {
        330.dp
    } else {
        0.dp
    }

    val rightTargetAreaXOffset = with(density) { 96.dp.toPx() + extraRightPadding.toPx() }

    Column(
        Modifier
            .fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { layoutSize ->
                    val dragEndPoint = layoutSize.width - rightTargetAreaXOffset
                    anchoredDraggableState.updateAnchors(
                        DraggableAnchors {
                            DragAnchors.entries
                                .forEach { anchor ->
                                    anchor at dragEndPoint * anchor.fraction
                                }
                        }
                    )
                }
        ) {
            val selectedTextParamsRects = viewState.selectedTextParamsRects
            if (selectedTextParamsRects != null) {
                ReaderActionMenuPopup(
                    selectedTextParamsRects = selectedTextParamsRects,
                    viewModel = viewModel,
                    viewState = viewState,
                )
            }

            ReaderWebView(viewModel = viewModel)
            if (viewState.showCreationToolbar) {
                ReaderAnnotationCreationToolbar(
                    viewState = viewState,
                    viewModel = viewModel,
                    isOverlayMode = isOverlayMode,
                    state = anchoredDraggableState,
                    onShowSnapTargetAreas = { shouldShowSnapTargetAreas = true },
                    shouldShowSnapTargetAreas = shouldShowSnapTargetAreas
                )
            }
            // While an annotation tool is active, tap-to-turn is suppressed (taps draw),
            // so offer semi-transparent page-turn buttons at the screen edges. Only shown
            // for PDFs with a tool actually selected (not just the toolbar being open).
            if (viewState.fileType == ReaderFileType.PDF && viewState.activeTool != null) {
                // Sit just BELOW the annotation toolbar, hard against the edges, so the
                // buttons neither overlap the toolbar nor float over the middle of the
                // page. With a tool active (so the color circle shows) the toolbar's
                // content bottom sits ~480dp under the status bar inset (= 96dp top inset
                // + content); place the buttons ~12dp below that. statusBarsPadding()
                // matches the toolbar's own top inset so they track it as bars show/hide.
                val pageTurnTopOffset = 492.dp
                PageTurnButton(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 8.dp, top = pageTurnTopOffset),
                    rotationDegrees = 180f,
                    onClick = viewModel::onTurnToPreviousPage,
                )
                PageTurnButton(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(end = 8.dp, top = pageTurnTopOffset),
                    rotationDegrees = 0f,
                    onClick = viewModel::onTurnToNextPage,
                )
            }
        }

        if (viewState.fileType == ReaderFileType.EPUB) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(CustomTheme.colors.surface)
            ) {
                // EPUB: When bars are hidden, show page progress in the empty space
                val pageProgress = viewState.pageProgress
                if (!viewState.isTopBarVisible
                    && pageProgress != null
                ) {
                    Text(
                        modifier = Modifier.align(Alignment.Center),
                        text = pageProgress,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PageTurnButton(
    modifier: Modifier,
    rotationDegrees: Float,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = Drawables.chevron_right_24px),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier
                .size(30.dp)
                .rotate(rotationDegrees),
        )
    }
}

enum class DragAnchors(val fraction: Float) {
    Start(0f),
    End(1f),
}

