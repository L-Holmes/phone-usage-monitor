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
// AppConfig  -  THE ONE PLACE TO EDIT LISTS & SETTINGS
// =====================================================================================
// Everything here is compile-time (no file is parsed on the device - fastest possible,
// and a typo fails the build instead of silently breaking a list at runtime).
// Grouped by purpose; app entries map a friendly name -> the package our monitor sees.
// Per-page block TEXT (e.g. specific Settings screens) deliberately stays in source.
object AppConfig {

    // === Mode → permissions ==========================================================
    // The app's modes and what each allows. Display names are read from here so the
    // rest of the app stays consistent. (Behavioural wiring beyond names/breathing/
    // flag-threshold is still in code; this block is the dial to grow into.)
    //
    // ┌───────────────────────────────────────────────────────────────────────────────┐
    // │  AI / MAINTAINER - READ THIS BEFORE CHANGING ANY MODE BEHAVIOUR               │
    // │                                                                               │
    // │  The plain-English rules the user reads on the "What each mode does" screen   │
    // │  (Main.kt -> showModeRules) now live in res/values/strings.xml as the         │
    // │  string-arrays mode_<id>_rules and the always_on_* strings (so they can be    │
    // │  translated). This ModeSpec holds ONLY the behaviour flags.                   │
    // │                                                                               │
    // │  Whenever you change what a mode does - here, or anywhere in the code that    │
    // │  branches on Mode.current()/Mode.isStrict()/Mode.isSuperHardcore()/spec() -   │
    // │  you MUST update that mode's mode_<id>_rules in strings.xml in the same        │
    // │  change. A rule the user can't see is a rule they'll feel blindsided by.      │
    // │  Keep each line short, concrete and in plain English, never internal names.   │
    // └───────────────────────────────────────────────────────────────────────────────┘
    data class ModeSpec(
        val id: String,
        val displayName: String,
        val breathingOn: Boolean,      // show the breathing pause on "breathing apps" at all
        val breathEveryOpen: Boolean,  // true = every open; false = first open of each day only
        val greyscale: Boolean,        // let the greyscale watcher grey the screen in this mode
        /**
         * The NIGHT GUARD: block every non-essential app while the phone says you are lying
         * down. [flagLyingDown] and [nightGuardLuxBelow] are its triggers - though as of
         * 2026-08-24 no mode sets the light one any more, so lying down is the whole guard.
         */
        val nightGuard: Boolean = false,
        // NOT WIRED INTO THE SCORER. flagThreshold is dev-console display only - the live
        // scorer uses one flat FilterTuning.THRESHOLD for every mode. Do NOT describe it in
        // the mode_<id>_rules strings until it is actually wired, or the screen becomes a lie.
        val flagThreshold: Int,
        // These two ARE live, but only as the night-guard's triggers (see nightGuard above).
        val flagLyingDown: Boolean = false,
        /** Lux at or below which the guard's DARK trigger fires - null means light NEVER
         *  trips the guard in this mode.
         *
         *  IT IS NULL IN EVERY MODE NOW (2026-08-24). The retreat went: DULL -> DARK
         *  (2026-07-15), then out of Strict (2026-08-01), and now out of Super hardcore too.
         *  "Not in the dark" was the wrong rule even at 15 lux: the sensor is on the front of
         *  the phone, so a hand over it, a pocket, a face close to the screen or a dim room
         *  someone is entitled to be in all read as darkness, and the punishment for a bad
         *  reading is a phone that will not open. Lying down is the trigger that actually
         *  describes the behaviour we are aiming at, and it survives on its own.
         *
         *  To bring it back for a mode: nightGuardLuxBelow = NIGHT_GUARD_LUX below. Nothing
         *  else needs changing - the block reason strings (br_night_dark) and the dev-console
         *  row are still wired up and go live again with it. */
        val nightGuardLuxBelow: Float? = null,
    )
    // The lux level the night guard's DARK trigger USED to fire at, kept as the value to
    // restore with (see nightGuardLuxBelow - no mode sets it now). 15 lux is genuinely dark -
    // lights off, curtains drawn - so an ordinary lamp-lit room never tripped it.
    const val NIGHT_GUARD_LUX = 15f

