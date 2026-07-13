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

    private var breathing: BreathingOverlay? = null
    private var lastForegroundPkgForBreathing: String? = null

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
    private var armedAt = 0L   // when the current blocked page first armed; used to "settle" before banning

    private var lastPackage: String? = null
    private var lastHost: String? = null
    private var lastUrl: String? = null
    private var lastFullUrl: String? = null

    // App-level block state. While true, the cover is OWNED by the recheck loop
    // below: it is kept up / taken down based on what is actually in the
    // foreground, never by individual events (events flicker; window state doesn't).
    private var appBlockActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var keyboardPackages: Set<String> = emptySet()

    private var lastDumpAt = 0L

    /**
     * Runs every RECHECK_MS while an app block is up. Looks at the real window
     * state: still in a blocked app -> keep the cover; an allowed app is genuinely
     * in front -> drop it; can't tell (mid-animation) -> keep it and try again.
     */
    private val recheck = object : Runnable {
        override fun run() {
            if (!appBlockActive) return
            val pkg = currentForegroundPackage()
            val blocked = appBlockReason(pkg)
            when {
                blocked != null -> showAppBlock(blocked, pkg!!) // keeps cover + reposts
                pkg != null -> {
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

    private val greyTick = object : Runnable {
        override fun run() {
            flushGrey()
            val t = greyTarget
            if (t != null) {
                // Enforce even while the app sits idle with no events.
                if (greyIsApp && GreyUsage.isOverLimit(this@PageMonitorAccessibilityService, t)) {
                    showAppBlock(
                        "That's your ${GreyUsage.LIMIT_MIN} min for this hour - it'll open again soon", t)
                }
                mainHandler.postDelayed(this, GREY_TICK_MS)
            }
        }
    }


    /**
     * Built-in guards for in-app screens we never want reachable. Currently:
     * Firefox Focus's privacy settings, where the "stealth" option blocks
     * screenshots and would blind the screen capture.
     *
     * Only ever called off the web (host == null), so a web page that merely
     * mentions the keyword can't trip it. To add another guarded screen, copy the
     * if-block and change the package / keywords.
     */
    private fun appScreenBlock(packageName: String, title: String?, content: String?): String? {
        val t = title?.lowercase().orEmpty()
        val c = content?.lowercase().orEmpty()
        for (g in AppConfig.SCREEN_GUARDS) {
            if (g.pkg != packageName) continue
            val hitTitle = g.titleKeywords.any { it in t }
            val hitContent = g.contentKeywords.any { it in c }
            if (hitTitle || hitContent) return g.reason
        }
        return null
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
    private fun pageMatches(page: AppConfig.PageMatch): Boolean {
        val root = rootInActiveWindow ?: return false
        return page.mustContain.all { needle ->
            root.findAccessibilityNodeInfosByText(needle).isNotEmpty()
        }
    }

    /** True when the Settings screen in front matches any uninstall-guard page. */
    private fun isOurUninstallScreen(): Boolean =
        AppConfig.UNINSTALL_GUARD_PAGES.any { pageMatches(it) }



    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        breathing = BreathingOverlay(this)
        BlockRules.load(this)
        AppBlocklist.refresh(this)
        loadKeyboardPackages()
        DomainBlocklist.warmUp(this)
        startGreyscaleWatch()
    }

    // ── Greyscale enforcement ───────────────────────────────────────────────────────
    private var greyscaleSensor: SensorMonitor? = null
    private var greyscaleApplied = false      // true only while WE hold greyscale on
    private var lastGreyEval = 0L

    private fun startGreyscaleWatch() {
        if (!AppConfig.GREYSCALE_IN_STRICT) return
        val m = SensorMonitor(this)
        m.onUpdate = { updateGreyscale() }
        greyscaleSensor = m
        m.start(slow = true)      // light polling rate - this runs for the whole session
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
        try {
            handleEvent(event)
        } catch (t: Throwable) {
            android.util.Log.e("PageMonitor", "event handling failed", t)
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName) return
        // Uninstall guard: while the lock is on, bounce out of our own
        // App-info / uninstall / "deactivate admin" pages in Settings.
        if (UninstallGuard.isAdminActive(this) && packageName == "com.android.settings") {
            if (isOurUninstallScreen()) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
        }
        // Optional user lock: keep them off the Colour-correction page so they can't turn
        // greyscale back off. Only while greyscale is actually on, so they can never lock
        // themselves out of turning it ON in the first place.
        if (packageName == "com.android.settings" &&
            Greyscale.isLockColorPage(this) && Greyscale.isOn(this) &&
            pageMatches(AppConfig.COLOR_CORRECTION_PAGE)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        if (packageName in IGNORED_PACKAGES) return
        // Keyboards pop their own window over the app and fire events under their
        // own package; treating that as "the foreground app changed" is what made
        // the cover flicker. Skip them completely.
        if (packageName.lowercase() in keyboardPackages || isKeyboardWindow(event)) return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            RecentAppsTracker.onForeground(packageName)
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

        // ---- Breathing gate: a calming pause when a chosen app opens ----
        // Fire only when the foreground app actually changes, so it triggers on a
        // fresh open but never while you're already inside the app. How OFTEN it may
        // fire is BreathingGate's call: every open in super hardcore, otherwise only
        // the first open of that app each day (which is what keeps it out of the way
        // of 2FA codes).
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            packageName != lastForegroundPkgForBreathing
        ) {
            if (breathing?.isShowing == true) breathing?.hide()   // left the gated app: drop it
            lastForegroundPkgForBreathing = packageName
            if (packageName in BREATHING_APPS && overlay?.isShowing != true &&
                BreathingGate.shouldBreathe(this, packageName)) {
                BreathingGate.markBreathed(this, packageName)
                val label = appLabelFor(packageName)
                breathing?.show(
                    appLabel = label,
                    onContinue = { breathing?.hide() },
                    onDontWant = { breathing?.hide(); exitToHome(packageName) },
                )
                return
            }
        }


        // An allowed app fired a real window change while an app block is up
        // (e.g. user pressed Home): verify against actual window state right away.
        if (appBlockActive && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            mainHandler.removeCallbacks(recheck)
            mainHandler.post(recheck)
        }

        val now = System.currentTimeMillis()

        // Blocking now runs on a MUCH shorter leash than logging. The old single 700ms gate
        // ran both, and it is why a banned word gave you a clear look at the results before
        // the cover landed, and why a page you'd already banned took a beat to be covered on
        // reopen. A window CHANGE (new page, app resumed) never waits at all.
        val stateChange = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!stateChange && now - lastBlockEvalAt < BLOCK_INTERVAL_MS) return
        lastBlockEvalAt = now

        // Known-safe app (maps, messaging, banking, utilities…): no public feed and
        // no arbitrary web content worth scanning - skip the read/scan/screenshot/log
        // entirely to save battery and CPU.
        if (Whitelist.isSafeApp(this, packageName)) return

        val root = rootInActiveWindow ?: return

        if (DEBUG_DUMP_NODES && packageName in BROWSER_DEBUG_PACKAGES &&
            now - lastDumpAt > DUMP_INTERVAL_MS
        ) {
            lastDumpAt = now
            dumpBrowserNodes(root, packageName)
        }

        // The bar text is the full address (URL or search), as a screen reader sees
        // it. The host is derived from it purely for blocking.
        val barText = readAddressBarText()
        val host = barText?.let { hostInText(it) }

        if (packageName != lastPackage) {
            lastPackage = packageName
            lastHost = null
            lastUrl = null
            lastFullUrl = null
        }
        // A host change makes any captured full URL stale.
        if (host != null && host != lastHost) lastFullUrl = null
        if (host != null) lastHost = host
        if (barText != null) lastUrl = barText
        readFocusedFullUrl(host)?.let { lastFullUrl = it }   // fills in path if user taps the bar

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

        // Log the content score on every web page so we can see what each one scored
        // while tuning - shows as a prefix on the log row, e.g. "[score 18] cute puppies".
        val pageScore = if (host != null && !Whitelist.isSafeDomain(this, host))
            BorderlineScorer.score(rawTitle, lastFullUrl ?: lastUrl, text)?.score else null
        val loggedTitle = if (pageScore != null) "[score $pageScore]  ${title.orEmpty()}".trim()
                          else title

        MonitorStore.record(
            this,
            MonitorEntry(
                timestamp = now,
                kind = MonitorEntry.KIND_PAGE,
                packageName = packageName,
                title = loggedTitle,
                domain = lastHost,
                url = lastFullUrl ?: lastUrl,
                text = text,
            ),
        )
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
        )
        mainHandler.removeCallbacks(recheck)
        mainHandler.postDelayed(recheck, RECHECK_MS)
    }


    /** Reason an app should currently be covered: a blocked browser, or a timed content block. */
    private fun appBlockReason(pkg: String?): String? {
        if (LoosenWindow.isActive(this)) return null          // loosen window: apps allowed
        if (Lockdown.isActive(this) && pkg != packageName && !Lockdown.isAllowed(pkg)) {
            return "Locked down - ride out the urge"
        }
        if (LoosenWait.isActive(this) && pkg != packageName && !LoosenWait.isAllowed(pkg)) {
            return "Waiting it out - stay off other apps for now"
        }
        when (AppRules.appTier(this, pkg)) {                   // user "Report an app" rules
            AppRules.BLOCK -> return "Blocked app"
            AppRules.GREY ->
                if (pkg != null && GreyUsage.isOverLimit(this, pkg.lowercase()))
                    return "That's your ${GreyUsage.LIMIT_MIN} min for this hour - it'll open again soon"
        }
        AppBlocklist.blockedReason(pkg)?.let { return "Blocked app: $it" }
        return AppTimedBlock.reasonIfBlocked(this, pkg)
    }

    private fun blockSettled(): Boolean =
        System.currentTimeMillis() - armedAt >= BAN_SETTLE_MS

    private fun escalateWebBlock(host: String, pageUrl: String?) {
        val isSearch = BlockRules.isSearchEngineHost(host)
        val pageRule = BlockRules.pageRuleFor(pageUrl)
        when {
            pageRule != null -> BlockRules.add(this, pageRule)   // block this exact page / search term
            !isSearch        -> BlockRules.add(this, host)       // non-search, no path -> block host
            // search engine with no term -> add nothing (never ban a whole search engine)
        }
        // Domain strikes never accrue for search engines.
        if (!isSearch) {
            BlockEscalation.recordWebBlock(this, host)?.let { domain ->
                BlockRules.addTimed(this, domain, DOMAIN_BLOCK_MS)
            }
        }
    }

    private fun appLabelFor(pkg: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            pkg
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
        mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, LEAVE_REDIRECT_MS)
        mainHandler.postDelayed({
            controller.hide()
            shownBlockHost = null
            shownBlockUrl = null
            leaving = false
        }, LEAVE_HIDE_MS)
    }

    private fun exitToHome(pkg: String? = null, redirectBrowser: Boolean = false) {
        val target = pkg ?: lastPackage
        val redirecting = redirectBrowser && target != null && AppBlocklist.isBrowser(target)
        if (redirecting) sendBrowserHome(target!!)
        // Only wait if we actually handed the browser an intent; otherwise go straight out.
        if (redirecting) mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, BROWSER_HOME_DELAY_MS)
        else performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Point [pkg] at a blank page, so it is no longer parked on the blocked one. Fired as
     * a normal VIEW intent at that browser specifically; if the browser refuses it, we
     * just go Home and nothing is lost.
     */
    private fun sendBrowserHome(pkg: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(BROWSER_HOME_URL)).apply {
                    setPackage(pkg)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (t: Throwable) {
            android.util.Log.w("PageMonitor", "could not send $pkg to a blank page", t)
        }
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
        val host = rawHost ?: lastHost.takeIf { AppBlocklist.isBrowser(packageName) }

        // Tab switcher / "jump back in" previews expose a tab's URL but no readable PAGE TEXT -
        // you're looking at a thumbnail, not visiting the page. A real visit always has text.
        //
        // This USED to null out the host entirely, which disabled ALL web blocking until the
        // page's text had loaded - that is why reopening a browser sat on a page you'd already
        // banned left it readable for several seconds. Now it only gags the HEURISTIC SCORER
        // (the one that genuinely needs text to judge, and the one that was false-positiving on
        // the tab grid). An EXPLICIT match - your own ban list, or a known adult domain - fires
        // off the URL alone, instantly, text or no text.
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

        val baseReason = when {
               appGuard != null -> appGuard
               host != null && DomainBlocklist.isBlocked(host) -> "Adult site (blocklist): $host"
               rule != null -> describeRule(rule)
               host != null && AppRules.hostTier(this, host) == AppRules.GREY &&
                   GreyUsage.isOverLimit(this, host) ->
                       "That's your ${GreyUsage.LIMIT_MIN} min for this hour - $host opens again soon"
               host != null && Whitelist.isSafeDomain(this, host) -> null   // trusted domain: skip heuristic
               // The heuristic - and ONLY the heuristic - waits for real page text.
               contentReady && (host != null || AppBlocklist.isBrowser(packageName)) ->
                   BorderlineScorer.evaluate(title, url, content)?.reason
               else -> null
           }

        if (baseReason != null) {
            val freshShow = !controller.isShowing
            if (freshShow) {
                val blockScore = if (host != null)
                    BorderlineScorer.score(title, url, content)?.score else null
                BlockEventLog.recordWeb(this, packageName, host, url, baseReason, blockScore)
            }

            // The "you went BACK..." line is a RESPONSE TO A TAP, never ambient commentary.
            // It used to be recomputed on every re-evaluation, so it appeared (and stuck) on
            // pages the user had never pressed Back on. Now it is only produced when awaiting-
            // BackResult says the user actually tapped the link, and it is flashed - see below.
            val backStatus = if (!freshShow && awaitingBackResult) {
                awaitingBackResult = false
                if (host != null && host == shownBlockHost)
                    "Still the SAME blocked page. Press Back again, or go home."
                else
                    "That's a DIFFERENT page - but it's blocked too. Press Back again, or go home."
            } else {
                null
            }
            // A DIFFERENT page just became the blocked one -> restart the settle timer.
            if (url != shownBlockUrl) armedAt = System.currentTimeMillis()
            shownBlockHost = host
            shownBlockUrl = url
            val reason = baseReason

            if (freshShow) {
                // Every NEW block screen (page rules included, not just images) now
                // counts toward the rapid limit: 5 in 10 min on one app -> 90 min.
                RapidBlockMonitor.record(packageName)?.let { penaltyMs ->
                    AppTimedBlock.blockFor(
                        this, packageName, penaltyMs,
                        "App blocked for ${RapidBlockMonitor.PENALTY_LABEL} (too many blocks)",
                    )
                    showAppBlock(
                        AppTimedBlock.reasonIfBlocked(this, packageName) ?: "App blocked",
                        packageName,
                    )
                    return
                }
            }

            controller.show(
                reason = reason,
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

    /** Turn a raw block rule into readable wording: a dot means a site, otherwise a keyword. */
    private fun describeRule(rule: String): String =
        if ('.' in rule) "Blocked site: $rule" else "Blocked keyword: \"$rule\""

    /** The package of the application window that is actually in front, or null. */
    private fun currentForegroundPackage(): String? {
        try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (!window.isActive && !window.isFocused) continue
                val pkg = window.root?.packageName?.toString() ?: continue
                if (isNoise(pkg)) continue
                return pkg
            }
        } catch (_: Throwable) {
            // fall through to the fallback below
        }
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return null
        return if (isNoise(pkg)) null else pkg
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
     */
    private fun readAddressBarText(): String? {
        val candidates = mutableListOf<String>()
        rootInActiveWindow?.let { collectAddressCandidates(it, depth = 0, out = candidates) }
        for (window in windows) {
            window.root?.let { collectAddressCandidates(it, depth = 0, out = candidates) }
        }
        return candidates.distinct().maxByOrNull { urlRichness(it) }?.take(MAX_URL_CHARS)
    }

    private fun collectAddressCandidates(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableList<String>,
    ) {
        if (node == null || depth > ADDRESS_BAR_DEPTH) return
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

        val nodeText = node.text?.toString()?.trim()
        if (!nodeText.isNullOrEmpty()) {
            out.append(nodeText).append('\n')
        }

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, depth + 1)
        }
    }

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
        rootInActiveWindow?.let { collectWebViewText(it, depth = 0, out = out, insideWeb = false) }
        return out.toString().trim().take(MAX_TEXT_CHARS).takeIf { it.isNotBlank() }
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
            if (node == null || depth > ADDRESS_BAR_DEPTH || found != null) return
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
        rootInActiveWindow?.let { walk(it, 0) }
        return found
    }

    override fun onInterrupt() {
        // Nothing to clean up.
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(recheck)
        greyscaleSensor?.stop(); greyscaleSensor = null
        if (greyscaleApplied) { Greyscale.setEnabled(this, false); greyscaleApplied = false }
        overlay?.hide()
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
        private const val LEAVE_REDIRECT_MS = 220L   // let the browser take the new-tab intent
        private const val LEAVE_HIDE_MS = 900L       // cover comes off only once Home is in front

        // How often blocking may re-evaluate. Short: this is the gap between a banned thing
        // being on screen and the cover landing on it. Logging keeps the slower MIN_INTERVAL_MS.
        private const val BLOCK_INTERVAL_MS = 200L
        private val DOMAIN_BLOCK_MS = AppConfig.DOMAIN_BLOCK_MS   // whole-domain block length

        private val IGNORED_PACKAGES = AppConfig.IGNORED_PACKAGES

        // Apps that get a calming breathing pause each time they're opened.
        private val BREATHING_APPS = AppConfig.BREATHING_APPS

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
