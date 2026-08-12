package com.bomaload.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object OcrExtractor {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private const val SAMPLE = "1234567890123456"
    private val CONF = mapOf('O' to '0', 'Q' to '0', 'D' to '0', 'I' to '1', 'L' to '1',
        'S' to '5', 'B' to '8', 'Z' to '2', 'G' to '6', 'T' to '7')

    data class Result(val pins: List<String>, val review: List<String>, val expired: Boolean)

    fun extract(bitmap: Bitmap, onDone: (Result) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { onDone(parse(it.text)) }
            .addOnFailureListener { onDone(Result(emptyList(), emptyList(), false)) }
    }

    private fun deconfuse(s: String) = s.map { CONF[it.uppercaseChar()] ?: it }.joinToString("")

    private fun shielded(lines: List<String>, i: Int): Boolean {
        val bad = listOf("SERIAL", "EXPIRY", "TAMPERED", "AIRTIME", "KEYS")
        val cur = lines[i].uppercase()
        val prev = if (i > 0) lines[i - 1].uppercase() else ""
        return bad.any { cur.contains(it) || prev.contains(it) }
    }

    fun parse(text: String): Result {
        val lines = text.split("\n")
        val pins = LinkedHashSet<String>()
        val review = LinkedHashSet<String>()

        val exp = Regex("(\\d{2})-(\\d{2})-(\\d{4})").find(text)
        var expired = false
        if (exp != null) {
            val mm = exp.groupValues[2].toInt(); val yy = exp.groupValues[3].toInt()
            if (yy < 2026 || (yy == 2026 && mm < 8)) expired = true
        }

        for (i in lines.indices) {
            if (shielded(lines, i)) continue
            val raw = lines[i]
            val clean = raw.replace(Regex("[^0-9]"), "")
            val cleanD = deconfuse(raw).replace(Regex("[^0-9]"), "")

            listOf(clean, cleanD).forEach { c ->
                if (c.length == 16 && c != SAMPLE) pins.add(c)
                if (c.length == 15 || c.length == 17) review.add(c)
            }
            listOf(raw, deconfuse(raw)).forEach { r ->
                Regex("\\*141\\*([0-9]{16})").find(r)?.let {
                    if (it.groupValues[1] != SAMPLE) pins.add(it.groupValues[1])
                }
            }
            if (clean.length in 6..12 && i + 1 < lines.size) {
                val nxt = lines[i + 1].replace(Regex("[^0-9]"), "")
                if (nxt.length in 4..10) {
                    if (clean.length + nxt.length == 16) pins.add(clean + nxt)
                    else if (clean.length + nxt.length in 15..17) review.add(clean + nxt)
                }
            }
        }
        return Result(pins.toList(), review.toList(), expired)
    }
}