    val MODES: List<ModeSpec> = listOf(
        ModeSpec(id = "off", displayName = "Off",
            breathingOn = false, breathEveryOpen = false, greyscale = false,
            nightGuard = false,
            flagThreshold = 0, flagLyingDown = false),
        ModeSpec(id = "relaxed", displayName = "Relaxed",
            breathingOn = false, breathEveryOpen = false, greyscale = false,
            nightGuard = false,
            flagThreshold = 60, flagLyingDown = false),
        ModeSpec(id = "strict",  displayName = "Strict",
            breathingOn = true,  breathEveryOpen = false, greyscale = true,
            // NO night guard in Strict any more (2026-08-01). First the light trigger kept
            // catching ordinary dim evening rooms (2026-07-15 DULL->DARK), then the lying-
            // down trigger proved too harsh as well - both now live in Super hardcore only.
            // Lying down in Strict still greys the screen (greyscale above), it just never
            // BLOCKS.
            nightGuard = false,
            flagThreshold = 45, flagLyingDown = false),
        ModeSpec(id = "superhardcore", displayName = "Super hardcore",
            breathingOn = true,  breathEveryOpen = true, greyscale = true,
            // Night guard: LYING DOWN ONLY. The light trigger ("Not in the dark") came out
            // here too on 2026-08-24 - see nightGuardLuxBelow for why. This was the last mode
            // that had it, so the DARK half of the guard is now off everywhere.
            nightGuard = true,
            flagThreshold = 30, flagLyingDown = true),
    )

    // === Night guard: what still opens while lying down / in the dark =================
    // Matched as SUBSTRINGS of the package name. Keep this to genuine essentials - the guard
    // is worthless if the thing you actually reach for is on it. WhatsApp is deliberately NOT
    // here (it is a scroll surface like any other); the dialer and SMS are, so you can still
    // be contacted in an emergency.
    // Data now in assets/filter/apps_night_guard.txt (FilterData).
    val NIGHT_GUARD_ALLOWED_SUBSTRINGS: List<String> get() = FilterData.lines("apps_night_guard.txt")

    // Hysteresis for the night guard's DARK trigger. Dormant with the trigger itself (no mode
    // sets nightGuardLuxBelow now), and kept for the same reason the threshold is: it blocks at
    // or below NIGHT_GUARD_LUX, but does not release until the light is comfortably past it
    // (threshold x this). Without the gap, a reading hovering on the threshold - which is
    // exactly what happens when the cover's own glow hits the sensor - makes the block flicker.
    const val NIGHT_GUARD_LIGHT_RELEASE = 1.6f
    fun modeName(id: String): String = MODES.firstOrNull { it.id == id }?.displayName ?: id

    // ALWAYS-ON rules + per-mode rule bullets now live in res/values/strings.xml
    // (always_on_* and mode_*_rules), resolved at display time in Main.showModeRules.
    // ModeSpec keeps only the behaviour flags; the user-facing prose is the string master.

    // === Ambient light (from the phone's light sensor, in lux) =======================
    // Thresholds are rough, drawn from common lighting references: a dark room (lights off,
    // curtains drawn) sits under ~20 lux, a dim lamp-lit evening room ~20-80, ordinary indoor
    // lighting ~80-400, and bright indoor / daylight above that.
    enum class LightLevel { DARK, DULL, NORMAL, BRIGHT }
    const val LIGHT_DULL_MAX = 20f      // < 20 lux  -> DARK
    const val LIGHT_NORMAL_MAX = 80f    // 20-80     -> DULL
    const val LIGHT_BRIGHT_MAX = 400f   // 80-400    -> NORMAL ; >=400 -> BRIGHT
    fun lightLevel(lux: Float): LightLevel = when {
        lux < LIGHT_DULL_MAX -> LightLevel.DARK
        lux < LIGHT_NORMAL_MAX -> LightLevel.DULL
        lux < LIGHT_BRIGHT_MAX -> LightLevel.NORMAL
        else -> LightLevel.BRIGHT
    }
    // A LightLevel is "at or below" another when it's the same or darker.
    fun lightAtOrBelow(level: LightLevel, floor: LightLevel): Boolean = level.ordinal <= floor.ordinal

    // === Lying-down heuristic (from the accelerometer / gravity direction) ===========
    // Uses the normalised gravity vector (gx, gy, gz). Calibrated against real test data:
    //   * on the LEFT side  -> gx strongly positive (~+0.87..+0.99)
    //   * on the RIGHT side -> gx strongly negative (~-0.87..-0.99)
    //   * on the BACK       -> gx near 0 AND the screen faces DOWN toward the face (gz strongly
    //                          negative). Screen facing UP (gz positive) is looking-down, NOT lying.
    // Tune these on the sensor debug page. (If left/right read reversed on a given device,
    // flip the sign test in SensorMonitor.)
    const val SIDE_GX = 0.55f   // |gx| at/above this  -> lying on a side
    const val BACK_GZ = 0.55f   // gz at/below -this (screen face-down) + not on a side -> lying on back

