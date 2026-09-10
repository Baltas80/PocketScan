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
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace("\\p{M}+".toRegex(), "")
            .replace("[\\u200B-\\u200D\\uFEFF]".toRegex(), "")
            .lowercase(Locale.ROOT)

    fun categoryForFile(file: File, scansDir: File): String {
        val relative = file.relativeToOrSelf(scansDir).path
        val first = relative.substringBefore(File.separator)
        return if (categories.contains(first)) first else GENERAL
    }

    fun directory(scansDir: File, category: String): File =
        File(scansDir, if (categories.contains(category)) category else GENERAL).apply { mkdirs() }

    fun moveDocument(pdf: File, text: File?, scansDir: File, category: String): File {
        val targetDir = directory(scansDir, category)
        if (pdf.parentFile?.canonicalFile == targetDir.canonicalFile) return pdf
        var target = File(targetDir, pdf.name)
        var counter = 2
        while (target.exists()) {
            target = File(targetDir, "${pdf.nameWithoutExtension} ($counter).pdf")
            counter++
        }
        if (!pdf.renameTo(target)) return pdf
        moveSidecar(text, targetDir, target.nameWithoutExtension + ".txt")
        moveSidecar(File(pdf.parentFile, pdf.nameWithoutExtension + ".ai.json"), targetDir, target.nameWithoutExtension + ".ai.json")
        return target
    }

    private fun moveSidecar(source: File?, targetDir: File, targetName: String) {
        source?.takeIf { it.exists() }?.let { sidecar ->
            val target = File(targetDir, targetName)
            if (!sidecar.renameTo(target)) {
                sidecar.copyTo(target, overwrite = true)
                sidecar.delete()
            }
        }
    }
}
