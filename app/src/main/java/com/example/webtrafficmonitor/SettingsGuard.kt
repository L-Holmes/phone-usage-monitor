package com.example.webtrafficmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView


// #####################################################################################
// #                                                                                   #
// #   SettingsGuard  —  CLOSING THE "TURN THE GUARD OFF BY SPAMMING TAPS" HOLE        #
// #                                                                                   #
// #####################################################################################
//
//  THE BUG THIS FILE EXISTS FOR (2026-09-09, reported off the user's own phone).
//
//  Super hardcore was bypassed in about four seconds, like this:
//
//    1. Open Settings -> Accessibility -> "Web Traffic Monitor - page monitoring".
//    2. We match the page and bounce them to the home screen. So far so good.
//    3. They tap the Settings icon in recents. Android restores Settings ON THE PAGE
//       THEY LEFT IT ON - the same toggle page.
//    4. Between the window appearing and our guard reading the screen there is a gap:
//       the guard has to fetch rootInActiveWindow and walk it (see the note on
//       pageMatches). On a busy phone that is a few hundred milliseconds.
//    5. They already know where the switch is. They tap that exact spot, repeatedly,
//       from the moment the window appears. One of those taps lands before we do.
//
//  Monitoring is then off, this service is dead, and nothing is watching anything.
//  The same trick works on "Appear on top" and on the device-admin page.
//
//  FIVE THINGS ANSWER IT, and they are deliberately layered - each one alone can be
//  raced, all five together cannot:
//
//   (1) DETECT WITHOUT READING THE SCREEN. The accessibility event for a window change
//       CARRIES THE WINDOW TITLE in event.text. "Appear on top" and "…page monitoring"
//       are both matched by title alone, so the common case now needs no tree walk, no
//       binder call, and no budget - it is a substring test on a string we were handed.
//       See PageMonitorAccessibilityService.quickGuardMatch.
//
//   (2) REMEMBER THE PAGE (StickyGuardPage). Settings reopens where it was left. Once
//       we have matched a guard page in Settings, the NEXT time Settings comes to the
//       front within a minute we bounce first and read afterwards. Being wrong costs a
//       bounce off a Settings page they are about to be locked out of anyway.
//
//   (3) DO NOT LET THEM TOUCH IT (ReentryGuard). Being sent home is not enough on its
//       own, because the race above is won by whoever taps first. So for a few seconds
//       after a bounce, reopening that app raises the ordinary block cover - which is
//       opaque AND consumes touches - over it. The taps land on our cover instead of on
//       the switch. Every re-entry inside the window makes the next hold longer, so
//       mashing it makes it worse rather than better.
//
//   (4) TAKE SETTINGS AWAY (SettingsLockout). Reaching for our own off-switch is not a
//       thing anyone does by accident. The first attempt costs an hour of Settings; the
//       second costs a day; after that, three days. Enforced by package name, so it
//       needs no screen read at all and cannot be raced.
//
//   (5) NOTICE IF THEY WIN ANYWAY (MonitorHealth + MonitorGuardService). If monitoring
//       does get switched off, the phone does not simply go quiet: a foreground service
//       takes over and covers everything that is not an essential until it is switched
//       back on. See the long note on MonitorGuardService for exactly what that can and
//       cannot do.
//
//  ⚠️ WHAT DELIBERATELY STAYS OPEN. ADB. Same reason as ever - see the note on
//  ESCAPE_ROUTE_PAGES in AppConfig. A person who has locked themselves out badly needs
//  a cable and a computer to get back, and nothing here closes that.


