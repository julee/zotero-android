package org.zotero.android.screens.reader.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR via Google ML Kit. Uses the bundled Chinese recognizer, which
 * also handles Latin script, so it works offline and without Google Play Services.
 *
 * ML Kit exposes geometry at the block / line / element / symbol levels. We build
 * per-character boxes from symbols when available, otherwise we distribute an
 * element's box evenly across its characters (the usual case for CJK, where
 * symbol-level boxes are not provided).
 */
class MLKitOcrProvider : OcrProvider {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

        val blocks = ArrayList<OcrBlock>()
        for (block in text.textBlocks) {
            val lines = ArrayList<OcrLinePx>()
            for (line in block.lines) {
                val lineBox = line.boundingBox ?: continue
                val chars = ArrayList<OcrCharPx>()
                for (element in line.elements) {
                    appendElementChars(element.symbols, element.text, element.boundingBox, chars)
                }
                if (chars.isNotEmpty()) {
                    lines.add(
                        OcrLinePx(
                            left = lineBox.left.toFloat(),
                            top = lineBox.top.toFloat(),
                            right = lineBox.right.toFloat(),
                            bottom = lineBox.bottom.toFloat(),
                            chars = chars,
                        )
                    )
                }
            }
            if (lines.isNotEmpty()) {
                blocks.add(OcrBlock(lines))
            }
        }
        return blocks
    }

    private fun appendElementChars(
        symbols: List<com.google.mlkit.vision.text.Text.Symbol>,
        elementText: String,
        elementBox: Rect?,
        out: ArrayList<OcrCharPx>,
    ) {
        // Prefer real per-symbol boxes when ML Kit provides them.
        val symbolsHaveBoxes = symbols.isNotEmpty() && symbols.all { it.boundingBox != null }
        if (symbolsHaveBoxes) {
            for (symbol in symbols) {
                val b = symbol.boundingBox ?: continue
                val t = symbol.text
                if (t.isBlank()) continue
                out.add(OcrCharPx(t, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()))
            }
            return
        }

        // Fall back to splitting the element box evenly across its characters.
        val box = elementBox ?: return
        val codepoints = toCodepoints(elementText)
        if (codepoints.isEmpty()) return
        val width = (box.right - box.left).toFloat()
        val step = width / codepoints.size
        for (i in codepoints.indices) {
            val ch = codepoints[i]
            if (ch.isBlank()) continue
            val l = box.left + step * i
            val r = box.left + step * (i + 1)
            out.add(OcrCharPx(ch, l, box.top.toFloat(), r, box.bottom.toFloat()))
        }
    }

    private fun toCodepoints(s: String): List<String> {
        val result = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val count = Character.charCount(cp)
            result.add(s.substring(i, i + count))
            i += count
        }
        return result
    }
}
