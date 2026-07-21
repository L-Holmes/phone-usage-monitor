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
 * Four modes:
 *   OFF           - monitoring is off entirely: no scanning, no blocking, no covers.
 *                   THE DEFAULT on a fresh install. This is the only mode where the two
 *                   core permissions (page monitoring + block overlay) are optional -
 *                   pick anything higher and MainActivity's setup gate makes them
 *                   mandatory again (see currentStep()).
 *   RELAXED       - the calming "breathing" pause is suppressed for every app.
 *   STRICT        - the breathing pause shows on the FIRST open of a chosen app each day.
 *   SUPERHARDCORE - the breathing pause shows on EVERY open of a chosen app, plus the
 *                   tightest flagging thresholds.
 *
 * The per-mode behaviour that actually differs lives in AppConfig.MODES (one ModeSpec
 * per mode) - keep it there rather than sprinkling `if (isStrict)` around, because the
 * user-facing rules screen (showModeRules) is generated straight from those specs.
 *
 * "Start week-long strict mode" sets STRICT and locks it for 7 days: until the timer
 * runs out the mode can't be loosened. Stored in SharedPreferences, same best-effort
 * durability as the other locks in this app.
 */
object Mode {
    private const val PREFS = "app_mode"
    private const val KEY_MODE = "mode"
    private const val KEY_LOCK_UNTIL = "strict_locked_until"
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    const val OFF = "off"
    const val RELAXED = "relaxed"
    const val STRICT = "strict"
    const val SUPERHARDCORE = "superhardcore"

