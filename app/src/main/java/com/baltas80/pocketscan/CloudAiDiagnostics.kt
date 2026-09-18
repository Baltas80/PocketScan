package com.baltas80.pocketscan

import java.io.IOException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeoutException

/** Classifies Firebase AI Logic failures for actionable user diagnostics without exposing secrets. */
object CloudAiDiagnostics {
    enum class Kind {
        APP_CHECK,
        AUTHORIZATION,
        MODEL,
        NETWORK,
        API_OR_QUOTA,
        CONFIGURATION,
        UNKNOWN
    }

    data class Diagnostic(
        val kind: Kind,
        val detail: String
    )

    fun classify(error: Throwable): Diagnostic {
        val chain = generateSequence(error) { it.cause }.toList()
        val names = chain.joinToString(" ") { it::class.java.name.lowercase(Locale.ROOT) }
        val messages = chain.mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        val text = (names + " " + messages.joinToString(" ")).lowercase(Locale.ROOT)

        val kind = when {
            text.contains("appcheck") || text.contains("app check") -> Kind.APP_CHECK
            text.contains("unauthenticated") || text.contains("unauthorized") ||
                text.contains("permission denied") || text.contains("http 401") ||
                text.contains("http 403") || text.contains("status code 401") ||
                text.contains("status code 403") -> Kind.AUTHORIZATION
            text.contains("model") && (
                text.contains("not found") || text.contains("unsupported") ||
                    text.contains("invalid model") || text.contains("does not exist")
                ) -> Kind.MODEL
            chain.any { it is UnknownHostException || it is IOException || it is TimeoutException } ||
                text.contains("network") || text.contains("timed out") ||
                text.contains("timeout") || text.contains("unavailable") -> Kind.NETWORK
            text.contains("quota") || text.contains("rate limit") ||
                text.contains("api not enabled") || text.contains("billing") ||
                text.contains("resource exhausted") -> Kind.API_OR_QUOTA
            text.contains("firebaseapp") || text.contains("remote config") ||
                text.contains("configuration") || text.contains("not initialized") -> Kind.CONFIGURATION
            else -> Kind.UNKNOWN
        }

        val detail = messages.firstOrNull()?.take(500)
            ?: chain.lastOrNull()?.javaClass?.simpleName
            ?: error.javaClass.simpleName
        return Diagnostic(kind, detail)
    }

    fun userMessage(error: Throwable, language: String): String {
        val diagnostic = classify(error)
        val label = when (diagnostic.kind) {
            Kind.APP_CHECK -> "App Check"
            Kind.AUTHORIZATION -> if (language == "es") "Autorización" else "Authorization"
            Kind.MODEL -> if (language == "es") "Modelo Gemini" else "Gemini model"
            Kind.NETWORK -> if (language == "es") "Red" else "Network"
            Kind.API_OR_QUOTA -> if (language == "es") "API/cuota" else "API/quota"
            Kind.CONFIGURATION -> if (language == "es") "Configuración" else "Configuration"
            Kind.UNKNOWN -> if (language == "es") "Desconocido" else "Unknown"
        }
        return if (language == "es") {
            "Gemini no está disponible.\n\nTipo: " + label + "\nDiagnóstico: " + diagnostic.detail
        } else {
            "Gemini is unavailable.\n\nType: " + label + "\nDiagnostic: " + diagnostic.detail
        }
    }
}
