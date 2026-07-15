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


// NOTE: This file now holds only MainActivity. The rest of the code has been split
// into logical modules in the same package (same source folder):
//   AppConfig.kt              – all editable config lists & thresholds
//   AccessibilityService.kt   – PageMonitorAccessibilityService (the monitor)
//   Blocking.kt               – block rules, whitelist, escalation, app/greylist, lockdown, unlock timers
//   Overlay.kt                – block + breathing overlays
//   Database.kt               – Room entities/DAO/DB + the log RecyclerView adapter
//   UserState.kt              – prefs-backed state: Mode, logs, Progress, Usage, Protocol, option lists
//   Views.kt                  – custom Views (orb, faces, charts, thumb-back, etc.)
//   UninstallGuard.kt         – device-admin uninstall lock
//   Sensors.kt                – SensorMonitor (accelerometer + light)
//   RoomBeacons.kt            – BLE room beacons (KKM K11): scanner, config, presence
// All files share `package com.example.webtrafficmonitor`, so they compile together
// with no imports between them. NOTE: this supersedes the old merge_kt.py workflow -
// do NOT re-merge these back into one file, or you'll get duplicate package/import lines.

// =====================================================================================
// APP
// =====================================================================================

// Request code for the Bluetooth-scanning permissions (room-beacon debug page).
private const val REQ_BEACON_PERMS = 71

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


    /** Long-press a row to read the whole entry - including the full NODE DUMP. */
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
            append("\nDOMAIN STRIKES (today - 3 bans the domain for 1h)\n")
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
        root.addView(titleText("Manage blocks"))
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(container)
        })
        setContentWithThumb(root) { setupMainScreen() }

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
    root.addView(titleText("Statistics"))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    // NOTE: "Dopamine baseline" deliberately does NOT live here. It is a whole-phone,
    // whole-life measure, not an adult-content one, so it belongs on the Productivity page -
    // see showProductivity(). Don't move it back in here.
    list.addView(pickCard("Progress & reward") { showProgress() })
    list.addView(pickCard("Where & how it happens") { showContextStats() })
    list.addView(pickCard("Temptation patterns") { showTemptationStats() })
    list.addView(pickCard("Relapse patterns") { showRelapseStats() })
    list.addView(pickCard("Unlock attempts") { showLoosenStats() })
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
    })
    setContentWithThumb(root) { showReportScreen() }
}

/** A quiet teal text link, used under the cards on the Productivity page. */
private fun smallLink(label: String, dp: Float, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFF2E9E8F.toInt())
        isClickable = true; isFocusable = true
        setPadding(0, (12 * dp).toInt(), 0, (2 * dp).toInt())
        setOnClickListener { onClick() }
    }

// ── About you: optional numbers, used ONLY to make the cost concrete ────────
private var aboutYouBack: () -> Unit = { setupHomeScreen() }
private var dopamineBack: () -> Unit = { showProductivity() }
private var lifeInputsBack: () -> Unit = { showProductivity() }

private fun showAboutYou() {
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("About you"))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { aboutYouBack() }

    c.addView(TextView(this).apply {
        text = "Optional. We use this only to estimate what the lost time is actually costing " +
            "you — nothing here leaves your phone, and nothing here is shared.\n\n" +
            "Leave it blank and we'll assume £${AboutYou.DEFAULT_HOURLY_GBP * AboutYou.HOURS_PER_YEAR / 1000}k a year " +
            "(£${AboutYou.DEFAULT_HOURLY_GBP}/hr). Everything on the home page updates the moment you type."
        textSize = 14f; setTextColor(0xFF4A4F54.toInt()); setLineSpacing(0f, 1.2f)
        setPadding(0, 0, 0, (16 * dp).toInt())
    })

    fun moneyRow(label: String, sub: String, get: () -> Int, set: (Int) -> Unit) {
        c.addView(TextView(this).apply {
            text = label; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1F2933.toInt()); setPadding(0, (12 * dp).toInt(), 0, (2 * dp).toInt())
        })
        c.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(0xFF7B848C.toInt())
            setPadding(0, 0, 0, (6 * dp).toInt())
        })
        c.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(get().takeIf { it > 0 }?.toString().orEmpty())
            hint = "£ per year"
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    set(s?.toString()?.toIntOrNull() ?: 0)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
    }

    moneyRow("What you earn a year",
        "Your day job, after tax if you like. Only know your hourly rate? " +
            "About ${AboutYou.HOURS_PER_YEAR} working hours make a year - £15/hr ≈ £29,000.",
        { AboutYou.annualWage(this) }, { AboutYou.setAnnualWage(this, it) })
    moneyRow("Side income a year (optional)",
        "A business you're building, freelance work. Don't know? LEAVE IT BLANK - " +
            "a guess here just skews your numbers. If it's higher than your wage, we use it.",
        { AboutYou.annualSide(this) }, { AboutYou.setAnnualSide(this, it) })

    c.addView(TextView(this).apply {
        text = "\nWhy we ask: \"3 hours a day\" is abstract and easy to shrug off. " +
            "\"£13,000 a year\" is not."
        textSize = 13f; setTextColor(0xFF7B848C.toInt()); setLineSpacing(0f, 1.15f)
        setPadding(0, (14 * dp).toInt(), 0, (20 * dp).toInt())
    })
}

// ═══════════════════════════════════════════════════════════════════════════
//  DOPAMINE BASELINE
//  The number, the trend, and an honest breakdown of what is driving it.
//  The algorithm is NOT here - it is all in Dopamine.kt (DopamineTuning).
// ═══════════════════════════════════════════════════════════════════════════
private fun showDopamine() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val today = DopamineLog.today(this)
    val r = DopamineScore.of(today)
    val root = vbox(pad)
    root.addView(titleText("Dopamine baseline"))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { dopamineBack() }

    if (!r.hasData) {
        c.addView(TextView(this).apply {
            text = "Nothing measured yet today.\n\nThis builds itself as you use the phone - what you " +
                "spend time on, how often you unlock, how fast you scroll, when and where you do it. " +
                "Come back later today."
            textSize = 15f; setTextColor(0xFF6B7075.toInt()); setLineSpacing(0f, 1.2f)
        })
        return
    }

    // ── the number, and the scale it sits on ──
    val scaleRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (200 * dp).toInt())
    }
    scaleRow.addView(DopamineScaleView(this, r.score), LinearLayout.LayoutParams(
        (54 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
    val numbers = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((16 * dp).toInt(), 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
    }
    numbers.addView(TextView(this).apply {
        text = "${r.score}"; textSize = 52f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(r.colour); includeFontPadding = false
    })
    numbers.addView(TextView(this).apply {
        text = r.band; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(r.colour)
    })
    numbers.addView(TextView(this).apply {
        text = "out of 100 · higher is more fried\ntoday so far"
        textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (6 * dp).toInt(), 0, 0)
    })
    scaleRow.addView(numbers)
    c.addView(scaleRow)

    // ── the trend ──
    val history = DopamineLog.history(this, 14)
    val scores = history.map { DopamineScore.of(it).score.toFloat() }.toFloatArray()
    if (scores.count { it > 0f } >= 2) {
        c.addView(statHeader("LAST 14 DAYS", dp))
        c.addView(TrendView(this, scores), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (120 * dp).toInt()))
        val avg = scores.filter { it > 0f }.average().toInt()
        c.addView(TextView(this).apply {
            text = "Average $avg over the days we have data for."
            textSize = 13f; setTextColor(0xFF7B848C.toInt())
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
    }

    // ── what's driving it ──
    if (r.contributors.isNotEmpty()) {
        c.addView(statHeader("WHAT'S DRIVING IT TODAY", dp))
        r.contributors.forEach { line ->
            c.addView(scoreLineRow(line.label, line.points, line.detail, 0xFFB3261E.toInt(), dp))
        }
    }
    if (r.credits.isNotEmpty()) {
        c.addView(statHeader("PULLING IT DOWN", dp))
        r.credits.forEach { line ->
            c.addView(scoreLineRow(line.label, line.points, line.detail, 0xFF2E7D32.toInt(), dp))
        }
    }

    c.addView(statHeader("MORE", dp))
    c.addView(captionedButton("How is this calculated?", "every rule, and what it's worth",
        0xFF34464E.toInt()) { showDopamineMaths() })
    c.addView(captionedButton("How do I bring this down?", "what actually moves the number",
        0xFF3E535C.toInt()) { showDopamineGuidance() })
    c.addView(captionedButton("Your habits estimate", "self-reported · does NOT affect the score above",
        0xFF52796F.toInt()) { lifeInputsBack = { showDopamine() }; showLifeInputs() })
    c.addView(TextView(this).apply {
        text = "A rough model, not a medical measurement. It exists to make a pattern visible, " +
            "nothing more."
        textSize = 12f; setTextColor(0xFF9AA0A6.toInt())
        setPadding(0, (14 * dp).toInt(), 0, (20 * dp).toInt())
    })
}

// ── "How is this calculated?" - every rule, in one list, in plain English ───
//
// AI / MAINTAINER: this screen is GENERATED from DopamineTuning. If you change a weight or
// a threshold in Dopamine.kt, this page follows automatically - there is nothing to edit
// here. Do NOT hard-code a number into this screen; read it from the tuning object, or the
// page will start lying the first time someone retunes the algorithm.
private fun showDopamineMaths() {
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val t = DopamineTuning
    val root = vbox(pad)
    root.addView(titleText("How it's calculated"))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { showDopamine() }

    c.addView(TextView(this).apply {
        text = "Every rule below adds points to a daily total, capped at 100. Higher is worse. " +
            "Nothing here is hidden from you."
        textSize = 14f; setTextColor(0xFF4A4F54.toInt()); setLineSpacing(0f, 1.2f)
        setPadding(0, 0, 0, (4 * dp).toInt())
    })

    fun rule(title: String, worth: String, body: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * dp; setColor(0xFFF4F6F8.toInt())
            }
            val p = (14 * dp).toInt(); setPadding(p, (12 * dp).toInt(), p, (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1F2933.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = worth; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFB3261E.toInt())
        })
        card.addView(head)
        card.addView(TextView(this).apply {
            text = body; textSize = 13f; setTextColor(0xFF4A4F54.toInt())
            setLineSpacing(0f, 1.15f); setPadding(0, (5 * dp).toInt(), 0, 0)
        })
        c.addView(card)
    }

    c.addView(statHeader("1 · TIME SPENT — THE BIG ONE", dp))
    c.addView(TextView(this).apply {
        text = "Each category is worth points at a full dose. A \"full dose\" is " +
            "${t.DOSE_MAX_HOURS.toInt()} hours or more in a day. Below that it scales up on a " +
            "curve, so the fourth hour costs you more than the first."
        textSize = 13f; setTextColor(0xFF4A4F54.toInt()); setLineSpacing(0f, 1.15f)
    })
    DopamineCategory.values()
        .filter { it != DopamineCategory.OTHER }
        .sortedByDescending { t.CATEGORY_POINTS[it] ?: 0f }
        .forEach { cat ->
            val pts = Math.round(t.CATEGORY_POINTS[cat] ?: 0f)
            c.addView(TextView(this).apply {
                text = "•  ${cat.label} — up to $pts points"
                textSize = 14f; setTextColor(0xFF3C4650.toInt())
                setPadding(0, (7 * dp).toInt(), 0, 0)
            })
        }
    c.addView(TextView(this).apply {
        text = "Example: 2 hours of ${DopamineCategory.FAST_VIDEO.label.lowercase()} is half the " +
            "maximum dose, which on the curve is about " +
            "${Math.round(t.doseMultiplier(2f) * 100)}% of " +
            "${Math.round(t.CATEGORY_POINTS[DopamineCategory.FAST_VIDEO] ?: 0f)} points ≈ " +
            "${Math.round(t.doseMultiplier(2f) * (t.CATEGORY_POINTS[DopamineCategory.FAST_VIDEO] ?: 0f))} points."
        textSize = 12f; setTextColor(0xFF7B848C.toInt()); setLineSpacing(0f, 1.15f)
        setPadding(0, (10 * dp).toInt(), 0, 0)
    })

    c.addView(statHeader("2 · EVERYTHING ELSE", dp))
    rule("When you did it",
        "×${t.LATE_NIGHT_MULTIPLIER} / ×${t.JUST_WOKE_MULTIPLIER}",
        "Time spent between ${t.LATE_NIGHT_FROM}:00 and 0${t.LATE_NIGHT_TO}:00 is multiplied by " +
            "${t.LATE_NIGHT_MULTIPLIER} — you pay twice, once for the hit and once for the sleep. " +
            "Use in the first ${t.JUST_WOKE_WINDOW_MIN} minutes after waking is multiplied by " +
            "${t.JUST_WOKE_MULTIPLIER}.")
    rule("Phone unlocks", "up to ${Math.round(t.UNLOCKS_MAX_POINTS)}",
        "Nothing below ${Math.round(t.UNLOCKS_PER_HOUR_OK)} an hour. Full points at " +
            "${Math.round(t.UNLOCKS_PER_HOUR_BAD)} an hour or more.")
    rule("Straight-in opens", "up to ${Math.round(t.URGENT_OPEN_MAX_POINTS)}",
        "${Math.round(t.URGENT_OPEN_POINTS)} points each time you unlock the phone and are inside " +
            "a feed within ${t.URGENT_OPEN_SECONDS} seconds. No decision happened there.")
    rule("Compulsive checking", "up to ${Math.round(t.CHECKS_MAX_POINTS)}",
        "Based on the MOST times you opened any single app within one hour. Nothing below " +
            "${t.CHECKS_PER_HOUR_OK} opens; full points at ${t.CHECKS_PER_HOUR_BAD}.")
    rule("Scrolling / tapping rate", "up to ${Math.round(t.INTERACTIONS_MAX_POINTS)}",
        "Interactions per minute of screen time. Nothing below ${Math.round(t.INTERACTIONS_PER_MIN_OK)}/min " +
            "(that's reading). Full points at ${Math.round(t.INTERACTIONS_PER_MIN_BAD)}/min (that's thumbing a feed).")
    rule("Lying down", "up to ${Math.round(t.LYING_MAX_POINTS)}",
        "Full points at ${t.POSTURE_FULL_HOURS.toInt()} hours of phone use lying down.")
    rule("In the dark", "up to ${Math.round(t.DARK_MAX_POINTS)}",
        "Full points at ${t.POSTURE_FULL_HOURS.toInt()} hours of phone use in a dark room.")

    c.addView(statHeader("3 · WHAT TAKES POINTS OFF", dp))
    c.addView(TextView(this).apply {
        text = "Time with the screen off — up to ${Math.round(t.SCREEN_OFF_MAX_POINTS)} points back, " +
            "with full credit at ${t.SCREEN_OFF_FULL_HOURS.toInt()} waking hours off the phone.\n\n" +
            "That is deliberately small. A calm evening does not undo a four-hour binge, and a " +
            "score that pretended it did would be no use to you."
        textSize = 13f; setTextColor(0xFF4A4F54.toInt()); setLineSpacing(0f, 1.15f)
    })

    c.addView(statHeader("4 · WHAT THE NUMBER IS CALLED", dp))
    listOf(0, 15, 30, 45, 60, 80).forEach { lo ->
        c.addView(TextView(this).apply {
            text = "•  $lo+  ·  ${t.band(lo)}"
            textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(t.bandColour(lo)); setPadding(0, (6 * dp).toInt(), 0, 0)
        })
    }

    c.addView(statHeader("WHAT WE CAN'T MEASURE", dp))
    c.addView(TextView(this).apply {
        text = "Being straight with you about the holes:\n\n" +
            "• We cannot tell when you woke up. Android gives us no alarm signal, so we treat " +
            "your first unlock after a ${t.WAKE_GAP_HOURS.toInt()}+ hour gap as \"just woke up\". " +
            "Nap and it'll misfire.\n\n" +
            "• Screen-off credit doesn't know whether a podcast is playing. Seeing that needs a " +
            "permission this app deliberately doesn't take."
        textSize = 13f; setTextColor(0xFF7B848C.toInt()); setLineSpacing(0f, 1.15f)
        setPadding(0, 0, 0, (24 * dp).toInt())
    })
}

/** One "Short-form video   +18   1h 20m today" row. */
private fun scoreLineRow(label: String, points: Int, detail: String, colour: Int, dp: Float): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
    }
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    texts.addView(TextView(this).apply {
        text = label; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
    })
    texts.addView(TextView(this).apply {
        text = detail; textSize = 13f; setTextColor(0xFF7B848C.toInt())
    })
    row.addView(texts)
    if (points != 0) row.addView(TextView(this).apply {
        text = if (points > 0) "+$points" else "$points"
        textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(colour)
    })
    return row
}

// ── "How do I bring this down?" - generic advice, then advice aimed at THEIR data ──
private fun showDopamineGuidance() {
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val today = DopamineLog.today(this)
    val r = DopamineScore.of(today)
    val root = vbox(pad)
    root.addView(titleText("Bringing it down"))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { showDopamine() }

    // The part aimed squarely at what THEIR number is actually made of.
    val top = r.contributors.firstOrNull { it.points > 0 }
    if (top != null) {
        c.addView(statHeader("YOUR BIGGEST ONE", dp))
        c.addView(TextView(this).apply {
            text = "${top.label} — ${top.detail}.\n\n${adviceFor(top.label)}"
            textSize = 15f; setTextColor(0xFF1F2933.toInt()); setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, (8 * dp).toInt())
        })
    }

    c.addView(statHeader("WHAT ACTUALLY MOVES IT", dp))
    listOf(
        "Time is the biggest lever, by a mile." to
            "Everything else in the score is small change next to how long you spend in the worst categories. An hour less short-form video will move this number more than a perfect day of everything else.",
        "The first hour matters least, the fourth matters most." to
            "The curve is deliberately steep at the top end. Cutting a four-hour day to three does more than cutting a one-hour day to zero.",
        "Don't start the day in a feed." to
            "Use just after waking is weighted harder, and it sets the tone for the whole day. Put the first unlock somewhere else.",
        "Don't end it in one either." to
            "Late-night use is weighted hardest of all, because you're paying twice: the hit, and the sleep.",
        "Unlocks are a habit, not a need." to
            "Most unlocks aren't for anything. The count itself is the signal - if it's high, you're reaching for it on autopilot.",
        "Slow down." to
            "A high interaction rate means thumbing a feed. A low one means reading something. The same hour can be either.",
        "Get out of bed, and turn a light on." to
            "Lying down in the dark with a phone is the single most reliable setup for a bad night. Strict mode's night guard exists for exactly this.",
    ).forEach { (h, b) ->
        c.addView(TextView(this).apply {
            text = h; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
            setPadding(0, (12 * dp).toInt(), 0, (2 * dp).toInt())
        })
        c.addView(TextView(this).apply {
            text = b; textSize = 14f; setTextColor(0xFF4A4F54.toInt()); setLineSpacing(0f, 1.15f)
        })
    }
    c.addView(TextView(this).apply {
        text = "\nAnd the honest bit: this recovers slowly. The anti-rules in the score are weak on " +
            "purpose, because a walk does not undo a four-hour binge and a number that pretended " +
            "otherwise would be useless to you."
        textSize = 13f; setTextColor(0xFF7B848C.toInt()); setLineSpacing(0f, 1.15f)
        setPadding(0, (10 * dp).toInt(), 0, (16 * dp).toInt())
    })
}

