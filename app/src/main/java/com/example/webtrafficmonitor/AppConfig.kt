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
    // │  `summary` is what the user reads on the in-app "What each mode does" screen  │
    // │  (Main.kt -> showModeRules, reachable from the Sexual arousal page). It is    │
    // │  the ONLY explanation they get, so it must stay true.                         │
    // │                                                                               │
    // │  Whenever you change what a mode does - here, or anywhere in the code that    │
    // │  branches on Mode.current()/Mode.isStrict()/Mode.isSuperHardcore()/spec() -   │
    // │  you MUST update that mode's `summary` lines in the same change. A rule the   │
    // │  user can't see is a rule they'll feel blindsided by.                         │
    // │                                                                               │
    // │  Keep each line short, concrete and in plain English ("Breathing pause: every │
    // │  time you open a watched app"), never jargon or internal names.               │
    // └───────────────────────────────────────────────────────────────────────────────┘
    data class ModeSpec(
        val id: String,
        val displayName: String,
        val breathingOn: Boolean,      // show the breathing pause on "breathing apps" at all
        val breathEveryOpen: Boolean,  // true = every open; false = first open of each day only
        val greyscale: Boolean,        // let the greyscale watcher grey the screen in this mode
        /**
         * The NIGHT GUARD: block every non-essential app while the phone says you are lying
         * down, or the room is dark. [flagLyingDown] and [lightFlagBelow] are its triggers.
         */
        val nightGuard: Boolean = false,
        // NOT WIRED INTO THE SCORER. flagThreshold is dev-console display only - the live
        // scorer uses one flat FilterTuning.THRESHOLD for every mode. Do NOT describe it in
        // `summary` until it is actually wired, or the rules screen becomes a lie.
        val flagThreshold: Int,
        // These two ARE live, but only as the night-guard's triggers (see nightGuard above).
        val flagLyingDown: Boolean = false,
        val lightFlagBelow: LightLevel = LightLevel.DARK,
        val summary: List<String> = emptyList(),     // plain-English rules shown to the user
    )
    val MODES: List<ModeSpec> = listOf(
        ModeSpec(id = "relaxed", displayName = "Relaxed",
            breathingOn = false, breathEveryOpen = false, greyscale = false,
            nightGuard = false,
            flagThreshold = 60, flagLyingDown = false, lightFlagBelow = LightLevel.DARK,
            summary = listOf(
                "Breathing pause: NEVER. Watched apps open straight away.",
                "Greyscale: off. Your screen stays in colour.",
                "Lying down or sitting in the dark changes nothing. Every app still opens.",
                "This is the ONLY mode where the \"who is the filter for\" switches (Adult content -> Settings) can be changed. In any stricter mode they are forced fully on.",
                "Everything in \"Always on\" above still applies - blocking does not stop in Relaxed.",
                "You can switch out of this mode whenever you like, unless the 7-day strict lock is running.",
            )),
        ModeSpec(id = "strict",  displayName = "Strict",
            breathingOn = true,  breathEveryOpen = false, greyscale = true,
            nightGuard = true,
            // DARK, not DULL: lightFlagBelow means "this band OR DARKER", so DULL was also
            // catching an ordinary dim room. Only genuine darkness should trip Strict.
            flagThreshold = 45, flagLyingDown = true,  lightFlagBelow = LightLevel.DARK,
            summary = listOf(
                "Breathing pause: the FIRST time you open a watched app each day. Open it again later the same day and it goes straight in - so 2FA codes and quick checks are not interrupted.",
                "The daily pass resets at midnight.",
                "NIGHT GUARD: while you are LYING DOWN, or the room is properly DARK, only the essentials open - calls, texts, alarms, contacts, camera, maps. Everything else, WhatsApp included, is blocked until you sit up or turn a light on.",
                "That is the whole point: the phone in bed, in the dark, is where this goes wrong.",
                "The \"who is the filter for\" switches are LOCKED fully on. You can only change those in Relaxed.",
                "Greyscale: your screen turns grey while you are lying down.",
                "Everything in \"Always on\" above still applies.",
                "The 7-day strict lock (if you start it) stops you dropping back to Relaxed for a week. You can still go UP to Super hardcore.",
            )),
        ModeSpec(id = "superhardcore", displayName = "Super hardcore",
            breathingOn = true,  breathEveryOpen = true, greyscale = true,
            nightGuard = true,
            // DARK here too (2026-07-15). This used to be DULL ("a dim room counts"), but
            // lightFlagBelow means "this band OR DARKER", so DULL tripped the guard in an
            // ordinary 35-lux evening room. Only genuine darkness should block, in every mode.
            flagThreshold = 30, flagLyingDown = true,  lightFlagBelow = LightLevel.DARK,
            summary = listOf(
                "Breathing pause: EVERY single time you open a watched app. There is no daily pass.",
                "Warning: this WILL interrupt you when you jump to an authenticator app for a 2FA code. That is the trade-off you are choosing.",
                "NIGHT GUARD: while you are LYING DOWN, or the room is properly DARK, only the essentials open - same triggers as Strict.",
                "Greyscale: your screen turns grey while you are lying down, same as Strict.",
                "Everything in \"Always on\" above still applies.",
                "This is Strict with the daily pass taken away.",
            )),
    )

    // === Night guard: what still opens while lying down / in the dark =================
    // Matched as SUBSTRINGS of the package name. Keep this to genuine essentials - the guard
    // is worthless if the thing you actually reach for is on it. WhatsApp is deliberately NOT
    // here (it is a scroll surface like any other); the dialer and SMS are, so you can still
    // be contacted in an emergency.
    val NIGHT_GUARD_ALLOWED_SUBSTRINGS: List<String> = listOf(
        "launcher", "trebuchet", "dialer", "incallui", "telecom", "phone", "contacts",
        "messaging", "mms", "deskclock", "clock", "alarm", "camera", "maps", "waze",
    )

    /** The lux ceiling of a light band - the level is "at or below" this. */
    fun lightBandMax(level: LightLevel): Float = when (level) {
        LightLevel.DARK -> LIGHT_DULL_MAX        // < 20 lux
        LightLevel.DULL -> LIGHT_NORMAL_MAX      // < 80 lux
        LightLevel.NORMAL -> LIGHT_BRIGHT_MAX    // < 400 lux
        LightLevel.BRIGHT -> Float.MAX_VALUE
    }

    // Hysteresis for the night guard's DARK trigger. It blocks at or below the band ceiling,
    // but does not release until the light is comfortably past it (ceiling x this). Without
    // the gap, a reading hovering on the threshold - which is exactly what happens when the
    // cover's own glow hits the sensor - makes the block flicker on and off.
    const val NIGHT_GUARD_LIGHT_RELEASE = 1.6f
    fun modeName(id: String): String = MODES.firstOrNull { it.id == id }?.displayName ?: id

    /**
     * Rules that are TRUE IN EVERY MODE - the "Always on" block at the top of the in-app
     * rules screen. Same maintenance contract as ModeSpec.summary: change the behaviour,
     * change these lines in the same commit.
     */
    val ALWAYS_ON_RULES: List<String> = listOf(
        "Known adult sites are blocked outright. Mode makes no difference.",
        "Pages are scanned for sexual words. Enough of them and the page is blocked, in every mode - including Relaxed.",
        "Anything on your ban list is blocked - the sites, pages, search terms and keywords you have banned yourself from.",
        "Blocked browsers are covered the moment you open them. DuckDuckGo is deliberately left usable.",
        "Dismiss a block on the same site enough times in one day and the whole site gets banned automatically for a while.",
        "Short-form feeds (Reels, Shorts, TikTok-style) are blocked if you have that switch on.",
        "Greylisted apps (TikTok, Instagram and friends) get ${GreyUsage.LIMIT_MIN} minutes per hour, then they close.",
        "\"Ride out the urge\" lockdown: ${Lockdown.DURATION_MS / 60_000} minutes where only the essentials - calls, texts, alarms, maps - will open. It cannot be cancelled early.",
        "Unlocking a relaxed window is limited: once a day, and only ${LoosenLimit.LIFETIME_MAX} times ever.",
        "There is no \"let me look anyway\" button. It is not on any screen, on purpose - a button like that is just a door with a handle, and sooner or later the urge tries the handle.",
        "It IS still there, but you only get offered it if you go for the uninstall button, the device-admin lock, the monitoring switch, or try to escape a locked strict mode. Reach for the destructive option and you'll be offered the honest one instead - a wait, a written commitment, a fixed window, and everything still standing afterwards.",
        "The app never presses Back for you. Blocking covers the screen and offers you the way out; it never navigates your browser behind your back.",
    )

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
    // entirely - no screenshot, scan, or log - to save battery/CPU. Add freely.
    val SAFE_APPS_BY_NAME: Map<String, String> = linkedMapOf(
        // Maps & navigation
        "Google Maps" to "com.google.android.apps.maps", "Waze" to "com.waze",
        "Google Maps Go" to "com.google.android.apps.navlite", "HERE WeGo" to "com.here.app.maps",
        "Mapbox" to "com.mapbox.app", "Citymapper" to "com.citymapper.app.release",
        "Google Earth" to "com.google.earth",
        // Messaging & calls (no public feed)
        "WhatsApp" to "com.whatsapp", "WhatsApp Business" to "com.whatsapp.w4b",
        "Telegram" to "org.telegram.messenger", "Signal" to "org.thoughtcrime.securesms",
        "Google Messages" to "com.google.android.apps.messaging", "AOSP Messaging" to "com.android.mms",
        "Viber" to "com.viber.voip", "Skype" to "com.skype.raider",
        "Gmail" to "com.google.android.gm", "Outlook" to "com.microsoft.office.outlook",
        "K-9 Mail" to "com.fsck.k9", "Google Chat" to "com.google.android.apps.dynamite",
        "Zoom" to "us.zoom.videomeetings", "Google Meet" to "com.google.android.apps.tachyon",
        "Microsoft Teams" to "com.microsoft.teams",
        // Productivity, notes, office, files
        "Google Calendar" to "com.google.android.calendar", "Google Keep" to "com.google.android.keep",
        "Microsoft To Do" to "com.microsoft.todos", "Todoist" to "com.todoist",
        "TickTick" to "com.ticktick.task", "Evernote" to "com.evernote", "Notion" to "com.notion.id",
        "Obsidian" to "md.obsidian", "Any.do" to "com.anydo",
        "Word" to "com.microsoft.office.word", "Excel" to "com.microsoft.office.excel",
        "PowerPoint" to "com.microsoft.office.powerpoint", "OneNote" to "com.microsoft.office.onenote",
        "Google Drive" to "com.google.android.apps.docs",
        "Google Docs" to "com.google.android.apps.docs.editors.docs",
        "Google Sheets" to "com.google.android.apps.docs.editors.sheets",
        "Google Slides" to "com.google.android.apps.docs.editors.slides",
        "Dropbox" to "com.dropbox.android", "Adobe Reader" to "com.adobe.reader",
        // Banking & finance
        "PayPal" to "com.paypal.android.p2pmobile", "Google Wallet" to "com.google.android.apps.walletnfcrel",
        "Wise" to "com.wise.android", "Revolut" to "com.revolut.revolut",
        // Utilities & system
        "Calculator" to "com.android.calculator2", "Google Calculator" to "com.google.android.calculator",
        "Clock" to "com.android.deskclock", "Google Clock" to "com.google.android.deskclock",
        "Files" to "com.android.documentsui", "Files by Google" to "com.google.android.apps.nbu.files",
        "Contacts" to "com.android.contacts", "Google Contacts" to "com.google.android.contacts",
        "Phone" to "com.android.dialer", "Google Phone" to "com.google.android.dialer",
        "Google Camera" to "com.google.android.GoogleCamera", "Google Photos" to "com.google.android.apps.photos",
        "Samsung Gallery" to "com.sec.android.gallery3d",
        // Weather
        "Google Weather" to "com.google.android.apps.weather", "Weather Channel" to "com.weather.Weather",
        "Met Office" to "org.metoffice.weather.android",
        // Audio & podcasts (no visual feed)
        "Spotify" to "com.spotify.music", "Audible" to "com.audible.application",
        "Google Podcasts" to "com.google.android.apps.podcasts", "Shazam" to "com.shazam.android",
        "Deezer" to "deezer.android.app",
        // Health & fitness
        "Google Fit" to "com.google.android.apps.fitness", "Fitbit" to "com.fitbit.FitbitMobile",
        "MyFitnessPal" to "com.myfitnesspal.android", "Sleep Cycle" to "com.sleepcycle.sleepanalysis",
        // Transit, ride, food
        "Uber" to "com.ubercab", "Uber Eats" to "com.ubercab.eats", "Deliveroo" to "com.deliveroo.orderapp",
        "Grubhub" to "com.grubhub.android", "Zomato" to "com.application.zomato",
        // Reading, reference, translation
        "Play Books" to "com.google.android.apps.books", "Kindle" to "com.amazon.kindle",
        "Kobo" to "com.kobobooks.android", "Google Translate" to "com.google.android.apps.translate",
    )
    val SAFE_APPS: Set<String> = SAFE_APPS_BY_NAME.values.toSet()

    // === Greylist apps (friendly name -> package) ====================================
    // Social / short-form apps that MAY contain bad stuff. Never whitelisted; defaulted
    // to the time-limited GREY tier unless the user overrides.
    val GREYLIST_APPS_BY_NAME: Map<String, String> = linkedMapOf(
        "TikTok" to "com.zhiliaoapp.musically", "TikTok (trill)" to "com.ss.android.ugc.trill",
        "TikTok Lite" to "com.zhiliaoapp.musically.go",
        "Instagram" to "com.instagram.android", "Instagram Lite" to "com.instagram.lite",
        "Snapchat" to "com.snapchat.android", "Reddit" to "com.reddit.frontpage",
        "X / Twitter" to "com.twitter.android", "X Lite" to "com.twitter.android.lite",
        "Facebook" to "com.facebook.katana", "Facebook Lite" to "com.facebook.lite",
        "Messenger" to "com.facebook.orca", "Pinterest" to "com.pinterest", "Tumblr" to "com.tumblr",
        "Twitch" to "tv.twitch.android.app", "Discord" to "com.discord",
        "YouTube" to "com.google.android.youtube", "LinkedIn" to "com.linkedin.android",
        "Bluesky" to "xyz.blueskyweb.app",
    )
    val GREYLIST_APPS: Set<String> = GREYLIST_APPS_BY_NAME.values.toSet()

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
        val title: String,
        val subtitle: String,
        /** "Does this sound like you?" - the bullets they read to self-identify. */
        val covers: List<String>,
        /** BlockRules entries the "block what feeds this" switch adds/removes. */
        val blockPatterns: List<String> = emptyList(),
        /** Packages the same switch drops to the GREY tier (time-limited, not banned). */
        val greyApps: List<String> = emptyList(),
        /** One concrete thing to do instead. One. Not a menu. */
        val insteadOf: String,
    )

    val TEMPTATIONS: List<TemptationSpec> = listOf(
        TemptationSpec(
            id = "scrolling",
            title = "Endless Scrolling / Brain Rot  🤳",
            subtitle = "Break the infinite feed loop.",
            covers = listOf(
                "TikTok, Reels, Shorts, Reddit feeds - anything that never ends",
                "The \"just one more scroll\" loop",
                "Passively consuming until your attention is shredded and you feel drained",
                "Content built on constant novelty, to keep you hunting the next hit",
                "Losing an hour without ever deciding to spend it",
            ),
            blockPatterns = SHORT_FORM_PATTERNS,
            greyApps = listOf(
                "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go",
                "com.instagram.android", "com.reddit.frontpage", "com.snapchat.android",
            ),
            insteadOf = "Put the phone in another room and do the one thing you were avoiding for ten minutes.",
        ),
        TemptationSpec(
            id = "binge",
            title = "Binge Watching  📺",
            subtitle = "Escape loops through endless viewing.",
            covers = listOf(
                "Netflix, YouTube, streaming, autoplay running on and on",
                "Watching hours longer than you meant to",
                "Using a show to escape boredom, stress, or a task you don't want to start",
                "Autoplay and \"recommended for you\" deciding your evening for you",
                "The gap between choosing to watch something and losing control of the night",
            ),
            blockPatterns = listOf(
                "netflix.com", "hulu.com", "disneyplus.com", "primevideo.com", "twitch.tv",
                "youtube.com/feed/recommended",
            ),
            greyApps = listOf("com.netflix.mediaclient", "tv.twitch.android.app"),
            insteadOf = "Decide the ONE episode before you start, and set a timer for when it ends.",
        ),
        TemptationSpec(
            id = "comparison",
            title = "Social Comparison / Social Media  👥",
            subtitle = "Break the comparison and validation loop.",
            covers = listOf(
                "Watching friends, influencers and peers post the highlight reel of their lives",
                "FOMO - the sense that everyone else is somewhere better",
                "Fishing for validation in likes, comments and views",
                "Measuring your looks, money, job and progress against strangers",
                "Coming away feeling behind, small, or quietly anxious",
            ),
            blockPatterns = listOf(
                "instagram.com", "facebook.com", "x.com", "twitter.com", "linkedin.com/feed",
            ),
            greyApps = listOf(
                "com.instagram.android", "com.instagram.lite", "com.facebook.katana",
                "com.facebook.lite", "com.twitter.android", "com.pinterest",
            ),
            insteadOf = "Message one person you actually care about, instead of watching a hundred you don't.",
        ),
        TemptationSpec(
            id = "checking",
            title = "Phone Checking  📱",
            subtitle = "Stop the automatic checking habit.",
            covers = listOf(
                "Unlocking the phone with no idea why you picked it up",
                "Checking notifications and messages purely out of reflex",
                "Reaching for it the second you feel bored or uncomfortable",
                "Doing it dozens of times a day without noticing",
                "Wanting the choice back",
            ),
            // Nothing to block: the pull here is the device itself, not a site. The page
            // offers the 30-minute lockdown instead.
            insteadOf = "Leave the phone face-down in another room for the next hour.",
        ),
        TemptationSpec(
            id = "news",
            title = "News Cycles / Existential Anxiety  📰",
            subtitle = "Step away from endless worry.",
            covers = listOf(
                "Refreshing the news all day for an update that never settles anything",
                "Reading the same bad story again and again, and doing nothing with it",
                "Drowning in politics, disasters and problems you cannot personally touch",
                "The loop: fear, uncertainty, check, brief relief, fear again",
                "Being informed is fine. Being consumed by it is not.",
            ),
            blockPatterns = listOf(
                "news.google.com", "cnn.com", "foxnews.com", "bbc.co.uk/news",
                "theguardian.com", "dailymail.co.uk", "reddit.com/r/worldnews",
            ),
            insteadOf = "Pick one time tomorrow to read the news once, and act on nothing else until then.",
        ),
        TemptationSpec(
            id = "gaming",
            title = "Gaming & Reward Loops  🎮",
            subtitle = "Understand digital reward cycles.",
            covers = listOf(
                "Games built around streaks, unlocks, levels and daily rewards",
                "Chasing progress that only exists inside the game",
                "\"One more run\" turning into three hours",
                "Playing to dodge boredom, stress, or something you owe someone",
                "The point where entertainment quietly became a compulsion",
            ),
            blockPatterns = listOf("poki.com", "crazygames.com", "miniclip.com", "coolmathgames.com"),
            insteadOf = "Name what you're avoiding by playing, and do five minutes of it.",
        ),
        TemptationSpec(
            id = "shopping",
            title = "Impulse Shopping  💳",
            subtitle = "Break the buying-for-a-feeling loop.",
            covers = listOf(
                "Amazon, Temu, discount sites, ads following you around",
                "Buying because you're bored, stressed, or high on getting a deal",
                "\"Limited time\" offers engineered to stop you thinking",
                "The hit is the ordering and the parcel - not the thing itself",
                "Wanting to spend on purpose again",
            ),
            blockPatterns = listOf(
                "amazon.com", "amazon.co.uk", "temu.com", "ebay.com", "aliexpress.com",
                "shein.com", "wish.com",
            ),
            insteadOf = "Put it in the basket and leave it there for 48 hours. Most of it dies on its own.",
        ),
    )

    fun temptation(id: String): TemptationSpec? = TEMPTATIONS.firstOrNull { it.id == id }

    // === Trusted domains (heuristic scorer skipped here) =============================
    val SAFE_DOMAINS: Set<String> = setOf(
        "wikipedia.org", "wikimedia.org", "wiktionary.org", "britannica.com",
        "stackoverflow.com", "stackexchange.com", "superuser.com", "serverfault.com",
        "github.com", "gitlab.com", "bitbucket.org", "developer.android.com", "developer.mozilla.org",
        "developer.apple.com", "kotlinlang.org", "python.org", "npmjs.com", "pypi.org", "rust-lang.org",
        "go.dev", "w3.org", "w3schools.com", "geeksforgeeks.org",
        "maps.google.com", "docs.google.com", "drive.google.com", "calendar.google.com",
        "mail.google.com", "translate.google.com", "scholar.google.com", "openstreetmap.org",
        "gov.uk", "nhs.uk", "who.int", "cdc.gov", "nih.gov", "nasa.gov", "europa.eu", "usa.gov",
        // Health/medical: anatomy words are unavoidable here, and nobody should be blocked
        // from looking up a symptom. (The scorer also damps medical context generally - see
        // MedicalContext - but these are common enough to be worth skipping outright.)
        "mayoclinic.org", "healthline.com", "webmd.com", "nhsinform.scot", "patient.info",
        "medlineplus.gov", "plannedparenthood.org", "brook.org.uk", "netdoctor.co.uk",
        "hopkinsmedicine.org", "clevelandclinic.org", "bupa.co.uk",
        "khanacademy.org", "coursera.org", "edx.org", "mit.edu", "duolingo.com",
        "notion.so", "todoist.com", "trello.com", "asana.com", "slack.com", "figma.com", "linear.app",
        "outlook.com", "outlook.office.com", "office.com", "microsoft.com", "apple.com", "icloud.com",
        "metoffice.gov.uk", "accuweather.com", "arxiv.org", "pubmed.ncbi.nlm.nih.gov",
        "paypal.com", "wise.com", "revolut.com",
    )

    // === Browsers ====================================================================
    // We standardise on Firefox. ALLOWED_BROWSERS stay usable; everything in
    // BLOCKED_BROWSERS is funnelled away so users land on Firefox.
    val ALLOWED_BROWSERS: Set<String> = setOf("org.mozilla.firefox", "org.mozilla.fenix")
    val BLOCKED_BROWSERS: Set<String> = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "org.mozilla.firefox_beta", "org.mozilla.fennec_fdroid", "org.mozilla.focus",
        "org.mozilla.klar", "org.mozilla.rocket", "org.mozilla.reference.browser",
        "io.github.forkmaintainers.iceraven", "us.spotco.fennec_dos",
        "com.duckduckgo.mobile.android",
        "com.microsoft.emmx", "com.opera.browser", "com.opera.browser.beta", "com.opera.mini.native",
        "com.opera.gx", "com.opera.touch", "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta", "com.vivaldi.browser", "com.vivaldi.browser.snapshot",
        "com.yandex.browser", "com.yandex.browser.beta",
        "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly",
        "com.android.browser", "com.google.android.browser",
        "com.miui.browser", "com.mi.globalbrowser", "com.mi.globalbrowser.mini", "com.heytap.browser",
    )

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
        Triple("History screen",
            listOf("history"),
            listOf("recently closed tabs", "no history here")),
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

    // (2) & (3) Settings pages matched by text. A page matches when EVERY string in
    // `mustContain` is present on screen (case-insensitive substring). Strings copied
    // verbatim from this app's monitor on a Samsung device.
    data class PageMatch(val label: String, val mustContain: List<String>)

    // Bounced to Home while the uninstall lock is ON - the "escape routes" that would
    // let someone unlock or kill the guard.
    val UNINSTALL_GUARD_PAGES: List<PageMatch> = listOf(
        PageMatch("Device admin", listOf("Web Traffic Monitor", "admin app")),
        PageMatch("App info - uninstall", listOf("Web Traffic Monitor", "uninstall")),
        PageMatch("App info - force stop", listOf("Web Traffic Monitor", "force stop")),
        PageMatch("Page monitoring (accessibility)", listOf("page monitoring")),
        PageMatch("Overlay - Appear on top", listOf("Appear on top")),
    )

    // The system Colour/Color-correction page (where Greyscale is toggled). Blocked only
    // when the user turns on "block this page" in the Grayscale setup screen AND greyscale
    // is currently on (so they can't disable it, but can never lock themselves out of
    // enabling it). "correction" + "yscale" match both Colour/Color and Grey/Grayscale.
    val COLOR_CORRECTION_PAGE = PageMatch("Colour correction", listOf("correction", "yscale"))

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
    val LOCKDOWN_ALLOWED_SUBSTRINGS: List<String> = listOf(
        "launcher", "trebuchet", "dialer", "incallui", "telecom", "phone", "contacts",
        "messaging", "mms", "deskclock", "clock", "alarm",
    )

    // === Domain-strike escalation ====================================================
    const val DOMAIN_BLOCK_MS = 60 * 60 * 1000L   // whole-domain block length
    const val DOMAIN_STRIKE_THRESHOLD = 3         // strikes on one domain in a day -> permanent block
}
