package com.baltas80.pocketscan

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File

object MultilingualOcr {
    fun recognize(files: List<File>, context: Context, onComplete: (String) -> Unit) {
        if (files.isEmpty()) { onComplete(""); return }
        val recognizers: List<TextRecognizer> = listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        )
        val output = StringBuilder()
        fun closeAndFinish() {
            recognizers.forEach { it.close() }
            onComplete(output.toString())
        }
        fun processFile(index: Int) {
            if (index >= files.size) { closeAndFinish(); return }
            val file = files[index]
            val image = runCatching { InputImage.fromFilePath(context, Uri.fromFile(file)) }.getOrNull()
            if (image == null) { processFile(index + 1); return }
            var remaining = recognizers.size
            var completed = false
            recognizers.forEach { recognizer ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        synchronized(output) {
                            val text = result.text.trim()
                            if (text.isNotBlank() && !output.toString().contains(text)) {
                                if (output.isNotEmpty()) output.append("\n\n")
                                output.append(text)
                            }
                        }
                    }
                    .addOnCompleteListener {
                        remaining--
                        if (remaining == 0 && !completed) {
                            completed = true
                            processFile(index + 1)
                        }
                    }
            }
        }
        processFile(0)
    }
}
