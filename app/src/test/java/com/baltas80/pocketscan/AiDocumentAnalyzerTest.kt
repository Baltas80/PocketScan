package com.baltas80.pocketscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AiDocumentAnalyzerTest {
    @Test
    fun categoryClassifierRecognizesExactSpanishInvoiceOcr() {
        val text = """
            ===== PÁGINA 1 =====
            MINISTERIO
            Factura
            ACME Servicios SL
            Proveedor: ACME Servicios SL
            Total: 1.493,75 €
        """.trimIndent()

        assertEquals(DocumentOrganizer.FACTURAS, DocumentOrganizer.categoryForText(text))
    }

    @Test
    fun localAnalysisExtractsStructuredSpanishInvoiceFields() {
        val root = Files.createTempDirectory("pocketscan-ai").toFile()
        try {
            val pdf = File(root, "factura.pdf").apply { writeText("pdf") }
            val text = """
                ===== PÁGINA 1 =====
                MINISTERIO
                Factura
                ACME Servicios SL
                Proveedor: ACME Servicios SL
                Cliente: Juan Pérez
                NIF: B12345678
                Número: F-2026/0042
                Fecha: 10/09/2026
                Vencimiento: 10/10/2026
                Subtotal: 1.234,50 €
                IVA: 21%
                Total: 1.493,75 €
                Dirección: Calle Mayor 12, Córdoba
                Teléfono: +34 600 123 456
                Concepto: Servicios de mantenimiento
                Periodo: septiembre 2026
            """.trimIndent()

            val analysis = invokeLocalAnalysis(pdf, text)

            assertEquals(DocumentOrganizer.FACTURAS, analysis.category)
            assertEquals("ACME Servicios SL", analysis.title)
            assertEquals("1.493,75", analysis.fields["total"])
            assertEquals("EUR", analysis.fields["moneda"])
            assertEquals("21%", analysis.fields["iva"])
            assertEquals("10/09/2026", analysis.fields["fecha"])
            assertEquals("B12345678", analysis.fields["nif_cif"])
            assertEquals("F-2026/0042", analysis.fields["numero"])
            assertEquals("10/10/2026", analysis.fields["vencimiento"])
            assertEquals("1.234,50", analysis.fields["subtotal"])
            assertEquals("ACME Servicios SL", analysis.fields["proveedor"])
            assertEquals("Juan Pérez", analysis.fields["cliente"])
            assertEquals("septiembre 2026", analysis.fields["periodo"])
            assertTrue(analysis.fields["telefono"]?.contains("600 123 456") == true)
            assertEquals("Calle Mayor 12, Córdoba", analysis.fields["direccion"])
            assertEquals("Servicios de mantenimiento", analysis.fields["concepto"])
            assertFalse(analysis.title.contains("PÁGINA", ignoreCase = true))
            assertFalse(analysis.summary.contains("===== PÁGINA 1 ====="))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localAnalysisSupportsEnglishInvoiceKeywords() {
        val root = Files.createTempDirectory("pocketscan-ai-en").toFile()
        try {
            val pdf = File(root, "invoice.pdf").apply { writeText("pdf") }
            val text = """
                ===== PAGINA 1 =====
                Invoice
                Example Ltd
                Customer: Jane Doe
                Tax ID: GB123456789
                Reference: INV-88
                Date: 2026/09/10
                Due date: 2026/10/10
                Subtotal: 250.00 USD
                VAT: 20%
                Total amount: 300.00 USD
            """.trimIndent()

            val analysis = invokeLocalAnalysis(pdf, text)

            assertEquals(DocumentOrganizer.FACTURAS, analysis.category)
            assertEquals("250.00", analysis.fields["subtotal"])
            assertEquals("300.00", analysis.fields["total"])
            assertEquals("USD", analysis.fields["moneda"])
            assertEquals("20%", analysis.fields["iva"])
            assertEquals("2026/09/10", analysis.fields["fecha"])
            assertEquals("2026/10/10", analysis.fields["vencimiento"])
            assertEquals("GB123456789", analysis.fields["nif_cif"])
            assertEquals("INV-88", analysis.fields["numero"])
            assertFalse(analysis.summary.contains("===== PAGINA 1 ====="))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localAnalysisHandlesCurrencyBeforeAmountAndDashedPageMarkers() {
        val root = Files.createTempDirectory("pocketscan-ai-currency").toFile()
        try {
            val pdf = File(root, "invoice.pdf").apply { writeText("pdf") }
            val text = """
                ----- PÁGINA 2 -----
                Factura
                Proveedor: Global Services
                Subtotal: USD 125.50
                IVA: 10 %
                Total: $ 138.05
            """.trimIndent()

            val analysis = invokeLocalAnalysis(pdf, text)

            assertEquals("125.50", analysis.fields["subtotal"])
            assertEquals("138.05", analysis.fields["total"])
            assertEquals("USD", analysis.fields["moneda"])
            assertEquals("10 %", analysis.fields["iva"])
            assertFalse(analysis.title.contains("PÁGINA", ignoreCase = true))
            assertFalse(analysis.summary.contains("PÁGINA", ignoreCase = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun multilingualOcrKeepsStrongLatinDocumentOverTinyNonLatinFragment() {
        val selected = invokeOcrSelection(
            candidate("latin", "Factura ACME Servicios SL Total 1.493,75 EUR Cliente Juan Pérez"),
            candidate("korean", "문서")
        )

        assertEquals("latin", selectedName(selected))
    }

    @Test
    fun multilingualOcrUsesStrongNonLatinDocumentWhenLatinIsOnlyNoise() {
        val selected = invokeOcrSelection(
            candidate("latin", "ab"),
            candidate("japanese", "請求書 株式会社 料金")
        )

        assertEquals("japanese", selectedName(selected))
    }

    @Test
    fun multilingualOcrFallsBackToBestQualityCandidateWithoutStrongScript() {
        val selected = invokeOcrSelection(
            candidate("latin", "short"),
            candidate("korean", "12 34")
        )

        assertEquals("latin", selectedName(selected))
    }

    private fun candidate(name: String, text: String): Any {
        val candidateClass = Class.forName("com.baltas80.pocketscan.MultilingualOcr\\$Candidate")
        val constructor = candidateClass.getDeclaredConstructor(String::class.java, String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(name, text)
    }

    private fun invokeOcrSelection(vararg candidates: Any): Any {
        val method = MultilingualOcr::class.java.getDeclaredMethod("selectCandidate", List::class.java)
        method.isAccessible = true
        return method.invoke(MultilingualOcr, candidates.toList())!!
    }

    private fun selectedName(candidate: Any): String {
        val field = candidate.javaClass.getDeclaredField("name")
        field.isAccessible = true
        return field.get(candidate) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeLocalAnalysis(file: File, text: String): AiDocumentAnalyzer.Analysis {
        val method = AiDocumentAnalyzer::class.java.getDeclaredMethod("localAnalysis", File::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(AiDocumentAnalyzer, file, text) as AiDocumentAnalyzer.Analysis
    }
}
