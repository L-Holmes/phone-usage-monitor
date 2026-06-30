package com.example.webtrafficmonitor

import android.graphics.PixelFormat
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

// stuff for the breating
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

//other

import android.widget.ImageView
import android.graphics.Path


// NOTE: This whole module is intentionally kept in ONE file.
// These classes would normally live in separate files / sub-packages;
// they are consolidated here on purpose to make development easier.
// Major sections (// ===) mark what used to be sub-folders;
// subsections (// ---) mark what used to be separate files.
// Regenerate with merge_kt.py -- do not re-split by hand.

// =====================================================================================
// APP
// =====================================================================================


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
    )
    val MODES: List<ModeSpec> = listOf(
        ModeSpec(id = "relaxed", displayName = "Relaxed", breathingOn = true,  flagThreshold = 60),
        ModeSpec(id = "strict",  displayName = "Strict",  breathingOn = true,  flagThreshold = 45),
    )
    fun modeName(id: String): String = MODES.firstOrNull { it.id == id }?.displayName ?: id

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
        // Private browsing on Firefox defeats monitoring → block the private-tab screen.
        ScreenGuard("org.mozilla.firefox", listOf("private browsing", "private tab", "you're in a private tab"), emptyList(),
            "Private browsing is blocked \u2014 use a normal tab"),
        ScreenGuard("org.mozilla.fenix", listOf("private browsing", "private tab", "you're in a private tab"), emptyList(),
            "Private browsing is blocked \u2014 use a normal tab"),
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

// --------------------------------------------------------------
// MainActivity
// --------------------------------------------------------------


class MainActivity : AppCompatActivity() {

    private val database by lazy { MonitorDatabase.get(this) }
    private val adapter = MonitorAdapter(
        onEntryClick = ::blockEntry,
        onEntryLongClick = ::showEntryDetails,
    )

    private lateinit var statusAccessibility: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var statusLock: TextView
    private lateinit var emptyList: TextView
    private lateinit var btnUninstallGuard: Button
    private lateinit var spinnerMode: Spinner


    /** Long-press a row to read the whole entry — including the full NODE DUMP. */
    private fun showEntryDetails(entry: MonitorEntry) {
        val details = buildString {
            append("kind: ").append(entry.kind).append("\n\n")
            append("package: ").append(entry.packageName).append("\n\n")
            append("url: ").append(entry.url ?: "(none)").append("\n\n")
            append("domain: ").append(entry.domain ?: "(none)").append("\n\n")
            append("title: ").append(entry.title ?: "(none)").append("\n\n")
            append("content / dump:\n").append(entry.text ?: "(none)")
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val textView = TextView(this).apply {
            text = details
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(textView) }
        AlertDialog.Builder(this)
            .setTitle("Entry details")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Scrollable summary of every ban: rules, timed domain bans, strikes per domain/app. */
    private fun showBanList() {
        val text = buildString {
            append("PERMANENT RULES (sites / keywords)\n")
            val perm = BlockRules.all().sorted()
            append(if (perm.isEmpty()) "(none)\n" else perm.joinToString("\n") + "\n")
            append("\nTIMED RULES (e.g. domains banned for 1h)\n")
            val timed = BlockRules.allTimed()
            append(if (timed.isEmpty()) "(none)\n" else timed.joinToString("\n") + "\n")
            append("\nDOMAIN STRIKES (today — 3 bans the domain for 1h)\n")
            val dom = BlockEscalation.summary(this@MainActivity)
            append(if (dom.isEmpty()) "(none)\n" else dom.joinToString("\n") + "\n")
            append("\nAPP STRIKES / TIMED APP BLOCKS\n")
            val apps = AppTimedBlock.summary(this@MainActivity)
            append(if (apps.isEmpty()) "(none)\n" else apps.joinToString("\n") + "\n")
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("Ban list")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun pickWithCustomScreen(
        title: String, base: List<String>, category: String?,
        onBack: (() -> Unit)?, onPick: (String) -> Unit,
    ) {
        val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        if (onBack != null) root.addView(backText { onBack() })
        root.addView(titleText(title))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val customs = category?.let { CustomOptions.all(this, it) } ?: emptyList()
        var cs = (base + customs).distinct().map { metaFor(category ?: "", it) }
        if (category == "feeling") cs = cs.sortedBy { feelingRank(it.value) }
        cs.forEach { c ->
            list.addView(optionRow(c) { onPick(c.value) })
        }
        if (category != null) list.addView(addOwnRow { promptCustom(category) { onPick(it) } })
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
        })
        setContentView(root)
    }

    private fun promptCustom(category: String, onAdded: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "Type it"; inputType = InputType.TYPE_CLASS_TEXT
            val p = (20 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this).setTitle("Add your own").setView(input)
            .setPositiveButton("Add") { _, _ ->
                val n = input.text.toString().trim().replace("\n", " ")
                if (n.isNotEmpty()) { CustomOptions.add(this, category, n); onAdded(n) }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /**
     * Like [pickWithCustomScreen] but lets the user tick several options ("select all
     * that apply") and hands back the full list. Feelings render grouped + tinted.
     */
    private fun pickMultiWithCustomScreen(
        title: String, base: List<String>, category: String?,
        onBack: (() -> Unit)?, onPick: (List<String>) -> Unit,
    ) {
        val selected = linkedSetOf<String>()
        val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        if (onBack != null) root.addView(backText { onBack() })
        root.addView(titleText(title))
        root.addView(TextView(this).apply {
            text = "Select all that apply."; textSize = 14f; setTextColor(0xFF6B7075.toInt())
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
        })
        val cont = bigContinue("Continue") { if (selected.isNotEmpty()) onPick(selected.toList()) }
        root.addView(cont)

        fun choices(): List<Choice> {
            val customs = category?.let { CustomOptions.all(this, it) } ?: emptyList()
            var cs = (base + customs).distinct().map { metaFor(category ?: "", it) }
            if (category == "feeling") cs = cs.sortedBy { feelingRank(it.value) }
            return cs
        }
        fun renderList() {
            list.removeAllViews()
            choices().forEach { c ->
                list.addView(checkRow(c, c.value in selected) {
                    if (c.value in selected) selected.remove(c.value) else selected.add(c.value)
                    renderList(); tuneContinue(cont, selected.isNotEmpty())
                })
            }
            if (category != null) list.addView(addOwnRow {
                promptCustom(category) { added -> selected.add(added); renderList(); tuneContinue(cont, selected.isNotEmpty()) }
            })
        }
        renderList(); tuneContinue(cont, false)
        setContentView(root)
    }

    // ── "I feel temptation" flow (groups -> sub-picks -> ride the wave) ─────────
    private enum class TGroup(
        val short: String, val example: String, val title: String, val category: String, val icon: String,
    ) {
        SCREEN("Something on a screen", "e.g. my phone, my computer, the TV", "What kind of screen?", "screen", "\uD83D\uDCF1"),
        PLACE("Linked to where I am", "e.g. bedroom, bathroom, in the house", "Where are you?", "location", "\uD83D\uDCCD"),
        FEELING("How I'm feeling", "e.g. anxious, low, frustrated", "How are you feeling?", "feeling", "\uD83D\uDCAD"),
        DOING("Out of habit", "e.g. scrolling, winding down, just woke up", "What were you doing?", "activity", "\uD83D\uDD01"),
    }
    private fun baseFor(g: TGroup): List<String> = when (g) {
        TGroup.SCREEN -> Opts.SCREEN_TYPES
        TGroup.PLACE -> Opts.LOCATIONS
        TGroup.FEELING -> Opts.FEELINGS
        TGroup.DOING -> ACTIVITIES
    }

    private val tGroups = linkedSetOf<TGroup>()
    private val tAnswers = linkedMapOf<TGroup, String>()
    private var tSubQueue: List<TGroup> = emptyList()
    private var tSubIndex = 0
    private var tUrgeIndex = 0
    private var waveStartAt = 0L
    private var breatheOn = false
    private var tBack: (() -> Unit)? = null

    private fun startTemptationFlow() {
        onReportScreen = true
        inTemptationFlow = true
        tGroups.clear(); tAnswers.clear(); tSubQueue = emptyList(); tSubIndex = 0; tUrgeIndex = 0
        temptationGroupsScreen()
    }

    private fun temptationBack() {
        (tBack ?: { stopRideTimer(); inTemptationFlow = false; showReportScreen() })()
    }

    private fun temptationGroupsScreen() {
        stopRideTimer()
        tBack = { stopRideTimer(); inTemptationFlow = false; showReportScreen() }
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(backText { temptationBack() })
        root.addView(titleText("What's feeding it right now?"))
        root.addView(TextView(this).apply {
            text = "Pick any that apply."; textSize = 14f; setTextColor(0xFF6B7075.toInt())
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
        })
        val cont = bigContinue("Continue") {
            if (tGroups.isNotEmpty()) { tSubQueue = tGroups.toList(); tSubIndex = 0; renderNextSub() }
        }
        root.addView(cont)
        fun renderList() {
            list.removeAllViews()
            TGroup.values().forEach { g ->
                list.addView(checkRow(Choice(g.short, g.icon, g.example), g in tGroups) {
                    if (g in tGroups) tGroups.remove(g) else tGroups.add(g)
                    renderList(); tuneContinue(cont, tGroups.isNotEmpty())
                })
            }
        }
        renderList(); tuneContinue(cont, tGroups.isNotEmpty())
        setContentView(root)
    }

    private fun renderNextSub() {
        if (tSubIndex >= tSubQueue.size) { temptationUrgeScreen(); return }
        val g = tSubQueue[tSubIndex]
        tBack = { if (tSubIndex == 0) temptationGroupsScreen() else { tSubIndex--; renderNextSub() } }
        if (g == TGroup.FEELING || g == TGroup.DOING) {
            pickMultiWithCustomScreen(g.title, baseFor(g), g.category, onBack = { temptationBack() }) {
                tAnswers[g] = it.joinToString(", "); tSubIndex++; renderNextSub()
            }
        } else {
            pickWithCustomScreen(g.title, baseFor(g), g.category, onBack = { temptationBack() }) {
                tAnswers[g] = it; tSubIndex++; renderNextSub()
            }
        }
    }

    private fun temptationUrgeScreen() {
        tBack = { if (tSubQueue.isEmpty()) temptationGroupsScreen() else { tSubIndex = tSubQueue.lastIndex; renderNextSub() } }
        urgeScaleScreen("How strong is the urge?", onBack = { temptationBack() }) {
            tUrgeIndex = Opts.URGE_LEVELS.indexOf(it).coerceAtLeast(0)
            startRideWave()
        }
    }

    private fun showManageRules() {
        inSubPage = true
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(backText { setupMainScreen() })
        root.addView(titleText("Manage blocks"))
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(container)
        })
        setContentView(root)

        fun header(t: String): TextView = TextView(this).apply {
            text = t; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF6B7075.toInt())
            setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
        }
        fun row(label: String, onRemove: () -> Unit): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(this@MainActivity).apply { text = "Remove"; setOnClickListener { onRemove() } })
        }

        fun reload() {
            container.removeAllViews()
            var any = false
            val blockedApps = AppRules.apps(this).filter { it.first == AppRules.BLOCK }
            if (blockedApps.isNotEmpty()) {
                any = true; container.addView(header("Blocked apps"))
                blockedApps.forEach { (_, pkg) ->
                    container.addView(row(appLabel(pkg)) { AppRules.remove(this, true, pkg); reload() })
                }
            }
            val greyApps = AppRules.apps(this).filter { it.first == AppRules.GREY }
            if (greyApps.isNotEmpty()) {
                any = true; container.addView(header("Greylisted apps (${GreyUsage.LIMIT_MIN} min/hour)"))
                greyApps.forEach { (_, pkg) ->
                    container.addView(row(appLabel(pkg)) { AppRules.remove(this, true, pkg); reload() })
                }
            }
            val siteRules = BlockRules.all()
            if (siteRules.isNotEmpty()) {
                any = true; container.addView(header("Blocked sites & pages"))
                siteRules.forEach { r -> container.addView(row(r) { BlockRules.remove(this, r); reload() }) }
            }
            val greyHosts = AppRules.hosts(this)
            if (greyHosts.isNotEmpty()) {
                any = true; container.addView(header("Greylisted sites (${GreyUsage.LIMIT_MIN} min/hour)"))
                greyHosts.forEach { (_, host) ->
                    container.addView(row(host) { AppRules.remove(this, false, host); reload() })
                }
            }
            if (!any) container.addView(TextView(this).apply {
                text = "Nothing blocked yet."; setPadding(0, (16 * dp).toInt(), 0, 0)
            })
        }
        reload()
    }

private fun appLabel(pkg: String): String = try {
    packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
} catch (t: Throwable) { pkg }

// ── Statistics ─────────────────────────────────────────────────────────────
private val DOW_ORDER = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private fun hourOf(ts: Long) = SimpleDateFormat("H", Locale.US).format(Date(ts)).toIntOrNull() ?: 0
private fun dowName(ts: Long) = SimpleDateFormat("EEE", Locale.US).format(Date(ts))
private fun topCounts(items: List<String>, limit: Int = 8): List<Pair<String, Int>> =
    items.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
private val HOUR_LABELS = mapOf(0 to "12a", 6 to "6a", 12 to "12p", 18 to "6p", 23 to "11p")

private fun showStatsMenu() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { showReportScreen() })
    root.addView(titleText("Statistics"))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(pickCard("Progress & reward") { showProgress() })
    list.addView(pickCard("Temptation patterns") { showTemptationStats() })
    list.addView(pickCard("Relapse patterns") { showRelapseStats() })
    list.addView(pickCard("Unlock attempts") { showLoosenStats() })
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
    })
    setContentView(root)
}

// ── Progress & reward: the non-resetting consistency score + real stats ─────
private fun showProgress() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val s = Progress.snapshot(this)
    val green = 0xFF2E7D32.toInt(); val teal = 0xFF2E9E8F.toInt()
    val root = vbox(pad)
    root.addView(backText { showStatsMenu() })
    root.addView(titleText("Progress"))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentView(root)

    if (!s.hasData) {
        c.addView(TextView(this).apply {
            text = "This fills in as you use the app. Ride out an urge or get through a wait, and your consistency and reclaimed time start showing here."
            textSize = 15f; setTextColor(0xFF6B7075.toInt()); setPadding(0, (12 * dp).toInt(), 0, 0)
        })
        return
    }

    // headline: consistency that never resets to zero
    c.addView(statBigCard("${s.consistency}%", "consistency",
        "${s.cleanDays} of the last ${s.trackedDays} days clean", green))
    c.addView(TextView(this).apply {
        text = "One slip never resets this \u2014 it only dips it a little. The goal is the trend, not a perfect streak."
        textSize = 13f; setTextColor(0xFF6B7075.toInt()); setPadding(0, (8 * dp).toInt(), 0, 0)
    })
    if (s.forgivingRun > 0) c.addView(TextView(this).apply {
        text = "Current run: ${s.forgivingRun} day${if (s.forgivingRun == 1) "" else "s"} \u2014 one slip won't end it."
        textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(green); setPadding(0, (8 * dp).toInt(), 0, 0)
    })

    c.addView(sectionTitle("Time reclaimed"))
    c.addView(statBigCard("${s.reclaimedHours}h", "reclaimed so far",
        "estimated \u2014 about ${Progress.EST_MIN_PER_WIN} min per urge you rode out", teal))

    c.addView(sectionTitle("Heading the right way"))
    c.addView(TrendView(this, s.weeklyWins), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, (120 * dp).toInt()))
    c.addView(TextView(this).apply {
        text = "urges ridden out per week (last 8 weeks)"
        textSize = 12f; setTextColor(0xFF9AA0A6.toInt()); setPadding(0, (4 * dp).toInt(), 0, 0)
    })

    c.addView(sectionTitle("If you keep this pace"))
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val cardH = statBigCard("~${s.projYearHours}h", "per year", null, teal).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = (4 * dp).toInt() }
    }
    val cardM = statBigCard("~\u00a3${s.projYearGbp}", "per year", null, green).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (4 * dp).toInt() }
    }
    row.addView(cardH); row.addView(cardM)
    c.addView(row)
    c.addView(TextView(this).apply {
        text = "projected from your recent pace \u00b7 reclaimed time valued at ~\u00a3${Progress.VALUE_PER_HOUR_GBP}/hr"
        textSize = 12f; setTextColor(0xFF9AA0A6.toInt()); setPadding(0, (4 * dp).toInt(), 0, 0)
    })

    c.addView(sectionTitle("Milestones"))
    if (s.milestones.isEmpty()) c.addView(TextView(this).apply {
        text = "None yet \u2014 they're coming."; textSize = 14f; setTextColor(0xFF9AA0A6.toInt())
    })
    s.milestones.forEach { m ->
        c.addView(TextView(this).apply {
            text = "\uD83C\uDFC5  $m"; textSize = 15f; setPadding(0, (5 * dp).toInt(), 0, (5 * dp).toInt())
        })
    }
    s.nextMilestone?.let { nm ->
        c.addView(TextView(this).apply {
            text = "\u25CB  Next: $nm"; textSize = 14f; setTextColor(0xFF9AA0A6.toInt())
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        })
    }
}

private fun statBigCard(value: String, label: String, sub: String?, accent: Int): LinearLayout {
    val dp = resources.displayMetrics.density
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 14 * dp; setColor(0xFFF3F6F5.toInt())
        }
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dp).toInt() }
        addView(TextView(this@MainActivity).apply {
            text = value; textSize = 30f; setTypeface(typeface, Typeface.BOLD); setTextColor(accent)
        })
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = 14f; setTextColor(0xFF4A4F54.toInt())
        })
        if (sub != null) addView(TextView(this@MainActivity).apply {
            text = sub; textSize = 12f; setTextColor(0xFF80868B.toInt()); setPadding(0, (4 * dp).toInt(), 0, 0)
        })
    }
}

private fun statsPage(title: String, back: () -> Unit, build: (LinearLayout) -> Unit) {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { back() })
    root.addView(titleText(title))
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(content)
    })
    setContentView(root)
    build(content)
}

private fun showTemptationStats() {
    val events = TemptationLog.all(this)
    statsPage("Temptation patterns", { showStatsMenu() }) { c ->
        if (events.isEmpty()) { c.addView(emptyStat()); return@statsPage }
        c.addView(summaryLine("${events.size} urges ridden out"))
        c.addView(sectionTitle("Time of day"))
        val hours = IntArray(24); events.forEach { hours[hourOf(it.ts).coerceIn(0, 23)]++ }
        c.addView(vBars(hours, HOUR_LABELS))
        c.addView(sectionTitle("Day of week"))
        c.addView(hBars(DOW_ORDER.map { d -> d to events.count { dowName(it.ts) == d } }))
        c.addView(sectionTitle("Where"))
        c.addView(hBars(topCounts(events.mapNotNull { it.location })))
        c.addView(sectionTitle("What you saw"))
        c.addView(hBars(topCounts(events.mapNotNull { it.screen })))
        c.addView(sectionTitle("How you felt"))
        c.addView(hBars(topCounts(events.mapNotNull { it.feeling })))
        c.addView(sectionTitle("Urge strength"))
        c.addView(hBars(Opts.URGE_LEVELS.map { lvl -> lvl to events.count { it.urge == lvl } }))
        c.addView(sectionTitle("Last 14 days"))
        c.addView(vBars(TemptationLog.dailyCounts(this, 14), mapOf(0 to "-13", 13 to "now")))
    }
}

private fun showRelapseStats() {
    statsPage("Relapse patterns", { showStatsMenu() }) { c ->
        c.addView(summaryLine("Loading\u2026"))
        lifecycleScope.launch {
            val list = RelapseLog.all(this@MainActivity)
            c.removeAllViews()
            if (list.isEmpty()) { c.addView(emptyStat()); return@launch }
            c.addView(summaryLine("${list.size} reports"))
            c.addView(sectionTitle("Time of day"))
            val hours = IntArray(24); list.forEach { if (it.hourOfDay in 0..23) hours[it.hourOfDay]++ }
            c.addView(vBars(hours, HOUR_LABELS))
            c.addView(sectionTitle("Day of week"))
            val cal = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            c.addView(hBars(DOW_ORDER.map { d -> d to list.count { cal.getOrElse(it.dayOfWeek - 1) { "" } == d } }))
            c.addView(sectionTitle("Where"))
            c.addView(hBars(topCounts(list.mapNotNull { it.room })))
            c.addView(sectionTitle("How you felt"))
            c.addView(hBars(topCounts(list.mapNotNull { it.feeling })))
            c.addView(sectionTitle("What led in"))
            c.addView(hBars(topCounts(list.mapNotNull { it.activity })))
        }
    }
}

private fun showLoosenStats() {
    val events = LoosenLog.all(this)
    statsPage("Unlock attempts", { showStatsMenu() }) { c ->
        if (events.isEmpty()) { c.addView(emptyStat()); return@statsPage }
        c.addView(summaryLine("${events.size} attempts"))
        c.addView(sectionTitle("How they ended"))
        val names = mapOf("stopped" to "Stopped", "tomorrow" to "Left till tomorrow", "looked" to "Looked")
        c.addView(hBars(listOf("stopped", "tomorrow", "looked")
            .map { (names[it] ?: it) to events.count { e -> e.outcome == it } }))
        c.addView(sectionTitle("What they hoped to quiet"))
        c.addView(hBars(topCounts(events.mapNotNull { it.feeling })))
        c.addView(sectionTitle("Time of day"))
        val hours = IntArray(24); events.forEach { hours[hourOf(it.ts).coerceIn(0, 23)]++ }
        c.addView(vBars(hours, HOUR_LABELS))
    }
}

// ── chart building blocks ──────────────────────────────────────────────────
private fun emptyStat(): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = "Nothing logged yet."; textSize = 15f; setTextColor(0xFF9AA0A6.toInt())
        setPadding(0, (16 * dp).toInt(), 0, 0)
    }
}
private fun summaryLine(t: String): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = t; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
    }
}
private fun sectionTitle(t: String): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = t; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF6B7075.toInt())
        setPadding(0, (18 * dp).toInt(), 0, (6 * dp).toInt())
    }
}

private fun hBars(pairs: List<Pair<String, Int>>): View {
    val dp = resources.displayMetrics.density
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (pairs.isEmpty()) {
        col.addView(TextView(this).apply { text = "No data yet."; textSize = 13f; setTextColor(0xFF9AA0A6.toInt()) })
        return col
    }
    val max = (pairs.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    pairs.forEach { (label, value) ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 13f; maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3.2f)
        })
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, (16 * dp).toInt(), 5f)
        }
        track.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 3 * dp; setColor(0xFF6FA8DC.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, value.toFloat().coerceAtLeast(0.001f))
        })
        track.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (max - value).toFloat().coerceAtLeast(0.001f))
        })
        row.addView(track)
        row.addView(TextView(this).apply {
            text = "  $value"; textSize = 13f; gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
        })
        col.addView(row)
    }
    return col
}

private fun vBars(values: IntArray, sparseLabels: Map<Int, String>): View {
    val dp = resources.displayMetrics.density
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (90 * dp).toInt())
    }
    values.forEachIndexed { _, v ->
        val colv = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            val m = (1 * dp).toInt(); setPadding(m, 0, m, 0)
        }
        colv.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, (max - v).toFloat().coerceAtLeast(0.001f))
        })
        colv.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 2 * dp; setColor(if (v > 0) 0xFF6FA8DC.toInt() else 0x22000000)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, v.toFloat().coerceAtLeast(0.04f))
        })
        row.addView(colv)
    }
    wrap.addView(row)
    if (sparseLabels.isNotEmpty()) {
        val lrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        values.indices.forEach { i ->
            lrow.addView(TextView(this).apply {
                text = sparseLabels[i] ?: ""; textSize = 9f; gravity = Gravity.CENTER
                setTextColor(0xFF9AA0A6.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        wrap.addView(lrow)
    }
    return wrap
}


    // ── ride the wave: click-through, one idea per screen ──────────────────────
    private fun startRideWave() {
        waveStartAt = System.currentTimeMillis()
        waveWalk()   // questions first; the breathing now lives at the "stuck" step
    }

    private fun waveWalk() {
        tBack = { stopRideTimer(); temptationUrgeScreen() }
        waveActionScreen(
            "Can you step outside \u2014 even just a short walk?", "\uD83D\uDEB6",
            "Yes \u2014 I'll go now", { waveSuccess() },
            "Not right now", { waveMove() },
        )
    }
    private fun waveMove() {
        tBack = { waveWalk() }
        waveActionScreen(
            "Can you move to a different room?", "\uD83D\uDEAA",
            "Done \u2014 I've moved", { waveSuccess() },
            "Can't right now", { wavePhysical() },
        )
    }
    private fun wavePhysical() {
        tBack = { waveMove() }
        waveActionScreen(
            "Can you do something physical \u2014 stretch, press-ups, tidy up?", "\uD83E\uDD38",
            "Yes \u2014 doing it", { waveSuccess() },
            "I can't do any of these", { waveStuck() },
        )
    }

    private fun waveStuck() {
        tBack = { wavePhysical() }
        waveBreatheScreen(
            "Then just breathe and wait",
            "You don't have to do anything but outlast it. The wave always passes \u2014 you only have to get through this one.",
            "I've breathed \u2014 what now?",
        ) { wavePeakScreen() }
    }

    /** After the breathing: make a real moment of "you're already past the peak". */
    private fun wavePeakScreen() {
        tBack = { waveStuck() }
        val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(backText { temptationBack() })
        root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(TextView(this).apply {
            text = "\uD83C\uDF0A"; textSize = 64f; gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "You've already cleared the hardest part."
            textSize = 26f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, (10 * dp).toInt())
        })
        root.addView(TextView(this).apply {
            text = "An urge peaks within the first 30 seconds or so \u2014 and you just rode straight through it. From here it only fades. You can get through this."
            textSize = 16f; gravity = Gravity.CENTER; setTextColor(0xFF4A4F54.toInt())
        })
        root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bigChoice("I've got through it", 0xFF2E7D32.toInt()) { waveSuccess() })
        setContentView(root)
    }

    private fun waveSuccess() {
        stopRideTimer()
        inTemptationFlow = false; onReportScreen = true; tBack = null
        TemptationLog.record(
                this,
                urge = Opts.URGE_LEVELS.getOrElse(tUrgeIndex) { "" },
                screen = tAnswers[TGroup.SCREEN],
                location = tAnswers[TGroup.PLACE],
                feeling = tAnswers[TGroup.FEELING],
                doing = tAnswers[TGroup.DOING],
        )
        val total = TemptationLog.total(this)
        val week = TemptationLog.dailyCounts(this, 7).sum()
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(titleText("That was you, beating it."))
        root.addView(TextView(this).apply {
            text = "Every urge you ride out makes the next one weaker."
            textSize = 16f; setTextColor(0xFF4A4F54.toInt()); setPadding(0, (4 * dp).toInt(), 0, 0)
        })
        // urge over time: it spikes, then falls — and you're already past the peak.
        root.addView(PeakCurveView(this), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = (12 * dp).toInt(); bottomMargin = (12 * dp).toInt() })
        root.addView(TextView(this).apply {
            text = "$total ridden out  \u00b7  $week this week"
            textSize = 15f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * dp).toInt())
        })
        root.addView(captionedButton("Put the phone down", "closes the app", 0xFF2E7D32.toInt()) {
            try { finishAffinity() } catch (_: Throwable) { setupMainScreen() }
        })
        root.addView(TextView(this).apply {
            text = "or lock apps for 30 minutes"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(0xFF48606A.toInt()); isClickable = true; isFocusable = true
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener {
                Lockdown.start(this@MainActivity)
                Toast.makeText(this@MainActivity, "Locked down for 30 min. Essentials still work.", Toast.LENGTH_LONG).show()
                setupMainScreen()
            }
        })
        setContentView(root)
    }

