package com.baltas80.pocketscan

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object AiLibraryAssistant {
    suspend fun ask(filesDir: File, question: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val scans = File(filesDir, "scans")
            if (!scans.isDirectory) return@runCatching noDocumentsMessage()

            val documents = scans.walkTopDown()
                .filter { it.isFile && it.extension.equals("pdf", true) }
                .toList()
            if (documents.isEmpty()) return@runCatching noDocumentsMessage()

            val queryResult = AiLibraryQueryEngine.query(question, documents)
            if (queryResult.matches.isEmpty()) {
                return@runCatching insufficientMessage(languageCode())
            }

            // Totals are accounting data. Verify them independently of the language
            // model and never accept an item price merely because the OCR contains the
            // word TOTAL somewhere in a product description.
            if (asksTotal(question)) {
                val verified = documents.mapNotNull { file ->
                    val textFile = File(file.parentFile, "${file.nameWithoutExtension}.txt")
                    if (!textFile.isFile) return@mapNotNull null
                    val text = runCatching { textFile.readText(Charsets.UTF_8) }.getOrDefault("")
                    ReceiptTotalExtractor.extract(text)?.let { file to it }
                }

                if (verified.size == 1) {
                    return@runCatching exactOcrTotalAnswer(verified.single().second)
                }
                if (verified.size > 1) {
                    val currencies = verified.map { it.second.currency }.distinct()
                    if (currencies.size == 1) {
                        val sum = verified.sumOf { it.second.amount }
                        return@runCatching exactOcrAggregateAnswer(sum, currencies.single(), verified.size)
                    }
                }
            }

            val context = AiLibraryQueryEngine.buildContext(queryResult).take(30000)
            tryCloud(question, context).getOrElse { error ->
                cloudFailure(error)
            }
        }
    }

    private suspend fun tryCloud(question: String, context: String): Result<String> = runCatching {
        val model: GenerativeModel = Firebase.ai(
            backend = GenerativeBackend.googleAI(),
            useLimitedUseAppCheckTokens = true
        ).generativeModel(AiModelConfig.modelName())

        val prompt = """
            You are PocketScan's document library assistant.
            Answer ONLY from the structured library results supplied below. Never invent facts.
            Respect the filters already applied by the local query engine.
            For totals, use only an explicitly verified document total. Never substitute an
            item price, subtotal, tax amount, cash received or change.
            Respond in ${languageName()} and keep the answer concise.

            USER QUESTION:
            $question

            STRUCTURED LIBRARY RESULTS:
            $context
        """.trimIndent()

        val answer = model.generateContent(prompt).text?.trim()?.takeIf { it.isNotBlank() }
            ?: error("AI returned no answer")
        "Gemini conectado\n\n$answer"
    }

    private fun cloudFailure(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.lastOrNull() ?: error
        val detail = root.message?.trim().orEmpty().take(500).ifBlank { root::class.java.simpleName }
        return when (languageCode()) {
            "es" -> "Gemini no está disponible.\n\nDiagnóstico: $detail"
            else -> "Gemini is unavailable.\n\nDiagnostic: $detail"
        }
    }

    private fun asksTotal(question: String): Boolean {
        val normalized = normalize(question)
        return normalized.contains("total") ||
            normalized.contains("cuanto") ||
            normalized.contains("importe") ||
            normalized.contains("suma") ||
            normalized.contains("sum")
    }

    private fun exactOcrTotalAnswer(total: ReceiptTotalExtractor.Total): String {
        val formatted = "%.2f".format(Locale.US, total.amount).replace('.', ',')
        val amount = total.currency?.let { "$formatted $it" } ?: formatted
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
        else -> Locale.getDefault().displayLanguage
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
}
