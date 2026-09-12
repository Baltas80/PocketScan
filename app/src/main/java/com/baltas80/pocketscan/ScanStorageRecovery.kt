package com.baltas80.pocketscan

import java.io.File

/** Removes files left behind by interrupted scan/import operations. */
object ScanStorageRecovery {
    fun cleanup(scansDir: File): Int {
        if (!scansDir.isDirectory) return 0
        var handled = 0
        scansDir.walkTopDown().filter { it.isFile }.toList().forEach { file ->
            when {
                file.name.endsWith(".pdf.tmp", true) ||
                    file.name.endsWith(".txt.tmp", true) ||
                    file.name.endsWith(".ai.json.tmp", true) ||
                    file.name.endsWith(".jpg.tmp", true) ||
                    file.name.endsWith(".improved.pdf", true) ||
                    file.name.matches(Regex("page_\\d{8}_\\d{6}_\\d{3}\\.jpg", RegexOption.IGNORE_CASE)) ||
                    (file.name.startsWith("import_", true) && file.name.endsWith(".jpg", true)) -> {
                    if (file.delete()) handled++
                }

                file.name.endsWith(".original.pdf", true) -> {
                    val baseName = file.name.dropLast(".original.pdf".length)
                    val restored = File(file.parentFile, "$baseName.pdf")
                    if (restored.isFile) {
                        if (file.delete()) handled++
                    } else if (file.renameTo(restored)) {
                        handled++
                    }
                }
            }
        }
        return handled
    }
}