// ── reusable ride pieces ───────────────────────────────────────────────────
private fun waveBreatheScreen(title: String, side: String, continueLabel: String, onContinue: () -> Unit) {
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val totalBreaths = 3
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(backText { temptationBack() })
    root.addView(titleText(title))
    root.addView(TextView(this).apply {
        text = side; textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF6B7075.toInt())
        setPadding(0, 0, 0, (4 * dp).toInt())
    })

    // Big orb, straight on the page (no dark card), filling the free space.
    val orb = BreathOrbView(this, 0xFF2E9E8F.toInt())
    val orbBox = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(orb, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    root.addView(orbBox)

    val breatheLabel = TextView(this).apply {
        text = "Breathe in"; textSize = 18f; gravity = Gravity.CENTER; setPadding(0, (10 * dp).toInt(), 0, 0)
    }
    root.addView(breatheLabel)
    val counter = TextView(this).apply {
        text = "Follow the orb \u2014 $totalBreaths slow breaths"
        textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF2E7D32.toInt())
        setPadding(0, (6 * dp).toInt(), 0, 0)
    }
    root.addView(counter)
    val milestone = TextView(this).apply {
        textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFF2E7D32.toInt()); setPadding(0, (8 * dp).toInt(), 0, 0)
    }
    root.addView(milestone)

    val continueBtn = bigContinue(continueLabel) { onContinue() }
    root.addView(continueBtn)
    setContentView(root)

    stopRideTimer()   // cancels any orb/timer left over from a previous wave screen
    waveOrb = BreathOrbAnimator(orb, breatheLabel).also { a ->
        a.start(
            cycles = totalBreaths,
            onCycle = { done, total ->
                if (done >= total) {
                    counter.text = "Done \u2014 nicely paced"
                    breatheLabel.text = ""
                    tuneContinue(continueBtn, true)
                } else {
                    counter.text = "$done of $total done"
                }
            },
        )
    }
    attachWaveTimer(milestone)
}

private fun waveActionScreen(
    prompt: String, icon: String,
    yesLabel: String, onYes: () -> Unit, noLabel: String, onNo: () -> Unit,
    tertiaryLabel: String? = null, onTertiary: (() -> Unit)? = null,
) {
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(backText { temptationBack() })
    root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(TextView(this).apply {
        text = icon; textSize = 72f; gravity = Gravity.CENTER
    })
    root.addView(TextView(this).apply {
        text = prompt; textSize = 23f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding((8 * dp).toInt(), (18 * dp).toInt(), (8 * dp).toInt(), 0)
    })
    val milestone = TextView(this).apply {
        textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFF2E7D32.toInt())
        setPadding(0, (10 * dp).toInt(), 0, 0)
    }
    root.addView(milestone)
    root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(bigChoice(yesLabel, 0xFF2E7D32.toInt()) { onYes() })
    root.addView(Button(this).apply { text = noLabel; setAllCaps(false); setOnClickListener { onNo() } })
    if (tertiaryLabel != null && onTertiary != null) {
        root.addView(TextView(this).apply {
            text = tertiaryLabel; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(0xFF48606A.toInt())
            setPadding(0, (8 * dp).toInt(), 0, (10 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { onTertiary() }
        })
    }
    setContentView(root)
    attachWaveTimer(milestone)
}

// Quiet milestone line — only speaks at 30s / 1m / 2m / 10m, nothing after.
private fun attachWaveTimer(label: TextView) {
    rideRunnable?.let { rideHandler?.removeCallbacks(it) }
    rideHandler = Handler(Looper.getMainLooper())
    rideRunnable = object : Runnable {
        override fun run() {
            val sec = (System.currentTimeMillis() - waveStartAt) / 1000
            label.text = when {
                sec >= 600 -> "10 minutes in \u2014 it's faded. You did this."
                sec >= 120 -> "2 minutes in \u2014 you're riding it out."
                sec >= 60 -> "1 minute in \u2014 the peak has passed."
                sec >= 30 -> "30 seconds in \u2014 you're doing it, keep going."
                else -> ""
            }
            rideHandler?.postDelayed(this, 1000)
        }
    }
    rideRunnable?.run()
}

private fun progressChart(counts: IntArray): View {
    val dp = resources.displayMetrics.density
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (90 * dp).toInt())
            .apply { topMargin = (16 * dp).toInt() }
    }
    counts.forEach { v ->
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            val m = (2 * dp).toInt(); setPadding(m, 0, m, 0)
        }
        col.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, (max - v).toFloat())
        })
        col.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 3 * dp; setColor(if (v > 0) 0xFF2E7D32.toInt() else 0x22000000)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, v.toFloat().coerceAtLeast(0.04f))
        })
        row.addView(col)
    }
    return row
}


    // ── "Report an app/site" flow ──────────────────────────────────────────────
    private var inAppSiteFlow = false

    private fun startAppSiteFlow() {
        onReportScreen = true
        inAppSiteFlow = true
        appSiteChooseKind()
    }

    private fun appSiteBack() {
        inAppSiteFlow = false
        showReportScreen()
    }

    private fun appSiteChooseKind() {
        inAppSiteFlow = true
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(backText { appSiteBack() })
        root.addView(titleText("What do you want to limit?"))
        root.addView(TextView(this).apply {
            text = "Set this now, while you're calm \u2014 the app just honours it later. " +
                "No content scanning, no screenshots."
            textSize = 14f; setTextColor(0xFF6B7075.toInt())
            setPadding(0, 0, 0, (16 * dp).toInt())
        })
        root.addView(bigChoice("An app on this phone", 0xFF3E535C.toInt()) { appSiteChooseApp() })
        root.addView(bigChoice("A website", 0xFF3E535C.toInt()) { appSiteChooseSite() })
        setContentView(root)
    }

private fun appSiteChooseSite() {
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(backText { appSiteChooseKind() })
    root.addView(titleText("Add a website"))
    val urlInput = EditText(this).apply {
        hint = "paste or type a web address"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        maxLines = 1
    }
    root.addView(urlInput)
    root.addView(tierNote())
    root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(bigChoice("Greylist it \u2014 ${GreyUsage.LIMIT_MIN} min / hour", 0xFF3E535C.toInt()) {
        saveSiteRule(urlInput, AppRules.GREY)
    })
    root.addView(bigChoice("Blocklist it \u2014 block outright", 0xFFB00020.toInt()) {
        saveSiteRule(urlInput, AppRules.BLOCK)
    })
    setContentView(root)
}


private data class AppRow(val label: String, val pkg: String, val icon: android.graphics.drawable.Drawable?)

private fun appSiteChooseApp() {
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(backText { appSiteChooseKind() })
    root.addView(titleText("Pick an app"))
    val loading = TextView(this).apply { text = "Loading apps\u2026"; textSize = 14f }
    root.addView(loading)
    val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(listLayout)
    })
    setContentView(root)

    lifecycleScope.launch(Dispatchers.IO) {
        val apps = loadLaunchableApps()
        runOnUiThread {
            loading.visibility = View.GONE
            apps.forEach { a -> listLayout.addView(appRow(a) { appSiteAppTier(a) }) }
        }
    }
}

private fun appRow(a: AppRow, onClick: () -> Unit): LinearLayout {
    val dp = resources.displayMetrics.density
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt())
        isClickable = true; isFocusable = true
        addView(ImageView(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
            if (a.icon != null) setImageDrawable(a.icon)
        })
        addView(TextView(this@MainActivity).apply {
            text = a.label; textSize = 16f; setPadding((12 * dp).toInt(), 0, 0, 0)
        })
        setOnClickListener { onClick() }
    }
}

private fun appSiteAppTier(a: AppRow) {
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(backText { appSiteChooseApp() })
    root.addView(titleText("Limit ${a.label}?"))
    root.addView(tierNote())
    root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(bigChoice("Greylist \u2014 ${GreyUsage.LIMIT_MIN} min / hour", 0xFF3E535C.toInt()) {
        AppRules.setApp(this, a.pkg, AppRules.GREY); appSiteSaved(a.label, AppRules.GREY)
    })
    root.addView(bigChoice("Blocklist \u2014 block outright", 0xFFB00020.toInt()) {
        AppRules.setApp(this, a.pkg, AppRules.BLOCK); appSiteSaved(a.label, AppRules.BLOCK)
    })
    setContentView(root)
}

private fun loadLaunchableApps(): List<AppRow> {
    val pm = packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
        val p = ri.activityInfo?.packageName ?: return@mapNotNull null
        if (p == packageName) return@mapNotNull null
        AppRow(ri.loadLabel(pm).toString(), p, try { ri.loadIcon(pm) } catch (t: Throwable) { null })
    }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
}

private fun saveSiteRule(input: EditText, tier: String) {
    if (tier == AppRules.BLOCK) {
        val rule = ruleFromInput(input.text.toString())
        if (rule == null) { Toast.makeText(this, "Couldn't read a web address.", Toast.LENGTH_SHORT).show(); return }
        BlockRules.add(this, rule)            // keeps the path -> blocks that page, not the whole site
        appSiteSaved(rule, AppRules.BLOCK)
    } else {
        val host = hostOf(input.text.toString())
        if (host == null) { Toast.makeText(this, "Couldn't read a web address.", Toast.LENGTH_SHORT).show(); return }
        AppRules.setHost(this, host, AppRules.GREY)   // greylist is per-site time, so whole host
        appSiteSaved(host, AppRules.GREY)
    }
}

// Mirrors BlockRules' own URL normalisation: a path -> page rule, bare domain -> domain rule.
private fun ruleFromInput(input: String): String? {
    var s = input.trim().lowercase()
    if (s.isEmpty()) return null
    s = s.substringAfter("://", s)   // drop scheme
    s = s.substringBefore('#')       // drop fragment
    s = s.trimEnd('/')
    if (s.isEmpty()) return null
    return if ('/' in s) s else s.removePrefix("www.")
}

private fun appSiteSaved(target: String, tier: String) {
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(titleText("Saved"))
    root.addView(TextView(this).apply {
        text = "$target is now " +
            (if (tier == AppRules.GREY) "greylisted \u2014 ${GreyUsage.LIMIT_MIN} minutes each hour"
             else "blocklisted \u2014 blocked outright") +
            ". It's in effect right away."
        textSize = 16f; setPadding(0, (12 * dp).toInt(), 0, 0)
    })
    val spacer = View(this)
    root.addView(spacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(Button(this).apply { text = "Add another"; setOnClickListener { appSiteChooseKind() } })
    root.addView(Button(this).apply { text = "Done"; setOnClickListener { showReportScreen() } })
    setContentView(root)
}

private fun tierNote(): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = "Greylist = ${GreyUsage.LIMIT_MIN} minutes each hour, then paused.\n" +
            "Blocklist = blocked completely."
        textSize = 13f; setTextColor(0xFF6B7075.toInt())
        setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
    }
}

private fun hostOf(input: String): String? {
    var s = input.trim().lowercase()
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "https://$s"
    val h = try { Uri.parse(s).host } catch (t: Throwable) { null } ?: return null
    return h.removePrefix("www.").ifBlank { null }
}

private fun showRecentBlocks() {
    val pad = (12 * resources.displayMetrics.density).toInt()
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }
    val scroll = ScrollView(this).apply { addView(container) }
    val dialog = AlertDialog.Builder(this)
        .setTitle("Recent blocks (past hour)")
        .setView(scroll)
        .setPositiveButton(android.R.string.ok, null)
        .create()

    val stamp = SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.getDefault())
    val dividerColor = 0x14000000                                  // ~8% black, very subtle
    val dividerH = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    fun reload() {
        lifecycleScope.launch {
            val items = BlockEventLog.recent(this@MainActivity, 60 * 60 * 1000L)
            container.removeAllViews()
            if (items.isEmpty()) {
                container.addView(TextView(this@MainActivity).apply {
                    text = "(nothing in the last hour)"
                })
                return@launch
            }
            items.forEachIndexed { index, e ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, pad / 2, 0, pad / 2)
                }
                val target = e.url ?: e.host ?: e.packageName ?: "(unknown)"
                val shortTarget = if (target.length > 40) target.take(40) + "\u2026" else target
                val scoreTag = e.score?.let { "[score $it]  " } ?: ""
                val before = e.recentAppsList().joinToString(", ").ifBlank { "\u2014" }
                row.addView(TextView(this@MainActivity).apply {
                    text = "${stamp.format(Date(e.timestamp))}\n$scoreTag$shortTarget\nbefore: $before"
                    textSize = 13f
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this@MainActivity).apply {
                    text = "Remove"
                    setOnClickListener { BlockEventLog.remove(this@MainActivity, e.id); reload() }
                })
                container.addView(row)
                if (index < items.lastIndex) {
                    container.addView(View(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dividerH)
                        setBackgroundColor(dividerColor)
                    })
                }
            }
        }
    }
    reload()
    dialog.show()
}

// ── Report screen: 4 equal full-width panes ────────────────────────────────
// ── Disguised home: a productivity face; the addiction tools live behind a tab ─
private fun setupHomeScreen() {
    onHomeScreen = true; onTemptationsTab = false; onReportScreen = false; onDevScreen = false
    inSubPage = false; inRelapseFlow = false; inTemptationFlow = false
    inLoosenFlow = false; inAppSiteFlow = false
    stopRideTimer(); stopLoosenTimer(); entriesJob?.cancel()
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

    // ── FIRST: what you've reclaimed (reward, don't punish) ─────────────────
    val green = 0xFF2E7D32.toInt(); val teal = 0xFF2E9E8F.toInt()
    val s = Progress.snapshot(this)
    content.addView(TextView(this).apply {
        text = "What you've reclaimed"; textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
        setPadding(0, 0, 0, (12 * dp).toInt())
    })
    if (s.hasData) {
        content.addView(statBigCard("${s.reclaimedHours}h", "reclaimed so far",
            "about ${Progress.EST_MIN_PER_WIN} min back for every urge you rode out", teal))
        content.addView(statBigCard("${s.consistency}%", "consistency",
            "${s.cleanDays} of the last ${s.trackedDays} days clean \u2014 one slip never resets it", green))
    } else {
        content.addView(statBigCard("0h", "reclaimed so far",
            "ride out your first urge and your reclaimed time starts here", teal))
    }

    // ── The graphic: what the scroll costs, over a number of years ──────────
    content.addView(sectionTitle("What the scroll costs you"))
    val hero = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 18 * dp; setColor(0xFFF4F6F8.toInt()) }
        val p = (18 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val donut = WastedDonutView(this)
    hero.addView(donut, LinearLayout.LayoutParams((168 * dp).toInt(), (168 * dp).toInt()).apply {
        gravity = Gravity.CENTER_HORIZONTAL; topMargin = (4 * dp).toInt(); bottomMargin = (6 * dp).toInt()
    })
    val bigStat = TextView(this).apply { textSize = 26f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(0xFFE4673B.toInt()) }
    val subStat = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF52606A.toInt()); setPadding(0, (2 * dp).toInt(), 0, 0) }
    val lifeStat = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF52606A.toInt()); setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt()) }
    hero.addView(bigStat); hero.addView(subStat); hero.addView(lifeStat)
    val minLabel = TextView(this).apply { textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt()) }
    hero.addView(minLabel)
    val minSeek = android.widget.SeekBar(this).apply { max = 300; progress = Usage.minutes(this@MainActivity).coerceIn(0, 300) }
    hero.addView(minSeek)
    val yearLabel = TextView(this).apply { textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (8 * dp).toInt(), 0, 0) }
    hero.addView(yearLabel)
    val yearSeek = android.widget.SeekBar(this).apply { max = 49; progress = (Usage.years(this@MainActivity) - 1).coerceIn(0, 49) }
    hero.addView(yearSeek)
    content.addView(hero)

    // ── big "Productivity" button (everything else lives behind it) ─────────
    content.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * dp; setColor(teal) }
        val p = (18 * dp).toInt(); setPadding(p, (16 * dp).toInt(), p, (16 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * dp).toInt() }
        isClickable = true; isFocusable = true; setOnClickListener { showProductivity() }
        addView(TextView(this@MainActivity).apply {
            text = "Productivity"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@MainActivity).apply { text = "\u2192"; textSize = 22f; setTextColor(0xFFFFFFFF.toInt()) })
    })

    // ── tools, then temptations ─────────────────────────────────────────────
    content.addView(TextView(this).apply {
        text = "TOOLS"; textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF9AA0A6.toInt())
        setPadding((2 * dp).toInt(), (22 * dp).toInt(), 0, (6 * dp).toInt())
    })
    content.addView(homeCard("Temptations", "Manage urges and stay on track.") { showTemptationsTab() })

    // ── About & privacy (moved off the dev page) ────────────────────────────
    content.addView(homeCard("About & privacy", "How this app works and what it stores.") { showAboutPage() })

    // ── Dev tools (only when dev mode is on) ────────────────────────────────
    if (AppConfig.DEV_MODE) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * dp; setStroke((1 * dp).toInt(), 0xFFB0B6BB.toInt()); setColor(0x00000000)
            }
            val p = (14 * dp).toInt(); setPadding(p, (12 * dp).toInt(), p, (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() }
            isClickable = true; isFocusable = true; setOnClickListener { setupMainScreen() }
            addView(TextView(this@MainActivity).apply {
                text = "\uD83D\uDD27  Dev tools"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF5A6068.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply { text = "\u203A"; textSize = 20f; setTextColor(0xFF9AA0A6.toInt()) })
        })
    }

    // permission/status console, at the bottom of the opening page
    content.addView(permissionConsole())

    fun refresh() {
        val min = Usage.minutes(this); val yrs = Usage.years(this)
        val perYearHours = min * 365.0 / 60.0
        val wakingDaysYr = (perYearHours / Usage.WAKING_HOURS)
        val gbpYr = Math.round(perYearHours * Usage.VALUE_PER_HOUR_GBP)
        val totalWakingYears = perYearHours * yrs / Usage.WAKING_HOURS / 365.0
        val gbpTotal = gbpYr * yrs
        donut.setFraction((min / (Usage.WAKING_HOURS * 60f)))
        bigStat.text = "${Math.round(wakingDaysYr)} waking days a year"
        subStat.text = "\u2248 \u00a3$gbpYr a year of your time"
        lifeStat.text = "Over $yrs year${if (yrs == 1) "" else "s"}: about ${String.format("%.1f", totalWakingYears)} years of waking life \u2014 and \u00a3$gbpTotal"
        minLabel.text = "$min minutes a day on short video & feeds"
        yearLabel.text = "Looking $yrs year${if (yrs == 1) "" else "s"} ahead"
    }

    val seekListener = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
            if (!fromUser) return
            if (sb === minSeek) Usage.setMinutes(this@MainActivity, p)
            else Usage.setYears(this@MainActivity, 1 + p)
            refresh()
        }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
    }
    minSeek.setOnSeekBarChangeListener(seekListener)
    yearSeek.setOnSeekBarChangeListener(seekListener)

    val root = ScrollView(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isFillViewport = true
        addView(content)
    }
    setContentView(root)
    refresh()
}

// Everything that used to sit under the home graphic now lives here.
private fun showProductivity() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
    content.addView(backText { setupHomeScreen() })
    content.addView(titleText("Productivity"))

    // Short-form blocking toggle
    val sfSub = TextView(this).apply { textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (2 * dp).toInt(), 0, 0) }
    val sfSwitch = android.widget.Switch(this).apply { isChecked = ShortForm.enabled() }
    val sfCard = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * dp; setColor(0xFFF4F6F8.toInt()) }
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() }
    }
    val sfText = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    sfText.addView(TextView(this).apply { text = "Block reels, shorts & feeds"; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt()) })
    sfText.addView(sfSub)
    sfCard.addView(sfText); sfCard.addView(sfSwitch)
    fun refreshSf() { sfSub.text = if (ShortForm.enabled()) "On \u2014 the endless feeds are blocked." else "Off \u2014 tap to cut the doomscroll." }
    sfSwitch.setOnCheckedChangeListener { _, checked -> ShortForm.setEnabled(this, checked); refreshSf() }
    refreshSf()
    content.addView(sfCard)

    // Your next year as days
    content.addView(sectionTitle("Your next year"))
    val grid = TimeGridView(this)
    content.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val gridCaption = TextView(this).apply { textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (8 * dp).toInt(), 0, 0) }
    content.addView(gridCaption)

    // Opportunity cost
    content.addView(sectionTitle("Reclaim it and you could"))
    val oppBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(oppBox)

    val min = Usage.minutes(this)
    val perYearHours = min * 365.0 / 60.0
    val wakingDaysYr = Math.round(perYearHours / Usage.WAKING_HOURS).toInt()
    grid.setFilledDays(wakingDaysYr)
    gridCaption.text = "$wakingDaysYr of the next 365 days, gone to the feed"
    listOf(
        "${Math.round(perYearHours / 6.0)} books read \u2014 about 6 hours each",
        "${Math.round(perYearHours / 0.75)} proper workouts, 45 minutes apiece",
        "${Math.round(perYearHours / 480.0 * 100)}% of the way to conversational in a new language",
        "${Math.round(perYearHours / 8.0)} full nights of extra sleep",
    ).forEach { line ->
        oppBox.addView(TextView(this).apply {
            text = "\u2022  $line"; textSize = 15f; setTextColor(0xFF3A434B.toInt())
            setLineSpacing((3 * dp), 1f); setPadding(0, (6 * dp).toInt(), 0, 0)
        })
    }

    val root = ScrollView(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isFillViewport = true; addView(content)
    }
    setContentView(root)
}

private fun showTemptationsTab() {
    onTemptationsTab = true; onHomeScreen = false; onReportScreen = false; inSubPage = false
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { setupHomeScreen() })
    root.addView(titleText("Temptations"))
    root.addView(TextView(this).apply {
        text = "What are you managing?"; textSize = 15f; setTextColor(0xFF7B848C.toInt())
        setPadding(0, 0, 0, (10 * dp).toInt())
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(homeCard("Sexual arousal  \u2642\uFE0F\u2640\uFE0F", "Tools for the moment, and the longer game.") {
        reportBackTarget = { showTemptationsTab() }; showReportScreen()
    })
    root.addView(list)
    root.addView(grow())
    root.addView(TextView(this).apply {
        text = "More areas later."; textSize = 13f; setTextColor(0xFF9AA0A6.toInt())
        setPadding(0, 0, 0, (8 * dp).toInt())
    })
    setContentView(root)
}

/** A clean tappable card for the home/tab screens (chevron shown when clickable). */
private fun homeCard(title: String, sub: String?, onClick: (() -> Unit)? = null): View {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * dp; setColor(0xFFF4F6F8.toInt())
        }
        val p = (18 * dp).toInt(); setPadding(p, (16 * dp).toInt(), p, (16 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * dp).toInt() }
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
    }
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    texts.addView(TextView(this).apply {
        text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
    })
    if (sub != null) texts.addView(TextView(this).apply {
        text = sub; textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (2 * dp).toInt(), 0, 0)
    })
    row.addView(texts)
    if (onClick != null) row.addView(TextView(this).apply {
        text = "\u203A"; textSize = 24f; setTextColor(0xFFB0B5BA.toInt())
    })
    return row
}