private fun adviceFor(label: String): String = when (label) {
    DopamineCategory.ADULT.label ->
        "This is the one this whole app is built around. Everything on the Adult Content page - strict mode, the ban list, the night guard - is aimed at exactly this."
    DopamineCategory.FAST_VIDEO.label ->
        "Short-form video is the most concentrated dose available to you. Turn on the short-form block (Temptations → Endless Scrolling) and the feeds die while the rest of the app still works."
    DopamineCategory.FAST_SOCIAL.label ->
        "Drop these to the grey tier so they get a few minutes an hour and then close. Temptations → Social Comparison has the switch."
    DopamineCategory.IMPULSE.label ->
        "Temptations → Impulse Shopping blocks the sites. The 48-hour basket rule does the rest."
    DopamineCategory.FORUMS_NEWS.label ->
        "Temptations → News Cycles. Pick one time a day to read it, and block it the rest of the time."
    DopamineCategory.LONG_VIDEO.label ->
        "Less concentrated than the short stuff, but it eats whole evenings. Temptations → Binge Watching."
    DopamineCategory.MOBILE_GAMING.label ->
        "Temptations → Gaming & Reward Loops. Name what you're avoiding by playing."
    DopamineCategory.GAMBLING.label ->
        "This is the one category here that can take more than your time. If it's showing up at all, it's worth treating as seriously as the adult content."
    "Phone unlocks" ->
        "Leave it in another room. Most of these aren't decisions - you won't miss what you never reached for."
    "Straight-in opens" ->
        "You unlocked and were in a feed before you'd thought about it. That's the purest autopilot there is. Moving the app off your home screen breaks the reflex more than willpower does."
    "Compulsive checking" ->
        "Opening the same app over and over in an hour is a tic, not a use. The grey tier (a few minutes an hour, then it closes) is built for it."
    "Constant scrolling / tapping" ->
        "A high rate means you're thumbing, not reading. Slowing down IS the intervention."
    "Using it lying down", "Using it in the dark" ->
        "Strict mode's night guard blocks non-essential apps in exactly this state. It's the single highest-leverage switch you have."
    else -> "Cut the time first. It outweighs everything else in the score."
}

// ── The self-reported habits: a SEPARATE estimate, never mixed into the score ──
private fun showLifeInputs() {
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("Your habits estimate"))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { lifeInputsBack() }

    val banner = TextView(this).apply {
        text = "This does NOT affect your dopamine baseline.\n\nIt's self-reported, so it can't - " +
            "otherwise you could tick a few boxes and watch the number you came here to face go " +
            "down. This is a separate estimate of the restorative side of your life, and it runs " +
            "the other way: HIGHER IS BETTER."
        textSize = 13f; setTextColor(0xFF4A4F54.toInt()); setLineSpacing(0f, 1.15f)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 14 * dp; setColor(0xFFF4F6F8.toInt())
        }
        val p = (14 * dp).toInt(); setPadding(p, p, p, p)
    }
    c.addView(banner)

    val scoreLabel = TextView(this).apply {
        textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF2E7D32.toInt())
        setPadding(0, (16 * dp).toInt(), 0, (2 * dp).toInt())
    }
    fun paintScore() {
        scoreLabel.text = "Restorative habits: ${LifeInputs.estimate(this)} / 100"
    }
    paintScore()
    c.addView(scoreLabel)
    c.addView(TextView(this).apply {
        text = "On a typical day."
        textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, 0, 0, (4 * dp).toInt())
    })

    // Each habit gets the scale that suits IT: sleep in hours, meditation in minutes,
    // caffeine in cups. A shared 0-7 slider was the wrong shape for all of them.
    LifeInputs.HABITS.forEach { habit ->
        c.addView(TextView(this).apply {
            text = habit.label; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1F2933.toInt()); setPadding(0, (18 * dp).toInt(), 0, (1 * dp).toInt())
        })
        c.addView(TextView(this).apply {
            text = habit.hint; textSize = 12f; setTextColor(0xFF9AA0A6.toInt())
            setPadding(0, 0, 0, (8 * dp).toInt())
        })
        c.addView(optionChips(habit) { paintScore() })
    }
    c.addView(View(this), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, (28 * dp).toInt()))
}

/** A row of tappable pills - one per option on this habit's scale. Selected one is filled. */
private fun optionChips(habit: LifeInputs.Habit, onChange: () -> Unit): View {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val chips = mutableListOf<TextView>()
    var selected = LifeInputs.get(this, habit.key)

    fun paint() {
        chips.forEachIndexed { i, chip ->
            val on = i == selected
            chip.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF5A6068.toInt())
            chip.setTypeface(chip.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
            chip.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(if (on) 0xFF52796F.toInt() else 0x00000000)
                setStroke((1.2f * dp).toInt(), if (on) 0xFF52796F.toInt() else 0xFFCFD5D9.toInt())
            }
        }
    }

    habit.options.forEachIndexed { i, opt ->
        val chip = TextView(this).apply {
            text = opt.label
            textSize = 12f
            gravity = Gravity.CENTER
            val px = (6 * dp).toInt(); val py = (9 * dp).toInt()
            setPadding(px, py, px, py)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = if (i == habit.options.lastIndex) 0 else (5 * dp).toInt() }
            setOnClickListener {
                selected = i
                LifeInputs.set(this@MainActivity, habit.key, i)
                paint(); onChange()
            }
        }
        chips.add(chip)
        row.addView(chip)
    }
    paint()
    return row
}

// ── Where & how it happens: posture + light at the moment things go wrong ───
//
// Built from the posture/lightLevel now stamped onto every block event and every relapse
// report (SensorContext captures them at the moment, rather than asking you to remember).
// The point is a single honest sentence: "this happens to you lying down, in the dark."
private fun showContextStats() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("Where & how it happens"))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { showStatsMenu() }

    lifecycleScope.launch {
        val events = BlockEventLog.recent(this@MainActivity, 90L * 24 * 60 * 60 * 1000)
        val relapses = RelapseLog.all(this@MainActivity)

        val postures = events.mapNotNull { it.posture } + relapses.mapNotNull { it.posture }
        val lights = events.mapNotNull { it.lightLevel } + relapses.mapNotNull { it.lightLevel }
        val known = postures.filter { it != SensorContext.UNKNOWN }
        val knownLight = lights.filter { it != SensorContext.UNKNOWN }

        if (known.isEmpty() && knownLight.isEmpty()) {
            c.addView(TextView(this@MainActivity).apply {
                text = "Nothing to show yet.\n\nFrom now on, every block and every reported slip " +
                    "records whether you were lying down and how dark the room was. Once a few " +
                    "have built up, the pattern shows up here."
                textSize = 15f; setTextColor(0xFF6B7075.toInt()); setLineSpacing(0f, 1.2f)
            })
            return@launch
        }

        // ── the one-sentence verdicts ──
        val topPosture = known.groupingBy { it }.eachCount().maxByOrNull { it.value }
        val topLight = knownLight.groupingBy { it }.eachCount().maxByOrNull { it.value }
        c.addView(TextView(this@MainActivity).apply {
            val bits = mutableListOf<String>()
            topPosture?.let { (label, n) ->
                val pct = n * 100 / known.size
                bits.add(if (label == "lying")
                    "You are usually LYING DOWN when this happens ($pct% of the time)."
                else
                    "You are usually UPRIGHT when this happens ($pct% of the time).")
            }
            topLight?.let { (label, n) ->
                val pct = n * 100 / knownLight.size
                bits.add("The light level is usually ${lightWord(label)} ($pct% of the time).")
            }
            text = bits.joinToString("\n\n")
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
            setLineSpacing(0f, 1.2f); setPadding(0, 0, 0, (18 * dp).toInt())
        })

        // ── the breakdowns ──
        if (known.isNotEmpty()) {
            c.addView(statHeader("BODY POSITION", dp))
            listOf("lying" to "Lying down", "upright" to "Upright").forEach { (key, label) ->
                c.addView(statBar(label, known.count { it == key }, known.size, 0xFF52796F.toInt(), dp))
            }
        }
        if (knownLight.isNotEmpty()) {
            c.addView(statHeader("LIGHT IN THE ROOM", dp))
            AppConfig.LightLevel.values().forEach { level ->
                c.addView(statBar(
                    lightWord(level.name).replaceFirstChar { it.uppercase() },
                    knownLight.count { it == level.name }, knownLight.size,
                    0xFF3E7C8E.toInt(), dp,
                ))
            }
        }

        val unknown = postures.size - known.size
        if (unknown > 0) c.addView(TextView(this@MainActivity).apply {
            text = "$unknown record(s) had no sensor reading yet, so they're left out of the " +
                "percentages above."
            textSize = 12f; setTextColor(0xFF9AA0A6.toInt())
            setPadding(0, (16 * dp).toInt(), 0, (16 * dp).toInt())
        })
    }
}

/** DARK/DULL/NORMAL/BRIGHT -> words a human uses. */
private fun lightWord(level: String): String = when (level) {
    "DARK" -> "dark"
    "DULL" -> "dim"
    "NORMAL" -> "normal indoor light"
    "BRIGHT" -> "bright"
    else -> level.lowercase()
}

private fun statHeader(text: String, dp: Float): TextView = TextView(this).apply {
    this.text = text; textSize = 11f; setTypeface(typeface, Typeface.BOLD)
    setTextColor(0xFF9AA0A6.toInt()); setPadding(0, (10 * dp).toInt(), 0, (8 * dp).toInt())
}

/** One labelled proportional bar: "Lying down   12  (67%)". */
private fun statBar(label: String, count: Int, total: Int, colour: Int, dp: Float): View {
    val pct = if (total == 0) 0 else count * 100 / total
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, (10 * dp).toInt())
    }
    row.addView(TextView(this).apply {
        text = "$label   ·   $count  ($pct%)"
        textSize = 14f; setTextColor(0xFF3C4650.toInt())
        setPadding(0, 0, 0, (4 * dp).toInt())
    })
    // The bar: a filled track inside a grey one, width by weight.
    val track = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 6 * dp; setColor(0xFFE8EBED.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (12 * dp).toInt())
    }
    track.addView(View(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 6 * dp; setColor(colour)
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, pct.toFloat())
    })
    // The empty remainder, so the weights add up to 100.
    track.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - pct).toFloat())
    })
    row.addView(track)
    return row
}

