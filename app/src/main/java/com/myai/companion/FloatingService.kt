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

class FloatingService : Service(), TextToSpeech.OnInitListener, android.hardware.SensorEventListener {

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
        try {
            registerReceiver(batteryReceiver,
                android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {}
        registerSensors()
    }

    // ── Sensor awareness: shake & flip ──
    private var sensorManager: android.hardware.SensorManager? = null
    private var lastShake = 0L
    private var lastShakeReact = 0L
    private var isSleeping = false

    private fun registerSensors() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val accel = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            sensorManager?.registerListener(this, accel,
                android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent?) {
        if (event == null || event.sensor.type != android.hardware.Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]

        // Flip face-down → sleep; face-up → wake
        if (z < -8.5f && !isSleeping) {
            isSleeping = true
            js("setExpression('sleepy')"); js("setMood('sleepy')")
            showSpeech("Going to sleep… 😴 zzz")
        } else if (z > 8.5f && isSleeping) {
            isSleeping = false
            js("setExpression('happy')"); avatarMood()
            showSpeech("I'm awake! 😊")
        }

        // Shake detection
        val g = Math.sqrt((x*x + y*y + z*z).toDouble()).toFloat()
        val now = System.currentTimeMillis()
        if (g > 22f) {
            if (now - lastShake < 600 && now - lastShakeReact > 3000) {
                lastShakeReact = now
                onShaken()
            }
            lastShake = now
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

    private fun onShaken() {
        js("setExpression('surprised')")
        val msgs = listOf("Whoa! Stop shaking me! 😵", "Wheee! That's dizzy! 🌀", "Haha, easy there! 😆")
        val m = msgs.random()
        showSpeech(m); speak(m)
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
        handler.postDelayed({ js("setExpression('happy')") }, 2500)
        EmotionEngine.recordInteraction(prefs, 1)
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
        setupAvatar()
        setupTouch()
        showSpeech(greeting())
    }

    // ── Animated character (WebView) ──
    private var avatarWeb: android.webkit.WebView? = null
    private var avatarReady = false

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun setupAvatar() {
        try {
            avatarWeb = bubbleView.findViewById(R.id.avatarWeb)
            avatarWeb?.apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        avatarReady = true
                        avatarMood()
                    }
                }
                loadUrl("file:///android_asset/avatar.html")
            }
        } catch (_: Exception) {
            // Fallback to emoji face if WebView fails
            val f = bubbleView.findViewById<TextView>(R.id.bubbleFace)
            f.visibility = View.VISIBLE; f.text = bubbleEmoji()
        }
    }

    private fun js(code: String) {
        try { avatarWeb?.evaluateJavascript(code, null) } catch (_: Exception) {}
    }

    private fun avatarTalk(text: String) {
        val ms = (text.length * 60).coerceIn(900, 6000)
        if (avatarReady) js("talk($ms)")
    }

    private fun avatarMood() {
        if (!avatarReady) return
        val m = when (EmotionEngine.mood(prefs)) {
            EmotionEngine.Mood.EXCITED -> "excited"
            EmotionEngine.Mood.LONELY -> "lonely"
            EmotionEngine.Mood.SLEEPY -> "sleepy"
            else -> "normal"
        }
        js("setMood('$m')")
    }

    private var lastX = 0
    private var lastY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var isDragging = false