// ── Break the addiction protocol: gamified, sequential big moves ────────────
private fun showProtocol() {
    inSubPage = true; onHomeScreen = false; onTemptationsTab = false
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val appsDone = Protocol.appsDone(this)
    val holidayDone = Protocol.holidayDone(this)
    val strictActive = Mode.isLocked(this)
    val sevenStarted = Protocol.sevenStarted(this)
    val root = vbox(pad)
    root.addView(backText { showReportScreen() })
    root.addView(titleText("Break the addiction protocol"))
    root.addView(TextView(this).apply {
        text = "Two moves do most of the work: a real break away from your device, then locking it down hard for the week after. Everything else supports those two."
        textSize = 15f; setTextColor(0xFF6B7075.toInt()); setPadding(0, 0, 0, (10 * dp).toInt())
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    // ── SUPPORTING TO-DOS first (the little things) ─────────────────────────
    list.addView(TextView(this).apply {
        text = "BUILD THE WALLS AROUND IT"; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFF9AA0A6.toInt()); setPadding((2 * dp).toInt(), 0, 0, (8 * dp).toInt())
    })
    list.addView(protocolStep(0, "Rearrange your apps",
        "Get the troublesome ones out of easy reach.", appsDone, false, null) { showProtocolApps() })
    list.addView(protocolGuidanceCard("Keep your phone out of the bedroom \u2014 get an alarm clock",
        "The phone-by-the-bed habit is where most late-night relapses start. Replacing what the phone does at night is one of the highest-impact moves. Tap to see how.")
        .apply { isClickable = true; isFocusable = true; setOnClickListener { showProtocolReplace() } })
    val checks = listOf(
        "out_of_house" to ("Be out of the house as much as possible" to "Spend the money if you have to \u2014 on anything that isn't addictive. Friends and social clubs most of all."),
        "delete_social" to ("Delete your social media accounts" to "Not just the apps \u2014 the accounts. Remove the pull entirely."),
        "new_background" to ("Set a new phone background" to "A clean visual reset every time you unlock."),
        "new_theme" to ("Change your app theme, if you can" to "Make the phone feel like a different, less familiar device."),
    )
    checks.forEach { (key, pair) ->
        val (t, sub) = pair
        list.addView(protocolCheckRow(key, t, sub))
    }

    // ── THE TWO KEY MOVES (now after the little things) ─────────────────────
    list.addView(TextView(this).apply {
        text = "\u2B50  THE TWO THAT MATTER MOST"; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFFB8860B.toInt()); setPadding((2 * dp).toInt(), (18 * dp).toInt(), 0, (8 * dp).toInt())
    })
    list.addView(protocolKeyStep("Go on holiday \u2014 without your device",
        "Step right out of the environment the habit lives in. This is the single biggest reset.",
        holidayDone) { showProtocolHoliday() })
    val sevenSub = when {
        strictActive -> "Active \u2014 ${Mode.daysLeft(this)} days left."
        sevenStarted -> "Completed. You can run it again any time."
        !holidayDone -> "Unlocks after the holiday \u2014 it's what protects the fresh start."
        else -> "Lock yourself out for 7 days straight, right after the holiday."
    }
    list.addView(protocolKeyStep("Super-strict lock for a week after",
        sevenSub, sevenStarted && !strictActive, locked = !holidayDone) {
        if (holidayDone) showProtocol7Day()
        else Toast.makeText(this, "Do the holiday first \u2014 it's what makes the lock stick.", Toast.LENGTH_SHORT).show()
    })

    // ── Additional tips (own page) ──────────────────────────────────────────
    list.addView(homeCard("Additional tips", "More ways to keep the phone out of your hands.") { showProtocolTips() })

    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
    })
    setContentView(root)
}

// A focused mini-page on replacing the phone's role (esp. at the bedside).
private fun showProtocolReplace() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { showProtocol() })
    root.addView(titleText("Replace what the phone does"))
    root.addView(TextView(this).apply {
        text = "The goal is simple: never need to bring your phone into the bedroom. If the phone isn't there at night, the highest-risk moments mostly disappear."
        textSize = 15f; setTextColor(0xFF4A4F54.toInt()); setPadding(0, 0, 0, (10 * dp).toInt())
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(protocolCheckRow("buy_alarm", "Buy a real alarm clock",
        "The single most important purchase. It removes the only honest reason to have the phone by your bed."))
    list.addView(protocolCheckRow("charge_outside", "Charge your phone in another room",
        "Pick a spot \u2014 kitchen, hallway \u2014 and make it the permanent overnight home for the phone."))
    list.addView(protocolCheckRow("buy_watch", "Wear a watch",
        "So you never reach for the phone just to check the time."))
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
    })
    setContentView(root)
}

// Read-through guidance, grouped on its own page.
private fun showProtocolTips() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { showProtocol() })
    root.addView(titleText("Additional tips"))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(protocolGuidanceCard("Don't bring your phone to bed or high-risk spots",
        "The bedroom, the bathroom, anywhere you've slipped before. Leave it charging in another room."))
    list.addView(protocolGuidanceCard("Change your state when an urge hits",
        "A shower, a cold blast at the end of it, a quick workout, stepping outside, a tight bedtime and wake-up routine, even a game \u2014 anything that breaks the moment and shifts how you feel."))
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
    })
    setContentView(root)
}

/** A tickable supporting to-do; persists via Protocol.isChecked. */
private fun protocolCheckRow(key: String, title: String, sub: String): View {
    val dp = resources.displayMetrics.density
    var checked = Protocol.isChecked(this, key)
    lateinit var marker: TextView
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * dp; setColor(0xFFF4F6F8.toInt()) }
        val p = (16 * dp).toInt(); setPadding(p, (14 * dp).toInt(), p, (14 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * dp).toInt() }
        isClickable = true; isFocusable = true
    }
    marker = TextView(this).apply {
        textSize = 18f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        val s = (30 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = (14 * dp).toInt() }
    }
    fun paint() {
        marker.text = if (checked) "\u2713" else ""
        marker.setTextColor(0xFFFFFFFF.toInt())
        marker.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (checked) 0xFF2E7D32.toInt() else 0xFFE2E6E9.toInt())
        }
    }
    paint()
    row.setOnClickListener {
        checked = !checked; Protocol.setChecked(this, key, checked); paint()
    }
    row.addView(marker)
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    texts.addView(TextView(this).apply {
        text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
    })
    texts.addView(TextView(this).apply {
        text = sub; textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (2 * dp).toInt(), 0, 0)
    })
    row.addView(texts)
    return row
}

/** A read-through guidance card (no number, no required tick). */
private fun protocolGuidanceCard(title: String, sub: String): View {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * dp; setColor(0xFFFCFAF3.toInt()); setStroke((1 * dp).toInt(), 0xFFEAE0C8.toInt())
        }
        val p = (16 * dp).toInt(); setPadding(p, (14 * dp).toInt(), p, (14 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * dp).toInt() }
    }
    row.addView(TextView(this).apply {
        text = "\uD83D\uDCA1"; textSize = 16f; setPadding(0, (1 * dp).toInt(), (12 * dp).toInt(), 0)
    })
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    texts.addView(TextView(this).apply {
        text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF6B5B14.toInt())
    })
    texts.addView(TextView(this).apply {
        text = sub; textSize = 13f; setTextColor(0xFF7A6F4A.toInt()); setPadding(0, (3 * dp).toInt(), 0, 0)
        setLineSpacing((2 * dp), 1f)
    })
    row.addView(texts)
    return row
}

/** A larger, highlighted "key move" step (for the two that matter most). */
private fun protocolKeyStep(title: String, sub: String, done: Boolean, locked: Boolean = false, onClick: () -> Unit): View {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 18 * dp
            setColor(if (done) 0xFFEAF5EC.toInt() else if (locked) 0xFFF2EFE6.toInt() else 0xFFFFF8E6.toInt())
            setStroke((if (done) 2 else 2 * 1).times(dp).toInt(), if (done) 0xFF2E7D32.toInt() else 0xFFD9B65A.toInt())
        }
        val p = (18 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * dp).toInt() }
        alpha = if (locked) 0.65f else 1f
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }
    val marker = TextView(this).apply {
        text = when { done -> "\u2713"; locked -> "\uD83D\uDD12"; else -> "\u2B50" }
        textSize = 20f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (done) 0xFFFFFFFF.toInt() else 0xFF8A6D1B.toInt())
        if (done) background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF2E7D32.toInt())
        }
        val s = (38 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = (14 * dp).toInt() }
    }
    row.addView(marker)
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    texts.addView(TextView(this).apply {
        text = title; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
    })
    texts.addView(TextView(this).apply {
        text = sub; textSize = 13f; setTextColor(0xFF6B6448.toInt()); setPadding(0, (3 * dp).toInt(), 0, 0)
        setLineSpacing((2 * dp), 1f)
    })
    row.addView(texts)
    return row
}

/** A numbered protocol step with a tick / lock / active state. */
private fun protocolStep(num: Int, title: String, sub: String, done: Boolean, locked: Boolean,
                         badge: String?, onClick: () -> Unit): View {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * dp; setColor(if (locked) 0xFFEDEFF1.toInt() else 0xFFF4F6F8.toInt())
        }
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * dp).toInt() }
        alpha = if (locked) 0.6f else 1f
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }
    // status marker: tick / lock / number
    val marker = TextView(this).apply {
        text = when { done -> "\u2713"; locked -> "\uD83D\uDD12"; else -> num.toString() }
        textSize = if (done || locked) 18f else 16f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (done) 0xFFFFFFFF.toInt() else 0xFF52606A.toInt())
        if (done) background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF2E7D32.toInt())
        } else if (!locked) background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFE2E6E9.toInt())
        }
        val s = (34 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = (14 * dp).toInt() }
    }
    row.addView(marker)
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    texts.addView(TextView(this).apply {
        text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
    })
    texts.addView(TextView(this).apply {
        text = sub; textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (2 * dp).toInt(), 0, 0)
    })
    row.addView(texts)
    if (badge == "active") row.addView(TextView(this).apply {
        text = "\u25CF"; textSize = 14f; setTextColor(0xFF2E7D32.toInt())
    })
    return row
}

private fun showProtocolApps() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { showProtocol() })
    root.addView(titleText("Rearrange your apps"))
    root.addView(body("Make the habit harder to reach by accident. Before anything else:"))
    listOf(
        "Move anything that tends to lead you in off your home screen \u2014 bury it in a folder, or remove the shortcut.",
        "Sign out of accounts so opening them isn't one tap.",
        "Delete the apps you don't truly need. The friction is the point.",
        "Add the rest to this app's block list so they're handled for you.",
    ).forEach { line ->
        root.addView(TextView(this).apply {
            text = "\u2022  $line"; textSize = 15f; setLineSpacing((4 * dp), 1f); setPadding(0, (6 * dp).toInt(), 0, 0)
        })
    }
    root.addView(grow())
    root.addView(bigChoice(if (Protocol.appsDone(this)) "Done \u2713" else "I've rearranged my apps", 0xFF2E7D32.toInt()) {
        Protocol.setApps(this, true); showProtocol()
    })
    setContentView(root)
}

private fun showProtocolHoliday() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { showProtocol() })
    root.addView(titleText("Go on holiday / break the routine"))
    root.addView(body("The habit is wired to a place and a rhythm. The fastest way to weaken it is to physically leave that environment for a while."))
    listOf(
        "Aim for a proper break \u2014 ideally around two weeks.",
        "Go without your phone if you can, or leave it locked down the whole time.",
        "Fill the days with people, movement and daylight \u2014 not screens.",
        "Come back to a home you've already rearranged, and start the 7-day lock fresh.",
    ).forEach { line ->
        root.addView(TextView(this).apply {
            text = "\u2022  $line"; textSize = 15f; setLineSpacing((4 * dp), 1f); setPadding(0, (6 * dp).toInt(), 0, 0)
        })
    }
    root.addView(grow())
    root.addView(bigChoice(if (Protocol.holidayDone(this)) "Done \u2713" else "I've taken the break", 0xFF2E7D32.toInt()) {
        Protocol.setHoliday(this, true); showProtocol()
    })
    setContentView(root)
}

private fun showProtocol7Day() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { showProtocol() })
    root.addView(titleText("7-day strict lock"))
    root.addView(body("Strict mode stays on for 7 days. You can't switch back to relaxed until it ends. It's most effective once you've reset with the holiday \u2014 you're protecting fresh ground, not fighting uphill."))
    if (Mode.isLocked(this)) {
        root.addView(TextView(this).apply {
            text = "Active \u2014 ${Mode.daysLeft(this@MainActivity)} days left."
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF2E7D32.toInt())
            setPadding(0, (12 * dp).toInt(), 0, 0)
        })
        root.addView(grow())
    } else {
        root.addView(grow())
        root.addView(bigChoice("Start the 7-day lock", 0xFF2E7D32.toInt()) {
            Protocol.setSevenStarted(this)
            Mode.startWeekStrict(this)
            Toast.makeText(this, "Strict mode on for 7 days", Toast.LENGTH_SHORT).show()
            showProtocol()
        })
    }
    setContentView(root)
}

private fun showReportScreen() {
    onReportScreen = true
    onHomeScreen = false
    onTemptationsTab = false
    inRelapseFlow = false
    inSubPage = false
    inTemptationFlow = false
    inLoosenFlow = false
    inAppSiteFlow = false
    stopLoosenTimer()
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    // ── top controls: back button (left) + mode dropdown (right) ────────────
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, (8 * dp).toInt())
    }
    val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    modeRow.addView(backText { reportBackTarget() }.apply {
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
    })
    modeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
    modeRow.addView(modeSpinner())
    top.addView(modeRow)
    top.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0xFF2E3F47.toInt()) }
        val p = (16 * dp).toInt(); setPadding(p, (14 * dp).toInt(), p, (14 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * dp).toInt() }
        isClickable = true; isFocusable = true; setOnClickListener { showProtocol() }
        addView(TextView(this@MainActivity).apply {
            text = "Break the addiction protocol"; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@MainActivity).apply { text = "\u203A"; textSize = 22f; setTextColor(0xFFAEB6BB.toInt()) })
    })
    root.addView(top)
    // Four main panes (weighted) + a thinner Statistics pane at the bottom.
    root.addView(reportPane("Report an app/site", 0xFF34464E.toInt()) { onReportAppSite() })
    root.addView(reportPane("I feel temptation", 0xFF3E535C.toInt()) { onFeelTemptation() })
    root.addView(reportPane("I'm going to look anyway", 0xFF48606A.toInt()) { onLookAnyway() })
    root.addView(reportPane("Report relapse", 0xFF526D78.toInt()) { onReportRelapse() })
    root.addView(reportPane("Statistics", 0xFF5E7A86.toInt()) { showStatsMenu() }.apply {
        textSize = 16f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * dp).toInt())
    })
    setContentView(root)
}

private fun showLogPage() {
    inSubPage = true
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(backText { setupMainScreen() })
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(TextView(this).apply {
        text = "Log"; textSize = 21f; setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    header.addView(Button(this).apply {
        text = "Clear log"
        setOnClickListener { clearLog(); Toast.makeText(this@MainActivity, "Log cleared", Toast.LENGTH_SHORT).show() }
    })
    root.addView(header)

    val empty = TextView(this).apply {
        text = "No entries yet"; setPadding(0, (24 * dp).toInt(), 0, 0); visibility = View.GONE
    }
    root.addView(empty)
    val rv = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@MainActivity)
        adapter = this@MainActivity.adapter
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    root.addView(rv)
    setContentView(root)

    emptyList = empty
    observeEntries()
}

private fun showAboutPage() {
    inSubPage = true
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(backText { setupHomeScreen() })
    root.addView(titleText("About & privacy"))
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(TextView(this).apply {
        text = getString(R.string.disclosure); textSize = 15f
    })
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(content)
    })
    setContentView(root)
}

/** One full-width quarter-height clickable pane. */
private fun reportPane(label: String, bg: Int, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 22f
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(bg)
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)   // weight 1 -> equal quarters
        setOnClickListener { onClick() }
    }

// ── Pane actions (stubs — fill these in later) ─────────────────────────────
private fun onReportAppSite() {
    startAppSiteFlow()
}
private fun onFeelTemptation() {
    startTemptationFlow()
}

// ── "I feel temptation" flow ───────────────────────────────────────────────

private var inTemptationFlow = false

private var rideHandler: Handler? = null
private var rideRunnable: Runnable? = null
private var rideEndAt = 0L
private var waveOrb: BreathOrbAnimator? = null

// ========================
// ── "I'm going to look anyway" (supervised loosen) flow ─────────────────────

private var inLoosenFlow = false

private var loosenHandler: Handler? = null
private var loosenRunnable: Runnable? = null
private var loosenOrb: BreathOrbAnimator? = null

// ── "I'm going to look anyway" (supervised loosen) — rebuilt ────────────────
private var loosenBackAction: (() -> Unit)? = null
private var loosenRegret: String? = null
private var loosenFix: String? = null
private var commitStep = 0
private var loosenNote: String? = null
private var loosenAdmit = false
private var loosenWontRepeat = false
private var loosenDuration = 2

private fun startLoosenFlow() {
    onReportScreen = true; inLoosenFlow = true; loosenBackAction = null
    if (LoosenWait.isActive(this)) { loosenWaitScreen(); return }          // resume a wait in progress
    if (!LoosenLimit.canUse(this)) { loosenBlockedScreen(); return }
    loosenRegret = null; loosenFix = null
    loosenIntro1()
}

private fun loosenBack() {
    (loosenBackAction ?: { stopLoosenTimer(); inLoosenFlow = false; showReportScreen() }).invoke()
}

private fun loosenStop(message: String) {
    stopLoosenTimer(); LoosenWait.end(this)
    inLoosenFlow = false; onReportScreen = true; loosenBackAction = null
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("Good."))
    root.addView(body(message))
    root.addView(grow())
    root.addView(Button(this).apply { text = "Done"; setOnClickListener { showReportScreen() } })
    setContentView(root)
}

private fun loosenBlockedScreen() {
    val today = LoosenLimit.usedToday(this)
    val msg = if (today)
        "You've already used your one unlock for today. It resets tomorrow \u2014 and that wait is doing its job."
    else
        "You've used all ${LoosenLimit.LIFETIME_MAX} of your lifetime unlocks, by your own earlier choice. You've got this without it."
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(bigPanic())
    root.addView(titleText("Not available right now"))
    root.addView(body(msg))
    root.addView(grow())
    root.addView(Button(this).apply { text = "Back"; setOnClickListener { showReportScreen() } })
    setContentView(root)
}

// ── intro, one idea per screen, panic taking the top third ──────────────────
private fun loosenIntro1() {
    loosenBackAction = { stopLoosenTimer(); inLoosenFlow = false; showReportScreen() }
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { stopLoosenTimer(); inLoosenFlow = false; showReportScreen() })
    root.addView(boldWordTitle("This is a supervised unlock, only for times of desperation.", "desperation"))
    root.addView(TextView(this).apply {
        text = "${LoosenLimit.remaining(this@MainActivity)} of ${LoosenLimit.LIFETIME_MAX} unlocks available"
        textSize = 15f; setTextColor(0xFF4A4F54.toInt()); setPadding(0, (8 * dp).toInt(), 0, 0)
    })
    root.addView(TextView(this).apply {
        text = "Every urge works the same way \u2014 it spikes hard, then fades. People who wait it out almost always find it's gone in minutes."
        textSize = 15f; setTextColor(0xFF4A4F54.toInt()); setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
    })
    root.addView(PeakCurveView(this, showMarker = false, labelTop = "it always", labelBot = "passes"),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(captionedButton("I want to stop instead", "I am strong and can do it", 0xFF2E7D32.toInt()) { openPanic() })
    root.addView(captionedButton("I understand", "and want to continue", 0xFF3E535C.toInt()) { loosenFaceActScreen() })
    setContentView(root)
}

private val NEG_FEELINGS = listOf("Regret", "Numb", "Empty", "Ashamed")
private val POS_FEELINGS = listOf("Proud", "Relieved", "Clear", "In control")

// ── Screen A: how will you feel after you unlock? (drag into the venn) ───────
private fun loosenFaceActScreen() {
    loosenBackAction = { loosenIntro1() }
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { loosenBack() })
    root.addView(titleText("How will you feel after?"))
    root.addView(TextView(this).apply {
        text = "Drag the face to where you'll feel after\u2026"
        textSize = 14f; setTextColor(0xFF6B7075.toInt()); setPadding(0, 0, 0, (4 * dp).toInt())
    })
    val face = FeelingFaceView(this, NEG_FEELINGS, 0xFFB0453B.toInt(), positiveInside = false,
        startZoneLabel = "you, if you get past this\n(just 5 minutes of waiting)")
    root.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    val cont = continueLink("Continue") { loosenRegret = face.nearestLabel() ?: loosenRegret; loosenFaceRideScreen() }
    face.onMoodChange = { enableLink(cont) }
    root.addView(panicBar())
    root.addView(cont)
    setContentView(root)
}

// ── Screen B: how will you feel if you wait it out? (all happy / neutral) ────
private fun loosenFaceRideScreen() {
    loosenBackAction = { stopLoosenTimer(); loosenFaceActScreen() }
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { loosenBack() })
    root.addView(titleText("And if you wait it out?"))
    root.addView(TextView(this).apply {
        text = "Drag the face to where you'll be in 30 minutes\u2026"
        textSize = 14f; setTextColor(0xFF6B7075.toInt()); setPadding(0, 0, 0, (2 * dp).toInt())
    })
    val timer = TextView(this).apply {
        textSize = 28f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setTextColor(0xFF2E7D32.toInt())
    }
    root.addView(timer)
    val face = FeelingFaceView(this, POS_FEELINGS, 0xFF2E7D32.toInt(), positiveInside = true)
    root.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    val cont = continueLink("Continue") { stopLoosenTimer(); loosenDelayChanceScreen() }
    face.onMoodChange = { enableLink(cont) }
    root.addView(panicBar())
    root.addView(cont)
    setContentView(root)
    runLoosenCountdown(timer, System.currentTimeMillis() + 30L * 60 * 1000) { timer.text = "0:00" }
}

// ── Screen C: how likely can you DELAY 30 mins? (slider, mirrors urge bands) ─
private fun loosenDelayChanceScreen() {
    loosenBackAction = { loosenFaceRideScreen() }
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { loosenBack() })
    root.addView(titleText("A 30-minute challenge"))
    root.addView(body("Beat the urge by doing nothing but waiting it out."))
    root.addView(TextView(this).apply {
        text = "Right now, how likely is it you can hold off for 30 minutes?"
        textSize = 15f; setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
    })
    val label = TextView(this).apply {
        textSize = 19f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setTextColor(0xFF2E7D32.toInt()); setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
    }
    root.addView(label)
    val seek = android.widget.SeekBar(this).apply { max = 100; progress = 50 }
    root.addView(seek)
    val ends = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    ends.addView(TextView(this).apply {
        text = "no chance"; textSize = 12f; setTextColor(0xFF9AA0A6.toInt())
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    ends.addView(TextView(this).apply {
        text = "I've got this"; textSize = 12f; setTextColor(0xFF9AA0A6.toInt()); gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    root.addView(ends)
    label.text = delayBand(seek.progress)
    val cont = continueLink("I want to continue anyway") { loosenOneOffScreen() }
    seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) {
            label.text = delayBand(p); if (fromUser) enableLink(cont)
        }
        override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
    })
    root.addView(grow())
    root.addView(panicBar())
    root.addView(cont)
    setContentView(root)
}

// Mirrors the "how strong is the urge" wording, flipped to "can I hold off?"
private fun delayBand(p: Int): String = when {
    p < 20 -> "Feels impossible right now"
    p < 40 -> "Very hard \u2014 but not impossible"
    p < 60 -> "Could honestly go either way"
    p < 80 -> "I think I can hold off"
    else -> "I've got this \u2014 30 minutes is nothing"
}

// ── Screen D: is this a one-off? how it shapes the future ───────────────────
private fun loosenOneOffScreen() {
    loosenBackAction = { loosenDelayChanceScreen() }
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { loosenBack() })
    root.addView(titleText("Is this really a one-off?"))
    root.addView(body("Each unlock nudges your brain back toward the old wiring. \u201cJust this once\u201d is exactly how the pattern keeps itself alive."))
    root.addView(RecoveryBrainView(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (resources.displayMetrics.density * 8).toInt(), 0, (resources.displayMetrics.density * 4).toInt())
    }
    list.addView(pickCard("Yes \u2014 genuinely a one-off") { loosenOneOffFollow(true) })
    root.addView(list)
    root.addView(panicBar())
    setContentView(root)
}

private fun loosenOneOffFollow(oneOff: Boolean) {
    loosenBackAction = { loosenOneOffScreen() }
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { loosenBack() })
    root.addView(titleText(if (oneOff) "Then waiting costs you nothing" else "Then let this be where it breaks"))
    root.addView(body(if (oneOff)
        "If it's truly just once, 30 minutes won't change that \u2014 except you'll have it behind you, clean, with every unlock still in the bank."
    else
        "Patterns break at one ordinary moment that looks exactly like this one. The future you is asking you to make it this one."))
    root.addView(grow())
    root.addView(bigChoice("I'll wait it out", 0xFF2E7D32.toInt()) {
        LoosenLog.record(this, "stopped", loosenRegret, loosenFix, 0)
        loosenStop("That was the hard choice, made well. The urge passes; this stays with you. Nothing's been used up.")
    })
    root.addView(continueLink("Continue anyway") { loosenFixScreen() }.also { enableLink(it) })
    root.addView(grow())
    setContentView(root)
}

// ── reuse the temptation emotion picker, then where they are ────────────────
private fun loosenFixScreen() {
    loosenBackAction = { loosenOneOffScreen() }
    pickMultiWithCustomScreen("What emotions are you feeling right now?", Opts.FEELINGS, "feeling",
        onBack = { loosenBack() }) { feels -> loosenFix = feels.joinToString(", "); loosenPlaceScreen() }
}

private fun loosenPlaceScreen() {
    loosenBackAction = { loosenFixScreen() }
    pickWithCustomScreen("Where are you right now?", Opts.LOCATIONS, "location",
        onBack = { loosenBack() }) { loc ->
        loosenFix = listOfNotNull(loosenFix?.takeIf { it.isNotBlank() }, loc).joinToString("  \u00b7  ")
        loosenUrgeGraphScreen()
    }
}

// ── the urge curve: they tap where they think they are on the wave ──────────
private fun loosenUrgeGraphScreen() {
    loosenBackAction = { loosenPlaceScreen() }
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { loosenBack() })
    root.addView(titleText("Where are you on the wave?"))
    root.addView(TextView(this).apply {
        text = "The urge spikes, then fades. Tap where you think you are right now."
        textSize = 14f; setTextColor(0xFF6B7075.toInt()); setPadding(0, 0, 0, (4 * dp).toInt())
    })
    val resp = TextView(this).apply {
        textSize = 16f; gravity = Gravity.CENTER; setTextColor(0xFF2E7D32.toInt())
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
    }
    val cont = continueLink("I want to continue anyway") { loosenWaitScreen() }
    val graph = PeakTapView(this, threshold = 0.30f) { _, correct ->
        if (correct) {
            resp.text = "That's right \u2014 you only have to beat the next 5 minutes. That's all, and it trains you for life."
            enableLink(cont)
        } else {
            resp.text = "Not quite \u2014 you've actually passed the peak already. Tap again, further along, where the urge is fading."
        }
    }
    root.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(resp)
    root.addView(panicBar())
    root.addView(cont)
    setContentView(root)
}

