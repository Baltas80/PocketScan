package com.baltas80.pocketscan

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer

class DocumentsActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var categorySpinner: Spinner
    private val documents = mutableListOf<File>()
    private val allDocuments = mutableListOf<File>()
    private lateinit var adapter: DocumentAdapter
    private val scansDir get() = File(filesDir, "scans")

    private val importImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) importImagesAsPdf(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_documents)
        recyclerView = findViewById(R.id.documentsRecycler)
        searchInput = findViewById(R.id.searchInput)
        categorySpinner = findViewById(R.id.categorySpinner)
        adapter = DocumentAdapter(documents, scansDir, ::openDocument, ::shareDocument, ::renameDocument, ::deleteDocument)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val categories = listOf(getString(R.string.my_documents).let { "Todas" }) + DocumentOrganizer.categories
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        categorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = applyFilters()
        }
        findViewById<Button>(R.id.importButton).setOnClickListener { importImagesLauncher.launch("image/*") }
        findViewById<Button>(R.id.aiLibraryButton).setOnClickListener {
            startActivity(Intent(this, AiAssistantActivity::class.java))
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilters()
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        AppLockManager.authenticateIfNeeded(this) { finish() }
        loadDocuments()
    }

    private fun loadDocuments() {
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                scansDir.mkdirs()
                scansDir.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) }
                    .sortedByDescending { it.lastModified() }.toList()
            }
            if (isFinishing || isDestroyed) return@launch
            allDocuments.clear(); allDocuments.addAll(found); applyFilters()
        }
    }

    private fun applyFilters() {
        val query = searchInput.text?.toString().orEmpty().trim().lowercase()
        val selected = categorySpinner.selectedItem?.toString() ?: "Todas"
        val snapshot = allDocuments.toList()
        lifecycleScope.launch {
            val filtered = withContext(Dispatchers.IO) {
                snapshot.filter { file ->
                    val category = DocumentOrganizer.categoryForFile(file, scansDir)
                    if (selected != "Todas" && category != selected) return@filter false
                    if (query.isEmpty()) return@filter true
                    if (file.nameWithoutExtension.lowercase().contains(query)) return@filter true
                    val textSidecar = File(file.parentFile, file.nameWithoutExtension + ".txt")
                    if (textSidecar.exists() && runCatching { textSidecar.readText(Charsets.UTF_8).lowercase().contains(query) }.getOrDefault(false)) return@filter true
                    val analysis = AiMetadataStore.load(file)
                    analysis != null && normalizeSearch(listOf(analysis.title, analysis.summary, analysis.category, analysis.fields.values.joinToString(" ")).joinToString(" ")).contains(normalizeSearch(query))
                }
            }
            if (isFinishing || isDestroyed) return@launch
            documents.clear(); documents.addAll(filtered); adapter.notifyDataSetChanged()
        }
    }

    private fun openDocument(file: File) {
        startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(PdfViewerActivity.EXTRA_PATH, file.absolutePath))
    }

    private fun shareDocument(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_document)))
    }

    private fun importImagesAsPdf(uris: List<Uri>) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { prepareImportedImages(uris) }
            if (result == null) {
                Toast.makeText(this@DocumentsActivity, R.string.import_failed, Toast.LENGTH_LONG).show(); return@launch
            }
            val (tempFiles, pdf, text) = result
            createPdfFromImagesAsync(tempFiles, pdf) { pdfCreated ->
                if (!pdfCreated) {
                    tempFiles.forEach { it.delete() }; pdf.delete(); text.delete()
                    Toast.makeText(this@DocumentsActivity, R.string.pdf_failed, Toast.LENGTH_LONG).show(); return@createPdfFromImagesAsync
                }
                runOcr(tempFiles, text) {
                    lifecycleScope.launch {
                        val category = withContext(Dispatchers.IO) {
                            tempFiles.forEach { it.delete() }
                            val ocr = runCatching { text.readText(Charsets.UTF_8) }.getOrDefault("")
                            val renamedPdf = autoNameDocument(pdf, ocr, getString(R.string.my_documents))
                            val finalText = File(renamedPdf.parentFile, renamedPdf.nameWithoutExtension + ".txt")
                            if (text.exists() && text.absolutePath != finalText.absolutePath) text.renameTo(finalText)
                            val finalOcr = runCatching { finalText.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty() }.getOrDefault(ocr)
                            val detected = DocumentOrganizer.categoryForText(finalOcr)
                            DocumentOrganizer.moveDocument(renamedPdf, finalText, scansDir, detected)
                            detected
                        }
                        loadDocuments()
                        Toast.makeText(this@DocumentsActivity, getString(R.string.document_imported, category), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private data class ImportFiles(val tempFiles: List<File>, val pdf: File, val text: File)

    private fun prepareImportedImages(uris: List<Uri>): ImportFiles? {
        scansDir.mkdirs()
        val stamp = System.currentTimeMillis()
        val pdf = File(scansDir, "document_import_$stamp.pdf")
        val text = File(scansDir, "document_import_$stamp.txt")
        val tempFiles = mutableListOf<File>()
        return try {
            uris.forEachIndexed { index, uri ->
                val temp = File(scansDir, "import_${stamp}_$index.jpg")
                contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { output -> input.copyTo(output) } } ?: error("No se pudo leer una imagen")
                tempFiles.add(temp)
            }
            ImportFiles(tempFiles, pdf, text)
        } catch (_: Exception) {
            tempFiles.forEach { it.delete() }; pdf.delete(); text.delete(); null
        }
    }

    private fun createPdfFromImagesAsync(files: List<File>, pdfFile: File, onComplete: (Boolean) -> Unit) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.Default) { runCatching { createPdfFromImages(files, pdfFile) }.isSuccess }
            onComplete(success)
        }
    }

    private fun createPdfFromImages(files: List<File>, pdfFile: File) {
        val document = PdfDocument()
        try {
            files.forEachIndexed { index, file ->
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: error("Imagen no válida")
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                val margin = 24f
                val scale = minOf((595f - margin * 2) / bitmap.width, (842f - margin * 2) / bitmap.height)
                val width = bitmap.width * scale; val height = bitmap.height * scale
                val left = (595f - width) / 2f; val top = (842f - height) / 2f
                page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), null)
                document.finishPage(page); bitmap.recycle()
            }
            FileOutputStream(pdfFile).use { document.writeTo(it) }
        } finally { document.close() }
    }

    private fun runOcr(files: List<File>, textFile: File, onComplete: () -> Unit) {
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        val all = StringBuilder()
        fun complete() {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { textFile.writeText(all.toString(), Charsets.UTF_8) }
                withContext(Dispatchers.Main) { recognizer.close(); onComplete() }
            }
        }
        fun next(index: Int) {
            if (index >= files.size) { complete(); return }
            try {
                val image = com.google.mlkit.vision.common.InputImage.fromFilePath(this, Uri.fromFile(files[index]))
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
        val candidate = suggestDocumentName(ocrText, fallback)
        var target = File(dir, "$candidate.pdf")
        var counter = 2
        while (target.exists() && target.absolutePath != pdf.absolutePath) { target = File(dir, "$candidate ($counter).pdf"); counter++ }
        if (target.absolutePath == pdf.absolutePath || !pdf.renameTo(target)) return pdf
        return target
    }

    private fun suggestDocumentName(text: String, fallback: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.length >= 4 }
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
            else -> fallback
        }
        val usefulLine = lines.firstOrNull { line -> !line.lowercase().contains(type.lowercase()) && !line.lowercase().matches(Regex("[0-9 ./:-]+")) }
        val cleanLine = sanitizeFileName(usefulLine ?: type).take(45).trim().trim('.', '_', '-')
        return sanitizeFileName(if (cleanLine.length >= 4) "$type - $cleanLine" else type).take(80).trim().ifEmpty { "Documento" }
    }

    private fun sanitizeFileName(value: String): String {
        val withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD).replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return withoutAccents.replace(Regex("[^A-Za-z0-9 _()-]"), "_").replace(Regex("\\s+"), " ").trim()
    }

    private fun normalizeSearch(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")

    private fun renameDocument(file: File) {
        val input = EditText(this).apply { setText(file.nameWithoutExtension); selectAll() }
        AlertDialog.Builder(this).setTitle(R.string.rename_document).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = sanitizeFileName(input.text.toString().trim())
                if (newName.isEmpty()) return@setPositiveButton
                val target = File(file.parentFile, "$newName.pdf")
                if (target.exists()) { Toast.makeText(this, R.string.file_already_exists, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        if (!file.renameTo(target)) false else {
                            File(file.parentFile, file.nameWithoutExtension + ".txt").renameTo(File(file.parentFile, "$newName.txt"))
                            val aiSidecar = File(file.parentFile, file.nameWithoutExtension + ".ai.json")
                            aiSidecar.renameTo(File(file.parentFile, "$newName.ai.json")); true
                        }
                    }
                    if (success) loadDocuments() else Toast.makeText(this@DocumentsActivity, R.string.rename_failed, Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun deleteDocument(file: File) {
        AlertDialog.Builder(this).setTitle(R.string.delete_document).setMessage(file.name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        val result = file.delete(); File(file.parentFile, file.nameWithoutExtension + ".txt").delete(); AiMetadataStore.delete(file); result
                    }
                    if (deleted) loadDocuments() else Toast.makeText(this@DocumentsActivity, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                }
            }.show()
    }
}