    // === Greyscale enforcement (see Greyscale.kt) ====================================
    // Drains the colour out of the whole screen in strict mode to kill the visual pull.
    // A normal app CANNOT switch system greyscale on itself - the user enables it once in
    // Settings (the in-app "Grayscale setup" screen deep-links them there). The app reads
    // the state to verify/nudge. Auto-toggle only happens on privileged builds.
    const val GREYSCALE_IN_STRICT = true         // master switch for the feature
    const val GREYSCALE_ONLY_WHEN_LYING = true   // true: only while lying down; false: whole strict session

    // === Leaving a blocked browser ===================================================
    // Where a browser is sent when the user taps "Go to home screen" on a block cover. The
    // point is to get the browser OFF the blocked page, so REOPENING it lands on a fresh
    // blank tab instead of straight back on it - the behaviour Stay Focused has.
    //
    // "about:blank" is what we want and what we try first. The catch: a browser only accepts
    // an external ACTION_VIEW intent for the schemes it declares an intent-filter for, and
    // that is normally just http/https. If Firefox ignores about: then the intent is dropped
    // and you land back on the blocked page - so sendBrowserHome checks whether the browser
    // will actually take it, and falls back to BROWSER_HOME_FALLBACK_URL if it won't.
    //
    // If you test and the new tab ISN'T blank, that means the fallback fired: set
    // BROWSER_HOME_URL to the fallback value below and be done with it.
    const val BROWSER_HOME_URL = "about:blank"

    // Only used if the browser refuses the URL above. Must be http/https.
    const val BROWSER_HOME_FALLBACK_URL = "https://www.google.com"

    // === Uninstall / device-admin passcode ==========================================
    const val UNINSTALL_PASSCODE = "666666"

    // === Developer mode =============================================================
    // When true, the home page shows a "Dev tools" button (block-rule tools, log, etc.).
    // Flip to false for a clean end-user build.
    const val DEV_MODE = true

    // === Safe apps (friendly name -> package) ========================================
    // No public scrolling feed and no arbitrary adult content. The monitor SKIPS these
    // entirely - no screenshot, scan, or log - to save battery/CPU.
    // Data now lives in assets/filter/apps_safe.txt (edit there); loaded via FilterData.
    val SAFE_APPS_BY_NAME: Map<String, String> get() = FilterData.map("apps_safe.txt")

    val SAFE_APPS: Set<String> get() = SAFE_APPS_BY_NAME.values.toSet()

    // === Greylist apps (friendly name -> package) ====================================
    // Social / short-form apps that MAY contain bad stuff. Never whitelisted; defaulted
    // to the time-limited GREY tier unless the user overrides.
    // Data now lives in assets/filter/apps_greylist.txt; loaded via FilterData.
    val GREYLIST_APPS_BY_NAME: Map<String, String> get() = FilterData.map("apps_greylist.txt")
    val GREYLIST_APPS: Set<String> get() = GREYLIST_APPS_BY_NAME.values.toSet()

    // === Short-form / feed patterns (the toggleable category) ========================
    // Page rules where only the feed should die; host rules where the whole thing is feed.
    val SHORT_FORM_PATTERNS: List<String> = listOf(
        "youtube.com/shorts", "instagram.com/reels", "facebook.com/reel",
        "snapchat.com/spotlight", "tiktok.com", "reddit.com/r/popular",
    )

    // === Temptations (the categories on the Temptations tab) =========================
    // Sexual arousal is NOT here - it has its own bespoke, much heavier flow (breathing +
    // supervised loosen + relapse reporting). Everything below shares ONE simple page
    // (Main.kt -> showTemptation), which is deliberately small: see what it is, ride the
    // urge out, block what feeds it, log a slip. Nothing else. Adding a category is a new
    // entry here and nothing else - no new screen code.
    //
    // AI / MAINTAINER: keep `covers` in the user's own plain language. It is what lets
    // someone recognise themselves on the page; it is not a feature list.
    data class TemptationSpec(
        val id: String,
        /** BlockRules entries the "block what feeds this" switch adds/removes. */
        val blockPatterns: List<String> = emptyList(),
        /** Packages the same switch drops to the GREY tier (time-limited, not banned). */
        val greyApps: List<String> = emptyList(),
        /** Packages the same switch BANS outright (AppRules.BLOCK - for things like game
         *  apps, where a time limit is just a shorter session, not a deterrent). */
        val blockApps: List<String> = emptyList(),
        /** The Phone Checking page's friction measures (see CheckingGuard) - the one
         *  category with nothing to block, because the pull is the device itself. */
        val checkingMeasures: Boolean = false,
        // Display text (title / subtitle / covers / insteadOf) now lives in
        // res/values/strings.xml as temptspec_<id>_* (keyed by id) so it can be translated.
        // Resolved at display time in Main.kt (temptTitle/temptSubtitle/temptCovers/temptInsteadOf).
    )

