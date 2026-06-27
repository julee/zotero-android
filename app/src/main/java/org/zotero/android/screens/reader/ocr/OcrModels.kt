package org.zotero.android.screens.reader.ocr

import android.graphics.Bitmap

/**
 * On-device OCR for scanned / image-only PDF pages.
 *
 * Providers recognize text on a rendered page [Bitmap] and return geometry in
 * **bitmap pixel space** (origin top-left, y increasing downward). The higher
 * level [OcrManager] is responsible for mapping pixels into PDF user space.
 *
 * Output is grouped block → line → char so that:
 *  - block order is preserved (keeps columns separate in multi-column layouts),
 *  - each line carries its own bounding box (used to give every char in the line
 *    a consistent vertical extent, which keeps the reader's line splitting stable).
 */
interface OcrProvider {
    suspend fun recognize(bitmap: Bitmap): List<OcrBlock>
}

/** A character box in bitmap pixel space. */
data class OcrCharPx(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** A line of recognized text with its bounding box and per-character boxes. */
data class OcrLinePx(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val chars: List<OcrCharPx>,
)

/** A block (≈ paragraph / column) of recognized lines, in reading order. */
data class OcrBlock(
    val lines: List<OcrLinePx>,
)

/**
 * A single recognized character expressed in PDF user space (origin bottom-left,
 * y increasing upward, with rect = [x1, y1, x2, y2], x1 < x2, y1 < y2). This is the
 * exact shape the reader's worker (module.js) consumes to synthesize a text layer.
 */
data class OcrChar(
    val c: String,
    val rect: List<Float>,
)
