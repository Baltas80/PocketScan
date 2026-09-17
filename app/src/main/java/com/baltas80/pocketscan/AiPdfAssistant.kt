package com.baltas80.pocketscan

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object AiPdfAssistant {
    private const val MAX_INLINE_PDF_BYTES = 14_000_000L
    private const val MAX_OCR_CORRECTION_CHARS = 30000

    suspend fun answerStream(file: File, ocrText: String, question: String): Flow<String> = withContext(Dispatchers.IO) {
        require(file.isFile) { "Document not found" }
        require(file.length() <= MAX_INLINE_PDF_BYTES) {
            "Este PDF supera el límite de análisis IA directo."
        }

        flow {
            try {
                // A monetary TOTAL is a critical data field. Never let the LLM choose a
                // number when the document extractor cannot independently verify the field.
                if (DocumentQuestionClassifier.isTotalQuestion(question)) {
                    val verified = SpatialReceiptTotalExtractor.extract(file)
                    if (verified != null && verified.confidence >= 0.90) {
                        emit(exactTotalAnswer(verified))
                    } else {
                        emit(unverifiedTotalAnswer())
                    }
                    return@flow
                }

                val model = createModel()
                val prompt = content {
                    inlineData(file.readBytes(), "application/pdf")
                    text("""
                        You are PocketScan's document assistant. Answer ONLY from this PDF and the auxiliary OCR below.
                        Never invent facts or use outside knowledge.
                        Treat the OCR reading order as unreliable when it conflicts with the visual PDF layout.
                        For financial values, do not guess or infer a missing amount from unrelated numbers.
                        Distinguish products, subtotal, tax, discount, total, total due, cash received and change.
                        If the document does not contain enough reliable information, say so clearly.
                        Answer in ${languageName()} and be concise.

                        USER QUESTION:
                        $question

                        AUXILIARY OCR:
                        ${ocrText.take(16000)}
                    """.trimIndent())
                }

                var emitted = false
                model.generateContentStream(prompt).collect { chunk ->
                    chunk.text?.takeIf { it.isNotBlank() }?.let {
                        emitted = true
                        emit(it)
                    }
                }
                if (!emitted) emit(cloudFailure(IllegalStateException("AI returned no answer")))
            } catch (error: Throwable) {
                emit(cloudFailure(error))
            }
        }
    }

    suspend fun correctOcr(ocrText: String): String = withContext(Dispatchers.IO) {
        require(ocrText.isNotBlank()) { "No hay texto OCR para corregir" }
        val source = ocrText.take(MAX_OCR_CORRECTION_CHARS)
        val prompt = content {
            text("""
                You are PocketScan's OCR correction engine.
                Correct ONLY obvious OCR recognition errors in the supplied scanned text.
                Preserve the original meaning, names, numbers, dates, amounts, punctuation and paragraph order.
                Do not summarize, rewrite, translate, add facts, or invent missing text.
                If a word is ambiguous, keep the original OCR text.
                Return ONLY the corrected plain text, with no explanation or Markdown.

                SCANNED OCR:
                $source
            """.trimIndent())
        }
        val response = createModel().generateContent(prompt)
        response.text?.trim().takeUnless { it.isNullOrBlank() }
            ?: throw IllegalStateException("La IA no devolvió texto corregido")
    }

    private suspend fun createModel(): GenerativeModel = Firebase.ai(
        backend = GenerativeBackend.googleAI(),
        useLimitedUseAppCheckTokens = true
    ).generativeModel(AiModelConfig.modelName())

    private fun exactTotalAnswer(total: SpatialReceiptTotalExtractor.Total): String {
        val formatted = "%.2f".format(Locale.US, total.amount).replace('.', ',')
        val amount = total.currency?.let { "$formatted $it" } ?: formatted
        return if (languageCode() == "es") {
            "Importe total verificado: $amount."
        } else {
            "Verified document total: $amount."
        }
    }

    private fun unverifiedTotalAnswer(): String = if (languageCode() == "es") {
        "No puedo verificar el importe total con suficiente evidencia espacial en este documento. No voy a darte una cifra que podría ser incorrecta."
    } else {
        "I cannot verify the document total with sufficient spatial evidence. I will not provide a number that could be incorrect."
    }

    private fun cloudFailure(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.lastOrNull() ?: error
        val detail = root.message?.trim().orEmpty().take(500).ifBlank { root::class.java.simpleName }
        return when (languageCode()) {
            "es" -> "Gemini no está disponible.\n\nDiagnóstico: $detail"
            else -> "Gemini is unavailable.\n\nDiagnostic: $detail"
        }
    }

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
