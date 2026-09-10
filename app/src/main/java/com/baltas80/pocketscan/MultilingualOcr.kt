package com.baltas80.pocketscan

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.File

/** Selects one coherent OCR interpretation per page instead of concatenating scripts. */
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
        fun finishAll() { recognizers.forEach { it.second.close() }; onComplete(output.toString()) }
        fun processFile(index: Int) {
            if (index >= files.size) { finishAll(); return }
            val image = runCatching { InputImage.fromFilePath(context, Uri.fromFile(files[index])) }.getOrNull()
            if (image == null) { processFile(index + 1); return }
            val candidates = mutableListOf<Candidate>()
            var remaining = recognizers.size
            var finished = false
            fun finishPage() {
                if (finished) return
                finished = true
                selectCandidate(candidates)?.takeIf { it.text.isNotBlank() }?.let {
                    if (output.isNotEmpty()) output.append("\n\n")
                    output.append(it.text)
                }
                processFile(index + 1)
            }
            recognizers.forEach { (name, recognizer) ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        result.text.trim().takeIf { it.isNotBlank() }?.let { text ->
                            synchronized(candidates) { candidates.add(Candidate(name, text)) }
                        }
                    }
                    .addOnCompleteListener { remaining--; if (remaining == 0) finishPage() }
            }
        }
        processFile(0)
    }

    /** Prefer a coherent Latin result, but reject weak Latin fragments when a script has strong evidence. */
    private fun selectCandidate(candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null
        val latin = candidates.firstOrNull { it.name == "latin" }
        val strongNonLatin = candidates.filter { it.name != "latin" && hasStrongScriptEvidence(it.name, it.text) }
            .maxByOrNull { scriptScore(it.name, it.text) }
        if (latin == null) return strongNonLatin ?: candidates.maxByOrNull { textQualityScore(it.text) }
        if (strongNonLatin == null) return latin
        val latinScore = textQualityScore(latin.text)
        val nonLatinScore = scriptScore(strongNonLatin.name, strongNonLatin.text)
        return if (latinScore >= 100 || latinScore >= nonLatinScore) latin else strongNonLatin
    }

    private fun textQualityScore(text: String): Int {
        val letters = text.count { it.isLetter() }
        val digits = text.count { it.isDigit() }
        val whitespace = text.count { it.isWhitespace() }
        val controls = text.count { it.isISOControl() }
        return letters * 4 + digits * 2 + whitespace - controls * 10
    }

    private fun scriptScore(name: String, text: String): Int {
        val matches = when (name) {
            "chinese" -> text.count { it.code in 0x4E00..0x9FFF }
            "devanagari" -> text.count { it.code in 0x0900..0x097F }
            "japanese" -> text.count { it.code in 0x3040..0x30FF }
            "korean" -> text.count { it.code in 0xAC00..0xD7AF }
            else -> 0
        }
        return matches * 100 + text.length
    }

    private fun hasStrongScriptEvidence(name: String, text: String): Boolean = when (name) {
        "chinese" -> text.count { it.code in 0x4E00..0x9FFF } >= 3
        "devanagari" -> text.count { it.code in 0x0900..0x097F } >= 3
        "japanese" -> text.count { it.code in 0x3040..0x30FF } >= 3
        "korean" -> text.count { it.code in 0xAC00..0xD7AF } >= 3
        else -> false
    }
}
