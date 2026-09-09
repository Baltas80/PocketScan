package com.baltas80.pocketscan

import java.io.File

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
        val value = text.lowercase()
        return when {
            value.contains("factura") || value.contains("invoice") -> FACTURAS
            value.contains("presupuesto") || value.contains("quotation") || value.contains("estimate") -> PRESUPUESTOS
            value.contains("contrato") || value.contains("contract") -> CONTRATOS
            value.contains("recibo") || value.contains("receipt") -> RECIBOS
            value.contains("ticket") || value.contains("tique") -> TICKETS
            value.contains("nómina") || value.contains("nomina") || value.contains("payroll") -> NOMINAS
            value.contains("certificado") || value.contains("certificate") -> CERTIFICADOS
            value.contains("informe") || value.contains("report") -> INFORMES
            value.contains("cita") || value.contains("appointment") -> CITAS
            else -> GENERAL
        }
    }

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
        text?.takeIf { it.exists() }?.let { sidecar ->
            val sidecarTarget = File(targetDir, target.nameWithoutExtension + ".txt")
            if (!sidecar.renameTo(sidecarTarget)) {
                sidecar.copyTo(sidecarTarget, overwrite = true)
                sidecar.delete()
            }
        }
        return target
    }
}
