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
        val sidecar = sidecarFor(document)
        if (!sidecar.isFile) return null
        val json = JSONObject(sidecar.readText(Charsets.UTF_8))
        val fieldsJson = json.optJSONObject("fields")
        val fields = linkedMapOf<String, String>()
        fieldsJson?.keys()?.forEach { key -> fieldsJson.optString(key).trim().takeIf { it.isNotEmpty() }?.let { fields[key] = it } }

        val ocr = File(document.parentFile ?: document, "${document.nameWithoutExtension}.txt")
        val verified = if (ocr.isFile) findExplicitTotal(ocr.readText(Charsets.UTF_8)) else null
        if (verified != null) {
            fields["total"] = verified.first
            verified.second?.let { fields["moneda"] = it }
        }

        val source = json.optString("source", "local") + if (verified != null) "+ocr-verified" else ""
        AiDocumentAnalyzer.Analysis(
            json.optString("category", DocumentOrganizer.GENERAL),
            json.optString("title", document.nameWithoutExtension),
            json.optString("summary", ""),
            fields,
            source
        )
    }.getOrNull()

    private fun findExplicitTotal(ocrText: String): Pair<String, String?>? {
        val label = Regex("(?i)^\\s*(?:total(?:\\s+a\\s+pagar)?|importe\\s+(?:total|final)|total\\s+general|total\\s+factura)\\s*(?:[.·:_-]\\s*)*")
        val amount = Regex("(?i)([0-9]{1,3}(?:[.][0-9]{3})*(?:,[0-9]{1,2})|[0-9]+(?:[.,][0-9]{1,2}))(?:\\s*(€|EUR|USD|\\$|GBP|£))?\\s*$")
        val currency = Regex("(?i)(€|EUR|USD|\\$|GBP|£)")
        for (line in ocrText.lineSequence()) {
            val clean = line.trim()
            val labelMatch = label.find(clean) ?: continue
            val suffix = clean.substring(labelMatch.range.last + 1).trim()
            val match = amount.find(suffix) ?: continue
            val value = match.groupValues[1]
            val unit = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                ?: currency.find(suffix)?.groupValues?.getOrNull(1)
            return value to unit?.let { normalizeCurrency(it) }
        }
        return null
    }

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
