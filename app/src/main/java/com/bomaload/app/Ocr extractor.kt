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

    data class Result(val pins: List<String>, val review: List<String>, val expired: Boolean)

    fun extract(bitmap: Bitmap, expected: Int, onDone: (Result) -> Unit) {
        val b = base(bitmap)
        val tiles = lazy { tiles(b) }
        val passCount = 4 + 9 + 1
        val pins = LinkedHashSet<String>()
        val review = LinkedHashSet<String>()
        var expired = false
        var zeroStreak = 0

        fun passBitmap(i: Int): Bitmap = when {
            i == 0 -> b
            i == 1 -> contrast(scale(b, 2f))
            i == 2 -> scale(crop(b, true), 2f)
            i == 3 -> scale(crop(b, false), 2f)
            i in 4..12 -> tiles.value[i - 4]
            else -> invert(b)
        }

        fun nextPass(i: Int) {
            if (i >= passCount || (i >= 4 && zeroStreak >= 2)) {
                val pruned = review.filter { rv -> pins.none { it.contains(rv) } }
                onDone(Result(pins.toList(), pruned, expired)); return
            }
            val before = pins.size + review.size
            recognizer.process(InputImage.fromBitmap(passBitmap(i), 0))
                .addOnSuccessListener { res ->
                    val r = parse(res.text)
                    pins.addAll(r.pins); review.addAll(r.review); expired = expired || r.expired
                    zeroStreak = if ((pins.size + review.size) == before) zeroStreak + 1 else 0
                    nextPass(i + 1)
                }
                .addOnFailureListener { nextPass(i + 1) }
        }
        nextPass(0)
    }

    private fun base(b: Bitmap): Bitmap =
        if (b.width < 1800) scale(b, 1800f / b.width) else b

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
        for (y in ys) for (x in xs) out.add(scale(Bitmap.createBitmap(b, x, y, tw, th), 2.5f))
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

    private fun shielded(lines: List<String>, i: Int): Boolean {
        val bad = listOf("SERIAL", "EXPIRY", "AIRTIME", "KEYS")
        val cur = lines[i].uppercase()
        val prev = if (i > 0) lines[i - 1].uppercase() else ""
        return bad.any { cur.contains(it) || prev.contains(it) }
    }

    private val HALF8 = Regex("^\\d{4}\\s\\d{4}$")

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
            val t = lines[i].trim()
            val hasLetters = t.any { it.isLetter() }
            val clean = t.replace(Regex("[^0-9]"), "")

            Regex("\\*141\\*([0-9]{16})").find(t)?.let {
                if (it.groupValues[1] != SAMPLE) pins.add(it.groupValues[1])
            }

            if (hasLetters) continue

            when {
                clean.length == 16 && clean != SAMPLE -> pins.add(clean)
                clean.length in 8..18 && clean.length != 16 -> review.add(clean)
            }

            // Only 8+8 merges are trusted (the one split shape these cards produce)
            if (HALF8.matches(t) && i + 1 < lines.size) {
                val nt = lines[i + 1].trim()
                if (HALF8.matches(nt)) {
                    val s = clean + nt.replace(Regex("[^0-9]"), "")
                    if (s.length == 16 && s != SAMPLE) pins.add(s)
                }
            }
        }
        return Result(pins.toList(), review.toList(), expired)
    }
}
