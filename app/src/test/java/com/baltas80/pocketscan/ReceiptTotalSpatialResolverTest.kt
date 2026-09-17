package com.baltas80.pocketscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptTotalSpatialResolverTest {
    @Test
    fun resolvesRealTotalWhenOcrReadingOrderIsWrong() {
        val tokens = listOf(
            token("BARRA", 100, 100),
            token("5,00", 700, 100),
            token("TOTAL", 100, 500),
            token("17,79", 700, 500),
            token("EFECTIVO", 100, 600),
            token("20,00", 700, 600),
            token("CAMBIO", 100, 700),
            token("2,21", 700, 700)
        )

        val result = ReceiptTotalSpatialResolver.resolve(tokens)

        assertEquals(17.79, result?.amount ?: -1.0, 0.001)
    }

    @Test
    fun rejectsAmountThatIsNotOnTheTotalRow() {
        val tokens = listOf(
            token("TOTAL", 100, 500),
            token("5,00", 700, 100),
            token("17,79", 700, 600)
        )

        assertNull(ReceiptTotalSpatialResolver.resolve(tokens))
    }

    @Test
    fun rejectsMergedOcrTextWithoutIndependentTotalGeometry() {
        val tokens = listOf(
            token("TOTAL 5,00", 100, 500),
            token("17,79", 700, 600)
        )

        assertNull(ReceiptTotalSpatialResolver.resolve(tokens))
    }

    @Test
    fun rejectsProductPriceEvenWhenItIsTheOnlyAmountNearAnotherLabel() {
        val tokens = listOf(
            token("SUBTOTAL", 100, 500),
            token("5,00", 700, 500),
            token("TOTAL", 100, 650),
            token("17,79", 700, 800)
        )

        assertNull(ReceiptTotalSpatialResolver.resolve(tokens))
    }

    private fun token(text: String, left: Int, top: Int): ReceiptTotalSpatialResolver.Token =
        ReceiptTotalSpatialResolver.Token(
            text = text,
            box = ReceiptTotalSpatialResolver.Box(left, top, left + 100, top + 30)
        )
}
