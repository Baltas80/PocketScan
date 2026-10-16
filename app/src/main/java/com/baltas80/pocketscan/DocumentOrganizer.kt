package com.baltas80.pocketscan

import java.io.File
import java.text.Normalizer
import java.util.Locale

object DocumentOrganizer {
    const val GENERAL = "General"
    const val FACTURAS = "Facturas"
    const val PRESUPUESTOS = "Presupuestos"
    const val CONTRATOS = "Contratos"
    const val RECIBOS = "Recibos"
    const val TICKETS = "Tickets"
    const val NOMINAS = "Nominas"
    const val CERTIFICADOS = "Certificados"
    const val INFORMES = "Informes"
    const val CITAS = "Citas"

    val categories = listOf(
        GENERAL, FACTURAS, PRESUPUESTOS, CONTRATOS, RECIBOS,
        TICKETS, NOMINAS, CERTIFICADOS, INFORMES, CITAS
    )

    fun categoryForText(text: String): String {
        val value = normalizeForClassification(text)
        return when {
            containsAny(value, "factura", "factur4", "invoice", "bill") -> FACTURAS
            containsAny(value, "presupuesto", "quotation", "estimate", "quote") -> PRESUPUESTOS
            containsAny(value, "contrato", "contract", "agreement") -> CONTRATOS
            containsAny(value, "recibo", "receipt") -> RECIBOS
            containsAny(value, "ticket", "tique") -> TICKETS
            containsAny(value, "nomina", "payroll", "payslip", "salary slip") -> NOMINAS
            containsAny(value, "certificado", "certificate") -> CERTIFICADOS
            containsAny(value, "informe", "report") -> INFORMES
            containsAny(value, "cita", "appointment") -> CITAS
            else -> GENERAL
        }
    }

    private fun containsAny(value: String, vararg terms: String): Boolean =
        terms.any { term -> value.contains(term) }

    private fun normalizeForClassification(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .replace("[\\u200B-\\u200D\\uFEFF]".toRegex(), "")
            .replace("[\\u00A0\\u202F]".toRegex(), " ")
            .replace("\\r".toRegex(), "\n")
            .lowercase(Locale.ROOT)

    fun categoryForFile(file: File, scansDir: File): String {
        val relative = file.relativeToOrSelf(scansDir).path
        val first = relative.substringBefore(File.separator)
        return if (categories.contains(first)) first else GENERAL
    }

    /** Shared filename sanitizer: strips accents and unsafe characters. Used by all scanners and the library. */
    fun sanitizeFileName(value: String): String {
        val withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return withoutAccents
            .replace(Regex("[^A-Za-z0-9 _()-]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Maps a document category to its human-readable name prefix for auto-generated filenames. */
    fun typeName(category: String, fallback: String = "Documento"): String = when (category) {
        FACTURAS -> "Factura"
        PRESUPUESTOS -> "Presupuesto"
        CONTRATOS -> "Contrato"
        RECIBOS -> "Recibo"
        TICKETS -> "Ticket"
        NOMINAS -> "Nomina"
        CERTIFICADOS -> "Certificado"
        INFORMES -> "Informe"
        CITAS -> "Cita"
        else -> fallback
    }

    /**
     * Builds an automatic "Type - First meaningful line" name from OCR text, sanitized and length-bounded.
     * Single shared implementation so every scan/import path names documents identically.
     */
    fun suggestDocumentName(text: String, category: String): String {
        val type = typeName(category)
        val usefulLine = text.lines().map { it.trim() }.firstOrNull { line ->
            line.length >= 4 && !line.matches(Regex("[0-9 ./:-]+")) && !line.equals(type, ignoreCase = true)
        } ?: type
        val cleanLine = sanitizeFileName(usefulLine).take(45).trim().trim('.', '_', '-')
        return sanitizeFileName(if (cleanLine.length >= 4) "$type - $cleanLine" else type)
            .take(80).trim().ifEmpty { "Documento" }
    }

    /**
     * Picks a collision-free target for `pdf` in its directory, considering the `.txt` and `.ai.json`
     * sidecars. Renames the PDF and returns the new file; returns `pdf` unchanged if renaming fails.
     */
    fun uniqueRenamedDocument(pdf: File, baseName: String): File {
        val dir = pdf.parentFile ?: return pdf
        var target = File(dir, "$baseName.pdf")
        var counter = 2
        while (
            target.exists() && target.absolutePath != pdf.absolutePath ||
            File(dir, target.nameWithoutExtension + ".txt").exists() &&
                File(dir, target.nameWithoutExtension + ".txt").absolutePath != File(dir, pdf.nameWithoutExtension + ".txt").absolutePath ||
            File(dir, target.nameWithoutExtension + ".ai.json").exists() &&
                File(dir, target.nameWithoutExtension + ".ai.json").absolutePath != File(dir, pdf.nameWithoutExtension + ".ai.json").absolutePath
        ) {
            target = File(dir, "$baseName ($counter).pdf")
            counter++
        }
        return if (target.absolutePath == pdf.absolutePath || pdf.renameTo(target)) target else pdf
    }

    fun directory(scansDir: File, category: String): File =
        File(scansDir, if (categories.contains(category)) category else GENERAL).apply { mkdirs() }

    fun moveDocument(pdf: File, text: File?, scansDir: File, category: String): File {
        val sourceDir = pdf.parentFile ?: return pdf
        val targetDir = directory(scansDir, category)
        if (sourceDir.canonicalFile == targetDir.canonicalFile) return pdf

        val sourceAi = File(sourceDir, pdf.nameWithoutExtension + ".ai.json")
        var target = File(targetDir, pdf.name)
        var counter = 2
        while (target.exists() || File(targetDir, target.nameWithoutExtension + ".txt").exists() || File(targetDir, target.nameWithoutExtension + ".ai.json").exists()) {
            target = File(targetDir, "${pdf.nameWithoutExtension} ($counter).pdf")
            counter++
        }

        if (!pdf.renameTo(target)) return pdf

        val moved = mutableListOf<Pair<File, File>>()
        fun rollback(): File {
            var rollbackOk = true
            moved.asReversed().forEach { (from, to) ->
                if (to.exists() && !to.renameTo(from)) rollbackOk = false
            }
            if (target.exists() && !target.renameTo(pdf)) rollbackOk = false
            return if (rollbackOk && pdf.isFile) pdf else target
        }

        if (!moveSidecar(text, targetDir, target.nameWithoutExtension + ".txt") { moved += it }) return rollback()
        if (!moveSidecar(sourceAi, targetDir, target.nameWithoutExtension + ".ai.json") { moved += it }) return rollback()
        return target
    }

    private fun moveSidecar(source: File?, targetDir: File, targetName: String, onMoved: (Pair<File, File>) -> Unit): Boolean {
        val sidecar = source?.takeIf { it.exists() } ?: return true
        val target = File(targetDir, targetName)
        if (target.exists()) return false
        if (sidecar.renameTo(target)) {
            onMoved(sidecar to target)
            return true
        }
        return runCatching {
            sidecar.copyTo(target, overwrite = false)
            if (!sidecar.delete()) {
                target.delete()
                false
            } else {
                onMoved(sidecar to target)
                true
            }
        }.getOrDefault(false)
    }
}
