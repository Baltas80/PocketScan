package com.baltas80.pocketscan

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Runtime AI configuration. Remote Config can change the model without an app release.
 * The default remains safe if Remote Config has not been published yet.
 */
object AiModelConfig {
    private const val MODEL_NAME_KEY = "ai_model_name"
    private const val DEFAULT_MODEL_NAME = "gemini-3.7-flash"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var resolvedModel: Deferred<String>? = null

    suspend fun modelName(): String {
        val deferred = synchronized(this) {
            resolvedModel ?: scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                resolveModelName()
            }.also { resolvedModel = it }
        }
        return deferred.await()
    }

    private suspend fun resolveModelName(): String {
        val config = FirebaseRemoteConfig.getInstance()
        return suspendCancellableCoroutine { continuation ->
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
    }
}
