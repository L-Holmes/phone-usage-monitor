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
import android.os.SystemClock
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
// MONITOR
// =====================================================================================


// --------------------------------------------------------------
// PageMonitorAccessibilityService
// --------------------------------------------------------------


/**
 * Reads what is on screen: the foreground app, the website domain (from the
 * address bar), a rough page title, and a sample of the visible text. It also
 * decides whether to block.
 *
 * Key design points:
 *  - The domain comes only from the browser address bar, read while it is NOT
 *    being edited. This avoids treating autocomplete suggestions or embedded
 *    resources (which merely appear somewhere on screen) as the current page.
 *  - The current page's domain is remembered until the app changes or a new
 *    address-bar value is read, so the block does not flicker when the toolbar
 *    scrolls out of view.
 *  - Blocking is re-checked on every (throttled) event, so a block stays up the
 *    whole time the page is shown.
 *  - It is event-driven and throttled, so it stays cheap.
 */
class PageMonitorAccessibilityService : AccessibilityService() {

    private var overlay: OverlayController? = null
    private val keyguard by lazy { getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager }

    private var lastProcessedAt = 0L      // gates LOGGING (cheap to be slow)
    private var lastBlockEvalAt = 0L      // gates BLOCKING (must be quick)
    private var lastLogSignature: String? = null
    private var lastGoBackAt = 0L
    // Set when the user taps "Go back", cleared by the next evaluation. It is the ONLY thing
    // that lets the "still the same blocked page" status line appear - so the line is always
    // an answer to a tap, never unprompted commentary.
    private var awaitingBackResult = false
    // True from the moment "Go to home screen" is tapped until we're actually gone. Blocks any
    // re-evaluation from tearing the cover down early and exposing the page.
    private var leaving = false
    // The host the current page-block cover is showing for (drives the
    // "still blocked / different page" status lines and dismiss escalation).
    private var shownBlockHost: String? = null
    private var shownBlockUrl: String? = null
    // What the page under the current cover scored, and the words that carried it. Kept so
    // that when the user's own tap turns this page into a RULE (escalateWebBlock), the rule
    // can record why it exists - which is what the next cover for it reads back.
    private var shownBlockScore: Int? = null
    private var shownBlockWords: List<String> = emptyList()
    private var armedAt = 0L   // when the current blocked page first armed; used to "settle" before banning
    // The app whose word-detection case RepeatGate confirmed, and the exact sentence that
    // cover went up with, kept for as long as that block stands (see repeatGateReason).
    private var gateBlockPkg: String? = null
    private var gateBlockText: String? = null

    private var lastPackage: String? = null
    private var lastHost: String? = null
    // When lastHost was last actually READ off a bar, and the app it was read from. The
    // timestamp bounds the fallback below (a host nobody has seen for a while is not
    // evidence about the page in front of you); the package is what tells the "leave"
    // button which browser is still parked on the blocked page.
    private var lastHostAt = 0L
    private var lastHostPkg: String? = null
    private var lastUrl: String? = null
    private var lastFullUrl: String? = null

    // App-level block state. While true, the cover is OWNED by the recheck loop
    // below: it is kept up / taken down based on what is actually in the
    // foreground, never by individual events (events flicker; window state doesn't).
    private var appBlockActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  THE BACKGROUND THREAD  -  and why the broadcasts moved onto it
    // ═══════════════════════════════════════════════════════════════════════════════════
    //  ⚠️ 2026-08-27. THIS IS THE THING THAT WAS KILLING THE APP, and it is worth being
    //  precise about the difference between the two halves of that.
    //
    //  A slow main thread is a performance problem. A slow main thread WITH A BROADCAST
    //  WAITING ON IT is a correctness problem, because the system does not merely complain:
    //  a broadcast receiver that does not return inside its timeout is an ANR, and an ANR in
    //  a background process gets the PROCESS KILLED.
    //
    //  All three ANR traces pulled off the phone say the same thing. Subject: "Broadcast of
    //  Intent { act=android.intent.action.SCREEN_OFF ... }". The receiver never ran - it was
    //  still queued behind an accessibility event that was parked in a five-second
    //  cross-process read (see the note on pageMatches). Screen goes off, broadcast times
    //  out, process is killed. And killing this process takes the accessibility service with
    //  it - which is why blocking silently stopped - and leaves the launcher unable to start
    //  MainActivity, which is the "splash screen that never loads".
    //
    //  So our receivers no longer run on the main thread at all. They are registered against
    //  this HandlerThread, they return in microseconds, and anything that genuinely needs the
    //  main thread is posted there to happen whenever it is free. A busy main thread can now
    //  make us LATE. It can no longer make us DEAD.
    private var bgThread: android.os.HandlerThread? = null
    private var bgHandler: Handler? = null

    private fun startBackgroundThread() {
        if (bgHandler != null) return
        val t = android.os.HandlerThread("wtm-bg", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        bgThread = t
        bgHandler = Handler(t.looper)
    }

    /** Register a receiver so it is delivered OFF the main thread. See the note above. */
    private fun registerOffMainThread(
        receiver: android.content.BroadcastReceiver,
        filter: android.content.IntentFilter,
    ) {
        runCatching { registerReceiver(receiver, filter, null, bgHandler) }
    }

    // ── THE STALL WATCHDOG ──────────────────────────────────────────────────────────
    // The main thread cannot notice that it is stuck; that is what being stuck means. So a
    // beat is posted to it from the background thread and the background thread checks
    // whether it came back. A long stall is recorded rather than guessed at - the whole
    // reason this bug survived so long is that from inside the app it looked like nothing
    // at all was happening.
    @Volatile private var lastBeatAt = 0L
    @Volatile private var beatPending = false

    private val heartbeat = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (beatPending) {
                val stalled = now - lastBeatAt
                if (stalled > STALL_WARN_MS) {
                    android.util.Log.e("PageMonitor", "MAIN THREAD STALLED ${stalled}ms")
                    // A cover whose buttons cannot be delivered is a locked phone. Ask for it
                    // to come down; the post lands the moment the main thread is free again,
                    // which is the earliest anything could have removed it anyway (a view can
                    // only be removed by the thread that added it).
                    if (stalled > STALL_PANIC_MS) mainHandler.post { panicDropCover() }
                }
            } else {
                lastBeatAt = now
                beatPending = true
                mainHandler.post { beatPending = false }
            }
            bgHandler?.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /**
     * Take the cover down because the service stopped being able to think, not because the
     * block ended. Only ever reached after a stall long enough that the cover's own buttons
     * were undeliverable - at which point the cover is not blocking anything, it is just a
     * wall the user cannot get past. The next healthy evaluation puts it straight back if
     * the block still stands.
     */
    private fun panicDropCover() {
        val controller = overlay ?: return
        if (!controller.isShowing) return
        android.util.Log.e("PageMonitor", "dropping cover after a main-thread stall")
        appBlockActive = false
        shownBlockHost = null
        shownBlockUrl = null
        controller.hide()
        mainHandler.removeCallbacks(recheck)
        mainHandler.postDelayed(recheck, RECHECK_MS)
    }

    private var keyboardPackages: Set<String> = emptySet()

    private var lastDumpAt = 0L

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  THE NODE BUDGET  -  why the phone used to freeze under a block screen
    // ═══════════════════════════════════════════════════════════════════════════════════
    //  ⚠️ 2026-08-27. THE PLAY STORE BUG. Reported as: the block screen appears, "Go to home
    //  screen" does nothing at all, the app then stops monitoring, and five minutes later a
    //  block screen lands on some completely unrelated app.
    //
    //  Every one of those symptoms is ONE cause. Reading a screen means walking the
    //  accessibility node tree, and every getChild() is a separate call into the other app's
    //  process. One event used to walk that tree up to SIX times over - the address bar, the
    //  WebView test, the in-app host, the focused URL, the page text, the screen sample -
    //  with a depth limit but NO limit on how many nodes that came to. On an ordinary app
    //  that is a few hundred nodes and nobody notices. The Play Store is a deep, lazily-built
    //  Compose hierarchy that fires content-change events continuously while it loads, and
    //  there it is thousands of cross-process calls per event, several times a second.
    //
    //  All of it runs on the service's MAIN thread - the same thread that delivers taps to
    //  the cover. So:
    //    * the cover's buttons "do nothing": the tap is queued behind seconds of tree walking;
    //    * monitoring appears to stop: events are being delivered faster than they finish;
    //    * a block lands minutes later on the wrong app: the backlog is finally processed,
    //      and a decision made about a screen the user left long ago is applied to whatever
    //      happens to be in front now.
    //
    //  Two limits fix it, and both have to stay. This one bounds the COST of a single pass:
    //  the walks are grouped into three phases (see beginWalkBudget) and each phase gets one
    //  allowance, so six unbounded walks of one screen become three bounded ones. The
    //  wall-clock deadline inside an allowance is the real protection - a node count says
    //  nothing at all about how slow the app on the other end of the binder is being.
    //
    //  Running out is not a failure. It truncates what we read from a screen we have already
    //  read the top of, and the very next event starts again with a full allowance.
    private var walkNodes = 0
    private var walkUntil = 0L
    private var walkTruncated = false

    // ── THE PASS ────────────────────────────────────────────────────────────────────
    // One accessibility event = one pass. The id is bumped at the top of each, and it is
    // what lets a read be cached for exactly as long as it is still about the same screen.
    //
    // ⚠️ AND `rootInActiveWindow` IS NOT A FIELD, IT IS A BLOCKING CALL. It reads like a
    // property, which is how this file ended up calling it six or seven times per event -
    // once in the guard scan, once for the text sample, once inside hasWebView, once inside
    // isTypingInField, and so on. Each one is a round trip to the app on screen with a FIVE
    // SECOND timeout, and on this Samsung build a Knox ServiceManager lookup on top. Fetch
    // it once per pass and hand the same node round.
    private var passId = 0L
    private var passRootId = -1L
    private var passRoot: AccessibilityNodeInfo? = null

    private fun beginPass() {
        passId++
        passRootId = -1L
        passRoot = null
        // Every pass starts with an allowance. Without this a pass that ran out would leave
        // walkNodes at zero, and passRoot() - which refuses to start a blocking read once the
        // budget is gone - would hand back null for the whole of the NEXT event too.
        beginWalkBudget()
    }

    /** The active window's root for THIS pass: fetched at most once, never after the deadline. */
    private fun passRoot(): AccessibilityNodeInfo? {
        if (passRootId == passId) return passRoot
        passRootId = passId
        // Past the deadline we do not start another blocking call - see canWalk(). Asking
        // through canWalk() rather than testing walkNodes directly is deliberate: fetching a
        // root IS one of these calls, and it should be charged for like any other.
        passRoot = if (!canWalk()) null else runCatching { rootInActiveWindow }.getOrNull()
        return passRoot
    }

    /**
     * Start a fresh allowance. Called between PHASES of an evaluation, never inside a walk.
     *
     * There are three phases and they get separate allowances on purpose. One shared
     * allowance sounds tidier and is quietly dangerous: the chrome reads (address bar,
     * WebView test, in-app host) run FIRST, so on a big enough tree they would spend the
     * lot and the page-text read - the one thing the whole content filter is built on -
     * would come back empty. A screen that reads as empty scores zero, and scoring zero is
     * indistinguishable from being clean. Blocking would fail open on exactly the heavy,
     * busy apps that need it most, and it would fail silently.
     *
     * So each phase is guaranteed its own slice. The worst case is the sum of them, which
     * is still a fraction of what this used to cost when it was unbounded.
     */
    private fun beginWalkBudget(nodes: Int = WALK_NODES_CHROME, ms: Long = WALK_MS_CHROME) {
        walkNodes = nodes
        walkUntil = SystemClock.uptimeMillis() + ms
        walkTruncated = false
    }

    /**
     * May we visit one more node? Every recursive walk in this file asks first, and so does
     * passRoot() before it starts a blocking read.
     *
     * ⚠️ THE CLOCK IS READ ON EVERY NODE, and the first version of this checked it every
     * 32nd to "save time". That was backwards. The thing being budgeted here is not
     * arithmetic, it is a getChild() that goes to another process and can block for
     * milliseconds each - so thirty-two of them between two clock readings is thirty-two
     * chances to sail past the deadline. Measured on the phone: a Play Store pass that had
     * ALREADY spent its node budget still took 563ms. uptimeMillis() is a vDSO read; against
     * a binder round trip it costs nothing at all.
     *
     * This cannot make a pass instant. One blocking read that has already started cannot be
     * cancelled, and the framework gives it five seconds - so the true worst case is "the
     * deadline, plus one slow call". What it does guarantee is that we never START another
     * one after the deadline, which is the difference between a slow pass and a wedged phone.
     */
    private fun canWalk(): Boolean {
        if (walkNodes <= 0) return false
        if (SystemClock.uptimeMillis() > walkUntil) {
            walkNodes = 0
            walkTruncated = true
            return false
        }
        walkNodes--
        return true
    }

    // The other half of the fix: how long the LAST pass actually took, and a cool-off if it
    // was slow. A screen that is expensive to read once is expensive to read every time, so
    // rather than discovering that afresh sixty times a second, we stand back from it for as
    // long as it cost us. Worst case the reader uses half the main thread instead of all of
    // it - and half a main thread still delivers taps.
    private var busyUntil = 0L
    // The surface the last expensive pass was about, so a genuinely NEW window can still
    // skip the throttle and be covered instantly.
    private var lastEvalPkg: String? = null
    private var lastEvalWindow = -1

    /**
     * Runs every RECHECK_MS while an app block is up. Looks at the real window
     * state: still in a blocked app -> keep the cover; an allowed app is genuinely
     * in front -> drop it; can't tell (mid-animation) -> keep it and try again.
     */
    private val recheck = object : Runnable {
        override fun run() {
            if (!appBlockActive) return
            // Any VISIBLE app, not just the focused one - a blocked app in the other
            // split-screen pane or in a PiP window is still on screen (§2.6).
            // OUR OWN APP IS NEVER COVERED. It is the only route to lowering the mode or
            // finishing the setup, so a cover that survives over it is unescapable.
            val front = currentForegroundPackage()
            val hit = if (front == packageName) null else blockedVisibleApp()
            when {
                hit != null -> showAppBlock(hit.second, hit.first) // keeps cover + reposts
                front != null || rootInActiveWindow?.packageName?.toString() == packageName -> {
                    appBlockActive = false
                    overlay?.hide()
                }
                else -> {
                    mainHandler.removeCallbacks(this)
                    mainHandler.postDelayed(this, RECHECK_MS)
                }
            }
        }
    }

    // ── Greylist foreground-time tracking (2-min/hour limit) ───────────────────
    private var greyTarget: String? = null
    private var greyIsApp = false
    private var greySince = 0L
    private val GREY_TICK_MS = 10_000L
    private val GREY_MAX_DELTA = 15_000L      // cap a single gap so screen-off can't over-count wildly

    private fun updateGreyTracking(target: String?, isApp: Boolean) {
        if (target == greyTarget) return
        flushGrey()
        greyTarget = target; greyIsApp = isApp; greySince = System.currentTimeMillis()
        mainHandler.removeCallbacks(greyTick)
        if (target != null) mainHandler.postDelayed(greyTick, GREY_TICK_MS)
    }

    private fun flushGrey() {
        val t = greyTarget ?: return
        val now = System.currentTimeMillis()
        val delta = now - greySince
        greySince = now
        if (delta in 1..GREY_MAX_DELTA) GreyUsage.addUsage(this, t, delta)
    }

    /**
     * Is the thing we are charging time to actually on screen RIGHT NOW?
     *
     * ⚠️ 2026-08-04 - THIS IS THE FIX FOR "the 2-minute message appears out of nowhere".
     * updateGreyTracking is only ever reached from evaluateBlock, and a great many screens
     * never get that far: a whitelisted app returns early, so do keyboards, ignored packages
     * and a screen that is simply off. So the tick carried on charging time to an app the
     * user had left ten minutes ago - and then, when that stale budget ran out, threw the
     * cover over whatever they had ACTUALLY opened. Hence "random", and hence a message
     * about an app that had nothing to do with the screen it landed on.
     *
     * So the tick now re-checks the real window state every time, exactly like the block
     * recheck loop does, instead of trusting a variable nobody is guaranteed to update.
     */
    private fun greyTargetStillInFront(): Boolean {
        val t = greyTarget ?: return false
        if (keyguard.isKeyguardLocked) return false          // screen locked: nobody is using it
        val front = currentForegroundPackage() ?: return false
        // An app target must BE the app in front. A HOST target only has to still be in a
        // browser - evaluateBlock re-reads the address bar and retargets or clears from there.
        return if (greyIsApp) front.lowercase() == t else AppBlocklist.isBrowser(front)
    }

    private val greyTick = object : Runnable {
        override fun run() {
            if (!greyTargetStillInFront()) { updateGreyTracking(null, false); return }
            flushGrey()
            val t = greyTarget ?: return
            // Enforce even while the app sits idle with no events. Routed through
            // coverForeground (i.e. appBlockReason) rather than raising the cover directly,
            // so this obeys the same rules as every other block: never over the keyguard,
            // never with monitoring off, never during a loosen window - and the reason text
            // is built in ONE place instead of two that can drift apart.
            if (greyIsApp && GreyUsage.isOverLimit(this@PageMonitorAccessibilityService, t)) {
                coverForeground()
            }
            mainHandler.postDelayed(this, GREY_TICK_MS)
        }
    }


    /**
     * Built-in guards for in-app screens we never want reachable. Currently:
     * Firefox Focus's privacy settings (the "stealth" option blocks screenshots and
     * would blind the screen capture), and every Firefox screen that can clear
     * browsing history - see AppConfig.FIREFOX_HISTORY_CLEAR_SCREENS, which is THE
     * place to update keywords when a Firefox update changes a screen's wording.
     *
     * Only ever called off the web (host == null), so a web page that merely
     * mentions the keyword can't trip it.
     */
    private fun appScreenBlock(packageName: String, title: String?, content: String?): String? {
        val t = title?.lowercase().orEmpty()
        val c = content?.lowercase().orEmpty()
        for (g in AppConfig.SCREEN_GUARDS) {
            if (g.pkg != packageName) continue
            if (g.superHardcoreOnly && !Mode.isSuperHardcore(this)) continue
            val hitTitle = g.titleKeywords.any { it in t }
            val hitContent = g.contentKeywords.any { it in c }
            if (hitTitle || hitContent) return g.reason
        }
        return null
    }

    /**
     * The image-monitor add-on's own page is where you switch it back OFF - the dump we
     * built this from carried "Enabled / Run in private browsing / Details / Permissions /
     * Remove / Report" - so once the user has confirmed the add-on is installed, that page
     * is off limits. Exactly the same reasoning as the uninstall lock: the guard is worth
     * nothing if the guard's own off-switch is one tap away.
     *
     * Armed ONLY after confirmation (BrowserSetup.extensionConfirmed) - otherwise setup
     * step 4 would block the very page it sends the user to.
     *
     * Matched two ways, because AMO serves the page under a locale segment
     * (".../en-GB/android/addon/<slug>") and Firefox's own add-on manager shows the same
     * switches with no web URL at all:
     *   • the URL carries the add-on's slug, or the title names it on addons.mozilla.org;
     *   • the screen names the add-on AND carries one of its switches.
     */
    private fun extensionPageBlock(
        packageName: String, host: String?, url: String?, title: String?, content: String?,
    ): String? {
        if (!BrowserSetup.extensionConfirmed(this)) return null
        if (packageName !in AppConfig.FIREFOX_PACKAGES) return null
        val u = url?.lowercase().orEmpty()
        val t = title?.lowercase().orEmpty()
        val c = content?.lowercase().orEmpty()
        val name = BrowserSetup.EXTENSION_NAME.lowercase()
        val onAddonPage = BrowserSetup.EXTENSION_SLUG in u ||
            (host != null && BrowserSetup.EXTENSION_HOST in host && name in t)
        val onManagerPage = name in c && AppConfig.EXTENSION_MANAGER_KEYWORDS.any { it in c }
        return if (onAddonPage || onManagerPage) getString(R.string.br_extension_page) else null
    }

    /**
     * Cover for one of the browser's OWN screens - the add-on manager, Firefox's history
     * clearing. Not a web page and not a blocked app, so it gets neither treatment:
     *
     *  - no "go back one page instead": there is no page underneath, and Back inside browser
     *    settings is unpredictable. Exit is the only offer, same as an app cover.
     *  - NOT appBlockActive: an app cover is sticky and owned by the recheck loop, which would
     *    keep it up over the whole browser. This one belongs to the SCREEN, so the ordinary
     *    re-evaluation takes it straight back down when they navigate away.
     */
    private fun showScreenGuard(reason: String, pkg: String, controller: OverlayController) {
        if (!controller.isShowing) BlockEventLog.recordWeb(this, pkg, null, null, reason, null)
        shownBlockHost = null
        shownBlockUrl = null
        controller.show(
            reason = reason,
            onGoBack = {},
            onLeave = { leaveBlockedPage(pkg, controller) },
            onReport = {},
            showGoBack = false,
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UNINSTALL-LOCK GUARDED PAGES  ──  FUTURE DEVS: READ THIS  ──
    // ════════════════════════════════════════════════════════════════════════
    //  While the uninstall lock is ON, the accessibility guard sends the user
    //  back to the home screen the moment they open any Settings page listed
    //  below. These are the "escape routes" that would let someone unlock or
    //  break the lock:
    //    1. Device admin page       – deactivating admin re-enables uninstall.
    //    2. App-info / uninstall     – the Uninstall & Force-stop buttons.
    //    3. Page monitoring (a11y)   – disabling this service KILLS the guard.
    //    4. Appear on top (overlay)  – turning this off breaks the block screen.
    //
    //  Each page is identified ONLY by text that appears on it. A page matches
    //  when EVERY string in `mustContain` is present on screen (case-insensitive
    //  substring). The strings were copied verbatim from this app's own page
    //  monitor on a Samsung device.
    //
    //  ⚠️ IF A PAGE STOPS BEING BLOCKED after an Android / OEM update:
    //     open that page on the phone, find its entry in this app's monitor log,
    //     copy the on-screen text, and update the strings below. That is the
    //     ONLY maintenance this feature needs.
    // ════════════════════════════════════════════════════════════════════════

    // Page-match text lists now live in AppConfig (PAGE-TEXT BLOCK RULES) so devs edit
    // them in one place. This just runs the match against whatever's on screen.
    //
    // ⚠️ 2026-08-27 - THIS FUNCTION IS WHY THE PHONE DIED. DO NOT PUT THE OLD ONE BACK.
    //
    // It used to be two lines:
    //
    //     val root = rootInActiveWindow ?: return false
    //     return page.mustContain.all { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }
    //
    // Both of those are SYNCHRONOUS CROSS-PROCESS CALLS, and neither is cheap:
    //
    //   • getRootInActiveWindow() blocks this thread until the app on screen answers.
    //     AccessibilityInteractionClient gives it a FIVE SECOND timeout. (On this Samsung
    //     build it also drags in a Knox hook that does a ServiceManager lookup first, so it
    //     is two round trips, not one.)
    //   • findAccessibilityNodeInfosByText() is worse: the search does not run here, it runs
    //     ON THE UI THREAD OF THE APP BEING SEARCHED. Ask a busy app and you wait behind
    //     whatever it is doing. Same five-second ceiling.
    //
    // The caller ran this over UNINSTALL_GUARD_PAGES (5) and then ESCAPE_ROUTE_PAGES (12),
    // once per accessibility event, ABOVE the throttle - up to thirty-odd blocking calls per
    // event, each able to take five seconds. And GUARDED_SETTINGS_PACKAGES contains
    // com.android.vending, on purpose: our own Play Store listing has an Uninstall button.
    //
    // So: open the Play Store, let it download something, and its UI thread is busy while it
    // fires content-change events continuously. Every one of those events put this service's
    // main thread into a queue behind the Play Store. Three ANR traces off the user's phone
    // (24, 25 and 26 Aug) all land on exactly these two lines, main thread parked in
    // AccessibilityInteractionClient.waitForResultTimedLocked, 74% CPU in our process across
    // the half-minute before it.
    //
    // An ANR in a BROADCAST is fatal here: the system kills the process. That kills the
    // accessibility service (blocking silently stops) and leaves MainActivity unable to
    // start (the launch icon shows the splash and hangs). Every symptom in the report is
    // this one function.
    //
    // The replacement reads the screen ONCE, into a string, with a node and time budget, and
    // matches every page against that string in memory. Thirty blocking searches become one
    // bounded walk that shares the pass's budget like every other read.
    private fun pageMatches(page: AppConfig.PageMatch): Boolean {
        val text = guardScreenText() ?: return false
        return page.mustContain.all { it.lowercase() in text }
    }

    /**
     * The screen's text, lowercased, for the page guards - read at most once per pass.
     *
     * Deliberately a SEPARATE read from sampleVisibleText: that one is capped at 1000
     * characters because it feeds the word scorer, and a guard needle ("force stop") can sit
     * well below the fold of a Settings page. This one gets a bigger character cap and reads
     * contentDescription too, which is where a Settings switch's label often lives.
     *
     * Cached for the pass, so asking about seventeen different pages costs one read.
     */
    private var guardTextPass = 0L
    private var guardText: String? = null

    private fun guardScreenText(): String? {
        if (guardTextPass == passId) return guardText
        guardTextPass = passId
        val root = passRoot()
        guardText = if (root == null) null else {
            val out = StringBuilder()
            collectGuardText(root, out, 0)
            out.toString().lowercase().takeIf { it.isNotBlank() }
        }
        return guardText
    }

    private fun collectGuardText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.length >= MAX_GUARD_CHARS) return
        if (!canWalk()) return
        node.text?.toString()?.let { if (it.isNotBlank()) out.append(it).append('\n') }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.append(it).append('\n') }
        for (i in 0 until node.childCount) collectGuardText(node.getChild(i), out, depth + 1)
    }

