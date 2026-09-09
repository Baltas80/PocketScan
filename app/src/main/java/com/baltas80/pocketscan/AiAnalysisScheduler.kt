package com.baltas80.pocketscan

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object AiAnalysisScheduler {
    private const val UNIQUE_PREFIX = "ai-document-analysis-"

    fun enqueue(context: Context, document: java.io.File) {
        if (!document.isFile) return

        val input = Data.Builder()
            .putString(AiDocumentWorker.KEY_DOCUMENT_PATH, document.absolutePath)
            .build()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<AiDocumentWorker>()
            .setInputData(input)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_PREFIX + document.absolutePath.hashCode(),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
