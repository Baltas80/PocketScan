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
    private const val MAX_OCR_CORRECTION_CHARS = 30000

    suspend fun answerStream(file: File, ocrText: String, question: String): Flow<String> = withContext(Dispatchers.IO) {
        require(file.isFile) { "Document not found" }
        require(file.length() <= MAX_INLINE_PDF_BYTES) {
            "Este PDF supera el límite de análisis IA directo."
        }

        // Monetary totals must be deterministic. Cloud AI can otherwise answer with a
        // plausible product price even when the OCR contains an explicit TOTAL row.
        // Resolve these questions locally from OCR before contacting the model.
        val normalizedQuestion = normalize(question)
        if (normalizedQuestion.contains("total") || normalizedQuestion.contains("importe")) {
            return@withContext flow { emit(localAnswer(question, ocrText)) }
        }

        val prompt = content {
            inlineData(file.readBytes(), "application/pdf")
            text("""
                You are PocketScan's document assistant. Answer the user's question using ONLY information contained in this PDF and the auxiliary OCR below.
                Do not invent, infer unsupported facts, or use outside knowledge. If the document does not contain enough information, say so clearly.
                Be concise but include exact values, dates, names and amounts when relevant.
                When the user asks for a total or amount, use the line explicitly labelled TOTAL or TOTAL A PAGAR, not the first product price or another monetary value.
                When the document contains a table, respect the relationship between labels and values and do not confuse the item-price column with the total row.
                Answer in ${languageName()}.

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

    suspend fun correctOcr(ocrText: String): String = withContext(Dispatchers.IO) {
        require(ocrText.isNotBlank()) { "No hay texto OCR para corregir" }
        val source = ocrText.take(MAX_OCR_CORRECTION_CHARS)
        val prompt = content {
            text("""
                You are PocketScan's OCR correction engine.
                Correct ONLY obvious OCR recognition errors in the supplied scanned text.
                Preserve the original meaning, names, numbers, dates, amounts, punctuation, paragraph order and page markers.
                Do not summarize, rewrite, translate, add facts, or invent missing text.
                If a word is ambiguous, keep the original OCR text rather than guessing.
                Return ONLY the corrected plain text, with no explanation and no Markdown.

                SCANNED OCR:
                $source
            """.trimIndent())
        }
        val model: GenerativeModel = Firebase.ai(
            backend = GenerativeBackend.googleAI(),
            useLimitedUseAppCheckTokens = true
        ).generativeModel(AiModelConfig.modelName())
        val response = model.generateContent(prompt)
        response.text?.trim().takeUnless { it.isNullOrBlank() }
            ?: throw IllegalStateException("La IA no devolvió texto corregido")
    }

    private fun localAnswer(question: String, ocrText: String): String {
        val normalized = normalize(question)
        if (normalized.contains("total") || normalized.contains("importe")) {
            val total = findDocumentTotal(ocrText)
            if (total != null) {
                return when (languageCode()) {
                    "es" -> "Importe total detectado en el documento: ${total.amount}${total.currency?.let { " $it" }.orEmpty()}."
                    else -> "Total amount detected in the document: ${total.amount}${total.currency?.let { " $it" }.orEmpty()}."
                }
            }
            return when (languageCode()) {
                "es" -> "No he encontrado un importe total claramente identificado en el documento."
                else -> "I could not find a clearly identified total amount in the document."
            }
        }

        val text = ocrText.replace(Regex("\\s+"), " ").trim()
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

    private data class AmountMatch(val amount: String, val currency: String?)

    /**
     * Receipt total extraction based on a proven open-source receipt-parser strategy:
     * explicit total labels first, adjacent-line recovery for OCR, then conservative
     * payment-line and largest-amount fallbacks.
     */
    private fun findDocumentTotal(ocrText: String): AmountMatch? {
        val lines = ocrText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null

        val totalKeywords = listOf(
            "grand total", "total due", "amount due", "balance due",
            "total", "toplam", "genel toplam", "tutar", "importe total", "importe final"
        )
        val negativeKeywords = listOf(
            "subtotal", "sub total", "ara toplam", "tax", "kdv", "vat",
            "change", "cash", "tip", "discount", "indirim"
        )
        val paymentKeywords = listOf("tarjeta", "pago", "efectivo", "card", "payment")
        val amount = Regex("(?<!\\d)(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})|\\d+[.,]\\d{2})(?!\\d)")

        fun currencyFromLine(line: String): String? = when {
            Regex("(?i)€|\\bEUR\\b").containsMatchIn(line) -> "EUR"
            Regex("(?i)\\$|\\bUSD\\b").containsMatchIn(line) -> "USD"
            Regex("(?i)£|\\bGBP\\b").containsMatchIn(line) -> "GBP"
            else -> null
        }

        fun amountsOnLine(line: String): List<AmountMatch> = amount.findAll(line).mapNotNull { match ->
            parseReceiptNumber(match.value)?.let { value -> AmountMatch(formatAmount(value), currencyFromLine(line)) }
        }.toList()

        fun keywordMatch(line: String, keywords: List<String>): Boolean {
            val normalized = normalize(line)
            return keywords.any { normalized.contains(normalize(it)) }
        }

        // 1. Explicit total and amount on the same OCR line.
        lines.forEach { line ->
            if (!keywordMatch(line, totalKeywords) || keywordMatch(line, negativeKeywords)) return@forEach
            amountsOnLine(line).lastOrNull()?.let { return it }
        }

        // 2. OCR often separates `TOTAL ........` and `23,02` into adjacent lines.
        lines.forEachIndexed { index, line ->
            if (!keywordMatch(line, totalKeywords) || keywordMatch(line, negativeKeywords)) return@forEachIndexed
            for (nextIndex in (index + 1)..minOf(index + 2, lines.lastIndex)) {
                amountsOnLine(lines[nextIndex]).lastOrNull()?.let { return it }
            }
        }

        // 3. A payment line is a conservative receipt-specific fallback.
        lines.forEach { line ->
            if (!keywordMatch(line, paymentKeywords)) return@forEach
            amountsOnLine(line).lastOrNull()?.let { return it }
        }

        // 4. Last resort: largest two-decimal monetary amount in the OCR.
        return lines.flatMap { amountsOnLine(it) }.maxByOrNull { parseReceiptNumber(it.amount) ?: Double.MIN_VALUE }
    }

    private fun formatAmount(value: Double): String = "%.2f".format(Locale.US, value).replace('.', ',')

    private fun parseReceiptNumber(value: String): Double? {
        val s = value.replace(" ", "")
        return runCatching {
            val normalized = when {
                s.contains(',') && s.contains('.') -> if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
                s.count { it == ',' } == 1 && s.substringAfter(',').length == 2 -> s.replace(',', '.')
                s.count { it == '.' } == 1 && s.substringAfter('.').length == 2 -> s
                s.count { it == ',' } > 1 -> s.replace(",", "")
                s.count { it == '.' } > 1 -> s.replace(".", "")
                else -> s
            }
            normalized.toDouble()
        }.getOrNull()
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
