package com.baltas80.pocketscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptTotalExtractorTest {
    @Test
    fun ignoresProductLineContainingTotal() {
        val ocr = """
            BOLSA REUTILIZABLE, UN 0,12
            BALANZA CHARCUTERIA 10,79
            BARRA PRECOC 235G ALFARES TOTAL 5,00 x
            PAN MOLDE ALTEZA FAMILIAR 1,19
            TOTAL ................ 17,79
            EFECTIVO 20,00
            CAMBIO EFECTIVO -2,21
        """.trimIndent()

        assertEquals(17.79, ReceiptTotalExtractor.extract(ocr)?.amount ?: -1.0, 0.001)
    }

    @Test
    fun acceptsTotalSplitAcrossLines() {
        val ocr = """
            TOTAL
            17,79
            EFECTIVO 20,00
        """.trimIndent()

        assertEquals(17.79, ReceiptTotalExtractor.extract(ocr)?.amount ?: -1.0, 0.001)
    }

    @Test
    fun refusesToGuessWhenOnlyItemPricesExist() {
        val ocr = """
            PAN 5,00
            BARRA 3,80
            EFECTIVO 20,00
            CAMBIO -2,21
        """.trimIndent()

        assertNull(ReceiptTotalExtractor.extract(ocr))
    }
}
