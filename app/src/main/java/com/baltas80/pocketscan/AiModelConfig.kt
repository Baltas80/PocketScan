package com.baltas80.pocketscan

import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import kotlinx.coroutines.tasks.await

/**
 * Runtime AI configuration. Remote Config can change the model without an app release.
 * The default remains safe if Remote Config has not been published yet.
 */
object AiModelConfig {
    private const val MODEL_NAME_KEY = "ai_model_name"
    private const val DEFAULT_MODEL_NAME = "gemini-3.7-flash"

    suspend fun modelName(): String {
        val config = Firebase.remoteConfig
        config.setDefaultsAsync(mapOf(MODEL_NAME_KEY to DEFAULT_MODEL_NAME)).await()
        runCatching { config.fetchAndActivate().await() }
        return config.getString(MODEL_NAME_KEY).trim().ifBlank { DEFAULT_MODEL_NAME }
    }
}
