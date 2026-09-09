package com.baltas80.pocketscan

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Document intelligence layer.
 *
 * Cloud analysis is preferred when Firebase AI Logic is configured. If the Firebase
 * project is not configured yet, PocketScan still provides deterministic local
 * classification from OCR instead of leaving the AI action unusable.
 */
object AiDocumentAnalyzer {
    data class Analysis(
        val category: String,
        val title: String,
        val summary: String,
        val fields: Map<String, String>,
        val source: String = "local"
    )

    suspend fun analyze(file: File, ocrText: String = ""): Result<Analysis> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { "Documento no encontrado" }
            val text = ocrText.trim()
            tryCloudAnalysis(text).getOrElse { localAnalysis(file, text) }
        }
    }

    private suspend fun tryCloudAnalysis(ocrText: String): Result<Analysis> = runCatching {
        val model: GenerativeModel = Firebase.ai(
            backend = GenerativeBackend.googleAI()
        ).generativeModel("gemini-3.8-flash")

        val prompt = """
            Eres el motor inteligente de PocketScan. Analiza el texto OCR y devuelve SOLO JSON válido.
            No inventes datos. Si un dato no aparece, usa una cadena vacía.
            Categoría obligatoria: FACTURAS, PRESUPUESTOS, CONTRATOS, RECIBOS, TICKETS,
            NOMINAS, CERTIFICADOS, INFORMES, CITAS o GENERAL.
            Extrae únicamente datos realmente presentes.
            Campos útiles: proveedor, cliente, nif_cif, numero, fecha, vencimiento,
            subtotal, iva, total, moneda, periodo, direccion, telefono y otros relevantes.

            FORMATO EXACTO:
            {"category":"...","title":"...","summary":"...","fields":{"clave":"valor"}}

            TEXTO OCR:
            $ocrText
        """.trimIndent()

        val response = model.generateContent(prompt)
        val json = JSONObject(extractJson(response.text ?: error("La IA no devolvió contenido")))
        val fieldsJson = json.optJSONObject("fields")
        val fields = linkedMapOf<String, String>()
        if (fieldsJson != null) {
            fieldsJson.keys().forEach { key ->
                val value = fieldsJson.optString(key).trim()
                if (value.isNotEmpty()) fields[key] = value
            }
        }
        Analysis(
            category = normalizeCategory(json.optString("category")),
            title = json.optString("title", "Documento").ifBlank { "Documento" },
            summary = json.optString("summary", "").trim(),
            fields = fields,
            source = "gemini"
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
        val usefulLine = text.lines().map { it.trim() }.firstOrNull {
            it.length >= 4 && !it.equals(type, true) && !it.matches(Regex("[0-9 ./,:-]+"))
        }
        val title = sanitizeTitle(usefulLine ?: file.nameWithoutExtension)
            .take(70).ifBlank { type }
        val fields = linkedMapOf<String, String>()
        firstMatch(text, Regex("(?i)\\b(?:total|importe total)\\s*[:€]?\\s*([0-9.,]+)"))?.let { fields["total"] = it }
        firstMatch(text, Regex("(?i)\\b(?:iva|vat)\\s*[:%]?\\s*([0-9.,]+\\s*%?)"))?.let { fields["iva"] = it }
        firstMatch(text, Regex("(?i)\\b(?:fecha)\\s*[:.-]?\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})"))?.let { fields["fecha"] = it }
        firstMatch(text, Regex("(?i)\\b(?:nif|cif)\\s*[:.-]?\\s*([A-Z]?[0-9]{7,9}[A-Z]?)"))?.let { fields["nif_cif"] = it }
        return Analysis(
            category = category,
            title = title,
            summary = if (text.isBlank()) "No hay OCR disponible para un análisis local más preciso." else "Clasificación local basada en el texto OCR.",
            fields = fields,
            source = "local"
        )
    }

    private fun firstMatch(text: String, regex: Regex): String? = regex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun normalizeCategory(value: String): String =
        value.trim().uppercase().let { candidate ->
            if (candidate in DocumentOrganizer.categories) candidate else DocumentOrganizer.GENERAL
        }

    private fun sanitizeTitle(value: String): String =
        value.replace(Regex("\\s+"), " ").replace(Regex("[\\r\\n]+"), " ").trim()

    private fun extractJson(raw: String): String {
        val cleaned = raw.substringAfter("```json", raw).substringBeforeLast("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { "Respuesta de IA no válida" }
        return cleaned.substring(start, end + 1)
    }
}
