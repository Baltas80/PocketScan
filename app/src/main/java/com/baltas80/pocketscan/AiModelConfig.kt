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

    @Volatile
    private var cachedModelName: String? = null

    suspend fun modelName(): String {
        cachedModelName?.let { return it }

        val config = FirebaseRemoteConfig.getInstance()
        val resolved = suspendCancellableCoroutine<String> { continuation ->
            config.setDefaultsAsync(mapOf(MODEL_NAME_KEY to DEFAULT_MODEL_NAME))
                .addOnCompleteListener {
                    config.fetchAndActivate().addOnCompleteListener {
                        val model = config.getString(MODEL_NAME_KEY)
                            .trim()
                            .ifBlank { DEFAULT_MODEL_NAME }
                        if (continuation.isActive) continuation.resume(model)
                    }
                }
        }

        cachedModelName = resolved
        return resolved
    }
}
