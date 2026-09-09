package com.baltas80.pocketscan

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class DocumentAdapter(
    private val items: List<File>,
    private val onOpen: (File) -> Unit,
    private val onShare: (File) -> Unit,
    private val onRename: (File) -> Unit,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<DocumentAdapter.DocumentViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder =
        DocumentViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_document, parent, false))
    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class DocumentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.documentName)
        private val category: TextView = view.findViewById(R.id.documentCategory)
        private val open: Button = view.findViewById(R.id.openButton)
        private val share: Button = view.findViewById(R.id.shareButton)
        private val rename: Button = view.findViewById(R.id.renameButton)
        private val delete: Button = view.findViewById(R.id.deleteButton)
        fun bind(file: File) {
            name.text = file.nameWithoutExtension
            category.text = "Categoría: ${DocumentOrganizer.categoryForFile(file, File(file.path.substringBeforeLast("/scans") + "/scans"))}"
            open.setOnClickListener { onOpen(file) }
            share.setOnClickListener { onShare(file) }
            rename.setOnClickListener { onRename(file) }
            delete.setOnClickListener { onDelete(file) }
        }
    }
}
