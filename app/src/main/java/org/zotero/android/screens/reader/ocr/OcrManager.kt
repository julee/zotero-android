package org.zotero.android.screens.reader.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Orchestrates OCR for scanned PDF pages: renders a page, recognizes text on it,
 * and maps the recognized geometry from bitmap pixels into PDF user space so the
 * reader can build a selectable text layer.
 *
 * Successful results are cached per page for the lifetime of the open document.
 * Transient failures (render/recognition errors) are NOT cached, so a later view
 * of the page retries. Rendering + recognition are serialized (one page at a time)
 * to bound memory and because the platform PdfRenderer is not safe for concurrent use.
 */
class OcrManager(
    private val provider: OcrProvider = MLKitOcrProvider(),
) : Closeable {
    // Concurrent map: read on the fast path outside the mutex, written under it.
    private val cache = ConcurrentHashMap<Int, List<OcrChar>>()
    private val mutex = Mutex()

    /**
     * @param viewBox the page's pdf.js viewBox: [x0, y0, x1, y1] in PDF user space.
     * @param rotation the page's /Rotate (0/90/180/270).
     * @return recognized characters in PDF user space, in reading order, or empty.
     */
    suspend fun ocrPage(file: File, pageIndex: Int, viewBox: List<Double>, rotation: Int): List<OcrChar> {
        cache[pageIndex]?.let { return it }
        return mutex.withLock {
            cache[pageIndex]?.let { return@withLock it }
            val result = try {
                runOcr(file, pageIndex, viewBox, normalizeRotation(rotation))
            } catch (e: Exception) {
                // Transient failure (render/recognition) — do NOT cache, allow retry.
                Timber.e(e, "OcrManager: OCR failed for page $pageIndex (not cached)")
                return@withLock emptyList()
            }
            // Cache successful results (including a genuine "no text recognized").
            cache[pageIndex] = result
            Timber.i("OcrManager: page $pageIndex produced ${result.size} OCR chars (rot=$rotation)")
            result
        }
    }

    override fun close() {
        cache.clear()
        try {
            provider.close()
        } catch (_: Exception) {
        }
    }

    /** Throws on hard render/recognition failure so the caller can avoid caching it. */
    private suspend fun runOcr(file: File, pageIndex: Int, viewBox: List<Double>, rotation: Int): List<OcrChar> {
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
        } ?: throw IllegalStateException("PdfPageRenderer returned null for page $pageIndex")

        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        // Cropbox sanity check: the rendered bitmap should cover the same region as the
        // pdf.js viewBox. For rotation 90/270 the page's width/height are swapped. A large
        // aspect mismatch means cropbox != viewBox (rare) and rects will be misplaced.
        val expectAspect = if (rotation == 90 || rotation == 270) pdfH / pdfW else pdfW / pdfH
        val bmpAspect = (bw / bh).toDouble()
        if (expectAspect > 0 && abs(bmpAspect - expectAspect) / expectAspect > 0.02) {
            Timber.w("OcrManager: page $pageIndex aspect mismatch (bitmap=$bmpAspect expected=$expectAspect, rot=$rotation) — cropbox != viewBox?")
        }

        val blocks = try {
            provider.recognize(bitmap)
        } finally {
            bitmap.recycle()
        }
        if (blocks.isEmpty() || bw <= 0 || bh <= 0) return emptyList()

        // Map a bitmap pixel (px,py; origin top-left, y down) to PDF user space
        // (origin bottom-left, y up), inverting the page's /Rotate so chars land in the
        // unrotated user space the reader stores annotations in.
        fun mapPoint(px: Float, py: Float): Pair<Float, Float> {
            val u = px / bw
            val v = py / bh
            val (xN, yN) = when (rotation) {
                90 -> Pair(v, u)
                180 -> Pair(1f - u, v)
                270 -> Pair(1f - v, 1f - u)
                else -> Pair(u, 1f - v)
            }
            return Pair((vx0 + xN * pdfW).toFloat(), (vy0 + yN * pdfH).toFloat())
        }

        // Axis-aligned user-space rect of a pixel box (two opposite corners suffice
        // since rotation is a multiple of 90°).
        fun mapRect(l: Float, t: Float, r: Float, b: Float): FloatArray {
            val (x1, y1) = mapPoint(l, t)
            val (x2, y2) = mapPoint(r, b)
            return floatArrayOf(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
        }

        val vertical = rotation == 90 || rotation == 270
        val out = ArrayList<OcrChar>()
        // Emit block-by-block to keep columns separate; line-by-line within a block.
        for (block in blocks) {
            for (line in block.lines) {
                // The whole line shares one perpendicular extent so the reader's geometric
                // line splitting stays stable: for upright text that's the vertical (y)
                // extent; for 90/270 text (which runs along y in user space) it's x.
                val lineRect = mapRect(line.left, line.top, line.right, line.bottom)
                for (ch in line.chars) {
                    val cr = mapRect(ch.left, ch.top, ch.right, ch.bottom)
                    if (vertical) {
                        cr[0] = lineRect[0]; cr[2] = lineRect[2]
                        if (cr[3] - cr[1] <= 0f) continue
                    } else {
                        cr[1] = lineRect[1]; cr[3] = lineRect[3]
                        if (cr[2] - cr[0] <= 0f) continue
                    }
                    out.add(OcrChar(c = ch.text, rect = listOf(cr[0], cr[1], cr[2], cr[3]), rotation = rotation))
                }
            }
        }
        return out
    }

    private fun normalizeRotation(rotation: Int): Int {
        val r = ((rotation % 360) + 360) % 360
        return when (r) {
            90, 180, 270 -> r
            else -> 0
        }
    }
}
