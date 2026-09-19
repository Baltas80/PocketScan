package com.baltas80.pocketscan

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Resolves receipt totals from OCR tokens while preserving physical geometry.
 * OCR reading order is never used as evidence by itself.
 */
object ReceiptTotalSpatialResolver {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    data class Token(
        val text: String,
        val box: Box,
        val lineId: Int = -1,
        val lineText: String = ""
    )

    data class Candidate(val amount: Double, val currency: String?, val confidence: Double)

    private data class CandidateToken(val token: Token, val amount: Double, val currency: String?)
    private data class ScoredCandidate(val candidate: CandidateToken, val score: Double)

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
                    .mapNotNull { candidate ->
                        scoreCandidate(label, candidate, normalized, documentWidth, cash, change)
                    }
            }
            .maxWithOrNull(compareBy<ScoredCandidate> { it.score }.thenBy { it.candidate.token.box.top })
            ?.let { scored ->
                Candidate(scored.candidate.amount, scored.candidate.currency, scored.score)
            }
    }

    internal fun resolveForTest(tokens: List<Token>): Candidate? = resolve(tokens)

    /**
     * Scores only two legitimate spatial layouts:
     *  1) label and amount on the same physical row;
     *  2) a standalone summary label followed by a vertically aligned amount
     *     in the same right-hand amount column, within a bounded summary block.
     *
     * This deliberately rejects arbitrary OCR-order associations.
     */
    private fun scoreCandidate(
        label: Token,
        candidate: CandidateToken,
        tokens: List<Token>,
        documentWidth: Int,
        cash: CandidateToken?,
        change: CandidateToken?
    ): ScoredCandidate? {
        val a = label.box
        val b = candidate.token.box

        if (sameRow(a, b) && isRightOf(a, b)) {
            val gap = horizontalGap(a, b).toDouble()
            val width = documentWidth.coerceAtLeast(1).toDouble()
            var score = 0.90 + (1.0 - gap / width).coerceIn(0.0, 1.0) * 0.07
            if (reconciles(candidate.amount, cash, change)) score += 0.02
            return ScoredCandidate(candidate, score.coerceAtMost(0.99))
        }

        // Some receipts print the TOTAL label and its amount on separate physical
        // lines. Permit that layout only when the label is a standalone summary label,
        // the amount is below it, and both occupy the same right-hand numeric column.
        if (!isStandaloneSummaryLabel(label)) return null
        if (b.top <= a.bottom) return null

        val rowHeight = medianLineHeight(tokens).coerceAtLeast(a.height).coerceAtLeast(1)
        val verticalRows = (b.top - a.bottom).toFloat() / rowHeight
        if (verticalRows > 8.0f) return null

        val amountColumn = b.centerX >= documentWidth * 0.50f
        if (!amountColumn) return null

        val xTolerance = maxOf(a.height, b.height) * 3.5f
        val expectedAmountX = maxOf(a.centerX, documentWidth * 0.50f)
        if (abs(b.centerX - expectedAmountX) > maxOf(xTolerance, documentWidth * 0.30f)) return null

        // A candidate separated from the label by several lines is accepted only if
        // the intervening region does not contain another explicit financial label.
        val intervening = tokens.filter { it.box.top > a.bottom && it.box.bottom < b.top }
        if (intervening.any { it.text in totalLabels || it.text in cashLabels || it.text in changeLabels }) return null

        var score = 0.82 - (verticalRows * 0.025f)
        if (reconciles(candidate.amount, cash, change)) score += 0.08
        // Summary labels are stronger when the amount is near the lower portion of the page.
        if (b.centerY > tokens.maxOf { it.box.centerY } * 0.55f) score += 0.03

        return ScoredCandidate(candidate, score.coerceAtMost(0.97))
    }

    private fun isValidTotalLabelContext(label: Token): Boolean {
        val line = label.lineText
        if (line.isBlank()) return true
        val startsAtLine = line == label.text || line.startsWith("${label.text} ") || line.startsWith("${label.text}:")
        if (!startsAtLine) return false

        val residual = line.removePrefix(label.text)
            .replaceFirst(Regex("^[\\s:;.=\\-]+"), "")
            .replace(amountRegex, "")
            .replace(Regex("(?i)\\b(eur|usd|gbp)\\b|[€$£]"), "")
            .replace(Regex("[\\s:;.=\\-]+"), "")
        return residual.isEmpty()
    }

    private fun isStandaloneSummaryLabel(label: Token): Boolean {
        val line = label.lineText
        if (line.isBlank()) return true
        return line == label.text || line == "${label.text}:"
    }

    private fun isSameGeometry(a: Token, b: Token): Boolean = a.box == b.box

    private fun sameRow(a: Box, b: Box): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val minHeight = minOf(a.height, b.height)
        if (minHeight <= 0) return false
        return overlap.toFloat() / minHeight >= 0.45f
    }

    private fun isRightOf(label: Box, amount: Box): Boolean =
        amount.left >= label.right - horizontalTolerance(label, amount)

    private fun horizontalGap(a: Box, b: Box): Int = (b.left - a.right).coerceAtLeast(0)

    private fun horizontalTolerance(a: Box, b: Box): Int =
        (maxOf(a.height, b.height) * 0.35f).toInt()

    private fun findPaymentAmount(
        tokens: List<Token>,
        amounts: List<CandidateToken>,
        labels: Set<String>
    ): CandidateToken? = tokens.asSequence()
        .filter { it.text in labels }
        .filter { it.lineText.isBlank() || it.lineText.startsWith(it.text) }
        .flatMap { label ->
            amounts.asSequence()
                .filterNot { isSameGeometry(it.token, label) }
                .filter { sameRow(label.box, it.token.box) }
                .filter { isRightOf(label.box, it.token.box) }
                .map { it to horizontalGap(label.box, it.token.box) }
        }
        .minByOrNull { it.second }
        ?.first

    private fun reconciles(total: Double, cash: CandidateToken?, change: CandidateToken?): Boolean =
        cash != null && change != null && abs((total + change.amount) - cash.amount) <= 0.01

    private fun isNegativeOrNonTotalContext(text: String): Boolean =
        Regex("\\b(subtotal|sub total|tax|kdv|vat|change|cash|tip|discount|indirim|descuento|cambio)\\b")
            .containsMatchIn(text)

    private fun List<Token>.documentWidth(): Int =
        (maxOfOrNull { it.box.right } ?: 0) - (minOfOrNull { it.box.left } ?: 0)

    private fun medianLineHeight(tokens: List<Token>): Float {
        val heights = tokens.map { it.box.height }.filter { it > 0 }.sorted()
        if (heights.isEmpty()) return 1f
        return heights[heights.size / 2].toFloat()
    }

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
