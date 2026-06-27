package org.zotero.android.screens.reader.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Orchestrates OCR for scanned PDF pages: renders a page, recognizes text on it,
 * and maps the recognized geometry from bitmap pixels into PDF user space so the
 * reader can build a selectable text layer.
 *
 * Results are cached per page for the lifetime of the open document. Rendering +
 * recognition are serialized (one page at a time) to bound memory and because the
 * platform PdfRenderer is not safe for concurrent use.
 */
class OcrManager(
    private val provider: OcrProvider = MLKitOcrProvider(),
) {
    private val cache = HashMap<Int, List<OcrChar>>()
    private val mutex = Mutex()

    /**
     * @param viewBox the page's pdf.js viewBox: [x0, y0, x1, y1] in PDF user space.
     * @return recognized characters in PDF user space, in reading order, or empty.
     */
    suspend fun ocrPage(file: File, pageIndex: Int, viewBox: List<Double>): List<OcrChar> {
        cache[pageIndex]?.let { return it }
        return mutex.withLock {
            cache[pageIndex]?.let { return@withLock it }
            val result = try {
                runOcr(file, pageIndex, viewBox)
            } catch (e: Exception) {
                Timber.e(e, "OcrManager: OCR failed for page $pageIndex")
                emptyList()
            }
            cache[pageIndex] = result
            Timber.i("OcrManager: page $pageIndex produced ${result.size} OCR chars")
            result
        }
    }

    private suspend fun runOcr(file: File, pageIndex: Int, viewBox: List<Double>): List<OcrChar> {
        val vx0 = viewBox.getOrElse(0) { 0.0 }
        val vy0 = viewBox.getOrElse(1) { 0.0 }
        val vx1 = viewBox.getOrElse(2) { 0.0 }
        val vy1 = viewBox.getOrElse(3) { 0.0 }
        val pdfW = vx1 - vx0
        val pdfH = vy1 - vy0
        if (pdfW <= 0 || pdfH <= 0) {
            Timber.w("OcrManager: invalid viewBox $viewBox")
            return emptyList()
        }

        val bitmap = withContext(Dispatchers.IO) {
            PdfPageRenderer.render(file, pageIndex)
        } ?: return emptyList()

        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        val blocks = try {
            provider.recognize(bitmap)
        } finally {
            bitmap.recycle()
        }
        if (blocks.isEmpty() || bw <= 0 || bh <= 0) return emptyList()

        val sx = pdfW / bw
        val sy = pdfH / bh
        val out = ArrayList<OcrChar>()
        // Emit block-by-block to keep columns separate; line-by-line within a block.
        for (block in blocks) {
            for (line in block.lines) {
                // Give every char in the line the same vertical extent (the line box),
                // which keeps the reader's geometric line-splitting stable, and map the
                // pixel box (origin top-left, y down) into PDF space (origin bottom-left).
                val lineTopPdf = (vy1 - line.top * sy).toFloat()      // larger y
                val lineBotPdf = (vy1 - line.bottom * sy).toFloat()   // smaller y
                for (ch in line.chars) {
                    val x1 = (vx0 + ch.left * sx).toFloat()
                    val x2 = (vx0 + ch.right * sx).toFloat()
                    val left = minOf(x1, x2)
                    val right = maxOf(x1, x2)
                    if (right - left <= 0f) continue
                    out.add(OcrChar(c = ch.text, rect = listOf(left, lineBotPdf, right, lineTopPdf)))
                }
            }
        }
        return out
    }
}