// ── the wait: persists, whitelist-locks, reuses breathing ──────────────────
private fun loosenWaitScreen() {
    onReportScreen = true; inLoosenFlow = true
    loosenBackAction = { loosenStop("You stepped back from it \u2014 nothing's been used up. The wait was already working.") }
    if (!LoosenWait.isActive(this)) LoosenWait.start(this, 5L * 60 * 1000)
    val endAt = System.currentTimeMillis() + LoosenWait.remaining(this)
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
    }
    content.addView(backText { loosenBack() })
    content.addView(TextView(this).apply {
        text = "A short wait first"; textSize = 21f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    // the only time readout — updates each minute, no ticking seconds
    val sub = TextView(this).apply {
        text = "you'll be able to continue in 5 minutes"; textSize = 16f; gravity = Gravity.CENTER
        setTextColor(0xFF4A4F54.toInt()); setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
    }
    content.addView(sub)
    // big orb on the page (no dark card), matching the temptation breathing
    val orb = BreathOrbView(this, 0xFF2E9E8F.toInt())
    val orbBox = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams((230 * dp).toInt(), (230 * dp).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        addView(orb, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    content.addView(orbBox)
    val breatheLabel = TextView(this).apply {
        text = "Breathe in"; textSize = 16f; gravity = Gravity.CENTER; setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
    }
    content.addView(breatheLabel)

    // the enticing primary; tapping it groups the "give it longer" options
    content.addView(GlowButton(this, "Lock me out for 5 mins \u2014 I can do this") { showLoosenLongerDialog() }.apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (62 * dp).toInt()
        ).apply { bottomMargin = (8 * dp).toInt() }
    })
    // the temptation-style exit, caption now inside the button
    content.addView(captionedButton("Put the phone down", "closes the app", 0xFF2E7D32.toInt()) {
        LoosenLog.record(this, "stopped", loosenRegret, loosenFix, 0)
        try { finishAffinity() } catch (_: Throwable) { setupMainScreen() }
    })
    content.addView(grow())
    // revealed once the wait is up, pinned to the very bottom
    val doneContinue = continueLink("I've waited \u2014 continue") { loosenCommitStart() }
    content.addView(doneContinue)
    val root = ScrollView(this).apply {
        setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(content)
    }
    setContentView(root)
    runLoosenMinuteCountdown(sub, endAt) {
        enableLink(doneContinue); sub.setTextColor(0xFF2E7D32.toInt()); sub.setTypeface(sub.typeface, Typeface.BOLD)
    }
    loosenOrb = BreathOrbAnimator(orb, breatheLabel).also { it.start(cycles = null) }
}

private fun showLoosenLongerDialog() {
    val dp = resources.displayMetrics.density
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (20 * dp).toInt(); setPadding(p, (8 * dp).toInt(), p, 0)
    }
    val dialog = AlertDialog.Builder(this)
        .setTitle("Give it longer? It only helps.")
        .setView(box)
        .setNegativeButton("Keep the 5 minutes", null)
        .create()
    fun option(label: String, ms: Long) {
        box.addView(bigChoice(label, 0xFF2E7D32.toInt()) {
            LoosenWait.start(this, ms); dialog.dismiss(); loosenWaitScreen()
        })
    }
    option("Lock me out for 10 minutes", 10L * 60 * 1000)
    option("Lock me out for 30 minutes", 30L * 60 * 1000)
    option("Lock me out for 2 hours", 2L * 60 * 60 * 1000)
    dialog.show()
}

// ── commit, one step at a time, all gated ──────────────────────────────────
private fun loosenCommitStart() {
    commitStep = 0; loosenAdmit = false; loosenWontRepeat = false; loosenNote = null; loosenDuration = 2
    renderCommitStep()
}

private fun renderCommitStep() {
    loosenBackAction = { if (commitStep == 0) loosenWaitScreen() else { commitStep--; renderCommitStep() } }
    when (commitStep.coerceIn(0, 3)) {
        0 -> commitConfirmScreen("Step 1 of 4", "Be honest with yourself",
            "I'm choosing this, knowing how I'll feel after.", { loosenAdmit }, { loosenAdmit = it }, "Yes, I'm choosing this")
        1 -> commitNoteScreen("Step 2 of 4")
        2 -> commitConfirmScreen("Step 3 of 4", "One promise",
            "I won't do this next time.", { loosenWontRepeat }, { loosenWontRepeat = it }, "I promise")
        3 -> commitDurationScreen("Step 4 of 4")
    }
}

private fun commitConfirmScreen(step: String, heading: String, statement: String,
    get: () -> Boolean, set: (Boolean) -> Unit, continueLabel: String) {
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { loosenBack() })
    root.addView(stepText(step))
    root.addView(titleText(heading))
    val check = checkButton()
    val cont = Button(this).apply { text = continueLabel }
    val render = { check.text = (if (get()) "\u2611  " else "\u2610  ") + statement; cont.isEnabled = get() }
    check.setOnClickListener { set(!get()); render() }
    render()
    root.addView(check)
    root.addView(grow())
    cont.setOnClickListener { commitStep++; renderCommitStep() }
    root.addView(cont)
    setContentView(root)
}

private fun commitNoteScreen(step: String) {
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { loosenBack() })
    root.addView(stepText(step))
    root.addView(titleText("What will you look at?"))
    root.addView(TextView(this).apply {
        text = "Private \u2014 stays on this device."; textSize = 13f; setTextColor(0xFF6B7075.toInt())
        setPadding(0, 0, 0, (8 * dp).toInt())
    })
    val note = EditText(this).apply {
        hint = "Name it plainly\u2026"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP or Gravity.START; minLines = 3; setText(loosenNote ?: "")
    }
    root.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(Button(this).apply {
        text = "That's what I'll look at"
        setOnClickListener {
            val t = note.text.toString().trim()
            if (t.isEmpty()) { Toast.makeText(this@MainActivity, "Write it down first.", Toast.LENGTH_SHORT).show() }
            else { loosenNote = t; commitStep++; renderCommitStep() }
        }
    })
    setContentView(root)
}

private fun commitDurationScreen(step: String) {
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { loosenBack() })
    root.addView(stepText(step))
    root.addView(titleText("For how long?"))
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val btns = linkedMapOf<Int, Button>()
    listOf(1, 2, 5).forEach { m ->
        val b = Button(this).apply {
            text = "$m min"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btns[m] = b; row.addView(b)
    }
    root.addView(row)
    root.addView(grow())
    val cont = Button(this)
    val refresh = {
        btns.forEach { (m, b) -> b.setTypeface(Typeface.DEFAULT, if (m == loosenDuration) Typeface.BOLD else Typeface.NORMAL) }
        cont.text = "Unlock for $loosenDuration min"
    }
    btns.forEach { (m, b) -> b.setOnClickListener { loosenDuration = m; refresh() } }
    cont.setOnClickListener { loosenUnlock() }
    refresh()
    root.addView(cont)
    setContentView(root)
}

private fun loosenUnlock() {
    LoosenLog.record(this, "looked", loosenRegret, loosenFix, loosenDuration)   // ADD
    LoosenLimit.consume(this)
    LoosenWait.end(this)
    LoosenWindow.start(this, loosenDuration * 60 * 1000L)
    loosenUnlockedScreen()
}

private fun loosenUnlockedScreen() {
    inLoosenFlow = false; onReportScreen = true; loosenBackAction = null
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("Unlocked for $loosenDuration min"))
    val countdown = TextView(this).apply {
        textSize = 40f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
    }
    root.addView(countdown)
    root.addView(body("The breathing orb and image friction stay on. It re-locks itself when the timer ends."))
    root.addView(grow())
    root.addView(bigChoice("Go", 0xFF3E535C.toInt()) { moveTaskToBack(true) })
    root.addView(Button(this).apply { text = "Done"; setOnClickListener { showReportScreen() } })
    setContentView(root)
    runLoosenCountdown(countdown, System.currentTimeMillis() + LoosenWindow.remaining(this)) {
        countdown.text = "Re-locked"
    }
}

// ── small builders for this flow ───────────────────────────────────────────
private fun vbox(pad: Int) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
private fun grow() = View(this).also { /* spacer */ }.apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
}
private fun body(t: String) = TextView(this).apply {
    text = t; textSize = 16f
    setPadding(0, (resources.displayMetrics.density * 8).toInt(), 0, 0)
}
private fun stepText(s: String) = TextView(this).apply {
    text = s; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF6B7075.toInt())
    setPadding(0, 0, 0, (resources.displayMetrics.density * 4).toInt())
}
private fun panicBar(): Button = bigChoice("I want to stop instead", 0xFF2E7D32.toInt()) { openPanic() }

private fun bigPanic(): Button {
    val dp = resources.displayMetrics.density
    val third = resources.displayMetrics.heightPixels / 3
    return Button(this).apply {
        text = "I want to stop instead"; setAllCaps(false)
        setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD); textSize = 20f
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * dp; setColor(0xFF2E7D32.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, third)
            .apply { bottomMargin = (12 * dp).toInt() }
        setOnClickListener { openPanic() }
    }
}

private fun urgeGraphView(): View {
    val dp = resources.displayMetrics.density
    val v = object : View(this) {
        val act = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC0392B.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * dp }
        val wait = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7D32.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * dp }
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000; strokeWidth = 1 * dp }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val padB = 14 * dp; val x0 = 6 * dp; val x1 = w - 6 * dp; val yBase = h - padB; val yTop = 6 * dp
            canvas.drawLine(x0, yBase, x1, yBase, axis)
            fun x(t: Float) = x0 + (x1 - x0) * t
            fun y(vv: Float) = yBase - (yBase - yTop) * vv
            val pWait = Path().apply {
                moveTo(x(0f), y(0.45f))
                cubicTo(x(0.2f), y(0.55f), x(0.4f), y(0.3f), x(0.6f), y(0.12f))
                cubicTo(x(0.75f), y(0.06f), x(0.9f), y(0.04f), x(1f), y(0.03f))
            }
            val pAct = Path().apply {
                moveTo(x(0f), y(0.45f))
                cubicTo(x(0.12f), y(0.9f), x(0.2f), y(0.98f), x(0.3f), y(0.95f))
                cubicTo(x(0.45f), y(0.85f), x(0.55f), y(0.2f), x(0.7f), y(0.12f))
                cubicTo(x(0.82f), y(0.18f), x(0.9f), y(0.3f), x(1f), y(0.28f))
            }
            canvas.drawPath(pWait, wait)
            canvas.drawPath(pAct, act)
        }
    }
    v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (150 * dp).toInt())
    return v
}

// ── Panic (lives here, no separate feature) ────────────────────────────────
private fun openPanic() {
    stopLoosenTimer()
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(titleText("Let's break the loop"))
    val pacer = TextView(this).apply {
        textSize = 30f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
    }
    root.addView(pacer)
    root.addView(TextView(this).apply {
        text = "Follow the words. In through the nose, out through the mouth."
        textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF6B7075.toInt())
        setPadding(0, 0, 0, (12 * dp).toInt())
    })
    val grounding = listOf(
        "Plant both feet on the floor and sit up straight.",
        "Name 5 things you can see, 4 you can hear, 3 you can touch.",
        "Stand up and walk into a different room.",
        "Pour a glass of water and drink it slowly.",
    )
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    grounding.forEachIndexed { i, s ->
        list.addView(TextView(this).apply {
            text = "${i + 1}.  $s"; textSize = 15f
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        })
    }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(list)
    })
    val lockApps = bigChoice("Lock apps for 30 minutes", 0xFF2E7D32.toInt()) {}
    lockApps.setOnClickListener {
        Lockdown.start(this); lockApps.text = "Apps locked for 30 min"; lockApps.isEnabled = false
        Toast.makeText(this, "Locked down. Essentials still work.", Toast.LENGTH_LONG).show()
    }
    root.addView(lockApps)
    root.addView(bigChoice("Lock my phone screen now", 0xFF3E535C.toInt()) { lockPhoneNow() })
    root.addView(Button(this).apply {
        text = "I'm okay now"
        setOnClickListener {
            stopLoosenTimer(); inLoosenFlow = false
            showReportScreen()
        }
    })
    setContentView(root)
    startBoxBreathing(pacer)
}

private fun lockPhoneNow() {
    if (UninstallGuard.isAdminActive(this)) {
        try {
            (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
        } catch (t: Throwable) {
            Toast.makeText(this, "Couldn't lock the screen.", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(this, "Turn on the lock feature first to use this.", Toast.LENGTH_SHORT).show()
    }
}

// ── shared bits for this flow ──────────────────────────────────────────────
private fun panicButton(): Button = bigChoice("PANIC \u2014 I need to stop", 0xFFB00020.toInt()) { openPanic() }

private fun bigChoice(label: String, color: Int, onClick: () -> Unit): Button {
    val dp = resources.displayMetrics.density
    return Button(this).apply {
        text = label; setAllCaps(false)
        setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12 * dp; setColor(color)
        }
        val p = (14 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (8 * dp).toInt() }
        setOnClickListener { onClick() }
    }
}

private fun checkButton(): Button {
    val dp = resources.displayMetrics.density
    return Button(this).apply {
        setAllCaps(false)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dp).toInt() }
    }
}

private fun startBoxBreathing(label: TextView) {
    stopLoosenTimer()
    val phases = listOf("Breathe in\u2026", "Hold\u2026", "Breathe out\u2026", "Hold\u2026")
    loosenHandler = Handler(Looper.getMainLooper())
    var i = 0
    loosenRunnable = object : Runnable {
        override fun run() {
            label.text = phases[i % phases.size]; i++
            loosenHandler?.postDelayed(this, 4000)
        }
    }
    loosenRunnable?.run()
}

private fun runLoosenCountdown(label: TextView, endAt: Long, onDone: () -> Unit) {
    stopLoosenTimer()
    loosenHandler = Handler(Looper.getMainLooper())
    loosenRunnable = object : Runnable {
        override fun run() {
            val remaining = (endAt - System.currentTimeMillis()).coerceAtLeast(0)
            val m = (remaining / 1000) / 60; val s = (remaining / 1000) % 60
            label.text = String.format("%02d:%02d", m, s)
            if (remaining <= 0) onDone() else loosenHandler?.postDelayed(this, 1000)
        }
    }
    loosenRunnable?.run()
}

/** Updates "you'll be able to continue in N minutes" rather than ticking seconds. */
private fun runLoosenMinuteCountdown(label: TextView, endAt: Long, onDone: () -> Unit) {
    stopLoosenTimer()
    loosenHandler = Handler(Looper.getMainLooper())
    loosenRunnable = object : Runnable {
        override fun run() {
            val rem = (endAt - System.currentTimeMillis()).coerceAtLeast(0)
            if (rem <= 0) { label.text = "you can continue now"; onDone() }
            else {
                val mins = ((rem + 59_999) / 60_000).toInt()
                label.text = "you'll be able to continue in $mins minute" + (if (mins == 1) "" else "s")
                loosenHandler?.postDelayed(this, 1000)
            }
        }
    }
    loosenRunnable?.run()
}

private fun stopLoosenTimer() {
    loosenRunnable?.let { loosenHandler?.removeCallbacks(it) }
    loosenRunnable = null; loosenHandler = null
    breatheOn = false
    loosenOrb?.stop(); loosenOrb = null
}


// ── shared little view helpers ─────────────────────────────────────────────
private fun backText(onBack: () -> Unit): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = "\u2190"; textSize = 20f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0x55000000.toInt())   // translucent — sits lightly over the content
        }
        val s = (40 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s).apply { bottomMargin = (10 * dp).toInt() }
        isClickable = true; isFocusable = true
        setOnClickListener { onBack() }
    }
}

private fun titleText(t: String): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = t; textSize = 21f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, (8 * dp).toInt())
    }
}

// ── ride-it-out countdown ──────────────────────────────────────────────────
private fun stopRideTimer() {
    rideRunnable?.let { rideHandler?.removeCallbacks(it) }
    rideRunnable = null
    rideHandler = null
    breatheOn = false
    waveOrb?.stop(); waveOrb = null
}


private fun onLookAnyway() {
    startLoosenFlow()
}
private fun onReportRelapse() {
    startRelapseFlow()
}

// ── Relapse report flow ────────────────────────────────────────────────────
private enum class RStep { DEVICE, HOME, ROOM, ACTIVITY, FEELING, URGE, NOTE }

private fun activeSteps(): List<RStep> {
    val s = mutableListOf(RStep.DEVICE, RStep.HOME)
    if (draft.atHome == true) s.add(RStep.ROOM)
    s.add(RStep.ACTIVITY); s.add(RStep.FEELING); s.add(RStep.URGE); s.add(RStep.NOTE)
    return s
}

private fun renderRelapseStep() {
    val steps = activeSteps()
    relapseStep = relapseStep.coerceIn(0, steps.lastIndex)
    when (steps[relapseStep]) {
        RStep.DEVICE -> reportChoiceScreen(
            "Where did it happen?", listOf("Yes, on this device", "No, a different device"),
            onBack = ::relapseBack) { draft.onThisDevice = it.startsWith("Yes"); relapseAdvance() }

        RStep.HOME -> reportChoiceScreen(
            "Were you at home?", listOf("At home", "Out / somewhere else"), onBack = ::relapseBack) {
            draft.atHome = (it == "At home"); if (draft.atHome != true) draft.room = null; relapseAdvance()
        }

        RStep.ROOM -> pickWithCustomScreen(
            "Where were you?", Opts.LOCATIONS, "location", onBack = ::relapseBack) {
            draft.room = it; relapseAdvance()
        }

        RStep.ACTIVITY -> pickMultiWithCustomScreen(
            "What were you doing just before?", ACTIVITIES, "activity", onBack = ::relapseBack) {
            draft.activity = it.joinToString(", "); relapseAdvance()
        }

        RStep.FEELING -> pickMultiWithCustomScreen(
            "How were you feeling?", Opts.FEELINGS, "feeling", onBack = ::relapseBack) {
            draft.feeling = it.joinToString(", "); relapseAdvance()
        }

        RStep.URGE -> urgeScaleScreen(
            "How strong was the urge?", onBack = ::relapseBack) {
            draft.urge = it; relapseAdvance()
        }

        RStep.NOTE -> noteStep()
    }
}

private var inRelapseFlow = false
private var relapseStep = 0
private var draft = RelapseDraft()

private val DEFAULT_ROOMS = listOf("Bedroom", "Bathroom", "Living room", "Office / desk", "Kitchen")
private val ACTIVITIES = listOf(
    "In bed / trying to sleep",
    "Just woke up",
    "Scrolling social media",
    "Watching videos or TV",
    "Browsing the web",
    "Putting off something I should do",
    "Just finished work or study",
    "Bored with nothing to do",
    "After something stressful",
    "Winding down at night",
)
private val FEELINGS = listOf(
    "Bored", "Anxious / on edge", "Stressed", "Low / down",
    "Lonely", "Tired", "Frustrated / angry", "Happy / excited", "Neutral",
)

private fun startRelapseFlow() {
    onReportScreen = true
    inRelapseFlow = true
    draft = RelapseDraft()
    relapseStep = 0
    renderRelapseStep()
}

private fun relapseAdvance() { relapseStep++; renderRelapseStep() }

private fun relapseBack() {
    if (relapseStep <= 0) { inRelapseFlow = false; showReportScreen(); return }
    relapseStep--
    renderRelapseStep()
}


private fun noteStep() {
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(TextView(this).apply {
        text = "\u2190 Back"; textSize = 15f
        setPadding(0, 0, 0, (8 * dp).toInt())
        isClickable = true; isFocusable = true
        setOnClickListener { relapseBack() }
    })
    root.addView(TextView(this).apply {
        text = "Anything you want to note?"
        textSize = 21f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, (4 * dp).toInt())
    })
    root.addView(TextView(this).apply {
        text = "Private. It stays on this device and is never shown back to you as judgement."
        textSize = 13f; setTextColor(0xFF6B7075.toInt())
        setPadding(0, 0, 0, (10 * dp).toInt())
    })
    val input = EditText(this).apply {
        hint = "What happened, what set it off\u2026 (optional)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP or Gravity.START
        minLines = 4
        setText(draft.note ?: "")
    }
    root.addView(input, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    val btns = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
        setPadding(0, (8 * dp).toInt(), 0, 0)
    }
    btns.addView(Button(this).apply {
        text = "Skip"
        setOnClickListener { draft.note = null; saveRelapse() }
    })
    btns.addView(Button(this).apply {
        text = "Save report"
        setOnClickListener {
            draft.note = input.text.toString().trim().ifBlank { null }
            saveRelapse()
        }
    })
    root.addView(btns)
    setContentView(root)
}

private fun saveRelapse() {
    Progress.recordSlip(this)
    lifecycleScope.launch {
        val priors = RelapseLog.all(this@MainActivity)   // their earlier reports (excludes this one)
        val report = draft.toReport()
        RelapseLog.record(this@MainActivity, report)
        val feedback = RelapseLog.analyze(report, priors)
        renderRelapseFeedback(feedback)
    }
}

private fun renderRelapseFeedback(fb: RelapseFeedback) {
    inRelapseFlow = false
    onReportScreen = true
    val dp = resources.displayMetrics.density
    val pad = (20 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(TextView(this).apply {
        text = "Report saved \u2713"
        textSize = 24f; setTypeface(typeface, Typeface.BOLD)
    })
    content.addView(TextView(this).apply {
        text = fb.encouragement
        textSize = 16f
        setPadding(0, (12 * dp).toInt(), 0, (8 * dp).toInt())
    })
    if (fb.lines.isNotEmpty()) {
        content.addView(TextView(this).apply {
            text = "What we noticed"
            textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        })
        fb.lines.forEach { line ->
            content.addView(TextView(this).apply {
                text = "\u2022  $line"
                textSize = 15f
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            })
        }
    }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(content)
    })
    root.addView(Button(this).apply {
        text = "Done"
        setOnClickListener { setupMainScreen() }
    })
    setContentView(root)
}

/** A title + a scroll list of big tappable "panels", optional Back / Skip. */
private fun reportChoiceScreen(
    title: String,
    options: List<String>,
    allowSkip: Boolean = false,
    skipLabel: String = "Skip",
    onSkip: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onPick: (String) -> Unit,
) {
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    if (onBack != null) {
        root.addView(TextView(this).apply {
            text = "\u2190 Back"; textSize = 15f
            setPadding(0, 0, 0, (8 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { onBack() }
        })
    }
    root.addView(TextView(this).apply {
        text = title
        textSize = 21f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, (4 * dp).toInt())
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    options.forEach { opt -> list.addView(pickCard(opt) { onPick(opt) }) }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(list)
    })
    if (allowSkip && onSkip != null) {
        root.addView(Button(this).apply {
            text = skipLabel
            setOnClickListener { onSkip() }
        })
    }
    setContentView(root)
}

/** One rounded, full-width tappable option card. */
private fun pickCard(label: String, onClick: () -> Unit): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = label
        textSize = 17f
        setTextColor(0xFF1A1A1A.toInt())
        gravity = Gravity.CENTER_VERTICAL
        val p = (18 * dp).toInt()
        setPadding(p, p, p, p)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor(0xFFF1F3F4.toInt())
        }
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dp).toInt() }
        setOnClickListener { onClick() }
    }
}

// ── nicer option rows: emoji icon (vertically centred) + label + lighter sub ──
private class Choice(
    val value: String,
    val icon: String? = null,
    val sub: String? = null,
    val tint: Int = 0xFFF1F3F4.toInt(),
    val group: String? = null,
)

private fun metaFor(category: String, v: String): Choice = when (category) {
    "screen" -> Choice(v, screenIcon(v))
    "location" -> Choice(v, locationIcon(v))
    "activity" -> Choice(v, activityIcon(v))
    "feeling" -> feelingMeta(v)
    else -> Choice(v)
}

private fun screenIcon(v: String) = when (v) {
    "Phone" -> "\uD83D\uDCF1"; "Tablet" -> "\uD83D\uDCF2"
    "Computer / laptop" -> "\uD83D\uDCBB"; "TV" -> "\uD83D\uDCFA"
    "Someone else's screen" -> "\uD83D\uDC40"; else -> "\uD83D\uDCF1"
}
private fun locationIcon(v: String) = when (v) {
    "Bedroom" -> "\uD83D\uDECC"; "Bathroom" -> "\uD83D\uDEBF"; "Living room" -> "\uD83D\uDECB"
    "Kitchen" -> "\uD83C\uDF73"; "Office / desk" -> "\uD83D\uDCBC"; "Out / in public" -> "\uD83C\uDF33"
    else -> "\uD83D\uDCCD"
}
private fun activityIcon(v: String) = when (v) {
    "In bed / trying to sleep" -> "\uD83D\uDECC"; "Just woke up" -> "\uD83C\uDF05"
    "Scrolling social media" -> "\uD83D\uDCF1"; "Watching videos or TV" -> "\uD83D\uDCFA"
    "Browsing the web" -> "\uD83C\uDF10"; "Putting off something I should do" -> "\u23F3"
    "Just finished work or study" -> "\uD83D\uDCBC"; "Bored with nothing to do" -> "\uD83E\uDD71"
    "After something stressful" -> "\uD83D\uDE23"; "Winding down at night" -> "\uD83C\uDF19"
    else -> "\uD83D\uDD01"
}
// feelings carry a group + a subtle tint so the screen reads as grouped bands
private fun feelingMeta(v: String): Choice = when (v) {
    "Anxious / on edge" -> Choice(v, "\uD83D\uDE30", null, 0xFFFFF3E0.toInt(), "On edge")
    "Stressed" -> Choice(v, "\uD83D\uDE23", null, 0xFFFFF3E0.toInt(), "On edge")
    "Frustrated / angry" -> Choice(v, "\uD83D\uDE20", null, 0xFFFCE9E6.toInt(), "Wound up")
    "Low / down" -> Choice(v, "\uD83D\uDE1E", null, 0xFFEAEFF4.toInt(), "Shut down / flat")
    "Lonely" -> Choice(v, "\uD83D\uDE41", null, 0xFFEAEFF4.toInt(), "Shut down / flat")
    "Tired" -> Choice(v, "\uD83D\uDE34", null, 0xFFEAEFF4.toInt(), "Shut down / flat")
    "Neutral" -> Choice(v, "\uD83D\uDE10", null, 0xFFEAEFF4.toInt(), "Shut down / flat")
    "Bored" -> Choice(v, "\uD83E\uDD71", null, 0xFFEEF1EB.toInt(), "Bored")
    "Happy / excited" -> Choice(v, "\uD83D\uDE04", null, 0xFFE7F4E8.toInt(), "Feeling good")
    else -> Choice(v, "\uD83D\uDE36")
}
private val FEELING_GROUP_ORDER = listOf("On edge", "Shut down / flat", "Bored", "Feeling good", "Wound up")
private fun feelingRank(v: String): Int =
    feelingMeta(v).group?.let { FEELING_GROUP_ORDER.indexOf(it) }.let { if (it == null || it < 0) FEELING_GROUP_ORDER.size else it }

