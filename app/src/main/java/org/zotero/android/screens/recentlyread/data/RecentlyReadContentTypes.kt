package org.zotero.android.screens.recentlyread.data

/** Single source of truth for the content types Recently Read can open + their icons. */
object RecentlyReadContentTypes {
    const val PDF = "application/pdf"
    const val EPUB = "application/epub+zip"
    const val HTML = "text/html"

    val READABLE: Set<String> = setOf(PDF, EPUB, HTML)

    fun iconName(contentType: String): String = when (contentType) {
        PDF -> "item_type_pdf"
        EPUB -> "item_type_epub"
        else -> "item_type_document"
    }
}
