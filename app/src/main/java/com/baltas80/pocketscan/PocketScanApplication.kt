package com.baltas80.pocketscan

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

class PocketScanApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val firebaseApp = FirebaseApp.initializeApp(this) ?: return
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
}
