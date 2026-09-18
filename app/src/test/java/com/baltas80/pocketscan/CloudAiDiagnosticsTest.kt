package com.baltas80.pocketscan

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAiDiagnosticsTest {
    @Test
    fun identifiesAppCheckFailures() {
        val error = IllegalStateException("App Check token rejected")
        assertEquals(
            CloudAiDiagnostics.Kind.APP_CHECK,
            CloudAiDiagnostics.classify(error).kind
        )
    }

    @Test
    fun identifiesAuthorizationFailures() {
        val error = IllegalStateException("HTTP 403 permission denied")
        assertEquals(
            CloudAiDiagnostics.Kind.AUTHORIZATION,
            CloudAiDiagnostics.classify(error).kind
        )
    }

    @Test
    fun identifiesModelFailures() {
        val error = IllegalStateException("Requested model not found")
        assertEquals(
            CloudAiDiagnostics.Kind.MODEL,
            CloudAiDiagnostics.classify(error).kind
        )
    }

    @Test
    fun identifiesNetworkFailuresFromCauseChain() {
        val error = IllegalStateException("request failed", IOException("network timeout"))
        assertEquals(
            CloudAiDiagnostics.Kind.NETWORK,
            CloudAiDiagnostics.classify(error).kind
        )
    }

    @Test
    fun userMessageDoesNotExposeStackTraceOrSecretLikePayloads() {
        val message = CloudAiDiagnostics.userMessage(
            IllegalStateException("quota exceeded"),
            "es"
        )
        assertTrue(message.contains("API/cuota"))
        assertTrue(!message.contains(" at com."))

        val redacted = CloudAiDiagnostics.userMessage(
            IllegalStateException("apiKey=SECRET123 https://example.test/call?token=SECRET456"),
            "es"
        )
        assertTrue(redacted.contains("apiKey=<redacted>"))
        assertTrue(redacted.contains("https://example.test/call?<redacted>"))
        assertTrue(!redacted.contains("SECRET123"))
        assertTrue(!redacted.contains("SECRET456"))
    }
}