private fun rowCard(tint: Int, selected: Boolean): LinearLayout {
    val dp = resources.displayMetrics.density
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val px = (16 * dp).toInt(); val py = (15 * dp).toInt(); setPadding(px, py, px, py)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 14 * dp; setColor(tint)
            if (selected) setStroke((2 * dp).toInt(), 0xFF2E7D32.toInt())
        }
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * dp).toInt() }
    }
}
private fun emojiView(icon: String?): View? {
    if (icon.isNullOrEmpty()) return null
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = icon; textSize = 21f; gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        val s = (40 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = (14 * dp).toInt() }
    }
}
private fun textCol(label: String, sub: String?): LinearLayout {
    val dp = resources.displayMetrics.density
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = 17f; setTextColor(0xFF1A1A1A.toInt())
        })
        if (!sub.isNullOrEmpty()) addView(TextView(this@MainActivity).apply {
            text = sub; textSize = 13f; setTextColor(0xFF80868B.toInt()); setPadding(0, (3 * dp).toInt(), 0, 0)
        })
    }
}
private fun checkRow(choice: Choice, checked: Boolean, onToggle: () -> Unit): View {
    val dp = resources.displayMetrics.density
    val card = rowCard(choice.tint, checked)
    card.addView(TextView(this).apply {
        text = if (checked) "\u2611" else "\u2610"; textSize = 22f
        setTextColor(if (checked) 0xFF2E7D32.toInt() else 0xFF9AA0A6.toInt())
        setPadding(0, 0, (12 * dp).toInt(), 0)
    })
    emojiView(choice.icon)?.let { card.addView(it) }
    card.addView(textCol(choice.value, choice.sub))
    card.setOnClickListener { onToggle() }
    return card
}
private fun optionRow(choice: Choice, onClick: () -> Unit): View {
    val card = rowCard(choice.tint, false)
    emojiView(choice.icon)?.let { card.addView(it) }
    card.addView(textCol(choice.value, choice.sub))
    card.setOnClickListener { onClick() }
    return card
}
private fun addOwnRow(onClick: () -> Unit): View = optionRow(Choice("Add your own\u2026", "\u2795"), onClick)

/** Big primary Continue that brightens and grows once something is selected. */
private fun bigContinue(label: String, onClick: () -> Unit): Button {
    val dp = resources.displayMetrics.density
    return Button(this).apply {
        text = label; setAllCaps(false); setTextColor(0xFFFFFFFF.toInt())
        setTypeface(typeface, Typeface.BOLD); textSize = 16f
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 14 * dp; setColor(0xFFB7C2BC.toInt())
        }
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * dp).toInt(); bottomMargin = (22 * dp).toInt() }
        isEnabled = false
        setOnClickListener { onClick() }
    }
}
private fun tuneContinue(btn: Button, active: Boolean) {
    btn.isEnabled = active
    (btn.background as? android.graphics.drawable.GradientDrawable)?.setColor(
        if (active) 0xFF2E7D32.toInt() else 0xFFB7C2BC.toInt())
    btn.textSize = if (active) 18f else 16f
    btn.animate().scaleX(if (active) 1.03f else 1f).scaleY(if (active) 1.03f else 1f).setDuration(140).start()
}

/** A quiet, lowercase "continue anyway" link that stays disabled until they've engaged. */
private fun continueLink(label: String, onClick: () -> Unit): Button {
    val dp = resources.displayMetrics.density
    return Button(this).apply {
        text = label; setAllCaps(false); setTextColor(0xFF48606A.toInt()); textSize = 15f
        background = null; isEnabled = false; alpha = 0.4f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (2 * dp).toInt(); bottomMargin = (14 * dp).toInt() }
        setOnClickListener { onClick() }
    }
}
private fun enableLink(b: Button) {
    if (!b.isEnabled) { b.isEnabled = true; b.animate().alpha(1f).setDuration(150).start() }
}

/** A primary button with a small, greyed caption underneath it. */
private fun captionedButton(label: String, caption: String, color: Int, onClick: () -> Unit): View {
    val dp = resources.displayMetrics.density
    val full = "$label\n$caption"
    val subStart = label.length + 1
    val sp = android.text.SpannableString(full).apply {
        setSpan(android.text.style.RelativeSizeSpan(0.72f), subStart, full.length, 0)
        setSpan(android.text.style.ForegroundColorSpan(0xCCFFFFFF.toInt()), subStart, full.length, 0)
        setSpan(android.text.style.StyleSpan(Typeface.NORMAL), subStart, full.length, 0)
    }
    return Button(this).apply {
        text = sp; setAllCaps(false); gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
        setLineSpacing((2 * dp), 1f)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12 * dp; setColor(color)
        }
        val px = (16 * dp).toInt(); setPadding(px, (16 * dp).toInt(), px, (16 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (8 * dp).toInt() }
        setOnClickListener { onClick() }
    }
}

/** A sentence-style heading with one word bolded. */
private fun boldWordTitle(full: String, word: String): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        textSize = 19f; setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
        val i = full.indexOf(word)
        text = if (i >= 0) android.text.SpannableString(full).apply {
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), i, i + word.length, 0)
        } else full
    }
}

// ── "How strong is the urge?" as a vertical colour scale ───────────────────
// Strongest at the top (red) fading to the gentlest at the bottom (blue), with
// faint hi/lo markers and a short example on each card (full text behind the ⓘ).
// Returns the chosen Opts.URGE_LEVELS string, so callers are unchanged.
private val URGE_EXAMPLES = mapOf(
    "Overwhelming" to "I feel I can't control it \u2014 like it's inevitable I'll give in.",
    "Strong" to "Hard to think about much else right now.",
    "Noticeable" to "Clearly there, but I can still steer around it.",
    "Mild" to "A small pull \u2014 easy to set aside.",
    "Barely there" to "Just a flicker \u2014 it barely registers.",
)

private fun urgeScaleScreen(title: String, onBack: (() -> Unit)?, onPick: (String) -> Unit) {
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    if (onBack != null) root.addView(backText { onBack() })
    root.addView(titleText(title))

    // scrollable so nothing is clipped on shorter screens; fillViewport keeps it
    // centred when there's room to spare.
    val center = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    center.addView(TextView(this).apply {
        text = "high level of urge"; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(0x33000000); setPadding(0, 0, 0, (6 * dp).toInt())
    })
    val red = 0xFFC0392B.toInt()
    val blue = 0xFF3E78C9.toInt()
    val ordered = Opts.URGE_LEVELS.reversed()   // Overwhelming (top) -> Barely there (bottom)
    ordered.forEachIndexed { i, level ->
        val f = if (ordered.size > 1) i.toFloat() / (ordered.size - 1) else 0f
        center.addView(urgeCard(level, URGE_EXAMPLES[level] ?: "", lerpColor(red, blue, f)) { onPick(level) })
    }
    center.addView(TextView(this).apply {
        text = "low level of urge"; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(0x33000000); setPadding(0, (6 * dp).toInt(), 0, (4 * dp).toInt())
    })
    root.addView(ScrollView(this).apply {
        isFillViewport = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(center)
    })
    setContentView(root)
}

private fun urgeCard(level: String, example: String, color: Int, onPick: () -> Unit): View {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 14 * dp; setColor(color)
        }
        val px = (16 * dp).toInt(); val py = (13 * dp).toInt(); setPadding(px, py, px, py)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (5 * dp).toInt(); bottomMargin = (5 * dp).toInt() }
        isClickable = true; isFocusable = true
        setOnClickListener { onPick() }
    }
    row.addView(TextView(this).apply {
        text = level; textSize = 19f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFFFFFFFF.toInt())
    })
    if (example.isNotEmpty()) row.addView(TextView(this).apply {
        text = example; textSize = 13f; setTextColor(0xCCFFFFFF.toInt())
        setPadding(0, (2 * dp).toInt(), 0, 0)
    })
    return row
}

private fun lerpColor(a: Int, b: Int, t: Float): Int {
    val tt = t.coerceIn(0f, 1f)
    val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
    val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
    val r = (ar + (br - ar) * tt).toInt()
    val g = (ag + (bg - ag) * tt).toInt()
    val bl = (ab + (bb - ab) * tt).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}


private fun refreshModeUi() {
    if (!::spinnerMode.isInitialized) return
    val wantPos = if (Mode.isStrict(this)) 1 else 0
    if (spinnerMode.selectedItemPosition != wantPos) spinnerMode.setSelection(wantPos)

    val locked = Mode.isLocked(this)
    spinnerMode.isEnabled = !locked
    val btn = findViewById<Button>(R.id.btn_strict_week)
    btn.isEnabled = true
    btn.text = if (locked) "Break the addiction protocol  \u00b7  strict ${Mode.daysLeft(this)}d left"
               else "Break the addiction protocol"
}

private fun startWeekStrict() {
    if (Mode.isLocked(this)) return
    AlertDialog.Builder(this)
        .setTitle("Start week-long strict mode?")
        .setMessage("Strict mode will stay on for 7 days. You won't be able to switch back to Relaxed until it ends.")
        .setPositiveButton("Start") { _, _ ->
            Mode.startWeekStrict(this)
            refreshModeUi()
            Toast.makeText(this, "Strict mode on for 7 days", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}


    private var entriesJob: kotlinx.coroutines.Job? = null
    private var shownStep: Step? = null

    private enum class Step { MONITORING, OVERLAY, LOCK, READY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BlockRules.load(this)
        updateScreen()
    }

    override fun onResume() {
        super.onResume()
        AppBlocklist.refresh(this)
        updateScreen()   // re-checks prerequisites every time the app is foregrounded
    }

    override fun onStop() {
        super.onStop()
        lockPromptHandled = false   // show the uninstall-lock page again on next reopen
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            inRelapseFlow -> relapseBack()
            inTemptationFlow -> temptationBack()
            inLoosenFlow -> loosenBack()
            inAppSiteFlow -> appSiteBack()
            onReportScreen -> reportBackTarget()
            onDevScreen -> setupHomeScreen()
            onTemptationsTab -> setupHomeScreen()
            inSubPage -> setupMainScreen()
            else -> super.onBackPressed()
        }
    }

    // ── Setup gate ────────────────────────────────────────────────────────────
    // Shows the prerequisites in order (monitoring -> overlay -> uninstall lock).
    // The first two are required; until both are on you can't reach the main
    // screen, and disabling either later sends you straight back here.

    // Reset on every reopen (see onStop) so the uninstall-lock page shows each time,
    // not just the first.
    private var lockPromptHandled = false
    private var onReportScreen = false
    private var inSubPage = false
    private var onHomeScreen = false
    private var onTemptationsTab = false
    private var onDevScreen = false
    private var reportBackTarget: () -> Unit = { setupMainScreen() }

    private fun currentStep(): Step = when {
        !isAccessibilityEnabled()       -> Step.MONITORING
        !Settings.canDrawOverlays(this) -> Step.OVERLAY
        !lockPromptHandled              -> Step.LOCK
        else                            -> Step.READY
    }

    private fun updateScreen() {
        val step = currentStep()
        if (step == Step.READY && shownStep == Step.READY) {
            renderStatus()   // already on the main screen — just refresh the dots
            return
        }
        shownStep = step
        when (step) {
            Step.MONITORING -> showPrereq(
                "Step 1 of 3\nTurn on page monitoring",
                "This lets the app see which website or app is on screen, so it can block " +
                    "what it should.\n\nWhen you tap Continue you'll land in Accessibility " +
                    "settings:\n\n" +
                    "1.  Tap \u201CInstalled apps\u201D (some phones say \u201CDownloaded apps\u201D).\n" +
                    "2.  Tap \u201CWeb Traffic Monitor\u201D.\n" +
                    "3.  Turn the toggle ON and accept.\n\nThen come back to this app.",
                "Continue to Accessibility",
                { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            )
            Step.OVERLAY -> showPrereq(
                "Step 2 of 3\nAllow the block screen",
                "This lets the app draw the blocking screen on top of other apps.\n\n" +
                    "When you tap Continue, find \u201CWeb Traffic Monitor\u201D in the list and " +
                    "turn its toggle ON.\n\nThen come back to this app.",
                "Continue to \u201CAppear on top\u201D",
                { requestOverlayPermission() },
            )
            Step.LOCK -> if (UninstallGuard.isAdminActive(this)) {
                showPrereq(
                    "Uninstall lock — ON",
                    "Protection is active: the app can't be uninstalled, and the settings " +
                        "pages that would switch it off are blocked.\n\nYou can turn it off " +
                        "from the main screen (you'll need the passcode).",
                    "Continue",
                    { lockPromptHandled = true; updateScreen() },
                )
            } else {
                showPrereq(
                    "Uninstall lock",
                    "Page monitoring and the block screen are on, so you can now enable the " +
                        "uninstall lock. While it's on, the app can't be uninstalled and the " +
                        "settings pages that would switch it off are blocked.",
                    "Enable uninstall lock",
                    {
                        lockPromptHandled = true
                        UninstallGuard.setEnabled(this, true)
                        startActivity(UninstallGuard.activationIntent(this))
                    },
                    "Skip for now",
                    { lockPromptHandled = true; updateScreen() },
                )
            }
            Step.READY -> setupHomeScreen()
        }
    }

    private fun showPrereq(
        title: String,
        body: String,
        buttonText: String,
        onContinue: () -> Unit,
        secondaryText: String? = null,
        onSecondary: (() -> Unit)? = null,
    ) {
        entriesJob?.cancel()
        onReportScreen = false
        setContentView(R.layout.screen_prereq)
        findViewById<TextView>(R.id.prereq_title).text = title
        findViewById<TextView>(R.id.prereq_body).text = body
        findViewById<Button>(R.id.prereq_primary).apply {
            text = buttonText
            setOnClickListener { onContinue() }
        }
        findViewById<Button>(R.id.prereq_secondary).apply {
            if (secondaryText == null) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = secondaryText
                setOnClickListener { onSecondary?.invoke() }
            }
        }
    }

    private fun setupMainScreen() {
        onReportScreen = false; onHomeScreen = false; onTemptationsTab = false
        onDevScreen = true
        inRelapseFlow = false; inTemptationFlow = false; inLoosenFlow = false
        inAppSiteFlow = false; inSubPage = false
        stopRideTimer(); stopLoosenTimer()
        entriesJob?.cancel()

        val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        content.addView(backText { setupHomeScreen() })
        content.addView(titleText("Developer tools"))
        content.addView(TextView(this).apply {
            text = "Diagnostics and block-rule management. Not shown to end users when dev mode is off."
            textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, 0, 0, (10 * dp).toInt())
        })
        content.addView(homeCard("System console", "Current mode, thresholds, and what's on or off.") { showDevConsole() })
        content.addView(homeCard("Recent blocks", "What's been blocked lately.") { showRecentBlocks() })
        content.addView(homeCard("Manage block rules", "Add or remove blocked sites and apps.") { showManageRules() })
        content.addView(homeCard("View log", "The full monitoring log.") { showLogPage() })
        content.addView(homeCard("Clear block rules", "Wipe all block rules and strikes.") {
            BlockRules.clear(this); BlockEscalation.clear(this); AppTimedBlock.clear(this)
            Toast.makeText(this, "Block rules cleared", Toast.LENGTH_SHORT).show()
        })

        val root = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true; addView(content)
        }
        setContentView(root)
    }

    // Read-only snapshot of everything the app is currently doing.
    private fun showDevConsole() {
        inSubPage = true
        val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
        val root = vbox(pad)
        root.addView(backText { setupMainScreen() })
        root.addView(titleText("System console"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
        })
        setContentView(root)

        fun header(t: String) = list.addView(TextView(this).apply {
            text = t.uppercase(); textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF9AA0A6.toInt())
            setPadding((2 * dp).toInt(), (16 * dp).toInt(), 0, (6 * dp).toInt())
        })
        fun row(label: String, value: String, on: Boolean? = null) = list.addView(TextView(this).apply {
            val dot = when (on) { true -> "\u25CF  "; false -> "\u25CB  "; null -> "" }
            text = "$dot$label:  $value"; textSize = 14f
            setTextColor(when (on) { true -> 0xFF2E9E44.toInt(); false -> 0xFF9AA0A6.toInt(); null -> 0xFF3A434B.toInt() })
            setPadding(0, (5 * dp).toInt(), 0, (5 * dp).toInt())
        })

        val modeId = Mode.current(this)
        val spec = AppConfig.MODES.firstOrNull { it.id == modeId }
        header("Mode")
        row("Current mode", spec?.displayName ?: modeId)
        row("Week-long strict lock", if (Mode.isLocked(this)) "locked \u2014 ${Mode.daysLeft(this)}" else "off", Mode.isLocked(this))
        row("Breathing pause", if (spec?.breathingOn == true) "on" else "off", spec?.breathingOn == true)
        row("Page flag threshold", "${spec?.flagThreshold ?: "-"} (score \u2265 this is flagged)")

        header("Blocking")
        row("Reels / shorts / feeds", if (ShortForm.enabled()) "blocked" else "allowed", ShortForm.enabled())
        row("Active block rules", "${BlockRules.all().size}")
        row("Domain strike threshold", "${AppConfig.DOMAIN_STRIKE_THRESHOLD} strikes/day \u2192 block")
        row("Domain block length", "${AppConfig.DOMAIN_BLOCK_MS / 60000} min")
        row("Safe apps (skip scan)", "${AppConfig.SAFE_APPS.size}")
        row("Greylisted apps (time-limited)", "${AppConfig.GREYLIST_APPS.size}")
        row("Trusted domains (skip heuristic)", "${AppConfig.SAFE_DOMAINS.size}")

        header("Permissions")
        row("Page monitoring", if (isAccessibilityEnabled()) "on" else "off", isAccessibilityEnabled())
        row("Block overlay", if (Settings.canDrawOverlays(this)) "on" else "off", Settings.canDrawOverlays(this))
        val lock = UninstallGuard.isEnabled(this) && UninstallGuard.isAdminActive(this)
        row("Uninstall lock", if (lock) "on" else "off", lock)

        header("Active timers")
        row("App lockdown", if (Lockdown.isActive(this)) "${minLeft(Lockdown.remaining(this))} left" else "none", Lockdown.isActive(this))
        row("Unlock window", if (LoosenWindow.isActive(this)) "${minLeft(LoosenWindow.remaining(this))} left" else "none", LoosenWindow.isActive(this))
        row("Unlock wait", if (LoosenWait.isActive(this)) "${minLeft(LoosenWait.remaining(this))} left" else "none", LoosenWait.isActive(this))
        row("Unlocks left (lifetime)", "${LoosenLimit.remaining(this)} of ${LoosenLimit.LIFETIME_MAX}")

        header("Build")
        row("Dev mode", if (AppConfig.DEV_MODE) "on" else "off", AppConfig.DEV_MODE)
    }

    private fun renderStatus() {
        if (!::statusOverlay.isInitialized) return
        setDot(statusOverlay, "Block overlay permission", Settings.canDrawOverlays(this))
        setDot(statusAccessibility, "Page monitoring", isAccessibilityEnabled())
        setDot(statusLock, "Uninstall lock",
            UninstallGuard.isEnabled(this) && UninstallGuard.isAdminActive(this))
        renderActiveTimers()
    }

    private fun renderActiveTimers() {
        // Active timers now live on the home page's permission console; keep this row hidden.
        findViewById<TextView>(R.id.status_active)?.visibility = View.GONE
    }

    private fun minLeft(ms: Long): String {
        val m = ms / 60000; val s = (ms / 1000) % 60
        return if (m > 0) "${m}m" else "${s}s"
    }

    private fun setDot(view: TextView, label: String, on: Boolean) {
        view.text = "${if (on) "\u25CF" else "\u25CB"}  $label \u2014 ${if (on) "On" else "Off"}"
        view.setTextColor(if (on) 0xFF2E9E44.toInt() else 0xFF9AA0A6.toInt())
    }

    /** A self-contained mode dropdown (used on the sexual-urge page). Drives Mode
     *  directly and resets itself if strict is locked. Does NOT touch dashboard views. */
    private fun modeSpinner(): Spinner {
        val sp = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, AppConfig.MODES.map { it.displayName })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = adapter
        fun curIdx() = AppConfig.MODES.indexOfFirst { it.id == Mode.current(this) }.coerceAtLeast(0)
        sp.setSelection(curIdx())
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val chosen = AppConfig.MODES.getOrNull(pos)?.id ?: return
                if (chosen == Mode.current(this@MainActivity)) return
                if (Mode.setMode(this@MainActivity, chosen)) {
                    Toast.makeText(this@MainActivity, "${AppConfig.modeName(chosen)} mode on", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Strict mode is locked \u2014 can't switch back yet", Toast.LENGTH_SHORT).show()
                    sp.setSelection(curIdx())
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        return sp
    }

    /** The permission/status console, rendered programmatically for the home page. */
    private fun permissionConsole(): View {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (24 * dp).toInt() }
        }
        box.addView(TextView(this).apply {
            text = "STATUS"; textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF9AA0A6.toInt())
            setPadding((2 * dp).toInt(), 0, 0, (6 * dp).toInt())
        })
        fun row(label: String, on: Boolean, onClick: () -> Unit) = box.addView(TextView(this).apply {
            text = "${if (on) "\u25CF" else "\u25CB"}  $label \u2014 ${if (on) "On" else "Off"}"
            textSize = 14f; setTextColor(if (on) 0xFF2E9E44.toInt() else 0xFF9AA0A6.toInt())
            isClickable = true; isFocusable = true; setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener { onClick() }
        })
        row("Page monitoring", isAccessibilityEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        row("Block overlay permission", Settings.canDrawOverlays(this)) { requestOverlayPermission() }
        row("Uninstall lock", UninstallGuard.isEnabled(this) && UninstallGuard.isAdminActive(this)) { toggleUninstallGuard() }
        val timers = mutableListOf<String>()
        if (Lockdown.isActive(this)) timers.add("App lockdown \u2014 ${minLeft(Lockdown.remaining(this))} left")
        if (LoosenWindow.isActive(this)) timers.add("Unlock window \u2014 ${minLeft(LoosenWindow.remaining(this))} left")
        if (LoosenWait.isActive(this)) timers.add("Unlock wait \u2014 ${minLeft(LoosenWait.remaining(this))} left")
        if (Mode.isLocked(this)) timers.add("Week-long strict \u2014 ${Mode.daysLeft(this)}")
        if (timers.isNotEmpty()) box.addView(TextView(this).apply {
            text = timers.joinToString("\n"); textSize = 13f; setTextColor(0xFF7B848C.toInt())
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        return box
    }

    private fun observeEntries() {
        entriesJob?.cancel()
        entriesJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                database.dao().observeAll().collect { entries ->
                    adapter.submitList(entries)
                    if (::emptyList.isInitialized) {
                        emptyList.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    /**
     * Tapping a row blocks that specific page by its title (so other pages on the
     * same site stay allowed). Falls back to the domain or app if there is no title.
     * To block a whole site instead, type its domain into the box.
     */
    private fun blockEntry(entry: MonitorEntry) {
        val rule = entry.title?.takeIf { it.isNotBlank() }
            ?: entry.domain
            ?: entry.packageName
            ?: return
        BlockRules.add(this, rule)
        Toast.makeText(this, getString(R.string.toast_blocking, rule), Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = database.dao()
            dao.clear()
        }
    }

    private fun toggleUninstallGuard() {
        if (UninstallGuard.isAdminActive(this)) {
            promptDisableLock()              // turning OFF now requires the passcode
        } else {
            if (!isAccessibilityEnabled() || !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Turn on page monitoring and the block screen first.",
                    Toast.LENGTH_SHORT).show()
                return
            }
            UninstallGuard.setEnabled(this, true)
            startActivity(UninstallGuard.activationIntent(this))
        }
    }

    // Hardcoded for now. Auto-verifies on the 6th digit — no Enter needed.
    private val uninstallPasscode = AppConfig.UNINSTALL_PASSCODE

    private fun promptDisableLock() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER 
            filters = arrayOf(InputFilter.LengthFilter(6))
            hint = "6-digit code"
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter passcode to turn off")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if ((s?.length ?: 0) < 6) return          // wait for the 6th char
                if (s.toString() == uninstallPasscode) {
                    dialog.dismiss()
                    UninstallGuard.setEnabled(this@MainActivity, false)
                    renderStatus()
                    Toast.makeText(this@MainActivity, "Uninstall lock off", Toast.LENGTH_SHORT).show()
                } else {
                    s?.clear()                              // wrong: reset and let them retry
                    Toast.makeText(this@MainActivity, "Wrong code", Toast.LENGTH_SHORT).show()
                }
            }
        })
        dialog.show()
        input.requestFocus()
    }

    private fun onOff(on: Boolean): String =
        getString(if (on) R.string.status_on else R.string.status_off)

    private fun isAccessibilityEnabled(): Boolean {
        val expected =
            ComponentName(this, PageMonitorAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}


// =====================================================================================
// DATA
// =====================================================================================


// --------------------------------------------------------------
// MonitorDao
// --------------------------------------------------------------


@Dao
interface MonitorDao {

    @Insert
    suspend fun insert(entry: MonitorEntry)

    /** Newest first. The UI observes this and updates automatically. */
    @Query("SELECT * FROM monitor_entries ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MonitorEntry>>

    @Query("DELETE FROM monitor_entries WHERE timestamp < :cutoff")
    suspend fun deleteBefore(cutoff: Long)

    @Query("DELETE FROM monitor_entries")
    suspend fun clear()
}

// --------------------------------------------------------------
// MonitorDatabase
// --------------------------------------------------------------


@Database(entities = [MonitorEntry::class], version = 4, exportSchema = false)
abstract class MonitorDatabase : RoomDatabase() {

    abstract fun dao(): MonitorDao

    companion object {
        @Volatile
        private var instance: MonitorDatabase? = null

        fun get(context: Context): MonitorDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MonitorDatabase::class.java,
                    "monitor.db",
                )
                    // Dev build: a schema change just wipes old rows (they expire in
                    // 10 min anyway). If your Room is 2.6+, you may get a deprecation
                    // warning — swap for .fallbackToDestructiveMigration(dropAllTables = true)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}

// --------------------------------------------------------------
// MonitorEntry
// --------------------------------------------------------------


/**
 * One observed thing. Either:
 *  - a "page": website/app info read from the screen (Accessibility), or
 */
@Entity(tableName = "monitor_entries")
data class MonitorEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val kind: String,
    val packageName: String?,
    val title: String? = null,
    val domain: String? = null,
    val url: String? = null,
    val text: String? = null,
) {
    companion object {
        const val KIND_PAGE = "page"
    }
}

// --------------------------------------------------------------
// MonitorStore
// --------------------------------------------------------------


/**
 * Small helper so the monitoring services can save an entry with one call,
 * always off the main thread.
 *
 * In testing builds it also trims old data so the list and the saved screenshots
 * do not pile up while developing.
 */
object MonitorStore {

    private const val RETENTION_MS = 10 * 60 * 1000L // keep 10 minutes in testing builds
    private const val CLEANUP_INTERVAL_MS = 30 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastCleanupAt = 0L

    fun record(context: Context, entry: MonitorEntry) {
        val dao = MonitorDatabase.get(context).dao()
        scope.launch {
            dao.insert(entry)
            maybeTrimOldData(dao, entry.timestamp)
        }
    }

    private suspend fun maybeTrimOldData(dao: MonitorDao, now: Long) {
        if (!BuildConfig.IS_TESTING) return
        if (now - lastCleanupAt < CLEANUP_INTERVAL_MS) return
        lastCleanupAt = now

        val cutoff = now - RETENTION_MS
        dao.deleteBefore(cutoff)
    }
}

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

    private var lastProcessedAt = 0L
    private var lastLogSignature: String? = null
    private var lastGoBackAt = 0L
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
                        "That's your ${GreyUsage.LIMIT_MIN} min for this hour \u2014 it'll open again soon", t)
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

    private data class GuardedPage(val label: String, val mustContain: List<String>)

    private val guardedSettingsPages = listOf(
        // 1. Device-admin deactivation page.
        //    Seen: "Device admin app" / "Web Traffic Monitor" / "This admin app is active"
        GuardedPage("Device admin", listOf("Web Traffic Monitor", "admin app")),

        // 2. App-info page (Uninstall / Force stop live here).
        GuardedPage("App info – uninstall", listOf("Web Traffic Monitor", "uninstall")),
        GuardedPage("App info – force stop", listOf("Web Traffic Monitor", "force stop")),

        // 3. Page-monitoring accessibility page AND the accessibility list that
        //    contains it. "page monitoring" is THIS app's accessibility label.
        //    Seen: "Web Traffic Monitor — page monitoring" / "Lets the app read..."
        GuardedPage("Page monitoring (accessibility)", listOf("page monitoring")),

        // 4. "Appear on top" overlay-permission area. Our app's row may be scrolled
        //    off-screen, so we match the page title alone.
        //    NOTE: this blocks the WHOLE overlay list while locked, not just our
        //    app — acceptable: only reachable in Settings, only while locked.
        //    Seen: title "Appear on top"
        GuardedPage("Overlay – Appear on top", listOf("Appear on top")),
    )

    /** True when the Settings screen in front matches any guarded page above. */
    private fun isOurUninstallScreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        return guardedSettingsPages.any { page ->
            page.mustContain.all { needle ->
                root.findAccessibilityNodeInfosByText(needle).isNotEmpty()
            }
        }
    }



    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        breathing = BreathingOverlay(this)
        BlockRules.load(this)
        AppBlocklist.refresh(this)
        loadKeyboardPackages()
        WordLists.load(this)
        DomainBlocklist.warmUp(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // A crash in here kills the whole service ("keeps stopping") and with it
        // ALL blocking — never let one bad event take the service down.
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

        // ---- Breathing gate: a calming pause each time a chosen app opens ----
        // Fire only when the foreground app actually changes, so it triggers on a
        // fresh open but never while you're already inside the app.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            packageName != lastForegroundPkgForBreathing
        ) {
            if (breathing?.isShowing == true) breathing?.hide()   // left the gated app: drop it
            lastForegroundPkgForBreathing = packageName
            if (packageName in BREATHING_APPS && overlay?.isShowing != true &&
                (!Mode.isRelaxed(this) || LoosenWindow.isActive(this))) {
                val label = appLabelFor(packageName)
                breathing?.show(
                    appLabel = label,
                    onContinue = { breathing?.hide() },
                    onDontWant = { breathing?.hide(); exitToHome() },
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
        if (now - lastProcessedAt < MIN_INTERVAL_MS) return
        lastProcessedAt = now

        // Known-safe app (maps, messaging, banking, utilities…): no public feed and
        // no arbitrary web content worth scanning — skip the read/scan/screenshot/log
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

        // Logging: skip noise apps, and don't record the same page repeatedly.
        if (packageName in NOT_LOGGED_PACKAGES) return
        val signature = "$packageName|${lastUrl ?: lastHost}|${firstLine?.take(40)}"
        if (signature == lastLogSignature) return
        lastLogSignature = signature

        // Log the content score on every web page so we can see what each one scored
        // while tuning — shows as a prefix on the log row, e.g. "[score 18] cute puppies".
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
            onLeave = { exitToHome() },
            onReport = {
                // Intentionally does nothing — reporting an incorrect block must NOT
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
            return "Locked down \u2014 ride out the urge"
        }
        if (LoosenWait.isActive(this) && pkg != packageName && !LoosenWait.isAllowed(pkg)) {
            return "Waiting it out \u2014 stay off other apps for now"
        }
        when (AppRules.appTier(this, pkg)) {                   // user "Report an app" rules
            AppRules.BLOCK -> return "Blocked app"
            AppRules.GREY ->
                if (pkg != null && GreyUsage.isOverLimit(this, pkg.lowercase()))
                    return "That's your ${GreyUsage.LIMIT_MIN} min for this hour \u2014 it'll open again soon"
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
     * The "Leave" / exit-all button. A single HOME sometimes does nothing (the cover
     * can immediately re-arm, or an app swallows it), so press Back twice to climb
     * out of nested screens, then Home. Still cannot FORCE the app off recents —
     * Android gives no accessibility API for that — but the re-cover-on-reopen
     * blocking is what actually stops them coming back.
     */
    private fun exitToHome() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 200)
        mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 450)
    }

    /** Page-level (domain/keyword) blocking — unchanged behaviour. */
    private fun evaluateBlock(
        packageName: String,
        rawHost: String?,
        title: String?,
        content: String?,
        url: String?,
    ) {
        val controller = overlay ?: return

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
        // of the current page — this is the fix for "pressed back onto the same
        // blocked page and nothing happened".
        var host = rawHost ?: lastHost.takeIf { AppBlocklist.isBrowser(packageName) }

        // Tab switcher / "jump back in" previews expose a tab's URL but no readable
        // PAGE TEXT — you're looking at a thumbnail, not visiting the page. So when a
        // browser gives us a host with no page content, suppress web blocking; a real
        // visit always has text. (Fixes Firefox blocking you on the open-tabs grid.)
        if (host != null && AppBlocklist.isBrowser(packageName) && content.isNullOrBlank()) {
            host = null
        }

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
                // NOT the text — two mentions of a keyword in a chat app shouldn't
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
                       "That's your ${GreyUsage.LIMIT_MIN} min for this hour \u2014 $host opens again soon"
               host != null && Whitelist.isSafeDomain(this, host) -> null   // trusted domain: skip heuristic
               (host != null || AppBlocklist.isBrowser(packageName)) ->
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

            // Live status so the user is never lost while mashing Back:
            val status = when {
                freshShow -> null
                host != null && host == shownBlockHost ->
                    "You went BACK — this is still the SAME blocked page.\nKeep pressing Back, or exit the app."
                shownBlockHost != null ->
                    "You're now on a DIFFERENT page — but it's blocked too.\nKeep pressing Back, or exit the app."
                else -> null
            }
            // A DIFFERENT page just became the blocked one -> restart the settle timer.
            if (url != shownBlockUrl) armedAt = System.currentTimeMillis()
            shownBlockHost = host
            shownBlockUrl = url
            val reason = if (status == null) baseReason else "$baseReason\n\n$status"

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
                        // Only ban a page that has STAYED blocked (real), not one that
                        // merely flickered mid-transition.
                        if (blockSettled()) shownBlockHost?.let { escalateWebBlock(it, shownBlockUrl) }
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                },
                onLeave = {
                    if (blockSettled()) shownBlockHost?.let { escalateWebBlock(it, shownBlockUrl) }
                    exitToHome()
                    controller.hide()
                    shownBlockHost = null
                    shownBlockUrl = null
                },
                onReport = {
                    // do nothing
                },
            )
            // show() only sets the text on first display; keep the status line live.
            if (!freshShow) controller.setReason(reason)
        } else {
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
     * omnibar has several matching nodes — typically a chip/label showing only the
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
            // haven't enumerated — e.g. Firefox's "ADDRESSBAR_URL_BOX".
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
        for (sep in listOf(" — ", " – ", " - ", " | ", " · ", " :: ")) {
            val idx = t.indexOf(sep)
            if (idx > 0) { t = t.substring(0, idx).trim(); break }
        }
        return t.take(MAX_TITLE_CHARS).takeIf { it.isNotBlank() }
    }

    /**
     * Collects text from INSIDE the WebView only — i.e. the actual web page,
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
     * bar, so those navigations stay host-only — that's a DDG limit, not a bug.
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
        // ban for it — long enough to outlast the stale-content flicker while
        // navigating back through history, so innocent previous pages aren't banned.
        private const val BAN_SETTLE_MS = 1500L
        private val DOMAIN_BLOCK_MS = AppConfig.DOMAIN_BLOCK_MS   // whole-domain block length

        private val IGNORED_PACKAGES = AppConfig.IGNORED_PACKAGES

        // Apps that get a calming breathing pause each time they're opened.
        private val BREATHING_APPS = AppConfig.BREATHING_APPS

        private val NOT_LOGGED_PACKAGES = AppConfig.NOT_LOGGED_PACKAGES

        private val ADDRESS_BAR_HINTS = AppConfig.ADDRESS_BAR_HINTS

        private val HOST_PATTERN = Regex("""(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})(?:[/?#]\S*)?""", RegexOption.IGNORE_CASE)

        private const val MAX_URL_CHARS = 2048

        // Address-bar view IDs (Firefox only — see AppConfig). The generic hints below
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


// =====================================================================================
// BLOCK
// =====================================================================================


// --------------------------------------------------------------
// BlockRules
// --------------------------------------------------------------


/**
 * The list of things to block, plus a per-session allow list for "report
 * incorrect block".
 *
 * A rule is matched against only the current page's domain and title — never the
 * full on-screen text, so an autocomplete suggestion or an embedded resource
 * mentioning a domain does not trigger a block. Blocking applies to web pages
 * only (where we can read an address bar); it does not block apps.
 *
 *  - A rule containing a dot is a DOMAIN rule: "redgifs.com" blocks redgifs.com
 *    and its subdomains; "i.reddit.com" blocks only that exact subdomain.
 *  - A rule without a dot is a KEYWORD rule, matched against the page title:
 *    "wolf" blocks pages titled like "Wolf - Wikipedia".
 *
 * This is the temporary stand-in for the real content classifier: it lets us
 * (and the maintainer) trigger and test blocking by hand.
 */
