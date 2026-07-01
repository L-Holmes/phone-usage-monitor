package com.example.webtrafficmonitor

import android.graphics.PixelFormat
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.graphics.Typeface
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.graphics.Path


// =====================================================================================
// Mode  (relaxed vs strict; optional week-long strict lock)
// =====================================================================================
/**
 * Two modes:
 *   RELAXED - the calming "breathing" pause is suppressed for every app.
 *   STRICT  - normal behaviour (the breathing pause shows for the chosen apps).
 *
 * "Start week-long strict mode" sets STRICT and locks it for 7 days: until the timer
 * runs out the mode can't be switched back to RELAXED. Stored in SharedPreferences,
 * same best-effort durability as the other locks in this app.
 */
object Mode {
    private const val PREFS = "app_mode"
    private const val KEY_MODE = "mode"
    private const val KEY_LOCK_UNTIL = "strict_locked_until"
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    const val RELAXED = "relaxed"
    const val STRICT = "strict"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Current mode. A live strict lock forces STRICT regardless of the stored value. */
    fun current(ctx: Context): String {
        if (isLocked(ctx)) return STRICT
        return prefs(ctx).getString(KEY_MODE, RELAXED) ?: RELAXED
    }

    fun isRelaxed(ctx: Context) = current(ctx) == RELAXED
    fun isStrict(ctx: Context) = current(ctx) == STRICT

    /** True while the week-long strict lock is still running. */
    fun isLocked(ctx: Context): Boolean =
        prefs(ctx).getLong(KEY_LOCK_UNTIL, 0L) > System.currentTimeMillis()

    /** ms left on the lock (0 if not locked). */
    fun lockRemaining(ctx: Context): Long =
        (prefs(ctx).getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** A short "3d 4h left" style label for the lock. */
    fun daysLeft(ctx: Context): String {
        val hours = lockRemaining(ctx) / (60 * 60 * 1000)
        val d = hours / 24
        val h = hours % 24
        return when {
            d > 0 -> "${d}d ${h}h left"
            h > 0 -> "${h}h left"
            else -> "<1h left"
        }
    }

    /**
     * Change the mode. Refused (returns false) if the strict lock is active and you're
     * trying to go back to RELAXED. Switching TO strict is always allowed.
     */
    fun setMode(ctx: Context, mode: String): Boolean {
        if (isLocked(ctx) && mode == RELAXED) return false
        prefs(ctx).edit().putString(KEY_MODE, mode).apply()
        return true
    }

    /** Force STRICT and lock it for 7 days. */
    fun startWeekStrict(ctx: Context) {
        prefs(ctx).edit()
            .putString(KEY_MODE, STRICT)
            .putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + WEEK_MS)
            .apply()
    }
}


// Shared option lists, reused across Report flows (so "feeling" etc. is the SAME everywhere).
object Opts {
    val FEELINGS = listOf(
        "Bored", "Anxious / on edge", "Stressed", "Low / down",
        "Lonely", "Tired", "Frustrated / angry", "Happy / excited", "Neutral")
    val URGE_LEVELS = listOf("Barely there", "Mild", "Noticeable", "Strong", "Overwhelming")
    val LOCATIONS = listOf("Bedroom", "Bathroom", "Living room", "Kitchen", "Office / desk", "Out / in public")
    val SCREEN_TYPES = listOf("Phone", "Tablet", "Computer / laptop", "TV", "Someone else's screen")
}


// Logs each urge ridden out, for the "progress" graph. Lightweight (SharedPreferences).
// Full temptation records (time, what-you-saw, where, feeling, habit, urge) for stats.
object TemptationLog {
    private const val PREFS = "temptation_log"
    private const val KEY = "events"
    private const val MAX = 5000
    private const val SEP = "\u001F"

    data class Event(
        val ts: Long, val urge: String,
        val screen: String?, val location: String?, val feeling: String?, val doing: String?,
    )

