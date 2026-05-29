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
import android.Manifest
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private var selectedEmoji = "🤖"
    private lateinit var emojiViews: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        val nameInput = findViewById<EditText>(R.id.nameInput)
        val keyInput = findViewById<EditText>(R.id.keyInput)
        val aiNameInput = findViewById<EditText>(R.id.aiNameInput)
        val headerName = findViewById<TextView>(R.id.headerName)
        val status = findViewById<TextView>(R.id.statusText)

        nameInput.setText(prefs.getString("user_name", ""))
        keyInput.setText(prefs.getString("groq_key", ""))
        aiNameInput.setText(prefs.getString("ai_name", "Aura"))
        selectedEmoji = prefs.getString("bubble_emoji", "🤖") ?: "🤖"
        headerName.text = prefs.getString("ai_name", "Aura")

        // Emoji picker
        emojiViews = listOf(
            findViewById(R.id.emoji1), findViewById(R.id.emoji2),
            findViewById(R.id.emoji3), findViewById(R.id.emoji4),
            findViewById(R.id.emoji5)
        )
        emojiViews.forEach { v ->
            v.setOnClickListener {
                selectedEmoji = v.text.toString()
                refreshEmojiSelection()
            }
        }
        refreshEmojiSelection()

        // Live update header as you type the AI name
        aiNameInput.setOnFocusChangeListener { _, _ ->
            headerName.text = aiNameInput.text.toString().trim().ifEmpty { "Aura" }
        }

        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            saveAll(nameInput, aiNameInput, keyInput)
            headerName.text = aiNameInput.text.toString().trim().ifEmpty { "Aura" }
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.overlayBtn).setOnClickListener { requestOverlay() }

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            if (!canDrawOverlay()) {
                Toast.makeText(this, "Please allow 'Display over other apps' first", Toast.LENGTH_LONG).show()
                requestOverlay(); return@setOnClickListener
            }
                requestMicIfNeeded()
            saveAll(nameInput, aiNameInput, keyInput)
            ContextCompat.startForegroundService(this, Intent(this, FloatingService::class.java))
            Toast.makeText(this, "Your AI is now alive! Minimize this app.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }

        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "AI stopped.", Toast.LENGTH_SHORT).show()
        }

        updateStatus(status)
    }

    private fun requestMicIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        }
    }

    private fun refreshEmojiSelection() {
        emojiViews.forEach { v ->
            v.setBackgroundResource(
                if (v.text.toString() == selectedEmoji) R.drawable.emoji_selected
                else R.drawable.emoji_unselected
            )
        }
    }

    private fun saveAll(name: EditText, aiName: EditText, key: EditText) {
        prefs.edit()
            .putString("user_name", name.text.toString().trim())
            .putString("groq_key", key.text.toString().trim())
            .putString("ai_name", aiName.text.toString().trim().ifEmpty { "Aura" })
            .putString("bubble_emoji", selectedEmoji)
            .apply()
    }

    override fun onResume() {
        super.onResume()
        updateStatus(findViewById(R.id.statusText))
    }

    private fun updateStatus(status: TextView) {
        status.text = if (canDrawOverlay())
            "✅ Float permission granted — ready to go!"
        else
            "⚠️ Tap '1️⃣ Allow Float Permission' first"
    }

    private fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this) else true
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            Toast.makeText(this, "Already granted!", Toast.LENGTH_SHORT).show()
        }
    }
}
