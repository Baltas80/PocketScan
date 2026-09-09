package com.baltas80.pocketscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Lightweight local document enhancement for scanned pages.
 * It keeps photographs and stamps usable while increasing text contrast
 * and reducing the washed-out appearance common in low-contrast scans.
 */
object DocumentImageEnhancer {
    private const val MAX_DIMENSION = 2600
    private const val LOW_PERCENTILE = 0.02f
    private const val HIGH_PERCENTILE = 0.98f

    fun enhanceToJpeg(context: Context, uri: Uri, output: File): Boolean = runCatching {
        val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: return false
        val bitmap = resizeIfNeeded(source)
        if (bitmap !== source) source.recycle()

        val enhanced = enhance(bitmap)
        if (enhanced !== bitmap) bitmap.recycle()

        output.parentFile?.mkdirs()
        FileOutputStream(output).use { stream ->
            enhanced.compress(Bitmap.CompressFormat.JPEG, 94, stream)
        }
        enhanced.recycle()
        true
    }.getOrElse { false }

    private fun resizeIfNeeded(source: Bitmap): Bitmap {
        val max = maxOf(source.width, source.height)
        if (max <= MAX_DIMENSION) return source
        val scale = MAX_DIMENSION.toFloat() / max.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun enhance(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val histogram = IntArray(256)
        for (pixel in pixels) histogram[luminance(pixel)]++
        val low = percentile(histogram, pixels.size, LOW_PERCENTILE)
        val high = percentile(histogram, pixels.size, HIGH_PERCENTILE).coerceAtLeast(low + 1)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val y = luminance(pixel)
            val stretched = ((y - low) * 255f / (high - low)).roundToInt().coerceIn(0, 255)
            // Slightly deepen dark text without turning the whole page into pure black.
            val adjusted = when {
                stretched < 90 -> (stretched * 0.78f).roundToInt()
                stretched < 180 -> (stretched * 0.90f).roundToInt()
                else -> stretched
            }.coerceIn(0, 255)
            pixels[i] = Color.rgb(adjusted, adjusted, adjusted)
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun luminance(pixel: Int): Int =
        (0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)).roundToInt().coerceIn(0, 255)

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).roundToInt().coerceIn(1, total)
        var count = 0
        for (value in histogram.indices) {
            count += histogram[value]
            if (count >= target) return value
        }
        return 255
    }

    fun buildPdfFromJpegs(images: List<File>, output: File): Boolean = runCatching {
        if (images.isEmpty()) return false
        val document = android.graphics.pdf.PdfDocument()
        try {
            images.forEachIndexed { index, imageFile ->
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return false
                try {
                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                        bitmap.width,
                        bitmap.height,
                        index + 1
                    ).create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    document.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
            }
            output.parentFile?.mkdirs()
            FileOutputStream(output).use { document.writeTo(it) }
            true
        } finally {
            document.close()
        }
    }.getOrElse { false }
}
