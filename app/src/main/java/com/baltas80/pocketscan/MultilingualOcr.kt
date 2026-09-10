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

/**
 * Runs the available ML Kit script recognizers, but does not concatenate their
 * independent interpretations of the same Latin document. That used to turn a
 * clean Spanish/English OCR result into several competing blocks of gibberish.
 */
object MultilingualOcr {
    private data class Candidate(val name: String, val text: String)

    fun recognize(files: List<File>, context: Context, onComplete: (String) -> Unit) {
        if (files.isEmpty()) { onComplete(""); return }

        val recognizers: List<Pair<String, TextRecognizer>> = listOf(
            "latin" to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            "chinese" to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            "devanagari" to TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
            "japanese" to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            "korean" to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        )
        val output = StringBuilder()

        fun closeAndFinish() {
            recognizers.forEach { it.second.close() }
            onComplete(output.toString())
        }

        fun processFile(index: Int) {
            if (index >= files.size) { closeAndFinish(); return }
            val file = files[index]
            val image = runCatching { InputImage.fromFilePath(context, Uri.fromFile(file)) }.getOrNull()
            if (image == null) { processFile(index + 1); return }

            val candidates = mutableListOf<Candidate>()
            var remaining = recognizers.size
            var completed = false

            fun finishPage() {
                if (completed) return
                completed = true
                val selected = selectCandidates(candidates)
                selected.forEach { candidate ->
                    if (candidate.text.isNotBlank()) {
                        if (output.isNotEmpty()) output.append("\n\n")
                        output.append(candidate.text)
                    }
                }
                processFile(index + 1)
            }

            recognizers.forEach { (name, recognizer) ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val text = result.text.trim()
                        if (text.isNotBlank()) synchronized(candidates) {
                            candidates.add(Candidate(name, text))
                        }
                    }
                    .addOnCompleteListener {
                        remaining--
                        if (remaining == 0) finishPage()
                    }
            }
        }

        processFile(0)
    }

    /**
     * Prefer the Latin recognizer for European/Latin-script documents. Add a
     * script-specific result only when it contains enough evidence that the
     * page actually uses that script. If Latin returns nothing, use the best
     * non-Latin candidate instead.
     */
    private fun selectCandidates(candidates: List<Candidate>): List<Candidate> {
        val latin = candidates.firstOrNull { it.name == "latin" && it.text.isNotBlank() }
        val nonLatin = candidates.filter { it.name != "latin" && hasStrongScriptEvidence(it.name, it.text) }

        if (latin != null) {
            return listOf(latin) + nonLatin
        }
        return nonLatin.maxByOrNull { it.text.length }?.let(::listOf)
            ?: candidates.maxByOrNull { it.text.length }?.let(::listOf)
            ?: emptyList()
    }

    private fun hasStrongScriptEvidence(name: String, text: String): Boolean {
        val matches = when (name) {
            "chinese" -> text.count { it.code in 0x4E00..0x9FFF }
            "devanagari" -> text.count { it.code in 0x0900..0x097F }
            "japanese" -> text.count { it.code in 0x3040..0x30FF }
            "korean" -> text.count { it.code in 0xAC00..0xD7AF }
            else -> 0
        }
        return matches >= 3
    }
}
