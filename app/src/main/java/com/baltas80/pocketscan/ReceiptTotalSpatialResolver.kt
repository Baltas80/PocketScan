package com.baltas80.pocketscan

import java.util.Locale

/**
 * Resolves receipt totals from OCR tokens while preserving their physical geometry.
 * Text reading order is deliberately ignored.
 */
object ReceiptTotalSpatialResolver {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    data class Token(val text: String, val box: Box)
    data class Candidate(val amount: Double, val currency: String?, val confidence: Double)

    private val amountRegex = Regex("(?<!\\d)(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})|\\d+[.,]\\d{2})(?!\\d)")
    private val totalLabels = setOf(
        "total", "total due", "amount due", "balance due", "grand total",
        "importe total", "importe final", "total general", "total factura",
        "tutar", "genel toplam"
    )

    fun resolve(tokens: List<Token>): Candidate? {
        if (tokens.isEmpty()) return null

        val normalized = tokens.map { it.copy(text = normalize(it.text)) }
        val labels = normalized.filter { isTotalLabel(it.text) }
        if (labels.isEmpty()) return null

        val amounts = normalized.flatMap { token ->
            amountRegex.findAll(token.text).mapNotNull { match ->
                parseNumber(match.value)?.let { value ->
                    CandidateToken(token, value, currency(token.text))
                }
            }
        }
        if (amounts.isEmpty()) return null

        val documentWidth = (normalized.maxOfOrNull { it.box.right } ?: 0) -
            (normalized.minOfOrNull { it.box.left } ?: 0)

        // A TOTAL value may be far to the right on a narrow receipt. The horizontal
        // relationship is therefore bounded by both token scale and document width,
        // rather than by an arbitrary fixed pixel distance.
        for (label in labels) {
            val labelBox = label.box
            val candidates = amounts
                .asSequence()
                .filterNot { isNegativeOrNonTotalContext(it.token.text) }
                .filter { sameRow(labelBox, it.token.box) }
                .filter { it.token.box.left >= labelBox.right - horizontalTolerance(labelBox, it.token.box) }
                .filter { horizontalGap(labelBox, it.token.box) <= maxHorizontalGap(labelBox, it.token.box, documentWidth) }
                .sortedWith(compareBy<CandidateToken> { horizontalGap(labelBox, it.token.box) })
                .toList()

            candidates.firstOrNull()?.let {
                return Candidate(
                    amount = it.amount,
                    currency = it.currency,
                    confidence = confidence(labelBox, it.token.box, documentWidth)
                )
            }
        }

        return null
    }

    private data class CandidateToken(val token: Token, val amount: Double, val currency: String?)

    private fun isTotalLabel(text: String): Boolean = text.trim().let { value ->
        value in totalLabels
    }

    private fun sameRow(a: Box, b: Box): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val minHeight = minOf(a.height, b.height)
        if (minHeight <= 0) return false
        return overlap.toFloat() / minHeight >= 0.45f
    }

    private fun horizontalGap(a: Box, b: Box): Int = (b.left - a.right).coerceAtLeast(0)

    private fun maxHorizontalGap(a: Box, b: Box, documentWidth: Int): Int {
        val scaledTokenLimit = maxOf(a.height, b.height) * 20
        val documentLimit = if (documentWidth > 0) (documentWidth * 0.75f).toInt() else scaledTokenLimit
        return minOf(scaledTokenLimit, documentLimit).coerceAtLeast(24)
    }

    private fun horizontalTolerance(a: Box, b: Box): Int = (maxOf(a.height, b.height) * 0.35f).toInt()

    private fun confidence(label: Box, amount: Box, documentWidth: Int): Double {
        val gap = horizontalGap(label, amount).toDouble()
        val width = documentWidth.coerceAtLeast(1).toDouble()
        val rowScore = 1.0
        val horizontalScore = (1.0 - (gap / width)).coerceIn(0.0, 1.0)
        return (0.80 + rowScore * 0.10 + horizontalScore * 0.10).coerceIn(0.0, 0.99)
    }

    private fun isNegativeOrNonTotalContext(text: String): Boolean =
        Regex("\\b(subtotal|sub total|tax|kdv|vat|change|cash|tip|discount|indirim|descuento|cambio)\\b")
            .containsMatchIn(text)

    private fun parseNumber(raw: String): Double? {
        val s = raw.replace(" ", "")
        return runCatching {
            when {
                s.contains(',') && s.contains('.') ->
                    if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.')
                    else s.replace(",", "")
                s.count { it == ',' } == 1 && s.substringAfter(',').length == 2 -> s.replace(',', '.')
                s.count { it == '.' } == 1 && s.substringAfter('.').length == 2 -> s
                else -> s
            }.toDouble()
        }.getOrNull()
    }

    private fun currency(text: String): String? = when {
        Regex("(?i)€|\\bEUR\\b").containsMatchIn(text) -> "EUR"
        Regex("(?i)\\$|\\bUSD\\b").containsMatchIn(text) -> "USD"
        Regex("(?i)£|\\bGBP\\b").containsMatchIn(text) -> "GBP"
        else -> null
    }

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o')
        .replace('ú', 'u').replace('ü', 'u')
        .trim()
}
