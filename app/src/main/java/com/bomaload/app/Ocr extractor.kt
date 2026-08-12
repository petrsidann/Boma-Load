package com.bomaload.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object OcrExtractor {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private const val SAMPLE = "1234567890123456"
    private val CONF = mapOf(
        'O' to '0', 'Q' to '0', 'D' to '0', 'U' to '0',
        'I' to '1', 'L' to '1',
        'S' to '5',
        'B' to '8', 'E' to '8',
        'Z' to '2', 'R' to '2',
        'G' to '6',
        'T' to '7', 'V' to '7',
        'A' to '4'
    )

    data class Result(val pins: List<String>, val review: List<String>, val expired: Boolean)

    fun extract(bitmap: Bitmap, expected: Int, onDone: (Result) -> Unit) {
        val b = base(bitmap)
        val tiles = lazy { tiles(b) }
        val passCount = 4 + 9 + 1
        val pins = LinkedHashSet<String>()
        val review = LinkedHashSet<String>()
        var expired = false

        fun passBitmap(i: Int): Bitmap = when {
            i == 0 -> b
            i == 1 -> contrast(scale(b, 2f))
            i == 2 -> scale(crop(b, true), 2f)
            i == 3 -> scale(crop(b, false), 2f)
            i in 4..12 -> tiles.value[i - 4]
            else -> invert(b)
        }

        fun nextPass(i: Int) {
            if (i >= passCount || pins.size >= expected) {
                onDone(Result(pins.toList(), review.toList(), expired)); return
            }
            recognizer.process(InputImage.fromBitmap(passBitmap(i), 0))
                .addOnSuccessListener { res ->
                    val r = parse(res.text)
                    pins.addAll(r.pins); review.addAll(r.review); expired = expired || r.expired
                    nextPass(i + 1)
                }
                .addOnFailureListener { nextPass(i + 1) }
        }
        nextPass(0)
    }

    private fun base(b: Bitmap): Bitmap =
        if (b.width < 1600) scale(b, 1600f / b.width) else b

    private fun scale(b: Bitmap, f: Float): Bitmap =
        Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)

    private fun crop(b: Bitmap, top: Boolean): Bitmap {
        val h = b.height / 2
        return if (top) Bitmap.createBitmap(b, 0, 0, b.width, h)
        else Bitmap.createBitmap(b, 0, h, b.width, b.height - h)
    }

    private fun tiles(b: Bitmap): List<Bitmap> {
        val tw = b.width / 2; val th = b.height / 2
        val xs = listOf(0, (b.width - tw) / 2, b.width - tw)
        val ys = listOf(0, (b.height - th) / 2, b.height - th)
        val out = mutableListOf<Bitmap>()
        for (y in ys) for (x in xs) out.add(scale(Bitmap.createBitmap(b, x, y, tw, th), 2f))
        return out
    }

    private fun filtered(b: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint()
        p.colorFilter = ColorMatrixColorFilter(matrix)
        c.drawBitmap(b, 0f, 0f, p)
        return out
    }

    private fun contrast(b: Bitmap): Bitmap = filtered(b, ColorMatrix(floatArrayOf(
        1.6f, 0f, 0f, 0f, -70f,
        0f, 1.6f, 0f, 0f, -70f,
        0f, 0f, 1.6f, 0f, -70f,
        0f, 0f, 0f, 1f, 0f)))

    private fun invert(b: Bitmap): Bitmap = filtered(b, ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f)))

    private fun deconfuse(s: String) = s.map { CONF[it.uppercaseChar()] ?: it }.joinToString("")

    private fun shielded(lines: List<String>, i: Int): Boolean {
        val bad = listOf("SERIAL", "EXPIRY", "AIRTIME", "KEYS")
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
                if (c.length in 14..18 && c.length != 16) review.add(c)
            }
            listOf(raw, deconfuse(raw)).forEach { r ->
                Regex("\\*141\\*([0-9]{16})").find(r)?.let {
                    if (it.groupValues[1] != SAMPLE) pins.add(it.groupValues[1])
                }
            }
            if (clean.length in 4..12 && i + 1 < lines.size) {
                val nxt = lines[i + 1].replace(Regex("[^0-9]"), "")
                if (nxt.length in 4..12) {
                    val sum = clean.length + nxt.length
                    if (sum == 16) pins.add(clean + nxt)
                    else if (sum in 14..18) review.add(clean + nxt)
                }
            }
        }
        return Result(pins.toList(), review.toList(), expired)
    }
}
