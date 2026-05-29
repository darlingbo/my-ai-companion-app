package com.myai.companion

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny Groq API client using plain HttpURLConnection (no extra dependencies).
 * Runs networking on a background thread and returns the reply on a callback.
 */
object GroqClient {

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "llama-3.3-70b-versatile"

    fun ask(apiKey: String, system: String, userMessage: String, callback: (String?) -> Unit) {
        Thread {
            var result: String? = null
            try {
                val url = URL(ENDPOINT)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true
                conn.connectTimeout = 20000
                conn.readTimeout = 30000

                val messages = JSONArray()
                messages.put(JSONObject().put("role", "system").put("content", system))
                messages.put(JSONObject().put("role", "user").put("content", userMessage))

                val body = JSONObject()
                    .put("model", MODEL)
                    .put("messages", messages)
                    .put("max_tokens", 150)
                    .put("temperature", 0.8)

                val os: OutputStream = conn.outputStream
                os.write(body.toString().toByteArray(Charsets.UTF_8))
                os.flush(); os.close()

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream)).readText()

                if (code in 200..299) {
                    val json = JSONObject(text)
                    result = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }
            } catch (_: Exception) {
                result = null
            }
            callback(result)
        }.start()
    }
}
