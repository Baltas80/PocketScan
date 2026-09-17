package com.baltas80.pocketscan

import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AiMetadataStore {
    private const val VERSION = 1

    fun sidecarFor(document: File): File = File(document.parentFile ?: document, "${document.nameWithoutExtension}.ai.json")

    fun save(document: File, analysis: AiDocumentAnalyzer.Analysis): Boolean {
        if (!document.isFile) return false
        return runCatching {
            val sidecar = sidecarFor(document)
            sidecar.parentFile?.mkdirs()
            val fields = JSONObject()
            analysis.fields.forEach { (key, value) -> fields.put(key, value) }
            val json = JSONObject().put("version", VERSION).put("source", analysis.source).put("category", analysis.category)
                .put("title", analysis.title).put("summary", analysis.summary).put("fields", fields)
                .put("updatedAt", System.currentTimeMillis()).toString()

            val temp = File(sidecar.parentFile ?: document, sidecar.name + ".tmp")
            temp.writeText(json, Charsets.UTF_8)
            try {
                if (!document.isFile) return false
                try {
                    Files.move(temp.toPath(), sidecar.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), sidecar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
            true
        }.getOrDefault(false)
    }

    fun load(document: File): AiDocumentAnalyzer.Analysis? = runCatching {
        if (!document.isFile) return null

        val sidecar = sidecarFor(document)
        val json = if (sidecar.isFile) JSONObject(sidecar.readText(Charsets.UTF_8)) else null
        val fields = linkedMapOf<String, String>()
        json?.optJSONObject("fields")?.keys()?.forEach { key ->
            json.optJSONObject("fields")?.optString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { fields[key] = it }
        }

        // Do not re-derive financial totals from the flattened OCR TXT sidecar here.
        // That path loses geometry and can resurrect the historical "TOTAL 5,00"
        // failure after a spatially verified value was already persisted. Monetary
        // verification is performed during analysis and its provenance is stored in
        // the metadata source field.
        AiDocumentAnalyzer.Analysis(
            json?.optString("category", DocumentOrganizer.GENERAL) ?: DocumentOrganizer.GENERAL,
            json?.optString("title", document.nameWithoutExtension) ?: document.nameWithoutExtension,
            json?.optString("summary", "") ?: "",
            fields,
            json?.optString("source", "local") ?: "local"
        )
    }.getOrNull()

    private fun normalizeCurrency(value: String): String = when (value.uppercase()) {
        "€", "EUR" -> "EUR"
        "$", "USD" -> "USD"
        "£", "GBP" -> "GBP"
        else -> value
    }

    fun delete(document: File): Boolean = runCatching {
        val sidecar = sidecarFor(document)
        val temp = File(sidecar.parentFile ?: document, sidecar.name + ".tmp")
        val sidecarDeleted = !sidecar.exists() || sidecar.delete()
        val tempDeleted = !temp.exists() || temp.delete()
        sidecarDeleted && tempDeleted
    }.getOrDefault(false)
}
