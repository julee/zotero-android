package org.zotero.android.screens.reader.web

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.webkit.WebView

class ReaderCustomWebView(context: Context, attrs: AttributeSet? = null) : WebView(context, attrs) {

    // Suppress the text-selection floating ActionMode entirely. The reader shows its
    // own selection popup (onSetSelectionPopup); the empty floating ActionMode window
    // steals input focus in the full-bleed/immersive reader and never returns it,
    // leaving the screen unresponsive ("frozen") until the window is re-focused.
    public override fun startActionMode(callback: ActionMode.Callback): ActionMode? {
        return null
    }

    public override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        return null
    }
}