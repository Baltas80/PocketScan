package com.baltas80.pocketscan

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.PlayIntegrityAppCheckProviderFactory
import java.io.File

class PocketScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val firebaseApp = FirebaseApp.initializeApp(this)
        if (firebaseApp != null) {
            val appCheck = FirebaseAppCheck.getInstance(firebaseApp)
            appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        }

        val scansDir = File(filesDir, "scans")
        ScanStorageRecovery.cleanup(scansDir)
        schedulePendingDocumentAnalysis(scansDir)
    }

    private fun schedulePendingDocumentAnalysis(scansDir: File) {
        if (!scansDir.isDirectory) return
        scansDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("pdf", true) }
            .forEach { document ->
                if (!AiMetadataStore.sidecarFor(document).isFile) {
                    AiAnalysisScheduler.enqueue(this, document)
                }
            }
    }
}
