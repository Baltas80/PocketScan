package com.baltas80.pocketscan

import java.io.File
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

object AiLibraryQueryEngine {
    data class Match(
        val file: File,
        val analysis: AiDocumentAnalyzer.Analysis?,
        val total: Double?,
        val currency: String?
    )

    data class Result(
        val matches: List<Match>,
        val aggregateTotal: Double?,
        val aggregateCurrency: String?
    )

    private val stopWords = setOf(
        "de", "del", "la", "el", "los", "las", "un", "una", "y", "en", "por", "para", "con",
        "que", "me", "mis", "mi", "a", "al", "the", "of", "and", "in", "for", "with", "what",
        "which", "how", "much", "many", "show", "list", "find", "total", "suma", "sum", "cuanto",
        "cuántas", "cuantas", "dime", "muestra", "buscar", "encuentra", "quiero", "hay"
    )

    private val categoryAliases = mapOf(
        "FACTURAS" to setOf("factura", "facturas", "invoice", "invoices"),
        "PRESUPUESTOS" to setOf("presupuesto", "presupuestos", "budget", "quote", "quotes"),
        "CONTRATOS" to setOf("contrato", "contratos", "contract", "contracts"),
        "RECIBOS" to setOf("recibo", "recibos", "receipt", "receipts"),
        "TICKETS" to setOf("ticket", "tickets", "tienda", "compra", "compras"),
        "NOMINAS" to setOf("nomina", "nominas", "nómina", "nóminas", "payroll", "salary", "salario"),
        "CERTIFICADOS" to setOf("certificado", "certificados", "certificate", "certificates"),
        "INFORMES" to setOf("informe", "informes", "report", "reports"),
        "CITAS" to setOf("cita", "citas", "appointment", "appointments")
    )

    fun query(query: String, documents: List<File>): Result {
        val normalizedQuery = normalize(query)
        val tokens = normalizedQuery.split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in stopWords }
        val category = categoryAliases.entries.firstOrNull { (_, aliases) ->
            aliases.any { alias -> containsWord(normalizedQuery, normalize(alias)) }
        }?.key
        val amountFilter = parseAmountFilter(normalizedQuery)
        val year = Regex("\\b20\\d{2}\\b").find(normalizedQuery)?.value?.toIntOrNull()

        val matches = documents.asSequence()
            .filter { it.isFile && it.extension.equals("pdf", true) }
            .map { file -> file to AiMetadataStore.load(file) }
            .filter { (file, analysis) ->
                val corpus = normalize(buildCorpus(file, analysis))
                val categoryOk = category == null || analysis?.category?.let { categoryKey(it) } == category
                val yearOk = year == null || extractYear(analysis?.fields?.get("fecha")) == year
                val amount = extractAmount(analysis?.fields?.get("total"))
                val amountOk = amountFilter == null || amountFilter.matches(amount)
                val lexicalScore = tokens.count { token -> containsWord(corpus, token) }
                val hasStructuredFilter = category != null || year != null || amountFilter != null
                categoryOk && yearOk && amountOk && (tokens.isEmpty() || lexicalScore > 0 || hasStructuredFilter)
            }
            .map { (file, analysis) ->
                val parsed = extractAmount(analysis?.fields?.get("total"))
                Match(file, analysis, parsed?.first, parsed?.second?.takeIf { it.isNotBlank() })
            }
            .sortedBy { it.file.name.lowercase(Locale.ROOT) }
            .take(100)
            .toList()

        val currencies = matches.mapNotNull { it.currency }.distinct()
        val aggregateCurrency = currencies.singleOrNull()
        val aggregateTotal = if (aggregateCurrency != null) {
            matches.mapNotNull { match -> match.total?.let { it to match.currency } }
                .filter { it.second == aggregateCurrency }
                .takeIf { it.isNotEmpty() }
                ?.sumOf { it.first }
        } else null