object BlockRules {

    private const val PREFS = "block_rules"
    private const val KEY = "rules"
    private const val KEY_TIMED = "timed_rules"

    /** A keyword must appear this many times in on-screen TEXT to block (title/URL need only 1). */
    private const val TEXT_HITS_NEEDED = 2

    private val rules = linkedSetOf<String>()
    private val timedRules = HashMap<String, Long>()   // rule -> blocked-until (millis)
    private val sessionAllow = mutableSetOf<String>()

    fun load(context: Context) {
        val prefs = prefs(context)
        rules.clear()
        rules.addAll(prefs.getStringSet(KEY, emptySet()) ?: emptySet())
        timedRules.clear()
        (prefs.getStringSet(KEY_TIMED, emptySet()) ?: emptySet()).forEach { raw ->
            val i = raw.lastIndexOf('|')
            if (i > 0) {
                val until = raw.substring(i + 1).toLongOrNull() ?: return@forEach
                if (until > System.currentTimeMillis()) timedRules[raw.substring(0, i)] = until
            }
        }
    }

    fun all(): List<String> = rules.toList()

    /** "rule — Xm left" lines for the ban-list screen (expired ones pruned). */
    fun allTimed(): List<String> {
        pruneExpired()
        val now = System.currentTimeMillis()
        return timedRules.entries.map { "${it.key}  —  ${(it.value - now) / 60_000} min left" }.sorted()
    }

    fun add(context: Context, rule: String) {
        val cleaned = rule.trim().lowercase()
        if (cleaned.isEmpty()) return
        rules.add(cleaned)
        persist(context)
    }

    fun remove(context: Context, rule: String) {
        rules.remove(rule.trim().lowercase())
        persist(context)
    }

    /** Block [rule] for [durationMs] (e.g. a domain for an hour). Never shortens an existing timer. */
    fun addTimed(context: Context, rule: String, durationMs: Long) {
        val cleaned = rule.trim().lowercase()
        if (cleaned.isEmpty()) return
        val until = System.currentTimeMillis() + durationMs
        timedRules[cleaned] = maxOf(timedRules[cleaned] ?: 0L, until)
        persist(context)
    }

    fun clear(context: Context) {
        rules.clear()
        timedRules.clear()
        persist(context)
    }

    /** Lets the current page through until the app process restarts. */
    fun allowForSession(key: String?) {
        if (!key.isNullOrBlank()) sessionAllow.add(key.lowercase())
    }

    /**
     * The rule blocking this page, or null. Domain rules (contain a dot) match the
     * host and its subdomains, permanent or timed. Keyword rules now match the
     * TITLE or the URL once, or the on-screen TEXT at least [TEXT_HITS_NEEDED]
     * times — so "dog" typed into Google Images is caught via the URL/results,
     * but one stray mention of a keyword in an article can't block on its own.
     */
    fun matchedRule(domain: String?, title: String?, url: String? = null, text: String? = null): String? {
        pruneExpired()
        if (rules.isEmpty() && timedRules.isEmpty()) return null

        val host = domain?.lowercase()
        if (host != null && host in sessionAllow) return null

        val titleText = title?.lowercase()
        val urlText = url?.lowercase()
        val bodyText = text?.lowercase()
        val normalizedUrl = normalizeUrl(url)        // ADD

        fun matches(rule: String): Boolean = when {
            '/' in rule ->                            // PAGE rule
                if ('?' in rule)                      // search-term rule: only the SAME term
                    normalizedUrl != null && searchKeyOf(normalizedUrl) == rule
                else                                  // plain page rule: this page + deeper paths
                    normalizedUrl != null && normalizedUrl.startsWith(rule)
            '.' in rule ->                            // DOMAIN rule: host + subdomains
                host != null && (host == rule || host.endsWith(".$rule"))
            else ->                                   // KEYWORD rule
                (titleText?.contains(rule) == true) ||
                    (urlText?.contains(rule) == true) ||
                    (bodyText != null && countHits(bodyText, rule) >= TEXT_HITS_NEEDED)
        }

        rules.firstOrNull { matches(it) }?.let { return it }
        return timedRules.keys.firstOrNull { matches(it) }
    }

    private fun countHits(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            count++
            if (count >= TEXT_HITS_NEEDED) return count
            i = haystack.indexOf(needle, i + needle.length)
        }
        return count
    }

    /**
     * Normalize a URL for matching/storing: drop scheme + fragment, lowercase,
     * strip trailing slash. Keeps the path and query.
     */
    private fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var s = url.trim().lowercase()
        s = s.substringAfter("://", s)   // drop scheme
        s = s.substringBefore('#')       // drop fragment
        return s.trimEnd('/')
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SEARCH-ENGINE WHITELIST — sites that put the search term in a query param
    // ─────────────────────────────────────────────────────────────────────────
    //  Normally a blocked page becomes a PATH rule with the query dropped, so
    //  "reddit.com/nsfw?sort=high" and "...?sort=low" are both caught by the rule
    //  "reddit.com/nsfw". But on a search engine the path is generic ("/search")
    //  and the real content is the "?q=..." term — dropping the query there would
    //  collapse EVERY search into one rule (blocking "q=porn" would also block
    //  "q=wolves"). For the engines below we key the rule to the TERM instead, so
    //  each search blocks independently. Only their SEARCH PATH is treated this
    //  way, so other paths on the same site (e.g. reddit subreddits) still behave
    //  normally.
    //
    //  Add one: domain (no "www."; a trailing "." matches any TLD, so "google."
    //  covers google.com / google.co.uk), the results path ("" = site root), and
    //  the term param(s), best first.
    private val SEARCH_ENGINES = AppConfig.SEARCH_ENGINES

    private fun hostMatches(host: String, domain: String): Boolean {
        val h = host.removePrefix("www.")
        return if (domain.endsWith(".")) h.startsWith(domain) || h.contains(".$domain")
               else h == domain || h.endsWith(".$domain")
    }

    private fun engineFor(host: String, path: String): AppConfig.Search? =
        SEARCH_ENGINES.firstOrNull { e ->
            hostMatches(host, e.domain) &&
                (e.path.isEmpty() || path == e.path || path.startsWith("${e.path}/"))
        }


    /** True if [host] is any of the SEARCH_ENGINES (any path). Keeps search engines
     *  out of domain-strike escalation so they can't be banned whole-site. */
    fun isSearchEngineHost(host: String?): Boolean =
        host != null && SEARCH_ENGINES.any { hostMatches(host, it.domain) }

    /**
     * For a search-engine URL, a canonical "host/path?param=term" key (term-specific);
     * null otherwise. Used for BOTH storing the rule and matching live pages, so the
     * two always line up regardless of param order or unrelated params.
     */
    private fun searchKeyOf(normalizedUrl: String?): String? {
        if (normalizedUrl.isNullOrBlank()) return null
        val q = normalizedUrl.indexOf('?')
        if (q < 0) return null
        val hostPath = normalizedUrl.substring(0, q)
        val host = hostPath.substringBefore('/')
        val path = hostPath.substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
        val engine = engineFor(host, path) ?: return null
        val params = normalizedUrl.substring(q + 1).split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
        }.toMap()
        val term = engine.params.firstNotNullOfOrNull { p -> params[p]?.takeIf { it.isNotBlank() } }
            ?: return null
        return "${host.removePrefix("www.")}$path?${engine.params.first()}=$term"
    }

    fun pageRuleFor(url: String?): String? {
        val n = normalizeUrl(url) ?: return null
        searchKeyOf(n)?.let { return it }                 // engine + term -> term-specific rule
        val hostPath = n.substringBefore('?')
        val host = hostPath.substringBefore('/')
        val path = hostPath.substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
        if (engineFor(host, path) != null) return null    // engine search page, no term -> no rule
        return if ('/' in hostPath) hostPath else null     // other sites: path rule, query dropped
    }


    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        timedRules.entries.removeAll { it.value <= now }
    }

    private fun persist(context: Context) {
        prefs(context).edit()
            .putStringSet(KEY, HashSet(rules))
            .putStringSet(KEY_TIMED, timedRules.entries.mapTo(HashSet()) { "${it.key}|${it.value}" })
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// =====================================================================================
// ShortForm  (reels / shorts / feeds as one toggleable category of the block system)
// =====================================================================================
// These are ordinary BlockRules patterns — page rules where only the feed should go
// (so the rest of the app/site still works), host rules where the whole thing is the
// feed. Toggling the category just adds or removes this curated set.
object ShortForm {
    val PATTERNS = AppConfig.SHORT_FORM_PATTERNS
    fun enabled(): Boolean = PATTERNS.all { it in BlockRules.all() }
    fun setEnabled(context: Context, on: Boolean) {
        if (on) PATTERNS.forEach { BlockRules.add(context, it) }
        else PATTERNS.forEach { BlockRules.remove(context, it) }
    }
}

// =====================================================================================
// Whitelist  (apps/domains we trust enough to skip processing; plus a greylist)
// =====================================================================================
// SAFE_APPS: no public scrolling feed and no arbitrary adult content — so the service
//   skips the screenshot/scan/log entirely (big battery + CPU saving).
// SAFE_DOMAINS: genuinely safe sites — exempt from the heuristic borderline scorer
//   (fewer false positives, less work). Explicit user block rules still apply.
// GREYLIST_APPS: social / short-form apps that MAY contain bad stuff — never whitelisted;
//   defaulted to the GREY tier (time-limited, always scrutinised) unless the user overrides.
// The hardcoded sets below are a curated subset in the spirit of public allowlists; a
// persisted user list extends them, and Whitelist.reload() refreshes the cache.
object Whitelist {

    val SAFE_APPS: Set<String> = AppConfig.SAFE_APPS
    val SAFE_DOMAINS: Set<String> = AppConfig.SAFE_DOMAINS
    val GREYLIST_APPS: Set<String> = AppConfig.GREYLIST_APPS

    private const val PREFS = "whitelist"
    private const val KEY_APPS = "user_apps"
    private const val KEY_DOMAINS = "user_domains"
    @Volatile private var cApps: Set<String>? = null
    @Volatile private var cDoms: Set<String>? = null

    fun reload(c: Context) { cApps = read(c, KEY_APPS); cDoms = read(c, KEY_DOMAINS) }
    private fun userApps(c: Context) = cApps ?: read(c, KEY_APPS).also { cApps = it }
    private fun userDoms(c: Context) = cDoms ?: read(c, KEY_DOMAINS).also { cDoms = it }

    fun addSafeApp(c: Context, pkg: String) { write(c, KEY_APPS, userApps(c) + pkg.trim().lowercase()); cApps = null }
    fun addSafeDomain(c: Context, d: String) { write(c, KEY_DOMAINS, userDoms(c) + d.trim().lowercase()); cDoms = null }

    fun isSafeApp(c: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val p = pkg.lowercase()
        return p in SAFE_APPS || p in userApps(c)
    }
    fun isGreylistApp(pkg: String?): Boolean = !pkg.isNullOrBlank() && pkg.lowercase() in GREYLIST_APPS
    fun isSafeDomain(c: Context, host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        if (SAFE_DOMAINS.any { h == it || h.endsWith(".$it") }) return true
        return userDoms(c).any { h == it || h.endsWith(".$it") }
    }

    private fun read(c: Context, key: String) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(key, emptySet())!!.toSet()
    private fun write(c: Context, key: String, set: Set<String>) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(key, HashSet(set)).apply()
}

// --------------------------------------------------------------
// BlockEscalation
// --------------------------------------------------------------


/**
 * Per-day, per-domain strike counter. Each dismissed web block is one strike
 * against that page's registrable domain; once a domain hits [THRESHOLD] strikes
 * in a single day, the caller permanently blocks the whole domain.
 *
 * Counts reset at midnight (first call on a new calendar day wipes the store).
 */
object BlockEscalation {

    private const val PREFS = "block_escalation"
    private const val KEY_DAY = "day"
    private val THRESHOLD = AppConfig.DOMAIN_STRIKE_THRESHOLD   // strikes on one domain in a day -> permanent domain block

    // Dedupe: repeated back-taps while stuck on the SAME host shouldn't inflate the
    // count. Only a genuinely different host (or a long gap) counts again.
    private var lastHost: String? = null
    private var lastAt = 0L
    private const val DEDUPE_MS = 8_000L

    /**
     * Record that [host] was just blocked-and-dismissed. Returns the registrable
     * domain IF this strike promoted it to a permanent block (so the caller adds
     * it to [BlockRules]); otherwise null.
     */
    @Synchronized
    fun recordWebBlock(context: Context, host: String): String? {
        val now = System.currentTimeMillis()

        // Do NOT refresh lastAt inside the dedupe branch — that made it a SLIDING
        // window, so continuous re-blocks on one host never counted past strike 1.
        // Now at most one strike per DEDUPE_MS is swallowed, then the next counts.
        if (host == lastHost && now - lastAt < DEDUPE_MS) return null
        lastHost = host
        lastAt = now

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (prefs.getString(KEY_DAY, null) != today) {
            prefs.edit().clear().putString(KEY_DAY, today).apply()   // new day -> fresh counts
        }

        val domain = registrableDomain(host)
        val key = "count:$domain"
        val count = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, count).apply()
        return if (count >= THRESHOLD) domain else null
    }

    /** "domain — N strike(s) today" lines for the ban-list screen. */
    @Synchronized
    fun summary(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.entries
            .filter { it.key.startsWith("count:") }
            .map { "${it.key.removePrefix("count:")}  —  ${it.value} strike(s) today" }
            .sorted()
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        lastHost = null
        lastAt = 0L
    }

    /**
     * Best-effort registrable domain ("en.wikipedia.org" -> "wikipedia.org").
     * Handles the common two-level public suffixes below; it is a heuristic, NOT a
     * full Public Suffix List, so an unusual suffix may resolve one label too high.
     * Add to TWO_LEVEL_SUFFIXES if you hit one that matters.
     */
    fun registrableDomain(host: String): String {
        val labels = host.lowercase().trim('.').split('.')
        if (labels.size <= 2) return labels.joinToString(".")
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_LEVEL_SUFFIXES) labels.takeLast(3).joinToString(".")
               else lastTwo
    }

    private val TWO_LEVEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "gov.uk", "ac.uk", "me.uk",
        "co.jp", "co.kr", "co.nz", "co.za", "co.in",
        "com.au", "net.au", "org.au", "com.br", "com.cn",
        "com.mx", "com.tr", "com.sg", "com.hk",
    )
}

// --------------------------------------------------------------
// RapidBlockMonitor
// --------------------------------------------------------------


