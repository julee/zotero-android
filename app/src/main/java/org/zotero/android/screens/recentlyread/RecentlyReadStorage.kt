package org.zotero.android.screens.recentlyread

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.zotero.android.architecture.Defaults
import org.zotero.android.database.objects.RCustomLibraryType
import org.zotero.android.sync.LibraryIdentifier
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local, persistent record of files opened on THIS device, newest first, stored in
 * [Defaults] as JSON. Besides the timestamp (the precise ordering signal) each entry
 * also caches enough display info (title, icon, content type, filename, parent key)
 * to render the row WITHOUT a database lookup. That matters because right after a read
 * the attachment's RItem is briefly rewritten by the page-index sync; the cached info
 * keeps the just-read file on the list instead of letting it flicker out.
 *
 * Cross-device coverage comes from the synced `RPageIndex` objects (see
 * [org.zotero.android.screens.recentlyread.data.ReadRecentlyReadItemsDbRequest]).
 */
@Singleton
class RecentlyReadStorage @Inject constructor(
    private val defaults: Defaults,
    private val gson: Gson,
) {
    data class Entry(
        val libraryId: LibraryIdentifier,
        val key: String,
        val timestamp: Long,
        val title: String?,
        val typeIconName: String?,
        val contentType: String?,
        val filename: String?,
        val parentKey: String?,
    )

    /** Records (or moves to the top) an open, seeding the display info the reader knows. */
    fun register(libraryId: LibraryIdentifier, key: String, filename: String, contentType: String) {
        if (key.isBlank()) {
            return
        }
        val composite = compositeKey(libraryId, key)
        val existing = readStored().firstOrNull { it.composite() == composite }
        val now = System.currentTimeMillis()
        val updated = StoredEntry(
            lib = encodeLibraryId(libraryId),
            key = key,
            ts = now,
            // Keep the richer title/icon/parentKey from a previous resolve if present.
            title = existing?.title,
            icon = existing?.icon,
            contentType = contentType,
            filename = filename,
            parentKey = existing?.parentKey,
        )
        val others = readStored().filterNot { it.composite() == composite }
        write((listOf(updated) + others).take(MAX_ENTRIES))
    }

    /**
     * Upgrades stored entries with fully-resolved display info (real titles/icons from
     * the database) so they survive later sync-churn windows and app restarts. Only
     * touches entries that already exist locally; one read + one write.
     */
    fun mergeDisplayInfo(resolved: List<Entry>) {
        if (resolved.isEmpty()) {
            return
        }
        val byComposite = resolved.associateBy { compositeKey(it.libraryId, it.key) }
        var changed = false
        val stored = readStored().map { entry ->
            val update = byComposite[entry.composite()] ?: return@map entry
            val next = entry.copy(
                title = update.title ?: entry.title,
                icon = update.typeIconName ?: entry.icon,
                contentType = update.contentType ?: entry.contentType,
                filename = update.filename ?: entry.filename,
                parentKey = update.parentKey ?: entry.parentKey,
            )
            if (next != entry) {
                changed = true
            }
            next
        }
        if (changed) {
            write(stored)
        }
    }

    fun entries(): List<Entry> = readStored().mapNotNull { it.toEntry() }

    private fun readStored(): List<StoredEntry> {
        val raw = defaults.getRecentlyReadEntries()
        if (raw.isBlank()) {
            return emptyList()
        }
        return try {
            val type = object : TypeToken<List<StoredEntry>>() {}.type
            gson.fromJson(raw, type) ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "RecentlyReadStorage: can't parse entries; resetting")
            emptyList()
        }
    }

    private fun write(entries: List<StoredEntry>) {
        defaults.setRecentlyReadEntries(gson.toJson(entries))
    }

    private data class StoredEntry(
        val lib: String,
        val key: String,
        val ts: Long,
        val title: String? = null,
        val icon: String? = null,
        val contentType: String? = null,
        val filename: String? = null,
        val parentKey: String? = null,
    ) {
        fun composite(): String = "$lib~$key"

        fun toEntry(): Entry? {
            val libraryId = decodeLibraryId(lib) ?: return null
            if (key.isBlank()) {
                return null
            }
            return Entry(
                libraryId = libraryId,
                key = key,
                timestamp = ts,
                title = title,
                typeIconName = icon,
                contentType = contentType,
                filename = filename,
                parentKey = parentKey,
            )
        }
    }

    companion object {
        private const val MAX_ENTRIES = 50

        fun compositeKey(libraryId: LibraryIdentifier, key: String): String =
            "${encodeLibraryId(libraryId)}~$key"

        private fun encodeLibraryId(libraryId: LibraryIdentifier): String = when (libraryId) {
            is LibraryIdentifier.custom -> "u"
            is LibraryIdentifier.group -> "g${libraryId.groupId}"
        }

        private fun decodeLibraryId(raw: String): LibraryIdentifier? = when {
            raw == "u" -> LibraryIdentifier.custom(RCustomLibraryType.myLibrary)
            raw.startsWith("g") -> raw.substring(1).toIntOrNull()?.let { LibraryIdentifier.group(it) }
            else -> null
        }
    }
}
