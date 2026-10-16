package com.baltas80.pocketscan

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var lockButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.scanButton).setOnClickListener { startActivity(Intent(this, SmartScannerActivity::class.java)) }
        findViewById<Button>(R.id.documentsButton).setOnClickListener { startActivity(Intent(this, DocumentsActivity::class.java)) }
        findViewById<Button>(R.id.aiAssistantButton).setOnClickListener { startActivity(Intent(this, AiAssistantActivity::class.java)) }
        lockButton = findViewById(R.id.lockButton)
        lockButton.setOnClickListener {
            AppLockManager.toggle(this) { success ->
                if (success) updateLockButton()
                else Toast.makeText(this, R.string.lock_toggle_failed, Toast.LENGTH_SHORT).show()
            }
        }
        updateLockButton()
        AdsConsentManager.prepare(this, findViewById(R.id.adViewContainer))
    }

    override fun onResume() {
        super.onResume()
        if (::lockButton.isInitialized) updateLockButton()
        AppLockManager.authenticateIfNeeded(this) { finishAffinity() }
    }

    private fun updateLockButton() {
        lockButton.setText(if (AppLockManager.isEnabled(this)) R.string.biometric_lock_disable else R.string.biometric_lock)
    }
}
