package com.baltas80.pocketscan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(photo).build(), ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exception: ImageCaptureException) {
                busy = false
                Toast.makeText(this@ScannerActivity, exception.message ?: "Capture failed", Toast.LENGTH_LONG).show()
            }
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                capturedPages.add(photo)
                busy = false
                hint.text = "Página ${capturedPages.size} capturada. Puedes añadir otra o finalizar."
            }
        })
    }

    private fun finishPdf() {
        if (busy || capturedPages.isEmpty()) {
            if (capturedPages.isEmpty()) Toast.makeText(this, "Captura al menos una página", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(filesDir, "scans")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pdf = File(dir, "document_$stamp.pdf")
        val text = File(dir, "document_$stamp.txt")
        try {
            createPdf(capturedPages, pdf)
            runOcr(capturedPages, text)
            capturedPages.forEach { it.delete() }
            Toast.makeText(this, "PDF guardado", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "No se pudo crear el PDF", Toast.LENGTH_LONG).show()
        }
    }

    private fun runOcr(files: List<File>, textFile: File) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val all = StringBuilder()
        fun next(index: Int) {
            if (index >= files.size) {
                textFile.writeText(all.toString(), Charsets.UTF_8)
                recognizer.close()
                return
            }
            try {
                val image = InputImage.fromFilePath(this, Uri.fromFile(files[index]))
                recognizer.process(image).addOnSuccessListener { result ->
                    if (result.text.isNotBlank()) {
                        if (all.isNotEmpty()) all.append("\n\n")
                        all.append(result.text)
                    }
                    next(index + 1)
                }.addOnFailureListener { next(index + 1) }
            } catch (_: Exception) { next(index + 1) }
        }
        next(0)
    }

    private fun createPdf(files: List<File>, pdfFile: File) {
        val document = PdfDocument()
        try {
            files.forEachIndexed { index, file ->
                val source = BitmapFactory.decodeFile(file.absolutePath) ?: error("No se pudo leer la imagen")
                val bitmap = applyExifRotation(file, source)
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
                val page = document.startPage(pageInfo)
                val margin = 24f
                val scale = minOf((595f - margin * 2) / bitmap.width, (842f - margin * 2) / bitmap.height)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val left = (595f - width) / 2f
                val top = (842f - height) / 2f
                page.canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + width, top + height), null)
                document.finishPage(page)
                if (bitmap !== source) source.recycle()
                bitmap.recycle()
            }
            FileOutputStream(pdfFile).use { document.writeTo(it) }
        } finally { document.close() }
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
