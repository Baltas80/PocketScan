package com.baltas80.pocketscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Lightweight local document enhancement for scanned pages.
 * It keeps photographs and stamps usable while increasing text contrast
 * and reducing the washed-out appearance common in low-contrast scans.
 */
object DocumentImageEnhancer {
    private const val MAX_DIMENSION = 2200
    private const val LOW_PERCENTILE = 0.02f
    private const val HIGH_PERCENTILE = 0.98f

    fun enhanceToJpeg(context: Context, uri: Uri, output: File): Boolean = runCatching {
        val source = decodeForEnhancement(context, uri) ?: return false
        val bitmap = resizeIfNeeded(source)
        if (bitmap !== source) source.recycle()

        try {
            val enhanced = enhance(bitmap)
            try {
                output.parentFile?.mkdirs()
                val temp = File(output.parentFile, "${output.name}.tmp")
                try {
                    FileOutputStream(temp).use { stream ->
                        if (!enhanced.compress(Bitmap.CompressFormat.JPEG, 94, stream)) return false
                        stream.fd.sync()
                    }
                    if (!temp.renameTo(output)) {
                        if (output.exists() && !output.delete()) return false
                        if (!temp.renameTo(output)) return false
                    }
                    true
                } finally {
                    temp.delete()
                }
            } finally {
                enhanced.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrElse { false }

    private fun decodeForEnhancement(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

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
            val y = luminance(pixels[i])
            val stretched = ((y - low) * 255f / (high - low)).roundToInt().coerceIn(0, 255)
            // Deepen dark text slightly without destroying stamps and photographs.
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
                val bitmap = decodeForPdf(imageFile) ?: return false
                try {
                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                        bitmap.width,
                        bitmap.height,
                        index + 1
                    ).create()
                    val page = document.startPage(pageInfo)
                    try {
                        page.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    } finally {
                        document.finishPage(page)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            output.parentFile?.mkdirs()
            val temp = File(output.parentFile, "${output.name}.tmp")
            try {
                FileOutputStream(temp).use { stream ->
                    document.writeTo(stream)
                    stream.fd.sync()
                }
                if (!temp.renameTo(output)) {
                    if (output.exists() && !output.delete()) return false
                    if (!temp.renameTo(output)) return false
                }
                true
            } finally {
                temp.delete()
            }
        } finally {
            document.close()
        }
    }.getOrElse { false }

    private fun decodeForPdf(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }
}
