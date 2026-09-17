package com.baltas80.pocketscan

import java.util.Locale

/** Classifies questions that require a verified monetary document total. */
object DocumentQuestionClassifier {
    private val totalTerms = Regex(
        "\\b(total|totales|importe total|total a pagar|total factura|grand total|amount due|balance due|montant total|gesamtbetrag|totale da pagare)\\b"
    )
    private val paymentOnlyTerms = Regex(
        "\\b(cash|efectivo|pague|pag[eé]|paid|payment|cambio|change)\\b"
    )

    fun isTotalQuestion(question: String): Boolean {
        val normalized = question.lowercase(Locale.ROOT)
        return totalTerms.containsMatchIn(normalized) && !paymentOnlyTerms.containsMatchIn(normalized)
    }
}
