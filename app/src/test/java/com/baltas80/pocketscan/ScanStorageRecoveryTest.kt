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
            root.resolve("document.improved.pdf").writeText("partial")
            root.resolve("document.original.pdf").writeText("backup")
            val valid = root.resolve("invoice.pdf").apply { writeText("valid") }

            assertEquals(5, ScanStorageRecovery.cleanup(root))
            assertTrue(valid.isFile)
            assertEquals("valid", valid.readText())
            assertEquals(0, root.walkTopDown().count { it.isFile })
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
