package com.baltas80.pocketscan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentQuestionClassifierTest {
    @Test
    fun identifiesTotalQuestions() {
        assertTrue(DocumentQuestionClassifier.isTotalQuestion("¿Cuál es el importe total del ticket?"))
        assertTrue(DocumentQuestionClassifier.isTotalQuestion("¿Cuál es el total de la factura?"))
        assertTrue(DocumentQuestionClassifier.isTotalQuestion("What is the grand total?"))
    }

    @Test
    fun doesNotClassifyCashAndChangeQuestionsAsTotal() {
        assertFalse(DocumentQuestionClassifier.isTotalQuestion("¿Cuánto pagué en efectivo?"))
        assertFalse(DocumentQuestionClassifier.isTotalQuestion("¿Cuál fue el cambio?"))
        assertFalse(DocumentQuestionClassifier.isTotalQuestion("How much was paid in cash?"))
    }

    @Test
    fun doesNotClassifyGenericQuestionsAsTotal() {
        assertFalse(DocumentQuestionClassifier.isTotalQuestion("¿Qué fecha tiene la factura?"))
        assertFalse(DocumentQuestionClassifier.isTotalQuestion("¿Cuál es el proveedor?"))
    }
}
