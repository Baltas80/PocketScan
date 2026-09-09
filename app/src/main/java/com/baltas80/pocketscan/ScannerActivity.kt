package com.baltas80.pocketscan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ScannerActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var hint: TextView
    private var imageCapture: ImageCapture? = null
    private val capturedPages = mutableListOf<File>()
    private var busy = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        previewView = findViewById(R.id.previewView)
        hint = findViewById(R.id.scanHint)
        findViewById<Button>(R.id.captureButton).setOnClickListener { takePage() }
        findViewById<Button>(R.id.removeButton).setOnClickListener { removeLastPage() }
        findViewById<Button>(R.id.finishButton).setOnClickListener { finishPdf() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "Camera error", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePage() {
        if (busy) return
        val capture = imageCapture ?: return
        val dir = File(filesDir, "scans").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val photo = File(dir, "page_$stamp.jpg")
        busy = true
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(photo).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    Toast.makeText(this@ScannerActivity, exception.message ?: "Capture failed", Toast.LENGTH_LONG).show()
                }
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    capturedPages.add(photo); busy = false; updatePageStatus()
                }
            }
        )
    }

    private fun removeLastPage() {
        if (busy || capturedPages.isEmpty()) return
        capturedPages.removeLast().delete(); updatePageStatus()
    }

    private fun updatePageStatus() {
        hint.text = if (capturedPages.isEmpty()) "Alinea el documento dentro del encuadre" else
            "${capturedPages.size} página(s) preparada(s). Puedes añadir otra, eliminar la última o finalizar."
        findViewById<Button>(R.id.removeButton).isEnabled = capturedPages.isNotEmpty() && !busy
        findViewById<Button>(R.id.finishButton).isEnabled = capturedPages.isNotEmpty() && !busy
    }

    private fun finishPdf() {
        if (busy || capturedPages.isEmpty()) {
            if (capturedPages.isEmpty()) Toast.makeText(this, "Captura al menos una página", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true; updatePageStatus(); hint.text = "Procesando documento…"
        val pages = capturedPages.toList()
        val dir = File(filesDir, "scans")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pdf = File(dir, "document_$stamp.pdf")
        val text = File(dir, "document_$stamp.txt")
        try {
            createPdf(pages, pdf)
            runOcr(pages, text) { ocrText ->
                pages.forEach { it.delete() }
                val finalPdf = autoNameDocument(pdf, ocrText, "Documento")
                if (finalPdf != pdf) text.renameTo(File(dir, finalPdf.nameWithoutExtension + ".txt"))
                busy = false
                Toast.makeText(this, "PDF guardado como ${finalPdf.name}", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            busy = false; updatePageStatus()
            Toast.makeText(this, e.message ?: "No se pudo crear el PDF", Toast.LENGTH_LONG).show()
        }
    }

    private fun runOcr(files: List<File>, textFile: File, onComplete: (String) -> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val all = StringBuilder()
        fun complete() {
            try { val result = all.toString(); textFile.writeText(result, Charsets.UTF_8); onComplete(result) }
            finally { recognizer.close() }
        }
        fun next(index: Int) {
            if (index >= files.size) { complete(); return }
            try {
                val image = InputImage.fromFilePath(this, Uri.fromFile(files[index]))
                recognizer.process(image).addOnSuccessListener { result ->
                    if (result.text.isNotBlank()) { if (all.isNotEmpty()) all.append("\n\n"); all.append(result.text) }
                    next(index + 1)
                }.addOnFailureListener { next(index + 1) }
            } catch (_: Exception) { next(index + 1) }
        }
        next(0)
    }

    private fun autoNameDocument(pdf: File, ocrText: String, fallback: String): File {
        val dir = pdf.parentFile ?: return pdf
        val lines = ocrText.lines().map { it.trim() }.filter { it.length >= 4 }
        val lower = ocrText.lowercase()
        val type = listOf(
            "factura" to "Factura", "invoice" to "Factura", "presupuesto" to "Presupuesto",
            "contrato" to "Contrato", "recibo" to "Recibo", "ticket" to "Ticket",
            "nómina" to "Nomina", "nomina" to "Nomina", "certificado" to "Certificado",
            "informe" to "Informe", "cita" to "Cita"
        ).firstOrNull { lower.contains(it.first) }?.second ?: fallback
        val usefulLine = lines.firstOrNull { line -> line.length >= 4 && !line.lowercase().matches(Regex("[0-9 ./:-]+")) } ?: type
        val cleanLine = sanitizeFileName(usefulLine).take(45).trim().trim('.', '_', '-')
        val base = sanitizeFileName(if (cleanLine.length >= 4) "$type - $cleanLine" else type).take(80).trim().ifEmpty { "Documento" }
        var target = File(dir, "$base.pdf")
        var counter = 2
        while (target.exists() && target.absolutePath != pdf.absolutePath) { target = File(dir, "$base ($counter).pdf"); counter++ }
        if (target.absolutePath == pdf.absolutePath || !pdf.renameTo(target)) return pdf
        return target
    }

    private fun sanitizeFileName(value: String): String {
        val withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD).replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return withoutAccents.replace(Regex("[^A-Za-z0-9 _()-]"), "_").replace(Regex("\\s+"), " ").trim()
    }

    private fun createPdf(files: List<File>, pdfFile: File) {
        val document = PdfDocument()
        try {
            files.forEachIndexed { index, file ->
                val source = BitmapFactory.decodeFile(file.absolutePath) ?: error("No se pudo leer la imagen")
                val rotated = applyExifRotation(file, source)
                val enhanced = enhanceDocument(rotated)
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                val margin = 24f
                val scale = minOf((595f - margin * 2) / enhanced.width, (842f - margin * 2) / enhanced.height)
                val width = enhanced.width * scale; val height = enhanced.height * scale
                val left = (595f - width) / 2f; val top = (842f - height) / 2f
                page.canvas.drawBitmap(enhanced, null, android.graphics.RectF(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                document.finishPage(page)
                if (enhanced !== rotated) enhanced.recycle()
                if (rotated !== source) rotated.recycle()
                source.recycle()
            }
            FileOutputStream(pdfFile).use { document.writeTo(it) }
        } finally { document.close() }
    }

    private fun enhanceDocument(input: Bitmap): Bitmap {
        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(output.width * output.height)
        output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
        val contrast = 1.18f; val brightness = 2f
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((android.graphics.Color.red(c) - 128) * contrast + 128 + brightness).toInt()
            val g = ((android.graphics.Color.green(c) - 128) * contrast + 128 + brightness).toInt()
            val b = ((android.graphics.Color.blue(c) - 128) * contrast + 128 + brightness).toInt()
            pixels[i] = android.graphics.Color.argb(android.graphics.Color.alpha(c), max(0, min(255, r)), max(0, min(255, g)), max(0, min(255, b)))
        }
        output.setPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
        return output
    }

    private fun applyExifRotation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }
}
