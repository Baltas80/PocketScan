package com.baltas80.pocketscan

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File

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
            UNIQUE_PREFIX + document.absolutePath.hashCode(),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
