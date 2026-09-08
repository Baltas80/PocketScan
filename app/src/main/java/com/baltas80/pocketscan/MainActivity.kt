package com.baltas80.pocketscan

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.baltas80.pocketscan.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scanButton.setOnClickListener {
            startActivity(Intent(this, ScannerActivity::class.java))
        }
        binding.documentsButton.setOnClickListener {
            startActivity(Intent(this, DocumentsActivity::class.java))
        }
    }
}
