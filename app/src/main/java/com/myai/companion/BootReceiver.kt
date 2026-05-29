package com.myai.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
            // Only auto-start if user has set up a key
            if (!prefs.getString("groq_key", "").isNullOrEmpty()) {
                val svc = Intent(context, FloatingService::class.java)
                ContextCompat.startForegroundService(context, svc)
            }
        }
    }
}
