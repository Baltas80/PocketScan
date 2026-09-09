package com.baltas80.pocketscan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class AiDocumentWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_DOCUMENT_PATH) ?: return Result.failure()
        val document = File(path)
        if (!document.isFile) return Result.failure()

        val ocrFile = File(document.parentFile, document.nameWithoutExtension + ".txt")
        val ocrText = runCatching {
            if (ocrFile.isFile) ocrFile.readText(Charsets.UTF_8) else ""
        }.getOrDefault("")

        val analysis = AiDocumentAnalyzer.analyze(document, ocrText)
            .getOrElse { return Result.retry() }

        return if (AiMetadataStore.save(document, analysis)) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_DOCUMENT_PATH = "document_path"
    }
}
