package org.zotero.android.screens.recentlyread

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.zotero.android.androidx.content.getDrawableByItemType
import org.zotero.android.screens.recentlyread.data.RecentlyReadListItem
import org.zotero.android.uicomponents.CustomScaffoldM3
import org.zotero.android.uicomponents.Drawables
import org.zotero.android.uicomponents.foundation.debounceCombinedClickable
import org.zotero.android.uicomponents.themem3.AppThemeM3

@Composable
internal fun RecentlyReadScreen(
    onBack: () -> Unit,
    onOpenReader: (String) -> Unit,
) {
    AppThemeM3 {
        // Activity-scoped so the screen always observes a single, stable instance —
        // returning from the reader doesn't hand us a different ViewModel/composition.
        val activity = LocalActivity.current as ComponentActivity
        val viewModel: RecentlyReadViewModel = hiltViewModel(activity)
        val viewState by viewModel.viewStates.observeAsState(RecentlyReadViewState())
        val viewEffect by viewModel.viewEffects.observeAsState()

        LaunchedEffect(key1 = Unit) {
            viewModel.init()
        }
        // Reload whenever the screen is resumed (first show + each return from the
        // reader) so the just-read file re-sorts to the top.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.load()
            }
        }

        LaunchedEffect(key1 = viewEffect) {
            when (val consumedEffect = viewEffect?.consume()) {
                null -> Unit
                RecentlyReadViewEffect.NavigateBack -> onBack()
                is RecentlyReadViewEffect.NavigateToReader -> onOpenReader(consumedEffect.params)
            }
        }

        CustomScaffoldM3(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    title = {
                        Text(
                            text = "Recently Read",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = viewModel::onBack) {
                            Icon(
                                painter = painterResource(Drawables.arrow_back_24dp),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!viewState.isLoading && viewState.items.isEmpty()) {
                    Text(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        text = "No recently read files yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = viewState.items,
                            key = { it.uniqueId },
                        ) { item ->
                            RecentlyReadRow(
                                item = item,
                                isDownloading = viewState.downloadingKey == item.key,
                                onTapped = { viewModel.onItemTapped(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentlyReadRow(
    item: RecentlyReadListItem,
    isDownloading: Boolean,
    onTapped: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .debounceCombinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onTapped,
                onLongClick = {},
            )
            .padding(horizontal = 16.dp),
    ) {
        val iconRes = LocalContext.current.getDrawableByItemType(item.typeIconName)
        if (iconRes != 0) {
            // Match the All Items list: full-color icon (no tint), so a PDF/journal
            // article keeps its real colors instead of being recolored solid.
            Image(
                modifier = Modifier.size(28.dp),
                painter = painterResource(id = iconRes),
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val subtitle = item.lastOpened?.let {
                DateUtils.getRelativeTimeSpanString(
                    it,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isDownloading) {
            Spacer(modifier = Modifier.width(12.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
