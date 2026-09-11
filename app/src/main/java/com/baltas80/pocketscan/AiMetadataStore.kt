package com.baltas80.pocketscan

import org.json.JSONObject
import java.io.File

object AiMetadataStore {
    private const val VERSION = 1

    fun sidecarFor(document: File): File = File(document.parentFile ?: document, "${document.nameWithoutExtension}.ai.json")

    fun save(document: File, analysis: AiDocumentAnalyzer.Analysis): Boolean = runCatching {
        val sidecar = sidecarFor(document)
        sidecar.parentFile?.mkdirs()
        val fields = JSONObject()
        analysis.fields.forEach { (key, value) -> fields.put(key, value) }
        val json = JSONObject().put("version", VERSION).put("source", analysis.source).put("category", analysis.category)
            .put("title", analysis.title).put("summary", analysis.summary).put("fields", fields)
            .put("updatedAt", System.currentTimeMillis()).toString()

        val temp = File(sidecar.parentFile ?: document, sidecar.name + ".tmp")
        temp.writeText(json, Charsets.UTF_8)
        if (!temp.renameTo(sidecar)) {
            temp.delete()
            return false
        }
        true
    }.getOrDefault(false)

    fun load(document: File): AiDocumentAnalyzer.Analysis? = runCatching {
        val sidecar = sidecarFor(document)
        if (!sidecar.isFile) return null
        val json = JSONObject(sidecar.readText(Charsets.UTF_8))
        val fieldsJson = json.optJSONObject("fields")
        val fields = linkedMapOf<String, String>()
        fieldsJson?.keys()?.forEach { key -> fieldsJson.optString(key).trim().takeIf { it.isNotEmpty() }?.let { fields[key] = it } }
        AiDocumentAnalyzer.Analysis(json.optString("category", DocumentOrganizer.GENERAL), json.optString("title", document.nameWithoutExtension), json.optString("summary", ""), fields, json.optString("source", "local"))
    }.getOrNull()

    fun delete(document: File): Boolean = runCatching { !sidecarFor(document).exists() || sidecarFor(document).delete() }.getOrDefault(false)
}
