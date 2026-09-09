package com.baltas80.pocketscan

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale

object AiPdfAssistant {
    private const val MAX_INLINE_PDF_BYTES = 14_000_000L

    suspend fun answerStream(file: File, ocrText: String, question: String): Flow<String> = withContext(Dispatchers.IO) {
        require(file.isFile) { "Document not found" }
        require(file.length() <= MAX_INLINE_PDF_BYTES) {
            "Este PDF supera el límite de análisis IA directo."
        }
        val prompt = content {
            inlineData(file.readBytes(), "application/pdf")
            text("""
                You are PocketScan's document assistant. Answer the user's question using ONLY information contained in this PDF and the auxiliary OCR below.
                Do not invent, infer unsupported facts, or use outside knowledge. If the document does not contain enough information, say so clearly.
                Be concise but include exact values, dates, names and amounts when relevant. Answer in ${languageName()}.

                USER QUESTION:
                $question

                AUXILIARY OCR:
                ${ocrText.take(16000)}
            """.trimIndent())
        }
        flow {
            try {
                val model: GenerativeModel = Firebase.ai(
                    backend = GenerativeBackend.googleAI(),
                    useLimitedUseAppCheckTokens = true
                ).generativeModel(AiModelConfig.modelName())
                var emitted = false
                model.generateContentStream(prompt).collect { chunk ->
                    chunk.text?.takeIf { it.isNotBlank() }?.let {
                        emitted = true
                        emit(it)
                    }
                }
                if (!emitted) emit(localAnswer(question, ocrText))
            } catch (_: Throwable) {
                emit(localAnswer(question, ocrText))
            }
        }
    }

    private fun localAnswer(question: String, ocrText: String): String {
        val normalized = normalize(question)
        val text = ocrText.replace(Regex("\\s+"), " ").trim()
        val total = Regex("(?i)\\b(?:total|importe total|total a pagar|importe)\\s*[:=]?\\s*([0-9][0-9.,]*)\\s*(€|EUR|USD|\\$|GBP|£)?")
            .find(text)
        if (normalized.contains("total") || normalized.contains("importe")) {
            if (total != null) {
                val amount = total.groupValues[1]
                val currency = total.groupValues[2].takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                return when (languageCode()) {
                    "es" -> "Importe total detectado en el documento: $amount$currency."
                    else -> "Total amount detected in the document: $amount$currency."
                }
            }
            return when (languageCode()) {
                "es" -> "No he encontrado un importe total claramente identificado en el documento."
                else -> "I could not find a clearly identified total amount in the document."
            }
        }

        val date = Regex("\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b").find(text)?.value
        if (normalized.contains("fecha") || normalized.contains("date")) {
            return if (date != null) "Fecha detectada: $date." else "No he encontrado una fecha claramente identificada."
        }

        val preview = text.take(700)
        return when (languageCode()) {
            "es" -> "La IA en la nube no está disponible en este momento. Puedo consultar el OCR local, pero esta pregunta necesita un análisis más profundo del documento.\n\nTexto detectado:\n$preview"
            else -> "Cloud AI is not available right now. I can consult local OCR, but this question needs deeper document analysis.\n\nDetected text:\n$preview"
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(
        value.lowercase(Locale.ROOT),
        Normalizer.Form.NFD
    ).replace("\\p{M}+".toRegex(), "")

    private fun languageCode(): String = Locale.getDefault().language.lowercase(Locale.ROOT)

    private fun languageName(): String = when (languageCode()) {
        "es" -> "Spanish (Spain)"
        "en" -> "English"
        "fr" -> "French"
        "de" -> "German"
        "it" -> "Italian"
        "pt" -> "Portuguese"
        "ca" -> "Catalan"
        else -> Locale.getDefault().displayLanguage
    }
}
