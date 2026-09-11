package com.baltas80.pocketscan

import android.content.Intent
import android.graphics.Bitmap
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
    private var filterJob: Job? = null
    private val scansDir get() = File(filesDir, "scans")
    private val categoryKeys = DocumentOrganizer.categories
    private val importImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (!uris.isNullOrEmpty()) importImagesAsPdf(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_documents)
        recyclerView = findViewById(R.id.documentsRecycler); searchInput = findViewById(R.id.searchInput); categorySpinner = findViewById(R.id.categorySpinner)
        adapter = DocumentAdapter(documents, scansDir, ::openDocument, ::shareDocument, ::renameDocument, ::deleteDocument)
        recyclerView.layoutManager = LinearLayoutManager(this); recyclerView.adapter = adapter
        val categoryLabels = listOf(getString(R.string.all_categories), getString(R.string.category_general), getString(R.string.category_invoices), getString(R.string.category_quotes), getString(R.string.category_contracts), getString(R.string.category_receipts), getString(R.string.category_tickets), getString(R.string.category_payroll), getString(R.string.category_certificates), getString(R.string.category_reports), getString(R.string.category_appointments))
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categoryLabels)
        categorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener { override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit; override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = applyFilters() }
        findViewById<Button>(R.id.importButton).setOnClickListener { importImagesLauncher.launch("image/*") }
        findViewById<Button>(R.id.aiLibraryButton).setOnClickListener { startActivity(Intent(this, AiAssistantActivity::class.java)) }
        searchInput.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilters(); override fun afterTextChanged(s: Editable?) = Unit })
    }

    override fun onResume() {
        super.onResume()
        AppLockManager.ensureUnlocked(this) { success ->
            if (success && !isFinishing && !isDestroyed) loadDocuments()
            else if (!success && !isFinishing) finish()
        }
    }

    override fun onDestroy() {
        filterJob?.cancel()
        super.onDestroy()
    }

    private fun loadDocuments() {
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { scansDir.mkdirs(); scansDir.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) }.sortedByDescending { it.lastModified() }.toList() }
            if (isFinishing || isDestroyed) return@launch
            allDocuments.clear(); allDocuments.addAll(found); applyFilters()
        }
    }

    private fun applyFilters() {
        filterJob?.cancel()
        val query = normalizeSearch(searchInput.text?.toString().orEmpty().trim())
        val selectedCategory = categorySpinner.selectedItemPosition.takeIf { it > 0 }?.let { categoryKeys.getOrNull(it - 1) }
        val snapshot = allDocuments.toList()
        filterJob = lifecycleScope.launch {
            val filtered = withContext(Dispatchers.IO) {
                snapshot.filter { file ->
                    val category = DocumentOrganizer.categoryForFile(file, scansDir)
                    if (selectedCategory != null && category != selectedCategory) return@filter false
                    if (query.isEmpty()) return@filter true
                    if (normalizeSearch(file.nameWithoutExtension).contains(query)) return@filter true
                    val textSidecar = File(file.parentFile, file.nameWithoutExtension + ".txt")
                    if (textSidecar.exists() && runCatching { normalizeSearch(textSidecar.readText(Charsets.UTF_8)).contains(query) }.getOrDefault(false)) return@filter true
                    val analysis = AiMetadataStore.load(file)
                    analysis != null && normalizeSearch(listOf(analysis.title, analysis.summary, analysis.category, analysis.fields.values.joinToString(" ")).joinToString(" ")).contains(query)
                }
            }
            if (isFinishing || isDestroyed || !isActive) return@launch
            documents.clear(); documents.addAll(filtered); adapter.notifyDataSetChanged()
        }
    }

    private fun openDocument(file: File) { startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(PdfViewerActivity.EXTRA_PATH, file.absolutePath)) }
    private fun shareDocument(file: File) { val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file); startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, getString(R.string.share_document))) }

    private fun importImagesAsPdf(uris: List<Uri>) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { prepareImportedImages(uris) }
            if (result == null) { Toast.makeText(this@DocumentsActivity, R.string.import_failed, Toast.LENGTH_LONG).show(); return@launch }
            val (tempFiles, pdf, text) = result
            createPdfFromImagesAsync(tempFiles, pdf) { pdfCreated ->
                if (!pdfCreated) { tempFiles.forEach { it.delete() }; pdf.delete(); text.delete(); Toast.makeText(this@DocumentsActivity, R.string.pdf_failed, Toast.LENGTH_LONG).show(); return@createPdfFromImagesAsync }
                runOcr(tempFiles, text) {
                    lifecycleScope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            tempFiles.forEach { it.delete() }
                            val ocr = runCatching { text.readText(Charsets.UTF_8) }.getOrDefault("")
                            val renamedPdf = autoNameDocument(pdf, ocr, getString(R.string.my_documents))
                            val finalText = File(renamedPdf.parentFile, renamedPdf.nameWithoutExtension + ".txt")
                            if (text.exists() && text.absolutePath != finalText.absolutePath) text.renameTo(finalText)
                            val finalOcr = runCatching { finalText.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty() }.getOrDefault(ocr)
                            val detected = DocumentOrganizer.categoryForText(finalOcr)
                            val finalPdf = DocumentOrganizer.moveDocument(renamedPdf, finalText, scansDir, detected)
                            Pair(detected, finalPdf)
                        }
                        AiAnalysisScheduler.enqueue(this@DocumentsActivity, saved.second)
                        loadDocuments(); Toast.makeText(this@DocumentsActivity, getString(R.string.document_imported, saved.first), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private data class ImportFiles(val tempFiles: List<File>, val pdf: File, val text: File)
    private fun prepareImportedImages(uris: List<Uri>): ImportFiles? { scansDir.mkdirs(); val stamp = System.currentTimeMillis(); val pdf = File(scansDir, "document_import_$stamp.pdf"); val text = File(scansDir, "document_import_$stamp.txt"); val tempFiles = mutableListOf<File>(); return try { uris.forEachIndexed { index, uri -> val temp = File(scansDir, "import_${stamp}_$index.jpg"); contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { output -> input.copyTo(output) } } ?: error("Unable to read image"); tempFiles.add(temp) }; ImportFiles(tempFiles, pdf, text) } catch (_: Exception) { tempFiles.forEach { it.delete() }; pdf.delete(); text.delete(); null } }
    private fun createPdfFromImagesAsync(files: List<File>, pdfFile: File, onComplete: (Boolean) -> Unit) { lifecycleScope.launch { val success = withContext(Dispatchers.Default) { runCatching { createPdfFromImages(files, pdfFile) }.isSuccess }; onComplete(success) } }
    private fun createPdfFromImages(files: List<File>, pdfFile: File) {
        val document = PdfDocument()
        try {
            files.forEachIndexed { index, file ->
                val bitmap = decodeBitmapForPdf(file) ?: error("Invalid image")
                try {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    try {
                        val margin = 24f
                        val scale = minOf((595f - margin * 2) / bitmap.width, (842f - margin * 2) / bitmap.height)
                        val width = bitmap.width * scale
                        val height = bitmap.height * scale
                        val left = (595f - width) / 2f
                        val top = (842f - height) / 2f
                        page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), null)
                    } finally {
                        document.finishPage(page)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            FileOutputStream(pdfFile).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
    private fun decodeBitmapForPdf(file: File): Bitmap? {
        val maxDimension = 2200
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
    }
    private fun runOcr(files: List<File>, textFile: File, onComplete: () -> Unit) {
        MultilingualOcr.recognize(files, this) { text ->
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { textFile.writeText(text, Charsets.UTF_8) }
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }
    private fun autoNameDocument(pdf: File, ocrText: String, fallback: String): File { val dir = pdf.parentFile ?: return pdf; val candidate = suggestDocumentName(ocrText, fallback); var target = File(dir, "$candidate.pdf"); var counter = 2; while (target.exists() && target.absolutePath != pdf.absolutePath) { target = File(dir, "$candidate ($counter).pdf"); counter++ }; if (target.absolutePath == pdf.absolutePath || !pdf.renameTo(target)) return pdf; return target }
    private fun suggestDocumentName(text: String, fallback: String): String { val lines = text.lines().map { it.trim() }.filter { it.length >= 4 }; val type = when (DocumentOrganizer.categoryForText(text)) { DocumentOrganizer.FACTURAS -> "Factura"; DocumentOrganizer.PRESUPUESTOS -> "Presupuesto"; DocumentOrganizer.CONTRATOS -> "Contrato"; DocumentOrganizer.RECIBOS -> "Recibo"; DocumentOrganizer.TICKETS -> "Ticket"; DocumentOrganizer.NOMINAS -> "Nomina"; DocumentOrganizer.CERTIFICADOS -> "Certificado"; DocumentOrganizer.INFORMES -> "Informe"; DocumentOrganizer.CITAS -> "Cita"; else -> fallback }; val usefulLine = lines.firstOrNull { line -> !line.lowercase().contains(type.lowercase()) && !line.lowercase().matches(Regex("[0-9 ./:-]+")) }; val cleanLine = sanitizeFileName(usefulLine ?: type).take(45).trim().trim('.', '_', '-'); return sanitizeFileName(if (cleanLine.length >= 4) "$type - $cleanLine" else type).take(80).trim().ifEmpty { "Documento" } }
    private fun sanitizeFileName(value: String): String { val withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD).replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), ""); return withoutAccents.replace(Regex("[^A-Za-z0-9 _()-]"), "_").replace(Regex("\\s+"), " ").trim() }
    private fun normalizeSearch(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    private fun renameDocument(file: File) { val input = EditText(this).apply { setText(file.nameWithoutExtension); selectAll() }; AlertDialog.Builder(this).setTitle(R.string.rename_document).setView(input).setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.rename) { _, _ -> val newName = sanitizeFileName(input.text.toString().trim()); if (newName.isEmpty()) return@setPositiveButton; val target = File(file.parentFile, "$newName.pdf"); if (target.exists() || File(target.parentFile, "$newName.txt").exists() || File(target.parentFile, "$newName.ai.json").exists()) { Toast.makeText(this, R.string.file_already_exists, Toast.LENGTH_SHORT).show(); return@setPositiveButton }; lifecycleScope.launch { val success = withContext(Dispatchers.IO) { renameDocumentFiles(file, target) }; if (success) loadDocuments() else Toast.makeText(this@DocumentsActivity, R.string.rename_failed, Toast.LENGTH_SHORT).show() } }.show() }
    private fun renameDocumentFiles(file: File, target: File): Boolean {
        val oldText = File(file.parentFile, file.nameWithoutExtension + ".txt")
        val newText = File(target.parentFile, target.nameWithoutExtension + ".txt")
        val oldAi = AiMetadataStore.sidecarFor(file)
        val newAi = File(target.parentFile, target.nameWithoutExtension + ".ai.json")
        if (target.exists() || newText.exists() || newAi.exists()) return false
        AiAnalysisScheduler.cancel(this, file)
        if (!file.renameTo(target)) {
            AiAnalysisScheduler.enqueue(this, file)
            return false
        }
        val textExists = oldText.isFile
        val aiExists = oldAi.isFile
        val textMoved = !textExists || oldText.renameTo(newText)
        val aiMoved = !aiExists || oldAi.renameTo(newAi)
        if (textMoved && aiMoved) {
            AiAnalysisScheduler.enqueue(this, target)
            return true
        }
        if (aiMoved && aiExists) newAi.renameTo(oldAi)
        if (textMoved && textExists) newText.renameTo(oldText)
        target.renameTo(file)
        AiAnalysisScheduler.enqueue(this, file)
        return false
    }
    private fun deleteDocument(file: File) { AlertDialog.Builder(this).setTitle(R.string.delete_document).setMessage(file.name).setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.delete) { _, _ -> lifecycleScope.launch { AiAnalysisScheduler.cancel(this@DocumentsActivity, file); val deleted = withContext(Dispatchers.IO) { if (!file.isFile || !file.delete()) false else { File(file.parentFile, file.nameWithoutExtension + ".txt").delete(); AiMetadataStore.delete(file); true } }; if (deleted) loadDocuments() else { AiAnalysisScheduler.enqueue(this@DocumentsActivity, file); Toast.makeText(this@DocumentsActivity, R.string.delete_failed, Toast.LENGTH_LONG).show() } } }.show() }
}
