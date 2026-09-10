package com.baltas80.pocketscan

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmartScannerActivity : AppCompatActivity() {
    private var scannerStarted = false

    private val scannerLauncher = registerForActivityResult(StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK) { finish(); return@registerForActivityResult }
        val scanResult = result.data?.let { GmsDocumentScanningResult.fromActivityResultIntent(it) }
        if (scanResult == null) {
            Toast.makeText(this, "No se pudo leer el resultado del escaneo", Toast.LENGTH_LONG).show(); finish(); return@registerForActivityResult
        }
        saveResult(scanResult)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLockManager.ensureUnlocked(this) { allowed ->
            if (allowed) startScannerOnce() else finish()
        }
    }

    private fun startScannerOnce() {
        if (scannerStarted || isFinishing || isDestroyed) return
        scannerStarted = true
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                if (!isFinishing && !isDestroyed) scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { error ->
                Toast.makeText(this, error.message ?: "No se pudo iniciar el escáner", Toast.LENGTH_LONG).show(); finish()
            }
    }

    private fun saveResult(result: GmsDocumentScanningResult) {
        val pdfUri = result.pdf?.uri
        if (pdfUri == null) {
            Toast.makeText(this, "El escáner no devolvió un PDF", Toast.LENGTH_LONG).show(); finish(); return
        }
        val pages = result.pages.orEmpty()
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                val dir = File(filesDir, "scans").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val pdfFile = File(dir, "document_$stamp.pdf")
                val textFile = File(dir, "document_$stamp.txt")
                val pageDir = File(cacheDir, "scan-pages-$stamp").apply { mkdirs() }
                try {
                    contentResolver.openInputStream(pdfUri).use { input ->
                        requireNotNull(input) { "No se pudo abrir el PDF" }
                        FileOutputStream(pdfFile).use { output -> input.copyTo(output) }
                    }

                    val enhancedPages = pages.mapIndexed { index, page ->
                        val enhanced = File(pageDir, "page-${index + 1}.jpg")
                        if (DocumentImageEnhancer.enhanceToJpeg(this@SmartScannerActivity, page.imageUri, enhanced)) enhanced else null
                    }
                    val usableEnhancedPages = enhancedPages.filterNotNull()

                    if (usableEnhancedPages.size == pages.size) {
                        val improvedPdf = File(dir, "document_$stamp.improved.pdf")
                        if (DocumentImageEnhancer.buildPdfFromJpegs(usableEnhancedPages, improvedPdf)) {
                            pdfFile.delete()
                            improvedPdf.renameTo(pdfFile)
                        } else {
                            improvedPdf.delete()
                        }
                    }

                    Triple(dir, pdfFile, textFile to enhancedPages)
                } catch (error: Exception) {
                    pdfFile.delete(); textFile.delete()
                    pageDir.deleteRecursively()
                    null
                }
            }
            if (prepared == null) {
                Toast.makeText(this@SmartScannerActivity, "No se pudo guardar el documento", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            val (dir, pdfFile, textAndPages) = prepared
            val (textFile, enhancedPages) = textAndPages
            val ocrUris = pages.mapIndexed { index, page ->
                enhancedPages[index]?.let { Uri.fromFile(it) } ?: page.imageUri
            }
            runOcr(ocrUris, textFile) {
                lifecycleScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        val ocrText = textFile.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()
                        val namedPdf = autoNameDocument(pdfFile, ocrText)
                        val namedText = File(namedPdf.parentFile, namedPdf.nameWithoutExtension + ".txt")
                        if (textFile.exists() && textFile.absolutePath != namedText.absolutePath) textFile.renameTo(namedText)
                        val category = DocumentOrganizer.categoryForText(ocrText)
                        val finalPdf = DocumentOrganizer.moveDocument(namedPdf, namedText, dir, category)
                        Triple(category, finalPdf, pages.size)
                    }
                    AiAnalysisScheduler.enqueue(this@SmartScannerActivity, saved.second)
                    enhancedPages.filterNotNull().forEach { it.delete() }
                    enhancedPages.firstOrNull()?.parentFile?.delete()
                    Toast.makeText(this@SmartScannerActivity, "Documento guardado en ${saved.first} (${saved.third} página(s))", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun autoNameDocument(pdf: File, text: String): File {
        val type = when (DocumentOrganizer.categoryForText(text)) {
            DocumentOrganizer.FACTURAS -> "Factura"
            DocumentOrganizer.PRESUPUESTOS -> "Presupuesto"
            DocumentOrganizer.CONTRATOS -> "Contrato"
            DocumentOrganizer.RECIBOS -> "Recibo"
            DocumentOrganizer.TICKETS -> "Ticket"
            DocumentOrganizer.NOMINAS -> "Nomina"
            DocumentOrganizer.CERTIFICADOS -> "Certificado"
            DocumentOrganizer.INFORMES -> "Informe"
            DocumentOrganizer.CITAS -> "Cita"
            else -> "Documento"
        }
        val useful = text.lines().map { it.trim() }.firstOrNull { it.length >= 4 && !it.matches(Regex("[0-9 ./:-]+")) && !it.equals(type, true) }
        val clean = sanitizeFileName(useful ?: type).take(45).trim().trim('.', '_', '-')
        val base = sanitizeFileName("$type - $clean").take(80).trim().ifEmpty { "Documento" }
        var target = File(pdf.parentFile, "$base.pdf")
        var counter = 2
        while (target.exists() && target.absolutePath != pdf.absolutePath) { target = File(pdf.parentFile, "$base ($counter).pdf"); counter++ }
        return if (target.absolutePath == pdf.absolutePath || pdf.renameTo(target)) target else pdf
    }

    private fun sanitizeFileName(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace(Regex("[^A-Za-z0-9 _()-]"), "_")
            .replace(Regex("\\s+"), " ").trim()

    private fun runOcr(uris: List<Uri>, textFile: File, onComplete: () -> Unit) {
        MultilingualOcr.recognizeUris(uris, this) { text ->
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { textFile.writeText(text.trim() + "\n", Charsets.UTF_8) }
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }
}