    val TEMPTATIONS: List<TemptationSpec> = listOf(
        TemptationSpec(
            id = "scrolling",
            blockPatterns = SHORT_FORM_PATTERNS,
            greyApps = listOf(
                "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go",
                "com.instagram.android", "com.reddit.frontpage", "com.snapchat.android",
            ),
        ),
        TemptationSpec(
            id = "binge",
            blockPatterns = listOf(
                "youtube.com", "netflix.com", "hulu.com", "disneyplus.com", "primevideo.com",
                "twitch.tv",
            ),
            greyApps = listOf(
                "com.google.android.youtube", "com.netflix.mediaclient", "tv.twitch.android.app",
                "com.disney.disneyplus", "com.amazon.avod.thirdpartyclient",
            ),
        ),
        TemptationSpec(
            id = "comparison",
            blockPatterns = listOf(
                "instagram.com", "facebook.com", "x.com", "twitter.com", "tiktok.com",
                "snapchat.com", "pinterest.com", "tumblr.com", "threads.net", "linkedin.com/feed",
            ),
            greyApps = listOf(
                "com.instagram.android", "com.instagram.lite", "com.facebook.katana",
                "com.facebook.lite", "com.twitter.android", "com.pinterest",
                "com.snapchat.android", "com.tumblr",
            ),
        ),
        TemptationSpec(
            id = "checking",
            // Nothing to block: the pull here is the device itself, not a site. The page
            // offers the CheckingGuard friction measures (and the 30-minute lockdown) instead.
            checkingMeasures = true,
        ),
        TemptationSpec(
            id = "news",
            blockPatterns = listOf(
                "news.google.com", "cnn.com", "foxnews.com", "bbc.co.uk/news",
                "theguardian.com", "dailymail.co.uk", "reddit.com/r/worldnews",
                "reuters.com", "apnews.com", "news.sky.com", "nytimes.com",
                "washingtonpost.com", "independent.co.uk", "telegraph.co.uk", "aljazeera.com",
                "news.yahoo.com",
            ),
            // News APPS are banned outright, not time-limited - five minutes of doomscroll
            // is still a doomscroll. (Google's Discover feed itself lives inside the Google
            // app / launcher and can't be blocked without killing search - news.google.com
            // and the Google News app above are as close as we can get.)
            blockApps = listOf(
                "com.google.android.apps.magazines",     // Google News
                "bbc.mobile.news.ww", "com.cnn.mobile.android.phone",
                "com.bskyb.skynews.android", "com.dailymail.online", "com.guardian",
                "com.reuters.rna", "com.foxnews.android",
            ),
        ),
        TemptationSpec(
            id = "gaming",
            blockPatterns = listOf("poki.com", "crazygames.com", "miniclip.com", "coolmathgames.com"),
            // The common mobile games, banned outright while the switch is on. Grow freely -
            // a package that isn't installed is just never matched.
            blockApps = listOf(
                "com.king.candycrushsaga", "com.king.candycrushsodasaga",
                "com.supercell.clashofclans", "com.supercell.clashroyale", "com.supercell.brawlstars",
                "com.roblox.client", "com.mojang.minecraftpe", "com.kiloo.subwaysurf",
                "com.tencent.ig", "com.activision.callofduty.shooter", "com.epicgames.fortnite",
                "com.dts.freefireth", "com.mihoyo.genshinimpact", "com.innersloth.spacemafia",
                "com.moonactive.coinmaster", "com.scopely.monopolygo", "com.dreamgames.royalmatch",
                "com.miniclip.eightballpool",
            ),
        ),
        TemptationSpec(
            id = "shopping",
            blockPatterns = listOf(
                "amazon.com", "amazon.co.uk", "temu.com", "ebay.com", "aliexpress.com",
                "shein.com", "wish.com",
            ),
        ),
    )

    fun temptation(id: String): TemptationSpec? = TEMPTATIONS.firstOrNull { it.id == id }

    // === Trusted domains - NEVER blocked, by anything ================================
    //  Moved to assets/filter/domains_trusted.txt (2026-08-04) and heavily expanded. It
    //  used to be a Kotlin set of ~60 mostly-developer domains; it is now the deliberate
    //  answer to filter over-blocking, and it OUTRANKS every block we have including the
    //  ~550k downloaded list. See the long note at the top of that file for why crisis
    //  lines, sexual-health charities, LGBT+ support and porn-addiction RECOVERY sites are
    //  enumerated there by name.
    val SAFE_DOMAINS: Set<String> get() = FilterData.set("domains_trusted.txt")

