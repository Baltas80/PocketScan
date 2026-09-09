package com.baltas80.pocketscan

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeBackend
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

object AiLibraryAssistant {
    suspend fun ask(context: Context, question: String): String = withContext(Dispatchers.IO) {
        val documents = DocumentsRepository.list(context)
        val result = AiLibraryQueryEngine.query(question, documents)
        val localContext = result.matches.joinToString("\n") { document ->
            "- ${document.name} | ${document.category ?: ""} | ${document.date ?: ""} | ${document.amount ?: ""} ${document.currency ?: ""}"
        }
        tryCloud(question, localContext).getOrElse { localAnswer(question, result, localContext) }
    }

    private suspend fun tryCloud(question: String, context: String): Result<String> = runCatching {
        val model: GenerativeModel = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(AiModelConfig.modelName())
        val language = languageName()
        val prompt = """
            You are PocketScan's document library assistant.
            Answer in $language using only the supplied library context.
            If the context does not contain enough information, say so clearly.
            Be concise and factual.
            User question: $question
            Library context:
            $context
        """.trimIndent()
        model.generateContent(prompt).text?.trim()?.takeIf { it.isNotBlank() } ?: error("AI returned no answer")
    }

    private fun localAnswer(
        question: String,
        result: AiLibraryQueryEngine.Result,
        context: String
    ): String {
        val language = languageCode()
        val normalizedQuestion = normalize(question)
        val asksTotal = normalizedQuestion.contains("total") || normalizedQuestion.contains("cuanto") || normalizedQuestion.contains("suma") || normalizedQuestion.contains("sum")
        if (asksTotal && result.matches.isNotEmpty()) {
            val formatted = NumberFormat.getNumberInstance(Locale.getDefault()).format(result.aggregateAmount)
            return when (language) {
                "es" -> "Total de los ${result.matches.size} documentos encontrados: $formatted ${result.aggregateCurrency}."
                "fr" -> "Total des ${result.matches.size} documents trouvés : $formatted ${result.aggregateCurrency}."
                "de" -> "Gesamtsumme der ${result.matches.size} gefundenen Dokumente: $formatted ${result.aggregateCurrency}."
                "it" -> "Totale dei ${result.matches.size} documenti trovati: $formatted ${result.aggregateCurrency}."
                "pt" -> "Total dos ${result.matches.size} documentos encontrados: $formatted ${result.aggregateCurrency}."
                "ca" -> "Total dels ${result.matches.size} documents trobats: $formatted ${result.aggregateCurrency}."
                else -> "Total for the ${result.matches.size} matching documents: $formatted ${result.aggregateCurrency}."
            }
        }
        val heading = when (language) {
            "es" -> "La IA en la nube no está disponible. Resultados locales:"
            "fr" -> "L’IA cloud n’est pas disponible. Résultats locaux :"
            "de" -> "Cloud-KI ist nicht verfügbar. Lokale Ergebnisse:"
            "it" -> "L’IA cloud non è disponibile. Risultati locali:"
            "pt" -> "A IA na nuvem não está disponible. Resultados locais:"
            "ca" -> "La IA al núvol no està disponible. Resultats locals:"
            else -> "Cloud AI is unavailable. Local results:"
        }
        return "$heading\n$context"
    }

    private fun languageName(): String = when (languageCode()) {
        "es" -> "Spanish (Spain)"
        "fr" -> "French"
        "de" -> "German"
        "it" -> "Italian"
        "pt" -> "Portuguese"
        "ca" -> "Catalan"
        else -> "English"
    }

    private fun languageCode(): String = Locale.getDefault().language.lowercase(Locale.ROOT)

    private fun normalize(value: String): String = java.text.Normalizer.normalize(
        value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD
    ).replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
}
