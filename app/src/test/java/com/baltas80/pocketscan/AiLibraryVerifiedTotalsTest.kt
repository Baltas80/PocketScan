package com.baltas80.pocketscan

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLibraryVerifiedTotalsTest {
    @Test
    fun unverifiedTotalInMetadataAndFlattenedOcrIsNotTrusted() {
        val dir = Files.createTempDirectory("pocketscan-unverified-").toFile()
        try {
            val pdf = File(dir, "ticket.pdf").apply { writeText("pdf") }
            writeMetadata(pdf, "test", "5,00 EUR")
            File(dir, "ticket.txt").writeText("TOTAL 17,79\n")

            val result = AiLibraryQueryEngine.query("total", listOf(pdf))

            assertEquals(1, result.matches.size)
            assertNull(result.matches.single().total)
            assertNull(result.aggregateTotal)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifiedSpatialTotalIsExposedAndAggregated() {
        val dir = Files.createTempDirectory("pocketscan-verified-").toFile()
        try {
            val first = File(dir, "first.pdf").apply { writeText("pdf") }
            val second = File(dir, "second.pdf").apply { writeText("pdf") }
            writeMetadata(first, "local+spatial-verified", "17,79 EUR")
            writeMetadata(second, "gemini+spatial-verified", "2,21 EUR")

            val result = AiLibraryQueryEngine.query("total", listOf(first, second))

            assertEquals(2, result.matches.size)
            assertEquals(17.79, result.matches[0].total!!, 0.001)
            assertEquals(2.21, result.matches[1].total!!, 0.001)
            assertEquals(20.0, result.aggregateTotal!!, 0.001)
            assertEquals("EUR", result.aggregateCurrency)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun amountFilterIgnoresUnverifiedTotals() {
        val dir = Files.createTempDirectory("pocketscan-filter-").toFile()
        try {
            val falseLarge = File(dir, "false-large.pdf").apply { writeText("pdf") }
            val realLarge = File(dir, "real-large.pdf").apply { writeText("pdf") }
            writeMetadata(falseLarge, "test", "1500,00 EUR")
            writeMetadata(realLarge, "local+spatial-verified", "1200,00 EUR")

            val result = AiLibraryQueryEngine.query("más de 1000", listOf(falseLarge, realLarge))

            assertEquals(1, result.matches.size)
            assertEquals(realLarge, result.matches.single().file)
            assertEquals(1200.0, result.matches.single().total!!, 0.001)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun contextDoesNotExposeUnverifiedTotal() {
        val dir = Files.createTempDirectory("pocketscan-context-").toFile()
        try {
            val pdf = File(dir, "ticket.pdf").apply { writeText("pdf") }
            writeMetadata(pdf, "test", "5,00 EUR")

            val context = AiLibraryQueryEngine.buildContext(AiLibraryQueryEngine.query("ticket", listOf(pdf)))

            assertTrue(!context.contains("5,00 EUR"))
            assertTrue(context.contains("VERIFIED_TOTAL="))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun writeMetadata(document: File, source: String, total: String) {
        val fields = JSONObject().put("total", total)
        val json = JSONObject()
            .put("version", 1)
            .put("source", source)
            .put("category", "FACTURAS")
            .put("title", document.nameWithoutExtension)
            .put("summary", "")
            .put("fields", fields)
        File(document.parentFile, "${document.nameWithoutExtension}.ai.json").writeText(json.toString())
    }
}
