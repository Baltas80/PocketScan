package com.baltas80.pocketscan

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/** Extracts receipt totals from OCR geometry, never from OCR reading order. */
object SpatialReceiptTotalExtractor {
    data class Total(val amount: Double, val currency: String?, val confidence: Double)

    private const val RENDER_WIDTH = 2200
    // Inspect all pages up to a bounded safety limit. A three-page cap could silently
    // miss the total on a later page of a multi-page invoice.
    private const val MAX_PAGES = 15
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
                                if (candidate != null && (best == null || candidate.confidence > best!!.confidence)) {
                                    best = candidate
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

    private fun findBestTotal(result: Text): Total? {
        val tokens = result.textBlocks
            .flatMap { it.lines }
            .flatMap { line ->
                line.elements.flatMap { element -> toTokens(element) }
            }

        return ReceiptTotalSpatialResolver.resolve(tokens.map {
            ReceiptTotalSpatialResolver.Token(
                text = it.text,
                box = ReceiptTotalSpatialResolver.Box(it.left, it.top, it.right, it.bottom)
            )
        })?.let {
            Total(it.amount, it.currency, it.confidence)
        }
    }

    private data class SpatialToken(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    /**
     * Produces geometry-bearing tokens from ML Kit Elements. When an amount is embedded
     * in a larger OCR element, Symbol boxes are used to recover the amount's actual region.
     */
    private fun toTokens(element: Text.Element): List<SpatialToken> {
        val box = element.boundingBox ?: return emptyList()
        val text = element.text
        if (text.isBlank()) return emptyList()

        val tokens = mutableListOf<SpatialToken>()
        tokens += SpatialToken(text, box.left, box.top, box.right, box.bottom)

        val symbols = element.symbols
        if (symbols.isEmpty()) return tokens

        amountRegex.findAll(text).forEach { match ->
            val symbolRange = symbolRangeFor(text, symbols, match.range)
            if (symbolRange != null) {
                val symbolBoxes = symbols.subList(symbolRange.first, symbolRange.last + 1)
                    .mapNotNull { it.boundingBox }
                if (symbolBoxes.isNotEmpty()) {
                    tokens += SpatialToken(
                        text = match.value,
                        left = symbolBoxes.minOf { it.left },
                        top = symbolBoxes.minOf { it.top },
                        right = symbolBoxes.maxOf { it.right },
                        bottom = symbolBoxes.maxOf { it.bottom }
                    )
                }
            }
        }

        return tokens
    }

    /** Maps non-whitespace element characters to ML Kit symbols. */
    private fun symbolRangeFor(
        text: String,
        symbols: List<Text.Symbol>,
        range: IntRange
    ): IntRange? {
        val charToSymbol = mutableListOf<Int>()
        var symbolIndex = 0
        for (charIndex in text.indices) {
            if (!text[charIndex].isWhitespace()) {
                if (symbolIndex >= symbols.size) return null
                charToSymbol += symbolIndex++
            }
        }
        if (range.first !in charToSymbol.indices || range.last !in charToSymbol.indices) return null
        return charToSymbol[range.first]..charToSymbol[range.last]
    }
}
