package com.baltas80.pocketscan

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfViewerActivity : AppCompatActivity() {
    private lateinit var pdfFile: File
    private lateinit var pageList: RecyclerView
    private var pendingText = ""

    private val textExportLauncher = registerForActivityResult(CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        writePendingText(uri)
    }

    private val correctedTextExportLauncher = registerForActivityResult(CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        writePendingText(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) { finish(); return }
        pdfFile = File(path)
        if (!pdfFile.isFile) {
            Toast.makeText(this, "No se encontró el documento", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.viewerTitle).text = pdfFile.name
        findViewById<Button>(R.id.viewerAi).setOnClickListener { analyzeWithAi() }
        findViewById<Button>(R.id.viewerAsk).setOnClickListener { askAboutDocument() }
        findViewById<Button>(R.id.viewerCorrectOcr).setOnClickListener { correctOcrWithAi() }
        findViewById<Button>(R.id.viewerExportText).setOnClickListener { exportOcrText() }
        findViewById<Button>(R.id.viewerShare).setOnClickListener { shareDocument() }
        findViewById<Button>(R.id.viewerClose).setOnClickListener { finish() }
        pageList = findViewById(R.id.pdfPagesRecycler)
        pageList.layoutManager = LinearLayoutManager(this)
        lifecycleScope.launch {
            val pageCount = withContext(Dispatchers.IO) { countPages() }
            if (pageCount <= 0 || isFinishing || isDestroyed) {
                Toast.makeText(this@PdfViewerActivity, "No se pudo abrir el PDF", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            pageList.adapter = PdfPageAdapter(pdfFile, pageCount)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::pdfFile.isInitialized) AppLockManager.authenticateIfNeeded(this) { finish() }
    }

    private fun readOcr(): String {
        val textFile = File(pdfFile.parentFile, pdfFile.nameWithoutExtension + ".txt")
        return runCatching { if (textFile.isFile) textFile.readText(Charsets.UTF_8) else "" }.getOrDefault("")
    }

    private fun exportOcrText() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { readOcr().trim() }
            if (text.isBlank()) {
                Toast.makeText(this@PdfViewerActivity, "Este documento no tiene texto OCR disponible", Toast.LENGTH_LONG).show()
                return@launch
            }
            pendingText = text + "\n"
            val baseName = pdfFile.nameWithoutExtension.ifBlank { "documento" }
            textExportLauncher.launch("$baseName.txt")
        }
    }

    private fun correctOcrWithAi() {
        val button = findViewById<Button>(R.id.viewerCorrectOcr)
        button.isEnabled = false
        lifecycleScope.launch {
            try {
                val ocr = withContext(Dispatchers.IO) { readOcr().trim() }
                if (ocr.isBlank()) {
                    Toast.makeText(this@PdfViewerActivity, "Este documento no tiene texto OCR disponible", Toast.LENGTH_LONG).show()
                    return@launch
                }
                Toast.makeText(this@PdfViewerActivity, "Corrigiendo errores de OCR con IA…", Toast.LENGTH_SHORT).show()
                val corrected = AiPdfAssistant.correctOcr(ocr).trim()
                if (corrected.isBlank()) throw IllegalStateException("La IA no devolvió texto corregido")
                pendingText = corrected + "\n"
                val baseName = pdfFile.nameWithoutExtension.ifBlank { "documento" }
                correctedTextExportLauncher.launch("$baseName-corregido.txt")
            } catch (error: Throwable) {
                Toast.makeText(
                    this@PdfViewerActivity,
                    error.message ?: "No se pudo corregir el OCR",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                button.isEnabled = true
            }
        }
    }

    private fun writePendingText(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(pendingText.toByteArray(Charsets.UTF_8))
                } ?: false
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@PdfViewerActivity,
                    if (success) "Texto exportado correctamente" else "No se pudo exportar el texto",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun analyzeWithAi() {
        findViewById<Button>(R.id.viewerAi).isEnabled = false
        lifecycleScope.launch {
            val ocr = withContext(Dispatchers.IO) { readOcr() }
            val result = AiDocumentAnalyzer.analyze(pdfFile, ocr)
            findViewById<Button>(R.id.viewerAi).isEnabled = true
            result.onSuccess { analysis ->
                AiMetadataStore.save(pdfFile, analysis)
                val fields = analysis.fields.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                val message = buildString {
                    append("Categoría: ${analysis.category}\n")
                    append("Título: ${analysis.title}\n\n")
                    if (analysis.summary.isNotBlank()) append("Resumen:\n${analysis.summary}\n\n")
                    if (fields.isNotBlank()) append("Datos extraídos:\n$fields")
                }
                AlertDialog.Builder(this@PdfViewerActivity)
                    .setTitle("Análisis inteligente")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }.onFailure { error ->
                Toast.makeText(this@PdfViewerActivity, error.message ?: "No se pudo analizar el documento", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun askAboutDocument() {
        val input = EditText(this).apply {
            hint = "Ej.: ¿Cuál es el importe total?"
            setSingleLine(false)
            minLines = 2
            maxLines = 4
        }
        val answer = TextView(this).apply {
            text = "La respuesta aparecerá aquí."
            setTextIsSelectable(true)
            setPadding(0, 16, 0, 0)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(input, android.widget.LinearLayout.LayoutParams(-1, -2))
            addView(answer, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Preguntar sobre este documento")
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Preguntar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val question = input.text.toString().trim()
                if (question.isBlank()) {
                    input.error = "Escribe una pregunta"
                    return@setOnClickListener
                }
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false
                answer.text = "Analizando el documento…\n"
                lifecycleScope.launch {
                    runCatching {
                        AiPdfAssistant.answerStream(pdfFile, withContext(Dispatchers.IO) { readOcr() }, question)
                            .collect { chunk -> answer.append(chunk) }
                    }.onFailure {
                        answer.append("\n\nNo se pudo completar la consulta: ${it.message ?: "error de IA"}")
                    }
                    button.isEnabled = true
                }
            }
        }
        dialog.show()
    }

    private fun countPages(): Int = runCatching {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { it.pageCount }
        }
    }.getOrDefault(0)

    private fun shareDocument() {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", pdfFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_document)))
    }

    companion object { const val EXTRA_PATH = "pdf_path" }
}

private class PdfPageAdapter(private val file: File, private val pageCount: Int) : RecyclerView.Adapter<PdfPageAdapter.PageHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
        PageHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false))
    override fun getItemCount(): Int = pageCount
    override fun onBindViewHolder(holder: PageHolder, position: Int) { holder.bind(file, position) }
    override fun onViewRecycled(holder: PageHolder) { holder.clear(); super.onViewRecycled(holder) }

    class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image = view.findViewById<android.widget.ImageView>(R.id.pdfPageImage)
        private val label = view.findViewById<TextView>(R.id.pdfPageNumber)
        private var generation = 0
        private var bitmap: Bitmap? = null

        fun bind(file: File, pageIndex: Int) {
            val currentGeneration = ++generation
            image.setImageDrawable(null)
            label.text = "Página ${pageIndex + 1}"
            ViewerScope.scope.launch(Dispatchers.IO) {
                val rendered = render(file, pageIndex, image.resources.displayMetrics.widthPixels)
                withContext(Dispatchers.Main) {
                    if (generation == currentGeneration && rendered != null) {
                        bitmap = rendered
                        image.setImageBitmap(rendered)
                    } else rendered?.recycle()
                }
            }
        }

        fun clear() {
            generation++
            image.setImageDrawable(null)
            bitmap?.recycle()
            bitmap = null
        }

        private fun render(file: File, index: Int, targetWidth: Int): Bitmap? = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(index).use { page ->
                        val width = targetWidth.coerceIn(480, 1400)
                        val ratio = page.height.toFloat() / page.width.toFloat()
                        val height = (width * ratio).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }.getOrNull()
    }
}

private object ViewerScope {
    val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
}
