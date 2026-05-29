package com.myai.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val task = intent?.getStringExtra("task") ?: "your reminder"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("reminders", "Reminders", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Your AI reminders"
            nm.createNotificationChannel(ch)
        }

        // Tapping the reminder opens the app
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        val pi = android.app.PendingIntent.getActivity(context, 0, openApp, flags)

        val notif = NotificationCompat.Builder(context, "reminders")
            .setContentTitle("⏰ Reminder")
            .setContentText(task)
            .setStyle(NotificationCompat.BigTextStyle().bigText(task))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        nm.notify((System.currentTimeMillis() % 100000).toInt(), notif)
    }
}
