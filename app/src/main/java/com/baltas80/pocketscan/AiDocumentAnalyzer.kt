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
    private const val MAX_INLINE_PDF_BYTES = 14_000_000L

    data class Analysis(
        val category: String,
        val title: String,
        val summary: String,
        val fields: Map<String, String>,
        val source: String = "local"
    )

    private val documentSchema: Schema by lazy {
        Schema.obj(
            mapOf(
                "category" to Schema.enumeration(listOf("FACTURAS", "PRESUPUESTOS", "CONTRATOS", "RECIBOS", "TICKETS", "NOMINAS", "CERTIFICADOS", "INFORMES", "CITAS", "GENERAL")),
                "title" to Schema.string(), "summary" to Schema.string(), "proveedor" to Schema.string(), "cliente" to Schema.string(),
                "nif_cif" to Schema.string(), "numero" to Schema.string(), "fecha" to Schema.string(), "vencimiento" to Schema.string(),
                "subtotal" to Schema.string(), "iva" to Schema.string(), "total" to Schema.string(), "moneda" to Schema.string(),
                "periodo" to Schema.string(), "direccion" to Schema.string(), "telefono" to Schema.string(), "concepto" to Schema.string()
            ),
            optionalProperties = listOf("proveedor", "cliente", "nif_cif", "numero", "fecha", "vencimiento", "subtotal", "iva", "total", "moneda", "periodo", "direccion", "telefono", "concepto")
        )
    }

    suspend fun analyze(file: File, ocrText: String = ""): Result<Analysis> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { "Document not found" }
            val text = ocrText.trim()
            tryCloudAnalysis(file, text).getOrElse { localAnalysis(file, text) }
        }
    }

    private suspend fun tryCloudAnalysis(file: File, ocrText: String): Result<Analysis> = runCatching {
        require(file.length() <= MAX_INLINE_PDF_BYTES) { "PDF demasiado grande para el análisis IA directo (${file.length() / 1_000_000} MB)." }
        val model: GenerativeModel = Firebase.ai(backend = GenerativeBackend.googleAI(), useLimitedUseAppCheckTokens = true).generativeModel(
            modelName = AiModelConfig.modelName(),
            generationConfig = generationConfig { responseMimeType = "application/json"; responseSchema = documentSchema }
        )
        val auxiliaryOcr = ocrText.take(12000)
        val prompt = content {
            inlineData(file.readBytes(), "application/pdf")
            text("""
                You are PocketScan's document intelligence engine. Analyze the complete PDF, including layout, tables and visible values. OCR text below is only auxiliary context.
                Return the requested JSON schema. Never invent data. For absent fields, leave them empty.
                Extract only data actually present in the document. Preserve exact amounts, dates, identifiers and names when readable.
                Category must be the closest allowed category. Write title and summary in ${languageName()}.
                For monetary values, preserve the document's displayed decimal separator and currency when possible.

                AUXILIARY OCR:
                $auxiliaryOcr
            """.trimIndent())
        }
        val response = model.generateContent(prompt)
        val json = JSONObject(response.text ?: error("AI returned no content"))
        val fields = linkedMapOf<String, String>()
        listOf("proveedor", "cliente", "nif_cif", "numero", "fecha", "vencimiento", "subtotal", "iva", "total", "moneda", "periodo", "direccion", "telefono", "concepto").forEach { key ->
            json.optString(key).trim().takeIf { it.isNotEmpty() }?.let { fields[key] = it }
        }
        Analysis(normalizeCategory(json.optString("category")), json.optString("title", "Document").ifBlank { "Document" }, json.optString("summary", "").trim(), fields, "gemini")
    }

    private fun localAnalysis(file: File, text: String): Analysis {
        val category = DocumentOrganizer.categoryForText(text)
        val type = when (category) {
            DocumentOrganizer.FACTURAS -> "Factura"; DocumentOrganizer.PRESUPUESTOS -> "Presupuesto"; DocumentOrganizer.CONTRATOS -> "Contrato"
            DocumentOrganizer.RECIBOS -> "Recibo"; DocumentOrganizer.TICKETS -> "Ticket"; DocumentOrganizer.NOMINAS -> "Nómina"
            DocumentOrganizer.CERTIFICADOS -> "Certificado"; DocumentOrganizer.INFORMES -> "Informe"; DocumentOrganizer.CITAS -> "Cita"; else -> "Documento"
        }
        val usefulLine = text.lineSequence().map { it.trim() }.firstOrNull {
            it.length >= 4 && it.any(Char::isLetter) && it.count(Char::isDigit) < it.length / 2 &&
                !it.equals(type, true) && !isOcrMarkerLine(it) && !isGenericHeaderLine(it)
        }
        val title = usefulLine?.replace(Regex("\\s+"), " ")?.take(70)?.ifBlank { "$type - ${file.nameWithoutExtension}" } ?: "$type - ${file.nameWithoutExtension}"

        val fields = linkedMapOf<String, String>()
        firstMatchGroups(text, Regex("(?i)^\\s*(?:total|importe total|total amount|montant total|gesamtbetrag|totale)\\s*[:=]?\\s*(?:(€|EUR|USD|\\$|GBP|£)\\s*)?([0-9][0-9.,]*)(?:\\s*(€|EUR|USD|\\$|GBP|£))?\\s*$"))?.let { match ->
            fields["total"] = match.value
            match.currency?.let { fields["moneda"] = normalizeCurrency(it) }
        }
        firstMatch(text, Regex("(?im)^\\s*(?:iva|vat|tva|mwst)\\s*[:=]?\\s*([0-9]+(?:[.,][0-9]+)?\\s*%?)\\s*$"))?.let { fields["iva"] = cleanField(it) }
        firstMatch(text, Regex("(?im)^\\s*(?:fecha|date|datum|data)\\s*[:.-]?\\s*(\\d{1,4}[./-]\\d{1,2}[./-]\\d{1,4})\\s*$"))?.let { fields["fecha"] = it }
        firstMatch(text, Regex("(?im)^\\s*(?:nif|cif|nie|vat|tax id|tax identification number)\\s*[:.-]?\\s*((?:[A-Z]{1,3})?[0-9]{7,12}[A-Z]?)\\s*$"))?.let { fields["nif_cif"] = it }
        firstMatch(text, Regex("(?im)^\\s*(?:n[uú]mero|nº|n°|num(?:ero)?|no\\.?|referencia|ref\\.?|reference|expediente)\\s*[:#.-]?\\s*([A-Z0-9][A-Z0-9./_-]{2,30})\\s*$"))?.let { fields["numero"] = it }
        firstMatch(text, Regex("(?im)^\\s*(?:fecha de vencimiento|due date|vencimiento|f\\.? venc\\.?)\\s*[:.-]?\\s*(\\d{1,4}[./-]\\d{1,2}[./-]\\d{1,4})\\s*$"))?.let { fields["vencimiento"] = it }
        firstMatchGroups(text, Regex("(?i)^\\s*(?:subtotal|base imponible|base)\\s*[:=]?\\s*(?:(€|EUR|USD|\\$|GBP|£)\\s*)?([0-9][0-9.,]*)(?:\\s*(€|EUR|USD|\\$|GBP|£))?\\s*$"))?.let { match ->
            fields["subtotal"] = match.value
            if (!fields.containsKey("moneda")) match.currency?.let { fields["moneda"] = normalizeCurrency(it) }
        }
        firstMatch(text, Regex("(?im)^\\s*(?:periodo|per[ií]odo|ejercicio|campaign|campa[nñ]a)\\s*[:.-]?\\s*([^\\r\\n]{2,60}?)\\s*$"))?.let { fields["periodo"] = cleanField(it) }
        firstMatch(text, Regex("(?im)^\\s*(?:tel[eé]fono|tel\\.?|phone|telephone)\\s*[:.-]?\\s*([+0-9][0-9 ()-]{6,24})\\s*$"))?.let { fields["telefono"] = cleanField(it) }
        firstMatch(text, Regex("(?im)^\\s*(?:direcci[oó]n|domicilio|address)\\s*[:.-]?\\s*([^\\r\\n]{4,100}?)\\s*$"))?.let { fields["direccion"] = cleanField(it) }
        firstMatch(text, Regex("(?im)^\\s*(?:concepto|asunto|motivo|description|descripci[oó]n)\\s*[:.-]?\\s*([^\\r\\n]{3,120}?)\\s*$"))?.let { fields["concepto"] = cleanField(it) }
        firstMatch(text, Regex("(?im)^\\s*(?:proveedor|emisor|empresa|entidad|supplier|vendor|issuer|company)\\s*[:.-]?\\s*([^\\r\\n]{3,100}?)\\s*$"))?.let { fields["proveedor"] = cleanField(it) }
        firstMatch(text, Regex("(?im)^\\s*(?:cliente|destinatario|beneficiario|titular|customer|client|recipient|beneficiary|account holder)\\s*[:.-]?\\s*([^\\r\\n]{3,100}?)\\s*$"))?.let { fields["cliente"] = cleanField(it) }

        val summary = if (text.isBlank()) "No OCR disponible para un análisis local más preciso." else text.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !isOcrMarkerLine(it) }.joinToString(" ").replace(Regex("\\s+"), " ").trim().take(700)
        return Analysis(category, title, summary, fields)
    }

    private data class Match(val value: String, val currency: String?)
    private fun firstMatchGroups(text: String, regex: Regex): Match? = text.lineSequence().asSequence().map { it.trim() }.firstNotNullOfOrNull { line ->
        regex.matchEntire(line)?.let { result ->
            val value = result.groupValues.getOrNull(2)?.trim().orEmpty()
            if (value.isBlank()) null else {
                val currency = result.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: result.groupValues.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
                Match(value, currency)
            }
        }
    }

    private fun cleanField(value: String): String = value.replace(Regex("\\s+"), " ").trim().trim('.', ':', ';', '-')
    private fun isOcrMarkerLine(line: String): Boolean = line.matches(Regex("(?i)^=+\\s*p[áa]gina\\s+\\d+\\s*=+$")) || line.matches(Regex("(?i)^-+\\s*p[áa]gina\\s+\\d+\\s*-+$"))
    private fun isGenericHeaderLine(line: String): Boolean {
        val normalized = line.lowercase(Locale.ROOT).replace(Regex("[^a-záéíóúüñ ]"), " ").replace(Regex("\\s+"), " ").trim()
        return normalized in setOf("ministerio", "ministerio de inclusion", "seguridad social", "seguridad ciudadana", "documento", "pagina", "servicio", "secretaria de estado", "renta de la seguridad social y pensiones")
    }
    private fun firstMatch(text: String, regex: Regex): String? = text.lineSequence().map { it.trim() }.firstNotNullOfOrNull { line -> regex.matchEntire(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } }
    private fun normalizeCurrency(value: String): String = when (value.uppercase(Locale.ROOT)) { "€", "EUR" -> "EUR"; "$", "USD" -> "USD"; "£", "GBP" -> "GBP"; else -> value }
    private fun normalizeCategory(value: String): String = when (value.trim().uppercase(Locale.ROOT)) {
        "FACTURAS" -> DocumentOrganizer.FACTURAS; "PRESUPUESTOS" -> DocumentOrganizer.PRESUPUESTOS; "CONTRATOS" -> DocumentOrganizer.CONTRATOS; "RECIBOS" -> DocumentOrganizer.RECIBOS; "TICKETS" -> DocumentOrganizer.TICKETS; "NOMINAS" -> DocumentOrganizer.NOMINAS; "CERTIFICADOS" -> DocumentOrganizer.CERTIFICADOS; "INFORMES" -> DocumentOrganizer.INFORMES; "CITAS" -> DocumentOrganizer.CITAS; else -> DocumentOrganizer.GENERAL
    }
    private fun languageName(): String = when (Locale.getDefault().language.lowercase(Locale.ROOT)) { "es" -> "Spanish (Spain)"; "en" -> "English"; "fr" -> "French"; "de" -> "German"; "it" -> "Italian"; "pt" -> "Portuguese"; "ca" -> "Catalan"; else -> Locale.getDefault().displayLanguage }
}
