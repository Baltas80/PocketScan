package com.baltas80.pocketscan

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

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

    /**
     * Optional line context is populated by the Android OCR adapter. Keeping defaults
     * preserves a small, pure data contract for unit tests and future OCR engines.
     */
    data class Token(
        val text: String,
        val box: Box,
        val lineId: Int = -1,
        val lineText: String = ""
    )

    data class Candidate(val amount: Double, val currency: String?, val confidence: Double)

    private val amountRegex = Regex("(?<!\\d)(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})|\\d+[.,]\\d{2})(?!\\d)")
    private val totalLabels = listOf(
        "total due", "amount due", "balance due", "grand total",
        "importe total", "importe final", "importe a pagar", "total a pagar", "total general", "total factura",
        "totaal te betalen", "tutar", "genel toplam", "montant total", "montant", "a payer",
        "gesamtbetrag", "zu zahlen", "totale da pagare", "totale", "valor total", "total pagar", "total"
    ).sortedByDescending { it.length }
    private val changeLabels = setOf("change", "cambio")
    private val cashLabels = setOf("cash", "efectivo")

    fun resolve(tokens: List<Token>): Candidate? {
        if (tokens.isEmpty()) return null

        val normalized = tokens.map { token ->
            token.copy(
                text = normalize(token.text),
                lineText = normalize(token.lineText.ifBlank { token.text })
            )
        }

        val labels = normalized.filter { it.text in totalLabels && isValidTotalLabelContext(it) }
        if (labels.isEmpty()) return null

        val amounts = normalized.flatMap { token ->
            amountRegex.findAll(token.text).mapNotNull { match ->
                parseNumber(match.value)?.let { value ->
                    CandidateToken(token, value, currency(token.text))
                }
            }
        }
        if (amounts.isEmpty()) return null

        val documentWidth = normalized.documentWidth()
        val cash = findPaymentAmount(normalized, amounts, cashLabels)
        val change = findPaymentAmount(normalized, amounts, changeLabels)

        return labels.asSequence()
            .flatMap { label ->
                amounts.asSequence()
                    .filterNot { isSameGeometry(it.token, label) }
                    .filterNot { isNegativeOrNonTotalContext(it.token.text) }
                    .filter { sameRow(label.box, it.token.box) }
                    .filter { isRightOf(label.box, it.token.box) }
                    .filter { horizontalGap(label.box, it.token.box) <= maxHorizontalGap(label.box, it.token.box, documentWidth) }
                    .map { candidate ->
                        ScoredCandidate(
                            candidate = candidate,
                            score = confidence(
                                label.box,
                                candidate.token.box,
                                documentWidth,
                                candidate.amount,
                                candidate.currency,
                                cash,
                                change
                            )
                        )
                    }
            }
            .maxWithOrNull(
                compareBy<ScoredCandidate> { it.score }
                    .thenBy { it.candidate.token.box.top }
            )
            ?.let { scored ->
                Candidate(
                    amount = scored.candidate.amount,
                    currency = scored.candidate.currency,
                    confidence = scored.score
                )
            }
    }

    /** Pure test entry point: the production adapter uses exactly the same resolver. */
    internal fun resolveForTest(tokens: List<Token>): Candidate? = resolve(tokens)

    private data class CandidateToken(val token: Token, val amount: Double, val currency: String?)
    private data class ScoredCandidate(val candidate: CandidateToken, val score: Double)

    private fun isValidTotalLabelContext(label: Token): Boolean {
        val line = label.lineText
        if (line.isBlank()) return true

        // A TOTAL token inside a product description is not a financial-summary label.
        // Valid total labels must occur at the beginning of their physical OCR line.
        val startsAtLine = line == label.text || line.startsWith("${label.text} ") || line.startsWith("${label.text}:")
        if (!startsAtLine) return false

        // After removing the label, only numbers, currency symbols, whitespace and
        // harmless separators may remain. Phrases such as "TOTAL DE ARTICULOS" fail.
        val residual = line.removePrefix(label.text)
            .replaceFirst(Regex("^[\\s:;.=\\-]+"), "")
            .replace(amountRegex, "")
            .replace(Regex("(?i)\\b(eur|usd|gbp)\\b|[€$£]"), "")
            .replace(Regex("[\\s:;.=\\-]+"), "")
        return residual.isEmpty()
    }

    private fun isSameGeometry(a: Token, b: Token): Boolean =
        a.box == b.box

    private fun sameRow(a: Box, b: Box): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val minHeight = minOf(a.height, b.height)
        if (minHeight <= 0) return false
        return overlap.toFloat() / minHeight >= 0.45f
    }

    private fun isRightOf(label: Box, amount: Box): Boolean =
        amount.left >= label.right - horizontalTolerance(label, amount)

    private fun horizontalGap(a: Box, b: Box): Int = (b.left - a.right).coerceAtLeast(0)

    private fun maxHorizontalGap(a: Box, b: Box, documentWidth: Int): Int {
        val scaledTokenLimit = maxOf(a.height, b.height) * 20
        val documentLimit = if (documentWidth > 0) (documentWidth * 0.75f).toInt() else scaledTokenLimit
        return minOf(scaledTokenLimit, documentLimit).coerceAtLeast(24)
    }

    private fun horizontalTolerance(a: Box, b: Box): Int =
        (maxOf(a.height, b.height) * 0.35f).toInt()

    private fun confidence(
        label: Box,
        amount: Box,
        documentWidth: Int,
        amountValue: Double,
        amountCurrency: String?,
        cash: CandidateToken?,
        change: CandidateToken?
    ): Double {
        val gap = horizontalGap(label, amount).toDouble()
        val width = documentWidth.coerceAtLeast(1).toDouble()
        val horizontalScore = (1.0 - (gap / width)).coerceIn(0.0, 1.0)

        var score = 0.89 + horizontalScore * 0.08

        // Arithmetic is validation only; it can never create a TOTAL candidate.
        // It is only meaningful when all known payment values use the same currency.
        if (cash != null && change != null && currenciesCompatible(amountCurrency, cash.currency) && currenciesCompatible(amountCurrency, change.currency)) {
            val reconciles = abs((amountValue + change.amount) - cash.amount) <= 0.01
            if (reconciles) score += 0.02
        }

        return score.coerceIn(0.0, 0.99)
    }

    private fun currenciesCompatible(first: String?, second: String?): Boolean =
        first == null || second == null || first == second

    private fun findPaymentAmount(
        tokens: List<Token>,
        amounts: List<CandidateToken>,
        labels: Set<String>
    ): CandidateToken? {
        return tokens.asSequence()
            .filter { it.text in labels }
            .filter { it.lineText.isBlank() || it.lineText.startsWith(it.text) }
            .flatMap { label ->
                amounts.asSequence()
                    .filterNot { isSameGeometry(it.token, label) }
                    .filter { sameRow(label.box, it.token.box) }
                    .filter { isRightOf(label.box, it.token.box) }
                    .filter { horizontalGap(label.box, it.token.box) <= maxHorizontalGap(label.box, it.token.box, tokens.documentWidth()) }
                    .map { it to horizontalGap(label.box, it.token.box) }
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun isNegativeOrNonTotalContext(text: String): Boolean =
        Regex("\\b(subtotal|sub total|tax|kdv|vat|change|cash|tip|discount|indirim|descuento|cambio)\\b")
            .containsMatchIn(text)

    private fun List<Token>.documentWidth(): Int =
        (maxOfOrNull { it.box.right } ?: 0) - (minOfOrNull { it.box.left } ?: 0)

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

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .trim()
        .replace(Regex("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$"), "")
        .replace(Regex("\\s+"), " ")
}
