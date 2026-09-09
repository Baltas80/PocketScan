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

    override fun onResume() {
        super.onResume()
        AppLockManager.authenticateIfNeeded(this) { finish() }
    }

    private fun ask() {
        val question = questionInput.text.toString().trim()
        if (question.isBlank()) {
            questionInput.error = "Escribe una pregunta"
            return
        }
        findViewById<Button>(R.id.aiAsk).isEnabled = false
        answerView.text = "Analizando tu biblioteca…"
        lifecycleScope.launch {
            val result = AiLibraryAssistant.ask(filesDir, question)
            findViewById<Button>(R.id.aiAsk).isEnabled = true
            result.onSuccess { answerView.text = it }
                .onFailure {
                    answerView.text = "No se pudo completar la consulta."
                    Toast.makeText(this@AiAssistantActivity, it.message ?: "Error de IA", Toast.LENGTH_LONG).show()
                }
        }
    }
}