    fun record(context: Context, urge: String, screen: String?, location: String?, feeling: String?, doing: String?) {
        val line = listOf(System.currentTimeMillis().toString(), urge,
            screen.orEmpty(), location.orEmpty(), feeling.orEmpty(), doing.orEmpty())
            .joinToString(SEP) { it.replace(SEP, " ").replace("\n", " ") }
        val list = read(context).toMutableList()
        list.add(line)
        while (list.size > MAX) list.removeAt(0)
        prefs(context).edit().putString(KEY, list.joinToString("\n")).apply()
    }

    fun all(context: Context): List<Event> = read(context).mapNotNull { parse(it) }
    fun total(context: Context) = read(context).size
    fun timestamps(context: Context) = all(context).map { it.ts }

    fun dailyCounts(context: Context, days: Int): IntArray {
        val counts = IntArray(days)
        val today = dayIndex(System.currentTimeMillis())
        for (ts in timestamps(context)) {
            val d = (today - dayIndex(ts)).toInt()
            if (d in 0 until days) counts[days - 1 - d]++
        }
        return counts
    }

    private fun parse(line: String): Event? {
        val p = line.split(SEP)
        val ts = p.getOrNull(0)?.toLongOrNull() ?: return null
        return Event(ts, p.getOrElse(1) { "" },
            p.getOrNull(2)?.ifBlank { null }, p.getOrNull(3)?.ifBlank { null },
            p.getOrNull(4)?.ifBlank { null }, p.getOrNull(5)?.ifBlank { null })
    }
    private fun dayIndex(ms: Long): Long {
        val off = java.util.TimeZone.getDefault().getOffset(ms)
        return (ms + off) / 86_400_000L
    }
    private fun read(c: Context) = prefs(c).getString(KEY, "").orEmpty().split("\n").filter { it.isNotEmpty() }
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


// Records each "look anyway" attempt and how it ended (stopped / tomorrow / looked).
object LoosenLog {
    private const val PREFS = "loosen_log"
    private const val KEY = "events"
    private const val MAX = 2000
    private const val SEP = "\u001F"

    data class Event(val ts: Long, val outcome: String, val regret: String?, val feeling: String?, val durationMin: Int)

    fun record(context: Context, outcome: String, regret: String?, feeling: String?, durationMin: Int) {
        val line = listOf(System.currentTimeMillis().toString(), outcome,
            regret.orEmpty(), feeling.orEmpty(), durationMin.toString())
            .joinToString(SEP) { it.replace(SEP, " ").replace("\n", " ") }
        val list = read(context).toMutableList()
        list.add(line)
        while (list.size > MAX) list.removeAt(0)
        prefs(context).edit().putString(KEY, list.joinToString("\n")).apply()
    }

    fun all(context: Context): List<Event> = read(context).mapNotNull { l ->
        val p = l.split(SEP); val ts = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        Event(ts, p.getOrElse(1) { "" }, p.getOrNull(2)?.ifBlank { null },
            p.getOrNull(3)?.ifBlank { null }, p.getOrNull(4)?.toIntOrNull() ?: 0)
    }
    private fun read(c: Context) = prefs(c).getString(KEY, "").orEmpty().split("\n").filter { it.isNotEmpty() }
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


// =====================================================================================
// Progress  (the reward view's data: a non-resetting consistency score + real stats)
// =====================================================================================
// Consistency = clean days within a rolling 30-day window. One slip just dips the
// number; it never wipes to zero. Slip days = reported relapses (recorded here) plus
// any supervised unlock that ended in "looked". Wins = urges ridden out + unlocks
// stopped. Everything is an estimate where noted, and milestones never un-earn.
object Progress {
    private const val PREFS = "progress"
    private const val KEY_SLIPS = "slips"
    private const val KEY_BEST = "best_clean30"
    private const val WINDOW = 30
    const val EST_MIN_PER_WIN = 25          // est. minutes reclaimed per urge ridden out
    const val VALUE_PER_HOUR_GBP = 12       // assumed value of reclaimed time, for the £ projection

    data class Snapshot(
        val hasData: Boolean,
        val trackedDays: Int, val cleanDays: Int, val slipDays: Int, val consistency: Int,
        val forgivingRun: Int, val bestClean: Int,
        val totalWins: Int, val reclaimedHours: Int,
        val projYearHours: Int, val projYearGbp: Int,
        val weeklyWins: FloatArray,
        val milestones: List<String>, val nextMilestone: String?,
    )

