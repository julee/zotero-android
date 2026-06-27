package org.zotero.android.screens.reader.ocr

import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pluggable OCR provider backed by Baidu's PaddleOCR-VL cloud API (AI Studio).
 *
 * This is wired but NOT the default: to use it, swap the provider passed to
 * [OcrManager] and supply [apiUrl] + [token] (obtained at
 * https://aistudio.baidu.com/paddleocr/task). When credentials are absent it
 * returns nothing, so the app degrades gracefully to the on-device provider.
 *
 * PaddleOCR-VL emits eight `<|LOC_nnn|>` tokens per text region (a 4-point
 * quadrilateral on a normalized 0..999 grid). We reduce each quadrilateral to an
 * axis-aligned line box and split it across the region's characters. The exact
 * response schema should be verified against the live API before relying on this
 * in production; the parsing below targets the documented structured output.
 */
class BaiduVLOcrProvider(
    private val apiUrl: String?,
    private val token: String?,
) : OcrProvider {

    override suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
        val url = apiUrl
        val tok = token
        if (url.isNullOrBlank() || tok.isNullOrBlank()) {
            Timber.w("BaiduVLOcrProvider: missing apiUrl/token; returning no results")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            try {
                val base64Image = encodeImage(bitmap)
                val body = "{\"image\":\"$base64Image\"}"
                val response = postJson(url, tok, body)
                parse(response, bitmap.width.toFloat(), bitmap.height.toFloat())
            } catch (e: Exception) {
                Timber.e(e, "BaiduVLOcrProvider: request failed")
                emptyList()
            }
        }
    }

    private fun encodeImage(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun postJson(url: String, token: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "token $token")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * Parse PaddleOCR-VL structured output into pixel-space blocks. Coordinates in
     * the response are on a normalized 0..999 grid; we scale them back to pixels.
     *
     * This targets a result shape of the form
     *   { "result": { "blocks": [ { "lines": [ { "text": "...", "poly": [x0,y0,...,x3,y3] } ] } ] } }
     * Adjust the field names here once verified against the live endpoint.
     */
    private fun parse(json: String, bw: Float, bh: Float): List<OcrBlock> {
        val root = JsonParser.parseString(json).asJsonObject
        val result = root.getAsJsonObject("result") ?: return emptyList()
        val blocksJson = result.getAsJsonArray("blocks") ?: return emptyList()
        val blocks = ArrayList<OcrBlock>()
        for (blockEl in blocksJson) {
            val linesJson = blockEl.asJsonObject.getAsJsonArray("lines") ?: continue
            val lines = ArrayList<OcrLinePx>()
            for (lineEl in linesJson) {
                val lineObj = lineEl.asJsonObject
                val text = lineObj.get("text")?.asString ?: continue
                val poly = lineObj.getAsJsonArray("poly") ?: continue
                if (poly.size() < 8 || text.isEmpty()) continue
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                var i = 0
                while (i + 1 < poly.size()) {
                    val x = poly[i].asFloat / 999f * bw
                    val y = poly[i + 1].asFloat / 999f * bh
                    minX = minOf(minX, x); maxX = maxOf(maxX, x)
                    minY = minOf(minY, y); maxY = maxOf(maxY, y)
                    i += 2
                }
                val chars = splitLineEvenly(text, minX, minY, maxX, maxY)
                if (chars.isNotEmpty()) {
                    lines.add(OcrLinePx(minX, minY, maxX, maxY, chars))
                }
            }
            if (lines.isNotEmpty()) blocks.add(OcrBlock(lines))
        }
        return blocks
    }

    private fun splitLineEvenly(text: String, l: Float, t: Float, r: Float, b: Float): List<OcrCharPx> {
        val cps = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            cps.add(text.substring(i, i + n)); i += n
        }
        if (cps.isEmpty()) return emptyList()
        val step = (r - l) / cps.size
        val out = ArrayList<OcrCharPx>()
        for (k in cps.indices) {
            val ch = cps[k]
            if (ch.isBlank()) continue
            out.add(OcrCharPx(ch, l + step * k, t, l + step * (k + 1), b))
        }
        return out
    }
}
