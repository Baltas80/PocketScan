package com.baltas80.pocketscan

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Runtime AI configuration. Remote Config can change the model without an app release.
 * The default remains safe if Remote Config has not been published yet.
 */
object AiModelConfig {
    private const val MODEL_NAME_KEY = "ai_model_name"
    private const val DEFAULT_MODEL_NAME = "gemini-3.7-flash"

    suspend fun modelName(): String {
        val config = FirebaseRemoteConfig.getInstance()
        config.setDefaultsAsync(mapOf(MODEL_NAME_KEY to DEFAULT_MODEL_NAME))
        suspendCancellableCoroutine<Unit> { continuation ->
            config.fetchAndActivate().addOnCompleteListener {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        return config.getString(MODEL_NAME_KEY).trim().ifBlank { DEFAULT_MODEL_NAME }
    }
}
