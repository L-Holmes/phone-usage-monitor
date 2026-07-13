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
// All files share `package com.example.webtrafficmonitor`, so they compile together
// with no imports between them. NOTE: this supersedes the old merge_kt.py workflow -
// do NOT re-merge these back into one file, or you'll get duplicate package/import lines.

// =====================================================================================
// APP
// =====================================================================================


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
    root.addView(titleText("Statistics"))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(pickCard("Progress & reward") { showProgress() })
    list.addView(pickCard("Temptation patterns") { showTemptationStats() })
    list.addView(pickCard("Relapse patterns") { showRelapseStats() })
    list.addView(pickCard("Unlock attempts") { showLoosenStats() })
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
    })
    setContentWithThumb(root) { showReportScreen() }
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
        root.addView(backText { appSiteBack() })
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
private fun setupHomeScreen() {
    onHomeScreen = true; onTemptationsTab = false; onReportScreen = false; onDevScreen = false
    subBack = null
    inSubPage = false; inRelapseFlow = false; inTemptationFlow = false
    inLoosenFlow = false; inAppSiteFlow = false
    stopRideTimer(); stopLoosenTimer(); entriesJob?.cancel()
    val dp = resources.displayMetrics.density; val pad = (20 * dp).toInt()
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

    // Gentle warning banner if the app isn't protected yet.
    if (!(UninstallGuard.isEnabled(this) && UninstallGuard.isAdminActive(this))) {
        content.addView(uninstallBanner())
    }

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
            "${s.cleanDays} of the last ${s.trackedDays} days clean - one slip never resets it", green))
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
        lifeStat.text = "Over $yrs year${if (yrs == 1) "" else "s"}: about ${String.format("%.1f", totalWakingYears)} years of waking life - and \u00a3$gbpTotal"
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
    list.addView(homeCard("Sexual arousal  \u2642\uFE0F\u2640\uFE0F", "Tools for the moment, and the longer game.") {
        reportBackTarget = { showTemptationsTab() }; showReportScreen()
    })
    root.addView(list)
    root.addView(grow())
    root.addView(TextView(this).apply {
        text = "More areas later."; textSize = 13f; setTextColor(0xFF9AA0A6.toInt())
        setPadding(0, 0, 0, (8 * dp).toInt())
    })
    setContentWithThumb(root) { setupHomeScreen() }
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
    root.addView(backText { showProtocol() })
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
    setContentView(root)
}

private fun showProtocol7Day() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (16 * dp).toInt()
    val root = vbox(pad)
    root.addView(backText { showProtocol() })
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
    setContentView(root)
}

private fun showReportScreen() {
    // On the highest-risk page: offer the uninstall lock once per session (unless in dev
    // mode, or it's already on). Returns to this page when the user enables or skips.
    if (!AppConfig.DEV_MODE && !arousalLockPromptShown &&
        !(UninstallGuard.isEnabled(this) && UninstallGuard.isAdminActive(this))) {
        arousalLockPromptShown = true
        showLockPrompt { showReportScreen() }
        return
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
    // Sits beside the dropdown on purpose: the moment you change mode is the moment you
    // want to know what you just signed up for.
    modeRow.addView(TextView(this).apply {
        text = "What each mode does  ›"
        textSize = 14f; setTextColor(0xFF2E9E8F.toInt()); setTypeface(typeface, Typeface.BOLD)
        isClickable = true; isFocusable = true
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        setOnClickListener { showModeRules() }
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
    setContentWithThumb(root) { reportBackTarget() }
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
    root.addView(backText { loosenBack() })
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
    root.addView(backText { loosenBack() })
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
    content.addView(backText { loosenBack() })
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
private fun backText(onBack: () -> Unit): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = "Back"; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFFFFFFFF.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 22 * dp; setColor(0xFF2E9E8F.toInt())     // teal button, top-left
        }
        val px = (20 * dp).toInt(); val py = (9 * dp).toInt(); setPadding(px, py, px, py)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * dp).toInt() }
        isClickable = true; isFocusable = true
        setOnClickListener { onBack() }
    }
}

// The floating thumb back button for non-report pages: a custom-drawn circle + arrow,
// so the arrow is geometrically centred (no font-baseline drift).
private fun thumbBack(onBack: () -> Unit): View =
    ThumbBackView(this).apply { isClickable = true; isFocusable = true; setOnClickListener { onBack() } }

// Renders a non-report page with the floating thumb back button. Back here ALWAYS
// means "go to the page I came from" (subBack), so navigation can't get tangled.
private fun setContentWithThumb(content: View, onBack: () -> Unit) {
    onReportScreen = false; onTemptationsTab = false; onDevScreen = false
    inRelapseFlow = false; inTemptationFlow = false; inLoosenFlow = false; inAppSiteFlow = false
    inSubPage = true
    subBack = onBack
    val dp = resources.displayMetrics.density
    val frame = android.widget.FrameLayout(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    frame.addView(content, android.widget.FrameLayout.LayoutParams(
        android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
    val size = (54 * dp).toInt()
    frame.addView(thumbBack(onBack), android.widget.FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
        bottomMargin = (resources.displayMetrics.heightPixels * 0.20f).toInt()
        marginEnd = (16 * dp).toInt()
    })
    setContentView(frame)
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
    if (onBack != null) root.addView(backText { onBack() })
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
        sensorMonitor?.stop(); sensorMonitor = null
        arousalLockPromptShown = false   // re-offer the lock on the arousal page once per app session
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

    // The uninstall-lock prompt now shows only during FIRST setup (persisted below) and
    // when entering the arousal page - never on every reopen. That, plus not rebuilding on
    // resume, is what keeps you on the page you left. Home shows a gentle banner instead.
    private var arousalLockPromptShown = false
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
    private var reportBackTarget: () -> Unit = { showTemptationsTab() }

    private fun currentStep(): Step = when {
        !isAccessibilityEnabled()       -> Step.MONITORING
        !Settings.canDrawOverlays(this) -> Step.OVERLAY
        !AppConfig.DEV_MODE && !lockPromptSeen() -> Step.LOCK
        else                            -> Step.READY
    }

    private fun updateScreen() {
        val step = currentStep()
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

    // Amber warning banner for the home screen; taps through to the lock prompt.
    private fun uninstallBanner(): View {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * dp; setColor(0xFFFFF3E0.toInt()); setStroke((1.5f * dp).toInt(), 0xFFE0A63C.toInt())
            }
            val p = (13 * dp).toInt(); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (14 * dp).toInt() }
            isClickable = true; isFocusable = true
            setOnClickListener { showLockPrompt { setupHomeScreen() } }
            addView(TextView(this@MainActivity).apply {
                text = "\u26A0"; textSize = 22f; setPadding(0, 0, (12 * dp).toInt(), 0); setTextColor(0xFFB8860B.toInt())
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "This app isn't protected yet"; textSize = 15f
                    setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF7A4F00.toInt())
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Tap to turn on the uninstall lock so you can't delete it in a weak moment."
                    textSize = 12f; setTextColor(0xFF8A6D3B.toInt()); setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            })
        }
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
        content.addView(homeCard("Grayscale setup", "Turn on the strict-mode grayscale filter.") { showGreyscaleSetup() })
        content.addView(homeCard("Preview uninstall prompt", "See the lock prompt (it's hidden in dev mode).") { showLockPrompt { setupMainScreen() } })
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
                    Toast.makeText(this@MainActivity, "Strict mode is locked - can't switch back yet", Toast.LENGTH_SHORT).show()
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