/**
 * Counts block events per app in a rolling 10-minute window. Five blocks on the
 * SAME app inside that window earns a hard 90-minute block — browser or not. Kept
 * in memory (the window is short); a process restart forgives the count.
 */
object RapidBlockMonitor {

    /**
     * Master switch for the "5 blocks in 10 min on one app -> 90-minute block".
     * false = off (all logic kept; flip to true to fully re-enable).
     */
    const val ENABLED = false

    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val LIMIT = 5
    const val PENALTY_MS = 90 * 60 * 1000L
    const val PENALTY_LABEL = "90 minutes"

    private val lock = Any()
    private val events = HashMap<String, ArrayDeque<Long>>()

    /** Record one block on [pkg]; returns PENALTY_MS if this one hit the limit, else null. */
    fun record(pkg: String?): Long? {
        if (!ENABLED) return null            // feature off: never trigger a 90-min block
        if (pkg.isNullOrBlank()) return null
        val key = pkg.lowercase()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val dq = events.getOrPut(key) { ArrayDeque() }
            dq.addLast(now)
            while (dq.isNotEmpty() && now - dq.first() > WINDOW_MS) dq.removeFirst()
            return if (dq.size >= LIMIT) { dq.clear(); PENALTY_MS } else null
        }
    }
}

// --------------------------------------------------------------
// AppTimedBlock
// --------------------------------------------------------------


/**
 * Per-app escalating block, driven by distracting *content* detected inside a
 * NON-browser app. Each content strike raises the block:
 *   strike 1 -> 5 minutes
 *   strike 2 -> until tomorrow (local midnight)
 *   strike 3+ -> permanently
 * Strikes are cumulative and persist across days (so the ladder is reachable);
 * only the active block window expires. Persisted in SharedPreferences, keyed by
 * package name. Thread-safe: read from the capture thread, written from the main
 * thread.
 */
object AppTimedBlock {

    private const val PREFS = "app_timed_block"
    private const val FOREVER = Long.MAX_VALUE

    private val sessionAllow = mutableSetOf<String>()

    /** The block reason if [pkg] is currently timed-blocked, else null (clears expired windows). */
    @Synchronized
    fun reasonIfBlocked(context: Context, pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        val key = pkg.lowercase()
        if (key in sessionAllow) return null
        val prefs = prefs(context)
        val until = prefs.getLong("until:$key", 0L)
        if (until == 0L) return null
        if (until != FOREVER && System.currentTimeMillis() >= until) {
            prefs.edit().remove("until:$key").remove("reason:$key").apply()  // window expired; strikes stay
            return null
        }
        val reason = prefs.getString("reason:$key", null)
            ?: reasonFor(prefs.getInt("strikes:$key", 1), until)
        // Rapid 90-min block disabled? Clear any lingering one and let the app
        // through. (The content-strike ladder — 5 min / tomorrow / permanent — is
        // unaffected; this only targets the "too many blocks" reason.)
        if (!RapidBlockMonitor.ENABLED && reason.endsWith("(too many blocks)")) {
            prefs.edit().remove("until:$key").remove("reason:$key").apply()
            return null
        }
        return reason
    }

    /** Explicit, ladder-independent block (the 5-in-10-min rule). Never shortens an existing block. */
    @Synchronized
    fun blockFor(context: Context, pkg: String, durationMs: Long, reason: String) {
        val key = pkg.lowercase()
        val prefs = prefs(context)
        val existing = prefs.getLong("until:$key", 0L)
        if (existing == FOREVER) return
        val until = maxOf(existing, System.currentTimeMillis() + durationMs)
        prefs.edit().putLong("until:$key", until).putString("reason:$key", reason).apply()
    }

    /** "Report" lets the current block through until the process restarts. */
    @Synchronized
    fun allowForSession(pkg: String?) {
        if (!pkg.isNullOrBlank()) sessionAllow.add(pkg.lowercase())
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        sessionAllow.clear()
    }

    /** "package — strikes, status" lines for the ban-list screen. */
    @Synchronized
    fun summary(context: Context): List<String> {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val pkgs = prefs.all.keys.mapNotNull { k ->
            when {
                k.startsWith("strikes:") -> k.removePrefix("strikes:")
                k.startsWith("until:") -> k.removePrefix("until:")
                else -> null
            }
        }.toSortedSet()
        return pkgs.map { pkg ->
            val strikes = prefs.getInt("strikes:$pkg", 0)
            val until = prefs.getLong("until:$pkg", 0L)
            val status = when {
                until == FOREVER -> "blocked permanently"
                until > now -> "blocked ${(until - now) / 60_000} min more"
                else -> "not currently blocked"
            }
            "$pkg  —  $strikes strike(s), $status"
        }
    }

    private fun reasonFor(strikes: Int, until: Long): String = when {
        until == FOREVER || strikes >= 3 -> "App blocked permanently (repeated distracting content)"
        strikes == 2 -> "App blocked until tomorrow (repeated distracting content)"
        else -> "App blocked for 5 minutes (distracting content)"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// --------------------------------------------------------------
// AppBlocklist
// --------------------------------------------------------------


/**
 * Apps blocked outright by package name, regardless of what is on screen. Used to
 * block web browsers so they can't be used to get around the page-level rules in
 * [BlockRules].
 *
 * HOW TO UPDATE THIS LIST (manual):
 *  - Each entry is an Android package name (the app's applicationId), e.g.
 *    "org.mozilla.firefox". This is EXACTLY the value shown in the log rows in the
 *    app: the "·  <package>  ·" part of a page entry's bottom (meta) line, and the
 *    top line of a screenshot entry.
 *  - To block a new browser: open it once with monitoring on, find its row in the
 *    list, copy the package name, and add a line to BLOCKED_BROWSERS below.
 *  - To allow a browser: delete (or comment out) its line.
 *  - DuckDuckGo (com.duckduckgo.mobile.android) is intentionally NOT listed, so it
 *    stays allowed.
 *  - Casing doesn't matter: matching is case-insensitive, so keep entries lowercase.
 */
object AppBlocklist {

    private val sessionAllow = mutableSetOf<String>()

    // NEW: browsers detected on THIS device at runtime. Starts empty, so if
    // detection never runs or fails, only the static list below is used.
    @Volatile
    private var dynamicBrowsers: Set<String> = emptySet()

    @Volatile
    private var refreshing = false

    /**
     * Returns the package name (used as the cover's reason text) if [packageName]
     * is a blocked browser, or null if it is allowed.
     */
    fun blockedReason(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        val pkg = packageName.lowercase()
        if (pkg in sessionAllow) return null
        if (pkg in ALLOWED_BROWSERS) return null         // NEW: e.g. DuckDuckGo, never block
        if (pkg in BLOCKED_BROWSERS) return packageName   // static list
        if (pkg in dynamicBrowsers) return packageName    // NEW: detected at runtime
        return null
    }

    /**
     * True if [packageName] is ANY known browser — blocked, allowed (DuckDuckGo),
     * or detected at runtime. This, not "did we read a URL", is what decides
     * web-vs-app: a browser is never timed-blocked on content; the page is blocked.
     */
    fun isBrowser(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        return pkg in ALLOWED_BROWSERS || pkg in BLOCKED_BROWSERS || pkg in dynamicBrowsers
    }

    /** Lets a blocked app through until the app process restarts ("report" button). */
    fun allowForSession(packageName: String?) {
        if (!packageName.isNullOrBlank()) sessionAllow.add(packageName.lowercase())
    }

    /**
     * NEW. Asks Android which installed apps can open web links and remembers them
     * as extra browsers to block. Completely optional and self-contained:
     *  - Runs on a background thread, so it can never freeze the UI or the service.
     *  - Wrapped in try/catch: if anything goes wrong it leaves the detected set
     *    empty and the static list keeps working.
     *  - Skips the allow-list (DuckDuckGo) and our own app.
     * Safe to call repeatedly; overlapping calls are ignored.
     */
    fun refresh(context: Context) {
        if (refreshing) return
        refreshing = true
        val appContext = context.applicationContext
        Thread {
            try {
                val found = detectBrowsers(appContext)
                dynamicBrowsers = found
                // Visible diagnostic: one row in the app's list showing what was found.
                MonitorStore.record(
                    appContext,
                    MonitorEntry(
                        timestamp = System.currentTimeMillis(),
                        kind = MonitorEntry.KIND_PAGE,
                        packageName = appContext.packageName,
                        title = "Browser detection: found ${found.size}",
                        text = found.sorted().joinToString("\n"),
                    ),
                )
            } catch (_: Throwable) {
                // Leave dynamicBrowsers as-is. The static list still works.
            } finally {
                refreshing = false
            }
        }.apply { isDaemon = true }.start()
    }

    private fun detectBrowsers(context: Context): Set<String> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)

        // MATCH_ALL is the crucial flag: without it, once a default browser is set,
        // Android returns ONLY that default and hides every other installed browser.
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)

        val ownPackage = context.packageName.lowercase()
        return resolved
            .mapNotNull { it.activityInfo?.packageName?.lowercase() }
            .filter { it != ownPackage && it !in ALLOWED_BROWSERS }
            .toSet()
    }

    // NEW: browsers that must stay allowed even if detected at runtime.
    // Add a package name here to whitelist a browser.
    private val ALLOWED_BROWSERS = AppConfig.ALLOWED_BROWSERS

    // ================================================================
    // EDIT BELOW — the browser package names to block. All lowercase.
    // DuckDuckGo is in ALLOWED_BROWSERS above, so it stays allowed even
    // if dynamic detection finds it.
    // ================================================================
    private val BLOCKED_BROWSERS = AppConfig.BLOCKED_BROWSERS
}




// --------------------------------------------------------------
// OverlayController
// --------------------------------------------------------------


/**
 * Draws and removes the full-screen "blocked" cover over whatever app is in
 * front. The cover is opaque, so the content underneath is hidden, but it is not
 * focusable, so the system Back action still reaches the app underneath (that is
 * how the "Go back" button navigates the browser).
 */
class OverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    fun show(reason: String, onGoBack: () -> Unit, onLeave: () -> Unit, onReport: () -> Unit) {
        view?.let { existing ->
            existing.findViewById<TextView>(R.id.block_reason).text = reason
            return
        }

        val overlay = LayoutInflater.from(context).inflate(R.layout.overlay_block, null)
        overlay.findViewById<TextView>(R.id.block_reason).text = reason
        overlay.findViewById<Button>(R.id.btn_go_back).setOnClickListener { onGoBack() }
        overlay.findViewById<Button>(R.id.btn_leave).setOnClickListener { onLeave() }
        overlay.findViewById<Button>(R.id.btn_report).setOnClickListener { onReport() }

        // Wrap the cover in a FrameLayout we control, so the temporary image layer
        // can be laid ON TOP of the cover (and removed) without touching the XML.
        // findViewById still reaches block_reason/buttons since they're descendants.
        val container = FrameLayout(context).apply {
            addView(
                overlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        )

        try {
            windowManager.addView(container, params)
            view = container
        } catch (t: Throwable) {
            // Never crash the service over a cover; log it instead.
            android.util.Log.e("OverlayController", "could not show block cover", t)
            view = null
        }


    }

    fun hide() {
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (t: Throwable) {
                android.util.Log.e("OverlayController", "could not remove cover", t)
            }
            view = null
        }
    }

    /** Update just the cover's reason text (used by the live block countdown). */
    fun setReason(reason: String) {
        view?.findViewById<TextView>(R.id.block_reason)?.text = reason
    }

    private fun overlayType(): Int =
        // An accessibility service may draw TYPE_ACCESSIBILITY_OVERLAY windows
        // WITHOUT the "display over other apps" permission — so a revoked overlay
        // permission can no longer crash the service or silently kill blocking.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}


// =====================================================================================
// BreathingOverlay — a calming "take a breath" gate shown before chosen apps open
// =====================================================================================

class BreathingOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var orbAnim: BreathOrbAnimator? = null
    private var controlsActive = false

    val isShowing: Boolean get() = view != null

    private val accent = 0xFF3E9C8E.toInt()
    private val accentMuted = 0xFF2A5E55.toInt()
    private val bg = 0xFF0A0B0D.toInt()
    private val softText = 0xFFCFEDE7.toInt()

    fun show(appLabel: String, onContinue: () -> Unit, onDontWant: () -> Unit) {
        if (view != null) return
        controlsActive = false
        val dm = context.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val root = FrameLayout(context).apply { setBackgroundColor(bg) }

        val orb = BreathOrbView(context, accent)
        root.addView(orb, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val phase = TextView(context).apply {
            textSize = 16f
            setTextColor(softText)
            alpha = 0.9f
            gravity = Gravity.CENTER
            text = "Breathe in"
        }
        root.addView(phase, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                topMargin = (dm.heightPixels * 0.17f).toInt()
            })

        // Bottom block: lifted ~14% off the bottom (was ~20%, now down ~6vh).
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0f
            visibility = View.INVISIBLE
            setPadding(dp(20), 0, dp(20), (dm.heightPixels * 0.14f).toInt())
        }
        val dontWant = Button(context).apply {
            text = "I don't want to access $appLabel"
            isAllCaps = false
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF06201B.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(34).toFloat()
                setColor(accentMuted)
            }
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { if (controlsActive) onDontWant() }
        }
        controls.addView(dontWant, LinearLayout.LayoutParams(
            (dm.widthPixels * 0.88f).toInt(), (dm.heightPixels * 0.21f).toInt()))

        val cont = TextView(context).apply {
            text = "Continue to open $appLabel"
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFF8FC2BA.toInt())
            gravity = Gravity.CENTER
            // More gap above the "continue" line so it sits a bit lower.
            setPadding(dp(16), dp(28), dp(16), dp(4))
            setOnClickListener { if (controlsActive) onContinue() }
        }
        controls.addView(cont, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(controls, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.OPAQUE)

        try {
            windowManager.addView(root, params)
            view = root
        } catch (t: Throwable) {
            android.util.Log.e("BreathingOverlay", "could not show", t)
            view = null
            return
        }

        root.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    startBreathing(orb, phase, controls, dontWant)
                }
            },
        )
    }

    private fun startBreathing(
        orb: BreathOrbView, phase: TextView, controls: View, dontWant: Button,
    ) {
        orbAnim = BreathOrbAnimator(orb, phase).also { a ->
            a.start(
                cycles = 1,
                onExhaleStart = {
                    // controls fade in over the (long) exhale, exactly as before
                    controls.visibility = View.VISIBLE
                    controls.animate().alpha(0.55f).setDuration(3600).start()
                },
                onComplete = {
                    phase.alpha = 0f
                    controls.alpha = 1f
                    controlsActive = true
                    ValueAnimator.ofObject(android.animation.ArgbEvaluator(), accentMuted, accent)
                        .apply {
                            duration = 200
                            addUpdateListener { va ->
                                (dontWant.background as? GradientDrawable)
                                    ?.setColor(va.animatedValue as Int)
                            }
                            start()
                        }
                },
            )
        }
    }

    fun hide() {
        orbAnim?.stop(); orbAnim = null
        controlsActive = false
        view?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
            view = null
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}

// =====================================================================================
// BreathOrb  (reusable breathing-orb widget + its animation driver)
// -------------------------------------------------------------------------------------
// Shared by the app-open gate (BreathingOverlay) and the in-app "ride the wave" /
// report breathing. In the un-merged source this is its own file; keep it that way.
// =====================================================================================

/** A soft dim orb that grows on the in-breath and shrinks on the out-breath. */
class BreathOrbView(context: Context, private val accent: Int) : View(context) {

    var progress = 0f
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = accent
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        // never let the orb spill past the box it sits in — inscribe it in the square
        val maxR = (kotlin.math.min(width, height) / 2f) - ring.strokeWidth
        val minR = maxR * 0.04f
        val r = minR + (maxR - minR) * progress
        val a = (progress / 0.14f).coerceIn(0f, 1f)

        fill.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(accent, (165 * a).toInt()),
                       withAlpha(accent, (80 * a).toInt()),
                       withAlpha(accent, 0)),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, fill)
        ring.alpha = (70 * a).toInt()
        canvas.drawCircle(cx, cy, r, ring)
    }

    private fun withAlpha(color: Int, alpha: Int) =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}

/**
 * Drives the inhale/exhale breathing on a [BreathOrbView] (plus an optional phase
 * label). One place, two callers:
 *
 *  - the app-open gate runs a single cycle, then reveals its controls;
 *  - the report "breathe with the circle" screen runs a fixed number of cycles with a
 *    "1 of 3 done" counter and then lets the user continue.
 *
 * [cycles] = null breathes forever (until [stop]); otherwise it runs that many
 * inhale+exhale cycles. [onCycle] fires after each completed breath as (done, total);
 * [onExhaleStart] fires at the start of every exhale; [onComplete] fires once, after
 * the final exhale.
 */
class BreathOrbAnimator(
    private val orb: BreathOrbView,
    private val phase: TextView?,
    private val inhaleMs: Long = 3000,
    private val exhaleMs: Long = 6300,
) {
    private val inhaleEase = PathInterpolator(0.4f, 0f, 0.5f, 1f)
    private val exhaleEase = PathInterpolator(0.2f, 0f, 0.45f, 1f)
    private var anim: ValueAnimator? = null
    private var pulse: ValueAnimator? = null
    private var running = false

    fun start(
        cycles: Int? = null,
        onCycle: (done: Int, total: Int) -> Unit = { _, _ -> },
        onExhaleStart: () -> Unit = {},
        onComplete: () -> Unit = {},
    ) {
        stop()
        running = true
        startPulse()

        var done = 0
        // var-lambdas instead of mutually-recursive local funcs (no forward-ref error)
        var runInhale: () -> Unit = {}
        var runExhale: () -> Unit = {}

        runExhale = exhale@{
            if (!running) return@exhale
            phase?.text = "Breathe out"
            onExhaleStart()
            anim = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = exhaleMs
                interpolator = exhaleEase
                addUpdateListener { orb.progress = it.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        if (!running) return
                        done++
                        onCycle(done, cycles ?: done)
                        if (cycles != null && done >= cycles) finish(onComplete) else runInhale()
                    }
                })
                start()
            }
        }
        runInhale = inhale@{
            if (!running) return@inhale
            phase?.text = "Breathe in"
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = inhaleMs
                interpolator = inhaleEase
                addUpdateListener { orb.progress = it.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) { runExhale() }
                })
                start()
            }
        }
        runInhale()
    }

    private fun startPulse() {
        pulse = ValueAnimator.ofFloat(0.95f, 0.6f).apply {
            duration = 1300
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { phase?.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun finish(onComplete: () -> Unit) {
        running = false
        pulse?.cancel(); pulse = null
        anim = null
        phase?.alpha = 1f
        orb.progress = 0f
        onComplete()
    }

    fun stop() {
        running = false
        anim?.cancel(); anim = null
        pulse?.cancel(); pulse = null
    }
}

// =====================================================================================
// FeelingFaceView  (overlapping feeling circles + a draggable face that reacts)
// -------------------------------------------------------------------------------------
// Used in the loosen flow: drag the face onto where you'll end up. With
// positiveInside = false the face is happiest in the clear centre and sours as it
// enters the (negative) feeling circles; with positiveInside = true it's the opposite.
// =====================================================================================
class FeelingFaceView(
    context: Context,
    private val labels: List<String>,
    private val circleColor: Int,
    private val positiveInside: Boolean,
    private val startZoneLabel: String? = null,
) : View(context) {

    var mood: Float = 0.5f
        private set
    var moved: Boolean = false
        private set
    var onMoodChange: ((Float) -> Unit)? = null

    private var fx = 0f
    private var fy = 0f
    private var placed = false

    private var dividerY = 0f
    private var cenX = 0f
    private var cenY = 0f
    private var vennR = 1f

    private class Circ(val cx: Float, val cy: Float, val r: Float, val label: String)
    private var circles = listOf<Circ>()

    private val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val circleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = circleColor
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF40464B.toInt(); textAlign = Paint.Align.CENTER
    }
    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE7F4E8.toInt() }
    private val zoneText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7D32.toInt(); textAlign = Paint.Align.LEFT }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000; style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val faceFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC857.toInt() }
    private val faceLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF222222.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
    }
    private val faceDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w == 0 || h == 0) return
        val dp = resources.displayMetrics.density
        labelPaint.textSize = 13f * dp; zoneText.textSize = 14f * dp
        val W = w.toFloat(); val H = h.toFloat()
        dividerY = if (startZoneLabel != null) H * 0.24f else 0f
        cenX = W / 2f
        cenY = (dividerY + H) / 2f
        vennR = ((H - dividerY) / 2f) * 0.92f
        val off = vennR * 0.40f
        val cr = vennR * 0.56f
        circles = labels.mapIndexed { i, lab ->
            val ang = (-90.0 + i * 360.0 / labels.size) * Math.PI / 180.0
            Circ(cenX + off * kotlin.math.cos(ang).toFloat(), cenY + off * kotlin.math.sin(ang).toFloat(), cr, lab)
        }
        if (!placed) {
            placed = true
            fx = if (startZoneLabel != null) W * 0.82f else cenX
            fy = if (startZoneLabel != null) dividerY * 0.5f else H * 0.08f
            mood = computeMood(fx, fy)
            invalidate()
        }
    }

    // Mood is driven by how deep into the venn you are (distance to the shared centre),
    // NOT per-circle overlap — so the centre is unambiguously the most intense point.
    private fun computeMood(x: Float, y: Float): Float {
        if (startZoneLabel != null && y < dividerY) return 1f          // the one happy place
        val d = kotlin.math.hypot(x - cenX, y - cenY)
        val nd = (d / vennR).coerceIn(0f, 1f)                          // 0 = centre, 1 = edge
        return if (positiveInside) 0.5f + 0.5f * (1f - nd)             // neutral edge -> happy centre
        else 0.5f * nd                                                 // neutral edge -> sad centre
    }

    private fun recompute() {
        moved = true
        mood = computeMood(fx, fy)
        onMoodChange?.invoke(mood)
        invalidate()
    }

    fun nearestLabel(): String? {
        var best: String? = null; var bestD = Float.MAX_VALUE
        for (c in circles) {
            val d = kotlin.math.hypot(fx - c.cx, fy - c.cy)
            if (d < c.r && d < bestD) { bestD = d; best = c.label }
        }
        return best
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                fx = event.x.coerceIn(0f, width.toFloat())
                fy = event.y.coerceIn(0f, height.toFloat())
                recompute()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (circles.isEmpty()) return
        val dp = resources.displayMetrics.density
        // happy start zone
        if (startZoneLabel != null) {
            canvas.drawRoundRect(0f, 0f, width.toFloat(), dividerY - 6f * dp, 16f * dp, 16f * dp, zoneFill)
            val lines = startZoneLabel.split("\n")
            var ty = dividerY * 0.5f - (lines.size - 1) * 9f * dp
            for (ln in lines) { canvas.drawText(ln, 14f * dp, ty, zoneText); ty += 18f * dp }
            canvas.drawLine(0f, dividerY, width.toFloat(), dividerY, dividerPaint)
        }
        // venn lobes
        for (c in circles) {
            circleFill.color = (circleColor and 0x00FFFFFF) or (46 shl 24)
            canvas.drawCircle(c.cx, c.cy, c.r, circleFill)
            canvas.drawCircle(c.cx, c.cy, c.r, circleStroke)
        }
        // labels pushed to the outer edge of each lobe
        for (c in circles) {
            val dx = c.cx - cenX; val dy = c.cy - cenY
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
            val lx = c.cx + dx / len * c.r * 0.5f
            val ly = c.cy + dy / len * c.r * 0.5f
            canvas.drawText(c.label, lx, ly + labelPaint.textSize / 3f, labelPaint)
        }
        // face
        val fr = kotlin.math.min(width, height) * 0.075f
        canvas.drawCircle(fx, fy, fr, faceFill)
        val ex = fr * 0.42f; val ey = fr * 0.28f; val er = fr * 0.12f
        canvas.drawCircle(fx - ex, fy - ey, er, faceDot)
        canvas.drawCircle(fx + ex, fy - ey, er, faceDot)
        val curve = (mood - 0.5f) * 2f
        val mw = fr * 0.5f; val my = fy + fr * 0.30f
        val path = Path().apply { moveTo(fx - mw, my); quadTo(fx, my + curve * fr * 0.6f, fx + mw, my) }
        canvas.drawPath(path, faceLine)
    }
}

// =====================================================================================
// PeakCurveView  (urge over time: spikes, then falls — and you're already past the peak)
// =====================================================================================
class PeakCurveView(
    context: Context,
    private val showMarker: Boolean = true,
    private val labelTop: String? = "you're strong \u2014",
    private val labelBot: String? = "you can get here",
) : View(context) {
    private var anim = 0f
    private val accent = 0xFF2E9E8F.toInt()
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = accent; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5F6368.toInt(); textAlign = Paint.Align.RIGHT }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E9E8F.toInt(); textAlign = Paint.Align.CENTER }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A9095.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // urge vs time: quick rise to a peak, slower decay back toward baseline
    private fun u(x: Float): Float {
        val xc = 0.22f; val amp = 0.80f; val base = 0.12f
        val sigma = if (x < xc) 0.10f else 0.26f
        val d = (x - xc).toDouble()
        return base + amp * Math.exp(-(d * d) / (2.0 * sigma * sigma)).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val dp = resources.displayMetrics.density
        val xL = 10f * dp; val xR = w - 10f * dp; val yB = h - 26f * dp; val yT = 14f * dp
        fun px(x: Float) = xL + (xR - xL) * x
        fun py(uu: Float) = yB - (yB - yT) * uu
        curve.strokeWidth = 3f * dp; axis.strokeWidth = 1f * dp; dotRing.strokeWidth = 3f * dp; arrow.strokeWidth = 1.6f * dp

        canvas.drawLine(xL, yB, xR, yB, axis)

        val path = Path(); val fillPath = Path()
        val n = 72
        for (i in 0..n) {
            val x = i / n.toFloat(); val xx = px(x); val yy = py(u(x))
            if (i == 0) { path.moveTo(xx, yy); fillPath.moveTo(xx, yB); fillPath.lineTo(xx, yy) }
            else { path.lineTo(xx, yy); fillPath.lineTo(xx, yy) }
        }
        fillPath.lineTo(px(1f), yB); fillPath.close()
        fill.shader = android.graphics.LinearGradient(
            0f, yT, 0f, yB,
            (accent and 0x00FFFFFF) or (60 shl 24), (accent and 0x00FFFFFF) or (8 shl 24),
            Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fill)
        canvas.drawPath(path, curve)

        if (showMarker) {
            val mx = 0.42f * anim
            val MX = px(mx); val MY = py(u(mx))
            canvas.drawCircle(MX, MY, 7f * dp, dotFill)
            canvas.drawCircle(MX, MY, 7f * dp, dotRing)
        }

        val la = ((anim - 0.45f) / 0.55f).coerceIn(0f, 1f)
        if (la > 0f && showMarker) {
            tag.textSize = 11f * dp; tag.alpha = (la * 200).toInt()
            canvas.drawText("past the peak", px(0.42f), yB + 18f * dp, tag)
        }
        if (la > 0f && labelTop != null) {
            // label sits up high, clear of the curve, with an arrow down to the faded tail
            label.textSize = 12.5f * dp; label.alpha = (la * 255).toInt()
            val tx = xR
            val ty = yT + 13f * dp
            canvas.drawText(labelTop, tx, ty, label)
            if (labelBot != null) canvas.drawText(labelBot, tx, ty + 16f * dp, label)
            // arrow from just below the label down to the curve's end
            arrow.alpha = (la * 200).toInt()
            val ax = px(0.9f); val aTopY = ty + 26f * dp; val aEndY = py(u(0.9f)) - 8f * dp
            if (aEndY > aTopY) {
                canvas.drawLine(ax, aTopY, ax, aEndY, arrow)
                canvas.drawLine(ax, aEndY, ax - 4f * dp, aEndY - 6f * dp, arrow)
                canvas.drawLine(ax, aEndY, ax + 4f * dp, aEndY - 6f * dp, arrow)
            }
        }
    }
}

