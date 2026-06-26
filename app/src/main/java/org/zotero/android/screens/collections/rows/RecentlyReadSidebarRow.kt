package org.zotero.android.screens.collections.rows

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.zotero.android.uicomponents.Drawables
import org.zotero.android.uicomponents.foundation.debounceCombinedClickable

/**
 * A library-sidebar row (sibling of the fixed "All Items"/"Trash" rows) that
 * opens the Recently Read list. It is UI-only — it is not a real collection, so
 * it doesn't go through CollectionIdentifier / the collection-selection machinery.
 */
internal fun LazyListScope.recentlyReadSidebarRow(
    onTapped: () -> Unit,
) {
    item {
        val levelPadding = 4.dp
        val arrowIconAreaSize = 48.dp
        val mainIconSize = 28.dp
        val paddingBetweenIconAndText = 12.dp
        val paddingBetweenArrowAndIcon = 4.dp
        val leadingPadding = levelPadding + arrowIconAreaSize + paddingBetweenArrowAndIcon
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(48.dp)
                .debounceCombinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = onTapped,
                    onLongClick = {},
                ),
        ) {
            Spacer(modifier = Modifier.width(leadingPadding))
            Icon(
                modifier = Modifier.size(mainIconSize),
                painter = painterResource(id = Drawables.history_24px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(paddingBetweenIconAndText))
            Text(
                modifier = Modifier.weight(1f),
                text = "Recently Read",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
    }
}
