package com.baltas80.pocketscan

import java.io.File
import java.util.Locale

/** Fast local search over filenames, OCR and persisted AI metadata. */
object AiLibrarySearch {
    data class Match(
        val document: File,
        val score: Int,
        val analysis: AiDocumentAnalyzer.Analysis?
    )

    fun search(scansDir: File, query: String, limit: Int = 20): List<Match> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank() || !scansDir.isDirectory) return emptyList()
        val tokens = normalizedQuery.split(" ").filter { it.length >= 2 }.distinct()
        if (tokens.isEmpty()) return emptyList()

        return scansDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("pdf", true) }
            .mapNotNull { document ->
                val analysis = AiMetadataStore.load(document)
                val ocr = readOcr(document)
                val haystack = normalize(buildString {
                    append(document.nameWithoutExtension).append(' ')
                    analysis?.let {
                        append(it.category).append(' ')
                        append(it.title).append(' ')
                        append(it.summary).append(' ')
                        it.fields.forEach { (key, value) -> append(key).append(' ').append(value).append(' ') }
                    }
                    append(ocr)
                })
                val score = score(haystack, tokens, analysis, normalizedQuery)
                if (score > 0) Match(document, score, analysis) else null
            }
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.document.name.lowercase(Locale.ROOT) })
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    private fun score(
        haystack: String,
        tokens: List<String>,
        analysis: AiDocumentAnalyzer.Analysis?,
        fullQuery: String
    ): Int {
        var score = 0
        if (haystack.contains(fullQuery)) score += 10
        tokens.forEach { token ->
            if (haystack.contains(token)) score += 2
            analysis?.let {
                if (normalize(it.title).contains(token)) score += 4
                if (normalize(it.category).contains(token)) score += 3
                if (normalize(it.summary).contains(token)) score += 2
            }
        }
        return score
    }

    private fun readOcr(document: File): String = runCatching {
        val file = File(document.parentFile, document.nameWithoutExtension + ".txt")
        if (file.isFile) file.readText(Charsets.UTF_8).take(120_000) else ""
    }.getOrDefault("")

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[áàäâ]"), "a")
            .replace(Regex("[éèëê]"), "e")
            .replace(Regex("[íìïî]"), "i")
            .replace(Regex("[óòöô]"), "o")
            .replace(Regex("[úùüû]"), "u")
            .replace('ñ', 'n')
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
