package com.baltas80.pocketscan

import java.text.Normalizer
import java.util.Locale

/**
 * Extracts a receipt total only when the OCR gives us strong evidence that the
 * amount belongs to a line explicitly labelled TOTAL. It deliberately refuses
 * to guess from product prices, cash received, change, tax or the largest amount.
 */
object ReceiptTotalExtractor {
    data class Total(val amount: Double, val currency: String?)

    private val amountPattern = Regex(
        "(?<!\\d)(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})|\\d+[.,]\\d{2})(?!\\d)"
    )
    private val totalLabel = Regex(
        "(?i)^(grand total|total due|amount due|balance due|total|importe total|importe final|total general|total factura|tutar|genel toplam)\\b"
    )
    private val standaloneTotal = Regex(
        "(?i)^(grand total|total due|amount due|balance due|total|importe total|importe final|total general|total factura|tutar|genel toplam)\\s*[:.]?\\s*$"
    )
    private val negative = Regex(
        "(?i)\\b(subtotal|sub total|tax|kdv|vat|change|cash|tip|discount|indirim)\\b"
    )

    fun extract(text: String): Total? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null

        // Strongest signal: TOTAL is at the start and the amount is the final value
        // on the line. This rejects OCR such as "... TOTAL 5,00 x PAN ...".
        lines.forEach { line ->
            if (negative.containsMatchIn(line)) return@forEach
            if (!totalLabel.containsMatchIn(normalize(line))) return@forEach
            val matches = amountPattern.findAll(line).toList()
            val match = matches.lastOrNull() ?: return@forEach
            val suffix = line.substring(match.range.last + 1).trim()
            if (suffix.isNotEmpty() && !suffix.matches(Regex("(?i)^(€|EUR|USD|\\$|GBP|£)[.,;:]?$") )) return@forEach
            parse(match.value)?.let { return Total(it, currency(line)) }
        }

        // Some OCR engines split TOTAL and its value into adjacent lines.
        lines.forEachIndexed { index, line ->
            if (negative.containsMatchIn(line) || !standaloneTotal.matches(line)) return@forEachIndexed
            for (offset in 1..2) {
                val next = lines.getOrNull(index + offset) ?: break
                if (negative.containsMatchIn(next)) continue
                val matches = amountPattern.findAll(next).toList()
                val match = matches.lastOrNull() ?: continue
                val suffix = next.substring(match.range.last + 1).trim()
                if (suffix.isNotEmpty() && !suffix.matches(Regex("(?i)^(€|EUR|USD|\\$|GBP|£)[.,;:]?$") )) continue
                parse(match.value)?.let { return Total(it, currency(next)) }
            }
        }

        return null
    }

    private fun parse(value: String): Double? {
        val s = value.replace(" ", "")
        return runCatching {
            when {
                s.contains(',') && s.contains('.') ->
                    if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
                s.count { it == ',' } == 1 && s.substringAfter(',').length == 2 -> s.replace(',', '.')
                s.count { it == '.' } == 1 && s.substringAfter('.').length == 2 -> s
                else -> s
            }.toDouble()
        }.getOrNull()
    }

    private fun currency(line: String): String? = when {
        Regex("(?i)€|\\bEUR\\b").containsMatchIn(line) -> "EUR"
        Regex("(?i)\\$|\\bUSD\\b").containsMatchIn(line) -> "USD"
        Regex("(?i)£|\\bGBP\\b").containsMatchIn(line) -> "GBP"
        else -> null
    }

    private fun normalize(value: String): String = Normalizer.normalize(
        value.lowercase(Locale.ROOT),
        Normalizer.Form.NFD
    ).replace("\\p{M}+".toRegex(), "")
}
