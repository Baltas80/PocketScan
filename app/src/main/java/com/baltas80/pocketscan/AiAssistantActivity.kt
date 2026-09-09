package com.baltas80.pocketscan

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AiAssistantActivity : AppCompatActivity() {
    private lateinit var questionInput: EditText
    private lateinit var answerView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_assistant)
        questionInput = findViewById(R.id.aiQuestion)
        answerView = findViewById(R.id.aiAnswer)
        findViewById<Button>(R.id.aiAsk).setOnClickListener { ask() }
    }

    override fun onResume() { super.onResume(); AppLockManager.authenticateIfNeeded(this) { finish() } }

    private fun ask() {
        val question = questionInput.text.toString().trim()
        if (question.isBlank()) { questionInput.error = getString(R.string.write_question); return }
        val button = findViewById<Button>(R.id.aiAsk)
        button.isEnabled = false
        answerView.text = getString(R.string.ai_analyzing_library)
        lifecycleScope.launch {
            val result = AiLibraryAssistant.ask(filesDir, question)
            button.isEnabled = true
            result.onSuccess { answerView.text = it }.onFailure {
                answerView.text = getString(R.string.ai_failed)
                Toast.makeText(this@AiAssistantActivity, it.message ?: getString(R.string.ai_error), Toast.LENGTH_LONG).show()
            }
        }
    }
}
