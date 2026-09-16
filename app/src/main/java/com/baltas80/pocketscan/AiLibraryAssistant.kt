package com.baltas80.pocketscan

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale

object AiLibraryAssistant {
    private const val VISUAL_OCR_WIDTH = 1800
    private const val VISUAL_OCR_MAX_PAGES = 3

    suspend fun ask(filesDir: File, question: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val scans = File(filesDir, "scans")
            if (!scans.isDirectory) return@runCatching noDocumentsMessage()

            val documents = scans.walkTopDown()
                .filter { it.isFile && it.extension.equals("pdf", true) }
                .toList()
            if (documents.isEmpty()) return@runCatching noDocumentsMessage()

            if (asksTotal(question)) {
                val ocrTotals = documents.mapNotNull { file ->
                    findReceiptTotalFromOcr(file)?.let { file to it }
                }
                if (ocrTotals.size == 1) {
                    return@runCatching exactOcrTotalAnswer(ocrTotals.single().second)
                }
                if (ocrTotals.size > 1) {
                    val currencies = ocrTotals.map { it.second.second }.distinct()
                    if (currencies.size == 1) {
                        val sum = ocrTotals.sumOf { it.second.first }
                        return@runCatching exactOcrAggregateAnswer(sum, currencies.single(), ocrTotals.size)
                    }
                }
            }

            val queryResult = AiLibraryQueryEngine.query(question, documents)
            if (queryResult.matches.isEmpty()) {
                return@runCatching insufficientMessage(languageCode())
            }

            val context = AiLibraryQueryEngine.buildContext(queryResult).take(30000)
            tryCloud(question, context).getOrElse {
                localAnswer(question, queryResult)
            }
        }
    }

    private suspend fun tryCloud(question: String, context: String): Result<String> = runCatching {
        val model: GenerativeModel = Firebase.ai(
            backend = GenerativeBackend.googleAI(),
            useLimitedUseAppCheckTokens = true
        ).generativeModel(AiModelConfig.modelName())

        val language = languageName()
        val prompt = """
            You are PocketScan's document library assistant.
            Answer ONLY from the structured library results supplied below. Never invent facts.
            Respect the filters already applied by the local query engine.
            If an aggregate total is supplied, use it exactly and do not recalculate it from unrelated values.
            VERIFIED_TOTAL is authoritative when present: it was extracted from a line explicitly labelled as the document total.
            When the user asks for a total, never substitute an item price, subtotal, tax amount, or another monetary value.
            You may summarize, compare, count, and identify dates, suppliers, clients, categories and amounts.
            Respond in the user's language: $language. Keep the answer concise and useful.

            USER QUESTION:
            $question

            STRUCTURED LIBRARY RESULTS:
            $context
        """.trimIndent()

        val answer = model.generateContent(prompt).text?.trim()?.takeIf { it.isNotBlank() }
            ?: error("AI returned no answer")
        "Gemini conectado\n\n$answer"
    }

    private fun localAnswer(question: String, result: AiLibraryQueryEngine.Result): String {
        val language = languageCode()
        val normalizedQuestion = normalize(question)
        val asksCount = normalizedQuestion.contains("cuantas") ||
            normalizedQuestion.contains("cuantos") ||
            normalizedQuestion.contains("cantidad") ||
            normalizedQuestion.contains("count") ||
            normalizedQuestion.contains("how many")

        if (asksCount) {
            val answer = when (language) {
                "es" -> "Hay ${result.matches.size} documento${if (result.matches.size == 1) "" else "s"} que coincide${if (result.matches.size == 1) "" else "n"} con la consulta."
                "fr" -> "Il y a ${result.matches.size} document${if (result.matches.size == 1) "" else "s"} correspondant à la recherche."
                "de" -> "Es gibt ${result.matches.size} passende Dokumente."
                "it" -> "Ci sono ${result.matches.size} document${if (result.matches.size == 1) "o" else "i"} corrispondenti."
                "pt" -> "Há ${result.matches.size} documento${if (result.matches.size == 1) "" else "s"} correspondente${if (result.matches.size == 1) "" else "s"}."
                "ca" -> "Hi ha ${result.matches.size} document${if (result.matches.size == 1) "" else "s"} que coincideix${if (result.matches.size == 1) "" else "en"} amb la consulta."
                else -> "There are ${result.matches.size} matching documents."
            }
            return localPrefix(language) + answer
        }

        if (asksTotal(question)) {
            if (result.aggregateTotal != null && result.aggregateCurrency != null) {
                val formatted = "%.2f".format(Locale.US, result.aggregateTotal)
                val answer = when (language) {
                    "es" -> "Total de los ${result.matches.size} documentos encontrados: $formatted ${result.aggregateCurrency}."
                    else -> "Total for the ${result.matches.size} matching documents: $formatted ${result.aggregateCurrency}."
                }
                return localPrefix(language) + answer
            }

            if (result.matches.size == 1) {
                val match = result.matches.single()
                val total = match.total
                if (total != null) {
                    val formatted = "%.2f".format(Locale.US, total)
                    val amount = match.currency?.takeIf { it.isNotBlank() }?.let { "$formatted $it" } ?: formatted
                    val answer = if (language == "es") {
                        "Importe total del documento: $amount."
                    } else {
                        "Document total: $amount."
                    }
                    return localPrefix(language) + answer
                }
            }
        }

        val heading = when (language) {
            "es" -> "La IA en la nube no está disponible. Resultados locales:"
            "fr" -> "L’IA cloud n’est pas disponible. Résultats locaux:"
            "de" -> "Cloud-KI ist nicht verfügbar. Lokale Ergebnisse:"
            "it" -> "L’IA cloud non è disponible. Risultati locali:"
            "pt" -> "A IA na nuvem não está disponível. Resultados locais:"
            "ca" -> "La IA al núvol no està disponible. Resultats locals:"
            else -> "Cloud AI is unavailable. Local results:"
        }

        val lines = result.matches.take(20).mapIndexed { index, match ->
            val analysis = match.analysis
            val title = analysis?.title?.takeIf { it.isNotBlank() } ?: match.file.nameWithoutExtension
            val category = analysis?.category?.takeIf { it.isNotBlank() }
            val total = match.total?.let { amount ->
                val formatted = "%.2f".format(Locale.US, amount)
                match.currency?.takeIf { it.isNotBlank() }?.let { "$formatted $it" } ?: formatted
            }
            buildString {
                append(index + 1).append(". ").append(title)
                category?.let { append(" — ").append(it) }
                total?.let { append(" — Total: ").append(it) }
            }
        }.joinToString("\n")

        return "$heading\n\n$lines"
    }

    private fun asksTotal(question: String): Boolean {
        val normalized = normalize(question)
        return normalized.contains("total") ||
            normalized.contains("cuanto") ||
            normalized.contains("importe") ||
            normalized.contains("suma") ||
            normalized.contains("sum")
    }

    private suspend fun findReceiptTotalFromOcr(file: File): Pair<Double, String?>? {
        val ocr = File(file.parentFile, "${file.nameWithoutExtension}.txt")
        val text = if (ocr.isFile) runCatching { ocr.readText(Charsets.UTF_8) }.getOrDefault("") else ""
        findExplicitReceiptTotal(text)?.let { return it }

        // The document viewer already proved that a higher-resolution visual OCR pass
        // can recover totals that the saved OCR sidecar misses. The library assistant
        // now uses the same retry before accepting any ambiguous monetary value.
        val visual = runCatching { highResolutionOcr(file) }.getOrDefault("")
        if (visual.isNotBlank()) {
            findExplicitReceiptTotal(visual)?.let { return it }
        }

        // Never guess a total from the largest product price. If no explicit total can
        // be verified, return null so the UI reports that it could not verify the total.
        return null
    }

    private fun findExplicitReceiptTotal(text: String): Pair<Double, String?>? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null

        val amount = Regex("(?<!\\d)(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})|\\d+[.,]\\d{2})(?!\\d)")
        val totalStart = Regex("(?i)^(grand total|total due|amount due|balance due|total|importe total|importe final|total general|total factura|tutar|genel toplam)\\b")
        val standaloneTotal = Regex("(?i)^\\s*(grand total|total due|amount due|balance due|total|importe total|importe final|total general|total factura|tutar|genel toplam)\\s*[:.]?\\s*$")
        val negative = Regex("(?i)\\b(subtotal|sub total|tax|kdv|vat|change|cash|tip|discount|indirim)\\b")

        fun currency(line: String): String? = when {
            Regex("(?i)€|\\bEUR\\b").containsMatchIn(line) -> "EUR"
            Regex("(?i)\\$|\\bUSD\\b").containsMatchIn(line) -> "USD"
            Regex("(?i)£|\\bGBP\\b").containsMatchIn(line) -> "GBP"
            else -> null
        }

        fun parse(value: String): Double? {
            val s = value.replace(" ", "")
            return runCatching {
                when {
                    s.contains(',') && s.contains('.') ->
                        if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
                    s.count { it == ',' } == 1 && s.substringAfter(',').length == 2 -> s.replace(',', '.')
                    s.count { it == '.' } == 1 && s.substringAfter('.').length == 2 -> s
                    else -> s
                }.toDouble()
            }.getOrNull()
        }

        fun amounts(line: String): List<Double> = amount.findAll(line).mapNotNull { parse(it.value) }.toList()
        fun result(line: String, value: Double): Pair<Double, String?> = value to currency(line)

        lines.forEach { line ->
            if (negative.containsMatchIn(line)) return@forEach
            if (totalStart.containsMatchIn(normalize(line))) {
                amounts(line).lastOrNull()?.let { return result(line, it) }
            }
        }

        lines.forEachIndexed { index, line ->
            if (!standaloneTotal.matches(line) || negative.containsMatchIn(line)) return@forEachIndexed
            for (offset in 1..2) {
                val next = lines.getOrNull(index + offset) ?: break
                amounts(next).lastOrNull()?.let { return result(next, it) }
            }
        }

        // Some printers put TOTAL on the same line as dotted leaders. If the label is
        // present but not at column zero, accept it only when the line is otherwise short.
        lines.forEachIndexed { index, line ->
            val n = normalize(line)
            if (!n.contains("total") || negative.containsMatchIn(line) || n.length > 80) return@forEachIndexed
            amounts(line).lastOrNull()?.let { return result(line, it) }
            for (offset in 1..2) {
                val next = lines.getOrNull(index + offset) ?: break
                amounts(next).lastOrNull()?.let { return result(next, it) }
            }
        }

        return null
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

    private fun exactOcrTotalAnswer(total: Pair<Double, String?>): String {
        val formatted = "%.2f".format(Locale.US, total.first).replace('.', ',')
        val amount = total.second?.let { "$formatted $it" } ?: formatted
        return if (languageCode() == "es") {
            "Total verificado por OCR: $amount."
        } else {
            "OCR-verified total: $amount."
        }
    }

    private fun exactOcrAggregateAnswer(total: Double, currency: String?, count: Int): String {
        val formatted = "%.2f".format(Locale.US, total).replace('.', ',')
        val amount = currency?.let { "$formatted $it" } ?: formatted
        return if (languageCode() == "es") {
            "Total verificado por OCR de los $count documentos: $amount."
        } else {
            "OCR-verified total of $count documents: $amount."
        }
    }

    private fun localPrefix(language: String): String = when (language) {
        "es" -> "Modo local — Gemini no disponible\n\n"
        "fr" -> "Mode local — Gemini indisponible\n\n"
        "de" -> "Lokaler Modus — Gemini nicht verfügbar\n\n"
        "it" -> "Modalità locale — Gemini non disponibile\n\n"
        "pt" -> "Modo local — Gemini indisponível\n\n"
        "ca" -> "Mode local — Gemini no disponible\n\n"
        else -> "Local mode — Gemini unavailable\n\n"
    }

    private fun noDocumentsMessage(): String = when (languageCode()) {
        "es" -> "No hay documentos indexados todavía."
        "fr" -> "Aucun document n’est encore indexé."
        "de" -> "Noch keine Dokumente indiziert."
        "it" -> "Nessun documento indicizzato."
        "pt" -> "Ainda não existem documentos indexados."
        "ca" -> "Encara no hi ha documents indexats."
        else -> "There are no indexed documents yet."
    }

    private fun insufficientMessage(language: String): String = when (language) {
        "es" -> "No encuentro información suficiente en la biblioteca para responder a esa pregunta."
        "fr" -> "Je ne trouve pas suffisamment d’informations dans la bibliothèque pour répondre."
        "de" -> "Ich finde in der Bibliothek nicht genügend Informationen für diese Frage."
        "it" -> "Non trovo informazioni sufficienti nella libreria per rispondere."
        "pt" -> "Não encontro informação suficiente na biblioteca para responder."
        "ca" -> "No trobo prou informació a la biblioteca per respondre."
        else -> "I cannot find enough information in the library to answer that question."
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
        "ar" -> "Arabic"
        "nl" -> "Dutch"
        "pl" -> "Polish"
        "tr" -> "Turkish"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "zh" -> "Chinese"
        "ru" -> "Russian"
        else -> "English"
    }

    private fun normalize(value: String): String = Normalizer.normalize(
        value.lowercase(Locale.ROOT),
        Normalizer.Form.NFD
    ).replace("\\p{M}+".toRegex(), "")
}
