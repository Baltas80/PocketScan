package com.baltas80.pocketscan

import android.content.Intent
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

        val categories = listOf("Todas") + DocumentOrganizer.categories
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        categorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = applyFilters()
        }
        findViewById<Button>(R.id.importButton).setOnClickListener { importImagesLauncher.launch("image/*") }
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
        scansDir.mkdirs()
        allDocuments.clear()
        allDocuments.addAll(scansDir.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) }.sortedByDescending { it.lastModified() })
        applyFilters()
    }

    private fun applyFilters() {
        val query = searchInput.text?.toString().orEmpty().trim().lowercase()
        val selected = categorySpinner.selectedItem?.toString() ?: "Todas"
        documents.clear()
        documents.addAll(allDocuments.filter { file ->
            val category = DocumentOrganizer.categoryForFile(file, scansDir)
            if (selected != "Todas" && category != selected) return@filter false
            if (query.isEmpty()) return@filter true
            file.nameWithoutExtension.lowercase().contains(query) || File(file.parentFile, file.nameWithoutExtension + ".txt").takeIf { it.exists() }?.readText(Charsets.UTF_8)?.lowercase()?.contains(query) == true
        })
        adapter.notifyDataSetChanged()
    }

    private fun openDocument(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        try { startActivity(intent) } catch (_: Exception) { Toast.makeText(this, "No hay una aplicación para abrir PDF", Toast.LENGTH_SHORT).show() }
    }

    private fun shareDocument(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivity(Intent.createChooser(intent, getString(R.string.share_document)))
    }

    private fun importImagesAsPdf(uris: List<Uri>) {
        scansDir.mkdirs()
        val stamp = System.currentTimeMillis()
        val pdf = File(scansDir, "document_import_$stamp.pdf")
        val text = File(scansDir, "document_import_$stamp.txt")
        val tempFiles = mutableListOf<File>()
        try {
            uris.forEachIndexed { index, uri ->
                val temp = File(scansDir, "import_${stamp}_$index.jpg")
                contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { output -> input.copyTo(output) } } ?: error("No se pudo leer una imagen")
                tempFiles.add(temp)
            }
            createPdfFromImages(tempFiles, pdf)
            runOcr(tempFiles, text) {
                tempFiles.forEach { it.delete() }
                val renamedPdf = autoNameDocument(pdf, text.readText(Charsets.UTF_8), "Documento importado")
                val finalText = File(renamedPdf.parentFile, renamedPdf.nameWithoutExtension + ".txt")
                if (text.exists() && text.absolutePath != finalText.absolutePath) text.renameTo(finalText)
                val category = DocumentOrganizer.categoryForText(finalText.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty())
                DocumentOrganizer.moveDocument(renamedPdf, finalText, scansDir, category)
                loadDocuments()
                Toast.makeText(this, "Documento importado en $category", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            tempFiles.forEach { it.delete() }; pdf.delete(); text.delete()
            Toast.makeText(this, e.message ?: "No se pudo importar", Toast.LENGTH_LONG).show()
        }
    }

    private fun createPdfFromImages(files: List<File>, pdfFile: File) {
        val document = android.graphics.pdf.PdfDocument()
        try {
            files.forEachIndexed { index, file ->
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: error("Imagen no válida")
                val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                val margin = 24f
                val scale = minOf((595f - margin * 2) / bitmap.width, (842f - margin * 2) / bitmap.height)
                val width = bitmap.width * scale; val height = bitmap.height * scale
                val left = (595f - width) / 2f; val top = (842f - height) / 2f
                page.canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + width, top + height), null)
                document.finishPage(page); bitmap.recycle()
            }
            FileOutputStream(pdfFile).use { document.writeTo(it) }
        } finally { document.close() }
    }

    private fun runOcr(files: List<File>, textFile: File, onComplete: () -> Unit) {
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        val all = StringBuilder()
        fun complete() { try { textFile.writeText(all.toString(), Charsets.UTF_8) } finally { recognizer.close(); onComplete() } }
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

    private fun renameDocument(file: File) {
        val input = EditText(this).apply { setText(file.nameWithoutExtension); selectAll() }
        AlertDialog.Builder(this).setTitle(R.string.rename_document).setView(input).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = sanitizeFileName(input.text.toString().trim())
                if (newName.isEmpty()) return@setPositiveButton
                val target = File(file.parentFile, "$newName.pdf")
                if (target.exists()) { Toast.makeText(this, R.string.file_already_exists, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                if (file.renameTo(target)) {
                    File(file.parentFile, file.nameWithoutExtension + ".txt").renameTo(File(file.parentFile, "$newName.txt")); loadDocuments()
                } else Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun deleteDocument(file: File) {
        AlertDialog.Builder(this).setTitle(R.string.delete_document).setMessage(file.name).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val deleted = file.delete(); File(file.parentFile, file.nameWithoutExtension + ".txt").delete()
                if (deleted) loadDocuments() else Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }.show()
    }
}