    private var lastGuardScanAt = 0L
    private var lastGuardWindow = -1

    /**
     * May the page guards read the screen right now?
     *
     * Yes immediately when the WINDOW changed - that is somebody opening a page, which is
     * the event this guard exists for. Otherwise at most once every GUARD_SCAN_MS, because
     * the alternative is what the note on pageMatches describes.
     */
    private fun guardScanDue(event: AccessibilityEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        val newWindow = event.windowId != lastGuardWindow ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!newWindow && now - lastGuardScanAt < GUARD_SCAN_MS) return false
        lastGuardScanAt = now
        lastGuardWindow = event.windowId
        return true
    }

    /** The uninstall-guard page in front, or null. */
    private fun ourUninstallScreen(): AppConfig.PageMatch? =
        AppConfig.UNINSTALL_GUARD_PAGES.firstOrNull { pageMatches(it) }

    /** True when the Settings screen in front matches any uninstall-guard page. */
    private fun isOurUninstallScreen(): Boolean = ourUninstallScreen() != null

    /**
     * Is this page's bounce armed right now?
     *
     * Most are always armed. The two that are not are the pages you FINISH THE SETUP on -
     * App info (the only route to "Allow all the time" location on Android 11+) and "Appear
     * on top" (the overlay permission). Bouncing someone off those while the permission is
     * still missing is the lock guarding its own front door: Super hardcore demands the
     * uninstall lock first, and the lock was then blocking the page the mode's own house
     * rule is switched on from. Same principle as the Colour-correction page - a guard that
     * would stop you turning something ON is not armed until it is on. See GrantWindow.
     *
     * The relaxation is Settings-only. The Play Store listing matches the same App-info text
     * and has a real Uninstall button on it, so it is bounced whatever is outstanding.
     */
    private fun guardArmed(page: AppConfig.PageMatch, pkg: String): Boolean =
        page.armedWhen(this) || pkg !in AppConfig.GRANT_WINDOW_PACKAGES

    /**
     * Landing on one of these screens is not an accident - you do not open "Device admin" by
     * mistake. We bounce them home as before, but we also REMEMBER it, because that is what
     * puts the supervised "look anyway" exit on the table for the next half hour. See the big
     * comment on BypassWatch: the point is to catch them reaching for the destructive option
     * and offer the honest one instead.
     */
    private fun recordBypassAttempt(page: AppConfig.PageMatch) {
        val label = page.label.lowercase()
        val reason = when {
            "admin" in label -> BypassWatch.Reason.DEVICE_ADMIN
            "accessibility" in label || "monitoring" in label -> BypassWatch.Reason.ACCESSIBILITY
            "overlay" in label || "top" in label -> BypassWatch.Reason.OVERLAY
            else -> BypassWatch.Reason.UNINSTALL
        }
        BypassWatch.record(this, reason)
    }



    override fun onServiceConnected() {
        super.onServiceConnected()
        // Pre-OFF installs never stored a mode choice; the service running at all means
        // monitoring was live, so keep it live rather than defaulting them to Off.
        // (Accessibility is on by definition here - only the overlay needs checking.)
        Mode.migrateIfUnset(this, Settings.canDrawOverlays(this))
        // FIRST: the broadcasts and the watchdog need somewhere to land that is not the
        // main thread. Everything below this line registers something.
        startBackgroundThread()
        // A reconnect after the process was killed must not inherit a cover. The window died
        // with the old process, but our own idea of one has to be cleared with it or the
        // first evaluation would think a cover is already up and never raise a real one.
        overlay?.hide()
        appBlockActive = false
        overlay = OverlayController(this)
        FilterData.init(this)          // load word/app/domain lists from assets/filter/
        BlockRules.load(this)
        AppBlocklist.refresh(this)
        loadKeyboardPackages()
        DomainBlocklist.warmUp(this)
        startGreyscaleWatch()
        startScreenWatch()
        startBluetoothWatch()
        // Beacon room guard: arms itself only while strict + a room is calibrated +
        // scan permissions granted, and idles otherwise. Entering/leaving a protected
        // room fires no accessibility event, so it re-evaluates the foreground app
        // itself - same shape as the night guard's sensor callback.
        RoomGuard.start(this) { updateRoomGuard() }
        // Home area (GPS): "at the house or out". Like RoomGuard it gates itself - it
        // idles until a home point exists and location is granted - and it runs in every
        // mode because the answer has to be current the moment anything wants to use it.
        // It publishes to HomeAreaContext; HomeRule is what reads it (in Super hardcore,
        // being at the house is what collapses RepeatGate's ladder to one detection).
        HomeAreaWatch.start(this)
        startTamperWatch()
        startInstallWatch()
        bgHandler?.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    /**
     * The heartbeat behind TamperWatch: notices a wound-forward clock, and stretches where
     * this service was not running at all (safe mode, a force stop, an OEM battery killer).
     * Neither is preventable; both are worth knowing about, and until now we knew about
     * neither.
     */
    private val tamperBeat = object : Runnable {
        override fun run() {
            if (TamperWatch.beat(this@PageMonitorAccessibilityService)) {
                android.util.Log.w("PageMonitor", "tamper signal: clock jump or coverage gap")
            }
            mainHandler.postDelayed(this, TamperWatch.HEARTBEAT_MS)
        }
    }

    private fun startTamperWatch() {
        mainHandler.removeCallbacks(tamperBeat)
        mainHandler.post(tamperBeat)
    }

    /**
     * A NEW APP APPEARING is the other half of the sideloading story: guarding the "install
     * unknown apps" screen stops the casual route, but an APK can still arrive over ADB, a
     * work profile, or an already-granted installer. So we also watch for the arrival.
     *
     * A new install while Strict+ is on is checked against the category lists immediately -
     * if it is on one, it is blocked from its first launch rather than from whenever we
     * happen to next reload the lists.
     */
    private val installReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_PACKAGE_ADDED) return
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return  // an update
            val pkg = intent.data?.schemeSpecificPart ?: return
            // PACKAGE_ADDED arrives in bursts while the Play Store works through a queue of
            // downloads - the exact window this whole file's 2026-08-27 rework is about. It
            // is delivered on the background thread and does its (disk) work there too; only
            // the recording touches SharedPreferences, which is safe off the main thread.
            val service = this@PageMonitorAccessibilityService
            if (Mode.isOff(service)) return
            val category = BlockedCategories.appCategory(pkg)
            if (category != null) {
                BypassWatch.record(service, BypassWatch.Reason.INSTALLED_BLOCKED)
                android.util.Log.w("PageMonitor", "blocked-category app installed: $pkg (${category.id})")
            }
            InstallLog.record(service, pkg, category?.title)
        }
    }

    private fun startInstallWatch() {
        val filter = android.content.IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addDataScheme("package")
        }
        registerOffMainThread(installReceiver, filter)
    }

    // ═════════════════════════════════════════════════════════════════════════════════
    //  DOPAMINE BASELINE - the raw counters. The algorithm itself lives in Dopamine.kt.
    // ═════════════════════════════════════════════════════════════════════════════════

    private var segPkg: String? = null
    private var segHost: String? = null
    private var segStart = 0L
    private var screenOffAt = 0L
    private var lastUnlockAt = 0L
    private var urgentOpenArmed = false
    private var justWokeUntil = 0L
    // app -> opens in the current rolling hour, for the "checked Snapchat 50 times" signal
    private val opensThisHour = HashMap<String, Int>()
    private var hourBucketStart = 0L

    /**
     * Unlocks and screen on/off. An accessibility service cannot see these through
     * accessibility events at all - they only arrive as broadcasts, and SCREEN_ON/OFF and
     * USER_PRESENT cannot be declared in the manifest, so they must be registered at runtime.
     */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Delivered on the background thread (see registerOffMainThread) and it must
            // RETURN from there, immediately. This is the exact broadcast whose timeout was
            // getting the process killed, so nothing slow, nothing main-thread-affine, and
            // no waiting for the post to run: hand the work over and get out.
            val action = intent?.action ?: return
            mainHandler.post {
                when (action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        closeUsageSegment()
                        // Stop charging greylist time to whatever was in front. A dark screen
                        // is not two minutes of Instagram, and the tick has no other way to
                        // find out the screen went off (no accessibility event fires for it).
                        updateGreyTracking(null, false)
                        screenOffAt = System.currentTimeMillis()
                    }
                    Intent.ACTION_USER_PRESENT -> onUnlock()
                }
            }
        }
    }

    private fun startScreenWatch() {
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerOffMainThread(screenReceiver, filter)
    }

    private fun onUnlock() {
        val now = System.currentTimeMillis()
        val offFor = if (screenOffAt > 0) now - screenOffAt else 0L
        if (screenOffAt > 0) {
            val offSecs = offFor / 1000
            DopamineLog.update(this) { it.screenOffSeconds += offSecs }
            screenOffAt = 0L
        }
        // A long dark gap means this unlock is probably you waking up. It is a PROXY - we
        // have no way to know an alarm went off (see the note at the top of Dopamine.kt).
        val wokeGapMs = (DopamineTuning.WAKE_GAP_HOURS * 3600_000).toLong()
        if (offFor >= wokeGapMs) {
            justWokeUntil = now + DopamineTuning.JUST_WOKE_WINDOW_MIN * 60_000L
        }
        lastUnlockAt = now
        urgentOpenArmed = true          // the next app opened is a candidate "straight-in open"
        DopamineLog.update(this) { it.unlocks++ }
        // Phone-checking friction (hardcore): too many unlocks this hour -> a short pause.
        // Cover whatever is in front now; the launcher is on the essentials list, so in
        // practice the pause lands on the first non-essential app they open.
        if (CheckingGuard.recordUnlock(this)) coverForeground()
    }

    /**
     * Evaluate the app in front and cover it if anything now blocks it. For triggers that
     * fire OUTSIDE the accessibility-event stream (an unlock, a tap-rate pause) - mirror of
     * updateNightGuard / updateRoomGuard, which do the same off their own signals.
     */
    private fun coverForeground() {
        if (appBlockActive || leaving) return
        val (pkg, reason) = blockedVisibleApp() ?: return
        showAppBlock(reason, pkg)
    }

    /** Called on every foreground app change: closes the last segment and opens a new one. */
    private fun onForegroundChanged(pkg: String) {
        closeUsageSegment()
        segPkg = pkg
        segHost = null
        segStart = System.currentTimeMillis()

        // Straight-in open: unlocked, and within a few seconds we're in a worst-tier feed,
        // with no detour on the way. The purest autopilot signal we have.
        if (urgentOpenArmed) {
            urgentOpenArmed = false
            val within = System.currentTimeMillis() - lastUnlockAt
            if (within <= DopamineTuning.URGENT_OPEN_SECONDS * 1000L &&
                DopamineClassifier.isWorstTier(pkg, null)
            ) {
                DopamineLog.update(this) { it.urgentOpens++ }
            }
        }

        countAppOpen(pkg)
    }

    private fun countAppOpen(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - hourBucketStart >= 3600_000L) {
            hourBucketStart = now
            opensThisHour.clear()
        }
        val n = (opensThisHour[pkg] ?: 0) + 1
        opensThisHour[pkg] = n
        DopamineLog.update(this) { if (n > it.maxChecksInHour) it.maxChecksInHour = n }
    }

    /**
     * Bank the time spent in the app we were just in, against whatever category it counts as.
     * The HOST matters as much as the package: youtube.com/shorts is short-form video, not
     * long-form, even though it's the same app.
     */
    private fun closeUsageSegment() {
        val pkg = segPkg ?: return
        val start = segStart
        segPkg = null
        if (start <= 0) return
        val now = System.currentTimeMillis()
        val secs = (now - start) / 1000
        if (secs <= 0) return
        // A segment longer than this is almost certainly the screen having gone off without
        // us hearing about it. Don't bank a phantom eight-hour TikTok session.
        if (secs > MAX_SEGMENT_SECONDS) return

        val cat = DopamineClassifier.categorise(pkg, segHost, lastFullUrl ?: lastUrl)
        val lateNight = isLateNight()
        val justWoke = now < justWokeUntil
        val lying = SensorContext.known && SensorContext.lyingDown
        // The light BAND, not guardDark: the guard's latch only updates in modes with a light
        // trigger (super hardcore now), and the dopamine dark-seconds stat must not depend on
        // which mode you happen to be in.
        val dark = SensorContext.known && SensorContext.light == AppConfig.LightLevel.DARK

        DopamineLog.update(this) { d ->
            d.seconds[cat] = (d.seconds[cat] ?: 0L) + secs
            d.screenOnSeconds += secs
            if (cat != DopamineCategory.OTHER) {
                if (lateNight) d.lateNightSeconds += secs
                if (justWoke) d.justWokeSeconds += secs
            }
            if (lying) d.lyingSeconds += secs
            if (dark) d.darkSeconds += secs
        }
    }

    private fun isLateNight(): Boolean {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return h >= DopamineTuning.LATE_NIGHT_FROM || h < DopamineTuning.LATE_NIGHT_TO
    }

    /**
     * Scrolls and taps, batched. Writing to prefs on every single scroll event would be
     * absurd - a feed fires these dozens of times a second - so we count in memory and flush
     * every INTERACTION_FLUSH.
     */
    private var pendingInteractions = 0
    private fun countInteraction() {
        pendingInteractions++
        if (pendingInteractions >= INTERACTION_FLUSH) {
            val n = pendingInteractions.toLong()
            pendingInteractions = 0
            DopamineLog.update(this) { it.interactions += n }
        }
    }

    // ── Greyscale enforcement ───────────────────────────────────────────────────────
    private var greyscaleSensor: SensorMonitor? = null
    private var greyscaleApplied = false      // true only while WE hold greyscale on
    private var lastGreyEval = 0L
    private var lastGuardEval = 0L
    // Latched "it is dark": see darkEnoughForGuard. Sticky through the dead band so the cover
    // can't strobe on a borderline reading.
    private var guardDark = false

    /**
     * One SensorMonitor for the whole session. It drives THREE things now, so it runs
     * unconditionally (it used to start only if greyscale was enabled):
     *   - greyscale,
     *   - the night guard (no non-essential apps while lying down / in the dark),
     *   - the posture + light stamped onto every block and relapse record.
     * Slow polling rate: none of those need to be quick, and this runs all day.
     */
    private fun startGreyscaleWatch() {
        val m = SensorMonitor(this)
        m.onUpdate = {
            SensorContext.update(m)
            updateGreyscale()
            updateNightGuard()   // posture/light can change with no accessibility event at all
        }
        greyscaleSensor = m
        m.start(slow = true)
        updateGreyscale()
    }

    private fun updateGreyscale() {
        if (!AppConfig.GREYSCALE_IN_STRICT) return
        val now = System.currentTimeMillis()
        if (now - lastGreyEval < 1000L) return   // re-evaluate at most ~1x/sec
        lastGreyEval = now
        // Read the flag off the mode spec, NOT Mode.isStrict(): super hardcore is not
        // strict, and a stricter mode must never end up with weaker greyscale.
        val greyMode = Mode.spec(this).greyscale
        val lying = greyscaleSensor?.lyingDown ?: false
        val want = greyMode && (!AppConfig.GREYSCALE_ONLY_WHEN_LYING || lying)
        if (want && !greyscaleApplied) {
            if (Greyscale.setEnabled(this, true)) greyscaleApplied = true
        } else if (!want && greyscaleApplied) {
            Greyscale.setEnabled(this, false)
            greyscaleApplied = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // A crash in here kills the whole service ("keeps stopping") and with it
        // ALL blocking - never let one bad event take the service down.
        val startedAt = SystemClock.uptimeMillis()
        try {
            handleEvent(event)
        } catch (t: Throwable) {
            android.util.Log.e("PageMonitor", "event handling failed", t)
        } finally {
            noteEventCost(event, SystemClock.uptimeMillis() - startedAt)
        }
    }

    /**
     * How long that event cost us, and what to do about it.
     *
     * This method is the whole self-healing half of the Play Store fix. The node budget caps
     * ONE pass; this caps the DUTY CYCLE. If reading a screen took 200ms, we do not read
     * another for 200ms - so however hostile the app in front is, at least half of the
     * service's main thread stays free to deliver a tap on the cover's "Go to home screen"
     * button. The old behaviour had no such floor, which is why that button looked dead.
     */
    private fun noteEventCost(event: AccessibilityEvent?, elapsed: Long) {
        if (elapsed < SLOW_PASS_MS) return
        // Stand back for twice what it cost, capped. Twice rather than once because the cost
        // that actually hurts is not the arithmetic - it is a cross-process read that was
        // already in flight when the deadline passed and could not be cancelled. An app that
        // answers slowly once will answer slowly again in a moment, and the capped backoff is
        // what keeps a third of the main thread free while it does.
        busyUntil = SystemClock.uptimeMillis() + minOf(elapsed * 2, MAX_BACKOFF_MS)
        android.util.Log.w(
            "PageMonitor",
            "slow pass: ${elapsed}ms for ${event?.packageName}" +
                (if (walkTruncated) " (read budget spent)" else ""),
        )
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // A new pass: any screen read cached for the last event is now about a screen that
        // may no longer be there. See beginPass / passRoot.
        beginPass()

        val type = event.eventType

        // Scroll/tap counting for the dopamine baseline. Cheap and first: these fire in
        // bursts, so they must never fall through into the expensive page-reading path.
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            if (event.packageName?.toString() != this.packageName) {
                countInteraction()
                // Phone-checking friction. CLICKS only, never scrolls - one swipe fires a
                // burst of TYPE_VIEW_SCROLLED events, which would trip the rate instantly.
                if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                    val popup = CheckingGuard.recordTap(this)
                    if (popup != null) Toast.makeText(this, popup, Toast.LENGTH_LONG).show()
                    else if (CheckingGuard.pauseReason() != null) coverForeground()
                }
            }
            return
        }

        // NOTIFICATIONS  (§2.6). A notification is the one surface that arrives WITHOUT the
        // user opening anything, and its preview text is shown on the lock screen and over
        // whatever is in front. We cannot cover it - it is drawn by the system, and a block
        // screen over the shade would be both impossible and useless.
        //
        // What we can do is READ it. A notification scoring like adult content tells us the
        // app is still pushing that content at the user even while it is blocked, and that
        // is worth counting and worth logging. It feeds BorderlineWatch against the app that
        // sent it, so a stream of them behaves exactly like a stream of borderline screens.
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            handleNotification(event, packageName)
            return
        }

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return
        // Uninstall guard: while the lock is on, bounce out of our own App-info / uninstall /
        // "deactivate admin" pages in Settings.
        //
        // The RECORDING happens whether or not the lock is on: reaching for the uninstall
        // button is the signal we care about, and someone without the lock enabled is if
        // anything closer to actually going through with it. Bouncing still needs the lock.
        // ⚠️ 2026-08-27 - THROTTLED, AND IT HAS TO BE. See the note on pageMatches: this
        // block reads the whole screen, and GUARDED_SETTINGS_PACKAGES includes the Play
        // Store, which fires content-change events continuously while it downloads. Running
        // an unthrottled screen read off every one of those is what wedged the main thread
        // and got the process killed. Half a second is nobody's escape window - you cannot
        // find and press Uninstall in that - and a WINDOW CHANGE (opening the page in the
        // first place) is never throttled at all.
        if (packageName in AppConfig.GUARDED_SETTINGS_PACKAGES && guardScanDue(event)) {
            beginWalkBudget(WALK_NODES_GUARD, WALK_MS_GUARD)
            val guardPage = ourUninstallScreen()
            if (guardPage != null) {
                recordBypassAttempt(guardPage)
                if (UninstallGuard.isAdminActive(this) && guardArmed(guardPage, packageName)) {
                    goHome()
                    return
                }
            }
            // The OTHER ways out: a second user, a private space, a cloned app, sideloading,
            // the system clock, Private DNS, a factory reset. Strict and above only - in
            // Relaxed these are ordinary settings somebody is entitled to open.
            if (!Mode.isRelaxed(this) && !Mode.isOff(this)) {
                val escape = AppConfig.ESCAPE_ROUTE_PAGES.firstOrNull { pageMatches(it) }
                if (escape != null) {
                    BypassWatch.record(this, BypassWatch.Reason.ESCAPE_ROUTE)
                    Toast.makeText(
                        this, getString(R.string.br_escape_route, escape.label), Toast.LENGTH_LONG,
                    ).show()
                    goHome()
                    return
                }
            }
        }
        // Optional user lock: keep them off the Colour-correction page so they can't turn
        // greyscale back off. Only while greyscale is actually on, so they can never lock
        // themselves out of turning it ON in the first place.
        if (packageName == "com.android.settings" &&
            Greyscale.isLockColorPage(this) && Greyscale.isOn(this) &&
            guardScanDue(event) &&
            pageMatches(AppConfig.COLOR_CORRECTION_PAGE)) {
            goHome()
            return
        }
        if (packageName in IGNORED_PACKAGES) return
        // Keyboards pop their own window over the app and fire events under their
        // own package; treating that as "the foreground app changed" is what made
        // the cover flicker. Skip them completely.
        if (packageName.lowercase() in keyboardPackages || isKeyboardWindow(event)) return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            RecentAppsTracker.onForeground(packageName)
            // How well we know an app decides how many detections it takes to close it
            // (AppTrust / RepeatGate), and "how well we know it" is counted here - one
            // sighting a day, from the same event that already tells us the app is in front.
            AppTrust.onForeground(this, packageName)
            if (packageName != segPkg) onForegroundChanged(packageName)
        }

        // ---- App-level block: FIRST, on every event, before any throttling. ----
        // A plain set lookup is effectively free, and running it on the very first
        // window event of an app launch is what makes the cover appear instantly
        // (no waiting for rootInActiveWindow, no 700ms throttle).
        val blockedApp = appBlockReason(packageName)
        if (blockedApp != null) {
            showAppBlock(blockedApp, packageName)
            return // No point reading or logging pages inside a blocked app.
        }

        // The event's package is only ONE of the apps on screen. A window change is exactly
        // when a second split-screen pane or a picture-in-picture window appears, so that is
        // the moment to look at all of them (§2.6). Only on state changes - doing this on
        // every content change would walk the window list dozens of times a second.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val alsoBlocked = blockedVisibleApp()
            if (alsoBlocked != null) {
                showAppBlock(alsoBlocked.second, alsoBlocked.first)
                return
            }
        }


        // An allowed app fired a real window change while an app block is up
        // (e.g. user pressed Home): verify against actual window state right away.
        if (appBlockActive && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            mainHandler.removeCallbacks(recheck)
            mainHandler.post(recheck)
        }

        // Monitoring OFF (the lowest mode): no page reading, no scanning, no blocking, no
        // page log - and any page cover still up comes down. The dopamine/usage counters
        // above keep running (the Productivity stats are not adult-content monitoring),
        // and the uninstall-guard bounce stays, because that belongs to the uninstall lock.
        if (Mode.isOff(this)) {
            if (!appBlockActive) overlay?.let { if (it.isShowing) it.hide() }
            shownBlockHost = null
            shownBlockUrl = null
            return
        }

        val now = System.currentTimeMillis()
        val uptime = SystemClock.uptimeMillis()

        // ── AN OLD EVENT DESCRIBES A SCREEN THAT IS GONE ────────────────────────────
        // Everything above this line is cheap and correct to run late: an app on the block
        // list is on the block list whenever we hear about it. Everything BELOW reads a
        // screen and judges it, and doing that from a stale event is what produced "a block
        // screen popped up five minutes later, on a different app" - the judgement was about
        // the Play Store, the cover landed on whatever was in front by the time we got to it.
        //
        // So a late event is dropped rather than acted on. With the node budget above in
        // place these should be rare; when they are not, dropping them is exactly right.
        if (uptime - event.eventTime > STALE_EVENT_MS) return

        // Blocking now runs on a MUCH shorter leash than logging. The old single 700ms gate
        // ran both, and it is why a banned word gave you a clear look at the results before
        // the cover landed, and why a page you'd already banned took a beat to be covered on
        // reopen. A window CHANGE onto a NEW surface never waits at all.
        //
        // "Onto a new surface" is the 2026-08-27 qualifier, and it is the difference between
        // a leash and no leash. A state change used to skip the throttle unconditionally,
        // which is fine for the case it was written for (you opened something) and useless
        // against an app that fires state changes at itself while it loads - the Play Store
        // does, and every one of them bought a full six-walk read of a very large tree. Now
        // only a genuinely different window or package jumps the queue; the same surface
        // talking to itself waits its 200ms like everything else.
        val stateChange = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val newSurface = packageName != lastEvalPkg || event.windowId != lastEvalWindow
        if (!(stateChange && newSurface) && now - lastBlockEvalAt < BLOCK_INTERVAL_MS) return
        // ...and if the last pass was expensive, stand back for as long as it cost, unless
        // this is a new surface (where being slow is not a reason to be late).
        if (!newSurface && uptime < busyUntil) return
        lastBlockEvalAt = now
        lastEvalPkg = packageName
        lastEvalWindow = event.windowId

        // Known-safe app (maps, messaging, banking, utilities…): no public feed and
        // no arbitrary web content worth scanning - skip the read/scan/screenshot/log
        // entirely to save battery and CPU.
        if (Whitelist.isSafeApp(this, packageName)) return

        // PHASE 1: the chrome. Everything that tells us WHAT page this is.
        beginWalkBudget(WALK_NODES_CHROME, WALK_MS_CHROME)

        val root = passRoot() ?: return

        if (DEBUG_DUMP_NODES && packageName in BROWSER_DEBUG_PACKAGES &&
            now - lastDumpAt > DUMP_INTERVAL_MS
        ) {
            lastDumpAt = now
            dumpBrowserNodes(root, packageName)
        }

        // The bar text is the full address (URL or search), as a screen reader sees
        // it. The host is derived from it purely for blocking.
        val barText = readAddressBarText(packageName)
        // An app's OWN in-app browser has no toolbar we recognise, so fall back to reading
        // the domain out of its chrome (§2.5). Only when there is genuinely a web page on
        // screen and this is not a real browser - in a browser the address bar is the truth.
        val host = barText?.let { hostInText(it) }
            ?: if (!AppBlocklist.isBrowser(packageName) && hasWebView())
                readInAppBrowserHost(root) else null

        if (packageName != lastPackage) {
            lastPackage = packageName
            lastHost = null
            lastHostAt = 0L
            lastHostPkg = null
            lastUrl = null
            lastFullUrl = null
        }
        // A host change makes any captured full URL stale.
        if (host != null && host != lastHost) lastFullUrl = null
        if (host != null) {
            lastHost = host
            lastHostAt = now
            lastHostPkg = packageName
        }
        if (barText != null) lastUrl = barText
        // A host change inside the same app is a new dopamine segment: browsing reddit.com
        // then youtube.com is two different categories, not one long "browser" blur.
        if (host != null && host != segHost) {
            closeUsageSegment()
            segPkg = packageName
            segHost = host
            segStart = System.currentTimeMillis()
        }
        readFocusedFullUrl(host)?.let { lastFullUrl = it }   // fills in path if user taps the bar

        // PHASE 2: the text. Its own allowance, because this is the read the content filter
        // cannot do without - see beginWalkBudget.
        beginWalkBudget(WALK_NODES_TEXT, WALK_MS_TEXT)
        // Content = the web page itself (WebView subtree), falling back to the whole
        // screen for non-browser apps.
        val text = readWebViewText() ?: sampleVisibleText(root)
        val firstLine = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        val rawTitle = event.text
            .joinToString(" ") { it.toString() }
            .trim()
            .takeIf { it.isNotBlank() }
            ?: firstLine?.take(MAX_TITLE_CHARS)
        val title = cleanTitle(rawTitle)   // logged/displayed: "Dog"

        // Block on the RAW title so keyword rules (e.g. "wikipedia") still match.
        // Also passes the URL + on-screen text so "dog" in a search URL or all
        // over an image-results page is caught, not just in the title.
        evaluateBlock(packageName, host, rawTitle, text, lastFullUrl ?: lastUrl)

        // ---- Everything below is LOGGING, which can afford the slow 700ms gate. ----
        if (now - lastProcessedAt < MIN_INTERVAL_MS) return
        lastProcessedAt = now

        // Logging: skip noise apps, and don't record the same page repeatedly.
        if (packageName in NOT_LOGGED_PACKAGES) return
        val signature = "$packageName|${lastUrl ?: lastHost}|${firstLine?.take(40)}"
        if (signature == lastLogSignature) return
        lastLogSignature = signature

        // EVERY entry gets a score, app screens included - not just web pages, and no longer
        // jammed into the title as a "[score 18] " prefix. It is a real column now, rendered
        // as a badge by MonitorAdapter (see MonitorEntry.score).
        //
        // Scoring an app screen here is the same read the block path just did, so this costs
        // nothing extra in practice and is exactly what makes the in-app threshold tunable:
        // you can watch what ordinary use of a messaging app scores before deciding whether
        // APP_THRESHOLD is in the right place. A trusted domain still scores null - we
        // deliberately never read it, so we have nothing to report.
        val scoreThis = !(host != null && Whitelist.isSafeDomain(this, host))
        val pageScore = if (scoreThis)
            BorderlineScorer.score(rawTitle, lastFullUrl ?: lastUrl, text, filterSettings())?.score ?: 0
        else null

        MonitorStore.record(
            this,
            MonitorEntry(
                timestamp = now,
                kind = MonitorEntry.KIND_PAGE,
                packageName = packageName,
                title = title,
                domain = lastHost,
                url = lastFullUrl ?: lastUrl,
                text = text,
                score = pageScore,
            ),
        )
    }

    /**
     * Score a notification's text and act on the pattern rather than the one notification.
     *
     * Deliberately NOT a block: there is nothing to block. It is a read, a count and a log
     * entry. The one thing it can trigger on its own is the hour-long close, and only via
     * BorderlineWatch's ordinary thresholds - the same bar a stream of borderline screens
     * has to clear.
     */
    private fun handleNotification(event: AccessibilityEvent, packageName: String) {
        if (Mode.isOff(this)) return
        if (packageName == this.packageName) return
        if (Whitelist.isSafeApp(this, packageName)) return
        val text = event.text.joinToString(" ") { it.toString() }.trim()
        if (text.isBlank()) return

        val reading = BorderlineScorer.read(null, null, text, filterSettings())
        if (reading.score <= 0) return

        BlockEventLog.recordWeb(
            this, packageName, null, null,
            getString(R.string.br_notification_flagged, appLabelFor(packageName)),
            reading.score,
        )
        // Strict and above only, same as every other BorderlineWatch path.
        if (Mode.isRelaxed(this) || !reading.borderline) return
        if (BorderlineWatch.record(packageName, true) == BorderlineWatch.Action.BLOCK) {
            AppTrust.noteBlocked(this, packageName)
            AppTimedBlock.blockFor(
                this, packageName, BorderlineWatch.PENALTY_MS,
                getString(R.string.br_borderline_block, BorderlineWatch.PENALTY_LABEL),
            )
        }
    }

    /** Shows (or keeps) the sticky cover for a blocked app and (re)arms the loop. */
    private fun showAppBlock(reason: String, blockedPackage: String) {
        val controller = overlay ?: return
        val freshAppBlock = !appBlockActive          // ADD
        appBlockActive = true
        if (freshAppBlock) BlockEventLog.recordApp(this, blockedPackage, reason)   // ADD
        controller.show(
            reason = reason,
            onGoBack = {
                val tapAt = System.currentTimeMillis()
                if (tapAt - lastGoBackAt >= GO_BACK_DEBOUNCE_MS) {
                    lastGoBackAt = tapAt
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            },
            onLeave = { exitToHome(blockedPackage, redirectBrowser = true) },
            onReport = {
                // Intentionally does nothing - reporting an incorrect block must NOT
                // unlock a blocked app.  Kept as a stub so the overlay button still
                // appears, but the app stays covered.
            },
            // An APP cover (blocked app, night guard, room guard, lockdown, timed block):
            // there is no web page underneath, so "go back one page" would be nonsense.
            // Exit is the only offer.
            showGoBack = false,
        )
        mainHandler.removeCallbacks(recheck)
        mainHandler.postDelayed(recheck, RECHECK_MS)
    }


    /** Reason an app should currently be covered: a blocked browser, or a timed content block. */
    private fun appBlockReason(pkg: String?): String? {
        // NEVER cover the lock screen. With the keyguard up, currentForegroundPackage()
        // can still return the app underneath it (systemui is filtered as noise), and the
        // sensor-driven guards (night/room) would then draw the cover over the keyguard -
        // which is exactly where they fire, because you're lying in bed. The keyguard IS
        // a block; ours can wait until the phone is actually unlocked. This also makes
        // the recheck loop drop an existing cover the moment the screen locks.
        if (keyguard.isKeyguardLocked) return null
        if (Mode.isOff(this)) return null                     // monitoring off: nothing is covered
        if (LoosenWindow.isActive(this)) return null          // loosen window: apps allowed
        // Strict was chosen but the setup that enforces it never finished. Ahead of every
        // other guard, because the others are the ones that aren't fully working yet.
        setupGuardReason(pkg)?.let { return it }
        // The borderline WARNING - a real cover, but only for a few seconds. Held here rather
        // than by the caller so the recheck loop keeps it up for its full run and then drops
        // it by itself, and so it obeys the keyguard rule above like everything else.
        if (pkg != null && BorderlineWatch.warningUp(pkg)) return borderlineWarningText(pkg)
        nightGuardReason(pkg)?.let { return it }
        roomGuardReason(pkg)?.let { return it }
        bluetoothGuardReason(pkg)?.let { return it }
        checkingPauseReason(pkg)?.let { return it }
        if (Lockdown.isActive(this) && pkg != packageName && !Lockdown.isAllowed(pkg)) {
            return getString(R.string.br_lockdown)
        }
        if (LoosenWait.isActive(this) && pkg != packageName && !LoosenWait.isAllowed(pkg)) {
            return getString(R.string.br_waiting)
        }
        when (AppRules.appTier(this, pkg)) {                   // user "Report an app" rules
            AppRules.BLOCK -> return getString(R.string.br_blocked_app)
            AppRules.GREY ->
                // NAME THE APP. This cover used to say only "that's your 2 min for this
                // hour", which reads as a message from nowhere - especially since it can
                // land the moment you open the app rather than while you are in it.
                if (pkg != null && GreyUsage.isOverLimit(this, pkg.lowercase()))
                    return getString(R.string.br_grey_limit, appLabelFor(pkg), GreyUsage.LIMIT_MIN)
        }
        AppBlocklist.blockedReason(pkg)?.let { return getString(R.string.br_blocked_app_pkg, it) }
        // The hand-maintained category lists (UGC feeds, adult, strangers, VPNs). AFTER the
        // user's own AppRules above, so an explicit personal rule still wins, and after the
        // browser check so a blocked browser keeps its own clearer wording.
        //
        // ⚠️ THIS SUPERSEDES THE BUILT-IN GREYLIST for the ~17 apps on both lists (TikTok,
        // Instagram, YouTube, Reddit, Snapchat, Discord, Twitch, the Facebook family, X...).
        // AppRules.appTier returns GREY for them, but GREY only blocks once the hourly
        // budget is spent - so it falls through to here and they are blocked outright
        // instead of time-limited. That was the 2026-08-04 instruction ("put on the
        // blacklist"), not an accident.
        //
        // TO PUT THE GREYLIST BACK IN CHARGE for the overlap, move this call ABOVE the
        // `when (AppRules.appTier(...))` block and return null when the tier is GREY.
        BlockedCategories.appCategory(pkg)?.let {
            return getString(R.string.br_blocked_category_app, appLabelFor(pkg!!), it.title)
        }
        // ...and the ones the CURATED list could never have named, caught in the act. See
        // ProxyClients.kt. Deliberately last of the app rules: an app that is blocked for a
        // reason we can state plainly should be blocked with that reason, not this one.
        ProxyClients.detected(this, pkg)?.let {
            return getString(R.string.br_proxy_client, appLabelFor(pkg!!), it.label)
        }
        return AppTimedBlock.reasonIfBlocked(this, pkg)
    }

    /**
     * THIRD-PARTY CLIENT DETECTION. Two signals, worth very different amounts.
     *
     * The DOMAIN signal is proof and acts on one sighting: a third-party client has to send
     * you to the real service to sign in, and §2.5 already reads the host out of an app's
     * own chrome, so "reddit.com inside an app that is not Reddit" arrives here for free.
     *
     * The VOCABULARY signal is a suspicion and needs a second sighting a minute later,
     * because one screen is a question, not an answer - the same rule RepeatGate applies to
     * word detections, for the same reason.
     *
     * Never acts while monitoring is off, and never on an app ClientMarkers.isScannable
     * excludes - browsers, the Play Store, Settings, launchers, ourselves. Read the note on
     * that function before adding anything here: everything in this method ends in a whole
     * app being taken away.
     */
    private fun detectProxyClient(pkg: String, host: String?, title: String?, content: String?, url: String?) {
        if (Mode.isOff(this)) return

        // THE HANDOFF. A browser showing a service's own AUTHORIZE url is an app asking to
        // sign in, and the app that asked is the one that was in front a moment ago. This is
        // the only place a browser is looked at, and only for a URL nobody browses to by
        // choice - see ClientMarkers.serviceForAuthUrl.
        if (AppBlocklist.isBrowser(pkg)) {
            val handoff = ClientMarkers.serviceForAuthUrl(host, url) ?: return
            val asker = RecentAppsTracker.recentlyBefore(pkg)
                .firstOrNull { ClientMarkers.isScannable(it, packageName) } ?: return
            val why = getString(R.string.proxy_why_signin, host.orEmpty())
            if (ProxyClients.proof(this, asker, handoff, why)) {
                android.util.Log.w("PageMonitor", "proxy client (sign-in handoff): $asker -> ${handoff.id}")
                BlockEventLog.recordApp(
                    this, asker,
                    getString(R.string.br_proxy_client, appLabelFor(asker), handoff.label),
                )
            }
            return
        }

        if (!ClientMarkers.isScannable(pkg, packageName)) return

        // PROOF: the service's own site, inside an app that is not that service's app.
        val byHost = ClientMarkers.serviceForHost(host)
        if (byHost != null) {
            val why = getString(R.string.proxy_why_host, host.orEmpty())
            if (ProxyClients.proof(this, pkg, byHost, why)) {
                android.util.Log.w("PageMonitor", "proxy client: $pkg -> ${byHost.id} ($host)")
                BlockEventLog.recordApp(
                    this, pkg,
                    getString(R.string.br_proxy_client, appLabelFor(pkg), byHost.label),
                )
                coverForeground()
            }
            return
        }

        // SUSPICION: the service's own vocabulary. The app's LABEL is passed in as well, but
        // only ever as a tie-breaker - see ClientMarkers.suspectFromScreen.
        val screen = listOfNotNull(title, content).joinToString(" ")
        val match = ClientMarkers.suspectFromScreen(screen, appLabelFor(pkg) + " " + pkg) ?: return
        if (ProxyClients.suspect(this, pkg, match.service, match.why())) {
            android.util.Log.w("PageMonitor", "proxy client: $pkg -> ${match.service.id} (${match.why()})")
            BlockEventLog.recordApp(
                this, pkg,
                getString(R.string.br_proxy_client, appLabelFor(pkg), match.service.label),
            )
            coverForeground()
        }
    }

    /**
     * The borderline warning's wording. Says what is happening, why, and exactly what
     * happens next - a warning that doesn't name the consequence is just an interruption.
     */
    private fun borderlineWarningText(pkg: String): String = getString(
        R.string.br_borderline_warn, appLabelFor(pkg), BorderlineWatch.PENALTY_LABEL,
    )

    /**
     * STRICT MODE THAT ISN'T SET UP YET - see SetupGuard for the full reasoning.
     *
     * Strict and above can be chosen and then walked away from: MainActivity's gate only
     * holds the door inside our own app. Out here the mode would read "Strict" with the
     * overlay permission off, or no Firefox, or no image add-on - protection the user thinks
     * they have and doesn't. So everything non-essential is covered with a screen that says
     * which step is outstanding, until it is done.
     *
     * Relaxed is left alone: it enforces what it promises off the service alone.
     */
    private fun setupGuardReason(pkg: String?): String? {
        if (Mode.isRelaxed(this) || Mode.isOff(this)) return null
        if (SetupGuard.isAllowed(this, pkg)) return null
        val missing = SetupGuard.missingStep(this) ?: return null
        return getString(R.string.br_setup_incomplete, missing)
    }

    /**
     * THE NIGHT GUARD - Super hardcore ONLY now (2026-08-01; both triggers proved too harsh
     * for Strict). While the phone says you are lying down, nothing but the essentials opens,
     * WhatsApp included. That posture is where this goes wrong, and the cheapest intervention
     * is to make the phone useless there.
     *
     * LIGHT NO LONGER TRIGGERS IT ANYWHERE (2026-08-24): nightGuardLuxBelow is null in every
     * mode, so darkEnoughForGuard is permanently false and the dark-only branch below cannot
     * be reached. The branch stays because the trigger is data, not code - see AppConfig.
     *
     * The triggers come from the mode spec (flagLyingDown / nightGuardLuxBelow); Strict has
     * spec.nightGuard = false, so this returns null there without looking further.
     *
     * Fails OPEN on purpose. If the sensors haven't reported yet, or the phone has no light
     * sensor, we do NOT guess - locking someone out of their phone on a guess is far worse
     * than missing one late-night scroll.
     */
    private fun nightGuardReason(pkg: String?): String? {
        val spec = Mode.spec(this)
        if (!spec.nightGuard) return null
        if (pkg == null || pkg == packageName) return null       // never cover ourselves
        if (!SensorContext.known) return null                    // no reading yet -> allow
        if (isNightGuardAllowed(pkg)) return null

        val lying = spec.flagLyingDown && SensorContext.lyingDown
        val dark = darkEnoughForGuard(spec)
        return when {
            lying && dark -> getString(R.string.br_night_lying_dark)
            lying -> getString(R.string.br_night_lying)
            dark -> getString(R.string.br_night_dark)
            else -> null
        }
    }

    /**
     * Is it dark enough to trip the guard? Only in modes that HAVE a light trigger at all -
     * and since 2026-08-24 that is NO mode: nightGuardLuxBelow is null everywhere, so this
     * returns false on the first line and lying-down is the guard's only trigger. The rest is
     * kept working for the day a mode sets the lux level again.
     * Latching, with a deliberate gap between the level that turns it ON
     * and the level that turns it OFF - see NIGHT_GUARD_LIGHT_RELEASE. A bare threshold makes
     * the cover strobe when the reading sits right on the line.
     */
    private fun darkEnoughForGuard(spec: AppConfig.ModeSpec): Boolean {
        val enterAt = spec.nightGuardLuxBelow
        if (enterAt == null) { guardDark = false; return false } // no light trigger in this mode
        val lux = SensorContext.lux
        if (lux < 0f) return false                               // no light sensor / no reading
        val releaseAt = enterAt * AppConfig.NIGHT_GUARD_LIGHT_RELEASE
        guardDark = when {
            lux <= enterAt -> true
            lux > releaseAt -> false
            else -> guardDark                                    // in the dead band: hold
        }
        return guardDark
    }

    /**
     * Re-check the guard whenever the SENSORS move, not just when the screen does.
     *
     * This is what was missing: the recheck loop only runs while a cover is already up, and
     * lying back down fires no accessibility event at all - the screen isn't changing. So once
     * you sat up and the cover dropped, nothing was watching, and lying down again did nothing
     * until you switched apps and forced a fresh event. Now every sensor reading re-evaluates
     * the app in front of you.
     */
    private fun updateNightGuard() {
        if (!Mode.spec(this).nightGuard) return
        val now = System.currentTimeMillis()
        if (now - lastGuardEval < GUARD_EVAL_MS) return
        lastGuardEval = now
        if (appBlockActive) return          // already covered: the recheck loop owns it
        if (leaving) return

        // Ask the WINDOWS what's in front, never a remembered package - otherwise sitting in
        // our own app (or the launcher) would re-cover whatever you happened to open last.
        val pkg = currentForegroundPackage() ?: return
        val reason = appBlockReason(pkg) ?: return
        showAppBlock(reason, pkg)
    }

    private fun isNightGuardAllowed(pkg: String): Boolean {
        val p = pkg.lowercase()
        return NIGHT_GUARD_ALLOWED.any { p.contains(it) }
    }

    /**
     * THE ROOM GUARD. In strict (and super hardcore), while the beacons say you are in a
     * protected room - in the bedroom, at the risk spots you calibrated - nothing but the
     * essentials opens. RoomGuard does the sensing (and fails OPEN, like the night guard);
     * this just turns its verdict into a cover with a room-specific message. Same
     * essentials whitelist as the night guard: calls, texts, clock, camera, maps, home.
     */
    private fun roomGuardReason(pkg: String?): String? {
        if (pkg == null || pkg == packageName) return null       // never cover ourselves
        val room = RoomGuard.activeRoom ?: return null
        if (isNightGuardAllowed(pkg)) return null
        val roomName = room.replaceFirstChar { it.uppercase() }
        return getString(R.string.br_room, roomName)
    }

    /**
     * Re-check whenever the ROOM VERDICT moves, not just when the screen does. Walking
     * into the bedroom mid-scroll fires no accessibility event - without this, the cover
     * would only appear on the next app switch. Mirror of updateNightGuard.
     */
    private fun updateRoomGuard() {
        if (appBlockActive) {
            // A cover is up. If it was OURS and you left the room, the recheck loop will
            // clear it within RECHECK_MS via appBlockReason returning null - nothing to do.
            return
        }
        if (leaving) return
        if (RoomGuard.activeRoom == null) return
        val pkg = currentForegroundPackage() ?: return
        val reason = appBlockReason(pkg) ?: return
        showAppBlock(reason, pkg)
    }

    /**
     * THE BLUETOOTH GUARD - see BluetoothGuard in RoomBeacons.kt for the reasoning.
     *
     * Someone who has configured beacons and then switches Bluetooth off has blinded the
     * room guard, so every non-whitelisted app stays covered until it goes back on.
     *
     * "Whitelisted" here is the app's real whitelist (Whitelist.isSafeApp - the always-
     * allowed list you can see under Developer tools, plus anything the user added),
     * UNION the night/room guard essentials. The union matters: the guard essentials are
     * matched as package SUBSTRINGS, so an odd-branded dialer or clock still gets through
     * even if it never made the curated list. Whatever else this does, it must never stop
     * someone making a phone call.
     */
    private fun bluetoothGuardReason(pkg: String?): String? {
        if (pkg == null || pkg == packageName) return null       // never cover ourselves
        if (!BluetoothGuard.isBlocking(this)) return null
        if (Whitelist.isSafeApp(this, pkg) || isNightGuardAllowed(pkg)) return null
        return getString(R.string.br_bluetooth_off)
    }

    /**
     * THE CHECKING PAUSE - see CheckingGuard. A short cover after too many unlocks or
     * rapid-fire tapping (hardcore checking measures only). Same essentials whitelist as
     * the night guard, and the recheck loop drops the cover the moment the pause expires.
     */
    private fun checkingPauseReason(pkg: String?): String? {
        if (pkg == null || pkg == packageName) return null       // never cover ourselves
        val reason = CheckingGuard.pauseReason() ?: return null
        if (isNightGuardAllowed(pkg)) return null
        return reason
    }

    /**
     * Re-check when the RADIO moves, not just when the screen does. Toggling Bluetooth off
     * from the shade fires no accessibility event at all, so without this the cover would
     * only appear on the next app switch - and turning it back on would leave the cover
     * sitting there. Mirror of updateNightGuard / updateRoomGuard, in both directions:
     * the recheck loop drops the cover once appBlockReason stops returning a reason.
     */
    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) return
            mainHandler.post { onBluetoothStateChanged() }   // off the broadcast thread at once
        }
    }

    private fun onBluetoothStateChanged() {
        if (appBlockActive) {
            // A cover is up. If Bluetooth just came back on, the recheck loop clears it
            // within RECHECK_MS via appBlockReason returning null - but nudge it so the
            // phone comes back immediately rather than a beat later.
            mainHandler.removeCallbacks(recheck)
            mainHandler.post(recheck)
            return
        }
        if (leaving) return
        val pkg = currentForegroundPackage() ?: return
        val reason = appBlockReason(pkg) ?: return
        showAppBlock(reason, pkg)
    }

    private fun startBluetoothWatch() {
        val filter = android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        registerOffMainThread(bluetoothReceiver, filter)
    }

    /**
     * The switches in force for one scoring pass, read fresh so a change takes effect on the
     * next page. [surface] is the app the text came from: it is what PrimerWatch is keyed on,
     * so an adult-adjacent phrase seen in this app minutes ago can still be multiplying what
     * turns up now. Null (the default) means "no primer" - used by the paths that only want
     * a number for the log.
     */
    private fun filterSettings(surface: String? = null) =
        BorderlineScorer.Settings.of(this, primed = PrimerWatch.isPrimed(surface))

    /**
     * The "here is WHY" block under a cover's reason: the handful of words that actually
     * carried the score, biggest share first. A block with no working shown is a block the
     * user can only read as arbitrary - and when it IS wrong, this is the line that tells
     * them (and us) which word to go and fix.
     *
     * Null for anything that wasn't scored - a banned domain or a screen guard is its own
     * explanation and doesn't need a word list under it.
     */
    private fun contributorText(verdict: BorderlineScorer.Result?): String? {
        val top = BorderlineScorer.topContributors(verdict?.contributions ?: return null)
        if (top.isEmpty()) return null
        return top.joinToString("\n") { c ->
            getString(R.string.block_contributor_line, c.word, c.count, c.pct)
        }.let { getString(R.string.block_contributors_header) + "\n" + it }
    }

    // One explain() pass per page, reused for every re-evaluation while the cover is up.
    // Without this the scorer would run again on every accessibility event behind a cover.
    private var explainKey: String? = null
    private var explainResult: BorderlineScorer.Result? = null

    /** The score + breakdown for a page the SCORER didn't block (so it was never asked). */
    private fun explainPage(
        title: String?, url: String?, content: String?, settings: BorderlineScorer.Settings,
    ): BorderlineScorer.Result? {
        val key = "${url.orEmpty()}|${title.orEmpty()}|${content?.length ?: 0}|${settings.primed}"
        if (key != explainKey) {
            explainKey = key
            explainResult = BorderlineScorer.explain(title, url, content, settings)
        }
        return explainResult
    }

    /**
     * What goes UNDER the reason on a cover: the block showing its working.
     *
     * A scored block has its working already - the words that carried it. The gap this
     * fills is every OTHER block: a rule, a banned domain, a category list. Those used to
     * show nothing at all, so "Blocked site: google.com/search?q=..." was the entire
     * explanation, with no score anywhere on screen - which is exactly the complaint. They
     * now get the same treatment: what this page scores right now, the bar it would have
     * had to clear, and the words behind it. A page that scores nothing says so, out loud,
     * rather than leaving a blank space where the reason should be.
     */
    private fun coverDetails(
        verdict: BorderlineScorer.Result?,
        title: String?,
        url: String?,
        content: String?,
        settings: BorderlineScorer.Settings,
    ): String? {
        contributorText(verdict)?.let { return it }          // the scorer blocked: it explains itself
        if (verdict != null) return null
        val explained = explainPage(title, url, content, settings)
            ?: return getString(R.string.block_score_none)
        val head = getString(R.string.block_score_line, explained.score, BorderlineScorer.webBar())
        return contributorText(explained)?.let { "$head\n\n$it" } ?: head
    }

    /**
     * Is the user in the middle of TYPING - i.e. is an editable field focused right now?
     *
     * While they are, we do not raise a NEW block. Two reasons, and the second is the nasty
     * one:
     *   1. Half a word is not a search. Typing "vagin" on the way to "vaginal discharge"
     *      shouldn't slam a cover down mid-keystroke.
     *   2. The block would be attached to the WRONG PAGE. Mid-type, the address bar is being
     *      edited, so the URL we hold is still the PREVIOUS page's - so the escalation would
     *      ban whatever you were on before you started typing. That is how a search engine's
     *      own home page ends up on your ban list.
     *
     * A cover that is ALREADY up stays up - so this can't be used to peek at a blocked page
     * by tapping into the address bar.
     */
    private fun isTypingInField(): Boolean {
        fun walk(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > ADDRESS_BAR_DEPTH || !canWalk()) return false
            if (node.isFocused && node.isEditable) return true
            for (i in 0 until node.childCount) {
                if (walk(node.getChild(i), depth + 1)) return true
            }
            return false
        }
        return walk(passRoot(), 0)
    }

    private fun blockSettled(): Boolean =
        System.currentTimeMillis() - armedAt >= BAN_SETTLE_MS

    private fun escalateWebBlock(host: String, pageUrl: String?) {
        val isSearch = BlockRules.isSearchEngineHost(host)
        val pageRule = BlockRules.pageRuleFor(pageUrl)
        // Why this rule is being created, recorded now while we still know: the score the
        // page was carrying and the words behind it. Next time it blocks, the cover can
        // say so instead of just naming the URL.
        val note = BlockRules.Note(
            origin = BlockRules.Origin.AUTO_BLOCK,
            score = shownBlockScore,
            words = shownBlockWords,
        )
        when {
            pageRule != null -> BlockRules.add(this, pageRule, note)   // this exact page / search term
            !isSearch        -> BlockRules.add(this, host, note)       // non-search, no path -> block host
            // search engine with no term -> add nothing (never ban a whole search engine)
        }
        // Domain strikes never accrue for search engines.
        if (!isSearch) {
            BlockEscalation.recordWebBlock(this, host)?.let { domain ->
                BlockRules.addTimed(
                    this, domain, DOMAIN_BLOCK_MS,
                    BlockRules.Note(BlockRules.Origin.DOMAIN_STRIKE),
                )
            }
        }
    }

    // Two binder calls into PackageManager, and getApplicationInfo can hit disk on a cold
    // cache. That is fine once - it is not fine on the block path, which is exactly where
    // the block reasons ask for it. Labels do not change while an app is installed.
    private val appLabels = HashMap<String, String>()

    private fun appLabelFor(pkg: String): String = appLabels.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            pkg
        }
    }

    /**
     * The "Leave" / exit-all button.
     *
     * This used to fire Back, Back, then Home. That was the thing that "broke" browsers:
     * two blind Back presses land wherever the app's history happens to point - closing
     * tabs, backing out of a form, or bouncing off a page the user still needed. The app
     * now NEVER presses Back on its own. Only HOME, which is unambiguous.
     *
     * For a browser we first hand it a blank page. The troublesome tab stays in the tab
     * list (Android gives us no way to close it), but the browser is no longer sitting on
     * that page - so reopening it lands somewhere neutral, and the user can close the tab
     * themselves without the block cover slamming back down on them.
     *
     * We still cannot force the app off recents; the re-cover-on-reopen blocking is what
     * actually stops them coming back.
     */
    /**
     * "Go to home screen" on a block cover.
     *
     * Ordering is the whole point here, and it is load-bearing:
     *
     *  1. The cover STAYS UP for the entire exit. Hiding it first (which is what we used to
     *     do) uncovered the blocked page for the few frames it took Home to animate in - so
     *     the last thing you saw on the way out was the exact thing you were trying not to
     *     look at. Cover comes off LAST, once Home is already in front.
     *  2. The browser is handed a fresh tab BEFORE we leave, so reopening it does not drop
     *     you straight back onto the blocked page. This is the behaviour Stay Focused has.
     *     We cannot close the offending tab - Android exposes no API for it - but we can make
     *     sure it is not the one in front.
     */
    private fun leaveBlockedPage(pkg: String, controller: OverlayController) {
        leaving = true
        if (AppBlocklist.isBrowser(pkg)) sendBrowserHome(pkg)
        // The blocked host is not always the front app's. It can have been read from a
        // browser sharing the screen, or carried in by the lastHost fallback above. Parking
        // only `pkg` leaves THAT browser sitting on the blocked page, so the next evaluation
        // re-reads it and the cover comes straight back - the button "does nothing".
        lastHostPkg
            ?.takeIf { it != pkg && AppBlocklist.isBrowser(it) }
            ?.let { sendBrowserHome(it) }
        mainHandler.postDelayed({ goHome() }, LEAVE_REDIRECT_MS)
        mainHandler.postDelayed({
            controller.hide()
            shownBlockHost = null
            shownBlockUrl = null
            // Drop the remembered host too. Leaving it set means the fallback can re-arm the
            // very cover this button just took down, off a page the user has already left.
            lastHost = null
            lastHostAt = 0L
            lastHostPkg = null
            leaving = false
        }, LEAVE_HIDE_MS)
    }

    private fun exitToHome(pkg: String? = null, redirectBrowser: Boolean = false) {
        val target = pkg ?: lastPackage
        val redirecting = redirectBrowser && target != null && AppBlocklist.isBrowser(target)
        if (redirecting) sendBrowserHome(target!!)
        // Only wait if we actually handed the browser an intent; otherwise go straight out.
        if (redirecting) mainHandler.postDelayed({ goHome() }, BROWSER_HOME_DELAY_MS)
        else goHome()
        // An APP cover is owned by the recheck loop, which is what takes it down once an
        // allowed app is genuinely in front. Nudge it rather than waiting for its next tick,
        // so the cover lifts as the home screen arrives instead of a beat afterwards.
        mainHandler.removeCallbacks(recheck)
        mainHandler.postDelayed(recheck, BROWSER_HOME_DELAY_MS + RECHECK_MS)
    }

    /**
     * GO HOME, AND ACTUALLY GO.
     *
     * ⚠️ 2026-08-27. performGlobalAction returns a boolean and it is not decoration: it
     * comes back false when the system declines the action - during a window transition,
     * while another accessibility interaction is in flight, or when the service has been
     * temporarily disconnected. We ignored it, so a declined Home was indistinguishable
     * from a dead button, which is precisely how the cover's only way out was reported.
     *
     * When it declines, ask the launcher directly. That route needs no accessibility
     * privileges at all, so it works in the cases where the first one did not.
     */
    private fun goHome() {
        val done = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)
        if (done) return
        android.util.Log.w("PageMonitor", "GLOBAL_ACTION_HOME declined - using a home intent")
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    /**
     * Park [pkg] on a fresh blank tab, so it is no longer sitting on the blocked page and
     * reopening it doesn't drop you straight back onto it.
     *
     * We ASK the browser whether it will take an about:blank intent rather than assuming.
     * Browsers usually only register intent-filters for http/https, in which case an about:
     * URL is silently dropped and the whole redirect does nothing - which is exactly the bug
     * we're fixing. If it won't take it, we fall back to a real (dull) URL, because landing
     * somewhere neutral beats landing back on the blocked page.
     */
    private fun sendBrowserHome(pkg: String) {
        val blank = browserIntent(pkg, BROWSER_HOME_URL)
        val intent = if (blank.resolveActivity(packageManager) != null) {
            blank
        } else {
            android.util.Log.i("PageMonitor", "$pkg won't take $BROWSER_HOME_URL - using fallback")
            browserIntent(pkg, AppConfig.BROWSER_HOME_FALLBACK_URL)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            android.util.Log.w("PageMonitor", "could not send $pkg to a fresh tab", t)
        }
    }

    private fun browserIntent(pkg: String, url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(pkg)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Page-level (domain/keyword) blocking - unchanged behaviour. */
    private fun evaluateBlock(
        packageName: String,
        rawHost: String?,
        title: String?,
        content: String?,
        url: String?,
    ) {
        val controller = overlay ?: return

        // PHASE 3: deciding. The reads made from here on (the WebView test for browser
        // chrome, the am-I-typing test) get their own small allowance so a screen that was
        // expensive to READ cannot leave the VERDICT unable to look at anything.
        beginWalkBudget(WALK_NODES_VERDICT, WALK_MS_VERDICT)

        // Mid-exit: the cover is being held up on purpose until Home lands. Do not touch it.
        if (leaving) return

        // Loosen window: content/page blocks are suspended (the orb + image friction
        // still apply). Re-locks automatically the moment the window expires.
        if (LoosenWindow.isActive(this)) {
            if (!appBlockActive) {
                controller.hide(); shownBlockHost = null; shownBlockUrl = null
            }
            return
        }

        // The address bar is often unreadable

        // The address bar is often unreadable exactly when it matters (scrolled
        // away, image viewer open). For browsers, fall back to the REMEMBERED host
        // of the current page - this is the fix for "pressed back onto the same
        // blocked page and nothing happened".
        // BROWSER CHROME (no WebView on screen): the TAB SWITCHER, settings, history. The
        // browser still reports the current tab's URL and title here, so if we blocked on that
        // we'd cover the tab grid - the one screen you need in order to CLOSE the bad tab, and
        // the only way out of a blocked private tab. So: no page blocking, no keyword blocking,
        // and any cover already up comes down. You must always be able to reach tab view.
        //
        // ⚠️ 2026-08-04 - WITH ONE EXCEPTION, AND IT IS THE WHOLE POINT OF THE SCREEN GUARDS.
        // The browser's OWN dangerous screens are chrome too: the add-on manager, where our
        // image add-on is switched off or removed, and Firefox's delete-browsing-data pages.
        // Neither is a WebView, so bailing out here was quietly disabling BOTH guards - the
        // add-on's own "Remove" page was reachable in every mode, which makes the add-on
        // requirement in the setup gate worth nothing. Evaluate the guards first; only if
        // none of them matches do we drop the cover and leave the chrome alone.
        if (AppBlocklist.isBrowser(packageName) && !hasWebView()) {
            // No host, no URL: off the web by definition, and a stale URL from the last tab
            // must never be what identifies one of these screens. They are recognised purely
            // by what is ON them.
            val chromeGuard = appScreenBlock(packageName, title, content)
                ?: extensionPageBlock(packageName, null, null, title, content)
            if (chromeGuard != null) {
                showScreenGuard(chromeGuard, packageName, controller)
                return
            }
            if (!appBlockActive) {
                controller.hide()
                shownBlockHost = null
                shownBlockUrl = null
            }
            return
        }

        // Mid-type: don't raise a NEW block off half a search term, and don't pin one to the
        // page they haven't left yet. An existing cover is left exactly where it is.
        if (!controller.isShowing && isTypingInField()) return

        // The fallback exists because a browser's bar is not readable on every single
        // event (mid-navigation, bar scrolled away, chrome redrawing). It is NOT a licence
        // to keep asserting a host indefinitely: unbounded, it pinned "reddit.com" onto a
        // Tesco page minutes later and kept a cover up over it. If nothing has re-read the
        // bar within HOST_FALLBACK_MS, we no longer claim to know what page this is.
        val host = rawHost ?: lastHost.takeIf {
            AppBlocklist.isBrowser(packageName) &&
                System.currentTimeMillis() - lastHostAt <= HOST_FALLBACK_MS
        }

        // The heuristic scorer needs real page text to judge. Explicit matches (your ban list,
        // the adult-domain blocklist) do not - they go off the URL, so a page you already
        // banned is covered the instant it loads instead of after its text arrives.
        val contentReady = !content.isNullOrBlank()

        // Greylist time-tracking: accumulate foreground time for a greylisted app or
        // host so the per-hour limit can be enforced.
        val greyTarget = when {
            host != null && AppRules.hostTier(this, host) == AppRules.GREY -> host
            host == null && AppRules.appTier(this, packageName) == AppRules.GREY -> packageName.lowercase()
            else -> null
        }
        updateGreyTracking(greyTarget, isApp = greyTarget != null && host == null)

        val appGuard = if (host == null) appScreenBlock(packageName, title, content) else null
        val extGuard = extensionPageBlock(packageName, host, url, title, content)
        val rule = if (appGuard == null) {
            if (host == null) {
                // Off the web: keyword rules vs the screen title only (deliberately
                // NOT the text - two mentions of a keyword in a chat app shouldn't
                // lock the app). Launchers skipped.
                if (packageName !in NOT_LOGGED_PACKAGES) BlockRules.matchedRule(null, title) else null
            } else {
                // Web pages: domain rules, plus keywords vs title / URL / page text.
                BlockRules.matchedRule(host, title, url, content)
            }
        } else null

        // The scorer's own verdict, kept as a Result so the cover can show its working (the
        // "main contributors" breakdown) rather than just a number. Null unless it blocks.
        //
        // The mode-gated fragments ("ling eri", "bik ini", "red dit") used to be checked here
        // as a separate, unscored, outright block. They are now a TIER OF THE SCORER
        // (ModeFragments), which is why there is nothing left to check at this level.
        var verdict: BorderlineScorer.Result? = null
        // The numbers behind an APP screen's verdict, kept so a screen that did not block can
        // still be counted by BorderlineWatch. Null for anything that isn't an app screen.
        var appReading: BorderlineScorer.Reading? = null
        // The numbers behind ANY scored screen, web included - as opposed to appReading
        // above, which is app screens only because BorderlineWatch is. Only PrimerWatch
        // reads this: it needs to know a primer was on screen whether or not it blocked.
        var scoredReading: BorderlineScorer.Reading? = null
        // ONE Settings for the whole pass, built once. The primed flag has to be read BEFORE
        // this screen is scored, or a primer on this very screen would arm the multiplier
        // that is then applied to the same screen twice over - the scorer already handles
        // the same-screen case itself (see compute()).
        val settings = filterSettings(packageName)
        val baseReason = when {
               appGuard != null -> appGuard
               extGuard != null -> extGuard
               // ── TRUSTED DOMAINS WIN, OVER EVERYTHING ─────────────────────────────
               // This used to sit BELOW the domain blocklist, which made it almost
               // useless: the ~550k community list is built by automated categorisation,
               // and automated categorisation is measurably bad at telling a sexual-health
               // charity from a porn site. So a trusted domain that happened to be on that
               // list was blocked anyway - including, in the real-world UK rollout this is
               // modelled on, rape crisis centres and porn-addiction recovery sites.
               //
               // Being wrong in that direction is worse than the thing we are preventing.
               // The list is short, hand-maintained and documented (domains_trusted.txt);
               // it is allowed to outrank the machine-built ones.
               host != null && Whitelist.isSafeDomain(this, host) -> null
               host != null && DomainBlocklist.isBlocked(host) -> getString(R.string.br_adult_site, host)
               // Search engines: only Google is allowed, in every mode.
               host != null && SearchEngineBlocklist.isBlocked(host) -> getString(R.string.br_search_engine, host)
               // ...and Google only counts as allowed while SafeSearch is actually on.
               // There is no innocent route to "&safe=off", so it is recorded as an attempt.
               SafeSearch.isSearchHost(host) && SafeSearch.isExplicitlyOff(url) -> {
                   BypassWatch.record(this, BypassWatch.Reason.SAFESEARCH_OFF)
                   getString(R.string.br_safesearch_off)
               }
               // The hand-maintained ban list (reddit + its frontends/mirrors, imageboards,
               // borderline shops): banned in EVERY mode.
               host != null && AlwaysBlocklist.isBlocked(host) -> getString(R.string.br_blocked_site, host)
               // The hand-maintained category site lists. Same standing as the ban list
               // above - absolute, in every mode above Off - but they can say WHICH kind of
               // site it was, which is a more useful sentence to be shown.
               host != null && BlockedCategories.hostCategory(host) != null ->
                   getString(
                       R.string.br_blocked_category_site, host,
                       BlockedCategories.hostCategory(host)!!.title,
                   )
               // Strict+-only hosts (list currently empty - see StrictOnlyBlocklist).
               host != null && !Mode.isRelaxed(this) && StrictOnlyBlocklist.isBlocked(host) ->
                   getString(R.string.br_blocked_site, host)
               rule != null -> describeRule(rule)
               host != null && AppRules.hostTier(this, host) == AppRules.GREY &&
                   GreyUsage.isOverLimit(this, host) ->
                       getString(R.string.br_grey_limit_host, GreyUsage.LIMIT_MIN, host)
               // The heuristic - and ONLY the heuristic - waits for real page text.
               !contentReady -> null
               // A REAL browser gets the web bar. An app's in-app browser does NOT: the web
               // bar sits where it does (FilterTuning.WEB_THRESHOLD) because the image add-on
               // is reading the same page from the inside, and it is not doing that inside
               // Instagram. Domain rules apply
               // either way now (that is the point of §2.5) - only the scoring bar differs.
               AppBlocklist.isBrowser(packageName) -> {
                   // judgeWeb, not evaluate: the READING is wanted even when the page does
                   // not block, because that is where a primer sighting comes from (see
                   // PrimerWatch) - and "live cam" not blocking is exactly the case that
                   // has to be remembered.
                   val judged = BorderlineScorer.judgeWeb(title, url, content, settings)
                   scoredReading = judged.reading
                   judged.result?.also { verdict = it }?.reason
               }
               // NON-BROWSER APPS. This used to be the hole in the whole design: the
               // heuristic ran on web pages and inside browsers, and nowhere else - so an
               // app feed got nothing but a keyword match against its screen TITLE, which
               // for Instagram or TikTok is a title like "Instagram". The app feed is the
               // harder problem, not the easier one, and it was the one we weren't reading.
               //
               // Now the same scorer runs on the sampled screen text, at the tighter
               // in-app bar (see FilterTuning.APP_THRESHOLD).
               isScannableApp(packageName) -> {
                   // ONE scoring pass gives both the verdict and the raw numbers. The numbers
                   // are what BorderlineWatch needs: a screen that does not block is still
                   // worth remembering if it keeps not-quite-blocking (see below).
                   val judged = BorderlineScorer.judgeApp(title, url, content, settings)
                   appReading = judged.reading
                   scoredReading = judged.reading
                   judged.result?.also { verdict = it }?.reason
               }
               else -> null
           }

        // ── IS THIS APP A CLIENT FOR SOMETHING WE ALREADY BLOCK? ────────────────────
        // See ProxyClients.kt for the whole argument. Run here because this is the one place
        // that already has all three inputs - the package, the host read out of the app's own
        // chrome, and the screen text - so it costs one map lookup and one substring scan
        // over text we have already collected.
        detectProxyClient(packageName, host, title, content, url)

        // ── PRIMERS: REMEMBER THE ADULT-ADJACENT THING, ACT ON NOTHING ──────────────
        // Recorded whether or not this screen blocked, and deliberately so: the whole point
        // of the tier is that "live cam" on its own does nothing at the time. What it does
        // is arm the multiplier for the next few minutes IN THIS APP, so that if something
        // genuinely sexual follows it, that thing counts for more. Keyed on the package, not
        // the page, because the sequence this is here to catch happens across pages.
        PrimerWatch.note(packageName, scoredReading?.primers ?: 0)

        // ── ONE WORD IS A QUESTION, NOT AN ANSWER (see RepeatGate) ──────────────────
        // A word detection on an APP SCREEN no longer closes the app by itself. It opens a
        // case, and the case has to be confirmed by the word coming back - minutes later,
        // in the same app - before anything is taken away. How many confirmations, and how
        // long the waits between them are, depends on how well we know the app (AppTrust):
        // a new install is closed on the first one, an app you have had for a year is not.
        //
        // ONLY THE SCORER'S VERDICT GOES THROUGH THIS. A banned domain, a blacklisted app,
        // your own ban rule, a watched screen - none of those are evidence to be weighed a
        // second time, they are decisions already made, and they still land on sight. That
        // is also why the gate is skipped for web pages: this is about a word appearing in
        // an app you use for something else, not about the page you just opened.
        val gatedReason = if (
            baseReason != null && verdict != null && host == null &&
            !AppBlocklist.isBrowser(packageName)
        ) {
            repeatGateReason(packageName, baseReason)
        } else {
            baseReason
        }

        // ── THE APP THAT KEEPS ALMOST BLOCKING (see BorderlineWatch) ─────────────────
        // Nothing here has crossed the line, which is exactly why one screen can't be acted
        // on. A RUN of them, in one app, over minutes, is a different fact - and the only
        // place it can be noticed is here, where the readings arrive.
        //
        // A screen the gate is HOLDING lands here too, and should: it scored, so it is at
        // the very least borderline, and a session full of held detections is precisely the
        // pattern BorderlineWatch exists to catch.
        if (gatedReason == null && !Mode.isRelaxed(this) && !Mode.isOff(this)) {
            val reading = appReading
            if (reading != null) {
                // Clean screens are reported too - they drain the bucket. Only feeding it the
                // bad ones would make it a counter of "how long has this app been open".
                when (BorderlineWatch.record(packageName, reading.borderline)) {
                    BorderlineWatch.Action.BLOCK -> {
                        // A content block we worked out ourselves, so it counts against the
                        // app's standing exactly like a confirmed word detection does.
                        AppTrust.noteBlocked(this, packageName)
                        AppTimedBlock.blockFor(
                            this, packageName, BorderlineWatch.PENALTY_MS,
                            getString(R.string.br_borderline_block, BorderlineWatch.PENALTY_LABEL),
                        )
                        showAppBlock(
                            AppTimedBlock.reasonIfBlocked(this, packageName)
                                ?: getString(R.string.br_app_blocked),
                            packageName,
                        )
                        return
                    }
                    // The warning is a real cover, for a few seconds, because a toast behind a
                    // feed is not a warning - it is a thing you scroll past. appBlockReason
                    // keeps it up until it expires, then the ordinary loop drops it.
                    BorderlineWatch.Action.WARN -> {
                        showAppBlock(borderlineWarningText(packageName), packageName)
                        return
                    }
                    BorderlineWatch.Action.NONE -> Unit
                }
            }
        }

        if (gatedReason != null) {
            val freshShow = !controller.isShowing
            // The cover's working-out, for EVERY kind of block (see coverDetails).
            val details = coverDetails(verdict, title, url, content, settings)
            // Scored for app screens too, not just web pages - an in-app block is now a
            // thing that can happen, and a block event with no score is not reviewable.
            // Reuse the verdict when the scorer is what blocked; a block that came from
            // somewhere else (a rule, a banned domain, a screen guard) is scored by the
            // same cached pass the cover just used, so nothing is computed twice.
            val scored = verdict ?: explainPage(title, url, content, settings)
            shownBlockScore = scored?.score
            shownBlockWords = BorderlineScorer
                .topContributors(scored?.contributions ?: emptyList()).map { it.word }
            if (freshShow) {
                BlockEventLog.recordWeb(this, packageName, host, url, gatedReason, scored?.score)
            }

            // The "you went BACK..." line is a RESPONSE TO A TAP, never ambient commentary.
            // It used to be recomputed on every re-evaluation, so it appeared (and stuck) on
            // pages the user had never pressed Back on. Now it is only produced when awaiting-
            // BackResult says the user actually tapped the link, and it is flashed - see below.
            val backStatus = if (!freshShow && awaitingBackResult) {
                awaitingBackResult = false
                if (host != null && host == shownBlockHost)
                    getString(R.string.br_back_same)
                else
                    getString(R.string.br_back_diff)
            } else {
                null
            }
            // A DIFFERENT page just became the blocked one -> restart the settle timer.
            if (url != shownBlockUrl) armedAt = System.currentTimeMillis()
            shownBlockHost = host
            shownBlockUrl = url
            val reason = gatedReason

            if (freshShow) {
                // Every NEW block screen (page rules included, not just images) now
                // counts toward the rapid limit: 5 in 10 min on one app -> 90 min.
                RapidBlockMonitor.record(packageName)?.let { penaltyMs ->
                    AppTimedBlock.blockFor(
                        this, packageName, penaltyMs,
                        getString(R.string.br_app_too_many, RapidBlockMonitor.PENALTY_LABEL),
                    )
                    showAppBlock(
                        AppTimedBlock.reasonIfBlocked(this, packageName) ?: getString(R.string.br_app_blocked),
                        packageName,
                    )
                    return
                }
            }

            controller.show(
                reason = reason,
                details = details,
                onGoBack = {
                    val tapAt = System.currentTimeMillis()
                    if (tapAt - lastGoBackAt >= GO_BACK_DEBOUNCE_MS) {
                        lastGoBackAt = tapAt
                        awaitingBackResult = true    // arms the status line for the NEXT evaluation
                        // Only ban a page that has STAYED blocked (real), not one that
                        // merely flickered mid-transition.
                        if (blockSettled()) shownBlockHost?.let { escalateWebBlock(it, shownBlockUrl) }
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                },
                onLeave = {
                    if (blockSettled()) shownBlockHost?.let { escalateWebBlock(it, shownBlockUrl) }
                    leaveBlockedPage(packageName, controller)
                },
                onReport = {
                    // do nothing
                },
                // "Go back one page instead" only makes sense over a distracting WEB PAGE
                // in a browser (host != null there, and only there). A non-web screen
                // guard or an in-app keyword match has no page to go back to.
                showGoBack = host != null,
            )
            // show() only sets the text on first display; keep the reason live.
            if (!freshShow) controller.setReason(reason)
            backStatus?.let { controller.flashStatus(it) }
        } else {
            // Back landed somewhere clean: the tap is answered, so disarm the status line
            // rather than letting it fire on whatever gets blocked next.
            awaitingBackResult = false
            if (!appBlockActive) {
                controller.hide()
                shownBlockHost = null
                shownBlockUrl = null        // ADD
            }
        }
    }

    /**
     * Put a word detection on an app screen through [RepeatGate].
     *
     * Returns the reason to show if THIS detection is the one that confirms the case, or
     * null while it is still only a detection - in which case nothing at all is shown. The
     * confirmed reason keeps the scorer's own wording and adds the sentence that makes the
     * block make sense: that it took more than one look, and how many.
     */
    private fun repeatGateReason(pkg: String, reason: String): String? {
        // SUPER HARDCORE, AT THE HOUSE: no ladder at all - the first detection is the last
        // one. Every app is treated exactly like a brand new install, because in the place
        // and the mode where this fires, "it was probably nothing" has stopped being the
        // more likely explanation. See HomeRule for why it is this mode and this place.
        val atHome = HomeRule.oneDetectionIsEnough(this)
        val tier = if (atHome) AppTrust.Tier.NEW else AppTrust.tier(this, pkg)
        return when (RepeatGate.record(pkg, tier)) {
            RepeatGate.Verdict.HOLD -> null
            // The same block, still standing. Show the sentence it was raised with rather
            // than rebuilding it: the app's tier has changed underneath us (it has now been
            // blocked once), so a rebuilt sentence would quietly rewrite itself on screen.
            RepeatGate.Verdict.HELD ->
                gateBlockText?.takeIf { gateBlockPkg == pkg } ?: reason
            RepeatGate.Verdict.BLOCK -> {
                // From here on this app has form: a shorter ladder next time.
                AppTrust.noteBlocked(this, pkg)
                val text = when {
                    // Say WHERE the rule came from. "One detection was enough" is baffling
                    // in an app you have had for years unless the screen says why.
                    atHome -> getString(R.string.br_home_block, reason)
                    tier == AppTrust.Tier.NEW -> getString(
                        R.string.br_new_app_block, reason, AppTrust.installedDays(this, pkg),
                    )
                    else -> getString(
                        R.string.br_repeat_block, reason,
                        RepeatGate.hitsNeeded(tier), RepeatGate.CASE_MS / 60_000,
                    )
                }
                gateBlockPkg = pkg; gateBlockText = text
                text
            }
        }
    }

    /**
     * Is this a non-web app screen whose text we should judge for adult content?
     *
     * Almost everything is: known-safe apps never reach here (handleEvent returns early on
     * Whitelist.isSafeApp), nor do keyboards, our own UI, or IGNORED_PACKAGES. What is left
     * to exclude is the LAUNCHER - the home screen carries every app name and widget on the
     * device, which is somebody else's text, not a thing the user chose to look at.
     */
    private fun isScannableApp(packageName: String): Boolean =
        packageName !in NOT_LOGGED_PACKAGES

    /** Turn a raw block rule into readable wording: a dot means a site, otherwise a keyword. */
    /**
     * The cover's headline for a rule block: WHAT it blocks, and - the part that was
     * missing - HOW IT GOT THERE. A rule the user has never seen created ("blocked site:
     * google.com/search?q=...") is indistinguishable from the app being arbitrary; the
     * note recorded when the rule was added is the answer, so read it back.
     */
    private fun describeRule(rule: String): String {
        val head = BlockRules.describe(this, rule)
        val why = BlockRules.whyLine(this, rule)
        return if (why == null) head else "$head\n$why"
    }

    /** The package of the application window that is actually in front, or null. */
    private fun currentForegroundPackage(): String? {
        try {
            var looked = 0
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (!window.isActive && !window.isFocused) continue
                // ⚠️ window.root is a BLOCKING CROSS-PROCESS READ of that app, with the same
                // five-second ceiling as everything else in this file (see pageMatches). The
                // window list can be long, and this runs from the recheck loop every 400ms
                // while a cover is up - i.e. exactly when the phone must stay responsive. Cap
                // how many we are willing to ask.
                if (looked++ >= MAX_WINDOWS_QUERIED) break
                val pkg = window.root?.packageName?.toString() ?: continue
                if (isNoise(pkg)) continue
                return pkg
            }
        } catch (_: Throwable) {
            // fall through to the fallback below
        }
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: return null
        return if (isNoise(pkg)) null else pkg
    }

    /**
     * EVERY app package with a window on screen - not just the focused one.  (§2.6)
     *
     * currentForegroundPackage() answers "what is the user looking at", which is the right
     * question for logging and the wrong one for blocking. Two cases it gets wrong:
     *
     *   • SPLIT SCREEN. Two apps, both visible, one focused. A blocked app in the other pane
     *     is fully usable - you can read it, scroll it, and it never has focus.
     *   • PICTURE-IN-PICTURE. A PiP window is an application window that is never active and
     *     never focused. A video from a blocked app keeps playing in the corner over the
     *     home screen, and the focused package is the launcher.
     *
     * So blocking asks this instead, and covers if ANY of them is blocked.
     */
    private fun visibleAppPackages(): List<String> {
        val out = ArrayList<String>(3)
        try {
            var looked = 0
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (!isReallyOnScreen(window)) continue
                if (looked++ >= MAX_WINDOWS_QUERIED) break     // see currentForegroundPackage
                val pkg = window.root?.packageName?.toString() ?: continue
                if (isNoise(pkg)) continue
                if (pkg !in out) out.add(pkg)
            }
        } catch (_: Throwable) {
            // windows can throw mid-transition; the caller falls back to the focused package
        }
        if (out.isEmpty()) currentForegroundPackage()?.let { out.add(it) }
        return out
    }

    /**
     * Is this window ACTUALLY in front of the user right now?
     *
     * ⚠️ 2026-08-04 - THIS TEST IS THE FIX FOR A BAD REGRESSION, DO NOT LOOSEN IT AGAIN.
     * When PiP and split-screen support went in, this filter was dropped altogether on the
     * theory that "every application window is on screen". It is not: the window list keeps
     * entries for apps that are merely still alive, so a blocked app the user had opened
     * once made blockedVisibleApp() return it forever. The recheck loop then held the cover
     * up over EVERYTHING - including this app's own settings - reporting a block for an app
     * that was nowhere on screen. It bricked the phone.
     *
     * So: focused or active as the base (which is what "in front" means for an ordinary
     * app), plus picture-in-picture explicitly, because a PiP window is deliberately never
     * either of those and is the whole reason this function exists.
     */
    private fun isReallyOnScreen(window: AccessibilityWindowInfo): Boolean {
        if (window.isActive || window.isFocused) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // A PiP window is on screen by definition and never focused.
            if (runCatching { window.isInPictureInPictureMode }.getOrDefault(false)) return true
        }
        return false
    }

    /**
     * The first app that is ON SCREEN and should be covered, and why.
     *
     * NEVER returns this app. Our own UI is the only place the mode can be lowered or the
     * setup finished, so a cover over it is a cover nobody can get out from under. isNoise
     * already excludes us; this is the second belt.
     */
    private fun blockedVisibleApp(): Pair<String, String>? {
        for (pkg in visibleAppPackages()) {
            if (pkg == packageName) continue
            appBlockReason(pkg)?.let { return pkg to it }
        }
        return null
    }

    private fun isNoise(pkg: String): Boolean =
        pkg == packageName || pkg in IGNORED_PACKAGES || pkg.lowercase() in keyboardPackages

    private fun loadKeyboardPackages() {
        keyboardPackages = try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.inputMethodList.map { it.packageName.lowercase() }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun isKeyboardWindow(event: AccessibilityEvent): Boolean {
        val id = event.windowId
        return try {
            windows.firstOrNull { it.id == id }?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } catch (_: Throwable) {
            false
        }
    }


    /**
     * Reads the address bar, preferring the FULL url. DuckDuckGo's unfocused
     * omnibar has several matching nodes - typically a chip/label showing only the
     * host ("en.wikipedia.org") AND the real input field holding the full URL
     * ("https://en.wikipedia.org/wiki/Dog"). A plain depth-first walk hits the
     * host-only one first, which is why we were logging just the domain. So gather
     * ALL candidates and keep the richest.
     *
     * ⚠️ 2026-08-24 - AND ONLY FROM WINDOWS THAT ARE ACTUALLY IN FRONT, AND ACTUALLY THEIRS.
     * This used to walk every entry in `windows` unfiltered. The window list keeps entries
     * for apps that are merely still ALIVE, so the bar of a backgrounded browser was read
     * and attributed to whatever app was really on screen: a Firefox tab sitting on
     * "reddit.com" raised a banned-domain cover over the PLAY STORE. Nothing the cover
     * offered could clear it either - Back and Home move the FOREGROUND, and the string
     * holding the block up was in a window they do not touch - so every button looked dead
     * and the phone was unusable. Same failure isReallyOnScreen was written for; see the
     * warning on it. Do not drop either test.
     */
    private fun readAddressBarText(forPackage: String?): String? {
        val candidates = mutableListOf<String>()

        // A window's bar only counts if the window belongs to the app this evaluation is
        // ABOUT. Without that test the richest string on the whole device wins, whoever it
        // belongs to - which is exactly how a background Firefox tab got attributed to the
        // Play Store (see the note above).
        fun collectFrom(root: AccessibilityNodeInfo?) {
            if (root == null) return
            if (forPackage != null && root.packageName?.toString() != forPackage) return
            collectAddressCandidates(root, depth = 0, out = candidates)
        }

        collectFrom(passRoot())
        try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (!isReallyOnScreen(window)) continue
                collectFrom(window.root)
            }
        } catch (_: Throwable) {
            // windows can throw mid-transition; whatever the active window gave us stands.
        }
        return candidates.distinct().maxByOrNull { urlRichness(it) }?.take(MAX_URL_CHARS)
    }

    private fun collectAddressCandidates(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableList<String>,
    ) {
        if (node == null || depth > ADDRESS_BAR_DEPTH || !canWalk()) return
        if (isAddressBar(node) && !node.isFocused) {
            addressTextOf(node)?.let { out.add(it) }
        }
        for (i in 0 until node.childCount) {
            collectAddressCandidates(node.getChild(i), depth + 1, out)
        }
    }

    /** Higher = more like a real, full URL. A path is the strongest signal. */
    private fun urlRichness(value: String): Int {
        val afterScheme = value.substringAfter("://", value)
        var score = 0
        if (value.startsWith("http", ignoreCase = true)) score += 2
        if (afterScheme.contains('/')) score += 5     // has a path -> richest
        if (afterScheme.contains('?')) score += 1
        score += minOf(value.length, 250) / 50
        return score
    }

    /** Pulls the address text off a bar node, skipping empty/hint placeholders. */
    private fun addressTextOf(node: AccessibilityNodeInfo): String? {
        val raw = node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        // Some browsers (Firefox's Compose toolbar) put the address in the CONTENT
        // DESCRIPTION with the placeholder glued on:
        //   "example.com/page. Search or enter address"
        // So rather than discarding the whole string when a hint shows up, cut it
        // off at the first hint phrase and keep the real address in front of it.
        val lower = raw.lowercase()
        var end = raw.length
        for (hint in ADDRESS_BAR_HINTS) {
            val i = lower.indexOf(hint)
            if (i in 0 until end) end = i
        }
        val cleaned = raw.substring(0, end).trim().trim('.', ',', '-', '·').trim()
        if (cleaned.isBlank()) return null   // it really was only a placeholder
        return cleaned.take(MAX_URL_CHARS)
    }

    private fun isAddressBar(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase()
        if (viewId != null) {
            if (ADDRESS_BAR_IDS.any { viewId.endsWith(it) }) return true
            // Generic backup (the "looks like a url bar" catch-all): any id that
            // contains url / address / location / omnibar. Catches browsers we
            // haven't enumerated - e.g. Firefox's "ADDRESSBAR_URL_BOX".
            if (ADDRESS_BAR_ID_HINTS.any { it in viewId }) return true
        }
        if (node.isEditable || node.className == "android.widget.EditText") return true
        val description = node.contentDescription?.toString()?.lowercase() ?: return false
        return ADDRESS_BAR_HINTS.any { it in description }
    }

    private fun sampleVisibleText(root: AccessibilityNodeInfo): String? {
        val builder = StringBuilder()
        collectText(root, builder, depth = 0)
        return builder.toString().trim().take(MAX_TEXT_CHARS).takeIf { it.isNotBlank() }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.length >= MAX_TEXT_CHARS) return
        if (!canWalk()) return

        val nodeText = node.text?.toString()?.trim()
        if (!nodeText.isNullOrEmpty()) {
            out.append(nodeText).append('\n')
        }

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, depth + 1)
        }
    }

    /**
     * THE HOST OF A PAGE INSIDE AN APP'S OWN BROWSER  (§2.5)
     *
     * Instagram, Reddit, Telegram, Discord and most of the rest open links in a WebView they
     * own rather than handing them to Firefox. That WebView has no toolbar we recognise, so
     * readAddressBarText() returns nothing, `host` comes back null - and every domain rule we
     * have silently does not apply. Not the 550k blocklist, not the ban list, not one of the
     * six category lists. Only the word scorer ran, and a page of pictures has no words.
     *
     * That was the largest remaining hole in the design: every app on the blacklist ships a
     * browser that walks straight past the blacklist.
     *
     * These in-app browsers do almost always show the DOMAIN somewhere in their own chrome -
     * a header line, a subtitle under the page title, a share sheet. So: walk the tree, stay
     * OUT of the WebView subtree (inside it is page content, which is somebody else's text
     * and full of other people's domains), and take the first node whose entire text is a
     * host and nothing else.
     *
     * "Entire text" is the load-bearing part. A sentence that happens to mention a domain is
     * not an address bar, and treating it as one would let a page block itself by quoting a
     * URL. Whitespace anywhere in the string disqualifies it.
     */
    private fun readInAppBrowserHost(root: AccessibilityNodeInfo): String? {
        var found: String? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int, insideWeb: Boolean) {
            if (node == null || found != null || depth > ADDRESS_BAR_DEPTH || !canWalk()) return
            val nowInside = insideWeb || node.className == "android.webkit.WebView"
            if (!nowInside) {
                bareHost(node.text?.toString())?.let { found = it; return }
                bareHost(node.contentDescription?.toString())?.let { found = it; return }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1, nowInside)
                if (found != null) return
            }
        }
        walk(root, 0, false)
        return found
    }

    /**
     * A string that IS an address, not one that CONTAINS an address. "instagram.com" and
     * "https://example.com/x" qualify; "see instagram.com for more" does not.
     */
    private fun bareHost(raw: String?): String? = InAppBrowser.bareHost(raw)

    private fun hostInText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = HOST_PATTERN.find(raw) ?: return null
        return match.groupValues[1].lowercase()
    }


    /** "Dog - Wikipedia" -> "Dog". Strips a trailing " - Site" style suffix. */
    private fun cleanTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim()
        for (sep in listOf(" - ", " – ", " - ", " | ", " · ", " :: ")) {
            val idx = t.indexOf(sep)
            if (idx > 0) { t = t.substring(0, idx).trim(); break }
        }
        return t.take(MAX_TITLE_CHARS).takeIf { it.isNotBlank() }
    }

    /**
     * Collects text from INSIDE the WebView only - i.e. the actual web page,
     * skipping the browser's own chrome (toolbar, tabs, menus). This is what makes
     * "page content" the page, not the address bar.
     */
    private fun readWebViewText(): String? {
        val out = StringBuilder()
        passRoot()?.let { collectWebViewText(it, depth = 0, out = out, insideWeb = false) }
        return out.toString().trim().take(MAX_TEXT_CHARS).takeIf { it.isNotBlank() }
    }

    /**
     * Is an actual web page rendered right now? True only if a WebView is on screen.
     *
     * This is what tells a real page apart from the browser's own chrome - the TAB SWITCHER
     * above all. The tab grid is a list of thumbnails, not a WebView, yet the browser still
     * reports the current tab's URL - so without this check we block the user on the very
     * screen they need in order to CLOSE the offending tab. They must always be able to get
     * to tab view and shut a tab.
     *
     * Deliberately NOT "does the page have text yet": text arrives late, and gating on it is
     * what made a page you'd already banned sit there readable for seconds after reopening.
     */
    private fun hasWebView(): Boolean {
        fun walk(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > MAX_DEPTH || !canWalk()) return false
            if (node.className == "android.webkit.WebView") return true
            for (i in 0 until node.childCount) {
                if (walk(node.getChild(i), depth + 1)) return true
            }
            return false
        }
        return walk(passRoot(), 0)
    }

    // private fun collectWebViewText(
        // node: AccessibilityNodeInfo?,
        // depth: Int,
        // out: StringBuilder,
        // insideWeb: Boolean,
    // ) {
        // if (node == null || depth > MAX_DEPTH || out.length >= MAX_TEXT_CHARS) return
        // val nowInside = insideWeb || node.className == "android.webkit.WebView"
        // if (nowInside) {
            // val t = node.text?.toString()?.trim()
            // if (!t.isNullOrEmpty()) out.append(t).append('\n')
        // }
        // for (i in 0 until node.childCount) {
            // collectWebViewText(node.getChild(i), depth + 1, out, nowInside)
        // }
    // }


    private fun collectWebViewText(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: StringBuilder,
        insideWeb: Boolean,
    ) {
        if (node == null || depth > MAX_DEPTH || out.length >= MAX_TEXT_CHARS) return
        if (!canWalk()) return
        val nowInside = insideWeb || node.className == "android.webkit.WebView"
        if (nowInside) {
            val t = node.text?.toString()?.trim()
            val d = node.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty()) out.append(t).append('\n')
            if (!d.isNullOrEmpty() && d != t) out.append(d).append('\n')  // page content also hides here
        }
        for (i in 0 until node.childCount) {
            collectWebViewText(node.getChild(i), depth + 1, out, nowInside)
        }
    }

    /**
     * DDG only exposes the full path while the omnibar is focused (tapped). Grab it
     * then, but only if the host matches the current page and there's a real path,
     * so a half-typed search isn't mistaken for the URL. Link taps don't focus the
     * bar, so those navigations stay host-only - that's a DDG limit, not a bug.
     */
    private fun readFocusedFullUrl(currentHost: String?): String? {
        if (currentHost == null) return null
        var found: String? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > ADDRESS_BAR_DEPTH || found != null || !canWalk()) return
            if (isAddressBar(node) && node.isFocused) {
                val t = node.text?.toString()?.trim()
                if (!t.isNullOrBlank() &&
                    hostInText(t) == currentHost &&
                    t.substringAfter("://", t).contains('/')
                ) {
                    found = t.take(MAX_URL_CHARS)
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        passRoot()?.let { walk(it, 0) }
        return found
    }

    override fun onInterrupt() {
        // Nothing to clean up.
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(recheck)
        RoomGuard.stop()
        HomeAreaWatch.stop()
        greyscaleSensor?.stop(); greyscaleSensor = null
        if (greyscaleApplied) { Greyscale.setEnabled(this, false); greyscaleApplied = false }
        overlay?.hide()
        closeUsageSegment()                                    // bank the time we were mid-way through
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(bluetoothReceiver) }
        runCatching { unregisterReceiver(installReceiver) }
        mainHandler.removeCallbacks(tamperBeat)
        bgHandler?.removeCallbacksAndMessages(null)
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        super.onDestroy()
    }


    /**
     * DEBUG ONLY. Logs every text-bearing / editable node in the current browser
     * window with its view-id, class, editable/focused flags, text and
     * description. Open DuckDuckGo on a known page (e.g. the Dog article), then
     * read the "NODE DUMP" row in the list to see EXACTLY which node holds the full
     * URL on your version. Add that node's id suffix to ADDRESS_BAR_IDS, then set
     * DEBUG_DUMP_NODES = false.
     */
     private fun dumpBrowserNodes(root: AccessibilityNodeInfo, packageName: String) {
        val flagged = StringBuilder()
        val all = StringBuilder()
        dumpNode(root, depth = 0, all = all, flagged = flagged)
        val out = buildString {
            append("=== LIKELY URL / INPUT NODES (look here first) ===\n")
            append(if (flagged.isBlank()) "(none found)\n" else flagged.toString())
            append("\n=== ALL TEXT NODES ===\n")
            append(all)
        }
        MonitorStore.record(
            this,
            MonitorEntry(
                timestamp = System.currentTimeMillis(),
                kind = MonitorEntry.KIND_PAGE,
                packageName = packageName,
                title = "NODE DUMP",
                text = out.take(8000),
            ),
        )
    }

    private fun dumpNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        all: StringBuilder,
        flagged: StringBuilder,
    ) {
        if (node == null || depth > 30) return
        val id = node.viewIdResourceName
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val idLower = id?.lowercase()
        val urlish = idLower != null &&
            ("url" in idLower || "omni" in idLower || "address" in idLower || "location" in idLower)
        val line = "id=$id cls=${node.className} edit=${node.isEditable} " +
            "foc=${node.isFocused} text=$text desc=$desc\n"
        if (!text.isNullOrBlank() || !desc.isNullOrBlank() || node.isEditable || urlish) {
            all.append(line)
        }
        if (urlish || node.isEditable) flagged.append("★ ").append(line)
        for (i in 0 until node.childCount) {
            dumpNode(node.getChild(i), depth + 1, all, flagged)
        }
    }

    companion object {

        private const val MIN_INTERVAL_MS = 700L
        private const val RECHECK_MS = 400L
        private const val MAX_TEXT_CHARS = 1000
        private const val MAX_TITLE_CHARS = 120
        private const val MAX_DEPTH = 40

        // The node budget - see the block comment on beginWalkBudget. The MS figures are the
        // ones that matter: they are a promise about the main thread, not about a tree. The
        // three phases add up to 100ms in the very worst case, and on an ordinary app the
        // whole pass finishes in a fraction of one phase without ever reaching a limit.
        private const val WALK_NODES_CHROME = 900     // address bar, WebView test, in-app host
        private const val WALK_MS_CHROME = 40L
        private const val WALK_NODES_TEXT = 900       // the page/screen text itself
        private const val WALK_MS_TEXT = 40L
        private const val WALK_NODES_VERDICT = 500    // the checks made while deciding
        private const val WALK_MS_VERDICT = 20L
        private const val WALK_NODES_GUARD = 700      // the uninstall / escape-route page scan
        private const val WALK_MS_GUARD = 30L
        // The guard needles sit further down a Settings page than the scorer's sample ever
        // reaches, so this read gets its own, larger character cap.
        private const val MAX_GUARD_CHARS = 8000
        // The page guards are the most expensive thing we do and the least urgent: nobody
        // reaches the uninstall button in under half a second. One scan per this long.
        private const val GUARD_SCAN_MS = 500L
        // How many windows we will do a blocking root read on in one sweep. Split screen is
        // two, plus a PiP; beyond that the list is apps that merely still exist.
        private const val MAX_WINDOWS_QUERIED = 4
        // A pass slower than this makes us stand back for as long as it took.
        private const val SLOW_PASS_MS = 60L
        // ...and however slow a pass was, never go quiet for longer than this. A backoff is
        // a courtesy to the main thread, not a licence to stop watching the screen.
        private const val MAX_BACKOFF_MS = 2_000L
        // The stall watchdog. The beat is cheap; the thresholds are what matter. WARN is
        // "something is wrong and it should be in the log"; PANIC is "the cover's own buttons
        // have been undeliverable for long enough that the phone is unusable, take it down".
        private const val HEARTBEAT_MS = 2_000L
        private const val STALL_WARN_MS = 3_000L
        private const val STALL_PANIC_MS = 8_000L
        // An event older than this describes a screen the user has already left. Reading it
        // is work spent on the past, and ACTING on it is how a block lands on the wrong app.
        private const val STALE_EVENT_MS = 1500L
        private const val ADDRESS_BAR_DEPTH = 25
        private const val GO_BACK_DEBOUNCE_MS = 700L
        // A page must stay blocked this long before Back/Leave writes a PERMANENT
        // ban for it - long enough to outlast the stale-content flicker while
        // navigating back through history, so innocent previous pages aren't banned.
        private const val BAN_SETTLE_MS = 1500L

        // "Leave" on a browser: hand it a fresh tab, give it a moment to take the
        // intent, then go Home. (The background-activity-start restriction added in
        // Android 10 doesn't bite us - holding SYSTEM_ALERT_WINDOW exempts the app.)
        private val BROWSER_HOME_URL = AppConfig.BROWSER_HOME_URL
        private const val BROWSER_HOME_DELAY_MS = 250L

        // Leaving a blocked page. The cover must outlive the Home animation, or the page it
        // is covering flashes back into view on the way out - see leaveBlockedPage.
        // How long a host read off an address bar stays usable as a fallback once the bar
        // has stopped being readable. Long enough to ride out a navigation, far short of
        // "still the page you are on" minutes later.
        private const val HOST_FALLBACK_MS = 10_000L
        private const val LEAVE_REDIRECT_MS = 220L   // let the browser take the new-tab intent
        private const val LEAVE_HIDE_MS = 900L       // cover comes off only once Home is in front

        // How often blocking may re-evaluate. Short: this is the gap between a banned thing
        // being on screen and the cover landing on it. Logging keeps the slower MIN_INTERVAL_MS.
        private const val BLOCK_INTERVAL_MS = 200L

        // How often the night guard re-checks off the back of a sensor reading. Roughly once a
        // second: fast enough that lying back down covers the screen almost at once, slow
        // enough that it isn't running a window query on every accelerometer sample.
        private const val GUARD_EVAL_MS = 900L

        // Dopamine tracking. A "segment" longer than this means we missed the screen going
        // off, so it is discarded rather than banked as a phantom marathon session.
        private const val MAX_SEGMENT_SECONDS = 2 * 60 * 60L
        // Scrolls/taps are counted in memory and written out in batches of this size.
        private const val INTERACTION_FLUSH = 25
        private val DOMAIN_BLOCK_MS = AppConfig.DOMAIN_BLOCK_MS   // whole-domain block length

        private val IGNORED_PACKAGES = AppConfig.IGNORED_PACKAGES

        // Still allowed while the night guard is up (lying down / in the dark).
        private val NIGHT_GUARD_ALLOWED get() = AppConfig.NIGHT_GUARD_ALLOWED_SUBSTRINGS

        private val NOT_LOGGED_PACKAGES = AppConfig.NOT_LOGGED_PACKAGES

        private val ADDRESS_BAR_HINTS = AppConfig.ADDRESS_BAR_HINTS

        private val HOST_PATTERN = Regex("""(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})(?:[/?#]\S*)?""", RegexOption.IGNORE_CASE)

        private const val MAX_URL_CHARS = 2048

        // Address-bar view IDs (Firefox only - see AppConfig). The generic hints below
        // are the backup used by isAddressBar.
        private val ADDRESS_BAR_IDS = AppConfig.ADDRESS_BAR_IDS

        private val ADDRESS_BAR_ID_HINTS = AppConfig.ADDRESS_BAR_ID_HINTS

        // Diagnostics: true logs a "NODE DUMP" row for the browsers below. Turn OFF
        // once you've found the URL node.
        private const val DEBUG_DUMP_NODES = false
        private const val DUMP_INTERVAL_MS = 1500L
        private val BROWSER_DEBUG_PACKAGES = AppConfig.BROWSER_DEBUG_PACKAGES

    }
}
