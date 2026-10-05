package com.baltas80.pocketscan

import com.google.android.gms.tasks.Tasks
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.TimeUnit

/**
 * Runtime AI configuration. Remote Config can change the model without an app release.
 * The value is bounded to an explicit allow-list so a bad Remote Config value cannot
 * silently select an unsupported or unintended model.
 */
object AiModelConfig {
    private const val MODEL_NAME_KEY = "ai_model_name"
    // NOTE: "gemini-3.8-flash" never existed upstream; every call failed with
    // HTTP 404 models/gemini-3.8-flash. Default is now a real, GA model.
    private const val DEFAULT_MODEL_NAME = "gemini-2.5-flash"
    private const val REMOTE_CONFIG_TIMEOUT_SECONDS = 5L

    private val allowedModels = setOf(
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "gemini-2.5-flash-lite",
    )

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
        return runCatching {
            Tasks.await(
                config.setDefaultsAsync(mapOf(MODEL_NAME_KEY to DEFAULT_MODEL_NAME)),
                REMOTE_CONFIG_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            Tasks.await(
                config.fetchAndActivate(),
                REMOTE_CONFIG_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            sanitizeModelName(config.getString(MODEL_NAME_KEY))
        }.getOrDefault(DEFAULT_MODEL_NAME)
    }

    internal fun sanitizeModelName(value: String?): String {
        val candidate = value?.trim().orEmpty()
        return candidate.takeIf { it in allowedModels } ?: DEFAULT_MODEL_NAME
    }
}
