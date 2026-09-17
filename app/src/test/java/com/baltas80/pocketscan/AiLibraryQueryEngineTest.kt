package com.baltas80.pocketscan

import java.io.File
import java.nio.file.Files
import java.util.Locale
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
            val notes = File(dir, "notes.txt").apply { writeText("Factura Acme 2025") }

            val result = AiLibraryQueryEngine.query("Acme", listOf(pdf, notes))

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
    fun amountFiltersSupportSpanishComparisonsUsingVerifiedTotals() {
        val dir = tempDir()
        try {
            val small = document(dir, "small.pdf", "500,00 EUR", source = "local+spatial-verified")
            val large = document(dir, "large.pdf", "1.500,00 EUR", source = "local+spatial-verified")

            val result = AiLibraryQueryEngine.query("más de 1000", listOf(small, large))

            assertEquals(1, result.matches.size)
            assertEquals(large, result.matches.single().file)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aggregateTotalIsCalculatedOnlyForVerifiedSingleCurrency() {
        val dir = tempDir()
        try {
            val first = document(dir, "first.pdf", "10 EUR", source = "local+spatial-verified")
            val second = document(dir, "second.pdf", "20 EUR", source = "gemini+spatial-verified")
            val euros = AiLibraryQueryEngine.query("total", listOf(first, second))
            assertEquals(30.0, euros.aggregateTotal!!, 0.001)
            assertEquals("EUR", euros.aggregateCurrency)

            val dollars = document(dir, "third.pdf", "5 USD", source = "local+spatial-verified")
            val mixed = AiLibraryQueryEngine.query("total", listOf(first, dollars))
            assertNull(mixed.aggregateTotal)
            assertNull(mixed.aggregateCurrency)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unverifiedOcrTotalCannotOverrideStoredData() {
        val dir = tempDir()
        try {
            val pdf = document(dir, "ticket.pdf", "5,00 EUR", source = "test")
            File(dir, "ticket.txt").writeText("""
                BARRA PRECOC 235G 3,80
                5,00 x 0,76
                PAN MOLDE ALTEZA 1,19
                TOTAL
                17,79
            """.trimIndent())

            val result = AiLibraryQueryEngine.query("¿Cuál es el total?", listOf(pdf))

            assertEquals(1, result.matches.size)
            assertNull(result.matches.single().total)
            assertNull(result.aggregateTotal)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifiedSpatialTotalIsExposedWithoutUsingFlattenedOcr() {
        val dir = tempDir()
        try {
            val pdf = document(dir, "ticket.pdf", "17,79 EUR", source = "local+spatial-verified")
            File(dir, "ticket.txt").writeText("TOTAL 5,00\n17,79\n")

            val result = AiLibraryQueryEngine.query("¿Cuál es el total?", listOf(pdf))

            assertEquals(1, result.matches.size)
            assertEquals(17.79, result.matches.single().total!!, 0.001)
            assertEquals("EUR", result.matches.single().currency)
            assertEquals(17.79, result.aggregateTotal!!, 0.001)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun buildContextDoesNotExposeRawUnverifiedTotal() {
        val dir = tempDir()
        try {
            val pdf = document(dir, "ticket.pdf", "5,00 EUR", source = "test")
            val context = AiLibraryQueryEngine.buildContext(AiLibraryQueryEngine.query("¿Cuál es el total?", listOf(pdf)))

            assertTrue(!context.contains("5,00 EUR"))
            assertTrue(context.contains("VERIFIED_TOTAL="))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun document(
        dir: File,
        name: String,
        total: String,
        category: String = "FACTURAS",
        date: String? = null,
        source: String = "local+spatial-verified"
    ): File {
        val pdf = File(dir, name).apply { writeText("pdf") }
        val fields = JSONObject().put("total", total).apply {
            date?.let { put("fecha", it) }
        }
        val json = JSONObject()
            .put("version", 1)
            .put("source", source)
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
