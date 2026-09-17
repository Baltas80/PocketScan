package com.baltas80.pocketscan

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
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

            if (asksVerifiedFinancialQuestion(question)) {
                return@runCatching answerVerifiedFinancial(question, queryResult.matches)
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
            Monetary totals shown as VERIFIED_TOTAL are authoritative only when present.
            Do not derive or guess monetary values from raw OCR text or summaries.
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

    private fun asksVerifiedFinancialQuestion(question: String): Boolean {
        val normalized = normalize(question)
        return listOf(
            "total", "importe", "cuanto", "suma", "gaste", "gastado", "gasto",
            "mas caro", "más caro", "mas cara", "más cara", "expensive", "spent", "iva", "vat"
        ).any { normalized.contains(it) }
    }

    private fun answerVerifiedFinancial(question: String, matches: List<AiLibraryQueryEngine.Match>): String {
        val normalized = normalize(question)
        if (normalized.contains("iva") || normalized.contains("vat")) {
            val amounts = matches.map { parseExplicitCurrencyAmount(it.analysis?.fields?.get("iva")) }
            if (amounts.any { it == null }) return insufficientMessage(languageCode())
            val nonNull = amounts.filterNotNull()
            val currencies = nonNull.map { it.second }.distinct()
            if (currencies.size != 1) return insufficientMessage(languageCode())
            val total = nonNull.sumOf { it.first }
            return verifiedAmountAnswer(total, currencies.single(), "IVA verificado")
        }

        if (normalized.contains("mas caro") || normalized.contains("más caro") ||
            normalized.contains("mas cara") || normalized.contains("más cara") || normalized.contains("expensive")) {
            if (matches.any { it.total == null || it.currency == null }) return insufficientMessage(languageCode())
            val currencies = matches.map { it.currency!! }.distinct()
            if (currencies.size != 1) return insufficientMessage(languageCode())
            val candidate = matches.maxByOrNull { it.total!! } ?: return insufficientMessage(languageCode())
            val name = candidate.analysis?.title?.takeIf { it.isNotBlank() } ?: candidate.file.name
            return if (languageCode() == "es") {
                "Factura más cara entre los documentos verificados: $name — ${format(candidate.total!!)} ${candidate.currency}."
            } else {
                "Most expensive invoice among verified documents: $name — ${format(candidate.total!!)} ${candidate.currency}."
            }
        }

        if (matches.any { it.total == null || it.currency == null }) return insufficientMessage(languageCode())
        val currencies = matches.map { it.currency!! }.distinct()
        if (currencies.size != 1) return insufficientMessage(languageCode())
        val total = matches.sumOf { it.total!! }
        val label = if (matches.size == 1) "Total verificado" else "Total verificado de ${matches.size} documentos"
        return verifiedAmountAnswer(total, currencies.single(), label)
    }

    private fun parseExplicitCurrencyAmount(value: String?): Pair<Double, String>? {
        if (value.isNullOrBlank() || value.contains('%')) return null
        val currency = when {
            value.contains("EUR", true) || value.contains('€') -> "EUR"
            value.contains("USD", true) || value.contains('$') -> "USD"
            value.contains("GBP", true) || value.contains('£') -> "GBP"
            else -> return null
        }
        val raw = Regex("[+-]?[0-9][0-9.,\\s]*").find(value)?.value ?: return null
        val normalized = raw.replace("\\s".toRegex(), "")
        val number = runCatching {
            when {
                normalized.contains(',') && normalized.contains('.') -> {
                    if (normalized.lastIndexOf(',') > normalized.lastIndexOf('.')) {
                        normalized.replace(".", "").replace(',', '.')
                    } else normalized.replace(",", "")
                }
                normalized.count { it == ',' } == 1 && normalized.substringAfter(',').length <= 2 -> normalized.replace(',', '.')
                normalized.count { it == '.' } > 1 -> normalized.replace(".", "")
                else -> normalized
            }.toDouble()
        }.getOrNull() ?: return null
        return number to currency
    }

    private fun verifiedAmountAnswer(total: Double, currency: String, label: String): String =
        "$label: ${format(total)} $currency."

    private fun format(value: Double): String = "%.2f".format(Locale.US, value).replace('.', ',')

    private fun cloudFailure(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.lastOrNull() ?: error
        val detail = root.message?.trim().orEmpty().take(500).ifBlank { root::class.java.simpleName }
        return when (languageCode()) {
            "es" -> "Gemini no está disponible.\n\nDiagnóstico: $detail"
            else -> "Gemini is unavailable.\n\nDiagnostic: $detail"
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
        "es" -> "No encuentro información suficiente en la biblioteca para responder con seguridad."
        "fr" -> "Je ne trouve pas suffisamment d’informations dans la bibliothèque pour répondre avec fiabilité."
        "de" -> "Ich finde in der Bibliothek nicht genügend Informationen für eine verlässliche Antwort."
        "it" -> "Non trovo informazioni sufficienti nella libreria per rispondere con affidabilità."
        "pt" -> "Não encontro informação suficiente na biblioteca para responder com segurança."
        "ca" -> "No trobo prou informació a la biblioteca per respondre amb seguretat."
        else -> "I cannot find enough verified information in the library to answer safely."
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

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .replace("ñ", "n")
}
