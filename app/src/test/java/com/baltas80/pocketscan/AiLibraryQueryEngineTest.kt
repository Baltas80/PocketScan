package com.baltas80.pocketscan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLibraryQueryEngineTest {
    @Test
    fun queryIgnoresNonPdfFilesAndUsesOcrSidecar() {
        val dir = createTempDir(prefix = "pocketscan-query-")
        try {
            val pdf = File(dir, "scan.pdf").apply { writeText("pdf") }
            File(dir, "scan.txt").writeText("Factura Acme 2025")
            File(dir, "notes.txt").writeText("Factura Acme 2025")

            val result = AiLibraryQueryEngine.query("Acme", listOf(pdf, File(dir, "notes.txt")))

            assertEquals(1, result.matches.size)
            assertEquals(pdf, result.matches.single().file)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun categoryAndYearFiltersAreAccentInsensitive() {
        val dir = createTempDir(prefix = "pocketscan-query-")
        try {
            val pdf = File(dir, "nomina.pdf").apply { writeText("pdf") }
            assertTrue(
                AiMetadataStore.save(
                    pdf,
                    AiDocumentAnalyzer.Analysis(
                        category = "NÓMINAS",
                        title = "Nómina septiembre",
                        summary = "Salario",
                        fields = mapOf("fecha" to "15/09/2025", "total" to "1.234,56 EUR")
                    )
                )
            )

            val result = AiLibraryQueryEngine.query("nóminas 2025", listOf(pdf))

            assertEquals(1, result.matches.size)
            assertEquals(1234.56, result.matches.single().total!!, 0.001)
            assertEquals("EUR", result.matches.single().currency)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun amountFiltersSupportSpanishComparisons() {
        val dir = createTempDir(prefix = "pocketscan-query-")
        try {
            val small = document(dir, "small.pdf", "500,00 EUR")
            val large = document(dir, "large.pdf", "1.500,00 EUR")

            val result = AiLibraryQueryEngine.query("más de 1000", listOf(small, large))

            assertEquals(1, result.matches.size)
            assertEquals(large, result.matches.single().file)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aggregateTotalIsCalculatedOnlyForSingleCurrency() {
        val dir = createTempDir(prefix = "pocketscan-query-")
        try {
            val first = document(dir, "first.pdf", "10 EUR")
            val second = document(dir, "second.pdf", "20 EUR")
            val euros = AiLibraryQueryEngine.query("total", listOf(first, second))
            assertEquals(30.0, euros.aggregateTotal!!, 0.001)
            assertEquals("EUR", euros.aggregateCurrency)

            val dollars = document(dir, "third.pdf", "5 USD")
            val mixed = AiLibraryQueryEngine.query("total", listOf(first, dollars))
            assertNull(mixed.aggregateTotal)
            assertNull(mixed.aggregateCurrency)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun document(dir: File, name: String, total: String): File {
        val pdf = File(dir, name).apply { writeText("pdf") }
        AiMetadataStore.save(
            pdf,
            AiDocumentAnalyzer.Analysis(
                category = "FACTURAS",
                title = name,
                summary = "",
                fields = mapOf("total" to total)
            )
        )
        return pdf
    }
}
