package com.myai.companion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        val nameInput = findViewById<EditText>(R.id.nameInput)
        val keyInput = findViewById<EditText>(R.id.keyInput)
        val aiNameInput = findViewById<EditText>(R.id.aiNameInput)
        val status = findViewById<TextView>(R.id.statusText)

        nameInput.setText(prefs.getString("user_name", ""))
        keyInput.setText(prefs.getString("groq_key", ""))
        aiNameInput.setText(prefs.getString("ai_name", "Aura"))

        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            prefs.edit()
                .putString("user_name", nameInput.text.toString().trim())
                .putString("groq_key", keyInput.text.toString().trim())
                .putString("ai_name", aiNameInput.text.toString().trim().ifEmpty { "Aura" })
                .apply()
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.overlayBtn).setOnClickListener {
            requestOverlay()
        }

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            if (!canDrawOverlay()) {
                Toast.makeText(this, "Please allow 'Display over other apps' first", Toast.LENGTH_LONG).show()
                requestOverlay()
                return@setOnClickListener
            }
            if (keyInput.text.toString().trim().isEmpty()) {
                Toast.makeText(this, "Add your Groq API key first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("user_name", nameInput.text.toString().trim())
                .putString("groq_key", keyInput.text.toString().trim())
                .putString("ai_name", aiNameInput.text.toString().trim().ifEmpty { "Aura" })
                .apply()
            val svc = Intent(this, FloatingService::class.java)
            ContextCompat.startForegroundService(this, svc)
            Toast.makeText(this, "Your AI is now alive! Minimize this app.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }

        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "AI stopped.", Toast.LENGTH_SHORT).show()
        }

        updateStatus(status)
    }

    override fun onResume() {
        super.onResume()
        updateStatus(findViewById(R.id.statusText))
    }

    private fun updateStatus(status: TextView) {
        status.text = if (canDrawOverlay())
            "✅ Overlay permission granted — ready to go!"
        else
            "⚠️ Tap 'Allow Float Permission' below first"
    }

    private fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this) else true
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "Already granted!", Toast.LENGTH_SHORT).show()
        }
    }
}
