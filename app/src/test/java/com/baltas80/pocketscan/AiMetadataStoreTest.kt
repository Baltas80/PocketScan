package com.baltas80.pocketscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AiMetadataStoreTest {
    @Test
    fun saveLoadAndDeleteRoundTripCleansTemporaryFile() {
        val root = Files.createTempDirectory("pocketscan-ai").toFile()
        try {
            val document = root.resolve("invoice.pdf").apply { writeText("pdf") }
            val analysis = AiDocumentAnalyzer.Analysis(
                category = DocumentOrganizer.FACTURAS,
                title = "Factura proveedor",
                summary = "Total 25 EUR",
                fields = linkedMapOf("total" to "25", "moneda" to "EUR"),
                source = "local"
            )

            assertTrue(AiMetadataStore.save(document, analysis))
            val sidecar = AiMetadataStore.sidecarFor(document)
            val temp = root.resolve(sidecar.name + ".tmp")
            assertTrue(sidecar.isFile)
            assertFalse(temp.exists())

            val loaded = AiMetadataStore.load(document)
            assertEquals(DocumentOrganizer.FACTURAS, loaded?.category)
            assertEquals("Factura proveedor", loaded?.title)
            assertEquals("25", loaded?.fields?.get("total"))
            assertEquals("EUR", loaded?.fields?.get("moneda"))

            assertTrue(AiMetadataStore.delete(document))
            assertFalse(sidecar.exists())
            assertFalse(temp.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteSucceedsWhenMetadataAndTemporaryFileAreAlreadyAbsent() {
        val root = Files.createTempDirectory("pocketscan-ai").toFile()
        try {
            val document = root.resolve("document.pdf").apply { writeText("pdf") }
            assertTrue(AiMetadataStore.delete(document))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun saveFailsWhenDocumentDoesNotExist() {
        val root = Files.createTempDirectory("pocketscan-ai").toFile()
        try {
            val document = root.resolve("missing.pdf")
            val analysis = AiDocumentAnalyzer.Analysis(
                category = DocumentOrganizer.FACTURAS,
                title = "Factura",
                summary = "",
                fields = linkedMapOf<String, String>(),
                source = "local"
            )

            assertFalse(AiMetadataStore.save(document, analysis))
            assertFalse(root.resolve("missing.ai.json").exists())
            assertFalse(root.resolve("missing.ai.json.tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