    // === Browsers ====================================================================
    // We standardise on Firefox. ALLOWED_BROWSERS stay usable; everything in
    // BLOCKED_BROWSERS is funnelled away so users land on Firefox.
    // Data now lives in assets/filter/browsers_allowed.txt / browsers_blocked.txt (FilterData).
    val ALLOWED_BROWSERS: Set<String> get() = FilterData.set("browsers_allowed.txt")
    val BLOCKED_BROWSERS: Set<String> get() = FilterData.set("browsers_blocked.txt")

    // === Firefox address-bar detection (Firefox-only now) ============================
    // The view-ids the URL lives in. Trimmed to Firefox since that's the one browser
    // we support; generic hints stay as a safety net.
    val ADDRESS_BAR_IDS: List<String> = listOf(
        ":id/mozac_browser_toolbar_url_view",  // Firefox (old toolbar)
        "addressbar_url_box",                  // Firefox (new Compose toolbar)
    )
    val ADDRESS_BAR_ID_HINTS: List<String> = listOf("url", "address", "location")
    val ADDRESS_BAR_HINTS: List<String> = listOf(
        "search or enter", "search or type", "address bar", "enter address", "search address", "edit url",
    )
    // ═══════════════════════════════════════════════════════════════════════════════
    //  PAGE-TEXT BLOCK RULES  -  DEVS: this is THE place to edit page blocking.
    //  Every rule below matches a screen purely by text seen on it (from this app's own
    //  page-monitor log). If an Android/OEM update changes the wording and a page stops
    //  being blocked, open it on the phone, copy the on-screen text from the log, and
    //  update the strings here. Three kinds:
    //    • SCREEN_GUARDS        – in-app screens covered by the block overlay (Firefox).
    //    • UNINSTALL_GUARD_PAGES – Settings pages bounced to Home while the uninstall lock is on.
    //    • COLOR_CORRECTION_PAGE – the grayscale toggle page, optionally locked by the user.
    // ═══════════════════════════════════════════════════════════════════════════════

    // (1) In-app screens blocked by the overlay (matched off-web, host == null).
    // superHardcoreOnly: the guard is enforced only while the mode is Super hardcore.
    data class ScreenGuard(val pkg: String, val titleKeywords: List<String>, val contentKeywords: List<String>, val reason: String, val superHardcoreOnly: Boolean = false)

    // Toggle: when true, every Firefox screen that can clear browsing history is blocked
    // WHILE IN SUPER HARDCORE (the guards carry superHardcoreOnly). Flip to false to
    // remove the guards entirely.
    const val DISABLE_DELETE_HISTORY = true

    // Every Firefox build we guard. Add flavours here (e.g. "org.mozilla.firefox_beta")
    // and they inherit ALL the history-clearing blocks below automatically.
    val FIREFOX_PACKAGES: List<String> = listOf(
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
    )

    // ─── Firefox screens that can clear browsing history ──────────────────────────
    // DEVS: when a Firefox update changes a screen and it stops being blocked, open the
    // screen on the phone, find its row in this app's own monitor log, and refresh the
    // keywords below from the dump. Each entry lists the page's captured title/content
    // verbatim (2026-07-15, Firefox stable) so you can see exactly what was matched.
    // Matching: same package + ANY title keyword OR ANY content keyword (lowercase
    // substring), and only on non-web screens - a web page mentioning "history" can't trip it.
    val FIREFOX_HISTORY_CLEAR_SCREENS: List<Triple<String, List<String>, List<String>>> = listOf(
        // label                     title keywords                      content keywords
        // "Delete browsing data" settings screen. Dump: title "Delete browsing data";
        // content "Open tabs / Browsing history / Cookies and site data / Cached images
        // and files / Site permissions / Downloads / Delete browsing data".
        // (Also matches the old confirm dialog "…delete the selected browsing data".)
        Triple("Delete browsing data",
            listOf("delete browsing data", "delete the selected browsing data"),
            listOf("delete browsing data", "delete the selected browsing data")),
        // "Delete browsing data on quit" settings screen. Dump: title "Delete browsing
        // data on quit"; content "Automatically deletes browsing data when you select
        // \"Quit\" from the main menu…". Covered by the substrings above too, but kept
        // as its own entry so it survives if the wording of either screen drifts apart.
        Triple("Delete browsing data on quit",
            listOf("delete browsing data on quit"),
            listOf("delete browsing data on quit", "automatically deletes browsing data")),
        // The "Time range to delete" dialog (History screen → bin icon). Dump: title
        // "Time range to delete"; content "Removes history (including history
        // synchronised from other devices) / Last hour / Today and yesterday / Everything".
        Triple("Time range to delete",
            listOf("time range to delete"),
            listOf("time range to delete", "removes history (including history synchronised")),
        // The History screen itself - it has per-item delete and the bin icon, so the
        // whole screen is blocked. Dump: title "History"; content "History / Recently
        // closed tabs / N tabs / No history here". ("history" alone as a title keyword
        // is safe: these guards only run on Firefox's own non-web screens.)
        //
        // "recently closed tabs" is deliberately NOT a content keyword, even though it is
        // on this screen: it is ALSO an item in the tab switcher's overflow menu, and the
        // tab switcher must stay reachable (see the note in evaluateBlock - it is the only
        // way to close a bad tab). The title and "no history here" identify this screen
        // without reaching into the tab tray.
        Triple("History screen",
            listOf("history"),
            listOf("no history here")),
    )

