package com.baltas80.pocketscan

import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeBackend
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * AI layer for document understanding.
 *
 * The service is deliberately isolated from the UI and the document organizer so the
 * provider/model can be changed later without rewriting the application.
 */
object AiDocumentAnalyzer {
    data class Analysis(
        val category: String,
        val title: String,
        val summary: String,
        val fields: Map<String, String>
    )

    suspend fun analyze(file: File, ocrText: String = ""): Result<Analysis> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { "Documento no encontrado" }
            val model: GenerativeModel = Firebase.ai(
                backend = GenerativeBackend.googleAI()
            ).generativeModel("gemini-3.7-flash")

            val prompt = """
                Eres el motor inteligente de PocketScan. Analiza el documento y devuelve SOLO JSON válido.
                No inventes datos. Si un dato no aparece, usa una cadena vacía.
                Categoría obligatoria: FACTURAS, PRESUPUESTOS, CONTRATOS, RECIBOS, TICKETS,
                NOMINAS, CERTIFICADOS, INFORMES, CITAS o GENERAL.
                Campos relevantes como proveedor, cliente, nif_cif, numero, fecha, vencimiento,
                subtotal, iva, total, moneda, periodo y otros que realmente aparezcan.

                FORMATO:
                {"category":"...","title":"...","summary":"...","fields":{"clave":"valor"}}

                TEXTO OCR:
                $ocrText
            """.trimIndent()

            val response = model.generateContent(prompt)
            val raw = response.text ?: error("La IA no devolvió contenido")
            val json = JSONObject(extractJson(raw))
            val fieldsJson = json.optJSONObject("fields")
            val fields = linkedMapOf<String, String>()
            if (fieldsJson != null) {
                fieldsJson.keys().forEach { key -> fields[key] = fieldsJson.optString(key) }
            }
            Analysis(
                category = json.optString("category", DocumentOrganizer.GENERAL),
                title = json.optString("title", "Documento"),
                summary = json.optString("summary", ""),
                fields = fields
            )
        }
    }

    private fun extractJson(raw: String): String {
        val fenced = raw.substringAfter("```json", raw).substringBeforeLast("```").trim()
        val start = fenced.indexOf('{')
        val end = fenced.lastIndexOf('}')
        require(start >= 0 && end > start) { "Respuesta de IA no válida" }
        return fenced.substring(start, end + 1)
    }
}
