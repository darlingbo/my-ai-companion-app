package com.myai.companion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import java.util.Calendar

/**
 * Action Engine — turns what you SAY into things your phone DOES.
 * "Set alarm for 7am", "Call 0244...", "Remind me to...", "Navigate to...", etc.
 * Returns a spoken confirmation, or null if it wasn't an action.
 */
object ActionEngine {

    data class Result(val spoken: String, val intent: Intent?)

    fun handle(ctx: Context, saidRaw: String): Result? {
        val said = saidRaw.trim()
        val t = said.lowercase()

        // ── ALARM ──  "set alarm for 7", "set an alarm at 6:30 am"
        if (t.contains("alarm")) {
            val (h, m) = parseClock(t) ?: return Result("What time should I set the alarm for?", null)
            val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h)
                putExtra(AlarmClock.EXTRA_MINUTES, m)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return Result("Setting an alarm for ${fmt(h, m)}.", i)
        }

        // ── TIMER ──  "set a timer for 5 minutes"
        if (t.contains("timer")) {
            val secs = parseDurationSeconds(t) ?: return Result("How long should the timer be?", null)
            val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, secs)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val mins = secs / 60
            return Result("Timer set for ${if (mins > 0) "$mins minute${if (mins>1) "s" else ""}" else "$secs seconds"}.", i)
        }

        // ── REMINDER ──  "remind me to call mom at 5pm" / "in 10 minutes"
        if (t.startsWith("remind me") || t.startsWith("reminder")) {
            val task = extractReminderTask(said)
            val triggerAt = parseReminderTime(t)
            if (triggerAt == null) return Result("When should I remind you? Try 'in 10 minutes' or 'at 5pm'.", null)
            ReminderScheduler.schedule(ctx, task, triggerAt)
            val cal = Calendar.getInstance().apply { timeInMillis = triggerAt }
            return Result("Okay, I'll remind you to $task at ${fmt(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))}.", null)
        }

        // ── CALL ──  "call 0244123456"
        if (t.startsWith("call ") || t.contains("dial ")) {
            val number = said.filter { it.isDigit() || it == '+' }
            return if (number.length >= 3) {
                Result("Calling $number…", Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } else {
                Result("Opening your dialer.", Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }
        }

        // ── WHATSAPP ──
        if (t.contains("whatsapp")) {
            val number = said.filter { it.isDigit() || it == '+' }.removePrefix("+")
            val url = if (number.length >= 6) "https://wa.me/$number" else "https://web.whatsapp.com"
            return Result("Opening WhatsApp.", web(url))
        }

        // ── TEXT / SMS ──  "text 0244..."
        if (t.startsWith("text ") || t.startsWith("sms ") || t.contains("send a message")) {
            val number = said.filter { it.isDigit() || it == '+' }
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            return Result("Opening messages.", i)
        }

        // ── NAVIGATE / DIRECTIONS ──
        if (t.contains("navigate") || t.contains("directions") || t.startsWith("take me to")) {
            val place = said.replace(Regex("(?i)(navigate to|navigate|directions to|directions|take me to)"), "").trim()
            val url = "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(place)}"
            return Result("Getting directions to $place.", web(url))
        }
        if (t.startsWith("where is") || t.contains("find a") || t.contains("nearby")) {
            val place = said.replace(Regex("(?i)(where is|find a|find|nearby)"), "").trim()
            return Result("Searching the map for $place.", web("https://www.google.com/maps/search/${Uri.encode(place)}"))
        }

        // ── YOUTUBE / MUSIC ──
        if (t.startsWith("play ") || t.contains("youtube") || t.contains("music")) {
            val q = said.replace(Regex("(?i)(play|on youtube|youtube|some music|music)"), "").trim()
            val url = if (q.isNotEmpty()) "https://www.youtube.com/results?search_query=${Uri.encode(q)}" else "https://www.youtube.com"
            return Result(if (q.isNotEmpty()) "Playing $q on YouTube." else "Opening YouTube.", web(url))
        }

        // ── SEARCH ──
        if (t.startsWith("search ") || t.startsWith("google ") || t.startsWith("look up")) {
            val q = said.replace(Regex("(?i)(search for|search|google|look up)"), "").trim()
            return Result("Searching for $q.", web("https://www.google.com/search?q=${Uri.encode(q)}"))
        }

        // ── OPEN APP ──
        if (t.startsWith("open ")) {
            val appName = t.removePrefix("open ").trim()
            val pkgIntent = openAppIntent(ctx, appName)
            return if (pkgIntent != null) Result("Opening $appName.", pkgIntent)
            else Result("I couldn't find $appName, searching the web.", web("https://www.google.com/search?q=${Uri.encode(appName)}"))
        }

        return null // not an action — let the brain reply
    }

    private fun web(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    private fun openAppIntent(ctx: Context, name: String): Intent? {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(0)
        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label.contains(name) || name.contains(label)) {
                val launch = pm.getLaunchIntentForPackage(app.packageName)
                if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); return launch }
            }
        }
        return null
    }

    // ── Time parsing helpers ──
    private fun parseClock(t: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(t) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3]
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }

    private fun parseDurationSeconds(t: String): Int? {
        val m = Regex("(\\d+)\\s*(hour|hours|hr|minute|minutes|min|second|seconds|sec)").find(t) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return when {
            m.groupValues[2].startsWith("hour") || m.groupValues[2] == "hr" -> n * 3600
            m.groupValues[2].startsWith("sec") -> n
            else -> n * 60
        }
    }

    private fun parseReminderTime(t: String): Long? {
        // "in 10 minutes / 2 hours"
        Regex("in\\s+(\\d+)\\s*(minute|minutes|min|hour|hours|hr|second|seconds|sec)").find(t)?.let {
            val n = it.groupValues[1].toInt()
            val ms = when {
                it.groupValues[2].startsWith("hour") || it.groupValues[2] == "hr" -> n * 3600_000L
                it.groupValues[2].startsWith("sec") -> n * 1000L
                else -> n * 60_000L
            }
            return System.currentTimeMillis() + ms
        }
        // "at 5pm / 5:30 pm"
        val idx = t.indexOf(" at ")
        if (idx >= 0) {
            parseClock(t.substring(idx))?.let { (h, m) ->
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
                }
                if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
                return cal.timeInMillis
            }
        }
        return null
    }

    private fun extractReminderTask(said: String): String {
        var s = said.replace(Regex("(?i)remind me to|remind me|reminder to|reminder"), "").trim()
        s = s.replace(Regex("(?i)\\s+(in\\s+\\d+.*|at\\s+\\d+.*)$"), "").trim()
        return s.ifEmpty { "your reminder" }
    }

    private fun fmt(h: Int, m: Int): String {
        val ap = if (h < 12) "AM" else "PM"
        var hh = h % 12; if (hh == 0) hh = 12
        return "%d:%02d %s".format(hh, m, ap)
    }
}
