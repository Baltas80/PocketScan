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

    /** Actionable guidance per failure kind. Without it the dialog only says "Gemini no está disponible"
     *  and the user cannot tell a model bug apart from a Firebase console / App Check / billing issue. */
    private fun hint(kind: Kind, language: String): String = when (kind) {
        Kind.APP_CHECK -> if (language == "es") {
            "Acción: en Firebase Console → App Check, registra esta app (proveedor Play Integrity o Debug) y revisa que " +
                "AI Logic no esté desactivada por no exigir App Check."
        } else {
            "Action: in Firebase Console → App Check, register this app (Play Integrity or Debug provider) and make sure " +
                "AI Logic has not been deactivated for not enforcing App Check."
        }
        Kind.AUTHORIZATION -> if (language == "es") {
            "Acción: verifica la API key restringida y que el proyecto tenga habilitado Firebase AI Logic."
        } else {
            "Action: check the restricted API key and that Firebase AI Logic is enabled for the project."
        }
        Kind.MODEL -> if (language == "es") {
            "Acción: el modelo seleccionado ya no es válido; actualiza ai_model_name en Remote Config o reinstala la app."
        } else {
            "Action: the selected model is no longer valid; update ai_model_name in Remote Config or reinstall the app."
        }
        Kind.NETWORK -> if (language == "es") {
            "Acción: comprueba la conexión a internet y vuelve a intentar el análisis."
        } else {
            "Action: check the internet connection and retry the analysis."
        }
        Kind.API_OR_QUOTA -> if (language == "es") {
            "Acción: se superó la cuota o falta facturación; revisa la API de Generative Language en Cloud Console."
        } else {
            "Action: quota exceeded or billing missing; review the Generative Language API in Cloud Console."
        }
        Kind.CONFIGURATION -> if (language == "es") {
            "Acción: Firebase no quedó inicializado correctamente; revisa google-services.json del proyecto."
        } else {
            "Action: Firebase did not initialize correctly; check the project's google-services.json."
        }
        Kind.UNKNOWN -> if (language == "es") {
            "Acción: copia el diagnóstico e infórmalo; puede ser un fallo transitorio, reintenta."
        } else {
            "Action: copy the diagnostic and report it; it may be transient, retry."
        }
    }

    fun classify(error: Throwable): Diagnostic {
        val chain = generateSequence(error) { it.cause }.toList()
        val names = chain.joinToString(" ") { it::class.java.name.lowercase(Locale.ROOT) }
        val messages = chain.mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        val text = (names + " " + messages.joinToString(" ")).lowercase(Locale.ROOT)

        val kind = when {
            text.contains("appcheck") || text.contains("app check") ||
                text.contains("firebase ai logic has been deactivated") ||
                text.contains("enforce firebase app check") ||
                text.contains("unverified app check") || text.contains("app check token") -> Kind.APP_CHECK
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
                text.contains("http 429") || text.contains("status code 429") ||
                text.contains("api not enabled") || text.contains("billing") ||
                text.contains("resource exhausted") -> Kind.API_OR_QUOTA
            text.contains("firebaseapp") || text.contains("remote config") ||
                text.contains("configuration") || text.contains("not initialized") -> Kind.CONFIGURATION
            else -> Kind.UNKNOWN
        }

        val rawDetail = messages.firstOrNull()?.take(500)
            ?: chain.lastOrNull()?.javaClass?.simpleName
            ?: error.javaClass.simpleName
        return Diagnostic(kind, redactSensitive(rawDetail))
    }

    private fun redactSensitive(detail: String): String = detail
        .replace(Regex("(?i)(api[-_ ]?key|token|authorization|bearer)\\s*[:=]\\s*[^\\s,;]+"), "$1=<redacted>")
        .replace(Regex("(?i)(https?://[^\\s?]+)[^\\s]*\\?[^\\s]*"), "$1?<redacted>")
        .take(500)

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
            "Gemini no está disponible.\n\nTipo: " + label + "\nDiagnóstico: " + diagnostic.detail +
                "\n\n" + hint(diagnostic.kind, language)
        } else {
            "Gemini is unavailable.\n\nType: " + label + "\nDiagnostic: " + diagnostic.detail +
                "\n\n" + hint(diagnostic.kind, language)
        }
    }
}
