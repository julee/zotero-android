package org.zotero.android.screens.recentlyread

import android.net.Uri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.zotero.android.architecture.BaseViewModel2
import org.zotero.android.architecture.Defaults
import org.zotero.android.architecture.ViewEffect
import org.zotero.android.architecture.ViewState
import org.zotero.android.architecture.ifFailure
import org.zotero.android.architecture.navigation.NavigationParamsMarshaller
import org.zotero.android.attachmentdownloader.AttachmentDownloader
import org.zotero.android.attachmentdownloader.AttachmentDownloaderEventStream
import org.zotero.android.database.DbWrapperMain
import org.zotero.android.database.objects.Attachment
import org.zotero.android.files.FileStore
import org.zotero.android.screens.recentlyread.data.ReadRecentlyReadItemsDbRequest
import org.zotero.android.screens.recentlyread.data.ReadRecentlyReadOpenDataDbRequest
import org.zotero.android.screens.recentlyread.data.RecentlyReadListItem
import org.zotero.android.screens.recentlyread.data.RecentlyReadOpenData
import org.zotero.android.screens.reader.data.ReaderArgs
import org.zotero.android.sync.UrlDetector
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
internal class RecentlyReadViewModel @Inject constructor(
    private val dbWrapperMain: DbWrapperMain,
    private val fileStore: FileStore,
    private val defaults: Defaults,
    private val urlDetector: UrlDetector,
    private val recentlyReadStorage: RecentlyReadStorage,
    private val attachmentDownloader: AttachmentDownloader,
    private val attachmentDownloaderEventStream: AttachmentDownloaderEventStream,
    private val navigationParamsMarshaller: NavigationParamsMarshaller,
) : BaseViewModel2<RecentlyReadViewState, RecentlyReadViewEffect>(RecentlyReadViewState()) {

    private var pendingOpen: RecentlyReadOpenData? = null
    // Guards against a slower, earlier load applying its result after a newer one
    // (the screen reloads on every entry, so loads can overlap).
    private var loadGeneration = 0

    fun init() = initOnce {
        setupDownloadObserving()
    }

    fun load() {
        val generation = ++loadGeneration
        viewModelScope.launch {
            val localEntries = recentlyReadStorage.entries()
            val items = perform(
                dbWrapper = dbWrapperMain,
                invalidateRealm = true,
                request = ReadRecentlyReadItemsDbRequest(
                    localEntries = localEntries,
                    fileStorage = fileStore,
                    defaults = defaults,
                    urlDetector = urlDetector,
                )
            ).ifFailure {
                Timber.e(it, "RecentlyReadViewModel: can't load recently read items")
                if (generation == loadGeneration) {
                    updateState { copy(isLoading = false) }
                }
                return@launch
            }
            // A newer load already superseded this one — drop the stale result.
            if (generation != loadGeneration) {
                return@launch
            }
            // Persist freshly-resolved titles/icons so the rows survive future churn.
            recentlyReadStorage.mergeDisplayInfo(
                items.map {
                    RecentlyReadStorage.Entry(
                        libraryId = it.libraryId,
                        key = it.key,
                        timestamp = 0L,
                        title = it.title,
                        typeIconName = it.typeIconName,
                        contentType = it.contentType,
                        filename = it.filename,
                        parentKey = it.parentKey,
                    )
                }
            )
            updateState { copy(isLoading = false, items = items) }
        }
    }

    fun onItemTapped(item: RecentlyReadListItem) {
        if (viewState.downloadingKey != null) {
            return
        }
        // Optimistically float the tapped file to the top with a fresh timestamp, so
        // it's already in the right place when we come back from the reader (the screen
        // only does a full reload on a fresh entry, which avoids the post-read sync
        // churn briefly hiding the just-opened file).
        val movedToTop = item.copy(lastOpened = System.currentTimeMillis())
        updateState {
            copy(
                items = listOf(movedToTop) + items.filterNot { it.uniqueId == item.uniqueId },
                downloadingKey = item.key,
            )
        }
        viewModelScope.launch {
            val openData = perform(
                dbWrapper = dbWrapperMain,
                invalidateRealm = false,
                request = ReadRecentlyReadOpenDataDbRequest(
                    libraryId = item.libraryId,
                    key = item.key,
                    fileStorage = fileStore,
                    defaults = defaults,
                    urlDetector = urlDetector,
                )
            ).ifFailure {
                Timber.e(it, "RecentlyReadViewModel: can't resolve attachment ${item.key}")
                updateState { copy(downloadingKey = null) }
                return@launch
            }
            pendingOpen = openData
            attachmentDownloader.downloadIfNeeded(
                attachment = openData.attachment,
                parentKey = openData.parentKey,
            )
        }
    }

    fun onBack() {
        triggerEffect(RecentlyReadViewEffect.NavigateBack)
    }

    private fun setupDownloadObserving() {
        attachmentDownloaderEventStream.flow()
            .onEach { update -> processDownloadUpdate(update) }
            .launchIn(viewModelScope)
    }

    private fun processDownloadUpdate(update: AttachmentDownloader.Update) {
        val pending = pendingOpen ?: return
        if (update.key != pending.attachment.key || update.libraryId != pending.attachment.libraryId) {
            return
        }
        when (update.kind) {
            AttachmentDownloader.Update.Kind.ready -> {
                pendingOpen = null
                updateState { copy(downloadingKey = null) }
                openReader(pending)
            }

            is AttachmentDownloader.Update.Kind.failed -> {
                pendingOpen = null
                updateState { copy(downloadingKey = null) }
            }

            AttachmentDownloader.Update.Kind.cancelled -> {
                pendingOpen = null
                updateState { copy(downloadingKey = null) }
            }

            is AttachmentDownloader.Update.Kind.progress -> {
                // keep showing progress
            }
        }
    }

    private fun openReader(openData: RecentlyReadOpenData) {
        val fileKind = openData.attachment.type as? Attachment.Kind.file ?: return
        val file = fileStore.attachmentFile(
            libraryId = openData.library.identifier,
            key = openData.attachment.key,
            filename = fileKind.filename,
        )
        val readerArgs = ReaderArgs(
            key = openData.attachment.key,
            parentKey = openData.parentKey,
            library = openData.library,
            uri = Uri.fromFile(file),
        )
        val params = navigationParamsMarshaller.encodeObjectToBase64(readerArgs)
        triggerEffect(RecentlyReadViewEffect.NavigateToReader(params))
    }
}

internal data class RecentlyReadViewState(
    val isLoading: Boolean = true,
    val items: List<RecentlyReadListItem> = emptyList(),
    val downloadingKey: String? = null,
) : ViewState

internal sealed class RecentlyReadViewEffect : ViewEffect {
    object NavigateBack : RecentlyReadViewEffect()
    data class NavigateToReader(val params: String) : RecentlyReadViewEffect()
}