// =====================================================================================
// PeakTapView  (same urge curve, but the user taps where they think they are)
// =====================================================================================
class PeakTapView(
    context: Context,
    private val threshold: Float,
    private val onPick: (Float, Boolean) -> Unit,
) : View(context) {
    private val accent = 0xFF2E9E8F.toInt()
    private val gold = 0xFFD4A017.toInt()
    private val dull = 0xFFB9C4C2.toInt()
    private var tappedX: Float? = null
    private var correct = false
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = accent; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA0A6.toInt(); textAlign = Paint.Align.CENTER }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }

    private fun u(x: Float): Float {
        val xc = 0.22f; val amp = 0.80f; val base = 0.12f
        val sigma = if (x < xc) 0.10f else 0.26f
        val d = (x - xc).toDouble()
        return base + amp * Math.exp(-(d * d) / (2.0 * sigma * sigma)).toFloat()
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN || event.action == android.view.MotionEvent.ACTION_MOVE) {
            val dp = resources.displayMetrics.density
            val xL = 10f * dp; val xR = width - 10f * dp
            val x = ((event.x - xL) / (xR - xL)).coerceIn(0f, 1f)
            tappedX = x
            if (x > threshold) correct = true       // once they get it right, it stays gold
            invalidate(); onPick(x, x > threshold)
            return true
        }
        return super.onTouchEvent(event)
    }

    // draws a curve segment over [x0,x1] in the given colour
    private fun segment(canvas: Canvas, x0: Float, x1: Float, color: Int,
                        px: (Float) -> Float, py: (Float) -> Float) {
        curve.color = color
        val p = Path(); val n = 48
        for (i in 0..n) {
            val x = x0 + (x1 - x0) * i / n; val xx = px(x); val yy = py(u(x))
            if (i == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy)
        }
        canvas.drawPath(p, curve)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val dp = resources.displayMetrics.density
        val xL = 10f * dp; val xR = w - 10f * dp; val yB = h - 26f * dp; val yT = 14f * dp
        fun px(x: Float) = xL + (xR - xL) * x
        fun py(uu: Float) = yB - (yB - yT) * uu
        curve.strokeWidth = 3f * dp; axis.strokeWidth = 1f * dp; dotRing.strokeWidth = 3f * dp
        canvas.drawLine(xL, yB, xR, yB, axis)

        // soft fill under the whole curve
        val fillPath = Path(); val n = 72
        for (i in 0..n) {
            val x = i / n.toFloat(); val xx = px(x); val yy = py(u(x))
            if (i == 0) { fillPath.moveTo(xx, yB); fillPath.lineTo(xx, yy) } else fillPath.lineTo(xx, yy)
        }
        fillPath.lineTo(px(1f), yB); fillPath.close()
        val fillColor = if (correct) gold else accent
        val a0 = if (correct) 70 else 60
        fill.shader = android.graphics.LinearGradient(
            0f, yT, 0f, yB,
            (fillColor and 0x00FFFFFF) or (a0 shl 24),
            (fillColor and 0x00FFFFFF) or (8 shl 24),
            Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fill)

        if (correct) {
            // past-the-peak tail turns gold; the rising left half is dulled back
            segment(canvas, 0f, threshold, dull, ::px, ::py)
            segment(canvas, threshold, 1f, gold, ::px, ::py)
        } else {
            segment(canvas, 0f, 1f, accent, ::px, ::py)
        }

        val tx = tappedX
        if (tx == null) {
            hint.textSize = 13f * dp
            canvas.drawText("tap where you think you are", w / 2f, py(u(0.5f)) - 8f * dp, hint)
        } else {
            val mx = px(tx); val my = py(u(tx))
            dotFill.color = if (correct) gold else accent
            canvas.drawCircle(mx, my, 8f * dp, dotFill)
            canvas.drawCircle(mx, my, 8f * dp, dotRing)
        }
    }
}

// =====================================================================================
// GlowButton  (a filled button with a soft light tracing its edge, to invite a tap)
// =====================================================================================
class GlowButton(context: Context, private val label: String, onClick: () -> Unit) : View(context) {
    private var phase = 0f
    private var anim: android.animation.ValueAnimator? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC8932B.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val dp = resources.displayMetrics.density
        val r = 14f * dp; val inset = 2f * dp
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, bg)
        txt.textSize = 16f * dp
        canvas.drawText(label, w / 2f, h / 2f + txt.textSize / 3f, txt)
        // a warm bright band that travels around the rounded-rect edge
        edge.strokeWidth = 4f * dp
        val sweep = android.graphics.SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(0x00FFF6D8, 0x00FFF6D8, 0xFFFFF6D8.toInt(), 0x00FFF6D8, 0x00FFF6D8),
            floatArrayOf(0f, 0.38f, 0.5f, 0.62f, 1f))
        sweep.setLocalMatrix(android.graphics.Matrix().apply { postRotate(phase * 360f, w / 2f, h / 2f) })
        edge.shader = sweep
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, edge)
    }
}

// =====================================================================================
// RecoveryBrainView  (your progress so far, then the fork: a one-off vs keeping going)
// =====================================================================================
class RecoveryBrainView(context: Context) : View(context) {
    private var anim = 0f
    private val amber = 0xFFC9772B.toInt()
    private val green = 0xFF2E7D32.toInt()
    private val past = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF9AA0A6.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val up = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = amber; strokeCap = Paint.Cap.ROUND
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val down = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = green; strokeCap = Paint.Cap.ROUND
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18000000 }
    private val lab = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val axisLab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0B5BA.toInt() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400; startDelay = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val dp = resources.displayMetrics.density
        val xL = 12f * dp; val xR = w - 12f * dp; val yT = 16f * dp; val yB = h - 26f * dp
        fun px(x: Float) = xL + (xR - xL) * x
        fun py(f: Float) = yT + (yB - yT) * f      // f: 0 = high pull (top), 1 = free (bottom)
        past.strokeWidth = 3.5f * dp; up.strokeWidth = 3f * dp; down.strokeWidth = 3f * dp; axis.strokeWidth = 1f * dp

        // faint frame + axis hints
        canvas.drawLine(xL, yB, xR, yB, axis)
        axisLab.textSize = 10.5f * dp
        axisLab.textAlign = Paint.Align.LEFT; canvas.drawText("more pull", xL, yT + 4f * dp, axisLab)
        canvas.drawText("free", xL, yB - 4f * dp, axisLab)

        // progress so far: coming down from a high point to "now"
        val nowX = 0.40f; val nowF = 0.56f
        val pPath = Path().apply {
            moveTo(px(0.05f), py(0.20f))
            cubicTo(px(0.18f), py(0.22f), px(0.28f), py(0.48f), px(nowX), py(nowF))
        }
        canvas.drawPath(pPath, past)

        // the fork, drawn growing out from "now"
        val t = anim
        val upPath = Path().apply {
            moveTo(px(nowX), py(nowF))
            val ex = nowX + (0.95f - nowX) * t; val ef = nowF + (0.30f - nowF) * t
            cubicTo(px(nowX + 0.18f * t), py(nowF - 0.04f * t), px(nowX + 0.38f * t), py(nowF - 0.18f * t), px(ex), py(ef))
        }
        canvas.drawPath(upPath, up)
        val downPath = Path().apply {
            moveTo(px(nowX), py(nowF))
            val ex = nowX + (0.95f - nowX) * t; val ef = nowF + (0.88f - nowF) * t
            cubicTo(px(nowX + 0.20f * t), py(nowF + 0.10f * t), px(nowX + 0.40f * t), py(nowF + 0.22f * t), px(ex), py(ef))
        }
        canvas.drawPath(downPath, down)

        // branch labels (fade in)
        val la = ((anim - 0.5f) / 0.5f).coerceIn(0f, 1f)
        lab.textSize = 12.5f * dp; lab.textAlign = Paint.Align.RIGHT; lab.alpha = (la * 255).toInt()
        lab.color = amber; canvas.drawText("one-off \u2192 back up", px(0.95f), py(0.30f) - 6f * dp, lab)
        lab.color = green; canvas.drawText("keep going \u2192 free", px(0.95f), py(0.88f) + 16f * dp, lab)

        // a brain at "now"
        emoji.textSize = 26f * dp
        canvas.drawText("\uD83E\uDDE0", px(nowX), py(nowF) + 9f * dp, emoji)
    }
}

// =====================================================================================
// UI
// =====================================================================================


// --------------------------------------------------------------
// MonitorAdapter
// --------------------------------------------------------------


/** Shows the monitored entries in the scrollable list. Tapping a row blocks it. */
class MonitorAdapter(
    private val onEntryClick: (MonitorEntry) -> Unit,
    private val onEntryLongClick: (MonitorEntry) -> Unit,
) : ListAdapter<MonitorEntry, MonitorAdapter.ViewHolder>(DIFF) {

    private val timeFormat = SimpleDateFormat("MMM d  HH:mm:ss", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val primary: TextView = view.findViewById(R.id.primary)
        val secondary: TextView = view.findViewById(R.id.secondary)
        val meta: TextView = view.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val time = timeFormat.format(Date(entry.timestamp))

        holder.itemView.setOnClickListener { onEntryClick(entry) }
        holder.itemView.setOnLongClickListener { onEntryLongClick(entry); true }

        holder.primary.text = entry.title?.takeIf { it.isNotBlank() }
            ?: entry.url ?: entry.domain ?: entry.packageName ?: "Page"
        holder.secondary.text = entry.url ?: entry.domain ?: entry.packageName.orEmpty()
        val snippet = entry.text?.replace('\n', ' ')?.trim()?.take(40).orEmpty()
        holder.meta.text = snippet.ifBlank { "(none)" } + "   ·   $time"
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MonitorEntry>() {
            override fun areItemsTheSame(old: MonitorEntry, new: MonitorEntry) = old.id == new.id
            override fun areContentsTheSame(old: MonitorEntry, new: MonitorEntry) = old == new
        }
    }
}


// =====================================================================================
// Uninstall prevention
// =====================================================================================
class UninstallGuardAdminReceiver : DeviceAdminReceiver() {
    // You can't *stop* deactivation, but you get the last word on the system screen.
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turn off the lock inside the app first. Remove protection anyway?"
}

object UninstallGuard {
    private const val PREFS = "uninstall_guard"
    private const val KEY = "enabled"

    fun admin(ctx: Context) = ComponentName(ctx, UninstallGuardAdminReceiver::class.java)

    private fun dpm(ctx: Context) =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isAdminActive(ctx: Context) = dpm(ctx).isAdminActive(admin(ctx))

    /** The user-facing toggle (persisted). This is what the accessibility guard checks. */
    fun isEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (!on) deactivateAdmin(ctx)   // turning the toggle OFF lifts the block immediately
    }

    /** System "activate device admin?" prompt. */
    fun activationIntent(ctx: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets the app keep you from uninstalling it while the lock is on.")
        }

    fun deactivateAdmin(ctx: Context) {
        if (dpm(ctx).isAdminActive(admin(ctx))) dpm(ctx).removeActiveAdmin(admin(ctx))
    }
}

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
        if (best >= 30) ms.add("A clean month \u2014 every day counted")
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
// WastedDonutView  (share of your waking life going to the scroll — updates live)
// =====================================================================================
class WastedDonutView(context: Context) : View(context) {
    private var frac = 0f                 // 0..1 share of waking hours
    private var anim = 0f
    private val ringBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFE6EAED.toInt(); strokeCap = Paint.Cap.ROUND }
    private val ringFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFE4673B.toInt(); strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F2933.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7B848C.toInt(); textAlign = Paint.Align.CENTER }

    fun setFraction(f: Float) {
        val target = f.coerceIn(0f, 1f)
        android.animation.ValueAnimator.ofFloat(anim, target).apply {
            duration = 450; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim = it.animatedValue as Float; invalidate() }
            start()
        }
        frac = target
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val dp = resources.displayMetrics.density
        val sw = 16f * dp
        ringBg.strokeWidth = sw; ringFg.strokeWidth = sw
        val r = (kotlin.math.min(width, height) / 2f) - sw
        val cx = width / 2f; val cy = height / 2f
        val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, 0f, 360f, false, ringBg)
        canvas.drawArc(rect, -90f, 360f * anim, false, ringFg)
        big.textSize = 30f * dp
        canvas.drawText("${Math.round(anim * 100)}%", cx, cy + 4f * dp, big)
        small.textSize = 12.5f * dp
        canvas.drawText("of your waking life", cx, cy + 24f * dp, small)
    }
}

// =====================================================================================
// TimeGridView  (your next year as 365 squares; the ones lost to the scroll filled in)
// =====================================================================================
class TimeGridView(context: Context) : View(context) {
    private var filled = 0
    private val total = 365
    private val cols = 21
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE4673B.toInt() }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6EAED.toInt() }

    fun setFilledDays(d: Int) { filled = d.coerceIn(0, total); invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val dp = resources.displayMetrics.density
        val gap = 3f * dp
        val cell = (width - gap * (cols - 1)) / cols
        val rad = 2f * dp
        for (i in 0 until total) {
            val rIdx = i / cols; val cIdx = i % cols
            val x = cIdx * (cell + gap); val y = rIdx * (cell + gap)
            canvas.drawRoundRect(x, y, x + cell, y + cell, rad, rad, if (i < filled) on else off)
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val dp = resources.displayMetrics.density
        val gap = 3f * dp
        val rows = Math.ceil(total / cols.toDouble()).toInt()
        val cell = if (w > 0) (w - gap * (cols - 1)) / cols else 10f * dp
        val h = (rows * cell + (rows - 1) * gap).toInt()
        setMeasuredDimension(w, h)
    }
}

// =====================================================================================
// TrendView  (a small line chart for "urges ridden out per week" — shows direction)
// =====================================================================================
class TrendView(context: Context, private val values: FloatArray) : View(context) {
    private val accent = 0xFF2E7D32.toInt()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = accent; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18000000 }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0 || values.isEmpty()) return
        val dp = resources.displayMetrics.density
        val xL = 10f * dp; val xR = width - 10f * dp; val yT = 12f * dp; val yB = height - 12f * dp
        val n = values.size
        val mx = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
        fun px(i: Int) = if (n == 1) (xL + xR) / 2f else xL + (xR - xL) * i / (n - 1)
        fun py(v: Float) = yB - (yB - yT) * (v / mx)
        line.strokeWidth = 3f * dp; axis.strokeWidth = 1f * dp; ring.strokeWidth = 3f * dp
        canvas.drawLine(xL, yB, xR, yB, axis)

        val path = Path(); val fill = Path()
        for (i in 0 until n) {
            val xx = px(i); val yy = py(values[i])
            if (i == 0) { path.moveTo(xx, yy); fill.moveTo(xx, yB); fill.lineTo(xx, yy) }
            else { path.lineTo(xx, yy); fill.lineTo(xx, yy) }
        }
        fill.lineTo(px(n - 1), yB); fill.close()
        fillP.shader = android.graphics.LinearGradient(
            0f, yT, 0f, yB, (accent and 0x00FFFFFF) or (44 shl 24), (accent and 0x00FFFFFF) or (6 shl 24),
            Shader.TileMode.CLAMP)
        canvas.drawPath(fill, fillP)
        canvas.drawPath(path, line)
        for (i in 0 until n) canvas.drawCircle(px(i), py(values[i]), 3.5f * dp, dot)
        val li = n - 1
        canvas.drawCircle(px(li), py(values[li]), 6f * dp, dot)
        canvas.drawCircle(px(li), py(values[li]), 6f * dp, ring)
    }
}
// the existing BlockRules engine instead; only URL *greylist* is stored here as a host.
object AppRules {
    const val BLOCK = "B"
    const val GREY = "G"
    private const val PREFS = "app_rules"
    private const val KEY_APPS = "apps"     // entries: "B|pkg" / "G|pkg"
    private const val KEY_HOSTS = "hosts"   // entries: "G|host"

    fun setApp(context: Context, pkg: String, tier: String) {
        val key = pkg.trim().lowercase(); if (key.isEmpty()) return
        val set = readApps(context).filterNot { it.substringAfter('|') == key }.toMutableSet()
        set.add("$tier|$key"); writeApps(context, set)
    }

    fun setHost(context: Context, host: String, tier: String) {
        val key = host.trim().lowercase().removePrefix("www."); if (key.isEmpty()) return
        val set = readHosts(context).filterNot { it.substringAfter('|') == key }.toMutableSet()
        set.add("$tier|$key"); writeHosts(context, set)
    }

    fun appTier(context: Context, pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        val key = pkg.lowercase()
        val user = readApps(context).firstOrNull { it.substringAfter('|') == key }?.substringBefore('|')
        if (user != null) return user
        // Built-in greylist (TikTok, Instagram, etc.): time-limited by default.
        if (Whitelist.isGreylistApp(key)) return GREY
        return null
    }

    fun hostTier(context: Context, host: String?): String? {
        if (host.isNullOrBlank()) return null
        val h = host.lowercase()
        for (e in readHosts(context)) {
            val stored = e.substringAfter('|')
            if (h == stored || h.endsWith(".$stored")) return e.substringBefore('|')
        }
        return null
    }

    fun remove(context: Context, isApp: Boolean, target: String) {
        val key = target.lowercase()
        if (isApp) writeApps(context, readApps(context).filterNot { it.substringAfter('|') == key }.toMutableSet())
        else writeHosts(context, readHosts(context).filterNot { it.substringAfter('|') == key }.toMutableSet())
    }

    fun apps(context: Context): List<Pair<String, String>> =     // (tier, pkg)
        readApps(context).map { it.substringBefore('|') to it.substringAfter('|') }

    fun hosts(context: Context): List<Pair<String, String>> =    // (tier, host) — always GREY
        readHosts(context).map { it.substringBefore('|') to it.substringAfter('|') }

    private fun readApps(c: Context) = prefs(c).getStringSet(KEY_APPS, emptySet())!!.toSet()
    private fun readHosts(c: Context) = prefs(c).getStringSet(KEY_HOSTS, emptySet())!!.toSet()
    private fun writeApps(c: Context, s: Set<String>) = prefs(c).edit().putStringSet(KEY_APPS, s).apply()
    private fun writeHosts(c: Context, s: Set<String>) = prefs(c).edit().putStringSet(KEY_HOSTS, s).apply()
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// =====================================================================================
// GreyUsage  (per-target foreground time, capped per rolling hour)
// =====================================================================================
object GreyUsage {
    const val LIMIT_MIN = 2
    private const val LIMIT_MS = LIMIT_MIN * 60 * 1000L
    private const val WINDOW_MS = 60L * 60 * 1000
    private const val PREFS = "grey_usage"

    fun addUsage(context: Context, target: String, deltaMs: Long) {
        if (deltaMs <= 0) return
        val key = target.lowercase()
        val p = prefs(context); val now = System.currentTimeMillis()
        var start = p.getLong("start:$key", 0L)
        var used = p.getLong("used:$key", 0L)
        if (now - start >= WINDOW_MS) { start = now; used = 0L }   // hour rolled over
        used += deltaMs
        p.edit().putLong("start:$key", start).putLong("used:$key", used).apply()
    }

    fun isOverLimit(context: Context, target: String): Boolean {
        val key = target.lowercase()
        val p = prefs(context)
        val start = p.getLong("start:$key", 0L)
        if (System.currentTimeMillis() - start >= WINDOW_MS) return false
        return p.getLong("used:$key", 0L) >= LIMIT_MS
    }

    fun remainingMs(context: Context, target: String): Long {
        val key = target.lowercase()
        val p = prefs(context)
        val start = p.getLong("start:$key", 0L)
        if (System.currentTimeMillis() - start >= WINDOW_MS) return LIMIT_MS
        return (LIMIT_MS - p.getLong("used:$key", 0L)).coerceAtLeast(0L)
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}



// =====================================================================================
// LoosenWindow  (the temporary relaxed window after a supervised unlock)
// =====================================================================================
object LoosenWindow {
    private const val PREFS = "loosen_window"
    private const val KEY_UNTIL = "until"

    fun start(context: Context, durationMs: Long) {
        prefs(context).edit().putLong(KEY_UNTIL, System.currentTimeMillis() + durationMs).apply()
    }
    fun isActive(context: Context) = remaining(context) > 0
    fun remaining(context: Context) =
        (prefs(context).getLong(KEY_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    fun end(context: Context) = prefs(context).edit().remove(KEY_UNTIL).apply()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// =====================================================================================
// LoosenWait  (the pre-unlock wait; doubles as a whitelist lock so they can't bail)
// =====================================================================================
// Persists in SharedPreferences, so leaving the app and coming back resumes the same
// countdown instead of resetting. Essentials stay reachable.
object LoosenWait {
    private const val PREFS = "loosen_wait"
    private const val KEY_UNTIL = "until"
    private val ALLOW = listOf(
        "launcher", "trebuchet", "dialer", "incallui", "telecom", "phone", "contacts",
        "messaging", "mms", "whatsapp", "camera", "maps", "waze", "deskclock", "clock", "alarm",
    )
    fun start(context: Context, durationMs: Long) {
        prefs(context).edit().putLong(KEY_UNTIL, System.currentTimeMillis() + durationMs).apply()
    }
    fun add(context: Context, ms: Long) {
        val base = maxOf(prefs(context).getLong(KEY_UNTIL, 0L), System.currentTimeMillis())
        prefs(context).edit().putLong(KEY_UNTIL, base + ms).apply()
    }
    fun isActive(context: Context) = remaining(context) > 0
    fun remaining(context: Context) =
        (prefs(context).getLong(KEY_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    fun end(context: Context) = prefs(context).edit().remove(KEY_UNTIL).apply()
    fun isAllowed(pkg: String?): Boolean {
        if (pkg == null) return true
        val p = pkg.lowercase()
        return ALLOW.any { p.contains(it) }
    }
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// =====================================================================================
// LoosenLimit  (one unlock per day, five for life)
// =====================================================================================
object LoosenLimit {
    const val LIFETIME_MAX = 3
    private const val PREFS = "loosen_limit"
    private const val KEY_TOTAL = "total"
    private const val KEY_DAY = "last_day"

    fun lifetimeUsed(context: Context) = prefs(context).getInt(KEY_TOTAL, 0)
    fun remaining(context: Context) = (LIFETIME_MAX - lifetimeUsed(context)).coerceAtLeast(0)
    fun usedToday(context: Context) = prefs(context).getInt(KEY_DAY, 0) == today()
    fun canUse(context: Context) = remaining(context) > 0 && !usedToday(context)

    /** Consumed only when a window actually opens, so backing out is rewarded, not punished. */
    fun consume(context: Context) {
        prefs(context).edit()
            .putInt(KEY_TOTAL, lifetimeUsed(context) + 1)
            .putInt(KEY_DAY, today())
            .apply()
    }

    private fun today(): Int = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
// CustomOptions  (user-typed options per category — feeling / location / activity / screen)
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
// Lockdown  (temporary 30-min "allow-list only" mode)
// =====================================================================================
/**
 * While active, the accessibility service covers every app EXCEPT the essentials below.
 * Browsers, social, games — all off the table — so an urge has nowhere to go. Calls,
 * texts, alarms, contacts and the home screen still work, so the phone isn't bricked.
 * (systemui, keyboards and this app itself are already let through upstream.)
 *
 * Can't be cancelled early on purpose — that's the commitment. It just expires after
 * 30 minutes. Same best-effort durability as the app's other locks.
 *
 * Note: Settings is NOT on the allow-list, so the service can't be switched off mid-
 * lockdown to escape it. If that feels too strict, add "settings" to ALLOW_SUBSTRINGS.
 */
object Lockdown {
    private const val PREFS = "lockdown"
    private const val KEY_UNTIL = "until"
    const val DURATION_MS = 30L * 60 * 1000

    private val ALLOW_SUBSTRINGS = AppConfig.LOCKDOWN_ALLOWED_SUBSTRINGS

    fun start(context: Context) {
        prefs(context).edit()
            .putLong(KEY_UNTIL, System.currentTimeMillis() + DURATION_MS).apply()
    }

    fun isActive(context: Context): Boolean = remaining(context) > 0

    fun remaining(context: Context): Long =
        (prefs(context).getLong(KEY_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun end(context: Context) = prefs(context).edit().remove(KEY_UNTIL).apply()  // testing/dev only

    /** Allowed to stay open during a lockdown? */
    fun isAllowed(pkg: String?): Boolean {
        if (pkg == null) return true
        val p = pkg.lowercase()
        return ALLOW_SUBSTRINGS.any { p.contains(it) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
