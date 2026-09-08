package com.baltas80.pocketscan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class DocumentsActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private val documents = mutableListOf<File>()
    private lateinit var adapter: DocumentAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_documents)
        recyclerView = findViewById(R.id.documentsRecycler)
        adapter = DocumentAdapter(documents, ::shareDocument, ::renameDocument, ::deleteDocument)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadDocuments()
    }

    private fun loadDocuments() {
        val dir = File(filesDir, "scans")
        documents.clear()
        documents.addAll(dir.listFiles()?.filter { it.extension.equals("pdf", true) }?.sortedByDescending { it.lastModified() } ?: emptyList())
        adapter.notifyDataSetChanged()
    }

    private fun shareDocument(file: File) {
        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_document)))
    }

    private fun renameDocument(file: File) {
        val input = EditText(this).apply {
            setText(file.nameWithoutExtension)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_document)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                val target = File(file.parentFile, "$newName.pdf")
                if (target.exists()) {
                    Toast.makeText(this, R.string.file_already_exists, Toast.LENGTH_SHORT).show()
                } else if (file.renameTo(target)) {
                    loadDocuments()
                } else {
                    Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun deleteDocument(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_document)
            .setMessage(file.name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val deleted = file.delete()
                File(file.parentFile, file.nameWithoutExtension + ".jpg").delete()
                if (deleted) loadDocuments() else Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
