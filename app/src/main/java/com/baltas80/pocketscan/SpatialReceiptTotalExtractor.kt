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

/** Extracts receipt totals from OCR geometry, not OCR reading order. */
object SpatialReceiptTotalExtractor {
    data class Total(val amount: Double, val currency: String?, val confidence: Double)

    private const val RENDER_WIDTH = 2200
    private const val MAX_PAGES = 3
    private val amountRegex = Regex("(?<!\\d)(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})|\\d+[.,]\\d{2})(?!\\d)")

    fun extract(pdf: File): Total? {
        if (!pdf.isFile) return null
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    var best: Total? = null
                    for (index in 0 until renderer.pageCount.coerceAtMost(MAX_PAGES)) {
                        renderer.openPage(index).use { page ->
                            val ratio = page.height.toFloat() / page.width.toFloat()
                            val height = (RENDER_WIDTH * ratio).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(RENDER_WIDTH, height, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                                val candidate = findBestTotal(result)
                                if (candidate != null && (best == null || candidate.confidence > best!!.confidence)) best = candidate
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

    private data class AmountCandidate(val line: Text.Line, val box: Rect, val amount: Double, val currency: String?)

    private fun findBestTotal(result: Text): Total? {
        val lines = result.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null && it.text.isNotBlank() }
        val candidates = lines.flatMap { line ->
            amountRegex.findAll(line.text).mapNotNull { match ->
                parseNumber(match.value)?.let { value -> AmountCandidate(line, line.boundingBox!!, value, currency(line.text)) }
            }
        }.toList()
        if (candidates.isEmpty()) return null

        // 1) Exact physical line: a line beginning with TOTAL and containing an amount.
        candidates.filter { startsWithTotal(it.line.text) && !negative(it.line.text) }
            .maxByOrNull { scoreExact(it) }
            ?.let { return Total(it.amount, it.currency, 1.0) }

        // 2) Same row / same vertical band. TOTAL and amount may be separate OCR words/lines,
        // but they must be physically adjacent; a value five rows below is rejected.
        val labels = lines.filter { isStandaloneTotal(it.text) }
        for (label in labels) {
            val labelBox = label.boundingBox ?: continue
            val nearby = candidates
                .filter { !negative(it.line.text) }
                .filter { kotlin.math.abs(it.box.centerY() - labelBox.centerY()) <= labelBox.height() * 1.75f }
                .filter { it.box.centerX() >= labelBox.centerX() - labelBox.width() * 0.20f }
                .minByOrNull { kotlin.math.abs(it.box.centerY() - labelBox.centerY()) }
            if (nearby != null) return Total(nearby.amount, nearby.currency, 0.97)
        }

        // 3) If TOTAL is OCR-missed, use document layout: find a payment summary row and
        // a monetary value directly above it in the same right-hand amount column.
        val paymentRows = lines.filter { containsPayment(it.text) }
        for (payment in paymentRows) {
            val pb = payment.boundingBox ?: continue
            val previous = candidates
                .filter { !negative(it.line.text) && it.box.bottom <= pb.top }
                .minByOrNull { pb.top - it.box.bottom }
            if (previous != null && pb.top - previous.box.bottom <= previous.box.height() * 3f) {
                return Total(previous.amount, previous.currency, 0.86)
            }
        }
        return null
    }

    private fun scoreExact(candidate: AmountCandidate): Double {
        var score = 100.0
        if (containsPayment(candidate.line.text)) score -= 10.0
        return score
    }

    private fun startsWithTotal(text: String): Boolean = normalize(text)
        .matches(Regex("^(grand total|total due|amount due|balance due|total|importe total|importe final|total general|total factura|tutar|genel toplam)\\b.*"))

    private fun isStandaloneTotal(text: String): Boolean = normalize(text)
        .matches(Regex("^(grand total|total due|amount due|balance due|total|importe total|importe final|total general|total factura|tutar|genel toplam)\\s*[:.]?$"))

    private fun negative(text: String): Boolean = Regex("\\b(subtotal|sub total|tax|kdv|vat|change|cash|tip|discount|indirim|descuento|cambio)\\b")
        .containsMatchIn(normalize(text))

    private fun containsPayment(text: String): Boolean = Regex("\\b(efectivo|cash|payment|pago|tarjeta|card)\\b")
        .containsMatchIn(normalize(text))

    private fun parseNumber(raw: String): Double? {
        val s = raw.replace(" ", "")
        return runCatching {
            when {
                s.contains(',') && s.contains('.') -> if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
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
        .replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u').replace('ü', 'u')
}
