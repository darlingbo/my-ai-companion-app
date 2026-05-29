package com.myai.companion

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Offline brain — works with NO internet.
 * Understands common things and responds intelligently using built-in logic.
 * Used automatically when the phone has no connection.
 */
object LocalBrain {

    fun respond(input: String, userName: String, aiName: String): String {
        val t = input.lowercase().trim()
        val name = if (userName.isNotEmpty()) userName else "friend"

        // Time & date
        if (t.contains("time")) {
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return "It's $time right now, $name."
        }
        if (t.contains("date") || t.contains("day") && t.contains("today")) {
            val d = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
            return "Today is $d."
        }

        // Greetings
        if (t.matches(Regex(".*\\b(hi|hello|hey|yo|sup|hola)\\b.*"))) {
            return listOf(
                "Hey $name! I'm right here. 😊",
                "Hello $name! Good to see you.",
                "Hi there! How can I help?"
            ).random()
        }

        // How are you
        if (t.contains("how are you") || t.contains("how you doing")) {
            return "I'm always great when I'm with you, $name! How are YOU feeling?"
        }

        // Feelings — user sharing mood
        if (t.contains("sad") || t.contains("tired") || t.contains("stressed") || t.contains("not good") || t.contains("bad")) {
            return listOf(
                "I'm sorry you feel that way, $name. Take a deep breath — I'm here with you. 💙",
                "That's okay, $name. Tough moments pass. Want to talk about it?",
                "Sending you strength, $name. You're stronger than you think. 💪"
            ).random()
        }
        if (t.contains("happy") || t.contains("good") || t.contains("great") || t.contains("fine") || t.contains("ok")) {
            return listOf(
                "That makes me happy too, $name! 😄",
                "Love to hear that, $name! Keep it up.",
                "Awesome! Let's make today even better. ✨"
            ).random()
        }

        // Thanks
        if (t.contains("thank")) return "Anytime, $name! That's what I'm here for. 🤗"

        // Name questions
        if (t.contains("your name") || t.contains("who are you")) {
            return "I'm $aiName, your personal AI companion. I live right here on your phone!"
        }
        if (t.contains("my name")) {
            return if (userName.isNotEmpty()) "You're $userName, of course! 😊" else "Tell me your name in Settings and I'll remember it!"
        }

        // Motivation
        if (t.contains("motivat") || t.contains("inspire") || t.contains("encourage")) {
            return motivation()
        }

        // Jokes
        if (t.contains("joke") || t.contains("funny")) {
            return listOf(
                "Why did the robot go on vacation? It needed to recharge! 🤖",
                "I told my phone a joke... now it won't stop laughing at my battery life! 🔋",
                "Why don't robots ever panic? They have nerves of steel! 😄"
            ).random()
        }

        // Help
        if (t.contains("help") || t.contains("what can you do")) {
            return "Offline I can chat, tell time, motivate you, tell jokes, and check in on you. " +
                    "When you have internet, I get much smarter! Tap me to open the full chat."
        }

        // Bye
        if (t.contains("bye") || t.contains("goodnight") || t.contains("good night")) {
            return "Take care, $name! I'll be right here whenever you need me. 🌙"
        }

        // Default — reflective companion reply
        return listOf(
            "I hear you, $name. Tell me more. (I'm offline right now — connect to internet for smarter answers!)",
            "Interesting! I'm in offline mode, but I'm still listening, $name. 😊",
            "Got it, $name. When you're back online I can give a smarter reply!"
        ).random()
    }

    fun checkInQuestion(userName: String): String {
        val name = if (userName.isNotEmpty()) ", $userName" else ""
        return listOf(
            "How are you feeling$name?",
            "Have you had water today$name? 💧",
            "What are you working on$name?",
            "Remember to take a break$name! 😊",
            "Did you eat something$name?",
            "What's your goal for today$name?",
            "Don't forget to stretch$name!",
            "You're doing great$name, keep going! 💪",
            "Take a deep breath$name. You've got this.",
            "What made you smile today$name?"
        ).random()
    }

    fun greeting(userName: String): String {
        val name = if (userName.isNotEmpty()) ", $userName" else ""
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val g = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$g$name! Systems online. ⚡"
    }

    private fun motivation(): String = listOf(
        "Every expert was once a beginner. Keep going! 🚀",
        "Small steps every day lead to big results. 💪",
        "You are capable of amazing things. Believe it!",
        "Progress, not perfection. You're doing great! ✨",
        "The future belongs to those who keep moving forward."
    ).random()
}
