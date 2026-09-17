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
            assertEquals(19. ...