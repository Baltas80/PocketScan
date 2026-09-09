package com.baltas80.pocketscan

import org.json.JSONObject
import java.io.File

/** Persists document-intelligence results beside each PDF without exposing them in the UI. */
object AiMetadataStore {
    private const val VERSION = 1

    fun sidecarFor(document: File): File =
        File(document.parentFile ?: document.parentFile, "${document.nameWithoutExtension}.ai.json")

    fun save(document: File, analysis: AiDocumentAnalyzer.Analysis): Boolean = runCatching {
        val sidecar = sidecarFor(document)
        sidecar.parentFile?.mkdirs()
        val fields = JSONObject()
        analysis.fields.forEach { (key, value) -> fields.put(key, value) }
        val json = JSONObject()
            .put("version", VERSION)
            .put("source", analysis.source)
            .put("category", analysis.category)
            .put("title", analysis.title)
            .put("summary", analysis.summary)
            .put("fields", fields)
            .put("updatedAt", System.currentTimeMillis())
        sidecar.writeText(json.toString(), Charsets.UTF_8)
        true
    }.getOrDefault(false)

    fun load(document: File): AiDocumentAnalyzer.Analysis? = runCatching {
        val sidecar = sidecarFor(document)
        if (!sidecar.isFile) return null
        val json = JSONObject(sidecar.readText(Charsets.UTF_8))
        val fieldsJson = json.optJSONObject("fields")
        val fields = linkedMapOf<String, String>()
        fieldsJson?.keys()?.forEach { key ->
            val value = fieldsJson.optString(key).trim()
            if (value.isNotEmpty()) fields[key] = value
        }
        AiDocumentAnalyzer.Analysis(
            category = json.optString("category", DocumentOrganizer.GENERAL),
            title = json.optString("title", document.nameWithoutExtension),
            summary = json.optString("summary", ""),
            fields = fields,
            source = json.optString("source", "local")
        )
    }.getOrNull()

    fun delete(document: File): Boolean = runCatching {
        val sidecar = sidecarFor(document)
        !sidecar.exists() || sidecar.delete()
    }.getOrDefault(false)
}