// ── Progress & reward: the non-resetting consistency score + real stats ─────
private fun showProgress() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val s = Progress.snapshot(this)
    val green = 0xFF2E7D32.toInt(); val teal = 0xFF2E9E8F.toInt()
    val root = vbox(pad)
    root.addView(titleText("Progress"))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { showStatsMenu() }

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
        text = "One slip never resets this - it only dips it a little. The goal is the trend, not a perfect streak."
        textSize = 13f; setTextColor(0xFF6B7075.toInt()); setPadding(0, (8 * dp).toInt(), 0, 0)
    })
    if (s.forgivingRun > 0) c.addView(TextView(this).apply {
        text = "Current run: ${s.forgivingRun} day${if (s.forgivingRun == 1) "" else "s"} - one slip won't end it."
        textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(green); setPadding(0, (8 * dp).toInt(), 0, 0)
    })

    c.addView(sectionTitle("Time reclaimed"))
    c.addView(statBigCard("${s.reclaimedHours}h", "reclaimed so far",
        "estimated - about ${Progress.EST_MIN_PER_WIN} min per urge you rode out", teal))

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
        text = "None yet - they're coming."; textSize = 14f; setTextColor(0xFF9AA0A6.toInt())
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
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(title))
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(content)
    })
    setContentWithThumb(root) { back() }
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
            "Can you step outside - even just a short walk?", "\uD83D\uDEB6",
            "Yes - I'll go now", { waveSuccess() },
            "Not right now", { waveMove() },
        )
    }
    private fun waveMove() {
        tBack = { waveWalk() }
        waveActionScreen(
            "Can you move to a different room?", "\uD83D\uDEAA",
            "Done - I've moved", { waveSuccess() },
            "Can't right now", { wavePhysical() },
        )
    }
    private fun wavePhysical() {
        tBack = { waveMove() }
        waveActionScreen(
            "Can you do something physical - stretch, press-ups, tidy up?", "\uD83E\uDD38",
            "Yes - doing it", { waveSuccess() },
            "I can't do any of these", { waveStuck() },
        )
    }

    private fun waveStuck() {
        tBack = { wavePhysical() }
        waveBreatheScreen(
            "Then just breathe and wait",
            "You don't have to do anything but outlast it. The wave always passes - you only have to get through this one.",
            "I've breathed - what now?",
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
            text = "An urge peaks within the first 30 seconds or so - and you just rode straight through it. From here it only fades. You can get through this."
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
        // urge over time: it spikes, then falls - and you're already past the peak.
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
                showReportScreen()
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
        text = "Follow the orb - $totalBreaths slow breaths"
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
                    counter.text = "Done - nicely paced"
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

// Quiet milestone line - only speaks at 30s / 1m / 2m / 10m, nothing after.
private fun attachWaveTimer(label: TextView) {
    rideRunnable?.let { rideHandler?.removeCallbacks(it) }
    rideHandler = Handler(Looper.getMainLooper())
    rideRunnable = object : Runnable {
        override fun run() {
            val sec = (System.currentTimeMillis() - waveStartAt) / 1000
            label.text = when {
                sec >= 600 -> "10 minutes in - it's faded. You did this."
                sec >= 120 -> "2 minutes in - you're riding it out."
                sec >= 60 -> "1 minute in - the peak has passed."
                sec >= 30 -> "30 seconds in - you're doing it, keep going."
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
        root.addView(titleText("What do you want to limit?"))
        root.addView(TextView(this).apply {
            text = "Set this now, while you're calm - the app just honours it later. " +
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
    root.addView(titleText("Add a website"))
    val urlInput = EditText(this).apply {
        hint = "paste or type a web address"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        maxLines = 1
    }
    root.addView(urlInput)
    root.addView(tierNote())
    root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(bigChoice("Greylist it - ${GreyUsage.LIMIT_MIN} min / hour", 0xFF3E535C.toInt()) {
        saveSiteRule(urlInput, AppRules.GREY)
    })
    root.addView(bigChoice("Blocklist it - block outright", 0xFFB00020.toInt()) {
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
    root.addView(titleText("Limit ${a.label}?"))
    root.addView(tierNote())
    root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(bigChoice("Greylist - ${GreyUsage.LIMIT_MIN} min / hour", 0xFF3E535C.toInt()) {
        AppRules.setApp(this, a.pkg, AppRules.GREY); appSiteSaved(a.label, AppRules.GREY)
    })
    root.addView(bigChoice("Blocklist - block outright", 0xFFB00020.toInt()) {
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
            (if (tier == AppRules.GREY) "greylisted - ${GreyUsage.LIMIT_MIN} minutes each hour"
             else "blocklisted - blocked outright") +
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
                val before = e.recentAppsList().joinToString(", ").ifBlank { "-" }
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
// Order, deliberately: the dopamine baseline + rank first (the thing to care about),
// then the usage graphs (what it costs), then the two big doors (Productivity,
// Temptations), dev tools, the status console, and a tiny about link. The old
// "what you've reclaimed" stats and the cost projector live in showScrollCost()
// inside Productivity now.
private fun setupHomeScreen() {
    onHomeScreen = true; onTemptationsTab = false; onReportScreen = false; onDevScreen = false
    subBack = null
    inSubPage = false; inRelapseFlow = false; inTemptationFlow = false
    inLoosenFlow = false; inAppSiteFlow = false
    stopRideTimer(); stopLoosenTimer(); entriesJob?.cancel()
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
    val teal = 0xFF2E9E8F.toInt()

    // ── 1. Dopamine baseline: rank badge + gauge + trend. Tap for the full page. ──
    val today = DopamineScore.of(DopamineLog.today(this))
    val rank = DopamineRank.of(this)
    val dopCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 18 * dp; setColor(0xFFF4F6F8.toInt()) }
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        isClickable = true; isFocusable = true
        setOnClickListener { dopamineBack = { setupHomeScreen() }; showDopamine() }
    }
    dopCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = rank.title.uppercase(); textSize = 24f
            setTypeface(typeface, Typeface.BOLD); setTextColor(rank.colour)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@MainActivity).apply {
            text = "learn more ›"; textSize = 13f; setTextColor(0xFF2E9E8F.toInt()); setTypeface(typeface, Typeface.BOLD)
        })
    })
    dopCard.addView(TextView(this).apply {
        text = rank.longTitle; textSize = 13f; setTypeface(typeface, Typeface.BOLD or Typeface.ITALIC)
        setTextColor(rank.colour)
    })
    dopCard.addView(TextView(this).apply {
        text = rank.detail; textSize = 12f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (2 * dp).toInt(), 0, (10 * dp).toInt())
    })
    val history14 = DopamineLog.history(this, 14)
    val scores14 = history14.map { DopamineScore.of(it).score.toFloat() }.toFloatArray()
    dopCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(DopamineScaleView(this@MainActivity, today.score), LinearLayout.LayoutParams((46 * dp).toInt(), (132 * dp).toInt()))
        if (scores14.count { it > 0f } >= 2) {
            addView(TrendView(this@MainActivity, scores14), LinearLayout.LayoutParams(0, (132 * dp).toInt(), 1f).apply {
                marginStart = (12 * dp).toInt()
            })
        } else {
            addView(TextView(this@MainActivity).apply {
                text = "The 14-day trend appears here after a couple of days of normal phone use."
                textSize = 13f; setTextColor(0xFF9AA0A6.toInt()); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, (132 * dp).toInt(), 1f)
            })
        }
    })
    dopCard.addView(TextView(this).apply {
        text = if (today.hasData) "Baseline today: ${today.score} · ${today.band}" else "Baseline today: still measuring"
        textSize = 13f; setTextColor(0xFF52606A.toInt()); setPadding(0, (6 * dp).toInt(), 0, 0)
    })
    content.addView(dopCard)

    // ── 2. Usage graphs: real tracked screen-on time - or, before any exists, a
    //    clearly-labelled EXAMPLE, so a brand-new user immediately sees what the page
    //    becomes instead of an empty promise. The example uses their real £ numbers
    //    (AboutYou) and goal, so entering either updates it live.
    val history90 = DopamineLog.history(this, 90)
    fun hoursOf(day: DopamineDay): Float =
        if (DopamineScore.of(day).hasData) day.screenOnSeconds / 3600f else Float.NaN
    val goalHours = UsageGoal.hoursPerDay(this)
    val realDaily = history14.map { hoursOf(it) }.toFloatArray()
    val hasUsage = realDaily.any { !it.isNaN() }
    // A believable fortnight around the ~4-5 h/day average; fixed values, so the page
    // doesn't shimmer between opens.
    val daily = if (hasUsage) realDaily
        else floatArrayOf(3.6f, 4.4f, 5.1f, 3.2f, 4.8f, 6.0f, 5.4f, 3.9f, 4.2f, 5.6f, 4.9f, 3.4f, 4.6f, 5.2f)
    fun exampleTag() = TextView(this).apply {
        text = "EXAMPLE - your real usage replaces this after a day or two"
        textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFB07800.toInt())
        setPadding(0, (2 * dp).toInt(), 0, 0)
    }

    content.addView(sectionTitle("Hours on the phone - last 14 days"))
    val labels14 = if (hasUsage) history14.mapIndexed { i, d ->
        if (i % 3 == 0 || i == history14.size - 1) d.date.takeLast(2) else ""
    } else List(14) { "" }
    content.addView(UsageBarChartView(this, daily, labels14, goalHours),
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (150 * dp).toInt()))
    if (!hasUsage) content.addView(exampleTag())
    if (goalHours != null) {
        val dataDays = daily.filter { !it.isNaN() }
        val under = dataDays.count { it <= goalHours }
        content.addView(TextView(this).apply {
            text = "Goal ${fmtHours(goalHours)} a day · under it $under of ${dataDays.size} days"
            textSize = 13f; setTextColor(0xFF52606A.toInt()); setPadding(0, (4 * dp).toInt(), 0, 0)
        })
    }

    // Monthly: average hours per day, per calendar month.
    val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val byMonth = history90.filter { DopamineScore.of(it).hasData }
        .groupBy { it.date.substring(0, 7) }.toSortedMap()
    if (hasUsage && byMonth.size >= 2) {
        content.addView(sectionTitle("Average hours per day, by month"))
        val monthly = byMonth.values.map { days -> days.map { it.screenOnSeconds / 3600f }.average().toFloat() }.toFloatArray()
        val monthLabels = byMonth.keys.map { monthNames[it.substring(5, 7).toInt() - 1] }
        content.addView(UsageBarChartView(this, monthly, monthLabels, goalHours),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (130 * dp).toInt()))
    } else if (!hasUsage) {
        content.addView(sectionTitle("Average hours per day, by month"))
        val thisMonth = SimpleDateFormat("MM", Locale.UK).format(Date()).toInt() - 1
        val monthLabels = (2 downTo 0).map { monthNames[(thisMonth - it + 12) % 12] }
        content.addView(UsageBarChartView(this, floatArrayOf(4.9f, 4.4f, 4.7f), monthLabels, goalHours),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (130 * dp).toInt()))
        content.addView(exampleTag())
    }

    // The year: recent pace projected forward, as time and money (their real £ rate).
    val recentDays = history90.takeLast(30).map { hoursOf(it) }.filter { !it.isNaN() }
    val avgDaily = if (hasUsage && recentDays.isNotEmpty()) recentDays.average().toFloat() else 4.6f
    content.addView(sectionTitle("A year at this pace"))
    val rate = AboutYou.effectiveHourly(this)
    val yearHours = avgDaily * 365.0
    val wakingDaysYr = Math.round(yearHours / Usage.WAKING_HOURS)
    val gbpYr = Math.round(yearHours * rate)
    val yearCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 18 * dp; setColor(0xFFF4F6F8.toInt()) }
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val yearDonut = WastedDonutView(this)
    yearDonut.setFraction(avgDaily / Usage.WAKING_HOURS)
    yearCard.addView(yearDonut, LinearLayout.LayoutParams((132 * dp).toInt(), (132 * dp).toInt()).apply {
        gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (6 * dp).toInt()
    })
    yearCard.addView(TextView(this).apply {
        text = "$wakingDaysYr waking days a year"; textSize = 24f
        setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(0xFFE4673B.toInt())
    })
    yearCard.addView(TextView(this).apply {
        text = "≈ £$gbpYr a year of your time, at ${fmtHours(avgDaily)} a day"
        textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF52606A.toInt()); setPadding(0, (2 * dp).toInt(), 0, 0)
    })
    if (goalHours != null && goalHours < avgDaily) {
        val savedGbp = Math.round((avgDaily - goalHours) * 365.0 * rate)
        val goalDays = Math.round(goalHours * 365.0 / Usage.WAKING_HOURS)
        yearCard.addView(TextView(this).apply {
            text = "At your ${fmtHours(goalHours)}/day goal: $goalDays waking days - clawing back £$savedGbp a year"
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFF2E7D32.toInt())
            setTypeface(typeface, Typeface.BOLD); setPadding(0, (6 * dp).toInt(), 0, 0)
        })
    }
    if (!hasUsage) yearCard.addView(exampleTag().apply { gravity = Gravity.CENTER })
    content.addView(yearCard)
    content.addView(smallLink(if (goalHours == null) "Set a usage goal  ›" else "Your goal: ${fmtHours(goalHours)} a day - change  ›", dp) {
        showUsageGoal()
    })

    // ── 3. The two big doors ────────────────────────────────────────────────
    fun bigDoor(label: String, colour: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * dp; setColor(colour) }
        val p = (18 * dp).toInt(); setPadding(p, (16 * dp).toInt(), p, (16 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * dp).toInt() }
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@MainActivity).apply { text = "→"; textSize = 22f; setTextColor(0xFFFFFFFF.toInt()) })
    }
    content.addView(bigDoor("Productivity", teal) { showProductivity() })
    content.addView(bigDoor("Temptations", 0xFF3E535C.toInt()) { showTemptationsTab() })

    // ── 4. Dev tools (only when dev mode is on) ─────────────────────────────
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
                text = "🔧  Dev tools"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF5A6068.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply { text = "›"; textSize = 20f; setTextColor(0xFF9AA0A6.toInt()) })
        })
    }

    // permission/status console, then the quietest possible about link
    content.addView(permissionConsole())
    content.addView(TextView(this).apply {
        text = "About & privacy"; textSize = 12f; setTextColor(0xFF9AA0A6.toInt())
        gravity = Gravity.CENTER; isClickable = true; isFocusable = true
        setPadding(0, (18 * dp).toInt(), 0, (6 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { showAboutPage() }
    })

    val root = ScrollView(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isFillViewport = true
        addView(content)
    }
    setContentNoThumb(root)   // the landing screen - nothing behind it to go back to
}

private fun fmtHours(h: Float): String {
    val m = Math.round(h * 60)
    return if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m"
}

// ── Usage goal: pick a daily phone-time target; the home graphs draw it. ──────
private fun showUsageGoal() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("Usage goal"))
    root.addView(TextView(this).apply {
        text = "Pick how much time on the phone per day you'd be happy with. The home-page " +
            "graphs draw the goal as a line: days under it go green, days over it don't."
        textSize = 14f; setTextColor(0xFF52606A.toInt()); setPadding(0, 0, 0, (14 * dp).toInt())
    })
    val current = UsageGoal.minutesPerDay(this)
    val label = TextView(this).apply {
        textSize = 30f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(0xFF1F2933.toInt())
        setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
    }
    // 15-minute steps from 15 min to 8 h.
    val seek = android.widget.SeekBar(this).apply {
        max = 31
        progress = (((if (current > 0) current else 120) / 15) - 1).coerceIn(0, 31)
    }
    fun minutesOf(p: Int) = (p + 1) * 15
    fun refreshLabel() { label.text = fmtHours(minutesOf(seek.progress) / 60f) + " a day" }
    seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) { refreshLabel() }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
    })
    root.addView(label); root.addView(seek)
    refreshLabel()
    root.addView(bigChoice("Set goal", 0xFF2E7D32.toInt()) {
        UsageGoal.setMinutesPerDay(this, minutesOf(seek.progress))
        Toast.makeText(this, "Goal set: ${fmtHours(minutesOf(seek.progress) / 60f)} a day", Toast.LENGTH_SHORT).show()
        setupHomeScreen()
    })
    if (current > 0) root.addView(Button(this).apply {
        text = "Remove the goal"; setAllCaps(false)
        setOnClickListener { UsageGoal.clear(this@MainActivity); setupHomeScreen() }
    })
    setContentWithThumb(root) { setupHomeScreen() }
}

// ── What the scroll costs: the projector + reclaimed stats (moved off the home page). ──
private fun showScrollCost() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
    content.addView(titleText("What the scroll costs you"))

    val green = 0xFF2E7D32.toInt(); val teal = 0xFF2E9E8F.toInt()
    val s = Progress.snapshot(this)
    if (s.hasData) {
        content.addView(statBigCard("${s.reclaimedHours}h", "reclaimed so far",
            "about ${Progress.EST_MIN_PER_WIN} min back for every urge you rode out", teal))
        content.addView(statBigCard("${s.consistency}%", "consistency",
            "${s.cleanDays} of the last ${s.trackedDays} days clean - one slip never resets it", green))
    } else {
        content.addView(statBigCard("0h", "reclaimed so far",
            "ride out your first urge and your reclaimed time starts here", teal))
    }

    content.addView(sectionTitle("The projector"))
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
    // The same lost time, said in ways that aren't hours - money, health, people. Filled in
    // by refresh(), and it uses THEIR numbers once they've given us any (see AboutYou).
    val otherStat = TextView(this).apply {
        textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF52606A.toInt())
        setLineSpacing(0f, 1.25f)
        setPadding(0, 0, 0, (10 * dp).toInt())
    }
    val aboutYouLink = TextView(this).apply {
        textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFF2E9E8F.toInt())
        setTypeface(typeface, Typeface.BOLD)
        isClickable = true; isFocusable = true
        setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
        setOnClickListener { aboutYouBack = { showScrollCost() }; showAboutYou() }
    }
    hero.addView(bigStat); hero.addView(subStat); hero.addView(lifeStat)
    hero.addView(otherStat); hero.addView(aboutYouLink)
    val minLabel = TextView(this).apply { textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt()) }
    hero.addView(minLabel)
    val minSeek = android.widget.SeekBar(this).apply { max = 300; progress = Usage.minutes(this@MainActivity).coerceIn(0, 300) }
    hero.addView(minSeek)
    val yearLabel = TextView(this).apply { textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (8 * dp).toInt(), 0, 0) }
    hero.addView(yearLabel)
    val yearSeek = android.widget.SeekBar(this).apply { max = 49; progress = (Usage.years(this@MainActivity) - 1).coerceIn(0, 49) }
    hero.addView(yearSeek)
    content.addView(hero)

    fun refresh() {
        val min = Usage.minutes(this); val yrs = Usage.years(this)
        val perYearHours = min * 365.0 / 60.0
        val wakingDaysYr = (perYearHours / Usage.WAKING_HOURS)
        val rate = AboutYou.effectiveHourly(this)
        val gbpYr = Math.round(perYearHours * rate)
        val totalWakingYears = perYearHours * yrs / Usage.WAKING_HOURS / 365.0
        val gbpTotal = gbpYr * yrs
        donut.setFraction((min / (Usage.WAKING_HOURS * 60f)))
        bigStat.text = "${Math.round(wakingDaysYr)} waking days a year"
        subStat.text = "≈ £$gbpYr a year of your time"
        lifeStat.text = "Over $yrs year${if (yrs == 1) "" else "s"}: about ${String.format("%.1f", totalWakingYears)} years of waking life - and £$gbpTotal"
        minLabel.text = "$min minutes a day on short video & feeds"
        yearLabel.text = "Looking $yrs year${if (yrs == 1) "" else "s"} ahead"

        // Hours are abstract. These aren't.
        val gymSessions = Math.round(perYearHours / 1.0)          // ~1hr a session
        val eveningsWithPeople = Math.round(perYearHours / 3.0)   // ~3hr an evening
        val booksRead = Math.round(perYearHours / 8.0)            // ~8hr a book
        otherStat.text = "That same year is also:\n" +
            "$gymSessions hours of training  ·  $eveningsWithPeople evenings with people  ·  " +
            "about $booksRead books"

        aboutYouLink.text = if (AboutYou.hasData(this))
            "Valued at £${AboutYou.effectiveAnnual(this)}/yr, from your numbers  ·  change"
        else
            "Add your hourly rate to see what it's really costing you  ›"
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
    setContentWithThumb(root) { showProductivity() }
    refresh()
}

// Everything that used to sit under the home graphic now lives here.
private fun showProductivity() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
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
    fun refreshSf() { sfSub.text = if (ShortForm.enabled()) "On - the endless feeds are blocked." else "Off - tap to cut the doomscroll." }
    sfSwitch.setOnCheckedChangeListener { _, checked -> ShortForm.setEnabled(this, checked); refreshSf() }
    refreshSf()
    content.addView(sfCard)

    // The cost projector + reclaimed stats used to live on the landing page; they moved
    // here so the landing page can stay graphs-only.
    content.addView(homeCard("What the scroll costs", "The projector: minutes per day → years and £ - plus what you\u0027ve reclaimed.") { showScrollCost() })

    // ── Dopamine baseline: a whole-phone measure, so it belongs here and not buried in
    //    the adult-content statistics.
    val todayScore = DopamineScore.of(DopamineLog.today(this))
    content.addView(sectionTitle("Your dopamine baseline"))
    val dopCard = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * dp; setColor(0xFFF4F6F8.toInt())
        }
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        isClickable = true; isFocusable = true
        setOnClickListener { dopamineBack = { showProductivity() }; showDopamine() }
    }
    dopCard.addView(TextView(this).apply {
        text = if (todayScore.hasData) "${todayScore.score}" else "–"
        textSize = 34f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (todayScore.hasData) todayScore.colour else 0xFF9AA0A6.toInt())
        includeFontPadding = false
    })
    dopCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((14 * dp).toInt(), 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@MainActivity).apply {
            text = if (todayScore.hasData) todayScore.band else "Still measuring"
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
        })
        addView(TextView(this@MainActivity).apply {
            text = "out of 100 today · higher is more fried"
            textSize = 12f; setTextColor(0xFF7B848C.toInt())
        })
    })
    dopCard.addView(TextView(this).apply {
        text = "›"; textSize = 22f; setTextColor(0xFFB0B5BA.toInt())
    })
    content.addView(dopCard)

    content.addView(smallLink("Your habits estimate  ›", dp) {
        lifeInputsBack = { showProductivity() }; showLifeInputs()
    })
    content.addView(smallLink("About you — hourly rate, what your time is worth  ›", dp) {
        aboutYouBack = { showProductivity() }; showAboutYou()
    })

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
        "${Math.round(perYearHours / 6.0)} books read - about 6 hours each",
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
    setContentWithThumb(root) { setupHomeScreen() }
}

private fun showTemptationsTab() {
    onTemptationsTab = true; onHomeScreen = false; onReportScreen = false; inSubPage = false; subBack = null
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("Temptations"))
    root.addView(TextView(this).apply {
        text = "What are you managing?"; textSize = 15f; setTextColor(0xFF7B848C.toInt())
        setPadding(0, 0, 0, (10 * dp).toInt())
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    // Adult Content keeps its own big bespoke flow. Everything else shares the one simple
    // page below, driven off AppConfig.TEMPTATIONS - add a category there, not here.
    list.addView(homeCard("Adult Content \uD83D\uDD1E", "Tools for the moment, and the longer game.") {
        reportBackTarget = { showTemptationsTab() }; showReportScreen(offerLock = true)
    })
    AppConfig.TEMPTATIONS.forEach { spec ->
        list.addView(homeCard(spec.title, spec.subtitle) { showTemptation(spec) })
    }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        isFillViewport = true
        addView(list)
    })
    setContentWithThumb(root) { setupHomeScreen() }
}

// \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
//  The shared Temptations page (every category EXCEPT adult content)
// \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
//  Kept deliberately small. Three things you can do, and no more:
//    1. ride the urge out (breathe),
//    2. block what feeds it,
//    3. own up to a slip.
//  Resist bolting extras onto this - not overwhelming the user IS the feature. Anything
//  category-specific belongs in the AppConfig spec, never in an `if (spec.id == ...)`.
// \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

private var habitOrb: BreathOrbAnimator? = null

private fun showTemptation(spec: AppConfig.TemptationSpec) {
    habitOrb?.stop(); habitOrb = null
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(spec.title))
    root.addView(TextView(this).apply {
        text = spec.subtitle; textSize = 15f; setTextColor(0xFF7B848C.toInt())
        setPadding(0, 0, 0, (12 * dp).toInt())
    })

    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    // "Is this me?" - the bit that makes someone stop and recognise themselves.
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * dp; setColor(0xFFF4F6F8.toInt())
        }
        val p = (16 * dp).toInt(); setPadding(p, (14 * dp).toInt(), p, (14 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (14 * dp).toInt() }
    }
    card.addView(TextView(this).apply {
        text = "Does this sound like you?"; textSize = 15f
        setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
    })
    spec.covers.forEach { line ->
        card.addView(TextView(this).apply {
            text = "\u2022  $line"; textSize = 14f; setTextColor(0xFF3C4650.toInt())
            setLineSpacing(0f, 1.15f); setPadding(0, (8 * dp).toInt(), 0, 0)
        })
    }
    list.addView(card)

    val rides = HabitLog.count(this, spec.id, HabitLog.RIDE)
    val slips = HabitLog.recent(this, spec.id, HabitLog.SLIP, 7)
    list.addView(TextView(this).apply {
        text = "$rides ridden out  \u00B7  $slips slip(s) this week"
        textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF2E7D32.toInt())
        setPadding(0, 0, 0, (12 * dp).toInt())
    })

    list.addView(captionedButton("I feel the pull right now", "ride it out - it passes", 0xFF3E535C.toInt()) {
        habitRide(spec)
    })

    if (TemptationBlocks.hasBlocks(spec)) list.addView(blockSwitch(spec))

    list.addView(captionedButton("I slipped", "log it, no shame", 0xFF526D78.toInt()) {
        habitSlip(spec)
    })

    list.addView(TextView(this).apply {
        text = "Try instead:  ${spec.insteadOf}"
        textSize = 14f; setTextColor(0xFF4A4F54.toInt()); setLineSpacing(0f, 1.15f)
        setPadding(0, (16 * dp).toInt(), 0, (6 * dp).toInt())
    })
    list.addView(TextView(this).apply {
        text = "or lock everything down for 30 minutes"
        textSize = 14f; setTextColor(0xFF48606A.toInt())
        isClickable = true; isFocusable = true
        setPadding(0, (8 * dp).toInt(), 0, (16 * dp).toInt())
        setOnClickListener {
            Lockdown.start(this@MainActivity)
            Toast.makeText(this@MainActivity,
                "Locked down for 30 min. Essentials still work.", Toast.LENGTH_LONG).show()
            showTemptation(spec)
        }
    })

    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(list)
    })
    setContentWithThumb(root) { showTemptationsTab() }
}