    private fun setupTouch() {
        val face = bubbleView.findViewById<View>(R.id.touchLayer)
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
                    if (!isDragging) onBubbleTap() else snapToEdge()
                    true
                }
                else -> false
            }
        }
    }

    // Snap the bubble to the nearest left/right edge after dragging
    private fun snapToEdge() {
        val viewW = bubbleView.width.takeIf { it > 0 } ?: 220
        val targetX = if (params.x + viewW / 2 < screenW / 2) 0 else screenW - viewW
        val anim = android.animation.ValueAnimator.ofInt(params.x, targetX)
        anim.duration = 350
        anim.interpolator = android.view.animation.OvershootInterpolator(1.2f)
        anim.addUpdateListener { a ->
            params.x = a.animatedValue as Int
            try { windowManager.updateViewLayout(bubbleView, params) } catch (_: Exception) {}
        }
        anim.start()
    }

    private var lastTapTime = 0L

    private fun onBubbleTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 400) {
            // Double tap → open full web chat
            openWebChat()
            lastTapTime = 0
            return
        }
        lastTapTime = now
        // Bond grows with every interaction
        val newLevel = EmotionEngine.recordInteraction(prefs)
        if (newLevel != null) celebrateLevelUp(newLevel)
        // Single tap → voice conversation (listen + reply)
        handler.postDelayed({
            if (System.currentTimeMillis() - lastTapTime >= 380) startListening()
        }, 420)
    }

    private fun celebrateLevelUp(level: Int) {
        val title = EmotionEngine.bondTitle(level)
        val msg = "💖 Bond Level $level! We're now $title!"
        showSpeech(msg); speak("Our bond grew! We are now $title!")
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0,100,80,100), -1))
        } catch (_: Exception) {}
    }

    private fun openWebChat() {
        showSpeech("Opening full chat…")
        try {
            val url = prefs.getString("chat_url",
                "https://my-ai-assistant-ltwrdkacjgc59epbteifua.streamlit.app")
            val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {}
    }

    // ── Voice conversation ──
    private var recognizer: android.speech.SpeechRecognizer? = null

    private fun startListening() {
        val face = bubbleView.findViewById<TextView>(R.id.bubbleFace)
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            showSpeech("Voice not available — tap twice to open chat.")
            return
        }
        face.text = "👂"
        showSpeech("I'm listening…")
        try {
            recognizer?.destroy()
            recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(
                        android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    val said = list?.firstOrNull() ?: ""
                    face.text = bubbleEmoji()
                    if (said.isNotEmpty()) respondTo(said) else showSpeech("I didn't catch that.")
                }
                override fun onError(error: Int) {
                    face.text = bubbleEmoji()
                    showSpeech("Hmm, I didn't hear you. Tap to try again!")
                }
                override fun onReadyForSpeech(p0: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(p0: Float) {}
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onEndOfSpeech() { face.text = "🧠" }
                override fun onPartialResults(p0: Bundle?) {}
                override fun onEvent(p0: Int, p1: Bundle?) {}
            })
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            face.text = bubbleEmoji()
            showSpeech("Mic error — check microphone permission.")
        }
    }

    private fun respondTo(said: String) {
        val key = prefs.getString("groq_key", "") ?: ""
        val userName = prefs.getString("user_name", "") ?: ""
        val aiName = prefs.getString("ai_name", "Aura") ?: "Aura"

        // ── Voice ACTIONS first (alarms, reminders, call, navigate, open apps...) ──
        try {
            val action = ActionEngine.handle(this, said)
            if (action != null) {
                showSpeech(action.spoken); speak(action.spoken)
                action.intent?.let { i ->
                    try { startActivity(i) } catch (_: Exception) {}
                }
                return
            }
        } catch (_: Exception) {}

        // ── Local memory (works offline) ──
        val lower = said.lowercase().trim()
        if (lower.startsWith("remember ")) {
            val fact = said.substring(8).trim()
            val mem = prefs.getStringSet("memories", HashSet())!!.toMutableSet()
            mem.add(fact)
            prefs.edit().putStringSet("memories", mem).apply()
            val r = "Got it — I'll remember that. 🧠"
            showSpeech(r); speak(r); return
        }
        if (lower.contains("what do you remember") || lower.contains("what did i tell you")
            || lower.contains("remember anything")) {
            val mem = prefs.getStringSet("memories", HashSet())!!.toList()
            val r = if (mem.isEmpty()) "You haven't told me anything to remember yet."
                    else "I remember: " + mem.takeLast(5).joinToString("; ")
            showSpeech(r); speak(r); return
        }
        if (lower.contains("forget everything") || lower.contains("clear memory")) {
            prefs.edit().remove("memories").apply()
            val r = "Done — I've cleared my memory."
            showSpeech(r); speak(r); return
        }

        if (!isOnline() || key.isEmpty()) {
            val reply = LocalBrain.respond(said, userName, aiName)
            showSpeech(reply); speak(reply); return
        }
        showSpeech("…")
        val mems = prefs.getStringSet("memories", HashSet())!!.toList()
        val memText = if (mems.isNotEmpty()) " Things you remember about them: ${mems.joinToString("; ")}." else ""
        GroqClient.ask(
            key,
            "You are $aiName, a warm AI companion on ${if (userName.isNotEmpty()) "$userName's" else "the user's"} phone. Reply in 1-2 short sentences, friendly and natural.$memText",
            said
        ) { reply ->
            val text = reply?.trim()?.ifEmpty { LocalBrain.respond(said, userName, aiName) }
                ?: LocalBrain.respond(said, userName, aiName)
            handler.post { showSpeech(text); speak(text) }
        }
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

        if (isSleeping) return  // don't talk while sleeping (face down)

        // 40% of the time, express a feeling (mood-aware) instead of a question
        val moodTalk = (0..9).random() < 4
        if (moodTalk) {
            val m = EmotionEngine.moodMessage(prefs, userName)
            showSpeech(m); speak(m); return
        }

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

    // Rotating orbit ring — like a hologram spinning in space
    private fun startOrbit(view: View) {
        val spin = android.animation.ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f)
        spin.duration = 6000
        spin.repeatCount = android.animation.ValueAnimator.INFINITE
        spin.interpolator = android.view.animation.LinearInterpolator()
        spin.start()
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
        avatarTalk(text)
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
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { sensorManager?.unregisterListener(this) } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { windowManager.removeView(bubbleView) } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
    }

    // ── Battery awareness — comments when battery gets low ──
    private var lowBatteryWarned = false
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level < 0) return
            val pct = (level * 100 / scale)
            val name = prefs.getString("user_name", "") ?: ""
            val n = if (name.isNotEmpty()) ", $name" else ""
            if (pct <= 15 && !lowBatteryWarned) {
                lowBatteryWarned = true
                val msg = "Battery low ($pct%)$n — better charge soon! 🔋"
                showSpeech(msg); speak(msg)
            }
            if (pct > 30) lowBatteryWarned = false

            // Charging companionship
            val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (charging && !wasCharging) {
                wasCharging = true
                val msg = "Recharging together$n! 🔌⚡"
                showSpeech(msg)
            } else if (!charging && wasCharging) {
                wasCharging = false
            }
        }
    }
    private var wasCharging = false
}
