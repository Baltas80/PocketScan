package com.baltas80.pocketscan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream

class DocumentsActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private val documents = mutableListOf<File>()
    private val allDocuments = mutableListOf<File>()
    private lateinit var adapter: DocumentAdapter

    private val importImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) importImagesAsPdf(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_documents)
        recyclerView = findViewById(R.id.documentsRecycler)
        searchInput = findViewById(R.id.searchInput)
        adapter = DocumentAdapter(documents, ::openDocument, ::shareDocument, ::renameDocument, ::deleteDocument)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        findViewById<Button>(R.id.importButton).setOnClickListener { importImagesLauncher.launch("image/*") }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filterDocuments(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    override fun onResume() { super.onResume(); loadDocuments() }

    private fun loadDocuments() {
        val dir = File(filesDir, "scans")
        allDocuments.clear()
        allDocuments.addAll(dir.listFiles()?.filter { it.extension.equals("pdf", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList())
        filterDocuments(searchInput.text?.toString().orEmpty())
    }

    private fun filterDocuments(query: String) {
        val normalized = query.trim().lowercase()
        documents.clear()
        if (normalized.isEmpty()) documents.addAll(allDocuments)
        else documents.addAll(allDocuments.filter { file ->
            file.nameWithoutExtension.lowercase().contains(normalized) ||
                File(file.parentFile, file.nameWithoutExtension + ".txt").takeIf { it.exists() }
                    ?.readText(Charsets.UTF_8)?.lowercase()?.contains(normalized) == true
        })
        adapter.notifyDataSetChanged()
    }

    private fun openDocument(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) }
        catch (_: Exception) { Toast.makeText(this, "No hay una aplicación para abrir PDF", Toast.LENGTH_SHORT).show() }
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
        val dir = File(filesDir, "scans").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val pdf = File(dir, "document_import_$stamp.pdf")
        val text = File(dir, "document_import_$stamp.txt")
        val tempFiles = mutableListOf<File>()
        try {
            uris.forEachIndexed { index, uri ->
                val temp = File(dir, "import_${stamp}_$index.jpg")
                contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { output -> input.copyTo(output) } }
                    ?: error("No se pudo leer una imagen")
                tempFiles.add(temp)
            }
            createPdfFromImages(tempFiles, pdf)
            runOcr(tempFiles, text) {
                tempFiles.forEach { it.delete() }
                loadDocuments()
                Toast.makeText(this, "Documento importado", Toast.LENGTH_SHORT).show()
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

    private fun renameDocument(file: File) {
        val input = EditText(this).apply { setText(file.nameWithoutExtension); selectAll() }
        AlertDialog.Builder(this).setTitle(R.string.rename_document).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                val target = File(file.parentFile, "$newName.pdf")
                if (target.exists()) { Toast.makeText(this, R.string.file_already_exists, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                if (file.renameTo(target)) {
                    File(file.parentFile, file.nameWithoutExtension + ".txt").renameTo(File(file.parentFile, "$newName.txt"))
                    loadDocuments()
                } else Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun deleteDocument(file: File) {
        AlertDialog.Builder(this).setTitle(R.string.delete_document).setMessage(file.name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val deleted = file.delete()
                File(file.parentFile, file.nameWithoutExtension + ".txt").delete()
                if (deleted) loadDocuments() else Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }.show()
    }
}
