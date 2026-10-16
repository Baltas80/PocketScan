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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
                val tempPdf = File(dir, pdfFile.name + ".tmp")
                try {
                    contentResolver.openInputStream(pdfUri).use { input ->
                        requireNotNull(input) { "No se pudo abrir el PDF" }
                        FileOutputStream(tempPdf).use { output -> input.copyTo(output) }
                    }
                    try {
                        Files.move(tempPdf.toPath(), pdfFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(tempPdf.toPath(), pdfFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }

                    val enhancedPages = pages.mapIndexed { index, page ->
                        val enhanced = File(pageDir, "page-${index + 1}.jpg")
                        if (DocumentImageEnhancer.enhanceToJpeg(this@SmartScannerActivity, page.imageUri, enhanced)) enhanced else null
                    }
                    val usableEnhancedPages = enhancedPages.filterNotNull()

                    if (usableEnhancedPages.size == pages.size) {
                        val improvedPdf = File(dir, "document_$stamp.improved.pdf")
                        if (DocumentImageEnhancer.buildPdfFromJpegs(usableEnhancedPages, improvedPdf)) {
                            val backupPdf = File(dir, "document_$stamp.original.pdf")
                            if (pdfFile.renameTo(backupPdf)) {
                                if (improvedPdf.renameTo(pdfFile)) {
                                    backupPdf.delete()
                                } else {
                                    backupPdf.renameTo(pdfFile)
                                    improvedPdf.delete()
                                }
                            } else {
                                improvedPdf.delete()
                            }
                        } else {
                            improvedPdf.delete()
                        }
                    }

                    Triple(dir, pdfFile, textFile to enhancedPages)
                } catch (error: Exception) {
                    tempPdf.delete(); pdfFile.delete(); textFile.delete()
                    pageDir.deleteRecursively()
                    null
                } finally {
                    tempPdf.delete()
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
            runOcr(ocrUris, textFile, onComplete = {
                lifecycleScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        val ocrText = textFile.readText(Charsets.UTF_8)
                        val namedPdf = autoNameDocument(pdfFile, ocrText)
                        val namedText = File(namedPdf.parentFile, namedPdf.nameWithoutExtension + ".txt")
                        if (textFile.absolutePath != namedText.absolutePath && !textFile.renameTo(namedText)) {
                            if (namedPdf.absolutePath != pdfFile.absolutePath) namedPdf.renameTo(pdfFile)
                            return@withContext null
                        }
                        val category = DocumentOrganizer.categoryForText(ocrText)
                        val finalPdf = DocumentOrganizer.moveDocument(namedPdf, namedText, dir, category)
                        Triple(category, finalPdf, pages.size)
                    }
                    if (saved == null) {
                        enhancedPages.filterNotNull().forEach { it.delete() }
                        enhancedPages.firstOrNull()?.parentFile?.deleteRecursively()
                        Toast.makeText(this@SmartScannerActivity, "No se pudo guardar el texto OCR", Toast.LENGTH_LONG).show()
                        finish()
                        return@launch
                    }
                    AiAnalysisScheduler.enqueue(this@SmartScannerActivity, saved.second)
                    enhancedPages.filterNotNull().forEach { it.delete() }
                    enhancedPages.firstOrNull()?.parentFile?.deleteRecursively()
                    Toast.makeText(this@SmartScannerActivity, "Documento guardado en ${saved.first} (${saved.third} página(s))", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }, onFailure = {
                pdfFile.delete()
                textFile.delete()
                enhancedPages.filterNotNull().forEach { it.delete() }
                enhancedPages.firstOrNull()?.parentFile?.deleteRecursively()
                Toast.makeText(this@SmartScannerActivity, "No se pudo guardar el texto OCR", Toast.LENGTH_LONG).show()
                finish()
            })
        }
    }

    private fun autoNameDocument(pdf: File, text: String): File =
        DocumentOrganizer.uniqueRenamedDocument(
            pdf,
            DocumentOrganizer.suggestDocumentName(text, DocumentOrganizer.categoryForText(text))
        )

    private fun sanitizeFileName(value: String): String = DocumentOrganizer.sanitizeFileName(value)

    private fun runOcr(
        uris: List<Uri>,
        textFile: File,
        onComplete: () -> Unit,
        onFailure: () -> Unit
    ) {
        MultilingualOcr.recognizeUris(uris, this) { text ->
            lifecycleScope.launch(Dispatchers.IO) {
                val saved = runCatching {
                    val temp = File(textFile.parentFile ?: textFile, textFile.name + ".tmp")
                    temp.writeText(text.trim() + "\n", Charsets.UTF_8)
                    try {
                        runCatching {
                            Files.move(temp.toPath(), textFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        }.getOrElse {
                            Files.move(temp.toPath(), textFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    } finally {
                        if (temp.exists()) temp.delete()
                    }
                }.isSuccess
                withContext(Dispatchers.Main) {
                    if (saved) onComplete() else onFailure()
                }
            }
        }
    }
}
