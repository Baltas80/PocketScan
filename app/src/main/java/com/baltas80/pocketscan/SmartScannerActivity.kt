package com.baltas80.pocketscan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmartScannerActivity : AppCompatActivity() {
    private val scannerLauncher = registerForActivityResult(StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            finish()
            return@registerForActivityResult
        }

        val scanResult = result.data?.let { GmsDocumentScanningResult.fromActivityResultIntent(it) }
        if (scanResult == null) {
            Toast.makeText(this, "No se pudo leer el resultado del escaneo", Toast.LENGTH_LONG).show()
            finish()
            return@registerForActivityResult
        }

        saveResult(scanResult)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { error ->
                Toast.makeText(
                    this,
                    error.message ?: "No se pudo iniciar el escáner",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
    }

    private fun saveResult(result: GmsDocumentScanningResult) {
        val pdfUri = result.pdf?.uri
        if (pdfUri == null) {
            Toast.makeText(this, "El escáner no devolvió un PDF", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val dir = File(filesDir, "scans").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pdfFile = File(dir, "document_$stamp.pdf")
        val textFile = File(dir, "document_$stamp.txt")

        try {
            contentResolver.openInputStream(pdfUri).use { input ->
                requireNotNull(input) { "No se pudo abrir el PDF" }
                FileOutputStream(pdfFile).use { output -> input.copyTo(output) }
            }

            val pages = result.pages.orEmpty()
            runOcr(pages.map { it.imageUri }, textFile) {
                Toast.makeText(
                    this,
                    "Documento guardado (${pages.size} página(s))",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        } catch (error: Exception) {
            pdfFile.delete()
            textFile.delete()
            Toast.makeText(
                this,
                error.message ?: "No se pudo guardar el documento",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun runOcr(uris: List<Uri>, textFile: File, onComplete: () -> Unit) {
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
        )
        val allText = StringBuilder()

        fun complete() {
            try {
                textFile.writeText(allText.toString(), Charsets.UTF_8)
            } finally {
                recognizer.close()
                onComplete()
            }
        }

        fun next(index: Int) {
            if (index >= uris.size) {
                complete()
                return
            }

            try {
                val image = com.google.mlkit.vision.common.InputImage.fromFilePath(this, uris[index])
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        if (text.text.isNotBlank()) {
                            if (allText.isNotEmpty()) allText.append("\n\n")
                            allText.append(text.text)
                        }
                        next(index + 1)
                    }
                    .addOnFailureListener { next(index + 1) }
            } catch (_: Exception) {
                next(index + 1)
            }
        }

        next(0)
    }
}
