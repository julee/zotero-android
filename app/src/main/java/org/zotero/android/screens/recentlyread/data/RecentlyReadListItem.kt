package org.zotero.android.screens.recentlyread.data

import org.zotero.android.sync.LibraryIdentifier

/** A single row in the Recently Read list. */
data class RecentlyReadListItem(
    val key: String,
    val parentKey: String?,
    val libraryId: LibraryIdentifier,
    val title: String,
    val typeIconName: String,
    val contentType: String,
    val filename: String,
    /** Last opened on this device, millis; null if only known from cross-device sync. */
    val lastOpened: Long?,
) {
    val uniqueId: String get() = "${libraryId}_$key"
}
