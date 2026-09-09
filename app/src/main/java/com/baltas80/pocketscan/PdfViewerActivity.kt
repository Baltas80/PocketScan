package com.baltas80.pocketscan

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
