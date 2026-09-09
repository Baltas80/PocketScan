package com.baltas80.pocketscan

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Document intelligence layer.
 *
 * Cloud analysis sends the original PDF to Gemini so layout, tables and visual
 * information can be considered. OCR is supplied as auxiliary context. If cloud
 * AI is unavailable, PocketScan falls back to deterministic local extraction.
 */
object AiDocumentAnalyzer {
    data class Analysis(
        val category: String,
        val title: String,
        val summary: String,
        val fields: Map<String, String>,
        val source: String = "local"
    )

    private val documentSchema = Schema.obj(
        mapOf(
            "category" to Schema.enumeration(
                listOf("FACTURAS", "PRESUPUESTOS", "CONTRATOS", "RECIBOS", "TICKETS", "NOMINAS", "CERTIFICADOS", "INFORMES", "CITAS", "GENERAL")
            ),
            "title" to Schema.string(),
            "summary" to Schema.string(),
            "proveedor" to Schema.string(),
            "cliente" to Schema.string(),
            "nif_cif" to Schema.string(),
            "numero" to Schema.string(),
            "fecha" to Schema.string(),
            "vencimiento" to Schema.string(),
            "subtotal" to Schema.string(),
            "iva" to Schema.string(),
            "total" to Schema.string(),
            "moneda" to Schema.string(),
            "periodo" to Schema.string(),
            "direccion" to Schema.string(),
            "telefono" to Schema.string(),
            "concepto" to Schema.string()
        ),
        optionalProperties = listOf(
            "proveedor", "cliente", "nif_cif", "numero", "fecha", "vencimiento",
            "subtotal", "iva", "total", "moneda", "periodo", "direccion", "telefono", "concepto"
        )
    )

    suspend fun analyze(file: File, ocrText: String = ""): Result<Analysis> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { "Document not found" }
            val text = ocrText.trim()
            tryCloudAnalysis(file, text).getOrElse { localAnalysis(file, text) }
        }
    }

    private suspend fun tryCloudAnalysis(file: File, ocrText: String): Result<Analysis> = runCatching {
        val model: GenerativeModel = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(
                modelName = "gemini-3.8-flash",
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                    responseSchema = documentSchema
                }
            )

        val auxiliaryOcr = ocrText.take(12000)
        val prompt = content {
            inlineData(file.readBytes(), "application/pdf")
            text(
                """
                You are PocketScan's document intelligence engine. Analyze the complete PDF, including layout, tables and visible values. OCR text below is only auxiliary context.
                Return the requested JSON schema. Never invent data. For absent fields, leave them empty.
                Extract only data actually present in the document. Preserve exact amounts, dates, identifiers and names when readable.
                Category must be the closest allowed category. Write title and summary in ${languageName()}.
                For monetary values, preserve the document's displayed decimal separator and currency when possible.

                AUXILIARY OCR:
                $auxiliaryOcr
                """.trimIndent()
            )
        }

        val response = model.generateContent(prompt)
        val json = JSONObject(response.text ?: error("AI returned no content"))
        val fields = linkedMapOf<String, String>()
        listOf(
            "proveedor", "cliente", "nif_cif", "numero", "fecha", "vencimiento",
            "subtotal", "iva", "total", "moneda", "periodo", "direccion", "telefono", "concepto"
        ).forEach { key ->
            json.optString(key).trim().takeIf { it.isNotEmpty() }?.let { fields[key] = it }
        }
        Analysis(
            normalizeCategory(json.optString("category")),
            json.optString("title", "Document").ifBlank { "Document" },
            json.optString("summary", "").trim(),
            fields,
            "gemini"
        )
    }

    private fun localAnalysis(file: File, text: String): Analysis {
        val category = DocumentOrganizer.categoryForText(text)
        val type = when (category) {
            DocumentOrganizer.FACTURAS -> "Factura"
            DocumentOrganizer.PRESUPUESTOS -> "Presupuesto"
            DocumentOrganizer.CONTRATOS -> "Contrato"
            DocumentOrganizer.RECIBOS -> "Recibo"
            DocumentOrganizer.TICKETS -> "Ticket"
            DocumentOrganizer.NOMINAS -> "Nomina"
            DocumentOrganizer.CERTIFICADOS -> "Certificado"
            DocumentOrganizer.INFORMES -> "Informe"
            DocumentOrganizer.CITAS -> "Cita"
            else -> "Documento"
        }
        val usefulLine = text.lines().map { it.trim() }
            .firstOrNull { it.length >= 4 && !it.equals(type, true) && !it.matches(Regex("[0-9 ./,:-]+")) }
        val title = sanitizeTitle(usefulLine ?: file.nameWithoutExtension).take(70).ifBlank { type }
        val fields = linkedMapOf<String, String>()
        firstMatch(text, Regex("(?i)\\b(?:total|importe total|total amount|montant total|gesamtbetrag|totale)\\s*[:€]?\\s*([0-9.,]+)"))?.let { fields["total"] = it }
        firstMatch(text, Regex("(?i)\\b(?:iva|vat|tva|mwst)\\s*[:%]?\\s*([0-9.,]+\\s*%?)"))?.let { fields["iva"] = it }
        firstMatch(text, Regex("(?i)\\b(?:fecha|date|datum|data)\\s*[:.-]?\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})"))?.let { fields["fecha"] = it }
        firstMatch(text, Regex("(?i)\\b(?:nif|cif|vat|tax id)\\s*[:.-]?\\s*([A-Z]?[0-9]{7,9}[A-Z]?)"))?.let { fields["nif_cif"] = it }
        return Analysis(
            category,
            title,
            if (text.isBlank()) "No OCR available for a more precise local analysis." else "Local classification based on OCR text.",
            fields,
            "local"
        )
    }

    private fun firstMatch(text: String, regex: Regex): String? =
        regex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun normalizeCategory(value: String): String = value.trim().uppercase()
        .let { if (it in DocumentOrganizer.categories) it else DocumentOrganizer.GENERAL }

    private fun sanitizeTitle(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace(Regex("[\\r\\n]+"), " ")
        .trim()

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
}