    val SCREEN_GUARDS: List<ScreenGuard> = mutableListOf<ScreenGuard>().apply {
        add(ScreenGuard("org.mozilla.focus", listOf("privacy"), listOf("stealth"),
            "Firefox Focus stealth/privacy settings are blocked"))
        if (DISABLE_DELETE_HISTORY) {
            for (pkg in FIREFOX_PACKAGES) for ((label, titles, content) in FIREFOX_HISTORY_CLEAR_SCREENS) {
                add(ScreenGuard(pkg, titles, content,
                    "Clearing browsing history is disabled in Super hardcore ($label)",
                    superHardcoreOnly = true))
            }
        }
    }

    // ─── The image add-on's OWN page (its off-switch) ──────────────────────────────
    // Lives here rather than in the service, because this file is the one place page-text
    // rules are edited. Two ways in, so both are matched:
    //   • AMO serves the add-on's page under a locale segment - matched on the URL slug
    //     (BrowserSetup.EXTENSION_SLUG) or on the add-on's name on addons.mozilla.org;
    //   • Firefox's own add-on manager shows the same switches with no web URL at all -
    //     matched on the add-on's NAME plus one of the switches below.
    // Dump that produced these (2026-08-04, Firefox stable): "Distracting Image Monitor /
    // Enabled / Run in private browsing / Details / Permissions / Remove / Report".
    val EXTENSION_MANAGER_KEYWORDS: List<String> = listOf("run in private browsing", "remove")

    // (2) & (3) Settings pages matched by text. A page matches when EVERY string in
    // `mustContain` is present on screen (case-insensitive substring). Strings copied
    // verbatim from this app's monitor on a Samsung device.
    data class PageMatch(
        val label: String,
        val mustContain: List<String>,
        /**
         * When the BOUNCE is armed. Default: always. A page that is also the only route to
         * switching something ON must go unarmed until it IS on - otherwise the guard stops
         * the setup it exists to protect. (The match itself, and the recording that goes with
         * it, happen either way; this only decides whether we send the user home.)
         */
        val armedWhen: (Context) -> Boolean = { true },
        /** The condition above in plain words, for the watched-screens page. */
        val armedNote: String? = null,
    )

    // Bounced to Home while the uninstall lock is ON - the "escape routes" that would
    // let someone unlock or kill the guard.
    //
    // TWO of them carry an armedWhen, and for the same reason the Colour-correction page
    // does: they are ALSO the page you finish the setup on. App info is the only place
    // Android 11+ grants "Allow all the time" location (GrantWindow), and "Appear on top"
    // is where the overlay permission is granted. Bouncing someone off those before they
    // have granted anything is how the lock ends up guarding its own front door - see the
    // long note on GrantWindow.
    val UNINSTALL_GUARD_PAGES: List<PageMatch> = listOf(
        PageMatch("Device admin", listOf("Web Traffic Monitor", "admin app")),
        PageMatch("App info - uninstall", listOf("Web Traffic Monitor", "uninstall"),
            armedWhen = { !GrantWindow.isOpen(it) },
            armedNote = "and only once every permission we run on is granted - " +
                "this page is the only way to grant them"),
        PageMatch("App info - force stop", listOf("Web Traffic Monitor", "force stop"),
            armedWhen = { !GrantWindow.isOpen(it) },
            armedNote = "and only once every permission we run on is granted - " +
                "this page is the only way to grant them"),
        PageMatch("Page monitoring (accessibility)", listOf("page monitoring")),
        PageMatch("Overlay - Appear on top", listOf("Appear on top"),
            armedWhen = { Settings.canDrawOverlays(it) },
            armedNote = "and only once the overlay permission is granted - " +
                "this page is where it is granted"),
    )