    fun recordSlip(context: Context, ts: Long = System.currentTimeMillis()) {
        val list = readSlips(context).toMutableList()
        list.add(ts.toString())
        while (list.size > 4000) list.removeAt(0)
        prefs(context).edit().putString(KEY_SLIPS, list.joinToString("\n")).apply()
    }

    fun snapshot(context: Context): Snapshot {
        val today = dayIndex(System.currentTimeMillis())
        val loosen = LoosenLog.all(context)
        val winTs = TemptationLog.timestamps(context) +
            loosen.filter { it.outcome == "stopped" || it.outcome == "tomorrow" }.map { it.ts }
        val slipTs = readSlips(context).mapNotNull { it.toLongOrNull() } +
            loosen.filter { it.outcome == "looked" }.map { it.ts }

        val allTs = winTs + slipTs
        if (allTs.isEmpty())
            return Snapshot(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, FloatArray(0), emptyList(), null)

        val firstIdx = allTs.minOf { dayIndex(it) }
        val daysSinceFirst = (today - firstIdx).toInt() + 1
        val trackedDays = daysSinceFirst.coerceIn(1, WINDOW)
        val windowStart = today - trackedDays + 1

        val slipDaySet = slipTs.map { dayIndex(it) }.toSet()
        val slipDaysInWindow = (windowStart..today).count { it in slipDaySet }
        val cleanDays = (trackedDays - slipDaysInWindow).coerceAtLeast(0)
        val consistency = if (trackedDays > 0) Math.round(cleanDays * 100f / trackedDays) else 0

        // forgiving run: walk back from today, absorbing up to one slip before it ends
        var budget = 1; var run = 0; var d = today
        while (d >= firstIdx) {
            if (d in slipDaySet) { if (budget > 0) { budget--; run++ } else break } else run++
            d--
        }

        val totalWins = winTs.size
        val winsInWindow = winTs.count { dayIndex(it) in windowStart..today }
        val weeklyRate = if (trackedDays > 0) winsInWindow * 7.0 / trackedDays else 0.0
        val reclaimedHours = (totalWins * EST_MIN_PER_WIN) / 60
        val projYearHours = Math.round(weeklyRate * 52 * EST_MIN_PER_WIN / 60.0).toInt()
        val projYearGbp = projYearHours * VALUE_PER_HOUR_GBP

        val weeks = FloatArray(8)
        for (ts in winTs) {
            val w = ((today - dayIndex(ts)) / 7).toInt()
            if (w in 0..7) weeks[7 - w] += 1f
        }

        val best = maxOf(prefs(context).getInt(KEY_BEST, 0), cleanDays)
        prefs(context).edit().putInt(KEY_BEST, best).apply()

        val ms = mutableListOf<String>()
        if (totalWins >= 1) ms.add("First urge ridden out")
        if (daysSinceFirst >= 7) ms.add("First week in")
        if (best >= 7) ms.add("A clean week in the bag")
        if (totalWins >= 25) ms.add("25 urges beaten")
        if (best >= 14) ms.add("Two clean weeks")
        if (best >= 30) ms.add("A clean month - every day counted")
        if (totalWins >= 100) ms.add("100 urges beaten")

        val next = when {
            totalWins < 1 -> "Ride out your first urge"
            daysSinceFirst < 7 -> "Reach your first full week"
            best < 7 -> "Get to 7 clean days in your window"
            totalWins < 25 -> "Ride out 25 urges ($totalWins/25)"
            best < 30 -> "Build toward a clean month ($best/30)"
            totalWins < 100 -> "Ride out 100 urges ($totalWins/100)"
            else -> null
        }

        return Snapshot(true, trackedDays, cleanDays, slipDaysInWindow, consistency, run, best,
            totalWins, reclaimedHours, projYearHours, projYearGbp, weeks, ms, next)
    }

