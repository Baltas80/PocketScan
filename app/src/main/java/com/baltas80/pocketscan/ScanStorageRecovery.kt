package com.baltas80.pocketscan

import java.io.File

/** Removes files left behind by interrupted scan/import operations. */
object ScanStorageRecovery {
    private val temporarySuffixes = setOf(
        ".pdf.tmp",
        ".txt.tmp",
        ".ai.json.tmp",
        ".improved.pdf",
        ".original.pdf"
    )

    fun cleanup(scansDir: File): Int {
        if (!scansDir.isDirectory) return 0
        var removed = 0
        scansDir.walkTopDown().filter { it.isFile }.toList().forEach { file ->
            if (isRecoveryFile(file) && file.delete()) removed++
        }
        return removed
    }

    private fun isRecoveryFile(file: File): Boolean =
        file.name.endsWith(".pdf.tmp", true) ||
            file.name.endsWith(".txt.tmp", true) ||
            file.name.endsWith(".ai.json.tmp", true) ||
            file.name.endsWith(".improved.pdf", true) ||
            file.name.endsWith(".original.pdf", true)
}
