package com.baltas80.pocketscan

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeBackend
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AiLibraryAssistant {
    suspend fun ask(filesDir: File, question: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val scans = File(filesDir, "scans")
            val context = buildContext(scans, question)
            if (context.isBlank()) return@runCatching "No hay documentos indexados todavía."
            tryCloud(question, context).getOrElse { localAnswer(question, context) }
        }
    }

    private suspend fun tryCloud(question: String, context: String): Result<String> = runCatching {
        val model: GenerativeModel = Firebase.ai(
            backend = GenerativeBackend.googleAI()
        ).generativeModel("gemini-3.8-flash")
        val prompt = """
            Eres el asistente documental de PocketScan.
            Responde únicamente usando la información de la biblioteca proporcionada.
            Si la información no permite responder, dilo claramente y no inventes datos.
            Puedes comparar documentos y señalar fechas, importes, categorías o datos extraídos.
            Responde en español y de forma concisa.

            PREGUNTA:
            $question

            BIBLIOTECA:
            $context
        """.trimIndent()
        model.generateContent(prompt).text?.trim()?.takeIf { it.isNotBlank() }
            ?: error("La IA no devolvió respuesta")
    }

    private fun buildContext(scans: File, question: String): String {
        if (!scans.isDirectory) return ""
        val queryTokens = normalize(question).split(" ").filter { it.length >= 3 }.toSet()
        return scans.walkTopDown()
            .filter { it.isFile && it.extension.equals("pdf", true) }
            .mapNotNull { pdf ->
                val analysis = AiMetadataStore.load(pdf)
                val ocr = File(pdf.parentFile, pdf.nameWithoutExtension + ".txt")
                    .takeIf { it.isFile }
                    ?.let { runCatching { it.readText(Charsets.UTF_8).take(4000) }.getOrDefault("") }
                    .orEmpty()
                val searchable = normalize(listOf(pdf.name, analysis?.title, analysis?.category, analysis?.summary, analysis?.fields?.values?.joinToString(" "), ocr).joinToString(" "))
                val score = queryTokens.count { searchable.contains(it) }
                if (score == 0 && queryTokens.isNotEmpty()) null
                else buildString {
                    append("DOCUMENTO: ${pdf.name}\n")
                    analysis?.let {
                        append("Categoría: ${it.category}\nTítulo: ${it.title}\nResumen: ${it.summary}\n")
                        if (it.fields.isNotEmpty()) append("Campos: ${it.fields.entries.joinToString { e -> "${e.key}=${e.value}" }}\n")
                    }
                    if (ocr.isNotBlank()) append("OCR: $ocr\n")
                }
            }
            .take(8)
            .joinToString("\n---\n")
            .take(30000)
    }

    private fun localAnswer(question: String, context: String): String {
        val lines = context.split("\n---\n")
        val tokens = normalize(question).split(" ").filter { it.length >= 3 }
        val matches = lines.filter { block -> tokens.any { normalize(block).contains(it) } }.take(3)
        return if (matches.isEmpty()) {
            "No encuentro información suficiente en la biblioteca para responder a esa pregunta."
        } else {
            "No hay conexión con la IA en la nube. Estos son los documentos locales relacionados:\n\n" +
                matches.joinToString("\n\n---\n\n")
        }
    }

    private fun normalize(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
}
