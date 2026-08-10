package com.bomaload.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object OcrExtractor {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private const val SAMPLE = "1234567890123456"   // the fake example printed on every card – ignored

    fun extract(bitmap: Bitmap, onDone: (List<String>) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { onDone(parse(it.text)) }
            .addOnFailureListener { onDone(emptyList()) }
    }

    fun parse(text: String): List<String> {
        val out = LinkedHashSet<String>()
        text.split("\n").forEach { line ->
            val cleaned = line.replace(Regex("[^0-9]"), "")
            if (cleaned.length == 16 && cleaned != SAMPLE) out.add(cleaned)
            Regex("\\*141\\*([0-9]{16})").find(line)?.let {
                val d = it.groupValues[1]; if (d != SAMPLE) out.add(d)
            }
        }
        return out.toList()
    }
}
