package com.baltas80.pocketscan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
    private lateinit var searchInput: EditText
    private val documents = mutableListOf<File>()
    private val allDocuments = mutableListOf<File>()
    private lateinit var adapter: DocumentAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_documents)
        recyclerView = findViewById(R.id.documentsRecycler)
        searchInput = findViewById(R.id.searchInput)
        adapter = DocumentAdapter(documents, ::shareDocument, ::renameDocument, ::deleteDocument)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filterDocuments(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        loadDocuments()
    }

    private fun loadDocuments() {
        val dir = File(filesDir, "scans")
        allDocuments.clear()
        allDocuments.addAll(dir.listFiles()?.filter { it.extension.equals("pdf", true) }?.sortedByDescending { it.lastModified() } ?: emptyList())
        filterDocuments(searchInput.text?.toString().orEmpty())
    }

    private fun filterDocuments(query: String) {
        val normalized = query.trim().lowercase()
        documents.clear()
        if (normalized.isEmpty()) {
            documents.addAll(allDocuments)
        } else {
            documents.addAll(allDocuments.filter { file ->
                file.nameWithoutExtension.lowercase().contains(normalized) ||
                    File(file.parentFile, file.nameWithoutExtension + ".txt").takeIf { it.exists() }
                        ?.readText(Charsets.UTF_8)?.lowercase()?.contains(normalized) == true
            })
        }
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
                    return@setPositiveButton
                }
                if (file.renameTo(target)) {
                    File(file.parentFile, file.nameWithoutExtension + ".jpg")
                        .renameTo(File(file.parentFile, "$newName.jpg"))
                    File(file.parentFile, file.nameWithoutExtension + ".txt")
                        .renameTo(File(file.parentFile, "$newName.txt"))
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
                File(file.parentFile, file.nameWithoutExtension + ".txt").delete()
                if (deleted) loadDocuments() else Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
