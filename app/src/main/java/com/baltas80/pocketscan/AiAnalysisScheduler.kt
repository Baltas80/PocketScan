package com.baltas80.pocketscan

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.security.MessageDigest

object AiAnalysisScheduler {
    private const val UNIQUE_PREFIX = "ai-document-analysis-"

    fun enqueue(context: Context, document: File) {
        if (!document.isFile) return

        val input = Data.Builder()
            .putString(AiDocumentWorker.KEY_DOCUMENT_PATH, document.absolutePath)
            .build()
        val request = OneTimeWorkRequestBuilder<AiDocumentWorker>()
            .setInputData(input)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_PREFIX + stableId(document.absolutePath),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context, document: File) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(UNIQUE_PREFIX + stableId(document.absolutePath))
    }

    private fun stableId(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