    // Settings packages these pages are matched in. It was hardcoded to com.android.settings,
    // which missed the most obvious uninstall route of all: THE PLAY STORE. Our own listing
    // there has an Uninstall button, and device admin refusing the uninstall is no use if we
    // never notice the attempt - that attempt is our best early warning.
    val GUARDED_SETTINGS_PACKAGES: Set<String> = setOf(
        "com.android.settings",
        "com.android.vending",              // Play Store: our app page has Uninstall on it
        "com.samsung.android.settings",     // some Samsung builds
    )

    // Where an unarmed guard page (PageMatch.armedWhen) is actually let through: SETTINGS
    // ONLY. Derived from the list above rather than retyped, so a new OEM settings package
    // gets it automatically - but any STORE added up there has to be subtracted here too.
    // The Play Store's listing for us matches the App-info text ("Web Traffic Monitor" +
    // "uninstall") and carries a live Uninstall button rather than a permission switch: no
    // grant was ever completed there, so it is bounced exactly as it always was.
    val GRANT_WINDOW_PACKAGES: Set<String> = GUARDED_SETTINGS_PACKAGES - "com.android.vending"

    // ═══════════════════════════════════════════════════════════════════════════════
    //  (2b) THE OTHER WAYS OUT  -  bounced and recorded like the uninstall pages
    // ═══════════════════════════════════════════════════════════════════════════════
    //  Documented Android parental-control bypasses that we had no answer to. Each is a
    //  place where a person can put apps, time or network traffic somewhere this service
    //  cannot see, and each is a deliberate act rather than somewhere you land by mistake.
    //
    //  ⚠️ DEVELOPER OPTIONS AND USB DEBUGGING ARE DELIBERATELY ABSENT. ADB can switch this
    //  service off, and every competitor treats that as a hole to close. We leave it open
    //  on purpose: it is the master override. An app that can lock itself in against a
    //  cable and a computer is one nobody can rescue themselves from after a bad build or
    //  a forgotten passcode. Do not "fix" this.
    //
    //  Only enforced from STRICT upwards - see AccessibilityService.escapeRouteReason.
    val ESCAPE_ROUTE_PAGES: List<PageMatch> = listOf(
        // A second user, work profile or private space is a whole second phone this
        // service is not registered in. Android 15's Private Space is exactly that.
        PageMatch("Multiple users", listOf("Multiple users")),
        PageMatch("Add user", listOf("Add user")),
        PageMatch("Private space", listOf("Private space")),
        PageMatch("Secure folder", listOf("Secure Folder")),
        // A cloned app has a DIFFERENT package name, so every blacklist we have misses it.
        PageMatch("Dual apps", listOf("Dual apps")),
        PageMatch("App cloner", listOf("Dual Messenger")),
        // Sideloading puts any blocked app back under any name.
        PageMatch("Install unknown apps", listOf("Install unknown apps")),
        PageMatch("Unknown sources", listOf("Unknown sources")),
        // Every timer we run is wall-clock based; winding the clock forward ends them all.
        PageMatch("Date and time", listOf("Set time automatically")),
        // Not a threat to us (we do no DNS), but it is the layer people run underneath us.
        PageMatch("Private DNS", listOf("Private DNS")),
        // Unpreventable, but worth recording as the attempt it is.
        PageMatch("Factory reset", listOf("Erase all data")),
        PageMatch("Factory reset (alt)", listOf("Factory data reset")),
    )

    // The system Colour/Color-correction page (where Greyscale is toggled). Blocked only
    // when the user turns on "block this page" in the Grayscale setup screen AND greyscale
    // is currently on (so they can't disable it, but can never lock themselves out of
    // enabling it). "correction" + "yscale" match both Colour/Color and Grey/Grayscale.
    val COLOR_CORRECTION_PAGE = PageMatch("Colour correction", listOf("correction", "yscale"))

    // ═══════════════════════════════════════════════════════════════════════════════
    //  (4) THE CATALOGUE OF WATCHED SCREENS  —  for Developer tools → Word filter
    // ═══════════════════════════════════════════════════════════════════════════════
    //  Every SPECIFIC screen the monitor recognises by its text, described in one place so
    //  the dev console can list them. Built FROM the rule data above wherever possible, so
    //  editing a keyword list updates the page too - the moment this is retyped by hand it
    //  starts lying.
    //
    //  Two different things happen to a matched screen, and the difference matters:
    //    • COVER  - the block overlay goes over it. Used inside apps we don't control.
    //    • BOUNCE - we send the user to the home screen instead. Used for Settings pages,
    //               where a cover could be dismissed by the page underneath carrying on.
    // ═══════════════════════════════════════════════════════════════════════════════
    enum class GuardAction { COVER, BOUNCE }

