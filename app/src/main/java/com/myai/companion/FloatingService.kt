package com.myai.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class FloatingService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var tts: TextToSpeech
    private lateinit var prefs: android.content.SharedPreferences
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private val rng = Random(System.currentTimeMillis())

    // movement state
    private var screenW = 0
    private var screenH = 0
    private var velY = 0f
    private var movingUp = false

    private val CHANNEL = "ai_companion"

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ai:companion").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }

        startAsForeground()
        showBubble()
        startMovementLoop()
        scheduleTalking()
    }

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "AI Companion",
                NotificationManager.IMPORTANCE_LOW)
            ch.description = "Keeps your AI companion alive"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val aiName = prefs.getString("ai_name", "Aura")
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("$aiName is alive 🤖")
            .setContentText("Tap your bubble to chat. I'm always here!")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    private fun showBubble() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        params.x = screenW - 220
        params.y = screenH / 2

        windowManager.addView(bubbleView, params)
        setupTouch()

        val faceView = bubbleView.findViewById<TextView>(R.id.bubbleFace)
        faceView.text = bubbleEmoji()
        startPulse(faceView)
        showSpeech(greeting())
    }

    private var lastX = 0
    private var lastY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var isDragging = false

    private fun setupTouch() {
        val face = bubbleView.findViewById<TextView>(R.id.bubbleFace)
        face.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = params.x; lastY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) isDragging = true
                    params.x = lastX + dx
                    params.y = lastY + dy
                    windowManager.updateViewLayout(bubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) onBubbleTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun onBubbleTap() {
        val face = bubbleView.findViewById<TextView>(R.id.bubbleFace)
        face.text = "👂"
        showSpeech("Hi! Opening our chat…")
        speak("Hi! Let's chat.")
        handler.postDelayed({ face.text = bubbleEmoji() }, 1500)
        // Open the chat app (your Streamlit web app) — change this URL if you want
        try {
            val url = prefs.getString("chat_url",
                "https://my-ai-assistant-ltwrdkacjgc59epbteifua.streamlit.app")
            val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {}
    }

    // ── Continuous up/down floating movement ──
    private fun startMovementLoop() {
        val mover = object : Runnable {
            override fun run() {
                if (!bubbleView.isAttachedToWindow) return
                // gentle autonomous up/down drift
                if (movingUp) { params.y -= 4 } else { params.y += 4 }
                if (params.y < 120) movingUp = false
                if (params.y > screenH - 320) movingUp = true
                // occasional random direction flip so it wanders
                if (rng.nextInt(120) == 0) movingUp = !movingUp
                try { windowManager.updateViewLayout(bubbleView, params) } catch (_: Exception) {}
                handler.postDelayed(this, 40)
            }
        }
        handler.postDelayed(mover, 1000)
    }

    // ── Talking & asking questions on a timer ──
    private fun scheduleTalking() {
        val talkLoop = object : Runnable {
            override fun run() {
                askSomething()
                // every 3–6 minutes
                handler.postDelayed(this, (180_000L + rng.nextInt(180_000)).toLong())
            }
        }
        // first question after 20 seconds
        handler.postDelayed(talkLoop, 20_000)
    }

    private fun askSomething() {
        val key = prefs.getString("groq_key", "") ?: ""
        val userName = prefs.getString("user_name", "") ?: ""
        val aiName = prefs.getString("ai_name", "Aura") ?: "Aura"

        // OFFLINE or no key → use the built-in local brain
        if (!isOnline() || key.isEmpty()) {
            val q = LocalBrain.checkInQuestion(userName)
            showSpeech(q); speak(q); return
        }
        // ONLINE → ask the smarter cloud AI
        GroqClient.ask(
            key,
            "You are $aiName, a caring AI companion living on ${if (userName.isNotEmpty()) "$userName's" else "the user's"} phone. " +
                    "Say ONE short, warm sentence to check in on them or ask a question. Max 12 words. No quotes.",
            "Say something to me now."
        ) { reply ->
            val text = reply?.trim()?.ifEmpty { LocalBrain.checkInQuestion(userName) }
                ?: LocalBrain.checkInQuestion(userName)
            handler.post { showSpeech(text); speak(text) }
        }
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) { false }
    }

    private fun bubbleEmoji(): String {
        return prefs.getString("bubble_emoji", "🤖") ?: "🤖"
    }

    // Futuristic pulsing glow — orb breathes like a living hologram
    private fun startPulse(view: View) {
        val scaleUp = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            view,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f)
        )
        scaleUp.duration = 1400
        scaleUp.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleUp.repeatMode = android.animation.ValueAnimator.REVERSE
        scaleUp.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        scaleUp.start()
    }

    private fun greeting(): String {
        val name = prefs.getString("user_name", "") ?: ""
        return LocalBrain.greeting(name)
    }

    private fun showSpeech(text: String) {
        val tv = bubbleView.findViewById<TextView>(R.id.bubbleSpeech)
        tv.text = text
        tv.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages("hide")
        handler.postDelayed({ tv.visibility = View.GONE }, 6000)
    }

    private fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "id1")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            ttsReady = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY  // restart if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { windowManager.removeView(bubbleView) } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
    }
}
