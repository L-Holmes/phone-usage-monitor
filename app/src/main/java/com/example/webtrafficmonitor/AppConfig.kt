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
// AppConfig  —  THE ONE PLACE TO EDIT LISTS & SETTINGS
// =====================================================================================
// Everything here is compile-time (no file is parsed on the device — fastest possible,
// and a typo fails the build instead of silently breaking a list at runtime).
// Grouped by purpose; app entries map a friendly name -> the package our monitor sees.
// Per-page block TEXT (e.g. specific Settings screens) deliberately stays in source.
object AppConfig {

    // === Mode → permissions ==========================================================
    // The app's modes and what each allows. Display names are read from here so the
    // rest of the app stays consistent. (Behavioural wiring beyond names/breathing/
    // flag-threshold is still in code; this block is the dial to grow into.)
    data class ModeSpec(
        val id: String,
        val displayName: String,
        val breathingOn: Boolean,      // show the breathing pause on "breathing apps"
        val flagThreshold: Int,        // borderline score at/above which a page is flagged
        val flagLyingDown: Boolean = false,          // treat "probably lying down" as higher-risk
        val lightFlagBelow: LightLevel = LightLevel.DARK,  // treat light at/below this as higher-risk
    )
    val MODES: List<ModeSpec> = listOf(
        ModeSpec(id = "relaxed", displayName = "Relaxed", breathingOn = true,  flagThreshold = 60,
            flagLyingDown = false, lightFlagBelow = LightLevel.DARK),
        ModeSpec(id = "strict",  displayName = "Strict",  breathingOn = true,  flagThreshold = 45,
            flagLyingDown = true,  lightFlagBelow = LightLevel.DULL),
    )
    fun modeName(id: String): String = MODES.firstOrNull { it.id == id }?.displayName ?: id

    // === Ambient light (from the phone's light sensor, in lux) =======================
    // Thresholds are rough, drawn from common lighting references: moonlit/near-dark
    // rooms sit under ~10 lux, a dim lamp-lit evening room ~10–80, ordinary indoor
    // lighting ~80–400, and bright indoor / daylight above that.
    enum class LightLevel { DARK, DULL, NORMAL, BRIGHT }
    const val LIGHT_DULL_MAX = 10f      // < 10 lux  -> DARK
    const val LIGHT_NORMAL_MAX = 80f    // 10–80     -> DULL
    const val LIGHT_BRIGHT_MAX = 400f   // 80–400    -> NORMAL ; >=400 -> BRIGHT
    fun lightLevel(lux: Float): LightLevel = when {
        lux < LIGHT_DULL_MAX -> LightLevel.DARK
        lux < LIGHT_NORMAL_MAX -> LightLevel.DULL
        lux < LIGHT_BRIGHT_MAX -> LightLevel.NORMAL
        else -> LightLevel.BRIGHT
    }
    // A LightLevel is "at or below" another when it's the same or darker.
    fun lightAtOrBelow(level: LightLevel, floor: LightLevel): Boolean = level.ordinal <= floor.ordinal

    // === Lying-down heuristic (from the accelerometer / gravity direction) ===========
    // tilt = angle of the phone away from upright (0deg = held upright, 90deg = flat or
    // rolled onto its side). Beyond ~60deg the phone is well past normal upright use, and
    // a large side-roll suggests lying on one's side. These match the ranges used by
    // common "posture from accelerometer" projects; tune on the debug page.
    const val LYING_TILT_DEG = 60f
    const val LYING_SIDE_ROLL_DEG = 50f

    // === Uninstall / device-admin passcode ==========================================
    const val UNINSTALL_PASSCODE = "666666"

    // === Developer mode =============================================================
    // When true, the home page shows a "Dev tools" button (block-rule tools, log, etc.).
    // Flip to false for a clean end-user build.
    const val DEV_MODE = true

    // === Safe apps (friendly name -> package) ========================================
    // No public scrolling feed and no arbitrary adult content. The monitor SKIPS these
    // entirely — no screenshot, scan, or log — to save battery/CPU. Add freely.
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
    // Firefox private/incognito + Focus stealth screens we block (off-web only).
    data class ScreenGuard(val pkg: String, val titleKeywords: List<String>, val contentKeywords: List<String>, val reason: String)
    val SCREEN_GUARDS: List<ScreenGuard> = listOf(
        ScreenGuard("org.mozilla.focus", listOf("privacy"), listOf("stealth"),
            "Firefox Focus stealth/privacy settings are blocked"),
    )

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