// =====================================================================================
//  SettingsLockout  —  the escalating ban from Settings
// =====================================================================================
/**
 * Touch our off-switch, lose Settings for a while. An hour, then a day, then three days.
 *
 * WHY A LADDER AND NOT A FIXED PENALTY. The first attempt is usually an urge testing the
 * handle, and an hour is enough for that to pass. The second attempt is a decision - the
 * person has already waited out an hour and come back for another go - and it deserves an
 * answer on a different scale. The third is someone working at it, and by then the honest
 * response is "not today".
 *
 * WHY IT DECAYS. [DECAY_MS] of nothing at all winds the level back to zero. A lockout that
 * only ever ratchets would punish somebody for a bad night three months ago, and a guard
 * that never forgives is one people work around rather than live with.
 *
 * WHEN IT STANDS DOWN, and it is the same rule the App-info bounce has always had (see
 * GrantWindow): a guard that would stop you turning something ON is not armed until it IS
 * on. While a permission we run on is missing, Settings is the only place to fix it, so
 * the lockout lifts until it has been fixed. That is not a hole - going that way costs the
 * user a phone on which nothing but the essentials opens (SetupGuard), which is a far
 * worse trade than sitting the hour out.
 */
object SettingsLockout {

    private const val PREFS = "settings_lockout"
    private const val KEY_UNTIL = "until"
    private const val KEY_LEVEL = "level"
    private const val KEY_STRIKE_AT = "last_strike_at"
    private const val KEY_REASON = "last_reason"
    private const val KEY_TOTAL = "total_strikes"

    /** An hour, a day, three days. The last rung repeats. */
    private val LADDER_MS = longArrayOf(
        60L * 60 * 1000,
        24L * 60 * 60 * 1000,
        72L * 60 * 60 * 1000,
    )

    /** Clean for this long and the ladder starts again from the bottom. */
    private const val DECAY_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * One visit is one strike. Sitting on a Settings page fires events continuously and a
     * naive strike() would walk the whole ladder in a second - the same problem, and the
     * same fix, as BypassWatch.DEDUPE_MS.
     */
    private const val DEDUPE_MS = 60_000L

    /** What earned it, in the user's own words. Shown back to them on the cover. */
    object Cause {
        const val MONITORING_PAGE = "you opened the page monitoring switch"
        const val OVERLAY_PAGE = "you opened the block screen's permission"
        const val ADMIN_PAGE = "you went to turn off the uninstall lock"
        const val TAPPED_SWITCH = "you went to press the switch"
        const val OVERLAY_REVOKED = "the block screen's permission was taken away"
        const val MONITORING_STOPPED = "page monitoring was switched off"
    }

    /**
     * Record an attempt and return the lockout now in force, in ms (0 if the strike was
     * swallowed as a repeat). Safe to call on every event.
     *
     * The new lockout never SHORTENS one already running - somebody striking again on
     * hour 23 of a day-long lockout must not have it replaced by a fresh hour.
     */
    @Synchronized
    fun strike(ctx: Context, cause: String): Long {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val lastAt = p.getLong(KEY_STRIKE_AT, 0L)
        if (now - lastAt < DEDUPE_MS) return remaining(ctx)

        // Decay before escalation, so a strike after a long clean stretch starts at the
        // bottom of the ladder rather than wherever it was left months ago.
        val level = if (lastAt > 0L && now - lastAt > DECAY_MS) 0 else p.getInt(KEY_LEVEL, 0)
        val duration = LADDER_MS[level.coerceIn(0, LADDER_MS.lastIndex)]
        val until = maxOf(p.getLong(KEY_UNTIL, 0L), now + duration)

        p.edit()
            .putLong(KEY_UNTIL, until)
            .putInt(KEY_LEVEL, (level + 1).coerceAtMost(LADDER_MS.size))
            .putLong(KEY_STRIKE_AT, now)
            .putString(KEY_REASON, cause)
            .putInt(KEY_TOTAL, p.getInt(KEY_TOTAL, 0) + 1)
            .apply()
        // The honest "look anyway" offer is meant to be on the table exactly when somebody
        // is reaching for the destructive option - which is what this is. See BypassWatch.
        BypassWatch.record(ctx, BypassWatch.Reason.ACCESSIBILITY)
        return until - now
    }

