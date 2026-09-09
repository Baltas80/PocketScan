package com.baltas80.pocketscan

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object AiPdfAssistant {
    suspend fun answerStream(file: File, ocrText: String, question: String): Flow<String> = withContext(Dispatchers.IO) {
        require(file.isFile) { "Document not found" }
        val model: GenerativeModel = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel("gemini-3.8-flash")
        val prompt = content {
            inlineData(bytes = file.readBytes(), mimeType = "application/pdf")
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
            model.generateContentStream(prompt).collect { chunk ->
                chunk.text?.takeIf { it.isNotBlank() }?.let { emit(it) }
            }
        }
    }

    private fun languageName(): String = when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
        "es" -> "Spanish"
        "en" -> "English"
        "fr" -> "French"
        "de" -> "German"
        "it" -> "Italian"
        "pt" -> "Portuguese"
        "ca" -> "Catalan"
        else -> Locale.getDefault().displayLanguage
    }
}
