package com.baltas80.pocketscan

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.scanButton).setOnClickListener {
            startActivity(Intent(this, ScannerActivity::class.java))
        }
        findViewById<Button>(R.id.documentsButton).setOnClickListener {
            startActivity(Intent(this, DocumentsActivity::class.java))
        }
    }
}