        return Result(matches, aggregateTotal, aggregateCurrency)
    }

    fun buildContext(result: Result): String = buildString {
        appendLine("MATCHES=${result.matches.size}")
        result.matches.take(30).forEachIndexed { index, match ->
            val a = match.analysis
            append(index + 1).append(". FILE=").append(match.file.name)
            append(" | category=").append(a?.category ?: "GENERAL")
            append(" | title=").append(a?.title.orEmpty())
            append(" | date=").append(a?.fields?.get("fecha").orEmpty())
            append(" | supplier=").append(a?.fields?.get("proveedor").orEmpty())
            append(" | client=").append(a?.fields?.get("cliente").orEmpty())
            append(" | total=").append(a?.fields?.get("total").orEmpty())
            append(" | currency=").append(a?.fields?.get("moneda").orEmpty())
            append(" | summary=").append(a?.summary.orEmpty().take(500))
            appendLine()
        }
        result.aggregateTotal?.let {
            append("AGGREGATE_TOTAL=").append(formatAmount(it)).append(' ').append(result.aggregateCurrency.orEmpty()).appendLine()
        }
    }

    private data class AmountFilter(val mode: Mode, val value: Double) {
        enum class Mode { GT, GTE, LT, LTE, EQ }
        fun matches(amount: Pair<Double, String>?): Boolean {
            val actual = amount?.first ?: return false
            return when (mode) {
                Mode.GT -> actual > value
                Mode.GTE -> actual >= value
                Mode.LT -> actual < value
                Mode.LTE -> actual <= value
                Mode.EQ -> abs(actual - value) < 0.005
            }
        }
    }

    private fun parseAmountFilter(query: String): AmountFilter? {
        val number = "([0-9]{1,3}(?:[.\\s][0-9]{3})*(?:,[0-9]{1,2})?|[0-9]+(?:[.,][0-9]{1,2})?)"
        val phraseRegex = Regex(
            "\\b(al menos|como mínimo|como minimo|at least|mayor o igual que|greater than or equal to|" +
                "más de|mas de|mayor que|mayor|superior|greater than|over|above|" +
                "como máximo|como maximo|at most|menor o igual que|less than or equal to|" +
                "menos de|menor que|menor|inferior|less than|under|below|" +
                "igual a|exactamente)\\s+$number\\b"
        )
        val phrase = phraseRegex.find(query)
        if (phrase != null) {
            val amount = parseNumber(phrase.groupValues[2]) ?: return null
            val mode = when (phrase.groupValues[1]) {
                "al menos", "como mínimo", "como minimo", "at least", "mayor o igual que", "greater than or equal to" -> AmountFilter.Mode.GTE
                "más de", "mas de", "mayor que", "mayor", "superior", "greater than", "over", "above" -> AmountFilter.Mode.GT
                "como máximo", "como maximo", "at most", "menor o igual que", "less than or equal to" -> AmountFilter.Mode.LTE
                "menos de", "menor que", "menor", "inferior", "less than", "under", "below" -> AmountFilter.Mode.LT
                else -> AmountFilter.Mode.EQ
            }
            return AmountFilter(mode, amount)
        }

        val symbol = Regex("(?:total\\s*)?(>=|<=|>|<|=)\\s*$number\\b").find(query) ?: return null
        val amount = parseNumber(symbol.groupValues[2]) ?: return null
        val mode = when (symbol.groupValues[1]) {
            ">" -> AmountFilter.Mode.GT
            ">=" -> AmountFilter.Mode.GTE
            "<" -> AmountFilter.Mode.LT
            "<=" -> AmountFilter.Mode.LTE
            else -> AmountFilter.Mode.EQ
        }
        return AmountFilter(mode, amount)
    }

    private fun buildCorpus(file: File, analysis: AiDocumentAnalyzer.Analysis?): String = buildString {
        append(file.name).append(' ')
        analysis?.let {
            append(it.category).append(' ').append(it.title).append(' ').append(it.summary).append(' ')
            it.fields.values.forEach { value -> append(value).append(' ') }
        }
        val ocr = File(file.parentFile, "${file.nameWithoutExtension}.txt")
        if (ocr.isFile) append(ocr.readText(Charsets.UTF_8).take(12000))
    }

    private fun categoryKey(category: String): String = when (normalize(category)) {
        "facturas" -> "FACTURAS"
        "presupuestos" -> "PRESUPUESTOS"
        "contratos" -> "CONTRATOS"
        "recibos" -> "RECIBOS"
        "tickets" -> "TICKETS"
        "nominas" -> "NOMINAS"
        "certificados" -> "CERTIFICADOS"
        "informes" -> "INFORMES"
        "citas" -> "CITAS"
        else -> "GENERAL"
    }

    private fun extractAmount(value: String?): Pair<Double, String>? {
        if (value.isNullOrBlank()) return null
        val cleaned = value.trim().replace("€", " EUR", true)
        val match = Regex("[+-]?[0-9][0-9.,\\s]*").find(cleaned) ?: return null
        val number = parseNumber(match.value) ?: return null
        val currency = when {
            cleaned.contains("eur", true) -> "EUR"
            cleaned.contains("usd", true) || cleaned.contains("$", true) -> "USD"
            cleaned.contains("gbp", true) || cleaned.contains("£") -> "GBP"
            else -> ""
        }
        return number to currency
    }

    private fun parseNumber(value: String): Double? {
        val s = value.replace("\\s".toRegex(), "")
        return runCatching {
            when {
                s.contains(',') && s.contains('.') -> {
                    if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.')
                    else s.replace(",", "")
                }
                s.count { it == ',' } == 1 && s.substringAfter(',').length <= 2 -> s.replace(',', '.')
                s.count { it == '.' } > 1 -> s.replace(".", "")
                else -> s
            }.toDouble()
        }.getOrNull()
    }

    private fun extractYear(date: String?): Int? {
        if (date.isNullOrBlank()) return null
        val match = Regex("\\b(20\\d{2})\\b").find(date)
        if (match != null) return match.groupValues[1].toIntOrNull()
        val patterns = listOf("dd/MM/yyyy", "dd-MM-yyyy", "yyyy-MM-dd")
        for (pattern in patterns) {
            try { return LocalDate.parse(date, DateTimeFormatter.ofPattern(pattern)).year } catch (_: DateTimeParseException) { }
        }
        return null
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .replace("ñ", "n")

    private fun containsWord(text: String, word: String): Boolean {
        if (word.isBlank()) return false
        return Regex("(?:^|[^a-z0-9])${Regex.escape(word)}(?:$|[^a-z0-9])").containsMatchIn(text)
    }

    private fun formatAmount(value: Double): String = "%.2f".format(Locale.US, value)
}
