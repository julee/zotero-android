package org.zotero.android.architecture.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import org.zotero.android.screens.recentlyread.RecentlyReadScreen

const val RECENTLY_READ_SCREEN = "recentlyReadScreen"

fun NavGraphBuilder.recentlyReadScreen(
    onBack: () -> Unit,
    onOpenReader: (String) -> Unit,
) {
    composable(route = RECENTLY_READ_SCREEN) {
        RecentlyReadScreen(
            onBack = onBack,
            onOpenReader = onOpenReader,
        )
    }
}

fun ZoteroNavigation.toRecentlyRead() {
    navController.navigate(RECENTLY_READ_SCREEN)
}
