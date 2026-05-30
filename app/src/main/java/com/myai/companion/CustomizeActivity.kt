package com.myai.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CustomizeActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var web: WebView
    private var ready = false

    // selected values
    private val sel = HashMap<String, String>()

    private val skins  = listOf("#ffdbac", "#f1c27d", "#e0ac69", "#c68642", "#8d5524", "#5c3a21")
    private val hairs  = listOf("#2a1a0a", "#000000", "#6b3e1d", "#d4a017", "#b0b0b0", "#7b2fff", "#ff2ef7")
    private val shirts = listOf("#00f0ff", "#ff4a6a", "#10a37f", "#ffce00", "#7b2fff", "#ffffff", "#ff7a00")
    private val pantz  = listOf("#22305a", "#000000", "#3a3a3a", "#1a3a2a", "#5a2a2a", "#2a2a5a")
    private val eyez   = listOf("#1a1a1a", "#3a6ea5", "#2e7d32", "#6b3e1d", "#7b2fff")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customize)
        prefs = getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

        // load saved or defaults
        sel["skin"]  = prefs.getString("av_skin", skins[1])!!
        sel["hair"]  = prefs.getString("av_hair", hairs[0])!!
        sel["shirt"] = prefs.getString("av_shirt", shirts[0])!!
        sel["pants"] = prefs.getString("av_pants", pantz[0])!!
        sel["eyes"]  = prefs.getString("av_eyes", eyez[0])!!

        web = findViewById(R.id.previewWeb)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                applyToPreview()
            }
        }
        web.loadUrl("file:///android_asset/avatar.html")

        buildSwatches(findViewById(R.id.rowSkin), skins, "skin")
        buildSwatches(findViewById(R.id.rowHair), hairs, "hair")
        buildSwatches(findViewById(R.id.rowShirt), shirts, "shirt")
        buildSwatches(findViewById(R.id.rowPants), pantz, "pants")
        buildSwatches(findViewById(R.id.rowEyes), eyez, "eyes")

        findViewById<Button>(R.id.saveLookBtn).setOnClickListener {
            prefs.edit()
                .putString("av_skin", sel["skin"])
                .putString("av_hair", sel["hair"])
                .putString("av_shirt", sel["shirt"])
                .putString("av_pants", sel["pants"])
                .putString("av_eyes", sel["eyes"])
                .apply()
            Toast.makeText(this, "Avatar saved! 🎉", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun buildSwatches(row: LinearLayout, colors: List<String>, key: String) {
        val size = (44 * resources.displayMetrics.density).toInt()
        val margin = (5 * resources.displayMetrics.density).toInt()
        colors.forEach { hex ->
            val sw = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(margin, margin, margin, margin)
            sw.layoutParams = lp
            sw.setBackgroundColor(Color.parseColor(hex))
            sw.setOnClickListener {
                sel[key] = hex
                applyToPreview()
                if (key == "shirt") web.evaluateJavascript("wave()", null)
            }
            row.addView(sw)
        }
    }

    private fun applyToPreview() {
        if (!ready) return
        val js = "applyStyle('${sel["skin"]}','${sel["hair"]}','${sel["shirt"]}','${sel["pants"]}','${sel["eyes"]}','#111')"
        web.evaluateJavascript(js, null)
    }
}
