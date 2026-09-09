package com.baltas80.pocketscan

import android.content.Context
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object AppLockManager {
    private const val PREFS = "pocketscan_security"
    private const val LOCK_ENABLED = "lock_enabled"
    private const val GRACE_MS = 30_000L
    private var lastAuthenticatedAt = 0L

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LOCK_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(LOCK_ENABLED, enabled).apply()
        if (!enabled) lastAuthenticatedAt = 0L
    }

    fun toggle(context: FragmentActivity, onResult: (Boolean) -> Unit) {
        if (isEnabled(context)) {
            setEnabled(context, false)
            onResult(true)
            return
        }
        authenticate(context, "Activa el bloqueo de PocketScan") { success ->
            if (success) setEnabled(context, true)
            onResult(success)
        }
    }

    fun authenticateIfNeeded(activity: FragmentActivity, onBlocked: (() -> Unit)? = null) {
        if (!isEnabled(activity)) return
        if (System.currentTimeMillis() - lastAuthenticatedAt < GRACE_MS) return
        authenticate(activity, "Desbloquea PocketScan") { success ->
            if (!success) onBlocked?.invoke()
        }
    }

    private fun authenticate(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val manager = BiometricManager.from(activity)
        if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(activity, "Configura una huella, rostro o PIN en el dispositivo", Toast.LENGTH_LONG).show()
            onResult(false)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                lastAuthenticatedAt = System.currentTimeMillis()
                onResult(true)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false)
            }
            override fun onAuthenticationFailed() = Unit
        })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Biometría o credencial del dispositivo")
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }
}
