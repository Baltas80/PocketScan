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
            if (queryResult.matches.isEmpty()) return@runCatching insufficientMessage(languageCode())

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
            VERIFIED_TOTAL is authoritative when present: it was extracted from a line explicitly labelled as the document total in the local OCR.
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

    private fun localAnswer(
        question: String,
        result: AiLibraryQueryEngine.Result
    ): String {
        val language = languageCode()
        val normalizedQuestion = normalize(question)
        val asksTotal = normalizedQuestion.contains("total") || normalizedQuestion.contains("cuanto") || normalizedQuestion.contains("suma") || normalizedQuestion.contains("sum")
        val asksCount = normalizedQuestion.contains("cuantas") || normalizedQuestion.contains("cuantos") || normalizedQuestion.contains("cantidad") || normalizedQuestion.contains("count") || normalizedQuestion.contains("how many")

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

        if (asksTotal) {
            if (result.aggregateTotal != null && result.aggregateCurrency != null) {
                val formatted = "%.2f".format(Locale.US, result.aggregateTotal)
                val answer = when (language) {
                    "es" -> "Total de los ${result.matches.size} documentos encontrados: $formatted ${result.aggregateCurrency}."
                    "fr" -> "Total des ${result.matches.size} documents trouvés : $formatted ${result.aggregateCurrency}."
                    "de" -> "Gesamtsumme der ${result.matches.size} gefundenen Dokumente: $formatted ${result.aggregateCurrency}."
                    "it" -> "Totale dei ${result.matches.size} documenti trovati: $formatted ${result.aggregateCurrency}."
                    "pt" -> "Total dos ${result.matches.size} documentos encontrados: $formatted ${result.aggregateCurrency}."
                    "ca" -> "Total dels ${result.matches.size} documents trobats: $formatted ${result.aggregateCurrency}."
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
                    val answer = when (language) {
                        "es" -> "Importe total del documento: $amount."
                        else -> "Document total: $amount."
                    }
                    return localPrefix(language) + answer
                }
            }
        }

        val heading = when (language) {
            "es" -> "La IA en la nube no está disponible. Resultados locales:"
            "fr" -> "L’IA cloud n’est pas disponible. Résultats locaux :"
            "de" -> "Cloud-KI ist nicht verfügbar. Lokale Ergebnisse:"
            "it" -> "L’IA cloud non è disponibile. Risultati locali:"
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

    private fun normalize(value: String): String = java.text.Normalizer.normalize(
        value.lowercase(Locale.ROOT),
        java.text.Normalizer.Form.NFD
    ).replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
}
