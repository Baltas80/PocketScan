package com.baltas80.pocketscan

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object AiLibraryAssistantV2 {
    suspend fun answer(query: String, documents: List<File>): String = withContext(Dispatchers.IO) {
        val result = AiLibraryQueryEngine.query(query, documents)
        if (result.matches.isEmpty()) return@withContext "No he encontrado documentos que coincidan con la consulta."
        val context = AiLibraryQueryEngine.buildContext(result)
        runCatching {
            val model: GenerativeModel = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel("gemini-3.8-flash")
            val prompt = """
                You are PocketScan's private document-library assistant.
                Answer ONLY from the structured library context below. Never invent documents, amounts, dates, suppliers or other facts.
                If the context is insufficient, say so clearly. Answer in ${languageName()} and be concise.
                If an aggregate total is supplied, use it exactly and mention the currency.
                When useful, list matching filenames.

                USER QUERY:
                $query

                LIBRARY CONTEXT:
                $context
            """.trimIndent()
            model.generateContent(prompt).text?.trim().takeIf { !it.isNullOrEmpty() }
        }.getOrElse { buildLocalFallback(result) } ?: buildLocalFallback(result)
    }

    private fun buildLocalFallback(result: AiLibraryQueryEngine.Result): String = buildString {
        append("He encontrado ").append(result.matches.size).append(" documento(s).")
        result.aggregateTotal?.let { append(" El total es ").append("%.2f".format(Locale.US, it)).append(' ').append(result.aggregateCurrency.orEmpty()).append('.') }
        append(" Documentos: ").append(result.matches.take(10).joinToString(", ") { it.file.name })
        if (result.matches.size > 10) append(" y ").append(result.matches.size - 10).append(" más")
        append('.')
    }

    private fun languageName(): String = when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
        "es" -> "Spanish"; "en" -> "English"; "fr" -> "French"; "de" -> "German";
        "it" -> "Italian"; "pt" -> "Portuguese"; "ca" -> "Catalan"; else -> Locale.getDefault().displayLanguage
    }
}
