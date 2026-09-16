package com.baltas80.pocketscan

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object AiPdfAssistant {
    private const val MAX_INLINE_PDF_BYTES = 14_000_000L
    private const val MAX_OCR_CORRECTION_CHARS = 30000
    private const val VISUAL_OCR_WIDTH = 2200
    private const val VISUAL_OCR_MAX_PAGES = 3

    suspend fun answerStream(file: File, ocrText: String, question: String): Flow<String> = withContext(Dispatchers.IO) {
        require(file.isFile) { "Document not found" }
        require(file.length() <= MAX_INLINE_PDF_BYTES) {
            "Este PDF supera el límite de análisis IA directo."
        }

        flow {
            try {
                val normalizedQuestion = normalize(question)

                // Accounting values are resolved by geometry-aware OCR first. This keeps
                // the physical relationship between TOTAL and its value and prevents a
                // product amount such as 5,00 from being paired with a distant TOTAL.
                if (normalizedQuestion.contains("total") || normalizedQuestion.contains("importe")) {
                    SpatialReceiptTotalExtractor.extract(file)?.let { verified ->
                        emit(exactTotalAnswer(verified))
                        return@flow
                    }
                    // Do not fall back to text-only total heuristics. When geometry cannot
                    // verify the amount, Gemini receives the real PDF and can inspect its
                    // visual layout. A wrong total is worse than an explicit cloud error.
                }

                val model = createModel()
                val prompt = content {
                    inlineData(file.readBytes(), "application/pdf")
                    text("""
                        You are PocketScan's document assistant. Answer ONLY from this PDF and the auxiliary OCR below.
                        Never invent facts or use outside knowledge.
                        Treat the OCR reading order as unreliable when it conflicts with the visual PDF layout.
                        For totals and amounts, identify the actual financial summary row visually.
                        Do not use an item price, subtotal, tax, cash received or change as the document total.
                        The value must belong to the TOTAL/TOTAL A PAGAR field in the document, not merely be nearby in OCR text.
                        If the PDF does not contain enough information, say so clearly.
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

    private fun cloudFailure(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.lastOrNull() ?: error
        val detail = root.message?.trim().orEmpty().take(500).ifBlank { root::class.java.simpleName }
        return when (languageCode()) {
            "es" -> "Gemini no está disponible.\n\nDiagnóstico: $detail"
            else -> "Gemini is unavailable.\n\nDiagnostic: $detail"
        }
    }

    private fun highResolutionOcr(pdf: File): String {
        if (!pdf.isFile) return ""
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val output = StringBuilder()
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pages = renderer.pageCount.coerceAtMost(VISUAL_OCR_MAX_PAGES)
                    for (index in 0 until pages) {
                        renderer.openPage(index).use { page ->
                            val ratio = page.height.toFloat() / page.width.toFloat()
                            val height = (VISUAL_OCR_WIDTH * ratio).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(VISUAL_OCR_WIDTH, height, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                                if (result.text.isNotBlank()) {
                                    if (output.isNotEmpty()) output.append("\n\n")
                                    output.append(result.text)
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
            output.toString()
        } finally {
            recognizer.close()
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
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
