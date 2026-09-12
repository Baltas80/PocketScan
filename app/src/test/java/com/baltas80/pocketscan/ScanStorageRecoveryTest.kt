package com.baltas80.pocketscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ScanStorageRecoveryTest {
    @Test
    fun cleanupRemovesInterruptedScanFilesAndKeepsDocuments() {
        val root = Files.createTempDirectory("pocketscan-recovery").toFile()
        try {
            root.resolve("invoice.pdf.tmp").writeText("partial")
            root.resolve("invoice.txt.tmp").writeText("partial")
            root.resolve("invoice.ai.json.tmp").writeText("partial")
            root.resolve("invoice.jpg.tmp").writeText("partial")
            root.resolve("document.improved.pdf").writeText("partial")
            root.resolve("document.original.pdf").writeText("backup")
            root.resolve("page_20260912_110419_123.jpg").writeText("orphan")
            root.resolve("import_20260912_123456789_0.jpg").writeText("partial-import")
            root.resolve("page_invalid.jpg").writeText("keep")
            val validInvoice = root.resolve("invoice.pdf").apply { writeText("valid") }
            val validDocument = root.resolve("document.pdf").apply { writeText("valid-document") }

            assertEquals(8, ScanStorageRecovery.cleanup(root))
            assertTrue(validInvoice.isFile)
            assertEquals("valid", validInvoice.readText())
            assertTrue(validDocument.isFile)
            assertEquals("valid-document", validDocument.readText())
            assertTrue(root.resolve("page_invalid.jpg").isFile)
            assertEquals(3, root.walkTopDown().count { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupRestoresOriginalWhenMainPdfIsMissing() {
        val root = Files.createTempDirectory("pocketscan-recovery").toFile()
        try {
            root.resolve("document.original.pdf").writeText("original")
            root.resolve("document.improved.pdf").writeText("partial")

            assertEquals(2, ScanStorageRecovery.cleanup(root))

            val restored = root.resolve("document.pdf")
            assertTrue(restored.isFile)
            assertEquals("original", restored.readText())
            assertTrue(!root.resolve("document.original.pdf").exists())
            assertTrue(!root.resolve("document.improved.pdf").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupIsSafeWhenDirectoryDoesNotExist() {
        val root = Files.createTempDirectory("pocketscan-recovery").toFile()
        try {
            assertEquals(0, ScanStorageRecovery.cleanup(root.resolve("missing")))
        } finally {
            root.deleteRecursively()
        }
    }
}