/** The "block what feeds this" toggle card - on/off, saying exactly what it covers. */
private fun blockSwitch(spec: AppConfig.TemptationSpec): View {
    val dp = resources.displayMetrics.density
    var on = TemptationBlocks.enabled(spec)
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (16 * dp).toInt(); setPadding(p, (14 * dp).toInt(), p, (14 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            // Both margins, or this card sits flush against the button below it.
            topMargin = (2 * dp).toInt()
            bottomMargin = (10 * dp).toInt()
        }
        isClickable = true; isFocusable = true
    }
    val title = TextView(this).apply {
        textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
    }
    val sub = TextView(this).apply {
        textSize = 13f; setTextColor(0xFFCBD3D8.toInt()); setPadding(0, (3 * dp).toInt(), 0, 0)
    }
    fun paint() {
        title.text = if (on) "\u25CF  Blocking what feeds this" else "\u25CB  Block what feeds this"
        val sites = spec.blockPatterns.size
        val apps = spec.greyApps.size
        val appBit = if (apps > 0) " and limits $apps app(s) to ${GreyUsage.LIMIT_MIN} min/hour" else ""
        sub.text = if (on) "Blocking $sites site(s)$appBit.  Tap to turn off."
                   else "Blocks $sites site(s)$appBit.  Tap to turn on."
        row.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * dp
            setColor(if (on) 0xFF2E7D32.toInt() else 0xFF34464E.toInt())
        }
    }
    row.addView(title); row.addView(sub)
    paint()
    row.setOnClickListener {
        on = !on
        TemptationBlocks.setEnabled(this, spec, on)
        paint()
    }
    return row
}

/** Ride the urge out: a few slow breaths, then the button that says you beat it. */
private fun habitRide(spec: AppConfig.TemptationSpec) {
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val totalBreaths = 3
    val root = vbox(pad).apply { gravity = Gravity.CENTER_HORIZONTAL }
    root.addView(titleText("Ride it out"))
    root.addView(TextView(this).apply {
        text = "The pull peaks fast, then fades. Follow the orb - $totalBreaths slow breaths."
        textSize = 15f; gravity = Gravity.CENTER; setTextColor(0xFF6B7075.toInt())
    })

    val orb = BreathOrbView(this, 0xFF2E9E8F.toInt())     // INSCRIBE: it sits in a box here
    root.addView(FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(orb, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    })

    val label = TextView(this).apply {
        text = "Breathe in"; textSize = 18f; gravity = Gravity.CENTER
    }
    root.addView(label)
    val counter = TextView(this).apply {
        textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF2E7D32.toInt())
        setPadding(0, (6 * dp).toInt(), 0, (10 * dp).toInt())
    }
    root.addView(counter)

    // Only unlocks once the breaths are actually done - otherwise it's just a tap-through.
    val done = bigChoice("I've got through it", 0xFF2E7D32.toInt()) { habitRideDone(spec) }
    done.isEnabled = false
    done.alpha = 0.5f
    root.addView(done)

    habitOrb?.stop()
    habitOrb = BreathOrbAnimator(orb, label).also { a ->
        a.start(
            cycles = totalBreaths,
            onCycle = { d, t -> counter.text = if (d >= t) "Done - nicely paced" else "$d of $t done" },
            onComplete = { label.text = ""; done.isEnabled = true; done.alpha = 1f },
        )
    }
    setContentWithThumb(root) { habitOrb?.stop(); habitOrb = null; showTemptation(spec) }
}

private fun habitRideDone(spec: AppConfig.TemptationSpec) {
    habitOrb?.stop(); habitOrb = null
    HabitLog.record(this, spec.id, HabitLog.RIDE)
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val root = vbox(pad).apply { gravity = Gravity.CENTER_HORIZONTAL }
    root.addView(titleText("That was you, beating it."))
    root.addView(TextView(this).apply {
        text = "Every urge you ride out makes the next one weaker."
        textSize = 16f; gravity = Gravity.CENTER; setTextColor(0xFF4A4F54.toInt())
        setPadding(0, (4 * dp).toInt(), 0, 0)
    })
    root.addView(PeakCurveView(this), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = (12 * dp).toInt(); bottomMargin = (12 * dp).toInt()
        })
    root.addView(TextView(this).apply {
        text = "${HabitLog.count(this@MainActivity, spec.id, HabitLog.RIDE)} ridden out"
        textSize = 15f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(0, 0, 0, (12 * dp).toInt())
    })
    root.addView(captionedButton("Put the phone down", "closes the app", 0xFF2E7D32.toInt()) {
        try { finishAffinity() } catch (_: Throwable) { setupMainScreen() }
    })
    setContentWithThumb(root) { showTemptation(spec) }
}

/** Owning a slip. One tap, no interrogation - staying easy to be honest IS the point. */
private fun habitSlip(spec: AppConfig.TemptationSpec) {
    HabitLog.record(this, spec.id, HabitLog.SLIP)
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val week = HabitLog.recent(this, spec.id, HabitLog.SLIP, 7)
    val root = vbox(pad).apply { gravity = Gravity.CENTER_HORIZONTAL }
    root.addView(titleText("Logged."))
    root.addView(TextView(this).apply {
        text = "That's honesty, and it's worth more than a clean streak you had to lie for.\n\n" +
            "$week this week. The number isn't a verdict - it's just data. Watch which way it moves."
        textSize = 16f; gravity = Gravity.CENTER; setTextColor(0xFF4A4F54.toInt())
        setLineSpacing(0f, 1.15f); setPadding(0, (10 * dp).toInt(), 0, 0)
    })
    root.addView(grow())
    root.addView(TextView(this).apply {
        text = "Next time, try:  ${spec.insteadOf}"
        textSize = 15f; gravity = Gravity.CENTER; setTextColor(0xFF2E7D32.toInt())
        setLineSpacing(0f, 1.15f); setPadding(0, 0, 0, (14 * dp).toInt())
    })
    setContentWithThumb(root) { showTemptation(spec) }
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
    root.addView(titleText("Break the addiction protocol"))
    root.addView(TextView(this).apply {
        text = "Two moves do most of the work: a real break away from your device, then locking it down hard for the week after. Everything else supports those two."
        textSize = 15f; setTextColor(0xFF6B7075.toInt()); setPadding(0, 0, 0, (10 * dp).toInt())
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    list.addView(TextView(this).apply {
        text = "BUILD THE WALLS AROUND IT"; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFF9AA0A6.toInt()); setPadding((2 * dp).toInt(), 0, 0, (8 * dp).toInt())
    })
    // Look like the rest: a tickbox + tap to open the guide.
    list.addView(protocolLinkCheckRow("Rearrange your apps",
        "Get the troublesome ones out of easy reach.", appsDone) { showProtocolApps() })
    val anyReplace = Protocol.isChecked(this, "buy_alarm") ||
                     Protocol.isChecked(this, "charge_outside") || Protocol.isChecked(this, "buy_watch")
    list.addView(protocolLinkCheckRow("Keep your phone out of the bedroom",
        "Alarm clock so it never comes to bed - tap for the how.", anyReplace) { showProtocolReplace() })
    val checks = listOf(
        "out_of_house" to ("Be out of the house as much as possible" to "Spend the money if you have to - on anything that isn't addictive. Friends and social clubs most of all."),
        "delete_social" to ("Delete your social media accounts" to "Not just the apps - the accounts. Remove the pull entirely."),
        "new_background" to ("Set a new phone background" to "A clean visual reset every time you unlock."),
        "new_theme" to ("Change your app theme, if you can" to "Make the phone feel like a different, less familiar device."),
    )
    checks.forEach { (key, pair) ->
        val (t, sub) = pair
        list.addView(protocolCheckRow(key, t, sub))
    }

    // The two big moves: same tickbox card, but a gold outline (brighter gold once done).
    list.addView(protocolGoldRow("Go on holiday - without your device",
        "Step right out of the environment the habit lives in. The single biggest reset.",
        holidayDone) { showProtocolHoliday() })
    val sevenSub = when {
        strictActive -> "Active - ${Mode.daysLeft(this)} days left."
        sevenStarted -> "Completed. You can run it again any time."
        !holidayDone -> "Do the holiday first - it's what protects the fresh start."
        else -> "Lock yourself out for 7 days straight, right after the holiday."
    }
    list.addView(protocolGoldRow("Super-strict lock for a week after",
        sevenSub, sevenStarted && !strictActive) {
        if (holidayDone) showProtocol7Day()
        else Toast.makeText(this, "Do the holiday first - it's what makes the lock stick.", Toast.LENGTH_SHORT).show()
    })

    list.addView(homeCard("\uD83D\uDCA1  Additional tips", "More ways to keep the phone out of your hands.") { showProtocolTips() })

    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
    })
    setContentWithThumb(root) { showReportScreen() }
}

/** Tickbox card that reflects an external `done` state and opens a page on tap. */
private fun protocolLinkCheckRow(title: String, sub: String, done: Boolean, onClick: () -> Unit): View {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * dp; setColor(0xFFFFFFFF.toInt()); setStroke((1.5f * dp).toInt(), 0xFFD7DCE0.toInt())
        }
        val p = (16 * dp).toInt(); setPadding(p, (14 * dp).toInt(), p, (14 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * dp).toInt() }
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }
    row.addView(checkboxMarker(done, false))
    row.addView(rowTexts(title, sub))
    row.addView(TextView(this).apply { text = "\u203A"; textSize = 22f; setTextColor(0xFFAEB6BB.toInt()) })
    return row
}

/** Gold-outlined tickbox card for the two key moves; brighter gold + gold tick when done. */
private fun protocolGoldRow(title: String, sub: String, done: Boolean, onClick: () -> Unit): View {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * dp
            setColor(if (done) 0xFFFCE9A6.toInt() else 0xFFFFFFFF.toInt())     // brighter gold when done
            setStroke((2f * dp).toInt(), 0xFFD9B65A.toInt())                   // slight gold outline
        }
        val p = (16 * dp).toInt(); setPadding(p, (15 * dp).toInt(), p, (15 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * dp).toInt() }
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }
    row.addView(checkboxMarker(done, true))
    row.addView(rowTexts(title, sub))
    return row
}

/** Shared rounded-checkbox marker. gold=true uses gold theming; otherwise green. */
private fun checkboxMarker(done: Boolean, gold: Boolean): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = if (done) "\u2713" else ""; textSize = 18f; gravity = Gravity.CENTER
        includeFontPadding = false; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 7 * dp
            when {
                done && gold -> setColor(0xFFC8932B.toInt())
                done -> setColor(0xFF2E7D32.toInt())
                else -> { setColor(0xFFFFFFFF.toInt()); setStroke((2 * dp).toInt(), if (gold) 0xFFD9B65A.toInt() else 0xFFB9C0C6.toInt()) }
            }
        }
        val s = (28 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = (14 * dp).toInt() }
    }
}

/** Shared title+subtitle column for protocol rows. */
private fun rowTexts(title: String, sub: String): View {
    val dp = resources.displayMetrics.density
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@MainActivity).apply {
            text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
        })
        addView(TextView(this@MainActivity).apply {
            text = sub; textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (2 * dp).toInt(), 0, 0)
        })
    }
}

// A focused mini-page on replacing the phone's role (esp. at the bedside).
private fun showProtocolReplace() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("Replace what the phone does"))
    root.addView(TextView(this).apply {
        text = "The goal is simple: never need to bring your phone into the bedroom. If the phone isn't there at night, the highest-risk moments mostly disappear."
        textSize = 15f; setTextColor(0xFF4A4F54.toInt()); setPadding(0, 0, 0, (10 * dp).toInt())
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(protocolCheckRow("buy_alarm", "Buy a real alarm clock",
        "The single most important purchase. It removes the only honest reason to have the phone by your bed."))
    list.addView(protocolCheckRow("charge_outside", "Charge your phone in another room",
        "Pick a spot - kitchen, hallway - and make it the permanent overnight home for the phone."))
    list.addView(protocolCheckRow("buy_watch", "Wear a watch",
        "So you never reach for the phone just to check the time."))
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
    })
    setContentWithThumb(root) { showProtocol() }
}

// Read-through guidance, grouped on its own page.
private fun showProtocolTips() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("Additional tips"))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(protocolGuidanceCard("Don't bring your phone to bed or high-risk spots",
        "The bedroom, the bathroom, anywhere you've slipped before. Leave it charging in another room."))
    list.addView(protocolGuidanceCard("Change your state when an urge hits",
        "A shower, a cold blast at the end of it, a quick workout, stepping outside, a tight bedtime and wake-up routine, even a game - anything that breaks the moment and shifts how you feel."))
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
    })
    setContentWithThumb(root) { showProtocol() }
}

/** A tickable supporting to-do; persists via Protocol.isChecked. */
private fun protocolCheckRow(key: String, title: String, sub: String): View {
    val dp = resources.displayMetrics.density
    var checked = Protocol.isChecked(this, key)
    lateinit var marker: TextView
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * dp; setColor(0xFFFFFFFF.toInt()); setStroke((1.5f * dp).toInt(), 0xFFD7DCE0.toInt())
        }
        val p = (16 * dp).toInt(); setPadding(p, (14 * dp).toInt(), p, (14 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * dp).toInt() }
        isClickable = true; isFocusable = true
    }
    marker = TextView(this).apply {
        textSize = 18f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
        val s = (28 * dp).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = (14 * dp).toInt() }
    }
    fun paint() {
        marker.text = if (checked) "\u2713" else ""
        marker.setTextColor(0xFFFFFFFF.toInt())
        marker.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 7 * dp                                  // rounded checkbox, clearly tappable
            if (checked) setColor(0xFF2E7D32.toInt())
            else { setColor(0xFFFFFFFF.toInt()); setStroke((2 * dp).toInt(), 0xFFB9C0C6.toInt()) }
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

/** A tappable card styled like the task rows, but it opens a page (chevron, no checkbox). */
private fun protocolNavRow(title: String, sub: String, onClick: () -> Unit): View {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * dp; setColor(0xFFFFFFFF.toInt()); setStroke((1.5f * dp).toInt(), 0xFFD7DCE0.toInt())
        }
        val p = (16 * dp).toInt(); setPadding(p, (14 * dp).toInt(), p, (14 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * dp).toInt() }
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }
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
    row.addView(TextView(this).apply { text = "\u203A"; textSize = 22f; setTextColor(0xFFAEB6BB.toInt()) })
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
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("Rearrange your apps"))
    root.addView(body("Make the habit harder to reach by accident. Before anything else:"))
    listOf(
        "Move anything that tends to lead you in off your home screen - bury it in a folder, or remove the shortcut.",
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
    setContentWithThumb(root) { showProtocol() }
}

private fun showProtocolHoliday() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("Go on holiday / break the routine"))
    root.addView(body("The habit is wired to a place and a rhythm. The fastest way to weaken it is to physically leave that environment for a while."))
    listOf(
        "Aim for a proper break - ideally around two weeks.",
        "Go without your phone if you can, or leave it locked down the whole time.",
        "Fill the days with people, movement and daylight - not screens.",
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
    setContentWithThumb(root) { showProtocol() }
}

private fun showProtocol7Day() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("7-day strict lock"))
    root.addView(body("Strict mode stays on for 7 days. You can't switch back to relaxed until it ends. It's most effective once you've reset with the holiday - you're protecting fresh ground, not fighting uphill."))
    if (Mode.isLocked(this)) {
        root.addView(TextView(this).apply {
            text = "Active - ${Mode.daysLeft(this@MainActivity)} days left."
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
    setContentWithThumb(root) { showProtocol() }
}

private fun showReportScreen(offerLock: Boolean = false) {
    // The centred "this app isn't protected yet" popup shows every time the page is
    // ENTERED from the Temptations menu (offerLock = true) while the uninstall lock is
    // off - but not on thumb-back returns from sub-pages, or it nags on every hop.
    if (offerLock && !(UninstallGuard.isEnabled(this) && UninstallGuard.isAdminActive(this))) {
        showUnprotectedPopup()
    }
    onReportScreen = true
    subBack = null
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
    // ── top controls: mode dropdown (right) ─────────────────────────────────
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, (8 * dp).toInt())
    }
    val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    modeRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
    modeRow.addView(modeSpinner())
    // The rules live behind an (i) right next to the mode, so "what does Strict actually do?"
    // is answered where the question gets asked - not on a separate link somewhere else.
    modeRow.addView(TextView(this).apply {
        text = "ⓘ"
        textSize = 20f; setTextColor(0xFF2E9E8F.toInt())
        isClickable = true; isFocusable = true
        val p = (8 * dp).toInt(); setPadding(p, p, 0, p)
        setOnClickListener { showModeRules() }
    })
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
    // The main panes (weighted) + a thinner Statistics pane at the bottom.
    //
    // "I'm going to look anyway" is DELIBERATELY NOT HERE. A permanent button turns the wall
    // into a door with a handle, and every urge eventually tries the handle. It now appears
    // ONLY when the user has already started trying to tear the guard down (uninstall,
    // device admin, switching monitoring off, escaping a locked strict mode) - see the big
    // comment on BypassWatch, and the offer pane a few lines below. Do not put it back.
    root.addView(reportPane("Report an app/site", 0xFF34464E.toInt()) { onReportAppSite() })
    root.addView(reportPane("I feel temptation", 0xFF3E535C.toInt()) { onFeelTemptation() })
    root.addView(reportPane("Report relapse", 0xFF526D78.toInt()) { onReportRelapse() })

    // THE HONEST EXIT deliberately does NOT live on this page any more. It appears as
    // its own full screen (showBypassOffer) at the moment of a bypass attempt - see
    // maybeShowBypassOffer(). A card sitting here read as a permanent door handle.
    root.addView(reportPane("Statistics", 0xFF5E7A86.toInt()) { showStatsMenu() }.apply {
        textSize = 16f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * dp).toInt())
    })
    // Quiet on purpose: almost nobody needs these, and the ones who do will look for them.
    root.addView(TextView(this).apply {
        text = "Settings"
        textSize = 13f; gravity = Gravity.CENTER; setTextColor(0xFF8A9299.toInt())
        isClickable = true; isFocusable = true
        setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        setOnClickListener { showAdultSettings() }
    })
    setContentWithThumb(root) { reportBackTarget() }
}

