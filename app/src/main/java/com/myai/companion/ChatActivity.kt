package com.myai.companion

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.net.URL
import java.net.URLEncoder

class ChatActivity : AppCompatActivity(), android.speech.tts.TextToSpeech.OnInitListener {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private val history = ArrayList<Pair<String, String>>() // role, content
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var muted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        tts = android.speech.tts.TextToSpeech(this, this)

        messages = findViewById(R.id.messages)
        scroll = findViewById(R.id.chatScroll)

        val aiName = prefs.getString("ai_name", "Aura")
        findViewById<TextView>(R.id.chatTitle).text = "🤖 $aiName"
        findViewById<TextView>(R.id.chatStatus).text = if (isOnline()) "● online" else "● offline"

        val input = findViewById<EditText>(R.id.chatInput)
        findViewById<Button>(R.id.sendBtn).setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) { input.setText(""); send(t) }
        }
        findViewById<Button>(R.id.micBtn).setOnClickListener { listen() }
        findViewById<Button>(R.id.muteBtn).setOnClickListener {
            muted = !muted
            findViewById<Button>(R.id.muteBtn).text = if (muted) "🔇" else "🔊"
            if (muted) tts?.stop()
        }
        findViewById<Button>(R.id.clearBtn).setOnClickListener {
            messages.removeAllViews(); history.clear()
            val nm = prefs.getString("user_name", "") ?: ""
            addAi(LocalBrain.greeting(nm) + " Fresh start! What can I do for you?")
        }

        addChips(input)

        val name = prefs.getString("user_name", "") ?: ""
        addAi(LocalBrain.greeting(name) + " How can I help with your work today?")
    }

    private fun addChips(input: EditText) {
        val chips = findViewById<LinearLayout>(R.id.chips)
        val items = listOf("📋 Plan my day", "😂 Tell me a joke", "🎨 Draw something cool",
            "💪 Motivate me", "💡 Give me an idea", "🌍 Translate something")
        items.forEach { label ->
            val b = Button(this)
            b.text = label; b.textSize = 12f
            b.setBackgroundResource(R.drawable.card_bg)
            b.setTextColor(Color.parseColor("#00F0FF"))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 12, 6); b.layoutParams = lp
            b.setOnClickListener {
                val msg = when {
                    label.contains("Plan") -> "Plan my day for me"
                    label.contains("joke") -> "Tell me a joke"
                    label.contains("Draw") -> "Draw something cool and creative"
                    label.contains("Motivate") -> "Motivate me"
                    label.contains("idea") -> "Give me a creative idea for a project"
                    else -> "Help me translate a phrase"
                }
                send(msg)
            }
            chips.addView(b)
        }
    }

    override fun onInit(status: Int) {
        if (status == android.speech.tts.TextToSpeech.SUCCESS) {
            tts?.language = java.util.Locale.US; ttsReady = true
        }
    }

    private fun speak(text: String) {
        if (ttsReady && !muted)
            tts?.speak(text.take(500), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "c")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { tts?.shutdown() } catch (_: Exception) {}
    }

    // ── Send a message ──
    private fun send(text: String) {
        addUser(text)
        EmotionEngine.recordInteraction(prefs)
        val low = text.lowercase()
        val isImage = listOf("draw", "image of", "picture of", "generate an image", "create an image",
            "make an image", "generate a picture", "make a picture", "design an image", "paint")
            .any { low.contains(it) }
        if (isImage) { generateImage(cleanImagePrompt(text)) ; return }
        replyText(text)
    }

    private fun cleanImagePrompt(t: String): String {
        return t.replace(Regex("(?i)(draw me|draw a|draw an|draw|generate an image of|generate an image|create an image of|create an image|make an image of|make an image|make a picture of|generate a picture of|a picture of|image of|picture of|design an image of|paint me|paint a|paint)"), "").trim().ifEmpty { t }
    }

    // ── Text reply (online Groq / offline LocalBrain) ──
    private fun replyText(text: String) {
        val key = prefs.getString("groq_key", "") ?: ""
        val userName = prefs.getString("user_name", "") ?: ""
        val aiName = prefs.getString("ai_name", "Aura") ?: "Aura"
        history.add("user" to text)

        if (!isOnline() || key.isEmpty()) {
            val r = LocalBrain.respond(text, userName, aiName)
            addAi(r); history.add("assistant" to r); speak(r); return
        }
        val thinking = addAi("…")
        val mems = prefs.getStringSet("memories", HashSet())!!.toList()
        val memText = if (mems.isNotEmpty()) " You remember about them: ${mems.joinToString("; ")}." else ""
        val who = if (userName.isNotEmpty()) userName else "sir"
        GroqClient.ask(
            key,
            "You are $aiName, a brilliant JARVIS-style personal AI assistant helping $who with daily work, projects, and life. " +
                    "Be smart, helpful, warm and concise.$memText",
            text
        ) { reply ->
            val r = reply?.trim()?.ifEmpty { "Hmm, let me think again." } ?: "I had a connection issue."
            runOnUiThread {
                (thinking.tag as? TextView)?.text = r
                history.add("assistant" to r)
                speak(r)
                scrollDown()
            }
        }
    }

    // ── Image generation (Pollinations, free) ──
    private fun generateImage(prompt: String) {
        val loading = addAi("🎨 Generating image: $prompt …")
        Thread {
            try {
                val enc = URLEncoder.encode(prompt, "UTF-8")
                val seed = (0..99999).random()
                val url = URL("https://image.pollinations.ai/prompt/$enc?width=768&height=768&nologo=true&seed=$seed")
                val conn = url.openConnection()
                conn.connectTimeout = 20000; conn.readTimeout = 90000
                val bmp = BitmapFactory.decodeStream(conn.getInputStream())
                runOnUiThread {
                    (loading.tag as? TextView)?.text = "🎨 $prompt"
                    if (bmp != null) addImage(bmp)
                    scrollDown()
                }
            } catch (e: Exception) {
                runOnUiThread { (loading.tag as? TextView)?.text = "Couldn't generate image: ${e.message}" }
            }
        }.start()
    }

    // ── Voice input ──
    private fun listen() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voice not available", Toast.LENGTH_SHORT).show(); return
        }
        val rec = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        Toast.makeText(this, "🎤 Listening…", Toast.LENGTH_SHORT).show()
        rec.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: Bundle?) {
                val said = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!said.isNullOrEmpty()) send(said)
                rec.destroy()
            }
            override fun onError(error: Int) { rec.destroy() }
            override fun onReadyForSpeech(p0: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
        rec.startListening(intent)
    }

    // ── UI helpers ──
    private fun addUser(text: String): View = addBubble(text, true)
    private fun addAi(text: String): View = addBubble(text, false)

    private fun addBubble(text: String, isUser: Boolean): View {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(if (isUser) Color.parseColor("#0A0E1A") else Color.parseColor("#E0F7FF"))
        tv.textSize = 15f
        tv.setPadding(34, 26, 34, 26)
        tv.setBackgroundResource(if (isUser) R.drawable.bubble_user else R.drawable.bubble_ai)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (isUser) Gravity.END else Gravity.START
        lp.setMargins(if (isUser) 80 else 8, 8, if (isUser) 8 else 80, 8)
        tv.layoutParams = lp
        tv.setOnLongClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", tv.text))
            Toast.makeText(this, "Copied 📋", Toast.LENGTH_SHORT).show(); true
        }
        val holder = LinearLayout(this)
        holder.tag = tv
        holder.addView(tv)
        messages.addView(holder)
        scrollDown()
        return holder
    }

    private fun addImage(bmp: android.graphics.Bitmap) {
        val iv = ImageView(this)
        iv.setImageBitmap(bmp)
        iv.adjustViewBounds = true
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(8, 4, 60, 12)
        iv.layoutParams = lp
        messages.addView(iv)
        scrollDown()
    }

    private fun scrollDown() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    private fun isOnline(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val n = cm.activeNetwork; val c = cm.getNetworkCapabilities(n)
        c?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { false }
}
