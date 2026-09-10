package com.baltas80.pocketscan

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLibraryQueryEngineTest {
    @Test
    fun queryIgnoresNonPdfFilesAndUsesOcrSidecar() {
        val dir = tempDir()
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
        val dir = tempDir()
        try {
            val pdf = document(dir, "nomina.pdf", "1.234,56 EUR", "NÓMINAS", "15/09/2025")
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
        val dir = tempDir()
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
        val dir = tempDir()
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

    private fun document(
        dir: File,
        name: String,
        total: String,
        category: String = "FACTURAS",
        date: String? = null
    ): File {
        val pdf = File(dir, name).apply { writeText("pdf") }
        val fields = JSONObject().put("total", total).apply {
            date?.let { put("fecha", it) }
        }
        val json = JSONObject()
            .put("version", 1)
            .put("source", "test")
            .put("category", category)
            .put("title", name)
            .put("summary", "")
            .put("fields", fields)
        File(dir, "${pdf.nameWithoutExtension}.ai.json").writeText(json.toString())
        assertTrue(AiMetadataStore.load(pdf) != null)
        return pdf
    }

    private fun tempDir(): File = Files.createTempDirectory("pocketscan-query-").toFile()
}