/** The arming timestamp the offer has already been shown for - once per attempt. */
private var bypassOfferShownFor = 0L

/**
 * Show the supervised "look anyway" offer if a bypass attempt just happened and it
 * hasn't been shown for THIS arming yet. Returns true if it took the screen. It fires
 * at the moment of the attempt (mode spinner) or on the next app open (Settings-side
 * attempts like uninstall / device admin) - never as a card sitting on a page.
 */
private fun maybeShowBypassOffer(): Boolean {
    if (!BypassWatch.isArmed(this)) return false
    val at = BypassWatch.armedAt(this)
    if (at == bypassOfferShownFor) return false
    bypassOfferShownFor = at
    showBypassOffer()
    return true
}

/**
 * The supervised "look anyway" offer, its own full screen, shown ONLY right after the
 * user has actually gone for the uninstall / device-admin / monitoring-off route, or
 * tried to escape a locked strict mode.
 *
 * The wording names what they just did, on purpose. Pretending not to have noticed would be
 * strange and a bit insulting; they know what they were doing. The offer is: you're going to
 * get there anyway, so take the door that leaves the guard standing behind you.
 */
private fun showBypassOffer() {
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val reason = BypassWatch.lastReason(this)
    val root = vbox(pad)
    root.addView(titleText("Before you pull it all down"))
    root.addView(TextView(this).apply {
        text = buildString {
            if (reason != null) append("A moment ago, $reason.\n\n")
            append("If you're going to look, do it the honest way instead: a wait, a written ")
            append("commitment, a fixed window, and the guard still standing afterwards.\n\n")
            append("It costs you one of your ${LoosenLimit.LIFETIME_MAX} lifetime unlocks. ")
            append("Breaking the app costs you everything you've built.")
        }
        textSize = 15f; setTextColor(0xFF3A434B.toInt()); setLineSpacing(0f, 1.25f)
        setPadding(0, (4 * dp).toInt(), 0, (16 * dp).toInt())
    })
    root.addView(grow())
    root.addView(bigChoice("I'm going to look anyway  ›", 0xFFB1541F.toInt()) { onLookAnyway() })
    root.addView(Button(this).apply {
        text = "Not now"; setAllCaps(false)
        setOnClickListener { setupHomeScreen() }
    })
    setContentWithThumb(root) { setupHomeScreen() }
}

/**
 * Adult-content settings. Currently: who the filter is switched on for.
 *
 * These exist because the filter is not one-size-fits-all. A straight woman shopping for
 * lingerie, or a gay man with no interest in bikini content, should not be fighting it all
 * day. Turning a side down softens the SUGGESTIVE end only - explicit content stays blocked
 * for everyone, always, and the screen says so plainly rather than leaving them to find out.
 *
 * LOCKED outside Relaxed mode: if a switch could be flipped in strict, it would be the first
 * thing a bad night flipped.
 */
private fun showAdultSettings() {
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val canEdit = AttractionFilter.canEdit(this)
    val root = vbox(pad)
    root.addView(titleText("Settings"))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { showReportScreen() }

    c.addView(TextView(this).apply {
        text = "What the filter looks for"
        textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF9AA0A6.toInt())
        setPadding(0, 0, 0, (8 * dp).toInt())
    })
    c.addView(TextView(this).apply {
        text = "By default the filter treats sexualised images of women and of men the same. " +
            "If one of them simply isn't a temptation for you, you can turn it down — so you can " +
            "shop for swimwear or underwear without a fight.\n\n" +
            "This only softens the SUGGESTIVE end. Explicit content and known adult sites stay " +
            "blocked either way, whatever these switches say."
        textSize = 14f; setTextColor(0xFF4A4F54.toInt()); setLineSpacing(0f, 1.2f)
        setPadding(0, 0, 0, (14 * dp).toInt())
    })

    if (!canEdit) {
        c.addView(TextView(this).apply {
            text = "Locked while you're in ${AppConfig.modeName(Mode.current(this@MainActivity))} mode.\n" +
                "Both are fully on. Switch to Relaxed to change them."
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFB1541F.toInt())
            setLineSpacing(0f, 1.15f)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * dp; setColor(0xFFFBF0E7.toInt())
            }
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
        })
    }

    fun switchRow(label: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * dp; setColor(0xFFF4F6F8.toInt())
            }
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() }
            alpha = if (canEdit) 1f else 0.5f
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1F2933.toInt())
            })
            addView(TextView(this@MainActivity).apply {
                text = sub; textSize = 12f; setTextColor(0xFF7B848C.toInt())
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
        })
        row.addView(android.widget.Switch(this).apply {
            isChecked = get()
            isEnabled = canEdit
            setOnCheckedChangeListener { _, v -> set(v) }
        })
        c.addView(row)
    }

    switchRow("Block sexualised women",
        "Bikinis, lingerie, cleavage and the like.",
        { AttractionFilter.blockFemale(this) }, { AttractionFilter.setBlockFemale(this, it) })
    switchRow("Block sexualised men",
        "Shirtless, bulges and the like.",
        { AttractionFilter.blockMale(this) }, { AttractionFilter.setBlockMale(this, it) })

    c.addView(TextView(this).apply {
        text = "Health and medical pages are already treated differently — looking up a symptom " +
            "is not the same as looking at porn, and the filter knows the difference."
        textSize = 12f; setTextColor(0xFF9AA0A6.toInt()); setLineSpacing(0f, 1.15f)
        setPadding(0, (18 * dp).toInt(), 0, (20 * dp).toInt())
    })
}

/**
 * "What each mode does" - the plain-English rulebook.
 *
 * ┌──────────────────────────────────────────────────────────────────────────────────┐
 * │  AI / MAINTAINER: DO NOT let this screen drift out of date.                       │
 * │                                                                                  │
 * │  It is generated from AppConfig.ALWAYS_ON_RULES and AppConfig.MODES[].summary -   │
 * │  so there is nothing to edit HERE when behaviour changes. Edit those lists in     │
 * │  AppConfig, in the SAME change that alters the behaviour. That is the contract:   │
 * │  if a user can feel a rule, this screen must state it, in words a tired person    │
 * │  can understand at 1am.                                                           │
 * │                                                                                  │
 * │  Whenever you touch anything that branches on Mode (breathing, greyscale, block   │
 * │  thresholds, sensors, lock behaviour), re-read those lists and fix them.          │
 * └──────────────────────────────────────────────────────────────────────────────────┘
 */
private fun showModeRules() {
    inSubPage = true; onReportScreen = false
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val current = Mode.current(this)
    val root = vbox(pad)
    root.addView(titleText("The rules, in plain English"))
    root.addView(TextView(this).apply {
        text = "Everything this app does to you, and exactly when. No surprises."
        textSize = 14f; setTextColor(0xFF7B848C.toInt()); setPadding(0, 0, 0, (12 * dp).toInt())
    })

    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun sectionHeader(text: String, colour: Int) = list.addView(TextView(this).apply {
        this.text = text; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(colour); setPadding(0, (14 * dp).toInt(), 0, (8 * dp).toInt())
    })

    fun rulesCard(title: String, sub: String?, rules: List<String>, accent: Int, highlight: Boolean) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(0xFFFFFFFF.toInt())
                setStroke(((if (highlight) 2.5f else 1.5f) * dp).toInt(),
                    if (highlight) accent else 0xFFD7DCE0.toInt())
            }
            val p = (16 * dp).toInt(); setPadding(p, (14 * dp).toInt(), p, (14 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }
        }
        card.addView(TextView(this).apply {
            text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1F2933.toInt())
        })
        if (sub != null) card.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        rules.forEach { rule ->
            card.addView(TextView(this).apply {
                text = "•  $rule"
                textSize = 14f; setTextColor(0xFF3C4650.toInt())
                setLineSpacing(0f, 1.15f)
                setPadding(0, (9 * dp).toInt(), 0, 0)
            })
        }
        list.addView(card)
    }

    sectionHeader("ALWAYS ON - IN EVERY MODE", 0xFF9AA0A6.toInt())
    rulesCard("These never switch off", "Even in Relaxed.", AppConfig.ALWAYS_ON_RULES,
        0xFF2E7D32.toInt(), highlight = false)

    sectionHeader("THE MODES - WHAT CHANGES", 0xFF9AA0A6.toInt())
    AppConfig.MODES.forEach { spec ->
        val isCurrent = spec.id == current
        rulesCard(
            title = spec.displayName,
            sub = if (isCurrent) "You are in this mode right now" else null,
            rules = spec.summary,
            accent = 0xFF2E9E8F.toInt(),
            highlight = isCurrent,
        )
    }

    if (Mode.isLocked(this)) {
        list.addView(TextView(this).apply {
            text = "Strict lock is running: ${Mode.daysLeft(this@MainActivity)}. " +
                "You cannot go back to Relaxed until it ends. You can still go up to Super hardcore."
            textSize = 13f; setTextColor(0xFFB1541F.toInt()); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (6 * dp).toInt(), 0, (10 * dp).toInt())
        })
    }

    list.addView(TextView(this).apply {
        text = "\"Watched apps\" are the ones that get the breathing pause: " +
            AppConfig.BREATHING_APPS.joinToString(", ") { appLabelOrPackage(it) } + "."
        textSize = 13f; setTextColor(0xFF7B848C.toInt())
        setPadding(0, (6 * dp).toInt(), 0, (16 * dp).toInt())
    })

    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(list)
    })
    setContentWithThumb(root) { showReportScreen() }
}

/** Friendly app name for a package, falling back to the raw package if it isn't installed. */
private fun appLabelOrPackage(pkg: String): String =
    try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
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
    setContentWithThumb(root) { setupMainScreen() }

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
    root.addView(titleText("About & privacy"))
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(TextView(this).apply {
        text = getString(R.string.disclosure); textSize = 15f
    })
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(content)
    })
    setContentWithThumb(root) { setupHomeScreen() }
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

// ── Pane actions (stubs - fill these in later) ─────────────────────────────
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

// ── "I'm going to look anyway" (supervised loosen) - rebuilt ────────────────
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
        "You've already used your one unlock for today. It resets tomorrow - and that wait is doing its job."
    else
        "You've used all ${LoosenLimit.LIFETIME_MAX} of your lifetime unlocks, by your own earlier choice. You've got this without it."
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(bigPanic())
    root.addView(titleText("Not available right now"))
    root.addView(body(msg))
    root.addView(grow())
    setContentWithThumb(root) { showReportScreen() }
}

