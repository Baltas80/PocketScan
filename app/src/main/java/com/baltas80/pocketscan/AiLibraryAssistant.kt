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
            val context = buildContext(scans, question)
            if (context.isBlank()) return@runCatching noDocumentsMessage()
            tryCloud(question, context).getOrElse { localAnswer(question, context) }
        }
    }

    private suspend fun tryCloud(question: String, context: String): Result<String> = runCatching {
        val model: GenerativeModel = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel("gemini-3.7-flash")
        val language = languageName()
        val prompt = """
            You are PocketScan's document assistant.
            Answer only using the provided library information.
            If the information is insufficient, say so clearly and never invent facts.
            You may compare documents and identify dates, amounts, categories and extracted fields.
            Respond in the user's language: $language. Keep the answer concise.

            QUESTION:
            $question

            LIBRARY:
            $context
        """.trimIndent()
        model.generateContent(prompt).text?.trim()?.takeIf { it.isNotBlank() } ?: error("AI returned no answer")
    }

    private fun buildContext(scans: File, question: String): String {
        if (!scans.isDirectory) return ""
        val queryTokens = normalize(question).split(" ").filter { it.length >= 3 && it !in STOP_WORDS }.toSet()
        return scans.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) }.mapNotNull { pdf ->
            val analysis = AiMetadataStore.load(pdf)
            val ocr = File(pdf.parentFile, pdf.nameWithoutExtension + ".txt").takeIf { it.isFile }
                ?.let { runCatching { it.readText(Charsets.UTF_8).take(4000) }.getOrDefault("") }.orEmpty()
            val searchable = normalize(listOf(pdf.name, analysis?.title, analysis?.category, analysis?.summary, analysis?.fields?.values?.joinToString(" "), ocr).joinToString(" "))
            val score = queryTokens.count { searchable.contains(it) }
            if (score == 0 && queryTokens.isNotEmpty()) null else buildString {
                append("DOCUMENT: ${pdf.name}\n")
                analysis?.let {
                    append("Category: ${it.category}\nTitle: ${it.title}\nSummary: ${it.summary}\n")
                    if (it.fields.isNotEmpty()) append("Fields: ${it.fields.entries.joinToString { e -> "${e.key}=${e.value}" }}\n")
                }
                if (ocr.isNotBlank()) append("OCR: $ocr\n")
            }
        }.sortedByDescending { block -> queryTokens.count { normalize(block).contains(it) } }.take(8).joinToString("\n---\n").take(30000)
    }

    private fun localAnswer(question: String, context: String): String {
        val lines = context.split("\n---\n")
        val tokens = normalize(question).split(" ").filter { it.length >= 3 && it !in STOP_WORDS }
        val matches = lines.filter { block -> tokens.any { normalize(block).contains(it) } }.take(3)
        val language = Locale.getDefault().language.lowercase(Locale.ROOT)
        if (matches.isEmpty()) return insufficientMessage(language)
        val prefix = when (language) {
            "es" -> "La IA en la nube no está disponible. Documentos locales relacionados:"
            "fr" -> "L’IA cloud n’est pas disponible. Documents locaux associés :"
            "de" -> "Cloud-KI ist nicht verfügbar. Verwandte lokale Dokumente:"
            "it" -> "L’IA cloud non è disponibile. Documenti locali correlati:"
            "pt" -> "A IA na nuvem não está disponível. Documentos locais relacionados:"
            "ca" -> "La IA al núvol no està disponible. Documents locals relacionats:"
            else -> "Cloud AI is unavailable. Related local documents:"
        }
        return prefix + "\n\n" + matches.joinToString("\n\n---\n\n")
    }

    private fun noDocumentsMessage(): String = when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
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

    private fun languageName(): String = when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
        "es" -> "Spanish"
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

    private fun normalize(value: String): String = java.text.Normalizer.normalize(value.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD).replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")

    private val STOP_WORDS = setOf("the", "and", "for", "with", "what", "which", "this", "that", "from", "para", "con", "que", "las", "los", "una", "uno", "por", "del", "como", "est", "des", "les", "une", "pour", "und", "der", "die", "das", "mit", "ein", "eine", "per", "gli", "che", "uma", "com", "dos")
}
