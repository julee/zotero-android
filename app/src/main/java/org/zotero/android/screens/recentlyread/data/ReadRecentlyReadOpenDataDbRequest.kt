package org.zotero.android.screens.recentlyread.data

import io.realm.Realm
import io.realm.kotlin.where
import org.zotero.android.architecture.Defaults
import org.zotero.android.database.DbError
import org.zotero.android.database.DbResponseRequest
import org.zotero.android.database.objects.Attachment
import org.zotero.android.database.objects.RItem
import org.zotero.android.database.requests.ReadLibraryDbRequest
import org.zotero.android.database.requests.key
import org.zotero.android.files.FileStore
import org.zotero.android.sync.AttachmentCreator
import org.zotero.android.sync.Library
import org.zotero.android.sync.LibraryIdentifier
import org.zotero.android.sync.UrlDetector

/** Resolves everything needed to open an attachment in the reader, in one Realm pass. */
class ReadRecentlyReadOpenDataDbRequest(
    private val libraryId: LibraryIdentifier,
    private val key: String,
    private val fileStorage: FileStore,
    private val defaults: Defaults,
    private val urlDetector: UrlDetector,
) : DbResponseRequest<RecentlyReadOpenData> {

    override val needsWrite: Boolean = false

    override fun process(database: Realm): RecentlyReadOpenData {
        val item = database.where<RItem>().key(key, libraryId).findFirst()
            ?: throw DbError.objectNotFound
        val attachment = AttachmentCreator.attachment(
            item = item,
            fileStorage = fileStorage,
            isForceRemote = false,
            urlDetector = urlDetector,
            defaults = defaults,
        ) ?: throw DbError.objectNotFound
        val library = ReadLibraryDbRequest(libraryId = libraryId).process(database)
        return RecentlyReadOpenData(
            attachment = attachment,
            library = library,
            parentKey = item.parent?.key,
        )
    }
}

data class RecentlyReadOpenData(
    val attachment: Attachment,
    val library: Library,
    val parentKey: String?,
)
