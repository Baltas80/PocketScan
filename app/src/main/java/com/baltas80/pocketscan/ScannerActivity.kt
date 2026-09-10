package com.baltas80.pocketscan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class ScannerActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var hint: android.widget.TextView
    private lateinit var imageCapture: ImageCapture
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val capturedPages = mutableListOf<File>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        previewView = findViewById(R.id.previewView)
        hint = findViewById(R.id.hint)

        findViewById<Button>(R.id.captureButton).setOnClickListener { takePage() }
        findViewById<Button>(R.id.removeButton).setOnClickListener { removeLastPage() }
        findViewById<Button>(R.id.finishButton).setOnClickListener { finishPdf() }
        updatePageStatus()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else Toast.makeText(this, "Se necesita acceso a la cámara", Toast.LENGTH_LONG).show()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePage() {
        if (busy || !::imageCapture.isInitialized) return
        busy = true
        val dir = File(filesDir, "scans").apply { mkdirs() }
        val photo = File(dir, "page_${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(photo).build()
        imageCapture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    busy = false
                    Toast.makeText(this@ScannerActivity, exception.message ?: "Capture failed", Toast.LENGTH_LONG).show()
                }
            }
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                capturedPages.add(photo)
                runOnUiThread { busy = false; updatePageStatus() }
            }
        })
    }

    private fun removeLastPage() {
        if (busy || capturedPages.isEmpty()) return
        capturedPages.removeAt(capturedPages.lastIndex).delete()
        updatePageStatus()
    }

    private fun updatePageStatus() {
        hint.text = if (capturedPages.isEmpty()) "Alinea el documento dentro del encuadre" else
            "${capturedPages.size} página(s) preparada(s). Puedes añadir otra, eliminar la última o finalizar."
        findViewById<Button>(R.id.removeButton).isEnabled = capturedPages.isNotEmpty() && !busy
        findViewById<Button>(R.id.finishButton).isEnabled = capturedPages.isNotEmpty() && !busy
    }

    private fun finishPdf() {
        if (busy || capturedPages.isEmpty()) return
        busy = true
        val dir = File(filesDir, "scans").apply { mkdirs() }
        val pdf = File(dir, "document_${System.currentTimeMillis()}.pdf")
        val text = File(dir, "document_${pdf.nameWithoutExtension}.txt")
        createPdf(pdf, capturedPages)
        runOcr(capturedPages) { ocrText ->
            text.writeText(ocrText, Charsets.UTF_8)
            capturedPages.forEach { it.delete() }
            val finalPdf = autoNameDocument(pdf, ocrText, "Documento")
            if (finalPdf != pdf) {
                val renamedText = File(finalPdf.parentFile, "${finalPdf.nameWithoutExtension}.txt")
                if (text.exists()) text.renameTo(renamedText)
            }
            Toast.makeText(this, "PDF guardado correctamente", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun runOcr(pages: List<File>, onComplete: (String) -> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val result = StringBuilder()
        fun process(index: Int) {
            if (index >= pages.size) {
                recognizer.close()
                onComplete(result.toString().trim())
                return
            }
            val bitmap = BitmapFactory.decodeFile(pages[index].absolutePath)
            if (bitmap == null) {
                process(index + 1)
                return
            }
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { resultText ->
                    if (resultText.text.isNotBlank()) {
                        if (result.isNotEmpty()) result.append("\n\n")
                        result.append(resultText.text)
                    }
                    bitmap.recycle()
                    process(index + 1)
                }
                .addOnFailureListener {
                    bitmap.recycle()
                    process(index + 1)
                }
        }
        process(0)
    }

    private fun autoNameDocument(pdf: File, ocrText: String, fallback: String): File {
        val normalized = ocrText.lowercase()
        val type = when {
            "factura" in normalized || "invoice" in normalized -> "Factura"
            "presupuesto" in normalized || "quote" in normalized -> "Presupuesto"
            "contrato" in normalized || "contract" in normalized -> "Contrato"
            "recibo" in normalized || "receipt" in normalized -> "Recibo"
            "ticket" in normalized -> "Ticket"
            "nómina" in normalized || "nomina" in normalized || "payroll" in normalized -> "Nomina"
            "certificado" in normalized || "certificate" in normalized -> "Certificado"
            "informe" in normalized || "report" in normalized -> "Informe"
            "cita" in normalized || "appointment" in normalized -> "Cita"
            else -> fallback
        }
        val candidateLine = ocrText.lineSequence()
            .map { it.trim() }
            .filter { it.length >= 3 && it.any(Char::isLetter) }
            .filterNot { it.equals(type, true) }
            .firstOrNull()
        val cleanLine = sanitizeFileName(candidateLine ?: type).take(80).trim()
        val base = "$type - $cleanLine".ifBlank { fallback }
        var target = File(pdf.parentFile, "$base.pdf")
        var counter = 2
        while (target.exists() && target.absolutePath != pdf.absolutePath) {
            target = File(pdf.parentFile, "$base ($counter).pdf")
            counter++
        }
        return if (pdf.renameTo(target)) target else pdf
    }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun createPdf(output: File, pages: List<File>) {
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, file ->
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@forEachIndexed
                val rotated = applyExifRotation(file, bitmap)
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                val scale = minOf(575f / rotated.width, 822f / rotated.height)
                val width = (rotated.width * scale).roundToInt()
                val height = (rotated.height * scale).roundToInt()
                val left = (595 - width) / 2f
                val top = (842 - height) / 2f
                canvas.drawBitmap(enhanceDocument(rotated), null, android.graphics.RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
                document.finishPage(page)
                if (rotated !== bitmap) rotated.recycle()
                bitmap.recycle()
            }
            FileOutputStream(output).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    private fun enhanceDocument(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        val matrix = ColorMatrix().apply { setScale(1.18f, 1.18f, 1.18f, 1f) }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun applyExifRotation(file: File, bitmap: Bitmap): Bitmap {
        val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
        val degrees = when (exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