// ── intro, one idea per screen, panic taking the top third ──────────────────
private fun loosenIntro1() {
    loosenBackAction = { stopLoosenTimer(); inLoosenFlow = false; showReportScreen() }
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(boldWordTitle("This is a supervised unlock, only for times of desperation.", "desperation"))
    root.addView(TextView(this).apply {
        text = "${LoosenLimit.remaining(this@MainActivity)} of ${LoosenLimit.LIFETIME_MAX} unlocks available"
        textSize = 15f; setTextColor(0xFF4A4F54.toInt()); setPadding(0, (8 * dp).toInt(), 0, 0)
    })
    root.addView(TextView(this).apply {
        text = "Every urge works the same way - it spikes hard, then fades. People who wait it out almost always find it's gone in minutes."
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
    p < 40 -> "Very hard - but not impossible"
    p < 60 -> "Could honestly go either way"
    p < 80 -> "I think I can hold off"
    else -> "I've got this - 30 minutes is nothing"
}

// ── Screen D: is this a one-off? how it shapes the future ───────────────────
private fun loosenOneOffScreen() {
    loosenBackAction = { loosenDelayChanceScreen() }
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText("Is this really a one-off?"))
    root.addView(body("Each unlock nudges your brain back toward the old wiring. \u201cJust this once\u201d is exactly how the pattern keeps itself alive."))
    root.addView(RecoveryBrainView(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (resources.displayMetrics.density * 8).toInt(), 0, (resources.displayMetrics.density * 4).toInt())
    }
    list.addView(pickCard("Yes - genuinely a one-off") { loosenOneOffFollow(true) }.apply { gravity = Gravity.CENTER })
    root.addView(list)
    root.addView(panicBar())
    setContentView(root)
}

private fun loosenOneOffFollow(oneOff: Boolean) {
    loosenBackAction = { loosenOneOffScreen() }
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(if (oneOff) "Then waiting costs you nothing" else "Then let this be where it breaks"))
    root.addView(body(if (oneOff)
        "If it's truly just once, 30 minutes won't change that - except you'll have it behind you, clean, with every unlock still in the bank."
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
            resp.text = "That's right - you only have to beat the next 5 minutes. That's all, and it trains you for life."
            enableLink(cont)
        } else {
            resp.text = "Not quite - you've actually passed the peak already. Tap again, further along, where the urge is fading."
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
    loosenBackAction = { loosenStop("You stepped back from it - nothing's been used up. The wait was already working.") }
    if (!LoosenWait.isActive(this)) LoosenWait.start(this, 5L * 60 * 1000)
    val endAt = System.currentTimeMillis() + LoosenWait.remaining(this)
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
    }
    content.addView(TextView(this).apply {
        text = "A short wait first"; textSize = 21f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    // the only time readout - updates each minute, no ticking seconds
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
    content.addView(GlowButton(this, "Lock me out for 5 mins - I can do this") { showLoosenLongerDialog() }.apply {
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
    val doneContinue = continueLink("I've waited - continue") { loosenCommitStart() }
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
    root.addView(stepText(step))
    root.addView(titleText("What will you look at?"))
    root.addView(TextView(this).apply {
        text = "Private - stays on this device."; textSize = 13f; setTextColor(0xFF6B7075.toInt())
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
    // They took the honest exit. Take the offer back off the table - it must not still be
    // sitting there afterwards, or it stops being a last resort and becomes a menu item.
    BypassWatch.disarm(this)
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
private fun panicButton(): Button = bigChoice("PANIC - I need to stop", 0xFFB00020.toInt()) { openPanic() }

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
// (The old green "Back" pill that used to sit at the top of pages lived here. It is gone -
//  every page gets the thumb back button automatically now. See setContentView below.)

// The floating thumb back button: a custom-drawn circle + arrow, so the arrow is
// geometrically centred (no font-baseline drift).
private fun thumbBack(onBack: () -> Unit): View =
    ThumbBackView(this).apply { isClickable = true; isFocusable = true; setOnClickListener { onBack() } }

// =====================================================================================
//  THE ONE BACK BUTTON
// =====================================================================================
//  Every page in this app gets the floating translucent thumb back button, automatically,
//  because setContentView is overridden below to wrap whatever you pass it. There is
//  nothing to remember and nothing to add per page.
//
//  DO NOT add a back button to a page. No "Back" buttons, no "← Back" links, nothing in
//  the top-left, top-centre or top-right. They kept drifting out of sync with where back
//  actually goes, and half of them were wrong. If you catch yourself writing one, stop -
//  it already exists.
//
//  The thumb routes through onBackPressed(), which is the single source of truth for where
//  back goes (active flow -> subBack -> tab -> home). So a page only has to declare its
//  back target once, via setContentWithThumb, and the thumb follows automatically.
//
//  The ONLY page without it is the landing screen, which uses setContentNoThumb - there is
//  nowhere behind it to go.
// =====================================================================================

/** Set while rendering the landing screen, the one page with nothing behind it. */
private var noThumb = false

/** The landing screen: rendered raw, with no thumb back button. */
private fun setContentNoThumb(content: View) {
    noThumb = true
    try { setContentView(content) } finally { noThumb = false }
}

override fun setContentView(view: View) {
    if (noThumb) { super.setContentView(view); return }
    val dp = resources.displayMetrics.density
    val frame = android.widget.FrameLayout(this).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    frame.addView(view, android.widget.FrameLayout.LayoutParams(
        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
    val size = (54 * dp).toInt()
    frame.addView(
        thumbBack { onBackPressed() },
        android.widget.FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = (resources.displayMetrics.heightPixels * 0.20f).toInt()
            marginEnd = (16 * dp).toInt()
        },
    )
    super.setContentView(frame)
}

// Declares where THIS page's back goes (subBack), then renders it. The thumb button is
// added by the setContentView override above - this no longer adds one itself.
private fun setContentWithThumb(content: View, onBack: () -> Unit) {
    onReportScreen = false; onTemptationsTab = false; onDevScreen = false
    inRelapseFlow = false; inTemptationFlow = false; inLoosenFlow = false; inAppSiteFlow = false
    inSubPage = true
    subBack = onBack
    setContentView(content)
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
        setOnClickListener { showReportScreen() }
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
    "Overwhelming" to "I feel I can't control it - like it's inevitable I'll give in.",
    "Strong" to "Hard to think about much else right now.",
    "Noticeable" to "Clearly there, but I can still steer around it.",
    "Mild" to "A small pull - easy to set aside.",
    "Barely there" to "Just a flicker - it barely registers.",
)

private fun urgeScaleScreen(title: String, onBack: (() -> Unit)?, onPick: (String) -> Unit) {
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
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
        sensorMonitor?.stop(); sensorMonitor = null
        // Don't scan for beacons (or read the barometer) with the screen off; the
        // debug page's tick restarts both on resume.
        beaconScanner?.stop(); pressureMon?.stop()
        // Don't leave a breathing orb posting frame callbacks at a screen nobody is looking at.
        habitOrb?.stop(); habitOrb = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            inRelapseFlow -> relapseBack()
            inTemptationFlow -> temptationBack()
            inLoosenFlow -> loosenBack()
            inAppSiteFlow -> appSiteBack()
            subBack != null -> subBack!!.invoke()
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

    // The full-screen uninstall-lock prompt shows only during FIRST setup (persisted
    // below). The arousal page instead shows a dismissible centred popup EVERY time it
    // opens while unprotected (showUnprotectedPopup) - the home page nags nowhere.
    private fun lockPromptSeen(): Boolean =
        getSharedPreferences("setup", Context.MODE_PRIVATE).getBoolean("lock_prompt_seen", false)
    private fun markLockPromptSeen() =
        getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("lock_prompt_seen", true).apply()
    private var onReportScreen = false
    private var inSubPage = false
    private var onHomeScreen = false
    private var onTemptationsTab = false
    private var onDevScreen = false
    private var subBack: (() -> Unit)? = null
    private var sensorMonitor: SensorMonitor? = null
    private var beaconScanner: BeaconScanner? = null
    private var pressureMon: PressureMonitor? = null
    // The beacon pages' UI ticker. One shared handle so each page (and the wizard)
    // kills the previous page's ticker instead of leaking it across navigation.
    private var beaconUi: Handler? = null
    private var reportBackTarget: () -> Unit = { showTemptationsTab() }

    private fun currentStep(): Step = when {
        !isAccessibilityEnabled()       -> Step.MONITORING
        !Settings.canDrawOverlays(this) -> Step.OVERLAY
        !AppConfig.DEV_MODE && !lockPromptSeen() -> Step.LOCK
        else                            -> Step.READY
    }

    private fun updateScreen() {
        val step = currentStep()
        // A bypass attempt in Settings (uninstall, device admin, monitoring-off) can't
        // show UI from the service; it lands here on the next app open, once per attempt.
        if (step == Step.READY && maybeShowBypassOffer()) { shownStep = step; return }
        if (step == Step.READY && shownStep == Step.READY) {
            renderStatus()   // already on the main screen - just refresh the dots
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
                { openAccessibilitySettings() },
            )
            Step.OVERLAY -> showPrereq(
                "Step 2 of 3\nAllow the block screen",
                "This lets the app draw the blocking screen on top of other apps.\n\n" +
                    "When you tap Continue, find \u201CWeb Traffic Monitor\u201D in the list and " +
                    "turn its toggle ON.\n\nThen come back to this app.",
                "Continue to \u201CAppear on top\u201D",
                { requestOverlayPermission() },
            )
            Step.LOCK -> showLockPrompt { markLockPromptSeen(); updateScreen() }
            Step.READY -> setupHomeScreen()
        }
    }

    // "This app isn't protected yet" - a centred popup shown on the adult-content page
    // (showReportScreen) every time it opens while the uninstall lock is off. X to close.
    private fun showUnprotectedPopup() {
        val dp = resources.displayMetrics.density
        val dialog = AlertDialog.Builder(this).create()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * dp; setColor(0xFFFFF8EC.toInt()); setStroke((1.5f * dp).toInt(), 0xFFE0A63C.toInt())
            }
            val p = (18 * dp).toInt(); setPadding(p, (10 * dp).toInt(), p, p)
        }
        card.addView(TextView(this).apply {
            text = "\u2715"; textSize = 18f; setTextColor(0xFF8A6D3B.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END }
            val t = (8 * dp).toInt(); setPadding(t, t, t, t)
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })
        card.addView(TextView(this).apply {
            text = "\u26A0  This app isn't protected yet"; textSize = 17f
            setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF7A4F00.toInt())
        })
        card.addView(TextView(this).apply {
            text = "Without the uninstall lock you can delete this app in a weak moment - " +
                "and this page is exactly where those moments happen."
            textSize = 13f; setTextColor(0xFF8A6D3B.toInt()); setPadding(0, (6 * dp).toInt(), 0, (12 * dp).toInt())
        })
        card.addView(bigChoice("Turn on the uninstall lock", 0xFF2E7D32.toInt()) {
            dialog.dismiss()
            UninstallGuard.setEnabled(this, true)
            startActivity(UninstallGuard.activationIntent(this))
        })
        dialog.setView(card)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.show()
    }

    // The uninstall-lock prompt, reused by: first-setup gate, the arousal page, and the
    // dev-mode preview button. `onDone` runs after the user enables or skips.
    private fun showLockPrompt(onDone: () -> Unit) {
        if (UninstallGuard.isAdminActive(this)) {
            showPrereq(
                "Uninstall lock - ON",
                "Protection is active: the app can't be uninstalled, and the settings " +
                    "pages that would switch it off are blocked.\n\nYou can turn it off " +
                    "from the main screen (you'll need the passcode).",
                "Continue",
                { onDone() },
            )
        } else {
            showPrereq(
                "Protect this app",
                "Turn on the uninstall lock so you can't delete the app in a weak moment. " +
                    "While it's on, the app can't be uninstalled and the settings pages that " +
                    "would switch it off are blocked.",
                "Enable uninstall lock",
                {
                    UninstallGuard.setEnabled(this, true)
                    startActivity(UninstallGuard.activationIntent(this))
                    onDone()
                },
                "Skip for now",
                { onDone() },
            )
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
        content.addView(titleText("Developer tools"))
        content.addView(TextView(this).apply {
            text = "Diagnostics and block-rule management. Not shown to end users when dev mode is off."
            textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, 0, 0, (10 * dp).toInt())
        })
        content.addView(homeCard("System console", "Current mode, thresholds, and what's on or off.") { showDevConsole() })
        content.addView(homeCard("Sensor debug", "Live tilt / lying-down and ambient light readings.") { showSensorDebug() })
        content.addView(homeCard("Room detection (beacons)", "Bedroom / bathroom true-false from the K11 beacons, plus assignment and calibration.") { showRoomBeaconDebug() })
        content.addView(homeCard("Grayscale setup", "Turn on the strict-mode grayscale filter.") { showGreyscaleSetup() })
        content.addView(homeCard("Preview uninstall prompt", "See the lock prompt (it's hidden in dev mode).") { showLockPrompt { setupMainScreen() } })
        content.addView(homeCard("Recent blocks", "What's been blocked lately.") { showRecentBlocks() })
        content.addView(homeCard("Manage block rules", "Add or remove blocked sites and apps.") { showManageRules() })
        content.addView(homeCard("View log", "The full monitoring log.") { showLogPage() })
        // The supervised loosen flow is no longer reachable from the adult-content page (see
        // BypassWatch). It lives here so it can still be tested without staging a fake
        // uninstall attempt.
        content.addView(homeCard("\"I'm going to look anyway\" flow",
            "Hidden from users unless they're caught trying to bypass. Test it here.") {
            onLookAnyway()
        })
        content.addView(homeCard("Bypass watch",
            if (BypassWatch.isArmed(this))
                "ARMED - ${BypassWatch.remaining(this) / 60_000} min left · ${BypassWatch.totalAttempts(this)} attempt(s) ever"
            else
                "Not armed · ${BypassWatch.totalAttempts(this)} attempt(s) ever. Tap to arm it.") {
            BypassWatch.record(this, "you armed it from Developer tools")
            Toast.makeText(this, "Bypass watch armed for 30 min", Toast.LENGTH_SHORT).show()
            setupMainScreen()
        })
        content.addView(homeCard("Clear block rules", "Wipe all block rules and strikes.") {
            BlockRules.clear(this); BlockEscalation.clear(this); AppTimedBlock.clear(this)
            Toast.makeText(this, "Block rules cleared", Toast.LENGTH_SHORT).show()
        })

        val root = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true; addView(content)
        }
        setContentWithThumb(root) { setupHomeScreen() }
    }

    // In-app flow to get the user to turn grayscale on (the app can't do it itself).
    private fun showGreyscaleSetup() {
        val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText("Grayscale in strict mode"))
        root.addView(TextView(this).apply {
            text = "Colour is a big part of what makes feeds and images pull at you. Making the whole " +
                "screen grayscale strips that out - simple, and surprisingly powerful.\n\n" +
                "Android won't let an app switch grayscale on for you (it's a protected system " +
                "setting), so you turn it on once yourself. In strict mode, keep it on."
            textSize = 14f; setTextColor(0xFF52606A.toInt()); setPadding(0, 0, 0, (14 * dp).toInt())
        })
        val status = TextView(this).apply {
            textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, (14 * dp).toInt())
        }
        root.addView(status)
        val on = Greyscale.isOn(this)
        status.text = if (on) "Grayscale is ON \u2713" else "Grayscale is OFF"
        status.setTextColor(if (on) 0xFF2E7D32.toInt() else 0xFFB00020.toInt())

        root.addView(bigChoice("Open display settings", 0xFF2E9E8F.toInt()) { Greyscale.openGrayscaleSetting(this) })
        root.addView(TextView(this).apply {
            text = "How to turn it on:\n" +
                "1. Open Settings \u2192 Accessibility.\n" +
                "2. Go to Vision enhancements (called Colour and motion on some phones).\n" +
                "3. Tap Colour correction and toggle the slider On.\n" +
                "4. Scroll to the bottom and choose Greyscale."
            textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (12 * dp).toInt(), 0, (16 * dp).toInt())
        })

        // Optional lock: block the Colour-correction page so greyscale can't be turned off.
        val lockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * dp; setColor(0xFFFFFFFF.toInt()); setStroke((1.5f * dp).toInt(), 0xFFD7DCE0.toInt())
            }
            val p = (14 * dp).toInt(); setPadding(p, p, p, p)
        }
        lockRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = "Lock the Colour correction page"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
            })
            addView(TextView(this@MainActivity).apply {
                text = "Once greyscale is on, block that Settings page so it can't be turned back off. Turn this off here first if you need to change it."
                textSize = 12f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (2 * dp).toInt(), 0, 0)
            })
        })
        lockRow.addView(android.widget.Switch(this).apply {
            isChecked = Greyscale.isLockColorPage(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Greyscale.setLockColorPage(this@MainActivity, on) }
        })
        root.addView(lockRow)
        setContentWithThumb(root) { setupMainScreen() }
    }

    // Live sensor readout for tuning the lying-down + light heuristics.
    private fun showSensorDebug() {
        val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText("Sensor debug"))
        root.addView(TextView(this).apply {
            text = "Live readings. Tilt/lying-down come from the accelerometer; light from the ambient light sensor."
            textSize = 13f; setTextColor(0xFF7B848C.toInt()); setPadding(0, 0, 0, (12 * dp).toInt())
        })

        fun bigLine() = TextView(this).apply { textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt()); setPadding(0, (6 * dp).toInt(), 0, 0) }
        fun subLine() = TextView(this).apply { textSize = 14f; setTextColor(0xFF52606A.toInt()) }

        fun badge() = TextView(this).apply {
            textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            val p = (8 * dp).toInt(); setPadding(p * 2, p, p * 2, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (5 * dp).toInt(); bottomMargin = (5 * dp).toInt() }
        }
        root.addView(sectionTitle("Posture"))
        val lyingBadge = TextView(this).apply {
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            val p = (10 * dp).toInt(); setPadding(p * 2, p, p * 2, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * dp).toInt(); bottomMargin = (6 * dp).toInt() }
        }
        root.addView(lyingBadge)
        val leftBadge = badge(); val rightBadge = badge(); val backBadge = badge()
        root.addView(leftBadge); root.addView(rightBadge); root.addView(backBadge)
        val tiltLine = subLine(); val rollLine = subLine(); val gLine = subLine()
        root.addView(tiltLine); root.addView(rollLine); root.addView(gLine)

        root.addView(sectionTitle("Ambient light"))
        val luxLine = bigLine(); val levelLine = subLine()
        root.addView(luxLine); root.addView(levelLine)

        val note = TextView(this).apply { textSize = 12f; setTextColor(0xFF9AA0A6.toInt()); setPadding(0, (16 * dp).toInt(), 0, 0) }
        root.addView(note)

        root.addView(sectionTitle("Greyscale"))
        val greyLine = subLine(); val greyHint = TextView(this).apply { textSize = 12f; setTextColor(0xFF9AA0A6.toInt()); setPadding(0, (2 * dp).toInt(), 0, 0) }
        root.addView(greyLine); root.addView(greyHint)

        sensorMonitor?.stop()
        val monitor = SensorMonitor(this)
        sensorMonitor = monitor

        fun refresh() {
            val lying = monitor.lyingDown
            lyingBadge.text = if (lying) "  Lying down  " else "  Upright  "
            lyingBadge.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20 * dp; setColor(if (lying) 0xFF2E9E44.toInt() else 0xFFB9C0C6.toInt())
            }
            fun paint(tv: TextView, on: Boolean, label: String) {
                tv.text = if (on) "  $label  \u2713  " else "  $label  "
                tv.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 18 * dp; setColor(if (on) 0xFF2E9E44.toInt() else 0xFFCBD1D6.toInt())
                }
            }
            paint(leftBadge, monitor.onLeftSide, "Lying on left side")
            paint(rightBadge, monitor.onRightSide, "Lying on right side")
            paint(backBadge, monitor.onBack, "Lying on back")
            tiltLine.text = "Tilt from upright: ${Math.round(monitor.tiltDeg)}\u00b0"
            rollLine.text = "Side roll: ${Math.round(monitor.rollDeg)}\u00b0"
            gLine.text = "Gravity  x ${String.format("%.2f", monitor.gX)}  y ${String.format("%.2f", monitor.gY)}  z ${String.format("%.2f", monitor.gZ)}" +
                "  screen ${monitor.screenFacing}   (side |x|\u2265${AppConfig.SIDE_GX}, back z\u2264-${AppConfig.BACK_GZ})"
            if (monitor.lux < 0f) { luxLine.text = "- lux"; levelLine.text = "waiting for light sensor\u2026" }
            else {
                luxLine.text = "${Math.round(monitor.lux)} lux"
                levelLine.text = "Level: ${monitor.lightLevel?.name ?: "-"}"
            }
            val bits = mutableListOf<String>()
            if (!monitor.hasAccel) bits.add("no accelerometer")
            if (!monitor.hasLight) bits.add("no light sensor")
            note.text = if (bits.isEmpty()) "Both sensors present." else "Missing: ${bits.joinToString(", ")}"
            val perm = Greyscale.canControl(this@MainActivity)
            greyLine.text = "Grayscale currently ${if (Greyscale.isOn(this@MainActivity)) "ON" else "off"}" +
                (if (perm) "  (app can auto-toggle)" else "")
            greyHint.text = if (perm) "System-controlled build: app switches it in strict mode."
                else "Normal build: enable it in Settings - see 'Grayscale setup' on the dev page."
        }
        monitor.onUpdate = { runOnUiThread { refresh() } }
        monitor.start(); refresh()

        setContentWithThumb(root) { monitor.stop(); sensorMonitor = null; setupMainScreen() }
    }

    // ── Room detection (KKM K11 beacons) ─────────────────────────────────────
    // One card per room (rooms are user-defined, 2-8 beacons): a true / maybe (probs
    // is) / maybe (probs not) / false pill, a big live dBm readout, one red-amber-
    // green meter PER BEACON (zones learned per room), and the check bars. The
    // decision is PAIRWISE: the current tuple of all beacons' levels is matched
    // against recorded spots - see RoomBeacons.kt.
    private fun showRoomBeaconDebug() {
        if (!RoomBeacons.hasPermissions(this)) {
            showPrereq(
                "Allow Bluetooth scanning",
                "To hear the room beacons the app needs to scan for nearby Bluetooth " +
                    "devices. Android will ask for Nearby devices and Location - it treats " +
                    "beacon scanning as location, which is exactly what it's used for here " +
                    "(which room you're in). Nothing leaves the phone.\n\n" +
                    "If Android has stopped asking, grant them from Settings → Apps → " +
                    "this app → Permissions.",
                "Grant permissions",
                { requestPermissions(RoomBeacons.requiredPermissions(), REQ_BEACON_PERMS) },
                "Back", { setupMainScreen() },
            )
            return
        }

        val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        content.addView(titleText("Room detection"))

        content.addView(Button(this).apply {
            text = "How we determine what room you're in ›"; setAllCaps(false)
            setOnClickListener { showRoomHowItWorks() }
        })

        // Scan health, always visible: the "is data actually flowing" line.
        val scanLine = TextView(this).apply { textSize = 12f; setTextColor(0xFF7B848C.toInt()); setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt()) }
        content.addView(scanLine)

        fun warnLine(msg: String, onClick: () -> Unit) = TextView(this).apply {
            text = msg; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFB00020.toInt()); setPadding(0, 0, 0, (8 * dp).toInt())
            visibility = View.GONE; isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val btWarn = warnLine("Bluetooth is OFF - tap here to turn it on.") {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        val locWarn = warnLine("Location is OFF - Android hides beacons until it's on. Tap here.") {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        content.addView(btWarn); content.addView(locWarn)

        beaconScanner?.stop()
        val scanner = BeaconScanner(this)
        beaconScanner = scanner
        pressureMon?.stop()
        val press = PressureMonitor(this).also { it.start() }
        pressureMon = press
        RoomPresence.reset()
        beaconUi?.removeCallbacksAndMessages(null)
        val ui = Handler(Looper.getMainLooper()); beaconUi = ui

        fun roomTitle(room: String) = room.replaceFirstChar { it.uppercase() }

        class RoomCard(val room: String) {
            val pill = TextView(this@MainActivity).apply {
                textSize = 16f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                val p = (8 * dp).toInt(); setPadding(p * 2, p / 2, p * 2, p / 2)
            }
            val big = TextView(this@MainActivity).apply {
                textSize = 34f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
            }
            val sub = TextView(this@MainActivity).apply { textSize = 12f; setTextColor(0xFF9AA0A6.toInt()) }
            val metersBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            val meterRows = mutableListOf<Pair<TextView, SignalMeterView>>()
            val checksBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            val checkRows = mutableListOf<Triple<LinearLayout, TextView, TextView>>()
            val summary = TextView(this@MainActivity).apply { textSize = 13f; setTextColor(0xFF52606A.toInt()); setPadding(0, (6 * dp).toInt(), 0, 0) }
            val setupBtn = Button(this@MainActivity).apply { setAllCaps(false) }
        }
        val cards = RoomBeacons.rooms(this).map { RoomCard(it) }

        // One labelled red/amber/green meter per beacon, zones specific to this room.
        fun renderMeters(card: RoomCard, meters: List<RoomPresence.MeterData>) {
            if (card.meterRows.size != meters.size) {
                card.metersBox.removeAllViews(); card.meterRows.clear()
                repeat(meters.size) {
                    val label = TextView(this).apply {
                        textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, (6 * dp).toInt(), 0, 0)
                    }
                    val meter = SignalMeterView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, (38 * dp).toInt(),
                        )
                    }
                    card.meterRows.add(label to meter)
                    card.metersBox.addView(label); card.metersBox.addView(meter)
                }
            }
            meters.forEachIndexed { i, m ->
                val (label, meter) = card.meterRows[i]
                val avg6 = scanner.robustRssi(m.mac, 6_000)
                label.text = "${m.label}:  ${m.current?.let { "$it dBm" } ?: "not heard"}" +
                    (avg6?.let { "  ·  6s avg $it" } ?: "")
                label.setTextColor(when (m.state) {
                    2 -> 0xFF1B5E20.toInt(); 1 -> 0xFF2E7D32.toInt()
                    -1 -> 0xFFC0392B.toInt(); else -> 0xFF9A7B00.toInt()
                })
                meter.update(m.current, m.zone, m.openTop)
            }
        }

        fun renderChecks(card: RoomCard, checks: List<RoomPresence.Check>) {
            if (card.checkRows.size != checks.size) {
                card.checksBox.removeAllViews(); card.checkRows.clear()
                repeat(checks.size) {
                    val label = TextView(this).apply {
                        textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val value = TextView(this).apply { textSize = 11f; setTextColor(0xFFFFFFFF.toInt()) }
                    val bar = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        val p = (7 * dp).toInt(); setPadding((10 * dp).toInt(), p, (10 * dp).toInt(), p)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = (4 * dp).toInt() }
                        addView(label); addView(value)
                    }
                    card.checkRows.add(Triple(bar, label, value))
                    card.checksBox.addView(bar)
                }
            }
            checks.forEachIndexed { i, c ->
                val (bar, label, value) = card.checkRows[i]
                label.text = c.label
                value.text = c.value
                bar.background = GradientDrawable().apply {
                    cornerRadius = 10 * dp
                    setColor(when (c.state) { 1 -> 0xFF2E9E44.toInt(); -1 -> 0xFFC0392B.toInt(); else -> 0xFFE0A800.toInt() })
                }
            }
        }

        for (card in cards) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 16 * dp; setColor(0xFFFFFFFF.toInt()); setStroke((1.5f * dp).toInt(), 0xFFD7DCE0.toInt())
                }
                val p = (14 * dp).toInt(); setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (10 * dp).toInt() }
            }
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            header.addView(TextView(this).apply {
                text = roomTitle(card.room); textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(card.pill)
            box.addView(header)
            box.addView(card.big); box.addView(card.sub)
            box.addView(card.metersBox)
            box.addView(card.checksBox); box.addView(card.summary)

            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (6 * dp).toInt(), 0, 0) }
            card.setupBtn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
            card.setupBtn.setOnClickListener { showRoomSetup(card.room) }
            buttons.addView(card.setupBtn)
            buttons.addView(Button(this).apply {
                text = "Reset…"; setAllCaps(false)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Reset ${roomTitle(card.room)}?")
                        .setMessage(
                            "Reset forgets this room's beacon and calibration (you'd redo " +
                                "set-up). Remove deletes the room from the list entirely. " +
                                "The beacon hardware itself isn't touched either way.",
                        )
                        .setPositiveButton("Reset") { _, _ ->
                            RoomBeacons.setBeaconMac(this@MainActivity, card.room, null)
                            RoomPresence.reset()
                        }
                        .setNeutralButton("Remove room") { _, _ ->
                            RoomBeacons.removeRoom(this@MainActivity, card.room)
                            showRoomBeaconDebug()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            })
            box.addView(buttons)
            content.addView(box)
        }

        // Rooms are user-defined: anywhere from 2 to 8 beacons depending on the house.
        content.addView(Button(this).apply {
            text = "Add a room…"; setAllCaps(false)
            setOnClickListener {
                val input = EditText(this@MainActivity).apply {
                    hint = "Room name (e.g. office)"
                    val p = (16 * dp).toInt(); setPadding(p, p, p, p)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Add a room")
                    .setView(input)
                    .setPositiveButton("Add") { _, _ ->
                        if (RoomBeacons.addRoom(this@MainActivity, input.text.toString())) showRoomBeaconDebug()
                        else Toast.makeText(this@MainActivity,
                            "Couldn't add that (empty, duplicate, or ${RoomBeacons.MAX_ROOMS}-room limit)",
                            Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        })

        // Dual-sensor mode: two beacons per room, opposite ends, for tighter readings.
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            addView(TextView(this@MainActivity).apply {
                text = "Dual sensors per room (two per room, opposite ends - rooms need re-setup after switching)"
                textSize = 13f; setTextColor(0xFF52606A.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(android.widget.Switch(this@MainActivity).apply {
                isChecked = RoomBeacons.dualMode(this@MainActivity)
                setOnCheckedChangeListener { _, on -> RoomBeacons.setDualMode(this@MainActivity, on) }
            })
        })

        // Raw feed of every advertiser - developer diagnostics only, hidden until asked.
        val feed = TextView(this).apply {
            textSize = 11f; setTextColor(0xFF52606A.toInt()); typeface = Typeface.MONOSPACE
            setPadding(0, (4 * dp).toInt(), 0, (16 * dp).toInt()); visibility = View.GONE
        }
        val feedToggle = Button(this).apply {
            text = "Show raw scanner feed (dev)"; setAllCaps(false)
            setOnClickListener {
                val show = feed.visibility != View.VISIBLE
                feed.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "Hide raw scanner feed" else "Show raw scanner feed (dev)"
            }
        }
        content.addView(feedToggle); content.addView(feed)

        fun locationOn(): Boolean = try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
            else Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, 0) != Settings.Secure.LOCATION_MODE_OFF
        } catch (_: Throwable) { true }

        fun refresh() {
            val now = System.currentTimeMillis()
            btWarn.visibility = if (scanner.isBluetoothOn) View.GONE else View.VISIBLE
            locWarn.visibility = if (locationOn()) View.GONE else View.VISIBLE
            scanner.expectedMacs = RoomBeacons.allAssignedMacs(this).toSet()
            if (scanner.isBluetoothOn) scanner.ensureScanning()
            press.start()   // idempotent; restarts the barometer after onStop
            val err = scanner.lastScanError
            scanLine.text = when {
                !scanner.isBluetoothOn -> "Not scanning - Bluetooth is off"
                err != null -> "Scan problem (Android error $err) - retrying automatically…"
                else -> {
                    val heard = scanner.all().count { now - it.lastSeen <= RoomBeacons.TIMEOUT_MS }
                    val guard = when {
                        RoomGuard.activeRoom != null -> "guard BLOCKING (${RoomGuard.activeRoom})"
                        RoomGuard.armed -> "guard armed"
                        else -> "guard idle (needs strict mode + a calibrated room)"
                    }
                    "Scanning · ${String.format("%.1f", scanner.advertsPerSec)} adverts/s from $heard devices" +
                        (if (press.available) " · barometer on" else " · no barometer") + " · " + guard
                }
            }
            scanLine.setTextColor(if (err != null || !scanner.isBluetoothOn) 0xFFB00020.toInt() else 0xFF7B848C.toInt())

            val statuses = RoomPresence.evaluate(this, scanner, press)
            for (card in cards) {
                val st = statuses[card.room] ?: continue
                card.pill.text = when (st.verdict) {
                    RoomPresence.Verdict.IN -> "  true  "
                    RoomPresence.Verdict.MAYBE_IN -> "  maybe (probs is)  "
                    RoomPresence.Verdict.MAYBE_OUT -> "  maybe (probs not)  "
                    RoomPresence.Verdict.OUT -> "  false  "
                }
                card.pill.background = GradientDrawable().apply {
                    cornerRadius = 20 * dp
                    setColor(when (st.verdict) {
                        RoomPresence.Verdict.IN -> 0xFF2E9E44.toInt()
                        RoomPresence.Verdict.MAYBE_IN -> 0xFFE0A800.toInt()
                        RoomPresence.Verdict.MAYBE_OUT -> 0xFF8A6D3B.toInt()
                        RoomPresence.Verdict.OUT -> 0xFF9AA0A6.toInt()
                    })
                }
                card.big.text = st.rssi?.let { "$it dBm" } ?: "–– dBm"
                card.sub.text = when {
                    !st.assigned -> "No beacon assigned"
                    st.rssi == null -> "Beacon assigned, not heard yet"
                    else -> "Kalman-filtered · raw ${st.rawRssi} dBm"
                }
                renderMeters(card, st.meters)
                renderChecks(card, st.checks)
                card.summary.text = st.summary
                card.summary.visibility = if (st.summary.isEmpty()) View.GONE else View.VISIBLE
                card.setupBtn.text = if (st.calibrated) "Recalibrate" else "Set up this room"
            }

            if (feed.visibility == View.VISIBLE) {
                val rows = scanner.all().take(14).map { b ->
                    val age = (now - b.lastSeen) / 1000
                    val name = b.name ?: (if (b.iBeaconUuid != null) "iBeacon" else "-")
                    "${b.mac} ${String.format("%4d", b.rssi)}/${String.format("%4d", Math.round(b.smoothedRssi))}dBm ${age}s $name"
                }
                feed.text = if (rows.isEmpty()) "listening… (nothing heard yet)" else rows.joinToString("\n")
            }
        }

        ui.post(object : Runnable {
            override fun run() {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) refresh()
                ui.postDelayed(this, 300)
            }
        })
        scanner.ensureScanning(); refresh()

        val root = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true; addView(content)
        }
        setContentWithThumb(root) {
            ui.removeCallbacksAndMessages(null); beaconUi = null
            scanner.stop(); beaconScanner = null
            press.stop(); pressureMon = null
            setupMainScreen()
        }
    }

    // Plain-language explanation page. Deliberately basic - simple bullets, no styling.
    private fun showRoomHowItWorks() {
        val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText("How we determine what room you're in"))
        fun section(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
            setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
        })
        fun bullets(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 14f; setTextColor(0xFF3A434B.toInt())
        })
        section("Primary checks")
        bullets(
            "- We measure the current signal strength from every beacon in the house at once (any number of beacons, one per room).\n" +
                "- Raw Bluetooth readings are noisy, so every signal is run through a Kalman filter - a rolling estimate that strips the noise out. A single reading never drives the answer.\n" +
                "- Readings come as a set - one value per beacon - and during set-up we record what that whole set looks like from many spots inside and outside each room.\n" +
                "- A spot only counts as matched when EVERY beacon is close to what was recorded there. One beacon looking right while another is off means you're somewhere else that merely resembles the room.\n" +
                "- 'true' only when the current set matches one of the spots where you'd actually use the phone. Being even closer to the room's beacon than usual always counts.",
        )
        section("Backup checks")
        bullets(
            "- When the picture is mixed you get 'maybe (probs is)' or 'maybe (probs not)', depending on how close the current readings are to anything recorded inside the room.\n" +
                "- The 'false reading' spots you tag are remembered: match one of those better than any inside spot and the answer is false.\n" +
                "- Outliers are removed from the calibration data before the expected ranges are built.\n" +
                "- A barometer check notices when you've just gone up or down a floor and blocks 'true' right after.\n" +
                "- The answer has to hold steady for a moment before it changes, so one noisy reading can't flip it.",
        )
        section("What happens in strict mode")
        bullets(
            "- Once at least one room is set up and Bluetooth permissions are granted, strict mode turns on the room guard automatically.\n" +
                "- While the app is confident you're in a protected room, every app except the essentials (calls, texts, clock, camera, maps, home screen) is covered by a block screen naming the room.\n" +
                "- Step out of the room and everything unlocks by itself within a couple of seconds.\n" +
                "- If the beacons can't be heard, or permissions are missing, nothing is blocked - the guard never locks you out on a guess.",
        )
        section("Where to put the sensors")
        bullets(
            "- Put each beacon closest to where the risk actually is. In a bedroom: at the bed - the headboard or bedside table. This is the most important rule.\n" +
                "- In a bathroom: the back of a cupboard, or a shelf near where the phone would get used.\n" +
                "- Anywhere works as long as it never moves. If you move a beacon, redo the set-up.",
        )
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true; addView(root)
        }
        setContentWithThumb(scroll) { showRoomBeaconDebug() }
    }

    /** One place the set-up wizard asks the user to be. core = defines super green + true. */
    private data class CalSpot(val title: String, val instruction: String, val core: Boolean = false)

    private val insideSpots = listOf(
        CalSpot("Right next to the beacon", "Stand about an arm's length from this room's beacon.", core = true),
        CalSpot("Middle of the room", "Stand in the middle of the room."),
        CalSpot("Far corner", "Go to the far corner or edge of the room - as far from the beacon as it gets."),
        CalSpot("Opposite far corner", "Now a different far corner or edge of the room."),
    )

    // The most important readings of all: where the phone would ACTUALLY get used.
    // These are the spots that can produce a 'true'.
    private val temptationSpots = listOf(
        CalSpot("Where you'd actually use the phone", "Sit or lie EXACTLY where you'd really use the phone in this room - on the bed, in the chair. Get comfortable and hold the phone the way you really would.", core = true),
        CalSpot("Same place, held differently", "Stay there, but change it up: other hand, resting on your lap, lying back - a realistic variation of the same spot.", core = true),
    )

    // ── Room set-up wizard ────────────────────────────────────────────────────
    // One instruction per screen, big text. Flow: find this room's beacon (skipped if
    // already known), make sure at least one other room's beacon exists (the pairwise
    // matching needs 2+), place all beacons, 6 spots (statics + temptation), a 15 s
    // walk around the room, then a free-roam pass around the house tagging false
    // readings. Every recording captures ALL beacons at once - the values come as a
    // set, and that set is what gets matched later.
    private fun showRoomSetup(room: String) {
        val roomName = room.replaceFirstChar { it.uppercase() }
        val roomsNow = RoomBeacons.rooms(this)
        val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
        val scanner = beaconScanner ?: BeaconScanner(this).also { beaconScanner = it }
        val press = pressureMon ?: PressureMonitor(this).also { pressureMon = it }
        press.start()
        beaconUi?.removeCallbacksAndMessages(null)
        val ui = Handler(Looper.getMainLooper()); beaconUi = ui
        val collected = mutableListOf<RoomBeacons.Sample>()

        fun bigBody(t: String) = TextView(this).apply {
            text = t; textSize = 17f; setTextColor(0xFF3A434B.toInt())
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }
        fun bigCountdown() = TextView(this).apply {
            textSize = 64f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setTextColor(0xFF2E7D32.toInt())
        }
        fun liveLine() = TextView(this).apply {
            textSize = 22f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }
        // Renders one wizard screen and kills the previous screen's tickers.
        fun show(step: String, title: String, vararg views: View) {
            ui.removeCallbacksAndMessages(null)
            val root = vbox(pad)
            root.addView(stepText(step.uppercase()))
            root.addView(TextView(this).apply {
                text = title; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1F2933.toInt())
            })
            views.forEach { root.addView(it) }
            val scroll = ScrollView(this).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isFillViewport = true; addView(root)
            }
            setContentWithThumb(scroll) { ui.removeCallbacksAndMessages(null); showRoomBeaconDebug() }
        }
        fun tick(intervalMs: Long, body: () -> Unit) {
            // Re-post BEFORE the body runs: if the body navigates to another wizard
            // page (which clears this handler), the pending repost is cleared with it.
            ui.post(object : Runnable {
                override fun run() { ui.postDelayed(this, intervalMs); body() }
            })
        }
        scanner.expectedMacs = RoomBeacons.allAssignedMacs(this).toSet()
        // What every assigned beacon sounds like right now - always the FULL set.
        fun snapshotReadings(): Map<String, Int> =
            RoomBeacons.allAssignedMacs(this)
                .mapNotNull { m -> scanner.kalmanRssi(m)?.let { m to it } }
                .toMap()
        fun ownMac() = RoomBeacons.beaconMac(this, room)
        fun liveTick(live: TextView) = tick(300) {
            scanner.ensureScanning(); press.start()
            val mac = ownMac()
            val b = mac?.let { scanner.beacon(it) }
            val fresh = b != null && System.currentTimeMillis() - b.lastSeen <= RoomBeacons.TIMEOUT_MS
            live.text = if (fresh) "beacon: ${scanner.kalmanRssi(mac!!) ?: b!!.rssi} dBm" else "beacon: not heard"
            live.setTextColor(if (fresh) 0xFF1F2933.toInt() else 0xFFB00020.toInt())
        }

        fun finishPage() {
            RoomPresence.reset()
            val all = RoomBeacons.samples(this, room)
            val nIn = all.count { it.inRoom }; val nOut = all.size - nIn
            val remaining = roomsNow.filter { it != room && !RoomBeacons.isCalibrated(this, it) }
            val summary = buildString {
                append("Recorded $nIn inside readings and $nOut outside readings - your real usage ")
                append("spots, a walk around the room, and your tagged spots.\n\n")
                append("$roomName reads true only when the whole set of beacon readings matches one ")
                append("of your usage spots; maybe (probs is / probs not) when it's near the room's ")
                append("other recordings; false when it matches a tagged spot or nothing at all.")
                if (remaining.isNotEmpty()) {
                    append("\n\n➜ Still to set up: ${remaining.joinToString(", ")}.")
                }
            }
            show("Set-up complete · $roomName", "$roomName is ready",
                bigBody(summary),
                bigChoice("Finish", 0xFF2E7D32.toInt()) { showRoomBeaconDebug() })
        }

        // ── Phase: free-roam around the house, tagging false readings ─────────────
        fun goRoam() {
            // Persist everything so far - the live indicator below runs on the full set.
            RoomBeacons.setSamples(this, room, collected)
            RoomPresence.reset()
            val indicator = TextView(this).apply { gravity = Gravity.CENTER; setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt()) }
            var tagging = false
            val tagBtn = bigChoice("TAG FALSE READING HERE", 0xFFB00020.toInt()) {}
            val doneBtn = Button(this).apply { text = "Done tagging false readings"; setAllCaps(false) }
            tagBtn.setOnClickListener {
                if (tagging) return@setOnClickListener
                tagging = true; tagBtn.isEnabled = false
                val end = System.currentTimeMillis() + RoomBeacons.TAG_MS
                ui.post(object : Runnable {
                    override fun run() {
                        val left = end - System.currentTimeMillis()
                        if (left > 0) {
                            tagBtn.text = "hold still… ${(left + 999) / 1000}"
                            ui.postDelayed(this, 200)
                            return
                        }
                        tagging = false; tagBtn.isEnabled = true; tagBtn.text = "TAG FALSE READING HERE"
                        RoomBeacons.addSample(this@MainActivity, room,
                            RoomBeacons.Sample("Tagged false reading", false, snapshotReadings()))
                        RoomPresence.reset()
                        Toast.makeText(this@MainActivity, "Tagged as NOT in the $roomName ✓", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            doneBtn.setOnClickListener { finishPage() }
            tagBtn.visibility = View.GONE   // appears only while the reading is wrong
            show("Set up · $roomName · roam & tag", "Walk around the house",
                bigBody(
                    "Walk around the REST of the house - stay OUT of the $roomName. Visit the " +
                        "sneaky places: directly above or below the room, the landing or hallway, " +
                        "and the rooms next door.\n\n" +
                        "Watch the reading below. Whenever it says anything other than false, a " +
                        "red button appears - stand still and press it to teach that spot.",
                ),
                indicator, tagBtn, doneBtn)
            tick(400) {
                scanner.ensureScanning(); press.start()
                val st = RoomPresence.evaluate(this, scanner, press)[room]
                val wrongHere = st?.verdict != null && st.verdict != RoomPresence.Verdict.OUT
                if (!tagging) tagBtn.visibility = if (wrongHere) View.VISIBLE else View.GONE
                when (st?.verdict) {
                    RoomPresence.Verdict.IN -> {
                        indicator.text = "IT THINKS YOU'RE IN THE ${roomName.uppercase()}!\nThat's wrong - press the red button."
                        indicator.textSize = 22f; indicator.setTypeface(indicator.typeface, Typeface.BOLD)
                        indicator.setTextColor(0xFFB00020.toInt())
                    }
                    RoomPresence.Verdict.MAYBE_IN, RoomPresence.Verdict.MAYBE_OUT -> {
                        val which = if (st.verdict == RoomPresence.Verdict.MAYBE_IN) "probs is" else "probs not"
                        indicator.text = "MAYBE ($which)\nBorderline here - press the red button to teach it."
                        indicator.textSize = 18f; indicator.setTypeface(indicator.typeface, Typeface.BOLD)
                        indicator.setTextColor(0xFFB07800.toInt())
                    }
                    else -> {
                        indicator.text = "false ✓  (correct)"
                        indicator.textSize = 13f; indicator.setTypeface(null, Typeface.NORMAL)
                        indicator.setTextColor(0xFF9AA0A6.toInt())
                    }
                }
            }
        }

        // ── Phase: 15 s walk around the room (outliers get trimmed later) ─────────
        fun goWander() {
            val mac = ownMac() ?: run { showRoomBeaconDebug(); return }
            val live = liveLine()
            val countdown = bigCountdown()
            val prog = TextView(this).apply { textSize = 13f; setTextColor(0xFF7B848C.toInt()); gravity = Gravity.CENTER }
            var wandering = false
            var got = 0
            val startBtn = bigChoice("Start - walk around the room", 0xFF2E9E8F.toInt()) {}
            val end = longArrayOf(0)
            startBtn.setOnClickListener {
                if (wandering) return@setOnClickListener
                wandering = true; startBtn.isEnabled = false
                end[0] = System.currentTimeMillis() + 15_000
            }
            show("Set up · $roomName · walk the room", "Walk around the room for 15 s",
                bigBody(
                    "Wander slowly around the whole room - along the walls, past the furniture. " +
                        "This gives lots of readings so the odd weird one can be spotted and ignored.",
                ),
                live, countdown, prog, startBtn)
            liveTick(live)
            tick(1_000) {
                if (!wandering) return@tick
                val left = end[0] - System.currentTimeMillis()
                if (left <= 0) { goRoam(); return@tick }
                countdown.text = "${(left + 999) / 1000}"
                val readings = snapshotReadings()
                if (readings.containsKey(mac) && got < 20) {
                    collected.add(RoomBeacons.Sample("Walking around the room", true, readings))
                    got++
                }
                prog.text = "recorded $got readings"
            }
        }

        // ── Phase: static + temptation spots (3 s each) ───────────────────────────
        lateinit var goSpot: (Int) -> Unit
        goSpot = fun(i: Int) {
            val spots = insideSpots + temptationSpots
            if (i >= spots.size) { goWander(); return }
            val spot = spots[i]
            val mac = ownMac() ?: run { showRoomBeaconDebug(); return }
            val live = liveLine()
            val countdown = bigCountdown()
            val sampleBtn = bigChoice("Sample this spot (hold still 3 s)", 0xFF2E9E8F.toInt()) {}
            var sampling = false
            sampleBtn.setOnClickListener {
                if (sampling) return@setOnClickListener
                val heard = scanner.beacon(mac)
                if (heard == null || System.currentTimeMillis() - heard.lastSeen > RoomBeacons.TIMEOUT_MS) {
                    Toast.makeText(this, "Can't hear the $roomName beacon from here - is it switched on?", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                sampling = true; sampleBtn.isEnabled = false
                val end = System.currentTimeMillis() + RoomBeacons.SAMPLE_MS
                ui.post(object : Runnable {
                    override fun run() {
                        val left = end - System.currentTimeMillis()
                        if (left > 0) {
                            countdown.text = "${(left + 999) / 1000}"
                            ui.postDelayed(this, 200)
                            return
                        }
                        countdown.text = "✓"
                        val readings = snapshotReadings()
                        if (!readings.containsKey(mac)) {
                            sampling = false; sampleBtn.isEnabled = true; countdown.text = ""
                            Toast.makeText(this@MainActivity, "Lost the beacon mid-sample - try again", Toast.LENGTH_LONG).show()
                        } else {
                            collected.add(RoomBeacons.Sample(spot.title, true, readings, core = spot.core))
                            Toast.makeText(this@MainActivity, "${spot.title}: ${readings[mac]} dBm ✓", Toast.LENGTH_SHORT).show()
                            goSpot(i + 1)
                        }
                    }
                })
            }
            show("Set up · $roomName · spot ${i + 1} of ${spots.size}", spot.title,
                bigBody(spot.instruction + if (!spot.core) "\n\nHold the phone in your hand like you'd normally use it." else ""),
                live, countdown, sampleBtn)
            liveTick(live)
        }

        fun placePage() {
            val dual = RoomBeacons.dualMode(this)
            val placements = roomsNow.mapNotNull { r ->
                if (RoomBeacons.beaconMac(this, r) == null) null
                else if (RoomBeacons.beaconMac2(this, r) != null)
                    "· ${r.replaceFirstChar { c -> c.uppercase() }}: sensor A at the risk spot, sensor B at the opposite end"
                else "· ${r.replaceFirstChar { c -> c.uppercase() }} beacon → in the $r"
            }.joinToString("\n")
            show("Set up · $roomName · place the beacons", "Put EVERY beacon in its room",
                bigBody(
                    "KEY RULE: put each beacon closest to where the risk actually is - bedroom: " +
                        "at the bed (headboard or bedside table); bathroom: back of a cupboard or " +
                        "a shelf near where the phone would get used." +
                        (if (dual) "\nIn dual mode the second sensor goes at the OPPOSITE end of the room." else "") +
                        "\n\n" + placements + "\n\n" +
                        "They must never move once calibrated - if you move one later, redo the " +
                        "set-up.\n\nCome back here with your phone once they're all in place.",
                ),
                bigChoice("All in place - start calibrating", 0xFF2E9E8F.toInt()) { collected.clear(); goSpot(0) })
        }

        // Finds one room's beacon (hold-against-phone → strongest → confirm). Reused
        // for whichever room/slot needs one; second = the room's B sensor (dual mode).
        lateinit var assignFlow: (String, String, Boolean, () -> Unit) -> Unit
        fun confirmPage(target: String, step: String, second: Boolean, candidate: BeaconScanner.Beacon, onDone: () -> Unit) {
            val targetName = target.replaceFirstChar { it.uppercase() }
            val slotName = if (second) "second $targetName sensor" else "$targetName beacon"
            show(step, "Found it",
                bigBody(
                    "Strongest signal right now:\n\n${candidate.name ?: "unnamed beacon"}\n${candidate.mac}\n" +
                        "${Math.round(candidate.smoothedRssi)} dBm\n\nIs that the beacon in your hand?",
                ),
                bigChoice("Yes - this is the $slotName", 0xFF2E7D32.toInt()) {
                    if (second) RoomBeacons.setBeaconMac2(this, target, candidate.mac)
                    else RoomBeacons.setBeaconMac(this, target, candidate.mac)
                    scanner.expectedMacs = RoomBeacons.allAssignedMacs(this).toSet()
                    onDone()
                },
                Button(this).apply { text = "No - scan again"; setAllCaps(false); setOnClickListener { assignFlow(target, step, second, onDone) } })
        }

        assignFlow = fun(target: String, step: String, second: Boolean, onDone: () -> Unit) {
            val targetName = target.replaceFirstChar { it.uppercase() }
            val countdown = bigCountdown()
            val live = TextView(this).apply {
                textSize = 15f; setTextColor(0xFF7B848C.toInt()); gravity = Gravity.CENTER
                setPadding(0, (6 * dp).toInt(), 0, 0)
            }
            var finding = false
            val startBtn = bigChoice("Start the 3-second scan", 0xFF2E9E8F.toInt()) {}
            startBtn.setOnClickListener {
                if (finding) return@setOnClickListener
                if (!scanner.isBluetoothOn) {
                    Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                scanner.ensureScanning()
                finding = true; startBtn.isEnabled = false
                // Every beacon already assigned anywhere (except this very slot's
                // current value) is off-limits - can't reuse one beacon twice.
                val currentSlot = if (second) RoomBeacons.beaconMac2(this, target) else RoomBeacons.beaconMac(this, target)
                val otherMacs = RoomBeacons.allAssignedMacs(this).toSet() - setOfNotNull(currentSlot)
                val begin = System.currentTimeMillis()
                val end = begin + RoomBeacons.SAMPLE_MS
                ui.post(object : Runnable {
                    override fun run() {
                        val left = end - System.currentTimeMillis()
                        if (left > 0) {
                            countdown.text = "${(left + 999) / 1000}"
                            ui.postDelayed(this, 200)
                            return
                        }
                        finding = false; startBtn.isEnabled = true; countdown.text = ""
                        val fresh = scanner.all().filter { it.lastSeen >= begin }
                        val strongest = fresh.maxByOrNull { it.smoothedRssi }
                        val best = fresh.filter { it.mac !in otherMacs }.maxByOrNull { it.smoothedRssi }
                        when {
                            best == null -> Toast.makeText(this@MainActivity,
                                "Heard nothing. Is the beacon switched on? Hold its button ~3 s until the light blinks, then try again.",
                                Toast.LENGTH_LONG).show()
                            strongest != null && strongest.mac in otherMacs &&
                                strongest.smoothedRssi > best.smoothedRssi + 6 -> Toast.makeText(this@MainActivity,
                                "The beacon in your hand is already assigned to another room. To swap them, Reset that room first.",
                                Toast.LENGTH_LONG).show()
                            best.smoothedRssi < -55 -> Toast.makeText(this@MainActivity,
                                "Strongest device is only ${Math.round(best.smoothedRssi)} dBm - too weak to be touching the phone. Press the beacon against the phone's back and try again.",
                                Toast.LENGTH_LONG).show()
                            else -> confirmPage(target, step, second, best, onDone)
                        }
                    }
                })
            }
            show(step, if (second) "Find the second $targetName sensor" else "Find the $targetName beacon",
                bigBody(
                    (if (second) "This one will live at the OTHER end of the $target.\n\n" else "") +
                        "1. Make sure the beacon is switched on: hold its button for ~3 seconds until " +
                        "its light blinks. (Brand new? Pull the battery tab out first.)\n\n" +
                        "2. Hold the beacon flat against the BACK of your phone.\n\n" +
                        "3. Keep it there and press Start.",
                ),
                countdown, live, startBtn)
            tick(300) {
                scanner.ensureScanning()
                val strongest = scanner.all()
                    .filter { System.currentTimeMillis() - it.lastSeen <= 3_000 }
                    .maxByOrNull { it.smoothedRssi }
                live.text = strongest?.let { "strongest nearby device: ${Math.round(it.smoothedRssi)} dBm" } ?: "listening…"
            }
        }

        // The pairwise matching needs at least TWO beacons in the house. Anything
        // already assigned is skipped silently - no redundant questions.
        fun afterOwnBeacon() {
            val assignedTotal = RoomBeacons.allAssignedMacs(this).size
            val next = roomsNow.firstOrNull { it != room && RoomBeacons.beaconMac(this, it) == null }
            if (assignedTotal >= 2 || next == null) { placePage(); return }
            val nextName = next.replaceFirstChar { it.uppercase() }
            show("Set up · $roomName · second beacon", "Now the $nextName beacon",
                bigBody(
                    "One beacon alone can't tell \"in the $roomName\" from \"directly above or " +
                        "below it on another floor\" - radio goes straight through floors. The app " +
                        "matches the whole SET of beacon readings, so at least one more room's " +
                        "beacon must be set up before calibrating.\n\nGo and get the $nextName " +
                        "beacon now.",
                ),
                bigChoice("I'm holding it - find this beacon", 0xFF2E9E8F.toInt()) {
                    assignFlow(next, "Set up · $roomName · second beacon", false) { placePage() }
                })
        }

        // Dual mode: this room needs its B sensor (opposite end) before moving on.
        fun maybeOwnSecond() {
            if (!RoomBeacons.dualMode(this) || RoomBeacons.beaconMac2(this, room) != null) {
                afterOwnBeacon(); return
            }
            show("Set up · $roomName · sensor B", "This room gets TWO sensors",
                bigBody(
                    "Dual-sensor mode is on: the $roomName gets a second sensor at the OPPOSITE " +
                        "end of the room, which makes the readings much more distinctive.\n\n" +
                        "Go and get the second $roomName sensor now.",
                ),
                bigChoice("I'm holding it - find this sensor", 0xFF2E9E8F.toInt()) {
                    assignFlow(room, "Set up · $roomName · sensor B", true) { afterOwnBeacon() }
                })
        }

        val existing = RoomBeacons.beaconMac(this, room)
        when {
            existing == null -> assignFlow(room, "Set up · $roomName · find beacon", false) { maybeOwnSecond() }
            !RoomBeacons.isCalibrated(this, room) -> maybeOwnSecond()   // assigned during another room's set-up
            else -> show("Recalibrate · $roomName", "Recalibrate $roomName",
                bigBody(
                    "This room is already set up. Keep the same beacon and redo the calibration " +
                        "walk, or start from scratch?",
                ),
                bigChoice("Keep beacon - recalibrate", 0xFF2E9E8F.toInt()) { maybeOwnSecond() },
                Button(this).apply {
                    text = "Start over - pick the beacon again"; setAllCaps(false)
                    setOnClickListener { assignFlow(room, "Set up · $roomName · find beacon", false) { maybeOwnSecond() } }
                })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Re-enter the page: it shows the readings if granted, the explainer again if not.
        if (requestCode == REQ_BEACON_PERMS) showRoomBeaconDebug()
    }

    // Read-only snapshot of everything the app is currently doing.
    private fun showDevConsole() {
        inSubPage = true
        val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText("System console"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
        })
        setContentWithThumb(root) { setupMainScreen() }

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
        row("Week-long strict lock", if (Mode.isLocked(this)) "locked - ${Mode.daysLeft(this)}" else "off", Mode.isLocked(this))
        row("Breathing pause", if (spec?.breathingOn == true) "on" else "off", spec?.breathingOn == true)
        row("Page flag threshold", "${spec?.flagThreshold ?: "-"} (score \u2265 this is flagged)")
        row("Flag when lying down", if (spec?.flagLyingDown == true) "on" else "off", spec?.flagLyingDown == true)
        row("Flag when light \u2264", (spec?.lightFlagBelow ?: AppConfig.LightLevel.DARK).name)

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
        view.text = "${if (on) "\u25CF" else "\u25CB"}  $label - ${if (on) "On" else "Off"}"
        view.setTextColor(if (on) 0xFF2E9E44.toInt() else 0xFF9AA0A6.toInt())
    }

    /** A self-contained mode dropdown (used on the sexual-urge page). Drives Mode
     *  directly and resets itself if strict is locked. Does NOT touch dashboard views. */
    private fun modeSpinner(): Spinner {
        val dp = resources.displayMetrics.density
        val sp = Spinner(this)
        // Looked like inert text before, so nobody realised the mode was theirs to change.
        // An outlined pill reads as a control.
        sp.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 20 * dp
            setColor(0xFFFFFFFF.toInt())
            setStroke((1.5f * dp).toInt(), 0xFF2E9E8F.toInt())
        }
        val px = (14 * dp).toInt(); val py = (6 * dp).toInt()
        sp.setPadding(px, py, px, py)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, AppConfig.MODES.map { it.displayName })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = adapter
        fun curIdx() = AppConfig.MODES.indexOfFirst { it.id == Mode.current(this) }.coerceAtLeast(0)
        sp.setSelection(curIdx())
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val chosen = AppConfig.MODES.getOrNull(pos)?.id ?: return
                if (chosen == Mode.current(this@MainActivity)) return
                // Super hardcore needs the uninstall lock first - a mode this strict is
                // pointless if the app can just be deleted in a weak moment.
                if (chosen == Mode.SUPERHARDCORE &&
                    !(UninstallGuard.isEnabled(this@MainActivity) && UninstallGuard.isAdminActive(this@MainActivity))) {
                    Toast.makeText(this@MainActivity,
                        "Super hardcore needs the uninstall lock on first", Toast.LENGTH_LONG).show()
                    sp.setSelection(curIdx())
                    showLockPrompt { }
                    return
                }
                if (Mode.setMode(this@MainActivity, chosen)) {
                    Toast.makeText(this@MainActivity, "${AppConfig.modeName(chosen)} mode on", Toast.LENGTH_SHORT).show()
                } else {
                    // Refused: they're locked into strict and just tried to get out of it.
                    // That's a bypass attempt - see BypassWatch. The honest-exit offer
                    // shows immediately, at the moment of the attempt.
                    BypassWatch.record(this@MainActivity, BypassWatch.Reason.LEAVE_STRICT)
                    Toast.makeText(this@MainActivity, "Strict mode is locked - can't switch back yet", Toast.LENGTH_SHORT).show()
                    sp.setSelection(curIdx())
                    maybeShowBypassOffer()
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
            text = "${if (on) "\u25CF" else "\u25CB"}  $label - ${if (on) "On" else "Off"}"
            textSize = 14f; setTextColor(if (on) 0xFF2E9E44.toInt() else 0xFF9AA0A6.toInt())
            isClickable = true; isFocusable = true; setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener { onClick() }
        })
        row("Page monitoring", isAccessibilityEnabled()) {
            openAccessibilitySettings()
        }
        row("Block overlay permission", Settings.canDrawOverlays(this)) { requestOverlayPermission() }
        row("Uninstall lock", UninstallGuard.isEnabled(this) && UninstallGuard.isAdminActive(this)) { toggleUninstallGuard() }
        val timers = mutableListOf<String>()
        if (Lockdown.isActive(this)) timers.add("App lockdown - ${minLeft(Lockdown.remaining(this))} left")
        if (LoosenWindow.isActive(this)) timers.add("Unlock window - ${minLeft(LoosenWindow.remaining(this))} left")
        if (LoosenWait.isActive(this)) timers.add("Unlock wait - ${minLeft(LoosenWait.remaining(this))} left")
        if (Mode.isLocked(this)) timers.add("Week-long strict - ${Mode.daysLeft(this)}")
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

    // Hardcoded for now. Auto-verifies on the 6th digit - no Enter needed.
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

    // Open accessibility settings as directly as possible so returning to the app is quick:
    // on Android 11+ deep-link straight to THIS app's service page, and launch it as its own
    // task so a single back press lands back in the app instead of walking the Settings stack.
    private fun openAccessibilitySettings() {
        val cn = ComponentName(this, PageMonitorAccessibilityService::class.java).flattenToString()
        if (android.os.Build.VERSION.SDK_INT >= 30) {   // Android 11+ (R): deep-link to our page
            try {
                // API 30+ constants referenced by value so they compile on any SDK:
                //   ACTION_ACCESSIBILITY_DETAILS_SETTINGS / EXTRA_ACCESSIBILITY_DETAILS_SETTINGS
                startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra("android.provider.extra.ACCESSIBILITY_DETAILS_SETTINGS", cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) { /* fall back to the full list below */ }
        }
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

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
