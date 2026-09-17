package com.baltas80.pocketscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun rejectsTotalWordEmbeddedInsideProductLine() {
        val tokens = listOf(
            lineToken("BARRA PRECOC 235G ALFARES TOTAL 5,00", "TOTAL", 100, 500),
            lineToken("BARRA PRECOC 235G ALFARES TOTAL 5,00", "5,00", 700, 500),
            lineToken("TOTAL 17,79", "TOTAL", 100, 650),
            lineToken("TOTAL 17,79", "17,79", 700, 650)
        )

        val result = ReceiptTotalSpatialResolver.resolve(tokens)

        assertEquals(17.79, result?.amount ?: -1.0, 0.001)
    }

    @Test
    fun rejectsFiveRowsBelowEvenWhenAmountIsHorizontallyAligned() {
        val tokens = listOf(
            lineToken("TOTAL", "TOTAL", 100, 500),
            lineToken("PRODUCTO 5,00", "5,00", 700, 700),
            lineToken("OTRA FILA 17,79", "17,79", 700, 900)
        )

        assertNull(ReceiptTotalSpatialResolver.resolve(tokens))
    }

    @Test
    fun doesNotUsePaymentArithmeticToInventMissingTotal() {
        val tokens = listOf(
            lineToken("PRODUCTO", "PRODUCTO", 100, 500),
            lineToken("PRODUCTO 5,00", "5,00", 700, 500),
            lineToken("EFECTIVO 20,00", "EFECTIVO", 100, 650),
            lineToken("EFECTIVO 20,00", "20,00", 700, 650),
            lineToken("CAMBIO 2,21", "CAMBIO", 100, 700),
            lineToken("CAMBIO 2,21", "2,21", 700, 700)
        )

        assertNull(ReceiptTotalSpatialResolver.resolve(tokens))
    }

    @Test
    fun paymentArithmeticRaisesConfidenceForSpatiallyValidTotal() {
        val base = listOf(
            token("TOTAL", 100, 500),
            token("17,79", 700, 500),
            token("EFECTIVO", 100, 600),
            token("20,00", 700, 600),
            token("CAMBIO", 100, 700),
            token("2,21", 700, 700)
        )

        val result = ReceiptTotalSpatialResolver.resolve(base)

        assertEquals(17.79, result?.amount ?: -1.0, 0.001)
        assertTrue((result?.confidence ?: 0.0) > 0.90)
    }

    @Test
    fun doesNotAcceptAmountMerelyBecauseItIsTheClosestValueOnAnotherRow() {
        val tokens = listOf(
            token("TOTAL", 100, 500),
            token("5,00", 200, 100),
            token("17,79", 700, 650)
        )

        assertNull(ReceiptTotalSpatialResolver.resolve(tokens))
    }

    @Test
    fun recognizesPunctuationAndMultilingualTotalLabels() {
        val labels = listOf("TOTAL:", "TOTAL A PAGAR", "MONTANT TOTAL", "GESAMTBETRAG", "TOTALE DA PAGARE")
        labels.forEach { label ->
            val result = ReceiptTotalSpatialResolver.resolve(
                listOf(token(label, 100, 500), token("17,79 €", 700, 500))
            )
            assertEquals(label, 17.79, result?.amount ?: -1.0, 0.001)
        }
    }

    @Test
    fun doesNotBoostConfidenceWhenPaymentCurrenciesConflict() {
        val tokens = listOf(
            token("TOTAL", 100, 500),
            token("17,79 EUR", 700, 500),
            token("EFECTIVO", 100, 600),
            token("20,00 USD", 700, 600),
            token("CAMBIO", 100, 700),
            token("2,21 USD", 700, 700)
        )

        val result = ReceiptTotalSpatialResolver.resolve(tokens)
        val spatialOnly = ReceiptTotalSpatialResolver.resolve(
            listOf(
                token("TOTAL", 100, 500),
                token("17,79 EUR", 700, 500)
            )
        )

        assertEquals(17.79, result?.amount ?: -1.0, 0.001)
        assertEquals(
            spatialOnly?.confidence ?: -1.0,
            result?.confidence ?: -1.0,
            0.0001
        )
    }

    private fun token(text: String, left: Int, top: Int): ReceiptTotalSpatialResolver.Token =
        ReceiptTotalSpatialResolver.Token(
            text = text,
            box = ReceiptTotalSpatialResolver.Box(left, top, left + 100, top + 30)
        )

    private fun lineToken(
        lineText: String,
        text: String,
        left: Int,
        top: Int
    ): ReceiptTotalSpatialResolver.Token =
        ReceiptTotalSpatialResolver.Token(
            text = text,
            box = ReceiptTotalSpatialResolver.Box(left, top, left + 100, top + 30),
            lineText = lineText
        )
}
