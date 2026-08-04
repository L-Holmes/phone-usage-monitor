package com.example.webtrafficmonitor

import android.content.Context

// =====================================================================================
// CheckingGuard  (the Phone Checking temptation's friction measures)
// =====================================================================================
/**
 * Phone checking has nothing to BLOCK - the pull is the device itself. So its switch adds
 * FRICTION instead, driven by the two things the accessibility service can already see:
 * unlocks (the USER_PRESENT broadcast) and taps (the same TYPE_VIEW_CLICKED stream the
 * dopamine counters use).
 *
 * Two levels, chosen on the Phone Checking page:
 *  - RELAXED (the default): never blocks anything. When the tap rate passes CLICKS_PER_MIN
 *    it shows a plain "high volume of clicks" toast, at most once per POPUP_COOLDOWN_MS.
 *  - HARDCORE: real friction, as sub-toggles (all ON by default when the master goes on):
 *      * unlock delay - more than UNLOCKS_PER_HOUR unlocks in a rolling hour and each
 *        further unlock starts an UNLOCK_PAUSE_SECONDS cover;
 *      * tap limiter  - a tap rate past CLICKS_PER_MIN starts a shorter pause.
 *
 * A "pause" is just a timestamp here. The service's appBlockReason() reads pauseReason()
 * and the ordinary cover + recheck loop does the showing and the dropping - so essentials
 * (dialer, SMS, clock...) stay usable through a pause, exactly like the night guard.
 *
 * Counters are in-memory only, which is fine: they live in the accessibility service's
 * process, which runs all day, and losing them on a restart just means one lenient hour.
 */
object CheckingGuard {

    // ── Tuning ──────────────────────────────────────────────────────────────────────
    const val UNLOCKS_PER_HOUR = 8          // unlocks in a rolling hour before pausing
    const val UNLOCK_PAUSE_SECONDS = 20
    const val CLICKS_PER_MIN = 40           // taps in a rolling minute = "rapid-fire"
    const val CLICK_PAUSE_SECONDS = 10
    private const val POPUP_COOLDOWN_MS = 3 * 60_000L   // relaxed toast at most this often
    private const val RETRIGGER_GAP_MS = 60_000L        // grace after a pause ends

    // ── Settings ────────────────────────────────────────────────────────────────────
    private const val PREFS = "checking_guard"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HARDCORE = "hardcore"
    private const val KEY_UNLOCK_PAUSE = "unlock_pause"
    private const val KEY_CLICK_PAUSE = "click_pause"

    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
    fun hardcore(c: Context) = prefs(c).getBoolean(KEY_HARDCORE, false)
    fun setHardcore(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_HARDCORE, on).apply()
    // The hardcore sub-toggles default ON: ticking the master is opting into the lot,
    // and the boxes are there to opt back out of one.
    fun unlockPauseOn(c: Context) = prefs(c).getBoolean(KEY_UNLOCK_PAUSE, true)
    fun setUnlockPauseOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_UNLOCK_PAUSE, on).apply()
    fun clickPauseOn(c: Context) = prefs(c).getBoolean(KEY_CLICK_PAUSE, true)
    fun setClickPauseOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_CLICK_PAUSE, on).apply()

    // ── Runtime state ───────────────────────────────────────────────────────────────
    private val unlockTimes = ArrayDeque<Long>()
    private val tapTimes = ArrayDeque<Long>()
    @Volatile private var pauseUntil = 0L
    @Volatile private var pauseText: String? = null
    private var lastPopupAt = 0L

    /**
     * Called on every unlock. Returns true when a pause just started, so the service can
     * arm the cover straight away instead of waiting for the next window event.
     */
    @Synchronized
    fun recordUnlock(c: Context): Boolean {
        val now = System.currentTimeMillis()
        prune(unlockTimes, now, 3600_000L)
        unlockTimes.addLast(now)
        if (!enabled(c) || !hardcore(c) || !unlockPauseOn(c)) return false
        if (unlockTimes.size <= UNLOCKS_PER_HOUR) return false
        startPause(c.getString(R.string.br_checking_unlocks, unlockTimes.size), UNLOCK_PAUSE_SECONDS)
        return true
    }

    /**
     * Called on every tap outside our own app. Returns the relaxed-mode popup text when one
     * is due (the caller toasts it), null otherwise; in hardcore it may start a pause
     * instead - check pauseReason() after calling.
     */
    @Synchronized
    fun recordTap(c: Context): String? {
        if (!enabled(c)) return null
        val now = System.currentTimeMillis()
        prune(tapTimes, now, 60_000L)
        tapTimes.addLast(now)
        if (tapTimes.size < CLICKS_PER_MIN) return null
        if (hardcore(c)) {
            if (!clickPauseOn(c)) return null
            // Not straight back off the same burst: wait out the pause plus a grace gap.
            if (now < pauseUntil + RETRIGGER_GAP_MS) return null
            startPause(c.getString(R.string.br_checking_clicks, tapTimes.size), CLICK_PAUSE_SECONDS)
            return null
        }
        if (now - lastPopupAt < POPUP_COOLDOWN_MS) return null
        lastPopupAt = now
        return c.getString(R.string.checking_popup, tapTimes.size)
    }

    /** The cover reason while a pause is running, else null. Read by appBlockReason(). */
    fun pauseReason(): String? =
        if (System.currentTimeMillis() < pauseUntil) pauseText else null

    private fun startPause(text: String, seconds: Int) {
        pauseText = text
        pauseUntil = System.currentTimeMillis() + seconds * 1000L
    }

    private fun prune(q: ArrayDeque<Long>, now: Long, windowMs: Long) {
        while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
    }

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