    data class GuardedScreen(
        val name: String,
        /** The app it lives in, in plain words. */
        val where: String,
        /** When the guard is armed - the mode, or the setting it depends on. */
        val whenArmed: String,
        val action: GuardAction,
        /** Why this screen in particular. One line. */
        val why: String,
        /** The exact text we match on, for when an OS update changes the wording. */
        val matches: List<String>,
    )

    val GUARDED_SCREENS: List<GuardedScreen> = mutableListOf<GuardedScreen>().apply {
        add(GuardedScreen(
            "The image add-on's own page", "Firefox",
            "Every mode above Off, once you've confirmed the add-on is installed",
            GuardAction.COVER,
            "It carries the Remove and Disable switches. A guard whose off-switch is one " +
                "tap away is not a guard.",
            listOf(
                "URL contains \"${BrowserSetup.EXTENSION_SLUG}\"",
                "or the screen says \"${BrowserSetup.EXTENSION_NAME}\" AND one of:",
            ) + EXTENSION_MANAGER_KEYWORDS.map { "      \"$it\"" },
        ))
        add(GuardedScreen(
            "Stealth / privacy settings", "Firefox Focus",
            "Every mode above Off",
            GuardAction.COVER,
            "Focus's stealth option blocks screenshots, which would blind the monitor " +
                "completely.",
            listOf("title contains \"privacy\"", "screen says \"stealth\""),
        ))
        if (DISABLE_DELETE_HISTORY) {
            for ((label, titles, content) in FIREFOX_HISTORY_CLEAR_SCREENS) {
                add(GuardedScreen(
                    label, "Firefox",
                    "SUPER HARDCORE ONLY",
                    GuardAction.COVER,
                    "Wiping history is how a slip gets erased before anyone - you included - " +
                        "can look back at it.",
                    titles.map { "title contains \"$it\"" } + content.map { "screen says \"$it\"" },
                ))
            }
        }
        for (page in UNINSTALL_GUARD_PAGES) {
            add(GuardedScreen(
                page.label, "Android Settings",
                "While the uninstall lock is on" + (page.armedNote?.let { ", $it" } ?: "") +
                    ". The VISIT is recorded either way.",
                GuardAction.BOUNCE,
                "One of the four ways to take the guard down: uninstall it, force-stop it, " +
                    "deactivate its admin, or revoke a permission it runs on.",
                page.mustContain.map { "screen says \"$it\"" },
            ))
        }
        add(GuardedScreen(
            COLOR_CORRECTION_PAGE.label, "Android Settings",
            "Only if you turned on \"lock this page\", and only while greyscale is ON",
            GuardAction.BOUNCE,
            "Stops greyscale being switched back off. Deliberately inactive while greyscale " +
                "is off, so you can never lock yourself out of turning it ON.",
            COLOR_CORRECTION_PAGE.mustContain.map { "screen says \"$it\"" },
        ))
    }

    // === Search engines (term lives in a query param; only the search path matters) ==
    data class Search(val domain: String, val path: String, val params: List<String>)
    val SEARCH_ENGINES: List<Search> = listOf(
        Search("google.", "/search", listOf("q")),
        Search("duckduckgo.com", "", listOf("q")),
        Search("search.brave.com", "/search", listOf("q")),
        Search("ecosia.org", "/search", listOf("q")),
        Search("youtube.com", "/results", listOf("search_query")),
        Search("amazon.", "/s", listOf("k")),
        Search("ebay.", "/sch", listOf("_nkw")),
    )

    // === Apps the monitor ignores / never logs / breathing-gates =====================
    val IGNORED_PACKAGES: Set<String> = setOf("com.android.systemui")
    val BREATHING_APPS: Set<String> = setOf(
        "org.mozilla.firefox", "org.mozilla.fenix", "com.google.android.youtube", "com.android.vending",
    )
    val NOT_LOGGED_PACKAGES: Set<String> = setOf(
        "com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher",
        "com.android.launcher", "com.android.launcher3", "com.microsoft.launcher",
    )
    val BROWSER_DEBUG_PACKAGES: Set<String> = setOf("org.mozilla.firefox")

    // === Lockdown / unlock-wait essentials (kept usable even while locked down) =======
    // Matched as substrings of the package name. (Related to SAFE_APPS but narrower:
    // only the bare essentials, so a lockdown still lets you call/text/navigate.)
    // Data now in assets/filter/apps_lockdown.txt (FilterData).
    val LOCKDOWN_ALLOWED_SUBSTRINGS: List<String> get() = FilterData.lines("apps_lockdown.txt")

    // === Domain-strike escalation ====================================================
    const val DOMAIN_BLOCK_MS = 60 * 60 * 1000L   // whole-domain block length
    const val DOMAIN_STRIKE_THRESHOLD = 3         // strikes on one domain in a day -> permanent block
}
