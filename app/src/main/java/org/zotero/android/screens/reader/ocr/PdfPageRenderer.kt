package org.zotero.android.screens.reader.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import timber.log.Timber
import java.io.File

/**
 * Renders a single PDF page to a white-backed [Bitmap] using the platform
 * [PdfRenderer] (no third-party PDF engine needed). The bitmap is sized so that
 * its long edge is roughly [targetLongEdgePx] for reliable OCR.
 */
object PdfPageRenderer {

    fun render(file: File, pageIndex: Int, targetLongEdgePx: Int = 2200): Bitmap? {
        if (!file.exists()) {
            Timber.w("PdfPageRenderer: file does not exist: ${file.absolutePath}")
            return null
        }
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                Timber.w("PdfPageRenderer: page $pageIndex out of range (${renderer.pageCount})")
                return null
            }
            page = renderer.openPage(pageIndex)
            val pw = page.width   // points
            val ph = page.height
            if (pw <= 0 || ph <= 0) return null

            // Scale so the long edge is ~targetLongEdgePx; never upsample beyond 4x,
            // and don't force upsampling of already-large pages (lower bound 1x).
            val scale = (targetLongEdgePx.toFloat() / maxOf(pw, ph)).coerceIn(1f, 4f)
            val bw = (pw * scale).toInt().coerceAtLeast(1)
            val bh = (ph * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            // PDF pages render with a transparent background; OCR needs white.
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } catch (e: Exception) {
            Timber.e(e, "PdfPageRenderer: failed to render page $pageIndex")
            return null
        } finally {
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }
}
