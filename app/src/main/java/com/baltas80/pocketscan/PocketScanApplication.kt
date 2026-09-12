package com.baltas80.pocketscan

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import java.io.File

class PocketScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val firebaseApp = FirebaseApp.initializeApp(this)
        if (firebaseApp != null) {
            val appCheck = FirebaseAppCheck.getInstance(firebaseApp)
            if (BuildConfig.DEBUG) {
                installDebugAppCheckProvider(appCheck)
            } else {
                appCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                )
            }
        }

        val scansDir = File(filesDir, "scans")
        ScanStorageRecovery.cleanup(scansDir)
        schedulePendingDocumentAnalysis(scansDir)
    }

    private fun installDebugAppCheckProvider(appCheck: FirebaseAppCheck) {
        // The debug provider is a debug-only dependency, so resolve it without
        // creating a release-time reference to the debug provider class.
        val factoryClass = Class.forName(
            "com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory"
        )
        val factory = factoryClass.getMethod("getInstance").invoke(null) as AppCheckProviderFactory
        appCheck.installAppCheckProviderFactory(factory)
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
