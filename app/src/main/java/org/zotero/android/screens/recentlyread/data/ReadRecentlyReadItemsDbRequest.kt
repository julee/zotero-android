package org.zotero.android.screens.recentlyread.data

import io.realm.Realm
import io.realm.kotlin.where
import org.zotero.android.architecture.Defaults
import org.zotero.android.database.DbResponseRequest
import org.zotero.android.database.objects.Attachment
import org.zotero.android.database.objects.RItem
import org.zotero.android.database.objects.RPageIndex
import org.zotero.android.database.requests.key
import org.zotero.android.files.FileStore
import org.zotero.android.screens.recentlyread.RecentlyReadStorage
import org.zotero.android.sync.AttachmentCreator
import org.zotero.android.sync.LibraryIdentifier
import org.zotero.android.sync.UrlDetector

/**
 * Builds the Recently Read list by unioning two sources, keyed by (libraryId, key):
 *  - local opens recorded by [RecentlyReadStorage] (precise timestamp + cached display
 *    info, so a just-read file renders even while the page-index sync briefly rewrites
 *    its RItem), and
 *  - synced `RPageIndex` objects (one per attachment ever opened on any device,
 *    carrying the server `version` used as the cross-device recency proxy).
 *
 * Ordering: files opened on this device (have a local timestamp) come first, newest
 * first; the rest follow by `version`. The candidates are ranked and capped to
 * [MAX_ITEMS] BEFORE the expensive per-item resolution (RItem lookup + AttachmentCreator),
 * so a large read history doesn't turn each load into hundreds of DB hits.
 */
class ReadRecentlyReadItemsDbRequest(
    private val localEntries: List<RecentlyReadStorage.Entry>,
    private val fileStorage: FileStore,
    private val defaults: Defaults,
    private val urlDetector: UrlDetector,
) : DbResponseRequest<List<RecentlyReadListItem>> {

    override val needsWrite: Boolean = false

    override fun process(database: Realm): List<RecentlyReadListItem> {
        val candidates = LinkedHashMap<String, Candidate>()
        // Local opens first — reliable, immediate, and self-describing.
        for (entry in localEntries) {
            candidates[RecentlyReadStorage.compositeKey(entry.libraryId, entry.key)] = Candidate(
                libraryId = entry.libraryId,
                key = entry.key,
                localTimestamp = entry.timestamp,
                version = 0,
                stored = entry,
            )
        }
        // Synced page indexes — adds cross-device entries and the recency version.
        for (pageIndex in database.where<RPageIndex>().findAll()) {
            val libraryId = pageIndex.libraryId ?: continue
            val key = pageIndex.key
            if (key.isEmpty()) {
                continue
            }
            val compositeKey = RecentlyReadStorage.compositeKey(libraryId, key)
            val existing = candidates[compositeKey]
            if (existing == null) {
                candidates[compositeKey] = Candidate(
                    libraryId = libraryId,
                    key = key,
                    localTimestamp = null,
                    version = pageIndex.version,
                    stored = null,
                )
            } else if (pageIndex.version > existing.version) {
                candidates[compositeKey] = existing.copy(version = pageIndex.version)
            }
        }

        return candidates.values
            .sortedWith(
                compareByDescending<Candidate> { it.localTimestamp != null }
                    .thenByDescending { it.localTimestamp ?: 0L }
                    .thenByDescending { it.version }
            )
            .take(MAX_ITEMS)
            .mapNotNull { candidate -> resolve(database, candidate) }
    }

    private fun resolve(database: Realm, candidate: Candidate): RecentlyReadListItem? {
        resolveFromDb(database, candidate)?.let { return it }
        // DB resolution failed (e.g. a just-read file's RItem is mid-sync). Render a
        // local entry from its cached display info so it doesn't drop off the list.
        val stored = candidate.stored ?: return null
        val contentType = stored.contentType ?: return null
        if (contentType !in READABLE_CONTENT_TYPES) {
            return null
        }
        return RecentlyReadListItem(
            key = candidate.key,
            parentKey = stored.parentKey,
            libraryId = candidate.libraryId,
            title = stored.title?.takeIf { it.isNotBlank() }
                ?: stored.filename?.takeIf { it.isNotBlank() }
                ?: candidate.key,
            typeIconName = stored.typeIconName?.takeIf { it.isNotBlank() }
                ?: iconNameForContentType(contentType),
            contentType = contentType,
            filename = stored.filename ?: "",
            lastOpened = candidate.localTimestamp,
        )
    }

    private fun resolveFromDb(database: Realm, candidate: Candidate): RecentlyReadListItem? {
        val rItem = database.where<RItem>().key(candidate.key, candidate.libraryId).findFirst()
            ?: return null
        if (rItem.deleted || rItem.trash) {
            return null
        }
        val attachment = AttachmentCreator.attachment(
            item = rItem,
            fileStorage = fileStorage,
            isForceRemote = false,
            urlDetector = urlDetector,
            defaults = defaults,
        ) ?: return null
        val fileKind = attachment.type as? Attachment.Kind.file ?: return null
        if (fileKind.contentType !in READABLE_CONTENT_TYPES) {
            return null
        }
        val parent = rItem.parent
        if (parent != null && (parent.deleted || parent.trash)) {
            return null
        }
        val title = parent?.displayTitle?.takeIf { it.isNotBlank() } ?: attachment.title
        val typeIconName = parent?.allItemsDbRow?.typeIconName?.takeIf { it.isNotBlank() }
            ?: iconNameForContentType(fileKind.contentType)
        return RecentlyReadListItem(
            key = candidate.key,
            parentKey = parent?.key,
            libraryId = candidate.libraryId,
            title = title,
            typeIconName = typeIconName,
            contentType = fileKind.contentType,
            filename = fileKind.filename,
            lastOpened = candidate.localTimestamp,
        )
    }

    private fun iconNameForContentType(contentType: String): String =
        RecentlyReadContentTypes.iconName(contentType)

    private data class Candidate(
        val libraryId: LibraryIdentifier,
        val key: String,
        val localTimestamp: Long?,
        val version: Int,
        val stored: RecentlyReadStorage.Entry?,
    )

    companion object {
        private const val MAX_ITEMS = 60
        private val READABLE_CONTENT_TYPES = RecentlyReadContentTypes.READABLE
    }
}