    /** Loose -> tight. Used by the lock, so it can refuse a downgrade but allow an upgrade. */
    private fun rank(mode: String) = when (mode) {
        SUPERHARDCORE -> 2
        STRICT -> 1
        RELAXED -> 0
        else -> -1        // OFF (and anything unknown) sits below every real mode
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Current mode. A live strict lock enforces a FLOOR of STRICT - it no longer
     * flattens a stricter choice, so someone locked into the week can still sit in
     * superhardcore.
     */
    fun current(ctx: Context): String {
        // OFF is the fresh-install default: monitoring starts disabled and the two core
        // permissions stay optional until the user picks a real mode (or finishes the
        // guided permission flow, which bumps this to RELAXED).
        val stored = prefs(ctx).getString(KEY_MODE, OFF) ?: OFF
        if (isLocked(ctx) && rank(stored) < rank(STRICT)) return STRICT
        return stored
    }

    fun isOff(ctx: Context) = current(ctx) == OFF
    fun isRelaxed(ctx: Context) = current(ctx) == RELAXED
    fun isStrict(ctx: Context) = current(ctx) == STRICT
    fun isSuperHardcore(ctx: Context) = current(ctx) == SUPERHARDCORE

    /** The behaviour spec for the mode in force. Single source of truth for what a mode does. */
    fun spec(ctx: Context): AppConfig.ModeSpec {
        val id = current(ctx)
        return AppConfig.MODES.firstOrNull { it.id == id } ?: AppConfig.MODES.first()
    }

    /**
     * One-time migration for installs that predate OFF. They lived behind the mandatory
     * permission gate, so "no stored choice + both permissions granted" means monitoring
     * was already live - keep it live as RELAXED rather than silently switching it off.
     * Called with permsGranted from MainActivity (both checks) and from the service
     * (accessibility is on by definition there, so it checks the overlay only).
     */
    fun migrateIfUnset(ctx: Context, permsGranted: Boolean) {
        if (permsGranted && !prefs(ctx).contains(KEY_MODE))
            prefs(ctx).edit().putString(KEY_MODE, RELAXED).apply()
    }

    /** True while the week-long strict lock is still running. */
    fun isLocked(ctx: Context): Boolean =
        prefs(ctx).getLong(KEY_LOCK_UNTIL, 0L) > System.currentTimeMillis()

    /** ms left on the lock (0 if not locked). */
    fun lockRemaining(ctx: Context): Long =
        (prefs(ctx).getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * A short "3d 4h" / "4h" / "<1h" countdown for the lock, in the app's language (Units).
     * The DURATION only - every caller's string supplies its own "left"/"remaining" wording.
     */
    fun timeLeft(ctx: Context): String {
        val hours = lockRemaining(ctx) / (60 * 60 * 1000)
        val d = hours / 24
        val h = hours % 24
        return when {
            d > 0 -> Units.daysHours(ctx, d, h)
            h > 0 -> Units.hours(ctx, h)
            else -> Units.underAnHour(ctx)
        }
    }

    /**
     * Change the mode. While the strict lock is active any LOOSENING is refused (returns
     * false) - so you can go strict -> superhardcore, but not back down to relaxed.
     * Super hardcore additionally REQUIRES the uninstall lock to be active: a mode this
     * strict is pointless if the app can just be deleted in a weak moment. Callers should
     * check the same condition first to give a useful message (see modeSpinner).
     */
    fun setMode(ctx: Context, mode: String): Boolean {
        if (isLocked(ctx) && rank(mode) < rank(STRICT)) return false
        if (mode == SUPERHARDCORE &&
            !(UninstallGuard.isEnabled(ctx) && UninstallGuard.isAdminActive(ctx))) return false
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


// #####################################################################################
// #                                                                                   #
// #   BypassWatch  —  WHY "I'M GOING TO LOOK ANYWAY" IS NOT ON THE MENU               #
// #                                                                                   #
// #   THE PROBLEM WE ARE SOLVING                                                       #
// #   The supervised "look anyway" flow (a wait, a written commitment, a hard cap of   #
// #   one a day and 3 for life) is a GOOD thing to have. But sitting on the adult-     #
// #   content page as a permanent button, it is also an invitation. It turns a wall    #
// #   into a door with a handle, and every urge eventually tries the handle.           #
// #                                                                                   #
// #   THE RULE                                                                         #
// #   The button is GONE from the normal UI. It is not offered, not hinted at, not     #
// #   discoverable. It only appears once the user has already, unambiguously, started  #
// #   trying to TEAR THE GUARD DOWN — and then it is offered as the better option they #
// #   were about to skip past.                                                         #
// #                                                                                   #
// #   WHAT COUNTS AS "TRYING TO TEAR IT DOWN" (see BypassWatch.Reason)                 #
// #     • opening our entry in Settings to UNINSTALL or FORCE STOP the app             #
// #     • opening the DEVICE ADMIN page to deactivate the uninstall lock               #
// #     • opening the ACCESSIBILITY page for our service (i.e. to switch monitoring off)#
// #     • opening the "appear on top" permission page (i.e. to kill the block cover)   #
// #     • trying to drop out of a locked strict mode                                   #
// #     • wiping the ban list / block rules                                            #
// #   Those are all already DETECTED and bounced by the app. We now also record them.  #
// #                                                                                   #
// #   WHY THIS IS THE RIGHT TRADE                                                      #
// #   Someone at that point is going to get what they want one way or another — the    #
// #   app cannot physically stop a determined person with root and a factory reset.    #
// #   The only question is whether they do it by DESTROYING the guard (and losing      #
// #   every streak, log and protection, probably for good) or by taking a supervised,  #
// #   time-limited, counted window and keeping the guard standing afterwards. Offering  #
// #   the honest exit exactly at the moment they reach for the dishonest one is the     #
// #   whole point.                                                                     #
// #                                                                                   #
// #   It stays offered for OFFER_WINDOW_MS after the attempt, then goes away again.    #
// #   (The loosen flow's own limits — one a day, 3 for life — still apply on top. This #
// #   makes it HARDER to reach, never easier.)                                         #
// #                                                                                   #
// #   The button also lives permanently in Developer tools, so it can be tested        #
// #   without having to fake a bypass attempt.                                         #
// #                                                                                   #
// #####################################################################################
object BypassWatch {

    private const val PREFS = "bypass_watch"
    private const val KEY_AT = "last_attempt_at"
    private const val KEY_REASON = "last_reason"
    private const val KEY_COUNT = "total"

    /** How long an attempt keeps the honest exit on the table. */
    const val OFFER_WINDOW_MS = 30L * 60 * 1000

    /** The things that count. Kept as text so the reason can be shown back to the user. */
    object Reason {
        const val UNINSTALL = "you opened the uninstall / force-stop screen"
        const val DEVICE_ADMIN = "you went to turn off the uninstall lock"
        const val ACCESSIBILITY = "you went to switch monitoring off"
        const val OVERLAY = "you went to take away the block screen's permission"
        const val LEAVE_STRICT = "you tried to drop out of strict mode"
        const val WIPE_RULES = "you tried to wipe your own ban list"
    }

    // Sitting on the Settings uninstall page fires accessibility events continuously, so a
    // naive record() would count one visit as fifty attempts and hammer SharedPreferences.
    // One visit = one attempt: the same reason inside this window is the same attempt.
    private const val DEDUPE_MS = 60_000L
    private var lastReasonAt = 0L
    private var lastReasonSeen: String? = null

    /** Record an attempt. Safe to call on every event - repeats collapse into one. */
    @Synchronized
    fun record(ctx: Context, reason: String) {
        val now = System.currentTimeMillis()
        val repeat = reason == lastReasonSeen && now - lastReasonAt < DEDUPE_MS
        lastReasonSeen = reason
        lastReasonAt = now

        val p = prefs(ctx)
        val edit = p.edit()
            .putLong(KEY_AT, now)              // always refresh the window
            .putString(KEY_REASON, reason)
        if (!repeat) edit.putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1)
        edit.apply()
    }

    /** Is the honest exit currently on the table? */
    fun isArmed(ctx: Context): Boolean = remaining(ctx) > 0

    fun remaining(ctx: Context): Long =
        (prefs(ctx).getLong(KEY_AT, 0L) + OFFER_WINDOW_MS - System.currentTimeMillis())
            .coerceAtLeast(0L)

    fun lastReason(ctx: Context): String? = prefs(ctx).getString(KEY_REASON, null)

    /** When the current arming happened - lets the UI show its offer once per arming. */
    fun armedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_AT, 0L)

    fun totalAttempts(ctx: Context): Int = prefs(ctx).getInt(KEY_COUNT, 0)

    /** Called when they take the offer (or it lapses), so it isn't left sitting there. */
    fun disarm(ctx: Context) = prefs(ctx).edit().remove(KEY_AT).apply()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


// =====================================================================================
// BreathingGate  (how often the app-open breathing pause is allowed to fire)
// =====================================================================================
/**
 * Decides whether opening a watched app earns a breathing pause right now.
 *
 *  - RELAXED       : never (unless a loosen window is running - that re-arms the gate).
 *  - STRICT        : the FIRST open of that app each calendar day. Re-opening it later the
 *                    same day goes straight through. This is what stops the pause eating
 *                    2FA codes: you tab to the authenticator, grab the code, tab back, and
 *                    the gate does not fire again.
 *  - SUPERHARDCORE : every single open, no daily pass.
 *
 * The pass is per app and per calendar day, so it clears itself at midnight.
 *
 * KNOWN GAP - "reset when the app is swiped away": not implemented, because Android gives
 * an accessibility service no dependable signal for it. Visible-window checks can't see a
 * backgrounded-but-alive app, and a cold-start heuristic based on the first activity is
 * useless for exactly the apps we gate (Firefox/Fenix is single-activity, so a resume and
 * a fresh launch look identical). The honest options are to leave the daily pass as-is, or
 * to take the PACKAGE_USAGE_STATS special permission and watch for ACTIVITY_DESTROYED.
 * If you take that route, call [reset] with the package - that is the only hook needed.
 */
object BreathingGate {

    private const val PREFS = "breathing_gate"

    /** True if [pkg] should get the breathing pause on this open. */
    fun shouldBreathe(ctx: Context, pkg: String): Boolean {
        val spec = Mode.spec(ctx)
        // A loosen window re-arms the pause even in Relaxed: the whole point of the window
        // is that the guard rails come off elsewhere, so this one stays on.
        val on = spec.breathingOn || LoosenWindow.isActive(ctx)
        if (!on) return false
        if (spec.breathEveryOpen) return true
        return prefs(ctx).getString(pkg.lowercase(), null) != today()
    }

    /** Record that [pkg] has used its pause for today. */
    fun markBreathed(ctx: Context, pkg: String) {
        prefs(ctx).edit().putString(pkg.lowercase(), today()).apply()
    }

    /** Give [pkg] its daily pause back (see the KNOWN GAP note above). */
    fun reset(ctx: Context, pkg: String) {
        prefs(ctx).edit().remove(pkg.lowercase()).apply()
    }

    fun resetAll(ctx: Context) = prefs(ctx).edit().clear().apply()

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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


// =====================================================================================
// HabitLog  (the shared log behind every Temptations category EXCEPT sexual arousal)
// =====================================================================================
/**
 * One flat log of "I rode an urge out" and "I slipped", tagged with the category id from
 * AppConfig.TEMPTATIONS. Deliberately thin: the arousal flow has its own rich logs
 * (TemptationLog / RelapseLog) with urge levels, feelings and locations - the other
 * categories get a count and a date, which is all their pages promise.
 */
object HabitLog {
    private const val PREFS = "habit_log"
    private const val KEY = "events"
    private const val MAX = 5000
    private const val SEP = "\u001F"

    const val RIDE = "ride"     // urge felt, and got through it
    const val SLIP = "slip"     // did it anyway

    data class Event(val ts: Long, val category: String, val kind: String)

    fun record(context: Context, category: String, kind: String) {
        val line = listOf(System.currentTimeMillis().toString(), category, kind)
            .joinToString(SEP) { it.replace(SEP, " ").replace("\n", " ") }
        val list = read(context).toMutableList()
        list.add(line)
        while (list.size > MAX) list.removeAt(0)
        prefs(context).edit().putString(KEY, list.joinToString("\n")).apply()
    }

    fun all(context: Context): List<Event> = read(context).mapNotNull { l ->
        val p = l.split(SEP)
        val ts = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        Event(ts, p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
    }

    fun count(context: Context, category: String, kind: String): Int =
        all(context).count { it.category == category && it.kind == kind }

    /** How many [kind] events in this category in the last [days] days. */
    fun recent(context: Context, category: String, kind: String, days: Int): Int {
        val since = System.currentTimeMillis() - days * 86_400_000L
        return all(context).count { it.category == category && it.kind == kind && it.ts >= since }
    }

    private fun read(c: Context) =
        prefs(c).getString(KEY, "").orEmpty().split("\n").filter { it.isNotEmpty() }
    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
    // Assumed value of reclaimed time, for the money projection. A bare number in the
    // user's OWN currency (see Units) - never converted, so it means 12 of whatever they use.
    const val VALUE_PER_HOUR = 12

    data class Snapshot(
        val hasData: Boolean,
        val trackedDays: Int, val cleanDays: Int, val slipDays: Int, val consistency: Int,
        val forgivingRun: Int, val bestClean: Int,
        val totalWins: Int, val reclaimedHours: Int,
        val projYearHours: Int, val projYearMoney: Int,
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
        val projYearMoney = projYearHours * VALUE_PER_HOUR

        val weeks = FloatArray(8)
        for (ts in winTs) {
            val w = ((today - dayIndex(ts)) / 7).toInt()
            if (w in 0..7) weeks[7 - w] += 1f
        }

        val best = maxOf(prefs(context).getInt(KEY_BEST, 0), cleanDays)
        prefs(context).edit().putInt(KEY_BEST, best).apply()

        val ms = mutableListOf<String>()
        if (totalWins >= 1) ms.add(context.getString(R.string.ms_first_urge))
        if (daysSinceFirst >= 7) ms.add(context.getString(R.string.ms_first_week))
        if (best >= 7) ms.add(context.getString(R.string.ms_clean_week))
        if (totalWins >= 25) ms.add(context.getString(R.string.ms_25_urges))
        if (best >= 14) ms.add(context.getString(R.string.ms_two_weeks))
        if (best >= 30) ms.add(context.getString(R.string.ms_clean_month))
        if (totalWins >= 100) ms.add(context.getString(R.string.ms_100_urges))

        val next = when {
            totalWins < 1 -> context.getString(R.string.next_first_urge)
            daysSinceFirst < 7 -> context.getString(R.string.next_first_week)
            best < 7 -> context.getString(R.string.next_7_clean)
            totalWins < 25 -> context.getString(R.string.next_25_urges, totalWins)
            best < 30 -> context.getString(R.string.next_clean_month, best)
            totalWins < 100 -> context.getString(R.string.next_100_urges, totalWins)
            else -> null
        }

        return Snapshot(true, trackedDays, cleanDays, slipDaysInWindow, consistency, run, best,
            totalWins, reclaimedHours, projYearHours, projYearMoney, weeks, ms, next)
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
    const val VALUE_PER_HOUR = 12
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


// =====================================================================================
// UsageGoal  (the user's own "phone time per day" target, drawn on the home graphs)
// =====================================================================================
object UsageGoal {
    private const val PREFS = "usage_goal"
    private const val KEY_MIN = "minutes_per_day"

    /** Target minutes of screen-on time per day; 0 = no goal set. */
    fun minutesPerDay(c: Context): Int = prefs(c).getInt(KEY_MIN, 0)
    fun setMinutesPerDay(c: Context, v: Int) =
        prefs(c).edit().putInt(KEY_MIN, v.coerceIn(0, 24 * 60)).apply()
    fun clear(c: Context) = prefs(c).edit().remove(KEY_MIN).apply()
    fun hoursPerDay(c: Context): Float? =
        minutesPerDay(c).takeIf { it > 0 }?.let { it / 60f }

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