    fun remaining(ctx: Context): Long =
        (prefs(ctx).getLong(KEY_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Is a lockout on the clock? Says nothing about whether it is being ENFORCED. */
    fun isActive(ctx: Context): Boolean = remaining(ctx) > 0

    /**
     * Is Settings actually shut right now? [isActive] plus the stand-down rule.
     *
     * Cheap on purpose: this is asked on every accessibility event for a Settings package,
     * ahead of any screen read, because being un-raceable is the whole point of it.
     * SetupGuard.missingStep is cached at 2s and GrantWindow is two permission checks.
     */
    fun enforcedNow(ctx: Context): Boolean {
        if (!isActive(ctx)) return false
        // OFF means "nothing is monitored and nothing is covered", and a cover over Settings
        // would be the loudest possible contradiction of that. The mode cannot be reached
        // from Strict anyway (the ratchet), so this is a belt, not a door.
        if (Mode.isOff(ctx)) return false
        if (SetupGuard.missingStep(ctx) != null) return false   // Settings is the way to fix it
        if (GrantWindow.isOpen(ctx)) return false               // ...and so is App info
        return true
    }

    /** How far up the ladder we are: 0 = clean, 1 = an hour has been served, and so on. */
    fun level(ctx: Context): Int = prefs(ctx).getInt(KEY_LEVEL, 0)

    fun totalStrikes(ctx: Context): Int = prefs(ctx).getInt(KEY_TOTAL, 0)

    fun lastCause(ctx: Context): String? = prefs(ctx).getString(KEY_REASON, null)

    /** What the next strike would cost, for the warning shown BEFORE it is earned. */
    fun nextPenaltyMs(ctx: Context): Long = LADDER_MS[level(ctx).coerceIn(0, LADDER_MS.lastIndex)]

    /** The cover's text while Settings is shut. */
    fun coverText(ctx: Context): String =
        ctx.getString(
            R.string.br_settings_locked,
            lastCause(ctx) ?: Cause.MONITORING_PAGE,
            Units.compactDuration(ctx, remaining(ctx)),
        )

    /** Dev console only. There is deliberately no user-facing way to call this. */
    fun clear(ctx: Context) {
        if (!BuildConfig.IS_TESTING) return
        prefs(ctx).edit().clear().apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


// =====================================================================================
//  ReentryGuard  —  a few seconds of "you may look, you may not touch"
// =====================================================================================
/**
 * After we throw somebody out of an app, reopening it inside [ARM_MS] puts the block cover
 * up for a moment before anything underneath can be touched.
 *
 * WHY THIS IS THE PIECE THAT ACTUALLY FIXES THE BUG. Every other layer is a race we can
 * lose: they tap, we read, whoever is first wins. This one does not race at all. The cover
 * is a full-screen opaque window that CONSUMES TOUCHES (see OverlayController - it sets
 * FLAG_NOT_FOCUSABLE and nothing else, so touches stop at it), and it goes up off the
 * FIRST window event, from an in-memory map, with no screen read in the way. A tap aimed at
 * a switch underneath lands on the cover instead.
 *
 * WHY THE HOLD GROWS. Somebody who has worked out that there is a delay will simply wait it
 * out and try again, so re-entering while the guard is armed makes the next hold longer -
 * [ESCALATE]x each time, up to [MAX_HOLD_MS]. Spamming the app switcher is then strictly
 * worse than not spamming it, which is the behaviour we want to teach.
 *
 * DELIBERATELY IN MEMORY, NOT PREFS. It measures seconds, and the only thing that clears it
 * is this process dying - which is not something a user can arrange on demand mid-bypass,
 * and which the SettingsLockout (which IS persisted) covers anyway.
 */
object ReentryGuard {

    /** How long after a bounce a reopen still counts as a re-entry. */
    private const val ARM_MS = 45_000L

    /** The first hold. Long enough to outlast a burst of taps, short enough not to be a wall. */
    private const val BASE_HOLD_MS = 4_000L
    private const val ESCALATE = 1.75f
    private const val MAX_HOLD_MS = 20_000L

    /**
     * A hold restarted less than this after the last one is the SAME visit, not a new one.
     *
     * ⚠️ WITHOUT THIS THE ESCALATION RUNS AWAY. A Settings page in front fires content-change
     * events several times a second, every one of them routes through the eject path, and
     * every one of those would count as another re-entry - so the first visit alone would
     * walk the hold to MAX_HOLD_MS in under a second. A real re-entry costs a trip to the
     * home screen and a tap, which is far longer than this.
     */
    private const val RESTART_GAP_MS = 1_500L

    private class State(
        var armedUntil: Long,
        var nextHold: Long,
        var holdUntil: Long,
        var lastStart: Long,
    )

    private val states = HashMap<String, State>()

    /**
     * Arm the guard for [pkg]: we have just ejected the user from it. Re-arming an already
     * armed package extends the window but KEEPS the escalated hold - otherwise a second
     * bounce would reset the penalty the first one earned.
     */
    @Synchronized
    fun arm(pkg: String?) {
        if (pkg == null) return
        val now = android.os.SystemClock.uptimeMillis()
        val s = states.getOrPut(pkg) { State(0L, BASE_HOLD_MS, 0L, 0L) }
        if (now > s.armedUntil) s.nextHold = BASE_HOLD_MS      // lapsed: start again from the base
        s.armedUntil = now + ARM_MS
    }

    /**
     * [pkg] has just come to the front. If the guard is armed for it, start (or restart) a
     * hold and return true. Called from the window-state branch only - a hold that restarted
     * on every content change would never end.
     */
    @Synchronized
    fun onForeground(pkg: String?): Boolean {
        if (pkg == null) return false
        val s = states[pkg] ?: return false
        val now = android.os.SystemClock.uptimeMillis()
        if (now > s.armedUntil) { states.remove(pkg); return false }
        s.armedUntil = now + ARM_MS                            // coming back re-arms it
        // Still the same visit: leave the running hold (and the penalty) exactly as they are.
        if (now - s.lastStart < RESTART_GAP_MS) return true
        s.lastStart = now
        s.holdUntil = now + s.nextHold
        s.nextHold = (s.nextHold * ESCALATE).toLong().coerceAtMost(MAX_HOLD_MS)
        return true
    }

    /** ms left on [pkg]'s hold, or 0. */
    @Synchronized
    fun holdRemaining(pkg: String?): Long {
        if (pkg == null) return 0L
        val s = states[pkg] ?: return 0L
        return (s.holdUntil - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)
    }

    /**
     * The cover's reason while a hold is running, or null. A COUNTDOWN, not a flat message:
     * the recheck loop re-asks every 400ms and re-shows the cover with whatever comes back,
     * so the seconds tick down for free - and a wall that visibly ends is one people wait
     * out instead of fighting.
     */
    fun reason(ctx: Context, pkg: String?): String? {
        val left = holdRemaining(pkg)
        if (left <= 0L) return null
        return ctx.getString(R.string.br_reentry_hold, Units.secs(ctx, (left + 999) / 1000))
    }

    /** Forget everything (service teardown / dev console). */
    @Synchronized
    fun reset() = states.clear()
}


// =====================================================================================
//  StickyGuardPage  —  Settings reopens on the page you left it on
// =====================================================================================
/**
 * Remembers that a guard page was last seen in a given package, so the next visit can be
 * bounced BEFORE the screen is read rather than after.
 *
 * This is a guess, and it is allowed to be, because of what being wrong costs: one bounce
 * out of a Settings app the user is about to lose access to anyway (SettingsLockout strikes
 * on the same event). Being right costs them the bypass.
 *
 * In memory for the same reason as ReentryGuard: it measures a minute, not a policy.
 */
object StickyGuardPage {

    /** How long a remembered page stays believable. Settings keeps its back stack far longer. */
    private const val STICKY_MS = 60_000L

    private var pkg: String? = null
    private var label: String? = null
    private var at = 0L

    @Synchronized
    fun remember(inPkg: String, pageLabel: String) {
        pkg = inPkg
        label = pageLabel
        at = android.os.SystemClock.uptimeMillis()
    }

    /** The page [inPkg] was last left on, if it is recent enough to act on. */
    @Synchronized
    fun match(inPkg: String): String? {
        if (inPkg != pkg) return null
        if (android.os.SystemClock.uptimeMillis() - at > STICKY_MS) { pkg = null; return null }
        return label
    }

    @Synchronized
    fun forget() { pkg = null; label = null; at = 0L }
}


// =====================================================================================
//  MonitorHealth  —  is the guard actually running, and was it switched off?
// =====================================================================================
/**
 * The two permissions the whole app stands on, asked from anywhere (the activity, the
 * fallback service, the accessibility service itself).
 *
 * [noteMonitoringOff] is called from the accessibility service's own onUnbind - the last
 * thing it gets to do. That is the only moment we can tell "the user switched it off" apart
 * from "the process is being replaced by an update", because at unbind time the Secure
 * setting has already been rewritten.
 */
object MonitorHealth {

    private const val PREFS = "monitor_health"
    private const val KEY_OFF_AT = "off_at"
    private const val KEY_OFF_COUNT = "off_count"
    private const val KEY_OVERLAY_SEEN = "overlay_seen"

    /** Is our accessibility service listed as enabled? */
    fun monitoringOn(ctx: Context): Boolean {
        val expected =
            ComponentName(ctx, PageMonitorAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun overlayOn(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /**
     * Called from the service's onUnbind. Records the switch-off and strikes, so that when
     * monitoring comes back on the person finds Settings shut for an hour.
     */
    fun noteMonitoringOff(ctx: Context) {
        val p = prefs(ctx)
        p.edit()
            .putLong(KEY_OFF_AT, System.currentTimeMillis())
            .putInt(KEY_OFF_COUNT, p.getInt(KEY_OFF_COUNT, 0) + 1)
            .apply()
        SettingsLockout.strike(ctx, SettingsLockout.Cause.MONITORING_STOPPED)
    }

    /** When monitoring was last switched off, or 0. */
    fun lastOffAt(ctx: Context): Long = prefs(ctx).getLong(KEY_OFF_AT, 0L)

    fun offCount(ctx: Context): Int = prefs(ctx).getInt(KEY_OFF_COUNT, 0)

    /**
     * Watch the overlay permission for a revocation, which is the OTHER toggle this whole
     * file is about and the one we can see directly. Returns true the moment it goes from
     * granted to not, and strikes on the way past.
     *
     * The "seen" flag is what makes it a REVOCATION rather than "it has never been granted":
     * a fresh install has not lost anything.
     */
    fun checkOverlayRevoked(ctx: Context): Boolean {
        val p = prefs(ctx)
        val now = overlayOn(ctx)
        val seen = p.getBoolean(KEY_OVERLAY_SEEN, false)
        if (now) {
            if (!seen) p.edit().putBoolean(KEY_OVERLAY_SEEN, true).apply()
            return false
        }
        if (!seen) return false
        p.edit().putBoolean(KEY_OVERLAY_SEEN, false).apply()
        SettingsLockout.strike(ctx, SettingsLockout.Cause.OVERLAY_REVOKED)
        return true
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


// #####################################################################################
// #                                                                                   #
// #   MonitorGuardService  —  WHAT HAPPENS WHEN THEY WIN THE RACE ANYWAY              #
// #                                                                                   #
// #####################################################################################
//
//  Switching page monitoring off kills PageMonitorAccessibilityService outright. Nothing
//  in that file can run afterwards, so "block everything while monitoring is off" cannot
//  live there. It lives here, in an ordinary foreground service, which the system keeps
//  alive and which survives a reboot (GuardBootReceiver).
//
//  WHAT IT NEEDS, AND WHAT IT DOES WITHOUT EACH:
//
//   • USAGE ACCESS (PACKAGE_USAGE_STATS). The only way left to know what app is in front.
//     Without it we cannot tell an essential from anything else, so we do not guess: the
//     service still runs and still nags, but it covers nothing. The dev console and the
//     home page both say so, and the setup flow asks for it.
//
//   • APPEAR ON TOP (SYSTEM_ALERT_WINDOW). Without the accessibility service there is no
//     TYPE_ACCESSIBILITY_OVERLAY available to us, so the cover here is an ordinary
//     TYPE_APPLICATION_OVERLAY and it needs the real permission. Without it, all that is
//     left is a permanent high-priority notification.
//
//  ⚠️ THIS IS A NET, NOT A WALL, AND THE DIFFERENCE MATTERS. Polling for the foreground
//  app is inherently a beat behind: the cover lands a fraction of a second after the app
//  does, and there is no equivalent of an accessibility event to make it instant. It is
//  meant to make a phone with monitoring off unpleasant enough that the switch goes back
//  on, not to be a second blocking engine. The real answer to a bypass is the four layers
//  above this one, which stop it happening.
class MonitorGuardService : Service() {

    private var thread: HandlerThread? = null
    private var poll: Handler? = null
    private val ui = Handler(Looper.getMainLooper())

    private var cover: View? = null
    private var lastForeground: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(NOTIFICATION_ID, buildNotification())
        thread = HandlerThread("monitor-guard").also { it.start() }
        poll = Handler(thread!!.looper).also { it.post(tick) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // STICKY: if the system kills us for memory, we want to come back. This service is
        // the only thing watching the phone while monitoring is off.
        return START_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            runCatching { step() }
                .onFailure { android.util.Log.e(TAG, "guard tick failed", it) }
            poll?.postDelayed(this, POLL_MS)
        }
    }

    private fun step() {
        // The job is done the moment monitoring is back: the accessibility service is a
        // strictly better version of this, and two covers fighting over one screen is worse
        // than either alone.
        if (MonitorHealth.monitoringOn(this)) {
            ui.post { hideCover() }
            stopSelf()
            return
        }
        if (!MonitorFallback.wanted(this)) {
            ui.post { hideCover() }
            stopSelf()
            return
        }
        val front = foregroundPackage()
        lastForeground = front
        // No usage access: we cannot tell what is in front, and covering on a guess would
        // cover the Settings page they need to fix this. Nag only.
        if (front == null) { ui.post { hideCover() }; return }
        if (MonitorFallback.isAllowed(this, front)) ui.post { hideCover() }
        else ui.post { showCover() }
    }

    /**
     * The app in front, via usage events. queryEvents over a short window and take the last
     * MOVE_TO_FOREGROUND - queryUsageStats is coarser and lags by minutes.
     *
     * Returns null when we have no usage access, which the caller reads as "do nothing".
     */
    private fun foregroundPackage(): String? {
        if (!MonitorFallback.hasUsageAccess(this)) return null
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - LOOKBACK_MS, now) }.getOrNull() ?: return null
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
        }
        // Nothing moved to the foreground in the window: whatever we saw last is still there.
        return last ?: lastForeground
    }

    // ── The cover ────────────────────────────────────────────────────────────────────
    private fun showCover() {
        if (cover != null) return
        if (!Settings.canDrawOverlays(this)) return          // nothing we can do; the nag stands
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_block, null)
        view.findViewById<TextView>(R.id.block_reason).text = getString(R.string.br_monitoring_off)
        view.findViewById<View>(R.id.block_details).visibility = View.GONE
        view.findViewById<View>(R.id.btn_go_back).visibility = View.GONE
        view.findViewById<View>(R.id.btn_report).visibility = View.GONE
        // The ONE button, and it is the way out of this state rather than a way past it:
        // straight to the page where monitoring is switched back on.
        view.findViewById<TextView>(R.id.btn_leave).apply {
            text = context.getString(R.string.monitor_off_fix)
            setOnClickListener { openAccessibilitySettings() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // NOT_FOCUSABLE, exactly like the accessibility cover (see OverlayController):
            // touches still stop at this window - which is the whole job - while keyboard
            // focus and Back stay with the app underneath. A focusable full-screen overlay
            // owned by a background service takes the IME with it, and there is no activity
            // here to hand it back.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        )
        runCatching { wm.addView(view, params); cover = view }
            .onFailure { android.util.Log.e(TAG, "fallback cover would not attach", it) }
    }

    private fun hideCover() {
        val v = cover ?: return
        cover = null
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeViewImmediate(v) }
    }

    private fun openAccessibilitySettings() {
        val cn = ComponentName(this, PageMonitorAccessibilityService::class.java).flattenToString()
        val deep = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra("android.provider.extra.ACCESSIBILITY_DETAILS_SETTINGS", cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Take the cover down FIRST - it is focusable, and leaving it over the page we just
        // sent them to is exactly the lockout this service exists to avoid causing.
        hideCover()
        if (Build.VERSION.SDK_INT >= 30 && runCatching { startActivity(deep) }.isSuccess) return
        runCatching { startActivity(list) }
    }

    // ── The notification ─────────────────────────────────────────────────────────────
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.monitor_off_channel),
                    NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(true)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.monitor_off_title))
            .setContentText(getString(R.string.monitor_off_body))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        hideCover()
        poll?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        poll = null
        super.onDestroy()
    }

    /**
     * Android 14 refuses a startForeground with no type; Android 13 and below refuse the
     * three-argument form's constant. Named apart from the framework method on purpose -
     * an override of Service.startForeground would be called by the framework too.
     */
    private fun goForeground(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, n)
        }
    }

