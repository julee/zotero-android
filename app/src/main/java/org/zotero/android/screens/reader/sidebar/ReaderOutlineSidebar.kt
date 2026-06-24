package org.zotero.android.screens.reader.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.google.gson.JsonElement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.zotero.android.screens.reader.ReaderSidebarSearchBar
import org.zotero.android.screens.reader.ReaderViewModel
import org.zotero.android.screens.reader.ReaderViewState
import org.zotero.android.screens.reader.sidebar.data.ReaderWrapperOutline
import org.zotero.android.uicomponents.Drawables
import org.zotero.android.uicomponents.Strings
import org.zotero.android.uicomponents.foundation.debounceCombinedClickable
import org.zotero.android.uicomponents.foundation.safeStringResource
import org.zotero.android.uicomponents.icon.IconWithPadding
import org.zotero.android.uicomponents.misc.NewDivider
import org.zotero.android.uicomponents.theme.CustomPalette
import org.zotero.android.uicomponents.theme.CustomTheme

private val levelPaddingConst = 8.dp

@Composable
internal fun ReaderOutlineSidebar(
    viewModel: ReaderViewModel,
    viewState: ReaderViewState,
    outlineLazyListState: LazyListState,
) {
    if (viewState.isOutlineEmpty) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Text(
                modifier = Modifier.align(Alignment.Center),
                text = safeStringResource(id = Strings.pdf_sidebar_no_outline),
                color = CustomPalette.SystemGray,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }

    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                )

        ) {
            Spacer(modifier = Modifier.height(16.dp))
            ReaderSidebarSearchBar(
                searchValue = viewState.outlineSearchTerm,
                onSearch = viewModel::onOutlineSearch,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ReaderOutlineTable(
                viewModel = viewModel,
                viewState = viewState,
                outlineLazyListState = outlineLazyListState,
            )
        }
    }
}

@Composable
internal fun ReaderOutlineTable(
    viewModel: ReaderViewModel,
    viewState: ReaderViewState,
    outlineLazyListState: LazyListState,
) {
    val roundCornerShape = RoundedCornerShape(size = 10.dp)
    // Flatten the visible (non-collapsed) outline tree so the list can track
    // index positions for current-page scrolling.
    val visibleRows = remember(viewState.outlineSnapshot, viewState.outlineExpandedNodes) {
        val rows = mutableListOf<OutlineRow>()
        flattenVisibleOutline(
            outlineItems = viewState.outlineSnapshot,
            levelPadding = 8.dp,
            isCollapsed = { viewState.isOutlineSectionCollapsed(it.id) },
            out = rows,
        )
        rows
    }
    // The active row for the current page: the deepest item whose page is at or
    // before the current page.
    val activeIndex = remember(visibleRows, viewState.currentPageIndex) {
        val page = viewState.currentPageIndex ?: return@remember -1
        if (visibleRows.isEmpty()) return@remember -1
        var best = -1
        var bestPage = -1
        visibleRows.forEachIndexed { index, row ->
            val rowPage = row.item.outlinePageIndex()
            if (rowPage != null && rowPage <= page && rowPage > bestPage) {
                best = index
                bestPage = rowPage
            }
        }
        // The current page is before the first outline entry — scroll to the top.
        if (best == -1) 0 else best
    }
    // Scroll the active row into view whenever it changes — when the page changes
    // while the outline is visible, and once when the active row is first known.
    // The list state is hoisted, so a manual scroll by the user is preserved
    // across sidebar close/reopen.
    LaunchedEffect(activeIndex, visibleRows) {
        if (activeIndex >= 0 && activeIndex < visibleRows.size) {
            outlineLazyListState.scrollToItem(activeIndex)
        }
    }
    LazyColumn(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = roundCornerShape)
            .clip(roundCornerShape),
        state = outlineLazyListState,
    ) {
        items(visibleRows, key = { it.item.id }) { row ->
            OutlineItem(
                levelPadding = row.levelPadding,
                outline = row.item,
                hasChildren = row.item.children.isNotEmpty(),
                isCollapsed = viewState.isOutlineSectionCollapsed(row.item.id),
                onItemTapped = { viewModel.onOutlineItemTapped(row.item) },
                onItemChevronTapped = { viewModel.onOutlineItemChevronTapped(row.item) }
            )
        }
    }

}

private data class OutlineRow(
    val item: ReaderWrapperOutline,
    val levelPadding: Dp,
)

private fun flattenVisibleOutline(
    outlineItems: List<ReaderWrapperOutline>,
    levelPadding: Dp,
    isCollapsed: (item: ReaderWrapperOutline) -> Boolean,
    out: MutableList<OutlineRow>,
) {
    for (item in outlineItems) {
        out.add(OutlineRow(item = item, levelPadding = levelPadding))
        if (item.children.isNotEmpty() && !isCollapsed(item)) {
            flattenVisibleOutline(
                outlineItems = item.children,
                levelPadding = levelPadding + levelPaddingConst,
                isCollapsed = isCollapsed,
                out = out,
            )
        }
    }
}

// 0-based page index this outline item points at, or null if it has no resolved
// position (e.g. a URL-only or unresolved destination).
private fun ReaderWrapperOutline.outlinePageIndex(): Int? {
    val position = (location["position"] as? JsonElement)
        ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val pageIndex = position["pageIndex"]?.takeIf { it.isJsonPrimitive } ?: return null
    return try {
        pageIndex.asInt
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun OutlineItem(
    levelPadding: Dp,
    outline: ReaderWrapperOutline,
    hasChildren: Boolean,
    isCollapsed: Boolean,
    onItemTapped: () -> Unit,
    onItemChevronTapped: () -> Unit,
) {
    val rowModifier = Modifier.heightIn(min = 44.dp)
    val arrowIconAreaSize = 32.dp
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = rowModifier
                .debounceCombinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = onItemTapped,
                )
        ) {
            Spacer(modifier = Modifier.width(levelPadding))
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                text = outline.title,
                style = CustomTheme.typography.newBody,
                color = if (outline.isActive) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    CustomPalette.SystemGray
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (hasChildren) {
                IconWithPadding(
                    drawableRes = if (isCollapsed) {
                        Drawables.chevron_right_24px
                    } else {
                        Drawables.expand_more_24px
                    },
                    onClick = { onItemChevronTapped() },
                    areaSize = arrowIconAreaSize,
                    tintColor = MaterialTheme.colorScheme.primary,
                    shouldShowRipple = false
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
        NewDivider(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = levelPadding)
        )
    }
}
