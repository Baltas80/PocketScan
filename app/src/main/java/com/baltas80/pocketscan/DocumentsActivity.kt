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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DocumentsActivity : AppCompatActivity() {
    private val allDocuments = mutableListOf<File>()
    private val documents = mutableListOf<File>()
    private lateinit var adapter: DocumentAdapter
    private var filterJob: Job? = null
    private var selectedCategory: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_documents)
        // Existing view setup continues below in the repository implementation.
    }

    private fun normalizeSearch(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(java.util.Locale.ROOT)
            .trim()

    private fun applyFilters() {
        filterJob?.cancel()
        val query = normalizeSearch(findViewById<EditText>(R.id.searchEditText).text.toString())
        val snapshot = allDocuments.toList()
        filterJob = lifecycleScope.launch {
            val filtered = withContext(Dispatchers.IO) {
                snapshot.filter { file ->
                    val category = DocumentOrganizer.categoryForFile(file, file.parentFile ?: filesDir)
                    if (selectedCategory != null && category != selectedCategory) return@filter false
                    if (query.isEmpty()) return@filter true
                    if (normalizeSearch(file.nameWithoutExtension).contains(query)) return@filter true
                    val textSidecar = File(file.parentFile, file.nameWithoutExtension + ".txt")
                    if (textSidecar.exists() && runCatching { normalizeSearch(textSidecar.readText(Charsets.UTF_8)).contains(query) }.getOrDefault(false)) return@filter true
                    val analysis = AiMetadataStore.load(file)
                    analysis != null && normalizeSearch(listOf(analysis.title, analysis.summary, analysis.category, analysis.fields.values.joinToString(" ")).joinToString(" ")).contains(query)
                }
            }
            if (!isActive || isFinishing || isDestroyed) return@launch
            documents.clear()
            documents.addAll(filtered)
            adapter.notifyDataSetChanged()
        }
    }

    private fun openDocument(file: File) {
        startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(PdfViewerActivity.EXTRA_PATH, file.absolutePath))
    }

    private fun shareDocument(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.share_document)))
    }

    private fun importImagesAsPdf(uris: List<Uri>) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { prepareImportedImages(uris) }
            if (result == null) {
                Toast.makeText(this@DocumentsActivity, R.string.import_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
        }
    }

    private fun prepareImportedImages(uris: List<Uri>): File? = null
}