    private fun readSlips(c: Context) =
        prefs(c).getString(KEY_SLIPS, "").orEmpty().split("\n").filter { it.isNotEmpty() }
    private fun dayIndex(ms: Long): Long {
        val off = java.util.TimeZone.getDefault().getOffset(ms)
        return (ms + off) / 86_400_000L
    }
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


// =====================================================================================
// Usage  (inputs for the "time wasted" calculator on the home screen)
// =====================================================================================
object Usage {
    private const val PREFS = "usage"
    private const val MIN = "min_per_day"
    private const val AGE = "age"
    private const val YEARS = "years"
    const val WAKING_HOURS = 16
    const val LIFE_EXPECTANCY = 80
    const val VALUE_PER_HOUR_GBP = 12
    fun minutes(c: Context) = prefs(c).getInt(MIN, 75)
    fun setMinutes(c: Context, v: Int) = prefs(c).edit().putInt(MIN, v).apply()
    fun age(c: Context) = prefs(c).getInt(AGE, 30)
    fun setAge(c: Context, v: Int) = prefs(c).edit().putInt(AGE, v).apply()
    fun years(c: Context) = prefs(c).getInt(YEARS, 10)
    fun setYears(c: Context, v: Int) = prefs(c).edit().putInt(YEARS, v).apply()
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


// =====================================================================================
// Protocol  (the "break the addiction" challenge: which big moves are done)
// =====================================================================================
object Protocol {
    private const val PREFS = "protocol"
    private const val APPS = "apps_done"
    private const val HOLIDAY = "holiday_done"
    private const val SEVEN = "seven_started_at"
    fun appsDone(c: Context) = prefs(c).getBoolean(APPS, false)
    fun setApps(c: Context, v: Boolean) = prefs(c).edit().putBoolean(APPS, v).apply()
    fun holidayDone(c: Context) = prefs(c).getBoolean(HOLIDAY, false)
    fun setHoliday(c: Context, v: Boolean) = prefs(c).edit().putBoolean(HOLIDAY, v).apply()
    fun sevenStarted(c: Context) = prefs(c).getLong(SEVEN, 0L) > 0L
    fun setSevenStarted(c: Context) = prefs(c).edit().putLong(SEVEN, System.currentTimeMillis()).apply()
    // Generic tickable checklist items (keyed by a stable id).
    fun isChecked(c: Context, key: String) = prefs(c).getBoolean("chk_$key", false)
    fun setChecked(c: Context, key: String, v: Boolean) = prefs(c).edit().putBoolean("chk_$key", v).apply()
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}



// =====================================================================================
// TriggerOptions  (custom temptation triggers the user typed; reused next time)
// =====================================================================================
object TriggerOptions {
    private const val PREFS = "temptation_triggers"
    private const val KEY = "triggers"
    private const val MAX = 20

    fun all(context: Context): List<String> = read(context)

    fun add(context: Context, name: String) {
        val clean = name.trim().replace("\n", " ")
        if (clean.isEmpty()) return
        val list = read(context).toMutableList()
        if (list.any { it.equals(clean, ignoreCase = true) }) return
        list.add(clean)
        while (list.size > MAX) list.removeAt(0)      // keep newest 20
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, list.joinToString("\n")).apply()
    }

    private fun read(context: Context): List<String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}


// =====================================================================================
// CustomOptions  (user-typed options per category - feeling / location / activity / screen)
// =====================================================================================
object CustomOptions {
    private const val PREFS = "custom_options"
    private const val MAX = 20

    /** Custom options the user has added for this category, oldest -> newest. */
    fun all(context: Context, category: String): List<String> = read(context, category)

    fun add(context: Context, category: String, name: String) {
        val clean = name.trim().replace("\n", " ")
        if (clean.isEmpty()) return
        val list = read(context, category).toMutableList()
        if (list.any { it.equals(clean, ignoreCase = true) }) return
        list.add(clean)
        while (list.size > MAX) list.removeAt(0)      // keep newest MAX
        prefs(context).edit().putString(key(category), list.joinToString("\n")).apply()
    }

    private fun read(context: Context, category: String): List<String> =
        prefs(context).getString(key(category), "").orEmpty()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun key(category: String) = "opts:${category.lowercase()}"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
