package com.baltas80.pocketscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DocumentOrganizerTest {
    @Test
    fun categoryForTextRecognizesSpanishAndEnglishKeywords() {
        assertEquals(DocumentOrganizer.FACTURAS, DocumentOrganizer.categoryForText("Factura de proveedor"))
        assertEquals(DocumentOrganizer.CONTRATOS, DocumentOrganizer.categoryForText("Employment contract"))
        assertEquals(DocumentOrganizer.NOMINAS, DocumentOrganizer.categoryForText("Payroll January"))
        assertEquals(DocumentOrganizer.GENERAL, DocumentOrganizer.categoryForText("Documento sin categoría"))
    }

    @Test
    fun categoryForFileUsesTopLevelCategoryDirectory() {
        val root = Files.createTempDirectory("pocketscan").toFile()
        try {
            val invoices = DocumentOrganizer.directory(root, DocumentOrganizer.FACTURAS)
            val file = invoices.resolve("invoice.pdf")
            file.createNewFile()

            assertEquals(DocumentOrganizer.FACTURAS, DocumentOrganizer.categoryForFile(file, root))
            assertEquals(DocumentOrganizer.GENERAL, DocumentOrganizer.categoryForFile(root.resolve("other.pdf"), root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun moveDocumentMovesOcrAndAiSidecars() {
        val root = Files.createTempDirectory("pocketscan").toFile()
        try {
            val source = root.resolve("incoming").apply { mkdirs() }
            val pdf = source.resolve("receipt.pdf").apply { writeText("pdf") }
            val text = source.resolve("receipt.txt").apply { writeText("ocr") }
            val ai = source.resolve("receipt.ai.json").apply { writeText("{\"version\":1}") }

            val moved = DocumentOrganizer.moveDocument(pdf, text, root, DocumentOrganizer.RECIBOS)

            assertEquals("receipt.pdf", moved.name)
            assertTrue(moved.isFile)
            assertEquals("ocr", root.resolve("Recibos/receipt.txt").readText())
            assertTrue(root.resolve("Recibos/receipt.ai.json").isFile)
            assertTrue(!pdf.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun moveDocumentAvoidsOverwritingExistingPdf() {
        val root = Files.createTempDirectory("pocketscan").toFile()
        try {
            val source = root.resolve("incoming").apply { mkdirs() }
            val pdf = source.resolve("invoice.pdf").apply { writeText("new") }
            val targetDir = DocumentOrganizer.directory(root, DocumentOrganizer.FACTURAS)
            targetDir.resolve("invoice.pdf").writeText("existing")

            val moved = DocumentOrganizer.moveDocument(pdf, null, root, DocumentOrganizer.FACTURAS)

            assertEquals("invoice (2).pdf", moved.name)
            assertEquals("existing", targetDir.resolve("invoice.pdf").readText())
            assertEquals("new", moved.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
