package com.baltas80.pocketscan

import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AiLibraryAssistantTest {
    @Test
    fun totalQuestionUsesOnlyVerifiedSpatialTotals() = runBlocking {
        withSpanishLocale {
            val dir = Files.createTempDirectory("pocketscan-assistant-total-").toFile()
            try {
                document(dir, "ticket.pdf", "17,79 EUR", "local+spatial-verified")
                val result = AiLibraryAssistant.ask(dir, "¿Cuál es el total?")
                assertEquals("Total verificado: 17,79 EUR.", result.getOrThrow())
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun unverifiedTotalCannotBeAnsweredAsFact() = runBlocking {
        withSpanishLocale {
            val dir = Files.createTempDirectory("pocketscan-assistant-unverified-").toFile()
            try {
                document(dir, "ticket.pdf", "5,00 EUR", "test")
                val result = AiLibraryAssistant.ask(dir, "¿Cuál es el total?")
                assertEquals(
                    "No encuentro información suficiente en la biblioteca para responder con seguridad.",
                    result.getOrThrow()
                )
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun mostExpensiveQuestionRejectsIncompleteVerifiedDataset() = runBlocking {
        withSpanishLocale {
            val dir = Files.createTempDirectory("pocketscan-assistant-max-").toFile()
            try {
                document(dir, "first.pdf", "100 EUR", "local+spatial-verified")
                document(dir, "second.pdf", "5 EUR", "test")
                val result = AiLibraryAssistant.ask(dir, "¿Cuál fue la factura más cara?")
                assertEquals(
                    "No encuentro información suficiente en la biblioteca para responder con seguridad.",
                    result.getOrThrow()
                )
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    private fun document(dir: File, name: String, total: String, source: String): File {
        val scans = File(dir, "scans").apply { mkdirs() }
        val pdf = File(scans, name).apply { writeText("pdf") }
        JSONObject()
            .put("version", 1)
            .put("source", source)
            .put("category", "FACTURAS")
            .put("title", name)
            .put("summary", "")
            .put("fields", JSONObject().put("total", total))
            .also { json -> File(scans, "${pdf.nameWithoutExtension}.ai.json").writeText(json.toString()) }
        return pdf
    }

    private fun withSpanishLocale(block: () -> Unit) {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale("es", "ES"))
        try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
