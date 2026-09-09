package com.baltas80.pocketscan

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import java.io.File

class PocketScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val firebaseApp = FirebaseApp.initializeApp(this)
        if (firebaseApp != null) {
            val appCheck = FirebaseAppCheck.getInstance(firebaseApp)
            if (BuildConfig.DEBUG) {
                appCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
            } else {
                appCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                )
            }
        }

        schedulePendingDocumentAnalysis()
    }

    private fun schedulePendingDocumentAnalysis() {
        val scansDir = File(filesDir, "scans")
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