    companion object {
        private const val TAG = "MonitorGuard"
        private const val CHANNEL = "monitor_off"
        private const val NOTIFICATION_ID = 4711
        /** Fast enough to land within an app-open animation, slow enough to be free. */
        private const val POLL_MS = 700L
        private const val LOOKBACK_MS = 10_000L
    }
}


/**
 * Starting, stopping and gating [MonitorGuardService]. Kept apart from the service so the
 * activity can ask the questions without touching the service class.
 */
object MonitorFallback {

    /**
     * Should the fallback be running? Only when monitoring is off AND the user has asked to
     * be held to something. In Relaxed and Off, monitoring being off is a choice, not a
     * bypass, and covering their phone over it would be indefensible.
     *
     * everStrict rather than the current mode: dropping to Relaxed is not available from
     * Strict+ without going through the app, so "was ever strict and monitoring is now off"
     * is exactly the shape of the bypass.
     */
    fun wanted(ctx: Context): Boolean {
        if (MonitorHealth.monitoringOn(ctx)) return false
        return Mode.everStrict(ctx) && !Mode.isOff(ctx)
    }

    /** Bring the service into line with [wanted]. Cheap; call it from anywhere, often. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        val intent = Intent(app, MonitorGuardService::class.java)
        if (wanted(app)) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    app.startForegroundService(intent)
                else
                    app.startService(intent)
            }.onFailure { android.util.Log.w("MonitorGuard", "could not start fallback", it) }
        } else {
            runCatching { app.stopService(intent) }
        }
    }

    /**
     * Open while the fallback cover is up. The lockdown essentials (calls, texts, clock,
     * maps), Settings - which is where this gets fixed - and our own app.
     *
     * Note that Settings is ALLOWED here and locked in the accessibility service. That is
     * not a contradiction: with monitoring off, Settings is the only route back, and there
     * is no guard left to protect anyway.
     */
    fun isAllowed(ctx: Context, pkg: String?): Boolean {
        if (pkg == null) return true
        if (pkg == ctx.packageName) return true
        if (pkg in AppConfig.IGNORED_PACKAGES) return true
        val p = pkg.lowercase()
        if (AppConfig.SETTINGS_ONLY_PACKAGES.any { p.contains(it) }) return true
        if (p.contains("permissioncontroller") || p.contains("packageinstaller")) return true
        return AppConfig.LOCKDOWN_ALLOWED_SUBSTRINGS.any { p.contains(it) }
    }

    /** Has the user granted usage access? Without it the fallback can only nag. */
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(
                    "android:get_usage_stats", android.os.Process.myUid(), ctx.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    "android:get_usage_stats", android.os.Process.myUid(), ctx.packageName)
            }
        }.getOrDefault(android.app.AppOpsManager.MODE_ERRORED)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /** The system page where usage access is granted. */
    fun usageAccessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}


/**
 * A reboot is a coverage gap the accessibility service cannot close for itself: if
 * monitoring was switched off before the restart, nothing of ours runs at boot. This does.
 */
class GuardBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { MonitorFallback.sync(ctx) }
    }
}
