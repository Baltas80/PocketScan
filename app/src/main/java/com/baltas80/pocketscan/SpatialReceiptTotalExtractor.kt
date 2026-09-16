package com.baltas80.pocketscan

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Locale

/**
 * Extracts receipt totals using OCR geometry instead of OCR reading order.
 * This prevents words and amounts from different physical rows being paired.
 */
object SpatialReceiptTotalExtractor {
    data class Total(val amount: Double, val currency: String?, val confidence: Double)

    private const val RENDER_WIDTH = 2200
    private const val MAX_PAGES = 3

    fun extract(pdf: File): Total? {
        if (!pdf.isFile) return null
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    var best: Total? = null
                    val pages = renderer.pageCount.coerceAtMost(MAX_PAGES)
                    for (pageIndex in 0 until pages) {
                        renderer.openPage(pageIndex).use { page ->
                            val ratio = page.height.toFloat() / page.width.toFloat()
                            val height = (RENDER_WIDTH * ratio).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(RENDER_WIDTH, height, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val result = Tasks.await(
                                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                                )
                                findBestTotal(result)?.let { candidate ->
                                    if (best == null || candidate.confidence > best!!.confidence) {
                                        best = candidate
                                    }
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    best
                }
            }
        } finally {
            recognizer.close()
        }
    }

    private data class Candidate(
        val text: String,
        val box: Rect,
        val amount: Double,
        val currency: String?,
        val explicitLabel: Boolean,
        val nearbyPayment: Boolean
    )

    private fun findBestTotal(result: Text): Total? {
        val lines = result.textBlocks.flatMap { it.lines }
            .filter { it.boundingBox != null && it.text.isNotBlank() }

        val amounts = lines.flatMap { line ->
            amountMatches(line).map { match ->
                Candidate(
                    text = line.text,
                    box = line.boundingBox!!,
                    amount = match.first,
                    currency = match.second ?: currencyFromText(line.text),
                    explicitLabel = containsStandaloneTotal(line.text),
                    nearbyPayment = containsPaymentLabel(line.text)
                )
            }
        }
        if (amounts.isEmpty()) return null

        // Highest confidence: TOTAL label and amount on the same physical OCR line.
        amounts.filter { it.explicitLabel }
            .maxByOrNull { candidateScore(it, result.textBlocks.flatMap { block -> block.lines }) }
            ?.let { return Total(it.amount, it.currency, 1.0) }

        // Second pass: pair a standalone TOTAL label with the closest amount vertically
        // and require the amount to be in the same right-hand summary column. The
        // vertical relationship is deliberately tight: an amount five rows below is
        // never paired with a TOTAL label from above.
        val totalLabels = lines.filter { isStandaloneTotalLabel(it.text) }
        totalLabels.forEach { label ->
            val labelBox = label.boundingBox ?: return@forEach
            val candidate = amounts
                .filter { !it.explicitLabel && !containsNegativePayment(it.text) }
                .filter { horizontalCompatibility(labelBox, it.box) }
                .minByOrNull { verticalDistance(labelBox, it.box) }
            if (candidate != null) {
                val distance = verticalDistance(labelBox, candidate.box)
                if (distance <= labelBox.height * 2.2f) {
                    return Total(candidate.amount, candidate.currency, 0.95)
                }
            }
        }

        // Payment arithmetic can verify a receipt total even when OCR misses the label.
        // Find a cash/change pair and the amount immediately above that summary block.
        val paymentLines = lines.filter { containsPaymentLabel(it.text) }
        for (payment in paymentLines) {
            val paymentBox = payment.boundingBox ?: continue
            val cash = amountMatches(payment.text).firstOrNull()?.first
            if (cash != null) {
                val change = lines.asSequence()
                    .filter { it.boundingBox != null && it.boundingBox!!.top >= paymentBox.top }
                    .flatMap { amountMatches(it.text).map { pair -> pair.first } }
                    .firstOrNull { it > 0.0 }
                if (change != null && cash >= change) {
                    val derived = roundMoney(cash - change)
                    val candidate = amounts.filter { roundMoney(it.amount) == derived }
                        .minByOrNull { verticalDistance(it.box, paymentBox) }
                    if (candidate != null) return Total(candidate.amount, candidate.currency, 0.9)
                }
            }
        }

        return null
    }

    private fun candidateScore(candidate: Candidate, lines: List<Text.Line>): Double {
        var score = 100.0
        val box = candidate.box
        val nearby = lines.filter { it.boundingBox != null && kotlin.math.abs(it.boundingBox!!.centerY() - box.centerY()) <= box.height * 1.5f }
        if (nearby.any { isStandaloneTotalLabel(it.text) }) score += 20.0
        if (candidate.nearbyPayment) score -= 5.0
        return score
    }

    private fun horizontalCompatibility(label: Rect, amount: Rect): Boolean {
        val rightColumn = amount.centerX() >= label.centerX() - label.width * 0.25f
        val verticalBand = kotlin.math.abs(label.centerY() - amount.centerY()) <= label.height * 2.2f
        return rightColumn && verticalBand
    }

    private fun verticalDistance(a: Rect, b: Rect): Float = kotlin.math.abs(a.centerY() - b.centerY()).toFloat()

    private fun isStandaloneTotalLabel(text: String): Boolean =
        normalize(text).matches(Regex("^(grand total|total due|amount due|balance due|total|importe total|importe final|total general|total factura|tutar|genel toplam)\\s*[:.]?$"))

    private fun containsStandaloneTotal(text: String): Boolean {
        val normalized = normalize(text)
        if (isStandaloneTotalLabel(text)) return true
        return normalized.matches(Regex("^(grand total|total due|amount due|balance due|total|importe total|importe final|total general|total factura|tutar|genel toplam)\\b.*"))
    }

    private fun containsPaymentLabel(text: String): Boolean {
        val normalized = normalize(text)
        return Regex("\\b(efectivo|cash|payment|pago|tarjeta|card)\\b").containsMatchIn(normalized)
    }

    private fun containsNegativePayment(text: String): Boolean {
        val normalized = normalize(text)
        return Regex("\\b(cambio|change|discount|descuento|subtotal|iva|vat|tax|kdv)\\b").containsMatchIn(normalized)
    }

    private fun amountMatches(line: Text.Line): List<Pair<Double, String?>> = amountRegex.findAll(line.text)
        .mapNotNull { parseNumber(it.value)?.let { value -> value to currencyFromText(line.text) } }
        .toList()

    private fun currencyFromText(text: String): String? = when {
        Regex("(?i)€|\\bEUR\\b").containsMatchIn(text) -> "EUR"
        Regex("(?i)\\$|\\bUSD\\b").containsMatchIn(text) -> "USD"
        Regex("(?i)£|\\bGBP\\b").containsMatchIn(text) -> "GBP"
        else -> null
    }

    private fun parseNumber(raw: String): Double? {
        val s = raw.replace(" ", "")
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

    private val amountRegex = Regex("(?<!\\d)(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})|\\d+[.,]\\d{2})(?!\\d)")

    private fun roundMoney(value: Double): Double = "%.2f".format(Locale.US, value).toDouble()

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')
        .replace('ü', 'u')
}
