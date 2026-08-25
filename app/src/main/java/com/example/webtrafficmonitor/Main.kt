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
import android.location.Location
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
import android.text.Layout
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
//   Overlay.kt                – the block overlay
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

// Request codes for the location permissions (home-area debug page, see HomeArea.kt).
// Two, because "while using" and "all the time" MUST be asked for separately - one
// combined request and Android 11+ grants neither.
private const val REQ_HOME_LOCATION = 72
private const val REQ_HOME_BACKGROUND = 73

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
            append("\nOPEN DETECTION CASES (word hits waiting on a repeat)\n")
            val cases = RepeatGate.summary()
            append(if (cases.isEmpty()) "(none)\n" else cases.joinToString("\n") + "\n")
            append("\nWHAT WE KNOW ABOUT EACH APP (decides how many hits it takes)\n")
            val trust = AppTrust.summary(this@MainActivity)
            append(if (trust.isEmpty()) "(none)\n" else trust.joinToString("\n") + "\n")
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
        onBack: (() -> Unit)?, subtitle: String? = null, onPick: (String) -> Unit,
    ) {
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(titleText(title))
        if (subtitle != null) root.addView(TextView(this).apply {
            text = subtitle; textSize = 13f; setTextColor(Palette.labelTertiary)
            setPadding(0, 0, 0, (8 * dp).toInt())
        })
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
            hint = getString(R.string.picker_type_it); inputType = InputType.TYPE_CLASS_TEXT
            val p = (20 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.picker_add_own)).setView(input)
            .setPositiveButton(getString(R.string.picker_add)) { _, _ ->
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
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(titleText(title))
        root.addView(TextView(this).apply {
            text = getString(R.string.picker_select_all); textSize = 14f; setTextColor(Palette.labelSecondary)
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
        })
        val cont = bigContinue(getString(R.string.common_continue)) { if (selected.isNotEmpty()) onPick(selected.toList()) }
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
        val short: String, val example: String, val title: String, val category: String, val icon: Int,
    ) {
        SCREEN("Something on a screen", "e.g. my phone, my computer, the TV", "What kind of screen?", "screen", R.drawable.ic_opt_display),
        PLACE("Linked to where I am", "e.g. bedroom, bathroom, in the house", "Where are you?", "location", R.drawable.ic_opt_pin),
        FEELING("How I'm feeling", "e.g. anxious, low, frustrated", "How are you feeling?", "feeling", R.drawable.ic_opt_pulse),
        DOING("Out of habit", "e.g. scrolling, winding down, just woke up", "What were you doing?", "activity", R.drawable.ic_opt_repeat),
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
        val pad = (Space.page * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(titleText(getString(R.string.temp_groups_title)))
        root.addView(TextView(this).apply {
            text = getString(R.string.temp_groups_sub); textSize = 14f; setTextColor(Palette.labelSecondary)
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
        })
        val cont = bigContinue(getString(R.string.common_continue)) {
            if (tGroups.isNotEmpty()) { tSubQueue = tGroups.toList(); tSubIndex = 0; renderNextSub() }
        }
        root.addView(cont)
        fun renderList() {
            list.removeAllViews()
            TGroup.values().forEach { g ->
                list.addView(checkRow(Choice(g.short, g.icon, tgroupExample(g), label = tgroupShort(g)), g in tGroups) {
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
            pickMultiWithCustomScreen(tgroupTitle(g), baseFor(g), g.category, onBack = { temptationBack() }) {
                tAnswers[g] = it.joinToString(", "); tSubIndex++; renderNextSub()
            }
        } else {
            pickWithCustomScreen(tgroupTitle(g), baseFor(g), g.category, onBack = { temptationBack() }) {
                tAnswers[g] = it; tSubIndex++; renderNextSub()
            }
        }
    }

    private fun temptationUrgeScreen() {
        tBack = { if (tSubQueue.isEmpty()) temptationGroupsScreen() else { tSubIndex = tSubQueue.lastIndex; renderNextSub() } }
        urgeScaleScreen(getString(R.string.temp_urge_q), onBack = { temptationBack() }) {
            tUrgeIndex = Opts.URGE_LEVELS.indexOf(it).coerceAtLeast(0)
            startRideWave()
        }
    }

    private fun showManageRules() {
        inSubPage = true
        val dp = resources.displayMetrics.density
        val pad = (Space.page * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(titleText(getString(R.string.manage_title)))
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(container)
        })
        setContentWithThumb(root) { setupMainScreen() }

        fun header(t: String): TextView = TextView(this).apply {
            text = t; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.labelSecondary)
            setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
        }
        // [sub] is the "why is this here" line - see BlockRules.whyLine. A block list you
        // can't account for is one you can't safely prune.
        fun row(label: String, sub: String? = null, onRemove: () -> Unit): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply { text = label; textSize = 15f })
                if (sub != null) addView(TextView(this@MainActivity).apply {
                    text = sub; textSize = 12f; setTextColor(Palette.labelTertiary)
                })
            })
            addView(Button(this@MainActivity).apply { text = getString(R.string.common_remove); setOnClickListener { onRemove() } })
        }

        fun reload() {
            container.removeAllViews()
            var any = false
            val blockedApps = AppRules.apps(this).filter { it.first == AppRules.BLOCK }
            if (blockedApps.isNotEmpty()) {
                any = true; container.addView(header(getString(R.string.manage_blocked_apps)))
                blockedApps.forEach { (_, pkg) ->
                    container.addView(row(appLabel(pkg)) { AppRules.remove(this, true, pkg); reload() })
                }
            }
            val greyApps = AppRules.apps(this).filter { it.first == AppRules.GREY }
            if (greyApps.isNotEmpty()) {
                any = true; container.addView(header(getString(R.string.manage_greylisted_apps, GreyUsage.LIMIT_MIN)))
                greyApps.forEach { (_, pkg) ->
                    container.addView(row(appLabel(pkg)) { AppRules.remove(this, true, pkg); reload() })
                }
            }
            val siteRules = BlockRules.all()
            if (siteRules.isNotEmpty()) {
                any = true; container.addView(header(getString(R.string.manage_blocked_sites)))
                siteRules.forEach { r ->
                    container.addView(row(r, BlockRules.whyLine(this, r)) { BlockRules.remove(this, r); reload() })
                }
            }
            val greyHosts = AppRules.hosts(this)
            if (greyHosts.isNotEmpty()) {
                any = true; container.addView(header(getString(R.string.manage_greylisted_sites, GreyUsage.LIMIT_MIN)))
                greyHosts.forEach { (_, host) ->
                    container.addView(row(host) { AppRules.remove(this, false, host); reload() })
                }
            }
            if (!any) container.addView(TextView(this).apply {
                text = getString(R.string.manage_nothing); setPadding(0, (16 * dp).toInt(), 0, 0)
            })
        }
        reload()
    }

private fun appLabel(pkg: String): String = try {
    packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
} catch (t: Throwable) { pkg }

// ── Temptation-category display text (from strings.xml, keyed by spec id) ────────────
private fun habitResId(key: String, suffix: String): Int =
    resources.getIdentifier("habit_${key}_$suffix", if (suffix == "options") "array" else "string", packageName)
private fun habitLabel(key: String): String = getString(habitResId(key, "label"))
private fun habitHint(key: String): String = getString(habitResId(key, "hint"))
private fun habitOption(key: String, index: Int): String =
    resources.getStringArray(habitResId(key, "options")).getOrElse(index) { "" }

private fun temptResId(id: String, suffix: String): Int =
    resources.getIdentifier("temptspec_${id}_$suffix", if (suffix == "covers") "array" else "string", packageName)
/**
 * The glyph for a temptation category. Emoji used to live in the TITLES themselves, which
 * meant the title string carried a picture, every translation had to keep it, and the
 * result was a column of full-colour cartoons on cards that are otherwise monochrome and
 * quiet. These are stroke marks in the app's own tint instead - see iconBadge.
 */
private fun temptIcon(id: String): Int = when (id) {
    "scrolling" -> R.drawable.ic_cat_scrolling
    "checking" -> R.drawable.ic_cat_checking
    "binge" -> R.drawable.ic_cat_binge
    "comparison" -> R.drawable.ic_cat_comparison
    "news" -> R.drawable.ic_cat_news
    "gaming" -> R.drawable.ic_cat_gaming
    "shopping" -> R.drawable.ic_cat_shopping
    else -> R.drawable.ic_opt_pulse
}

private fun temptTitle(spec: AppConfig.TemptationSpec): String = getString(temptResId(spec.id, "title"))
private fun temptSubtitle(spec: AppConfig.TemptationSpec): String = getString(temptResId(spec.id, "subtitle"))
/**
 * What this category's block switch kills, in the user's words ("reels, shorts & endless
 * feeds") - or null, and the switch falls back to the generic "block what feeds this".
 *
 * Optional per category on purpose: most of them have nothing better to say than the
 * generic line, but scrolling owns the reels/shorts/feeds switch that used to sit on the
 * Productivity page, and that switch has to keep naming what it does.
 */
private fun temptBlocksLabel(spec: AppConfig.TemptationSpec): String? =
    temptResId(spec.id, "blocks").takeIf { it != 0 }?.let { getString(it) }

private fun temptCovers(spec: AppConfig.TemptationSpec): List<String> =
    resources.getStringArray(temptResId(spec.id, "covers")).toList()

/** Localised mode name for the UI (falls back to AppConfig's English name for unknown ids). */
private fun modeDisplayName(id: String): String = when (id) {
    Mode.OFF -> getString(R.string.mode_off_name)
    Mode.RELAXED -> getString(R.string.mode_relaxed_name)
    Mode.STRICT -> getString(R.string.mode_strict_name)
    Mode.SUPERHARDCORE -> getString(R.string.mode_superhardcore_name)
    else -> AppConfig.modeName(id)
}

/** The plain-English rule bullets for a mode (from string-arrays; empty for unknown ids). */
private fun modeRules(id: String): List<String> {
    val arr = when (id) {
        Mode.OFF -> R.array.mode_off_rules
        Mode.RELAXED -> R.array.mode_relaxed_rules
        Mode.STRICT -> R.array.mode_strict_rules
        Mode.SUPERHARDCORE -> R.array.mode_superhardcore_rules
        else -> return emptyList()
    }
    return resources.getStringArray(arr).toList()
}

/** The "always on, in every mode" rules. The three time/limit lines take live values. */
private fun alwaysOnRules(): List<String> = listOf(
    getString(R.string.always_on_01),
    getString(R.string.always_on_02),
    getString(R.string.always_on_03),
    getString(R.string.always_on_04),
    getString(R.string.always_on_05),
    getString(R.string.always_on_06),
    getString(R.string.always_on_07),
    getString(R.string.always_on_08, GreyUsage.LIMIT_MIN),
    getString(R.string.always_on_09, (Lockdown.DURATION_MS / 60_000).toInt()),
    getString(R.string.always_on_10, LoosenLimit.LIFETIME_MAX),
    getString(R.string.always_on_11),
    getString(R.string.always_on_12),
    getString(R.string.always_on_13),
    getString(R.string.always_on_14),
    getString(R.string.always_on_15),
    getString(R.string.always_on_16),
    getString(R.string.always_on_17),
    getString(R.string.always_on_18,
        (RepeatGate.WAIT_FIRST_MS / 1000).toInt(), (RepeatGate.WAIT_SECOND_MS / 1000).toInt(),
        RepeatGate.HITS_KNOWN),
    getString(R.string.always_on_19,
        AppTrust.ESTABLISHED_DAYS, AppTrust.ESTABLISHED_DAYS_SEEN,
        RepeatGate.HITS_REPEAT, RepeatGate.HITS_KNOWN),
    getString(R.string.always_on_20,
        (RepeatGate.CASE_MS / 60_000).toInt(), (RepeatGate.QUIET_RESET_MS / 60_000).toInt()),
)

/**
 * The in-app language picker. Lists LocaleHelper.SUPPORTED (system default first, then each
 * shipped language in its own name), applies the choice via the Android per-app-language API,
 * and recreates the UI so it takes effect at once. With only English shipped this is a no-op
 * for now, but the whole path is live — adding a values-<code>/ folder makes it real.
 */
private fun showLanguagePicker() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.settings_language)))
    root.addView(TextView(this).apply {
        text = getString(R.string.settings_language_subtitle)
        textSize = 13f; setTextColor(Palette.labelSecondary); setPadding(0, 0, 0, (10 * dp).toInt())
    })
    LocaleHelper.SUPPORTED.forEach { lang ->
        val name = if (lang.tag.isBlank()) getString(R.string.language_system_default)
                   else lang.nativeNameResOrText
        root.addView(Button(this).apply {
            text = if (LocaleHelper.isCurrent(lang)) "✓  $name" else name
            setOnClickListener {
                LocaleHelper.apply(lang.tag)
                Toast.makeText(this@MainActivity, getString(R.string.language_changed), Toast.LENGTH_SHORT).show()
                recreate()   // re-inflate everything in the chosen language
            }
        })
    }
    setContentWithThumb(root) { setupMainScreen() }
}

/**
 * The in-app currency picker, sibling of the language one. Money figures default to the
 * DEVICE REGION's currency; this is the escape hatch for anyone whose money and phone
 * disagree (a British salary on an Italian phone). Nothing is converted - see Units.
 */
private fun showCurrencyPicker() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.settings_currency)))
    root.addView(TextView(this).apply {
        text = getString(R.string.settings_currency_subtitle)
        textSize = 13f; setTextColor(Palette.labelSecondary); setPadding(0, 0, 0, (10 * dp).toInt())
    })
    val current = Units.currencyOverride(this)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
    })
    Units.SUPPORTED_CURRENCIES.forEach { code ->
        val name =
            if (code.isBlank()) getString(R.string.currency_device_default, Units.currencyCode(this))
            else getString(R.string.currency_row, Units.narrowSymbol(this, code), Units.currencyName(this, code))
        list.addView(Button(this).apply {
            text = if (code == current) "✓  $name" else name
            setOnClickListener {
                Units.setCurrencyOverride(this@MainActivity, code)
                Toast.makeText(this@MainActivity, getString(R.string.currency_changed), Toast.LENGTH_SHORT).show()
                recreate()   // every money figure on every screen is re-rendered
            }
        })
    }
    setContentWithThumb(root) { setupMainScreen() }
}

// ── Statistics ─────────────────────────────────────────────────────────────
// Localized short weekday names, Mon..Sun order (shortWeekdays is 1=Sun..7=Sat). Used as chart
// labels AND matched against dowName(ts) below, so both derive from the SAME locale.
private val DOW_ORDER: List<String>
    get() = java.text.DateFormatSymbols(Locale.getDefault()).shortWeekdays
        .let { w -> listOf(w[2], w[3], w[4], w[5], w[6], w[7], w[1]) }
private fun hourOf(ts: Long) = SimpleDateFormat("H", Locale.US).format(Date(ts)).toIntOrNull() ?: 0
private fun dowName(ts: Long) = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts))
private fun topCounts(items: List<String>, limit: Int = 8): List<Pair<String, Int>> =
    items.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
private val HOUR_LABELS = mapOf(0 to "12a", 6 to "6a", 12 to "12p", 18 to "6p", 23 to "11p")

private fun showStatsMenu() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.stats_title)))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    // NOTE: "Dopamine baseline" deliberately does NOT live here. It is a whole-phone,
    // whole-life measure, not an adult-content one, so it belongs on Overview - see
    // setupHomeScreen(). Don't move it back in here.
    list.addView(pickCard(getString(R.string.stats_progress)) { showProgress() })
    list.addView(pickCard(getString(R.string.stats_context)) { showContextStats() })
    list.addView(pickCard(getString(R.string.stats_temptation)) { showTemptationStats() })
    list.addView(pickCard(getString(R.string.stats_relapse)) { showRelapseStats() })
    list.addView(pickCard(getString(R.string.stats_unlock)) { showLoosenStats() })
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
    })
    setContentWithThumb(root) { setupMainScreen() }
}

// ── About you: optional numbers, used ONLY to make the cost concrete ────────
private var aboutYouBack: () -> Unit = { setupHomeScreen() }
private var dopamineBack: () -> Unit = { setupHomeScreen() }
/** The house page is reached from the dashboard AND from Developer tools; back goes home
 *  by default, and the dev card sets it to the tools page it was opened from. */
private var houseBack: () -> Unit = { setupHomeScreen() }

private fun showAboutYou() {
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.about_you_title)))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { aboutYouBack() }

    c.addView(TextView(this).apply {
        text = getString(R.string.about_intro,
            Units.money(this@MainActivity, AboutYou.DEFAULT_HOURLY * AboutYou.HOURS_PER_YEAR),
            Units.money(this@MainActivity, AboutYou.DEFAULT_HOURLY))
        textSize = 14f; setTextColor(Palette.labelSecondary); setLineSpacing(0f, 1.2f)
        setPadding(0, 0, 0, (16 * dp).toInt())
    })

    fun moneyRow(label: String, sub: String, get: () -> Int, set: (Int) -> Unit) {
        c.addView(TextView(this).apply {
            text = label; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.label); setPadding(0, (12 * dp).toInt(), 0, (2 * dp).toInt())
        })
        c.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(Palette.labelTertiary)
            setPadding(0, 0, 0, (6 * dp).toInt())
        })
        c.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(get().takeIf { it > 0 }?.toString().orEmpty())
            hint = getString(R.string.about_per_year, Units.symbol(this@MainActivity))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    set(s?.toString()?.toIntOrNull() ?: 0)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
    }

    moneyRow(getString(R.string.about_earn_label),
        getString(R.string.about_earn_sub, AboutYou.HOURS_PER_YEAR,
            Units.money(this, AboutYou.EXAMPLE_HOURLY),
            Units.money(this, AboutYou.EXAMPLE_HOURLY * AboutYou.HOURS_PER_YEAR / 1000 * 1000)),
        { AboutYou.annualWage(this) }, { AboutYou.setAnnualWage(this, it) })
    moneyRow(getString(R.string.about_side_label),
        getString(R.string.about_side_sub),
        { AboutYou.annualSide(this) }, { AboutYou.setAnnualSide(this, it) })

    c.addView(TextView(this).apply {
        text = getString(R.string.about_whyask, Units.money(this@MainActivity, AboutYou.EXAMPLE_WHYASK_ANNUAL))
        textSize = 13f; setTextColor(Palette.labelTertiary); setLineSpacing(0f, 1.15f)
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
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val today = DopamineLog.today(this)
    val r = DopamineScore.of(this, today)
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.dop_title)))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { dopamineBack() }

    if (!r.hasData) {
        c.addView(TextView(this).apply {
            text = getString(R.string.dop_empty)
            textSize = 15f; setTextColor(Palette.labelSecondary); setLineSpacing(0f, 1.2f)
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
        text = getString(R.string.dop_out_of_100)
        textSize = 13f; setTextColor(Palette.labelTertiary); setPadding(0, (6 * dp).toInt(), 0, 0)
    })
    scaleRow.addView(numbers)
    c.addView(scaleRow)

    // ── the trend ──
    val history = DopamineLog.history(this, 14)
    val scores = history.map { DopamineScore.of(this@MainActivity, it).score.toFloat() }.toFloatArray()
    if (scores.count { it > 0f } >= 2) {
        c.addView(statHeader(getString(R.string.dop_last14), dp))
        c.addView(TrendView(this, scores), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (120 * dp).toInt()))
        val avg = scores.filter { it > 0f }.average().toInt()
        c.addView(TextView(this).apply {
            text = getString(R.string.dop_average, avg)
            textSize = 13f; setTextColor(Palette.labelTertiary)
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
    }

    // ── what's driving it ──
    if (r.contributors.isNotEmpty()) {
        c.addView(statHeader(getString(R.string.dop_driving), dp))
        r.contributors.forEach { line ->
            c.addView(scoreLineRow(line.label, line.points, line.detail, Palette.dangerText, dp))
        }
    }
    if (r.credits.isNotEmpty()) {
        c.addView(statHeader(getString(R.string.dop_pulling_down), dp))
        r.credits.forEach { line ->
            c.addView(scoreLineRow(line.label, line.points, line.detail, Palette.successText, dp))
        }
    }

    // TWO BUTTONS USED TO SIT HERE AND HAVE BEEN REMOVED WITH THEIR PAGES:
    //   • "The ranks" - the belt ladder. A prestige system bolted onto a number that is
    //     supposed to be read once a day and acted on; it turned a measurement into a game
    //     with a score to protect.
    //   • "Your habits estimate" - a self-reported sleep/training/focus questionnaire that
    //     produced a second score which, by its own banner, changed nothing.
    // What is left is the two questions worth asking about a number: how is it worked out,
    // and how do I bring it down.
    c.addView(statHeader(getString(R.string.dop_more), dp))
    c.addView(captionedButton(getString(R.string.dop_maths_btn), getString(R.string.dop_maths_sub),
        Palette.tint) { showDopamineMaths() })
    c.addView(captionedButton(getString(R.string.dop_guidance_btn), getString(R.string.dop_guidance_sub),
        Palette.series[1]) { showDopamineGuidance() })
    c.addView(TextView(this).apply {
        text = getString(R.string.dop_disclaimer)
        textSize = 12f; setTextColor(Palette.labelTertiary)
        setPadding(0, (14 * dp).toInt(), 0, (20 * dp).toInt())
    })
}

// AI / MAINTAINER: this screen is GENERATED from DopamineTuning. If you change a weight or
// a threshold in Dopamine.kt, this page follows automatically - there is nothing to edit
// here. Do NOT hard-code a number into this screen; read it from the tuning object, or the
// page will start lying the first time someone retunes the algorithm.
private fun showDopamineMaths() {
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val t = DopamineTuning
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.dop_maths_title)))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { showDopamine() }

    c.addView(TextView(this).apply {
        text = getString(R.string.dop_maths_intro)
        textSize = 14f; setTextColor(Palette.labelSecondary); setLineSpacing(0f, 1.2f)
        setPadding(0, 0, 0, (4 * dp).toInt())
    })

    fun rule(title: String, worth: String, body: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Radius.control * dp; setColor(Palette.surface)
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
            setTextColor(Palette.label)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = worth; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.dangerText)
        })
        card.addView(head)
        card.addView(TextView(this).apply {
            text = body; textSize = 13f; setTextColor(Palette.labelSecondary)
            setLineSpacing(0f, 1.15f); setPadding(0, (5 * dp).toInt(), 0, 0)
        })
        c.addView(card)
    }

    c.addView(statHeader(getString(R.string.dop_maths_h1), dp))
    c.addView(TextView(this).apply {
        text = getString(R.string.dop_maths_dose, t.DOSE_MAX_HOURS.toInt())
        textSize = 13f; setTextColor(Palette.labelSecondary); setLineSpacing(0f, 1.15f)
    })
    DopamineCategory.values()
        .filter { it != DopamineCategory.OTHER }
        .sortedByDescending { t.CATEGORY_POINTS[it] ?: 0f }
        .forEach { cat ->
            val pts = Math.round(t.CATEGORY_POINTS[cat] ?: 0f)
            c.addView(TextView(this).apply {
                text = getString(R.string.dop_maths_cat_line, DopamineScore.catLabel(this@MainActivity, cat), pts)
                textSize = 14f; setTextColor(Palette.labelSecondary)
                setPadding(0, (7 * dp).toInt(), 0, 0)
            })
        }
    c.addView(TextView(this).apply {
        text = getString(R.string.dop_maths_example, DopamineScore.catLabel(this@MainActivity, DopamineCategory.FAST_VIDEO).lowercase(), Math.round(t.doseMultiplier(2f) * 100), Math.round(t.CATEGORY_POINTS[DopamineCategory.FAST_VIDEO] ?: 0f), Math.round(t.doseMultiplier(2f) * (t.CATEGORY_POINTS[DopamineCategory.FAST_VIDEO] ?: 0f)))
        textSize = 12f; setTextColor(Palette.labelTertiary); setLineSpacing(0f, 1.15f)
        setPadding(0, (10 * dp).toInt(), 0, 0)
    })

    c.addView(statHeader(getString(R.string.dop_maths_h2), dp))
    rule(getString(R.string.dop_maths_when_title),
        getString(R.string.dop_maths_when_worth, t.LATE_NIGHT_MULTIPLIER, t.JUST_WOKE_MULTIPLIER),
        getString(R.string.dop_maths_when_body, t.LATE_NIGHT_FROM, t.LATE_NIGHT_TO, t.LATE_NIGHT_MULTIPLIER, t.JUST_WOKE_WINDOW_MIN, t.JUST_WOKE_MULTIPLIER))
    rule(getString(R.string.dop_maths_unlocks_title), getString(R.string.dop_maths_up_to, Math.round(t.UNLOCKS_MAX_POINTS)),
        getString(R.string.dop_maths_unlocks_body, Math.round(t.UNLOCKS_PER_HOUR_OK), Math.round(t.UNLOCKS_PER_HOUR_BAD)))
    rule(getString(R.string.dop_maths_straightin_title), getString(R.string.dop_maths_up_to, Math.round(t.URGENT_OPEN_MAX_POINTS)),
        getString(R.string.dop_maths_straightin_body, Math.round(t.URGENT_OPEN_POINTS), t.URGENT_OPEN_SECONDS))
    rule(getString(R.string.dop_maths_checking_title), getString(R.string.dop_maths_up_to, Math.round(t.CHECKS_MAX_POINTS)),
        getString(R.string.dop_maths_checking_body, t.CHECKS_PER_HOUR_OK, t.CHECKS_PER_HOUR_BAD))
    rule(getString(R.string.dop_maths_scrolling_title), getString(R.string.dop_maths_up_to, Math.round(t.INTERACTIONS_MAX_POINTS)),
        getString(R.string.dop_maths_scrolling_body, Math.round(t.INTERACTIONS_PER_MIN_OK), Math.round(t.INTERACTIONS_PER_MIN_BAD)))
    rule(getString(R.string.dop_maths_lying_title), getString(R.string.dop_maths_up_to, Math.round(t.LYING_MAX_POINTS)),
        getString(R.string.dop_maths_lying_body, t.POSTURE_FULL_HOURS.toInt()))
    rule(getString(R.string.dop_maths_dark_title), getString(R.string.dop_maths_up_to, Math.round(t.DARK_MAX_POINTS)),
        getString(R.string.dop_maths_dark_body, t.POSTURE_FULL_HOURS.toInt()))

    c.addView(statHeader(getString(R.string.dop_maths_h3), dp))
    c.addView(TextView(this).apply {
        text = getString(R.string.dop_maths_screenoff, Math.round(t.SCREEN_OFF_MAX_POINTS), t.SCREEN_OFF_FULL_HOURS.toInt())
        textSize = 13f; setTextColor(Palette.labelSecondary); setLineSpacing(0f, 1.15f)
    })

    c.addView(statHeader(getString(R.string.dop_maths_h4), dp))
    listOf(0, 15, 30, 45, 60, 80).forEach { lo ->
        c.addView(TextView(this).apply {
            text = getString(R.string.dop_maths_band_line, lo, DopamineScore.bandLabel(this@MainActivity, lo))
            textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(t.bandColour(lo)); setPadding(0, (6 * dp).toInt(), 0, 0)
        })
    }

    c.addView(statHeader(getString(R.string.dop_maths_h5), dp))
    c.addView(TextView(this).apply {
        text = getString(R.string.dop_maths_cant, t.WAKE_GAP_HOURS.toInt())
        textSize = 13f; setTextColor(Palette.labelTertiary); setLineSpacing(0f, 1.15f)
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
        text = label; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
    })
    texts.addView(TextView(this).apply {
        text = detail; textSize = 13f; setTextColor(Palette.labelTertiary)
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
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val today = DopamineLog.today(this)
    val r = DopamineScore.of(this, today)
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.dop_guid_title)))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { showDopamine() }

    // The part aimed squarely at what THEIR number is actually made of.
    val top = r.contributors.firstOrNull { it.points > 0 }
    if (top != null) {
        c.addView(statHeader(getString(R.string.dop_guid_biggest), dp))
        c.addView(TextView(this).apply {
            text = getString(R.string.dop_guid_biggest_body, top.label, top.detail, adviceFor(top.key))
            textSize = 15f; setTextColor(Palette.label); setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, (8 * dp).toInt())
        })
    }

    c.addView(statHeader(getString(R.string.dop_guid_moves), dp))
    listOf(
        getString(R.string.dop_guid_h1) to getString(R.string.dop_guid_b1),
        getString(R.string.dop_guid_h2) to getString(R.string.dop_guid_b2),
        getString(R.string.dop_guid_h3) to getString(R.string.dop_guid_b3),
        getString(R.string.dop_guid_h4) to getString(R.string.dop_guid_b4),
        getString(R.string.dop_guid_h5) to getString(R.string.dop_guid_b5),
        getString(R.string.dop_guid_h6) to getString(R.string.dop_guid_b6),
        getString(R.string.dop_guid_h7) to getString(R.string.dop_guid_b7),
    ).forEach { (h, b) ->
        c.addView(TextView(this).apply {
            text = h; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
            setPadding(0, (12 * dp).toInt(), 0, (2 * dp).toInt())
        })
        c.addView(TextView(this).apply {
            text = b; textSize = 14f; setTextColor(Palette.labelSecondary); setLineSpacing(0f, 1.15f)
        })
    }
    c.addView(TextView(this).apply {
        text = getString(R.string.dop_guid_honest)
        textSize = 13f; setTextColor(Palette.labelTertiary); setLineSpacing(0f, 1.15f)
        setPadding(0, (10 * dp).toInt(), 0, (16 * dp).toInt())
    })
}

// Keys on the ScoreLine's STABLE key (category enum name / fixed id), never the localized label.
private fun adviceFor(key: String): String = getString(when (key) {
    DopamineCategory.ADULT.name -> R.string.dop_adv_adult
    DopamineCategory.FAST_VIDEO.name -> R.string.dop_adv_fastvideo
    DopamineCategory.FAST_SOCIAL.name -> R.string.dop_adv_fastsocial
    DopamineCategory.IMPULSE.name -> R.string.dop_adv_impulse
    DopamineCategory.FORUMS_NEWS.name -> R.string.dop_adv_forums
    DopamineCategory.LONG_VIDEO.name -> R.string.dop_adv_longvideo
    DopamineCategory.MOBILE_GAMING.name -> R.string.dop_adv_gaming
    DopamineCategory.GAMBLING.name -> R.string.dop_adv_gambling
    "unlocks" -> R.string.dop_adv_unlocks
    "straightin" -> R.string.dop_adv_straightin
    "checking" -> R.string.dop_adv_checking
    "scrolling" -> R.string.dop_adv_scrolling
    "lying", "dark" -> R.string.dop_adv_lyingdark
    else -> R.string.dop_adv_default
})

// ── Where & how it happens: posture + light at the moment things go wrong ───
//
// Built from the posture/lightLevel now stamped onto every block event and every relapse
// report (SensorContext captures them at the moment, rather than asking you to remember).
// The point is a single honest sentence: "this happens to you lying down, in the dark."
private fun showContextStats() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.stats_context)))
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
                text = getString(R.string.stats_ctx_empty)
                textSize = 15f; setTextColor(Palette.labelSecondary); setLineSpacing(0f, 1.2f)
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
                    getString(R.string.stats_ctx_lying, pct)
                else
                    getString(R.string.stats_ctx_upright, pct))
            }
            topLight?.let { (label, n) ->
                val pct = n * 100 / knownLight.size
                bits.add(getString(R.string.stats_ctx_light, lightWord(label), pct))
            }
            text = bits.joinToString("\n\n")
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
            setLineSpacing(0f, 1.2f); setPadding(0, 0, 0, (18 * dp).toInt())
        })

        // ── the breakdowns ──
        if (known.isNotEmpty()) {
            c.addView(statHeader(getString(R.string.stats_hdr_posture), dp))
            listOf("lying" to getString(R.string.stats_lying), "upright" to getString(R.string.stats_upright)).forEach { (key, label) ->
                c.addView(statBar(label, known.count { it == key }, known.size, Palette.tintDeep, dp))
            }
        }
        if (knownLight.isNotEmpty()) {
            c.addView(statHeader(getString(R.string.stats_hdr_light), dp))
            AppConfig.LightLevel.values().forEach { level ->
                c.addView(statBar(
                    lightWord(level.name).replaceFirstChar { it.uppercase() },
                    knownLight.count { it == level.name }, knownLight.size,
                    Palette.tintDeep, dp,
                ))
            }
        }

        val unknown = postures.size - known.size
        if (unknown > 0) c.addView(TextView(this@MainActivity).apply {
            text = getString(R.string.stats_ctx_unknown, unknown)
            textSize = 12f; setTextColor(Palette.labelTertiary)
            setPadding(0, (16 * dp).toInt(), 0, (16 * dp).toInt())
        })
    }
}

/** DARK/DULL/NORMAL/BRIGHT -> words a human uses. */
private fun lightWord(level: String): String = when (level) {
    "DARK" -> getString(R.string.light_dark)
    "DULL" -> getString(R.string.light_dim)
    "NORMAL" -> getString(R.string.light_normal)
    "BRIGHT" -> getString(R.string.light_bright)
    else -> level.lowercase()
}

private fun statHeader(text: String, dp: Float): TextView = TextView(this).apply {
    this.text = text; textSize = 11f; setTypeface(typeface, Typeface.BOLD)
    setTextColor(Palette.labelTertiary); setPadding(0, (10 * dp).toInt(), 0, (8 * dp).toInt())
}

/** One labelled proportional bar: "Lying down   12  (67%)". */
private fun statBar(label: String, count: Int, total: Int, colour: Int, dp: Float): View {
    val pct = if (total == 0) 0 else count * 100 / total
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, (10 * dp).toInt())
    }
    row.addView(TextView(this).apply {
        text = getString(R.string.stats_bar_row, label, count, pct)
        textSize = 14f; setTextColor(Palette.labelSecondary)
        setPadding(0, 0, 0, (4 * dp).toInt())
    })
    // The bar: a filled track inside a grey one, width by weight.
    val track = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 6 * dp; setColor(Palette.hairline)
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
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val s = Progress.snapshot(this)
    val green = Palette.successText; val teal = Palette.tint
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.stats_progress_title)))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { showStatsMenu() }

    if (!s.hasData) {
        c.addView(TextView(this).apply {
            text = getString(R.string.stats_prog_empty)
            textSize = 15f; setTextColor(Palette.labelSecondary); setPadding(0, (12 * dp).toInt(), 0, 0)
        })
        return
    }

    // headline: consistency that never resets to zero
    c.addView(statBigCard("${s.consistency}%", getString(R.string.stats_prog_consistency),
        getString(R.string.stats_prog_clean, s.cleanDays, s.trackedDays), green))
    c.addView(TextView(this).apply {
        text = getString(R.string.stats_prog_noreset)
        textSize = 13f; setTextColor(Palette.labelSecondary); setPadding(0, (8 * dp).toInt(), 0, 0)
    })
    if (s.forgivingRun > 0) c.addView(TextView(this).apply {
        text = getString(R.string.stats_prog_run, s.forgivingRun, if (s.forgivingRun == 1) "" else "s")
        textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(green); setPadding(0, (8 * dp).toInt(), 0, 0)
    })

    c.addView(sectionTitle(getString(R.string.stats_sec_reclaimed)))
    c.addView(statBigCard(Units.hours(this, s.reclaimedHours), getString(R.string.stats_prog_reclaimed_label),
        getString(R.string.stats_prog_reclaimed_sub, Progress.EST_MIN_PER_WIN), teal))

    c.addView(sectionTitle(getString(R.string.stats_sec_heading_right)))
    c.addView(TrendView(this, s.weeklyWins), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, (120 * dp).toInt()))
    c.addView(TextView(this).apply {
        text = getString(R.string.stats_prog_weekly)
        textSize = 12f; setTextColor(Palette.labelTertiary); setPadding(0, (4 * dp).toInt(), 0, 0)
    })

    c.addView(sectionTitle(getString(R.string.stats_sec_pace)))
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val cardH = statBigCard("~" + Units.hours(this, s.projYearHours), getString(R.string.stats_prog_per_year), null, teal).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = (4 * dp).toInt() }
    }
    val cardM = statBigCard("~" + Units.money(this, s.projYearMoney), getString(R.string.stats_prog_per_year), null, green).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (4 * dp).toInt() }
    }
    row.addView(cardH); row.addView(cardM)
    c.addView(row)
    c.addView(TextView(this).apply {
        text = getString(R.string.stats_prog_projected, Units.money(this@MainActivity, Progress.VALUE_PER_HOUR))
        textSize = 12f; setTextColor(Palette.labelTertiary); setPadding(0, (4 * dp).toInt(), 0, 0)
    })

    c.addView(sectionTitle(getString(R.string.stats_sec_milestones)))
    if (s.milestones.isEmpty()) c.addView(TextView(this).apply {
        text = getString(R.string.stats_prog_no_milestones); textSize = 14f; setTextColor(Palette.labelTertiary)
    })
    s.milestones.forEach { m ->
        c.addView(TextView(this).apply {
            text = getString(R.string.stats_prog_milestone_item, m); textSize = 15f; setPadding(0, (5 * dp).toInt(), 0, (5 * dp).toInt())
        })
    }
    s.nextMilestone?.let { nm ->
        c.addView(TextView(this).apply {
            text = getString(R.string.stats_prog_next, nm); textSize = 14f; setTextColor(Palette.labelTertiary)
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        })
    }
}

/**
 * A big stat NUMBER. Every value here carries a unit ("1.408 h", "16.890 $"), and a unit
 * split off its number is nonsense - a cell narrow enough to wrap once rendered
 * "16.890 U" / "SD". So: one line, no hyphenation, no clever line breaking, and if a long
 * currency CODE still will not fit (CHF, SEK - CLDR gives those no symbol in any language)
 * the type shrinks rather than the value breaking apart.
 */
private fun statValue(value: String, sizeSp: Float, colour: Int): TextView =
    TextView(this).apply {
        text = value; textSize = sizeSp; setTypeface(typeface, Typeface.BOLD); setTextColor(colour)
        maxLines = 1
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        // Autosizing needs a bounded width - it is undefined against wrap_content.
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            this, 11, sizeSp.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }

private fun statBigCard(value: String, label: String, sub: String?, accent: Int): LinearLayout {
    val dp = resources.displayMetrics.density
    return glassCard(Space.md).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (Space.xs * dp).toInt(); bottomMargin = (Space.xs * dp).toInt() }
        addView(statValue(value, 32f, accent))
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = Type.callout; setTextColor(Palette.labelSecondary)
        })
        if (sub != null) addView(TextView(this@MainActivity).apply {
            text = sub; textSize = Type.caption; setTextColor(Palette.labelTertiary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, (Space.xxs * dp).toInt(), 0, 0)
        })
    }
}

private fun statsPage(title: String, back: () -> Unit, build: (LinearLayout) -> Unit) {
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
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
    statsPage(getString(R.string.stats_temptation), { showStatsMenu() }) { c ->
        if (events.isEmpty()) { c.addView(emptyStat()); return@statsPage }
        c.addView(summaryLine(getString(R.string.stats_temp_count, events.size)))
        c.addView(sectionTitle(getString(R.string.stats_sec_time)))
        val hours = IntArray(24); events.forEach { hours[hourOf(it.ts).coerceIn(0, 23)]++ }
        c.addView(vBars(hours, HOUR_LABELS))
        c.addView(sectionTitle(getString(R.string.stats_sec_dow)))
        c.addView(hBars(DOW_ORDER.map { d -> d to events.count { dowName(it.ts) == d } }))
        c.addView(sectionTitle(getString(R.string.stats_sec_where)))
        c.addView(hBars(topCounts(events.mapNotNull { it.location }).map { optLabel("location", it.first) to it.second }))
        c.addView(sectionTitle(getString(R.string.stats_sec_saw)))
        c.addView(hBars(topCounts(events.mapNotNull { it.screen }).map { optLabel("screen", it.first) to it.second }))
        c.addView(sectionTitle(getString(R.string.stats_sec_felt)))
        c.addView(hBars(topCounts(events.mapNotNull { it.feeling }).map { optLabel("feeling", it.first) to it.second }))
        c.addView(sectionTitle(getString(R.string.stats_sec_urge)))
        c.addView(hBars(Opts.URGE_LEVELS.map { lvl -> optLabel("urge", lvl) to events.count { it.urge == lvl } }))
        c.addView(sectionTitle(getString(R.string.stats_sec_14d)))
        c.addView(vBars(TemptationLog.dailyCounts(this, 14), mapOf(0 to "-13", 13 to "now")))
    }
}

private fun showRelapseStats() {
    statsPage(getString(R.string.stats_relapse), { showStatsMenu() }) { c ->
        c.addView(summaryLine(getString(R.string.common_loading)))
        lifecycleScope.launch {
            val list = RelapseLog.all(this@MainActivity)
            c.removeAllViews()
            if (list.isEmpty()) { c.addView(emptyStat()); return@launch }
            c.addView(summaryLine(getString(R.string.stats_relapse_count, list.size)))
            c.addView(sectionTitle(getString(R.string.stats_sec_time)))
            val hours = IntArray(24); list.forEach { if (it.hourOfDay in 0..23) hours[it.hourOfDay]++ }
            c.addView(vBars(hours, HOUR_LABELS))
            c.addView(sectionTitle(getString(R.string.stats_sec_dow)))
            val cal = java.text.DateFormatSymbols(Locale.getDefault()).shortWeekdays  // [_,Sun,Mon..Sat]
            c.addView(hBars(DOW_ORDER.map { d -> d to list.count { cal.getOrElse(it.dayOfWeek) { "" } == d } }))
            c.addView(sectionTitle(getString(R.string.stats_sec_where)))
            c.addView(hBars(topCounts(list.mapNotNull { it.room }).map { optLabel("location", it.first) to it.second }))
            c.addView(sectionTitle(getString(R.string.stats_sec_felt)))
            c.addView(hBars(topCounts(list.mapNotNull { it.feeling }).map { optLabel("feeling", it.first) to it.second }))
            c.addView(sectionTitle(getString(R.string.stats_sec_ledin)))
            c.addView(hBars(topCounts(list.mapNotNull { it.activity }).map { optLabel("activity", it.first) to it.second }))
        }
    }
}

private fun showLoosenStats() {
    val events = LoosenLog.all(this)
    statsPage(getString(R.string.stats_unlock), { showStatsMenu() }) { c ->
        if (events.isEmpty()) { c.addView(emptyStat()); return@statsPage }
        c.addView(summaryLine(getString(R.string.stats_loosen_count, events.size)))
        c.addView(sectionTitle(getString(R.string.stats_sec_ended)))
        val names = mapOf("stopped" to getString(R.string.stats_outcome_stopped), "tomorrow" to getString(R.string.stats_outcome_tomorrow), "looked" to getString(R.string.stats_outcome_looked))
        c.addView(hBars(listOf("stopped", "tomorrow", "looked")
            .map { (names[it] ?: it) to events.count { e -> e.outcome == it } }))
        c.addView(sectionTitle(getString(R.string.stats_sec_quiet)))
        c.addView(hBars(topCounts(events.mapNotNull { it.feeling }).map { optLabel("feeling", it.first) to it.second }))
        c.addView(sectionTitle(getString(R.string.stats_sec_time)))
        val hours = IntArray(24); events.forEach { hours[hourOf(it.ts).coerceIn(0, 23)]++ }
        c.addView(vBars(hours, HOUR_LABELS))
    }
}

// ── chart building blocks ──────────────────────────────────────────────────
private fun emptyStat(): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = getString(R.string.stats_empty); textSize = 15f; setTextColor(Palette.labelTertiary)
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
        text = t; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.labelSecondary)
        setPadding(0, (18 * dp).toInt(), 0, (6 * dp).toInt())
    }
}

private fun hBars(pairs: List<Pair<String, Int>>): View {
    val dp = resources.displayMetrics.density
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (pairs.isEmpty()) {
        col.addView(TextView(this).apply { text = getString(R.string.misc_no_data); textSize = 13f; setTextColor(Palette.labelTertiary) })
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
                cornerRadius = 3 * dp; setColor(Palette.series[1])
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
                cornerRadius = 2 * dp; setColor(if (v > 0) Palette.series[1] else 0x22000000)
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
                setTextColor(Palette.labelTertiary)
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
            getString(R.string.ride_walk_q), R.drawable.ic_opt_outside,
            getString(R.string.ride_walk_yes), { waveSuccess() },
            getString(R.string.ride_walk_no), { waveMove() },
        )
    }
    private fun waveMove() {
        tBack = { waveWalk() }
        waveActionScreen(
            getString(R.string.ride_move_q), R.drawable.ic_opt_door,
            getString(R.string.ride_move_yes), { waveSuccess() },
            getString(R.string.ride_move_no), { wavePhysical() },
        )
    }
    private fun wavePhysical() {
        tBack = { waveMove() }
        waveActionScreen(
            getString(R.string.ride_phys_q), R.drawable.ic_opt_dumbbell,
            getString(R.string.ride_phys_yes), { waveSuccess() },
            getString(R.string.ride_phys_no), { waveStuck() },
        )
    }

    private fun waveStuck() {
        tBack = { wavePhysical() }
        waveBreatheScreen(
            getString(R.string.ride_stuck_title),
            getString(R.string.ride_stuck_body),
            getString(R.string.ride_stuck_btn),
        ) { wavePeakScreen() }
    }

    /** After the breathing: make a real moment of "you're already past the peak". */
    private fun wavePeakScreen() {
        tBack = { waveStuck() }
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(heroIcon(R.drawable.ic_opt_wave))
        root.addView(TextView(this).apply {
            text = getString(R.string.ride_peak_title)
            textSize = 26f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, (10 * dp).toInt())
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.ride_peak_body)
            textSize = 16f; gravity = Gravity.CENTER; setTextColor(Palette.labelSecondary)
        })
        root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bigChoice(getString(R.string.temp_ride_done_btn), Palette.successText) { waveSuccess() })
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
        val pad = (Space.page * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(titleText(getString(R.string.temp_ridedone_title)))
        root.addView(TextView(this).apply {
            text = getString(R.string.temp_ridedone_body)
            textSize = 16f; setTextColor(Palette.labelSecondary); setPadding(0, (4 * dp).toInt(), 0, 0)
        })
        // urge over time: it spikes, then falls - and you're already past the peak.
        root.addView(PeakCurveView(this), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = (12 * dp).toInt(); bottomMargin = (12 * dp).toInt() })
        root.addView(TextView(this).apply {
            text = getString(R.string.ride_success_stats, total, week)
            textSize = 15f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * dp).toInt())
        })
        root.addView(captionedButton(getString(R.string.temp_put_down), getString(R.string.temp_put_down_sub), Palette.successText) {
            try { finishAffinity() } catch (_: Throwable) { setupMainScreen() }
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.ride_lock_apps); textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Palette.tintDeep); isClickable = true; isFocusable = true
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener {
                Lockdown.start(this@MainActivity)
                Toast.makeText(this@MainActivity, getString(R.string.temp_lockdown_toast), Toast.LENGTH_LONG).show()
                showReportScreen()
            }
        })
        setContentView(root)
    }

// ── reusable ride pieces ───────────────────────────────────────────────────
private fun waveBreatheScreen(title: String, side: String, continueLabel: String, onContinue: () -> Unit) {
    val dp = resources.displayMetrics.density
    val pad = (Space.page * dp).toInt()
    val totalRounds = 3
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(titleText(title))
    root.addView(TextView(this).apply {
        text = side; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Palette.labelSecondary)
        setPadding(0, 0, 0, (4 * dp).toInt())
    })

    // The sweep, straight on the page (no dark card), filling the free space.
    val sweep = SweepPanelView(this, Palette.sweep)
    root.addView(FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(sweep, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    })

    val counter = TextView(this).apply {
        text = getString(R.string.ride_follow_sweep, totalRounds)
        textSize = 14f; gravity = Gravity.CENTER; setTextColor(Palette.successText)
        setPadding(0, (6 * dp).toInt(), 0, 0)
    }
    root.addView(counter)
    val milestone = TextView(this).apply {
        textSize = 13f; gravity = Gravity.CENTER; setTextColor(Palette.successText); setPadding(0, (8 * dp).toInt(), 0, 0)
    }
    root.addView(milestone)

    val continueBtn = bigContinue(continueLabel) { onContinue() }
    root.addView(continueBtn)
    setContentView(root)

    stopRideTimer()   // cancels any sweep/timer left over from a previous wave screen
    waveSweep = SweepAnimator(sweep).also { a ->
        a.start(
            cycles = totalRounds,
            onCycle = { done, total ->
                if (done >= total) {
                    counter.text = getString(R.string.temp_ride_paced)
                    tuneContinue(continueBtn, true)
                } else {
                    counter.text = getString(R.string.temp_ride_counter, done, total)
                }
            },
        )
    }
    attachWaveTimer(milestone)
}

private fun waveActionScreen(
    prompt: String, icon: Int,
    yesLabel: String, onYes: () -> Unit, noLabel: String, onNo: () -> Unit,
    tertiaryLabel: String? = null, onTertiary: (() -> Unit)? = null,
) {
    val dp = resources.displayMetrics.density
    val pad = (Space.page * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    // The hero mark. Big, thin and in the brand tint - it carries the screen the way the
    // 72sp emoji did, without shouting or looking like a different app on every phone.
    root.addView(heroIcon(icon))
    root.addView(TextView(this).apply {
        text = prompt; textSize = 23f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding((8 * dp).toInt(), (18 * dp).toInt(), (8 * dp).toInt(), 0)
    })
    val milestone = TextView(this).apply {
        textSize = 13f; gravity = Gravity.CENTER; setTextColor(Palette.successText)
        setPadding(0, (10 * dp).toInt(), 0, 0)
    }
    root.addView(milestone)
    root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(bigChoice(yesLabel, Palette.successText) { onYes() })
    root.addView(Button(this).apply { text = noLabel; setAllCaps(false); setOnClickListener { onNo() } })
    if (tertiaryLabel != null && onTertiary != null) {
        root.addView(TextView(this).apply {
            text = tertiaryLabel; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Palette.labelSecondary)
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
                sec >= 600 -> getString(R.string.ride_ms_10)
                sec >= 120 -> getString(R.string.ride_ms_2)
                sec >= 60 -> getString(R.string.ride_ms_1)
                sec >= 30 -> getString(R.string.ride_ms_30)
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
                cornerRadius = 3 * dp; setColor(if (v > 0) Palette.successText else 0x22000000)
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
        val pad = (Space.page * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(titleText(getString(R.string.appsite_kind_title)))
        root.addView(TextView(this).apply {
            text = getString(R.string.appsite_kind_subtitle)
            textSize = 14f; setTextColor(Palette.labelSecondary)
            setPadding(0, 0, 0, (16 * dp).toInt())
        })
        root.addView(bigChoice(getString(R.string.appsite_kind_app), Palette.tint) { appSiteChooseApp() })
        root.addView(bigChoice(getString(R.string.appsite_kind_website), Palette.tint) { appSiteChooseSite() })
        setContentView(root)
    }

private fun appSiteChooseSite() {
    val dp = resources.displayMetrics.density
    val pad = (Space.page * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(titleText(getString(R.string.appsite_site_title)))
    val urlInput = EditText(this).apply {
        hint = getString(R.string.appsite_site_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        maxLines = 1
    }
    root.addView(urlInput)
    root.addView(tierNote())
    root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(bigChoice(getString(R.string.appsite_greylist_it, GreyUsage.LIMIT_MIN), Palette.tint) {
        saveSiteRule(urlInput, AppRules.GREY)
    })
    root.addView(bigChoice(getString(R.string.appsite_blocklist_it), Palette.dangerText) {
        saveSiteRule(urlInput, AppRules.BLOCK)
    })
    setContentView(root)
}


private data class AppRow(val label: String, val pkg: String, val icon: android.graphics.drawable.Drawable?)

private fun appSiteChooseApp() {
    val dp = resources.displayMetrics.density
    val pad = (Space.page * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(titleText(getString(R.string.appsite_pick_app)))
    val loading = TextView(this).apply { text = getString(R.string.appsite_loading); textSize = 14f }
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
    val pad = (Space.page * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(titleText(getString(R.string.appsite_limit_app, a.label)))
    root.addView(tierNote())
    root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(bigChoice(getString(R.string.appsite_greylist, GreyUsage.LIMIT_MIN), Palette.tint) {
        AppRules.setApp(this, a.pkg, AppRules.GREY); appSiteSaved(a.label, AppRules.GREY)
    })
    root.addView(bigChoice(getString(R.string.appsite_blocklist), Palette.dangerText) {
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

/**
 * The pre-defined WHITELIST (AppConfig.SAFE_APPS_BY_NAME) - the apps the user is
 * never blocked on by default (Maps, WhatsApp, Camera, this app, …) - shown so the
 * user can opt to block one anyway with a tap. A blocked app is struck through and
 * tagged "Blocked" in place. Unblocking is only allowed in Relaxed mode - in any
 * stricter mode a tap on a blocked app is refused, so a block made here is a
 * commitment like every other block. Only apps actually installed are listed.
 */
private fun showBlockApps() {
    inSubPage = true
    val dp = resources.displayMetrics.density
    val pad = (Space.page * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(titleText(getString(R.string.blockapps_title)))
    root.addView(TextView(this).apply {
        text = getString(R.string.blockapps_subtitle_prefix) +
            (if (Mode.isRelaxed(this@MainActivity)) getString(R.string.blockapps_subtitle_relaxed)
             else getString(R.string.blockapps_subtitle_strict))
        textSize = 13f; setTextColor(Palette.labelSecondary)
        setPadding(0, 0, 0, (10 * dp).toInt())
    })
    val loading = TextView(this).apply { text = getString(R.string.appsite_loading); textSize = 14f }
    root.addView(loading)
    val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(listLayout)
    })
    setContentWithThumb(root) { showReportScreen() }

    lifecycleScope.launch(Dispatchers.IO) {
        val apps = loadWhitelistedApps()
        runOnUiThread {
            loading.visibility = View.GONE
            if (apps.isEmpty()) listLayout.addView(TextView(this@MainActivity).apply {
                text = getString(R.string.blockapps_none_installed); textSize = 14f
            })
            apps.forEach { a -> listLayout.addView(blockAppRow(a)) }
        }
    }
}

/** The pre-defined whitelist, limited to apps actually installed, with real icons. */
private fun loadWhitelistedApps(): List<AppRow> {
    val pm = packageManager
    return AppConfig.SAFE_APPS_BY_NAME.mapNotNull { (name, pkg) ->
        val info = try { pm.getApplicationInfo(pkg, 0) } catch (t: Throwable) { null }
            ?: return@mapNotNull null              // not installed - don't list it
        val icon = try { pm.getApplicationIcon(info) } catch (t: Throwable) { null }
        AppRow(name, pkg, icon)
    }.sortedBy { it.label.lowercase() }
}

private fun blockAppRow(a: AppRow): LinearLayout {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt())
        isClickable = true; isFocusable = true
    }
    val icon = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
        if (a.icon != null) setImageDrawable(a.icon)
    }
    val name = TextView(this).apply {
        text = a.label; textSize = 16f; setPadding((12 * dp).toInt(), 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val defaultColors = name.textColors    // restore this when un-blocking (theme-safe)
    val status = TextView(this).apply {
        text = getString(R.string.blockapps_blocked_tag); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Palette.dangerText)
    }
    row.addView(icon); row.addView(name); row.addView(status)

    fun render() {
        if (AppRules.appTier(this, a.pkg) == AppRules.BLOCK) {
            name.paintFlags = name.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            name.setTextColor(Palette.labelTertiary)
            icon.alpha = 0.4f
            status.visibility = View.VISIBLE
        } else {
            name.paintFlags = name.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            name.setTextColor(defaultColors)
            icon.alpha = 1f
            status.visibility = View.GONE
        }
    }
    render()

    row.setOnClickListener {
        when {
            AppRules.appTier(this, a.pkg) != AppRules.BLOCK ->
                { AppRules.setApp(this, a.pkg, AppRules.BLOCK); render() }
            Mode.isRelaxed(this) ->
                { AppRules.remove(this, true, a.pkg); render() }
            else ->
                Toast.makeText(this, getString(R.string.blockapps_cant_unblock), Toast.LENGTH_SHORT).show()
        }
    }
    return row
}

private fun saveSiteRule(input: EditText, tier: String) {
    if (tier == AppRules.BLOCK) {
        val rule = ruleFromInput(input.text.toString())
        if (rule == null) { Toast.makeText(this, getString(R.string.appsite_bad_url), Toast.LENGTH_SHORT).show(); return }
        // keeps the path -> blocks that page, not the whole site
        BlockRules.add(this, rule, BlockRules.Note(BlockRules.Origin.MANUAL))
        appSiteSaved(rule, AppRules.BLOCK)
    } else {
        val host = hostOf(input.text.toString())
        if (host == null) { Toast.makeText(this, getString(R.string.appsite_bad_url), Toast.LENGTH_SHORT).show(); return }
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
    val pad = (Space.page * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(titleText(getString(R.string.appsite_saved_title)))
    root.addView(TextView(this).apply {
        val state = if (tier == AppRules.GREY)
            getString(R.string.appsite_saved_greylisted, GreyUsage.LIMIT_MIN)
        else getString(R.string.appsite_saved_blocklisted)
        text = getString(R.string.appsite_saved_msg, target, state)
        textSize = 16f; setPadding(0, (12 * dp).toInt(), 0, 0)
    })
    val spacer = View(this)
    root.addView(spacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(Button(this).apply { text = getString(R.string.appsite_add_another); setOnClickListener { appSiteChooseKind() } })
    root.addView(Button(this).apply { text = getString(R.string.common_done); setOnClickListener { showReportScreen() } })
    setContentView(root)
}

private fun tierNote(): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = getString(R.string.appsite_tier_note, GreyUsage.LIMIT_MIN)
        textSize = 13f; setTextColor(Palette.labelSecondary)
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
        .setTitle(getString(R.string.recent_title))
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
                    text = getString(R.string.recent_empty)
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
                // The reason was being recorded and never shown, so this list answered
                // "what got blocked" and never "why" - the one question you open it for.
                val scoreTag = e.score?.let { "[score $it]  " } ?: "[no score]  "
                val why = e.reason?.replace("\n", " \u00b7 ")?.ifBlank { null } ?: "(no reason recorded)"
                val before = e.recentAppsList().joinToString(", ").ifBlank { "-" }
                row.addView(TextView(this@MainActivity).apply {
                    text = "${stamp.format(Date(e.timestamp))}\n$scoreTag$shortTarget\nwhy: $why\nbefore: $before"
                    textSize = 13f
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this@MainActivity).apply {
                    text = getString(R.string.common_remove)
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
// ── Bottom navigation: a floating pill, not a bar welded to the bottom edge ─────────
// The pill's geometry, in one place - the reserved space below is derived from it, so a
// change to the padding cannot silently leave the last card stranded under the pill.
private val barIconDp = 22          // the glyph itself
private val barInsetDp = 6          // pill edge -> tab capsule
private val barTabPadV = 10         // capsule padding, vertical
private val barTabPadH = 18         // capsule padding, horizontal (roomier: it is a capsule)
private val barLiftDp = Space.md    // how far the pill hovers above the bottom edge

private val barPillHeight get() = dp(barIconDp + barTabPadV * 2 + barInsetDp * 2)
private val barReservedSpace get() = barPillHeight + dp(barLiftDp + Space.sm)

/**
 * The tab bar. A single floating glass pill, centred over the content, with the selected
 * tab carried in a soft tinted capsule inside it.
 *
 * WHY IT FLOATS. The old bar was a full-width slab pinned to the bottom with a hairline
 * above it: it cut the screen off at a hard line and read as chrome bolted onto the page.
 * A pill that hovers - inset from all three edges, rounded to a capsule, with content
 * running underneath it - reads as an object sitting ON the app rather than the app's
 * bottom edge. It costs less width than it looks like it does (two tabs never needed the
 * whole screen), and it gives the content the full height of the display.
 *
 * TWO TABS, and it stays two. Productivity used to be a third one, but it is a page you
 * open when you want to look at your numbers, not somewhere you live - so it costs a
 * permanent third of the bar and earns a visit a week. It is now a section on Overview
 * (see setupHomeScreen) and reached like any other sub-page. Overview is where you land;
 * Temptations is what you reach for in the moment. Anything else is a sub-page.
 *
 * The selected capsule is Palette.tintSoft rather than an 8%-alpha teal: a solid soft
 * colour stays the same over any content, where an alpha wash shifted with whatever
 * scrolled underneath it - and under a floating pill, something is ALWAYS scrolling
 * underneath it.
 *
 * The content keeps scrolling behind the pill on purpose (clipToPadding = false), the way
 * an iOS tab bar does; the bottom padding added below is what stops the last card ending
 * up permanently parked under it.
 */
private fun withBottomBar(content: View, selected: Int): View {
    val root = FrameLayout(this).apply {
        setBackgroundColor(Palette.bg)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    // Room at the bottom of the scroll for the pill to hover over. Applied to the view we
    // were handed, so a ScrollView gets extra scroll travel (content passes under the
    // pill) and a plain column just gets a shorter box (content stops above it).
    content.setPadding(
        content.paddingLeft, content.paddingTop,
        content.paddingRight, content.paddingBottom + barReservedSpace)
    (content as? ViewGroup)?.clipToPadding = false
    root.addView(content, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    // The page fades up; the PILL DOES NOT. setContentView animates whatever it is handed,
    // and handing it the whole frame meant the bar rose 8dp with the content on every tab
    // change - a floating object that re-lands each time you touch it. Animate the content
    // here instead and tell setContentView to leave the frame alone (see skipEnter).
    content.enterFromBelow()
    skipEnter = true

    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = surfaceBg(Palette.glass, Radius.pill, stroke = Palette.hairline)
        // The one place in the app that gets a real shadow: a floating object has to read
        // as floating, and a hairline alone cannot say "above the page".
        elevation = dpf(12f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineSpotShadowColor = 0x40101820
            outlineAmbientShadowColor = 0x30101820
        }
        val inset = dp(barInsetDp)
        setPadding(inset, inset, inset, inset)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(barLiftDp)
            marginStart = dp(Space.md); marginEnd = dp(Space.md)
        }
    }
    val tabs = listOf(
        Triple(R.drawable.ic_nav_overview, getString(R.string.nav_overview)) { switchTab(0) },
        Triple(R.drawable.ic_nav_temptations, getString(R.string.nav_temptations)) { switchTab(1) },
    )
    tabs.forEachIndexed { i, (icon, label, go) ->
        val sel = i == selected
        val colour = if (sel) Palette.tintDeep else Palette.labelTertiary
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background =
                if (sel) surfaceBg(Palette.tintSoft, Radius.pill, stroke = null)
                else tappableBg(0x00000000, Radius.pill, stroke = null)
            setPadding(dp(barTabPadH), dp(barTabPadV), dp(barTabPadH), dp(barTabPadV))
            isClickable = true; isFocusable = true
            setOnClickListener { if (!sel) go() }
            if (!sel) pressable()
            addView(ImageView(this@MainActivity).apply {
                setImageResource(icon); setColorFilter(colour)
            }, LinearLayout.LayoutParams(dp(barIconDp), dp(barIconDp)))
            addView(TextView(this@MainActivity).apply {
                // BOTH labels are bold, always. Selection is said with colour and the
                // capsule behind it - never with weight, because bold text is WIDER, and a
                // wrap-content pill that changes width when you change tab visibly jumps as
                // it re-centres itself. The pill must be the same size on both tabs.
                text = label; textSize = Type.footnote; setTextColor(colour)
                letterSpacing = -0.01f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(Space.xs) })
        })
    }
    root.addView(bar)
    // THE TAB REMEMBERS ITSELF FROM THE MOMENT IT IS BUILT. Recording it here, rather than
    // on the way out, is what makes "back" reliable: sub-pages clear the tab flags at
    // different points in their own render, so there is no single moment on the way out
    // when "am I leaving a tab root?" is dependably true. At this point it always is.
    tabMemory[selected] = TabMemory(root, null, onReport = false, onDev = false, inSub = false)
    activeTab = selected
    return root
}

// ── Disguised home: a productivity face; the addiction tools live behind a tab ─
// Order, deliberately: the dopamine baseline + rank first (the thing to care about),
// then the usage graphs (what it costs), then the cost donut and the daily goal, the
// sensors and status consoles, a tiny about link, and dev tools dead last. Temptations is
// the other bottom-bar tab, so it needs no door here.
private fun setupHomeScreen() {
    // BACK is a return, not an arrival: it hands you the page you left, scrolled where you
    // left it (see restoreTab). Every OTHER route here - a resume, a mode change, finishing
    // a flow, "Set" on the usage goal - rebuilds, because those are the moments the numbers
    // on this page have actually changed and a preserved snapshot would be a stale one.
    if (inBackNav && restoreTab(0)) return
    onHomeScreen = true; onTemptationsTab = false; onReportScreen = false; onDevScreen = false
    subBack = null
    claimTab(0)
    inSubPage = false; inTemptationFlow = false
    inLoosenFlow = false; inAppSiteFlow = false
    stopRideTimer(); stopLoosenTimer(); entriesJob?.cancel()
    markTabSeen("overview")
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

    // Quiet amber nudge: the permissions are optional in Off mode, but they're the point.
    homeBuiltWithNudge = shouldNudgePermissions()
    if (homeBuiltWithNudge) content.addView(permissionNudgeBanner())

    // ── 1. Productivity score: today's score and the seven-day average, then 14 days of
    //    daily bars wearing the score's band colours - so a bad stretch is a run of red.
    //    Two numbers and a graph; everything else this card used to carry said the same
    //    thing again in a different shape.
    val today = DopamineScore.of(this, DopamineLog.today(this))
    // The seven-day average, shown beside today's score. Null when no day in the week scored.
    val weekAvgScore = DopamineLog.history(this, 7)
        .map { DopamineScore.of(this@MainActivity, it) }.filter { it.hasData }
        .takeIf { it.isNotEmpty() }?.map { it.score }?.average()?.let { Math.round(it).toInt() }
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
    val weekdayFmt = SimpleDateFormat("EEE", Locale.getDefault())
    content.addView(homeHeading(getString(R.string.home_productivity_title), getString(R.string.home_productivity_sub)))
    val dopCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glassBg(); elevation = 1f * dp
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        isClickable = true; isFocusable = true
        setOnClickListener { dopamineBack = { setupHomeScreen() }; showDopamine() }
    }
    // Two lines and the graph, and that is the whole card. What used to be here - the
    // vertical band gauge, a 44sp copy of today's score, and the rank's flavour name -
    // was three ways of saying one number before the chart underneath said it again. The
    // section heading above names the card, exactly like every other section on this page;
    // the gauge and the rank still live on the score page the card opens.
    dopCard.addView(TextView(this).apply {
        text = getString(R.string.home_prod_daily,
            if (today.hasData) Units.number(this@MainActivity, today.score) else "-")
        textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (today.hasData) today.colour else Palette.labelTertiary)
    })
    dopCard.addView(TextView(this).apply {
        text = getString(R.string.home_prod_weekly,
            weekAvgScore?.let { Units.number(this@MainActivity, it) } ?: "-")
        textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Palette.labelSecondary)
    })
    val history14 = DopamineLog.history(this, 14)
    val realScores = history14.map { DopamineScore.of(this@MainActivity, it).let { r -> if (r.hasData) r.score.toFloat() else Float.NaN } }
    val haveTrend = realScores.count { !it.isNaN() } >= 4
    // Example trend: ORGANIC (plateaus, one big drop, small wobbles) descending from
    // its peak to end EXACTLY on today's real score, so the graph always finishes on
    // the number shown beside it.
    val trendEnd = if (today.hasData) today.score.toFloat() else 26f
    val trendStart = (trendEnd + 42f).coerceAtMost(92f)
    val organic = floatArrayOf(1.00f, 0.97f, 0.84f, 0.88f, 0.70f, 0.45f, 0.52f, 0.48f, 0.30f, 0.34f, 0.18f, 0.22f, 0.08f, 0f)
    val trendScores = if (haveTrend) realScores.toFloatArray()
        else FloatArray(14) { i -> trendEnd + (trendStart - trendEnd) * organic[i] }
    val trendLabels = history14.mapIndexed { i, d ->
        if (i % 3 == 0 || i == history14.size - 1) {
            try { weekdayFmt.format(dayFmt.parse(d.date)!!) } catch (_: Throwable) { "" }
        } else ""
    }
    val trendColours = IntArray(trendScores.size) { i ->
        if (trendScores[i].isNaN()) Palette.labelTertiary
        else DopamineTuning.bandColour(Math.round(trendScores[i]))
    }
    // ONE BAR PER DAY. A score is a day's own quantity - it doesn't flow into the next
    // day - so bars say what a line only implied, and each keeps its band colour, so a bad
    // stretch is a run of red bars. Nothing under it: the axis is labelled, and a readout
    // naming the bar the finger is already on adds a line of text and no information.
    val trendChart = StatLineChartView(this, trendScores, trendLabels, hoursUnit = false,
        gridStep = 25f, segmentColours = trendColours, bars = true,
        watermark = if (haveTrend) null else getString(R.string.chart_example))
    dopCard.addView(trendChart, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, (120 * dp).toInt()).apply { topMargin = (10 * dp).toInt() })
    content.addView(dopCard)

    // ── 2. Usage, strava-style. Metric: screen-on time. EVERY chart falls back to
    //    labelled EXAMPLE data whenever its real data is missing OR too thin to mean
    //    anything - and examples always use the real money rate and goal, because the same
    //    graphs get the real arrays later.
    val history90 = DopamineLog.history(this, 90)
    fun hoursOf(day: DopamineDay): Float =
        if (DopamineScore.of(this@MainActivity, day).hasData) day.screenOnSeconds / 3600f else Float.NaN
    // Sensible default baseline: under an hour a day. A user-set goal replaces it.
    val goalHours = UsageGoal.hoursPerDay(this) ?: 1f
    val rate = AboutYou.effectiveHourly(this)

    // This week: ONE BAR PER DAY - legend, stat row, no readout (see the call below).
    val week = history90.takeLast(7)
    val realWeek = week.map { hoursOf(it) }.toFloatArray()
    val weekIsReal = realWeek.count { !it.isNaN() } >= 2 && realWeek.filter { !it.isNaN() }.sum() >= 0.5f
    val weekVals = if (weekIsReal) realWeek else floatArrayOf(3.1f, 2.8f, 4.9f, 4.6f, 1.9f, 5.8f, 3.4f)
    val weekLabels = week.map { d ->
        try { weekdayFmt.format(dayFmt.parse(d.date)!!) } catch (_: Throwable) { "" }
    }
    val weekTotal = weekVals.filter { !it.isNaN() }.sum()
    content.addView(homeHeading(getString(R.string.home_usage_title), getString(R.string.home_usage_week_sub)))
    content.addView(chartStatCard(
        weekVals, weekLabels,
        stats = listOf(
            fmtHours(weekTotal) to getString(R.string.home_time_wasted),
            Units.money(this, Math.round(weekTotal * rate)) to getString(R.string.home_money_wasted),
        ),
        goal = goalHours, gridStep = 1f, minorStep = 0.5f,
        legendMain = getString(R.string.home_legend_week),
        // ONE BAR PER DAY, and no scrub readout. Each bar is a day's screen time on an axis
        // labelled in hours with the goal line across it, which is the whole question this
        // chart answers - the "Sun 23 Aug · 5m (£1)" line under it only restated the bar the
        // finger was already on, and the total and cost are in the stat row underneath.
        bars = true,
        example = !weekIsReal,
    ))

    // By month - only once there's more than two months of real data (or as the example).
    val monthNames = java.text.DateFormatSymbols(Locale.getDefault()).shortMonths  // localized Jan..Dec
    val byMonth = history90.filter { DopamineScore.of(this@MainActivity, it).hasData }
        .groupBy { it.date.substring(0, 7) }.toSortedMap()
    val hasUsage = history90.sumOf { d -> hoursOf(d).takeIf { !it.isNaN() }?.toDouble() ?: 0.0 } >= 1.0
    if ((hasUsage && byMonth.size > 2) || !hasUsage) {
        content.addView(homeHeading(getString(R.string.home_usage_title), getString(R.string.home_usage_month_sub)))
        val monthly: FloatArray; val monthLabels: List<String>
        if (hasUsage) {
            monthly = byMonth.values.map { days -> days.sumOf { it.screenOnSeconds }.toFloat() / 3600f }.toFloatArray()
            monthLabels = byMonth.keys.map { monthNames[it.substring(5, 7).toInt() - 1] }
        } else {
            val thisMonth = SimpleDateFormat("MM", Locale.UK).format(Date()).toInt() - 1
            monthly = floatArrayOf(136f, 104f, 121f)
            monthLabels = (2 downTo 0).map { monthNames[(thisMonth - it + 12) % 12] }
        }
        val monthChart = StatLineChartView(this, monthly, monthLabels, goal = goalHours * 30,
            watermark = if (hasUsage) null else getString(R.string.chart_example))
        content.addView(monthChart,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (130 * dp).toInt()))
    }

    // This year: ONE line, climbing organically day by day, that turns grey and dotted
    // at today - the projection to December. Same card and same behaviour whether the
    // arrays are example or real: the graph just plots what it's given.
    val recentDays = history90.takeLast(30).map { hoursOf(it) }.filter { !it.isNaN() }
    val realAvg = if (recentDays.isNotEmpty()) recentDays.average().toFloat() else 0f
    // Real mode needs SUBSTANTIAL data (two weeks, a real amount per day) - a few thin
    // days must not oust the example with a meaningless near-zero line.
    val yearIsReal = recentDays.size >= 14 && realAvg >= 0.5f
    val avgDaily = if (yearIsReal) realAvg else 3.9f
    content.addView(homeHeading(getString(R.string.home_usage_overview_title), getString(R.string.home_usage_year_sub)))
    val dayOfYear = SimpleDateFormat("D", Locale.UK).format(Date()).toInt().coerceIn(1, 365)
    // Organic daily increments: calm stretches, weekend spikes, the odd binge - so the
    // cumulative line climbs unevenly like a real one (fixed pattern, no shimmer).
    val organicDay = floatArrayOf(0.5f, 0.7f, 0.6f, 1.6f, 1.9f, 0.8f, 0.6f, 0.4f, 1.1f, 1.4f, 0.5f, 0.9f, 2.0f, 0.7f)
    val yearNow = SimpleDateFormat("yyyy", Locale.UK).format(Date())
    val doyFmt = SimpleDateFormat("D", Locale.UK)
    val realByDoy = HashMap<Int, Float>()
    if (yearIsReal) for (d in history90) {
        val h = hoursOf(d); if (h.isNaN() || !d.date.startsWith(yearNow)) continue
        try { realByDoy[doyFmt.format(dayFmt.parse(d.date)!!).toInt()] = h } catch (_: Throwable) {}
    }
    var running = 0f
    val soFar = FloatArray(dayOfYear) { i ->
        running += realByDoy[i + 1] ?: (avgDaily * organicDay[i % organicDay.size])
        running
    }
    val toCome = FloatArray(365 - dayOfYear) { j -> soFar.last() + avgDaily * (j + 1) }
    val monthFirstDays = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
    val yearLabels = (0 until 365).map { d ->
        val m = monthFirstDays.indexOfLast { d >= it }
        if (d == monthFirstDays[m] && m % 2 == 0) monthNames[m] else ""
    }
    val yearHours = toCome.lastOrNull() ?: soFar.last()
    content.addView(chartStatCard(
        soFar, yearLabels,
        stats = listOf(
            Units.hours(this, Math.round(yearHours)) to getString(R.string.home_this_year),
            Units.money(this, Math.round(yearHours * rate)) to getString(R.string.home_of_your_time),
            "${Math.round(yearHours / 3f)}" to getString(R.string.home_evenings),
        ),
        dotted = toCome, goalPerSlot = goalHours,
        worth = getString(R.string.home_worth),
        example = !yearIsReal,
        onStatsClick = { aboutYouBack = { setupHomeScreen() }; showAboutYou() },
    ))

    // ── 3. What it costs, in one picture ────────────────────────────────────
    //    This used to be a heading and a big "Open Productivity" card - a door to a page
    //    that was itself mostly doors. The page is gone (see the note above showUsageGoal):
    //    what people actually took from it was the donut, so the donut is here, on the
    //    page they already open, with nothing under it. One number, no navigation.
    //
    //    It sits directly under the usage chart on purpose and carries no heading of its
    //    own: it is the same subject said a second way - the year in hours, then the day
    //    as a share of the waking one.
    //
    //    THE FRACTION is real once there is enough history to trust (the same >= 14 days
    //    bar the year chart uses); before that it falls back to Usage.minutes, which is
    //    where the old projector's slider left it. It is never allowed to read 0% and
    //    imply a clean slate we have not measured.
    val costMinutes = if (yearIsReal) Math.round(avgDaily * 60f) else Usage.minutes(this)
    content.addView(glassCard(Space.md).apply {
        addView(WastedDonutView(this@MainActivity).apply {
            setFraction(costMinutes / (Usage.WAKING_HOURS * 60f))
        }, LinearLayout.LayoutParams(dp(168), dp(168)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(Space.xs); bottomMargin = dp(Space.xs)
        })
    })

    //    The one figure on the old page you could actually SET, kept because it draws the
    //    target line on every usage graph above.
    content.addView(homeCard(getString(R.string.prod_row_goal), getString(R.string.prod_row_goal_sub),
        R.drawable.ic_opt_hourglass) { showUsageGoal() })

    // sensors console, then the permission/status console, then the quietest about link
    content.addView(sensorsConsole())
    content.addView(permissionConsole())
    content.addView(TextView(this).apply {
        text = getString(R.string.home_about_privacy); textSize = 12f; setTextColor(Palette.labelTertiary)
        gravity = Gravity.CENTER; isClickable = true; isFocusable = true
        setPadding(0, (18 * dp).toInt(), 0, (6 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { showAboutPage() }
    })

    // ── Dev tools: DEAD LAST, below even the about link (only when dev mode is on) ──
    // It used to sit up between the Productivity card and the consoles, which put a door
    // marked "developer" in the middle of the page a user actually reads. It is ours, not
    // theirs: the bottom of the page is exactly where it belongs.
    if (AppConfig.DEV_MODE) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Radius.control * dp; setStroke((1 * dp).toInt(), Palette.labelQuaternary); setColor(0x00000000)
            }
            val p = (14 * dp).toInt(); setPadding(p, (12 * dp).toInt(), p, (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() }
            isClickable = true; isFocusable = true; setOnClickListener { setupMainScreen() }
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.home_dev_tools); textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.labelSecondary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply { text = "›"; textSize = 20f; setTextColor(Palette.labelTertiary) })
        })
    }

    val root = ScrollView(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isFillViewport = true
        addView(content)
    }
    setContentNoThumb(withBottomBar(root, 0))   // the landing screen - nothing behind it
}

private fun fmtHours(h: Float): String = Units.fromHours(this, h)

/** "1h 20m (£16)" - the paired time+money readout under every usage chart. Both halves
 *  are localised (see Units); [whole] rounds to entire hours, for the year-long chart where
 *  the minutes are noise. */
private fun hoursAndMoney(hours: Float, rate: Int, whole: Boolean = false): String =
    getString(R.string.readout_time_money,
        if (whole) Units.hours(this, Math.round(hours)) else fmtHours(hours),
        Units.money(this, Math.round(hours * rate)))

/** A strava-style stat row: big bold values with small grey labels, evenly spread.
 *  One shared builder so home and Productivity arrange their numbers identically. */
private fun statRow(stats: List<Pair<String, String>>, colour: Int, onClick: (() -> Unit)? = null): View {
    val dp = resources.displayMetrics.density
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, (4 * dp).toInt(), 0, 0)
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
        for ((value, label) in stats) addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(statValue(value, 20f, colour))
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 12f; setTextColor(Palette.labelTertiary)
            })
        })
    }
}

/** The two-tone home-page heading: the name, then its qualifier on a SECOND line -
 *  smaller, grey and unbolded ("Phone usage" / "This week"). It used to run them together
 *  on one line with a "·" between, which made the qualifier compete with the name at the
 *  same size and left the row long enough to wrap on a narrow phone. */
private fun homeHeading(primary: String, secondary: String): TextView {
    val dp = resources.displayMetrics.density
    val s = android.text.SpannableString("$primary\n$secondary")
    val cut = primary.length
    s.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, cut, 0)
    s.setSpan(android.text.style.ForegroundColorSpan(Palette.labelTertiary), cut, s.length, 0)
    s.setSpan(android.text.style.RelativeSizeSpan(0.68f), cut, s.length, 0)
    return TextView(this).apply {
        text = s; textSize = 18f; setTextColor(Palette.label)
        setLineSpacing(1.5f * dp, 1f)
        setPadding(0, (20 * dp).toInt(), 0, (8 * dp).toInt())
    }
}

/** The scrub readout under a chart. Starts as a quiet hint; scrubbing fills it in. */
private fun scrubLabel(): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = getString(R.string.misc_drag_graph)
        textSize = 13f; setTextColor(Palette.labelTertiary)
        setPadding(0, (4 * dp).toInt(), 0, 0)
    }
}

/** Readout line for a scrubbed point: quiet date, then the VALUE - bold, bigger, and
 *  coloured to match the marker on the graph - then a quiet suffix ("[Example data]"). */
private fun readoutText(pre: String, strong: String, post: String, colour: Int): CharSequence {
    val s = android.text.SpannableString(pre + strong + post)
    val st = pre.length; val en = pre.length + strong.length
    if (st > 0) s.setSpan(android.text.style.ForegroundColorSpan(Palette.labelTertiary), 0, st, 0)
    s.setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, en, 0)
    s.setSpan(android.text.style.RelativeSizeSpan(1.2f), st, en, 0)
    s.setSpan(android.text.style.ForegroundColorSpan(colour), st, en, 0)
    if (post.isNotEmpty()) s.setSpan(android.text.style.ForegroundColorSpan(Palette.labelTertiary), en, s.length, 0)
    return s
}

/**
 * The shared chart card used by every stat graph (home week, home year, Productivity's
 * reclaimed week): grey card, teal line (optionally continuing grey-dotted as a
 * projection, optionally with a goal line/slope - both named in the legend), a scrub
 * READOUT updated in the page as the finger moves, an optional "worth…" line, and a
 * statRow. One look, different numbers.
 */
private fun chartStatCard(
    values: FloatArray, labels: List<String>,
    stats: List<Pair<String, String>>,
    accent: Int = Palette.tint,
    dotted: FloatArray = FloatArray(0),
    goal: Float? = null, goalPerSlot: Float? = null,
    gridStep: Float? = null, minorStep: Float? = null,
    legendMain: String? = null,
    worth: String? = null,
    // Bars side by side instead of a line - for the per-day charts. See StatLineChartView.
    bars: Boolean = false,
    // The numbers are made up until there is enough real data. Says so ON the graph, in grey,
    // and nowhere else: the amber caption under the card and the "[Example data]" tag on every
    // readout said the same thing three times over.
    example: Boolean = false,
    onStatsClick: (() -> Unit)? = null,
    pointInfo: ((Int, Float, Boolean) -> CharSequence)? = null,
): View {
    val dp = resources.displayMetrics.density
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glassBg(); elevation = 1f * dp
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val chart = StatLineChartView(this, values, labels,
        goal = goal, dotted = dotted, dottedColour = Palette.labelTertiary,
        goalPerSlot = goalPerSlot, accent = accent, gridStep = gridStep, minorStep = minorStep,
        bars = bars, watermark = if (example) getString(R.string.chart_example) else null)
    card.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (150 * dp).toInt()))

    // Legend: name every line on the chart.
    card.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, (4 * dp).toInt(), 0, 0)
        fun key(t: String, colour: Int) = addView(TextView(this@MainActivity).apply {
            text = t; textSize = 11f; setTextColor(colour); setPadding(0, 0, (14 * dp).toInt(), 0)
        })
        key(legendMain ?: getString(R.string.chart_so_far), accent)
        if (dotted.isNotEmpty()) key(getString(R.string.chart_projected), Palette.labelTertiary)
        if (goal != null || goalPerSlot != null) key(getString(R.string.chart_goal_legend), Palette.successText)
    })

    if (pointInfo != null) {
        val info = scrubLabel()
        card.addView(info)
        chart.onScrub = { i ->
            val projected = i >= values.size
            val v = if (projected) dotted.getOrNull(i - values.size) else values.getOrNull(i)
            if (v != null) info.text = pointInfo(i, v, projected)
        }
    }
    if (worth != null) card.addView(TextView(this).apply {
        text = worth; textSize = 14f; setTextColor(Palette.labelSecondary); setPadding(0, (8 * dp).toInt(), 0, 0)
    })
    if (stats.isNotEmpty()) card.addView(statRow(stats, accent, onStatsClick))
    return card
}

// ── Usage goal: pick a daily phone-time target; the home graphs draw it. ──────
// ═══════════════════════════════════════════════════════════════════════════════════════
//  THE PRODUCTIVITY PAGE IS GONE, AND SO IS "WHAT THE SCROLL COSTS". DO NOT REBUILD THEM.
//
//  Productivity was a page of doors: a dopamine card that Overview already shows, a
//  reclaimed-hours chart, a door to the cost projector, and three input rows. Two levels
//  of navigation to reach one number. What survived, and where it went:
//    • the cost, as a share of your waking day  → the donut, straight onto Overview
//    • the daily usage goal                     → this page, opened from Overview
//    • the dopamine baseline                    → already on Overview, always was
//    • your income (About you)                  → the usage chart's own stats row
//  Everything else was saying the same thing a third time. If a number needs to be seen,
//  it goes ON Overview; it does not get a page of its own with a door in front of it.
// ═══════════════════════════════════════════════════════════════════════════════════════
private fun showUsageGoal() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.usage_goal_title)))
    root.addView(TextView(this).apply {
        text = getString(R.string.usage_goal_intro)
        textSize = 14f; setTextColor(Palette.labelSecondary); setPadding(0, 0, 0, (14 * dp).toInt())
    })
    val current = UsageGoal.minutesPerDay(this)
    val label = TextView(this).apply {
        textSize = 30f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(Palette.label)
        setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
    }
    // 15-minute steps from 15 min to 8 h.
    val seek = android.widget.SeekBar(this).apply {
        max = 31
        progress = (((if (current > 0) current else 120) / 15) - 1).coerceIn(0, 31)
    }
    fun minutesOf(p: Int) = (p + 1) * 15
    fun refreshLabel() { label.text = getString(R.string.usage_goal_perday, fmtHours(minutesOf(seek.progress) / 60f)) }
    seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) { refreshLabel() }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
    })
    root.addView(label); root.addView(seek)
    refreshLabel()
    root.addView(bigChoice(getString(R.string.usage_goal_set), Palette.successText) {
        UsageGoal.setMinutesPerDay(this, minutesOf(seek.progress))
        Toast.makeText(this, getString(R.string.usage_goal_set_toast, fmtHours(minutesOf(seek.progress) / 60f)), Toast.LENGTH_SHORT).show()
        setupHomeScreen()
    })
    if (current > 0) root.addView(Button(this).apply {
        text = getString(R.string.usage_goal_remove); setAllCaps(false)
        setOnClickListener { UsageGoal.clear(this@MainActivity); setupHomeScreen() }
    })
    setContentWithThumb(root) { setupHomeScreen() }
}

private fun showTemptationsTab() {
    if (inBackNav && restoreTab(1)) return          // same as Overview - see setupHomeScreen
    onTemptationsTab = true; onHomeScreen = false; onReportScreen = false; inSubPage = false; subBack = null
    claimTab(1)
    markTabSeen("temptations")
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    // ONE SCROLLING COLUMN, exactly like Overview. It used to be a fixed header with the
    // list in a weighted ScrollView underneath, which meant the list got the leftovers of
    // the screen: under the floating pill that showed up as a dead band of page above the
    // bar, with the last card stranded well short of the bottom. A scrolling page has no
    // leftovers - the content runs to the bottom edge and passes under the pill.
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }
    content.addView(TextView(this).apply {
        text = getString(R.string.temp_managing); textSize = 15f; setTextColor(Palette.labelTertiary)
        setPadding(0, 0, 0, (10 * dp).toInt())
    })
    // ORDER, and it is not alphabetical or structural: the two that catch nearly everyone
    // go first (endless scrolling, then phone checking), adult content third, and the rest
    // keep the catalogue's order. The list is read top-down by someone who has already
    // decided they have a problem - the odds of the first card being the right card are
    // what matter, not tidiness.
    //
    // Adult Content keeps its own big bespoke flow. Everything else shares the one simple
    // page below, driven off AppConfig.TEMPTATIONS - add a category there, not here.
    fun temptCard(spec: AppConfig.TemptationSpec) =
        homeCard(temptTitle(spec), temptSubtitle(spec), temptIcon(spec.id)) { showTemptation(spec) }
    val first = listOf("scrolling", "checking")
    first.mapNotNull { AppConfig.temptation(it) }.forEach { content.addView(temptCard(it)) }
    content.addView(homeCard(getString(R.string.temp_adult_title), getString(R.string.temp_adult_sub),
        R.drawable.ic_cat_adult) {
        reportBackTarget = { showTemptationsTab() }; showReportScreen(offerLock = true)
    })
    AppConfig.TEMPTATIONS.filterNot { it.id in first }.forEach { content.addView(temptCard(it)) }
    val root = ScrollView(this).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isFillViewport = true
        addView(content)
    }
    setContentNoThumb(withBottomBar(root, 1))
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

private var habitSweep: SweepAnimator? = null

private fun showTemptation(spec: AppConfig.TemptationSpec) {
    habitSweep?.stop(); habitSweep = null
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(temptTitle(spec)))
    root.addView(TextView(this).apply {
        text = temptSubtitle(spec); textSize = 15f; setTextColor(Palette.labelTertiary)
        setPadding(0, 0, 0, (12 * dp).toInt())
    })

    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    // "Is this me?" - the bit that makes someone stop and recognise themselves.
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glassBg(); elevation = 1f * dp
        val p = (16 * dp).toInt(); setPadding(p, (14 * dp).toInt(), p, (14 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (14 * dp).toInt() }
    }
    // RESTORED-FROM-HEAD 2026-08-04 — the "Does this sound like you?" heading is gone here
    // because its string (temp_sound_like) no longer exists. See the note at habitRide.
    temptCovers(spec).forEach { line ->
        card.addView(TextView(this).apply {
            text = "\u2022  $line"; textSize = 14f; setTextColor(Palette.labelSecondary)
            setLineSpacing(0f, 1.15f); setPadding(0, (8 * dp).toInt(), 0, 0)
        })
    }
    list.addView(card)

    val rides = HabitLog.count(this, spec.id, HabitLog.RIDE)
    val slips = HabitLog.recent(this, spec.id, HabitLog.SLIP, 7)
    list.addView(TextView(this).apply {
        text = getString(R.string.temp_stats, rides, slips)
        textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.successText)
        setPadding(0, 0, 0, (12 * dp).toInt())
    })

    list.addView(captionedButton(getString(R.string.temp_pull_title), getString(R.string.temp_pull_sub), Palette.tint) {
        habitRide(spec)
    })

    if (TemptationBlocks.hasBlocks(spec)) list.addView(blockSwitch(spec))

    // RESTORED-FROM-HEAD 2026-08-04 — the "I slipped" button and the "try this instead" line
    // are gone here because their strings (temp_slipped_*, temp_try_instead) and the
    // temptInsteadOf() helper no longer exist. See the note at habitRide.
    list.addView(TextView(this).apply {
        text = getString(R.string.temp_lockdown)
        textSize = 14f; setTextColor(Palette.labelSecondary)
        isClickable = true; isFocusable = true
        setPadding(0, (8 * dp).toInt(), 0, (16 * dp).toInt())
        setOnClickListener {
            Lockdown.start(this@MainActivity)
            Toast.makeText(this@MainActivity,
                getString(R.string.temp_lockdown_toast), Toast.LENGTH_LONG).show()
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
        textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.onFill)
    }
    val sub = TextView(this).apply {
        textSize = 13f; setTextColor(Palette.divider); setPadding(0, (3 * dp).toInt(), 0, 0)
    }
    // Name the thing where the category has words for it ("Block reels, shorts & endless
    // feeds"), so a switch that used to have its own page keeps its own label.
    val named = temptBlocksLabel(spec)
    fun paint() {
        title.text = when {
            named != null && on -> getString(R.string.temp_block_named_on, named)
            named != null -> getString(R.string.temp_block_named_off, named)
            on -> getString(R.string.temp_block_on)
            else -> getString(R.string.temp_block_off)
        }
        val sites = spec.blockPatterns.size
        val apps = spec.greyApps.size
        val appBit = if (apps > 0) getString(R.string.temp_block_applimit, apps, GreyUsage.LIMIT_MIN) else ""
        sub.text = if (on) getString(R.string.temp_block_sub_on, sites, appBit)
                   else getString(R.string.temp_block_sub_off, sites, appBit)
        row.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Radius.card * dp
            setColor(if (on) Palette.success else Palette.labelQuaternary)
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

// ⚠️⚠️⚠️ RESTORED FROM THE LAST COMMIT — 2026-08-04. READ BEFORE TRUSTING THIS REGION. ⚠️⚠️⚠️
//
//  On 2026-08-04 a bad edit truncated Main.kt and destroyed roughly 4,600 lines of the
//  working copy - everything between the temptation pages and showDevConsole. The file was
//  rebuilt as: the surviving top of the working copy + THE LAST COMMIT'S version of this
//  middle + the surviving bottom of the working copy.
//
//  What that means for this region specifically:
//    • It is the COMMITTED version (11cbd85), not whatever was in the working copy.
//    • Roughly 205 lines of UNCOMMITTED work that lived in here were lost and could not be
//      recovered - no stash, no editor history, no dangling git object held them.
//    • The three sites marked RESTORED-FROM-HEAD nearby are where the committed code no
//      longer compiled against the current strings.xml, because the removed strings prove
//      those widgets had been deleted on purpose. Removing them was the minimal reading of
//      that intent - it is an INFERENCE, not the original code.
//
//  Anything else in this middle region that was changed but still compiled has silently
//  reverted to the committed version. If something here looks older than you remember, that
//  is why. Delete this banner once the region has been reviewed.
// ═══════════════════════════════════════════════════════════════════════════════════════

/** Ride the urge out: a few slow sweeps, then the button that says you beat it. */
private fun habitRide(spec: AppConfig.TemptationSpec) {
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val totalRounds = 3
    val root = vbox(pad).apply { gravity = Gravity.CENTER_HORIZONTAL }
    root.addView(titleText(getString(R.string.temp_ride_title)))
    root.addView(TextView(this).apply {
        text = getString(R.string.temp_ride_body, totalRounds)
        textSize = 15f; gravity = Gravity.CENTER; setTextColor(Palette.labelSecondary)
    })

    val sweep = SweepPanelView(this, Palette.sweep)   // it sits in a box here, and fills it
    root.addView(FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(sweep, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    })

    val counter = TextView(this).apply {
        textSize = 14f; gravity = Gravity.CENTER; setTextColor(Palette.successText)
        setPadding(0, (6 * dp).toInt(), 0, (10 * dp).toInt())
    }
    root.addView(counter)

    // Only unlocks once the sweeps are actually done - otherwise it's just a tap-through.
    val done = bigChoice(getString(R.string.temp_ride_done_btn), Palette.successText) { habitRideDone(spec) }
    done.isEnabled = false
    done.alpha = 0.5f
    root.addView(done)

    habitSweep?.stop()
    habitSweep = SweepAnimator(sweep).also { a ->
        a.start(
            cycles = totalRounds,
            onCycle = { d, t -> counter.text = if (d >= t) getString(R.string.temp_ride_paced) else getString(R.string.temp_ride_counter, d, t) },
            onComplete = { done.isEnabled = true; done.alpha = 1f },
        )
    }
    setContentWithThumb(root) { habitSweep?.stop(); habitSweep = null; showTemptation(spec) }
}

private fun habitRideDone(spec: AppConfig.TemptationSpec) {
    habitSweep?.stop(); habitSweep = null
    HabitLog.record(this, spec.id, HabitLog.RIDE)
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad).apply { gravity = Gravity.CENTER_HORIZONTAL }
    root.addView(titleText(getString(R.string.temp_ridedone_title)))
    root.addView(TextView(this).apply {
        text = getString(R.string.temp_ridedone_body)
        textSize = 16f; gravity = Gravity.CENTER; setTextColor(Palette.labelSecondary)
        setPadding(0, (4 * dp).toInt(), 0, 0)
    })
    root.addView(PeakCurveView(this), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = (12 * dp).toInt(); bottomMargin = (12 * dp).toInt()
        })
    root.addView(TextView(this).apply {
        text = getString(R.string.temp_ridden_out, HabitLog.count(this@MainActivity, spec.id, HabitLog.RIDE))
        textSize = 15f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(0, 0, 0, (12 * dp).toInt())
    })
    root.addView(captionedButton(getString(R.string.temp_put_down), getString(R.string.temp_put_down_sub), Palette.successText) {
        try { finishAffinity() } catch (_: Throwable) { setupMainScreen() }
    })
    setContentWithThumb(root) { showTemptation(spec) }
}

// RESTORED-FROM-HEAD 2026-08-04 — habitSlip() was here. It is gone because every string it
// showed (temp_slip_title / _body / _next) and the temptInsteadOf() helper it called have all
// been removed from the project, and nothing else called it. See the note at habitRide.

/** A clean tappable card for the home/tab screens (chevron shown when clickable). */
/**
 * The leading mark on a card or an option row: the glyph itself, and nothing else.
 *
 * NO TILE AND NO COLOUR. It briefly had both - a soft tinted square with a teal glyph -
 * and a column of them turned every list into a row of green badges competing with the
 * titles beside them. A line drawing in the same grey as the subtitle sits UNDER the text
 * in the hierarchy, which is where an icon belongs: it helps you find the row you already
 * know, and it never asks to be looked at first.
 *
 * The fixed [size] box is what keeps the text edges aligned down the list; only the box is
 * fixed, the ink inside it is free.
 */
private fun iconBadge(
    icon: Int,
    glyph: Int = Palette.labelSecondary,
    size: Int = 26,
): View = ImageView(this).apply {
    setImageResource(icon)
    setColorFilter(glyph)
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(Space.md) }
}

/** A large, centred, untiled glyph: the one image on a screen that has only one thing to say. */
private fun heroIcon(icon: Int, size: Int = 76, colour: Int = Palette.label): View =
    ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(colour)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

private fun homeCard(title: String, sub: String?, icon: Int? = null, onClick: (() -> Unit)? = null): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = if (onClick != null) tappableBg(Palette.glass) else glassBg()
        elevation = dpf(1f)
        setPadding(dp(Space.md), dp(Space.md), dp(Space.md), dp(Space.md))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(Space.sm) }
        if (onClick != null) {
            isClickable = true; isFocusable = true; setOnClickListener { onClick() }
            pressable()
        }
    }
    // Vertically centred against the whole card, one- or two-line subtitle regardless -
    // the row's CENTER_VERTICAL gravity does it, so nothing here has to know the height.
    if (icon != null) row.addView(iconBadge(icon))
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    texts.addView(TextView(this).apply {
        text = title; textSize = Type.headline
        setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
    })
    if (sub != null) texts.addView(TextView(this).apply {
        text = sub; textSize = Type.footnote; setTextColor(Palette.labelTertiary)
        setLineSpacing(0f, Type.lineSpacing)
        setPadding(0, dp(Space.xxs) / 2, 0, 0)
    })
    row.addView(texts)
    if (onClick != null) row.addView(TextView(this).apply {
        text = "\u203A"; textSize = 22f; setTextColor(Palette.labelQuaternary)
        setPadding(dp(Space.xs), 0, 0, 0)
    })
    return row
}

// ── Break the addiction protocol: gamified, sequential big moves ────────────
private fun showProtocol() {
    inSubPage = true; onHomeScreen = false; onTemptationsTab = false
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val appsDone = Protocol.appsDone(this)
    val holidayDone = Protocol.holidayDone(this)
    val strictActive = Mode.isLocked(this)
    val sevenStarted = Protocol.sevenStarted(this)
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.report_protocol)))
    root.addView(TextView(this).apply {
        text = getString(R.string.proto_intro)
        textSize = 15f; setTextColor(Palette.labelSecondary); setPadding(0, 0, 0, (10 * dp).toInt())
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    list.addView(TextView(this).apply {
        text = getString(R.string.proto_hdr_walls); textSize = 12f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Palette.labelTertiary); setPadding((2 * dp).toInt(), 0, 0, (8 * dp).toInt())
    })
    // Look like the rest: a tickbox + tap to open the guide.
    list.addView(protocolLinkCheckRow(getString(R.string.proto_apps_title),
        getString(R.string.proto_apps_sub), appsDone) { showProtocolApps() })
    val anyReplace = Protocol.isChecked(this, "buy_alarm") ||
                     Protocol.isChecked(this, "charge_outside") || Protocol.isChecked(this, "buy_watch")
    list.addView(protocolLinkCheckRow(getString(R.string.proto_bedroom_title),
        getString(R.string.proto_bedroom_sub), anyReplace) { showProtocolReplace() })
    val checks = listOf(
        "out_of_house" to (getString(R.string.proto_outhouse_title) to getString(R.string.proto_outhouse_sub)),
        "delete_social" to (getString(R.string.proto_delete_title) to getString(R.string.proto_delete_sub)),
        "new_background" to (getString(R.string.proto_bg_title) to getString(R.string.proto_bg_sub)),
        "new_theme" to (getString(R.string.proto_theme_title) to getString(R.string.proto_theme_sub)),
    )
    checks.forEach { (key, pair) ->
        val (t, sub) = pair
        list.addView(protocolCheckRow(key, t, sub))
    }

    // The two big moves: same tickbox card, but a gold outline (brighter gold once done).
    list.addView(protocolGoldRow(getString(R.string.proto_holiday_title),
        getString(R.string.proto_holiday_sub),
        holidayDone) { showProtocolHoliday() })
    val sevenSub = when {
        strictActive -> getString(R.string.proto_seven_active, Mode.timeLeft(this))
        sevenStarted -> getString(R.string.proto_seven_completed)
        !holidayDone -> getString(R.string.proto_seven_needholiday)
        else -> getString(R.string.proto_seven_ready)
    }
    list.addView(protocolGoldRow(getString(R.string.proto_seven_title),
        sevenSub, sevenStarted && !strictActive) {
        if (holidayDone) showProtocol7Day()
        else Toast.makeText(this, getString(R.string.proto_seven_toast_needholiday), Toast.LENGTH_SHORT).show()
    })

    list.addView(homeCard(getString(R.string.proto_tips_card_title), getString(R.string.proto_tips_card_sub)) { showProtocolTips() })

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
        background = glassBg(); elevation = 1f * dp
        val p = (16 * dp).toInt(); setPadding(p, (14 * dp).toInt(), p, (14 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * dp).toInt() }
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }
    row.addView(checkboxMarker(done, false))
    row.addView(rowTexts(title, sub))
    row.addView(TextView(this).apply { text = "\u203A"; textSize = 22f; setTextColor(Palette.labelQuaternary) })
    return row
}

/** Gold-outlined tickbox card for the two key moves; brighter gold + gold tick when done. */
private fun protocolGoldRow(title: String, sub: String, done: Boolean, onClick: () -> Unit): View {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Radius.card * dp
            setColor(if (done) Palette.warningSoft else Palette.surface)     // brighter gold when done
            setStroke((2f * dp).toInt(), Palette.warning)                   // slight gold outline
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
        includeFontPadding = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.onFill)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 7 * dp
            when {
                done && gold -> setColor(Palette.warning)
                done -> setColor(Palette.successText)
                else -> { setColor(Palette.surface); setStroke((2 * dp).toInt(), if (gold) Palette.warning else Palette.labelQuaternary) }
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
            text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
        })
        addView(TextView(this@MainActivity).apply {
            text = sub; textSize = 13f; setTextColor(Palette.labelTertiary); setPadding(0, (2 * dp).toInt(), 0, 0)
        })
    }
}

// A focused mini-page on replacing the phone's role (esp. at the bedside).
private fun showProtocolReplace() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.proto_replace_title)))
    root.addView(TextView(this).apply {
        text = getString(R.string.proto_replace_intro)
        textSize = 15f; setTextColor(Palette.labelSecondary); setPadding(0, 0, 0, (10 * dp).toInt())
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(protocolCheckRow("buy_alarm", getString(R.string.proto_alarm_title),
        getString(R.string.proto_alarm_sub)))
    list.addView(protocolCheckRow("charge_outside", getString(R.string.proto_charge_title),
        getString(R.string.proto_charge_sub)))
    list.addView(protocolCheckRow("buy_watch", getString(R.string.proto_watch_title),
        getString(R.string.proto_watch_sub)))
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
    })
    setContentWithThumb(root) { showProtocol() }
}

// Read-through guidance, grouped on its own page.
private fun showProtocolTips() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.proto_tips_title)))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(protocolGuidanceCard(getString(R.string.proto_tip1_title),
        getString(R.string.proto_tip1_sub)))
    list.addView(protocolGuidanceCard(getString(R.string.proto_tip2_title),
        getString(R.string.proto_tip2_sub)))
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
        background = glassBg(); elevation = 1f * dp
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
        marker.setTextColor(Palette.onFill)
        marker.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 7 * dp                                  // rounded checkbox, clearly tappable
            if (checked) setColor(Palette.successText)
            else { setColor(Palette.surface); setStroke((2 * dp).toInt(), Palette.labelQuaternary) }
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
        text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
    })
    texts.addView(TextView(this).apply {
        text = sub; textSize = 13f; setTextColor(Palette.labelTertiary); setPadding(0, (2 * dp).toInt(), 0, 0)
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
            cornerRadius = Radius.card * dp; setColor(Palette.warningSoft); setStroke((1 * dp).toInt(), Palette.warningSoft)
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
        text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.warningText)
    })
    texts.addView(TextView(this).apply {
        text = sub; textSize = 13f; setTextColor(Palette.warningText); setPadding(0, (3 * dp).toInt(), 0, 0)
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
        background = glassBg(); elevation = 1f * dp
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
        text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
    })
    texts.addView(TextView(this).apply {
        text = sub; textSize = 13f; setTextColor(Palette.labelTertiary); setPadding(0, (2 * dp).toInt(), 0, 0)
    })
    row.addView(texts)
    row.addView(TextView(this).apply { text = "\u203A"; textSize = 22f; setTextColor(Palette.labelQuaternary) })
    return row
}

/** A larger, highlighted "key move" step (for the two that matter most). */
private fun protocolKeyStep(title: String, sub: String, done: Boolean, locked: Boolean = false, onClick: () -> Unit): View {
    val dp = resources.displayMetrics.density
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Radius.card * dp
            setColor(if (done) Palette.successSoft else if (locked) Palette.warningSoft else Palette.warningSoft)
            setStroke((if (done) 2 else 2 * 1).times(dp).toInt(), if (done) Palette.successText else Palette.warning)
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
        setTextColor(if (done) Palette.onFill else Palette.warningText)
        if (done) background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Palette.successText)
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
        text = title; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
    })
    texts.addView(TextView(this).apply {
        text = sub; textSize = 13f; setTextColor(Palette.warningText); setPadding(0, (3 * dp).toInt(), 0, 0)
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
            cornerRadius = Radius.card * dp; setColor(if (locked) Palette.hairline else Palette.surface)
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
        setTextColor(if (done) Palette.onFill else Palette.labelSecondary)
        if (done) background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Palette.successText)
        } else if (!locked) background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Palette.hairline)
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
        text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
    })
    texts.addView(TextView(this).apply {
        text = sub; textSize = 13f; setTextColor(Palette.labelTertiary); setPadding(0, (2 * dp).toInt(), 0, 0)
    })
    row.addView(texts)
    if (badge == "active") row.addView(TextView(this).apply {
        text = "\u25CF"; textSize = 14f; setTextColor(Palette.successText)
    })
    return row
}

private fun showProtocolApps() {
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.proto_apps_title)))
    root.addView(body(getString(R.string.proto_apps_intro)))
    listOf(
        getString(R.string.proto_apps_b1),
        getString(R.string.proto_apps_b2),
        getString(R.string.proto_apps_b3),
        getString(R.string.proto_apps_b4),
    ).forEach { line ->
        root.addView(TextView(this).apply {
            text = getString(R.string.proto_bullet, line); textSize = 15f; setLineSpacing((4 * dp), 1f); setPadding(0, (6 * dp).toInt(), 0, 0)
        })
    }
    root.addView(grow())
    root.addView(bigChoice(if (Protocol.appsDone(this)) getString(R.string.proto_done) else getString(R.string.proto_apps_btn), Palette.successText) {
        Protocol.setApps(this, true); showProtocol()
    })
    setContentWithThumb(root) { showProtocol() }
}

private fun showProtocolHoliday() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.proto_holiday_page_title)))
    root.addView(body(getString(R.string.proto_holiday_intro)))
    listOf(
        getString(R.string.proto_holiday_b1),
        getString(R.string.proto_holiday_b2),
        getString(R.string.proto_holiday_b3),
        getString(R.string.proto_holiday_b4),
    ).forEach { line ->
        root.addView(TextView(this).apply {
            text = getString(R.string.proto_bullet, line); textSize = 15f; setLineSpacing((4 * dp), 1f); setPadding(0, (6 * dp).toInt(), 0, 0)
        })
    }
    root.addView(grow())
    root.addView(bigChoice(if (Protocol.holidayDone(this)) getString(R.string.proto_done) else getString(R.string.proto_holiday_btn), Palette.successText) {
        Protocol.setHoliday(this, true); showProtocol()
    })
    setContentWithThumb(root) { showProtocol() }
}

private fun showProtocol7Day() {
    inSubPage = true
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.proto_7day_title)))
    root.addView(body(getString(R.string.proto_7day_intro)))
    if (Mode.isLocked(this)) {
        root.addView(TextView(this).apply {
            text = getString(R.string.proto_seven_active, Mode.timeLeft(this@MainActivity))
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.successText)
            setPadding(0, (12 * dp).toInt(), 0, 0)
        })
        root.addView(grow())
    } else {
        root.addView(grow())
        root.addView(bigChoice(getString(R.string.proto_7day_btn), Palette.successText) {
            Protocol.setSevenStarted(this)
            Mode.startWeekStrict(this)
            Toast.makeText(this, getString(R.string.proto_7day_toast), Toast.LENGTH_SHORT).show()
            showProtocol()
        })
    }
    setContentWithThumb(root) { showProtocol() }
}

private fun showReportScreen(offerLock: Boolean = false) {
    // The centred "this app isn't protected yet" popup shows every time the page is
    // ENTERED from the Temptations menu (offerLock = true) while the uninstall lock is
    // off - but not on thumb-back returns from sub-pages, or it nags on every hop.
    //
    // It also stays quiet until the user is actually SET UP: on a fresh install (mode Off,
    // no permissions) the uninstall lock is the third thing they need, not the first, and
    // leading with it just adds a scary popup to a page that isn't monitoring anything yet.
    if (offerLock && !Mode.isOff(this) && corePermsGranted() &&
        !(UninstallGuard.isEnabled(this) && UninstallGuard.isAdminActive(this))) {
        showUnprotectedPopup()
    }
    onReportScreen = true
    subBack = null
    onHomeScreen = false
    onTemptationsTab = false
    inSubPage = false
    inTemptationFlow = false
    inLoosenFlow = false
    inAppSiteFlow = false
    stopLoosenTimer()

    // ── LAYOUT ──────────────────────────────────────────────────────────────────────
    // This page used to be full-bleed dark slabs stacked to fill the screen, each a
    // different shade of slate, with the mode dropdown floating above them. It read as a
    // control panel: heavy, unlabelled, and tonally nothing like the rest of the app.
    //
    // It is now an ordinary scrolling page, like every other page: title, a card for the
    // protection level, the protocol as the one accented card (it IS the main action),
    // then the two things you might have come here to do, then statistics, then a quiet
    // link to settings. Same content, same order of importance - stated in words rather
    // than colour-coded in slabs.
    val pad = dp(Space.page)
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, dp(Space.huge))
    }
    val root = ScrollView(this).apply {
        isFillViewport = true
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(content)
    }
    content.addView(titleText(getString(R.string.report_title)))

    // ── Protection level: the mode picker, labelled, with the rules an (i) away ──────
    // The rules live behind the (i) right next to the mode, so "what does Strict actually
    // do?" is answered where the question gets asked - not on a separate link elsewhere.
    content.addView(glassCard(Space.md).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.report_mode_label)
            textSize = Type.callout; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.labelSecondary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(modeSpinner())
        addView(TextView(this@MainActivity).apply {
            text = "\u24D8"
            textSize = 20f; setTextColor(Palette.tint)
            isClickable = true; isFocusable = true
            setPadding(dp(Space.xs), dp(Space.xs), 0, dp(Space.xs))
            setOnClickListener { showModeRules() }
        })
    })
    // (The "Off is no longer on the list" paragraph used to sit here, under the picker. It
    // was a wall of small print on the page people open when they are already struggling; the
    // ratchet is still explained in the mode rules behind the (i) above.)
    //
    // NOR does a "you are locked in" line under the picker, which briefly lived here. The
    // grey, inert picker showing one option says it already; a paragraph explaining what
    // the user chose on purpose is just something else to read on a bad day.

    // The protocol is the one thing on this page that gets the accent, because it is the
    // one thing here that fixes the problem rather than reacting to it.
    content.addView(reportPane(
        getString(R.string.report_protocol),
        getString(R.string.report_protocol_sub),
        accent = true,
    ) { showProtocol() })

    content.addView(sectionHeader(getString(R.string.nav_temptations)))
    // "I'm going to look anyway" is DELIBERATELY NOT HERE. A permanent button turns the
    // wall into a door with a handle, and every urge eventually tries the handle. It
    // appears ONLY when the user has already started trying to tear the guard down
    // (uninstall, device admin, switching monitoring off, escaping a locked strict mode) -
    // see the big comment on BypassWatch, and showBypassOffer. Do not put it back.
    content.addView(reportPane(
        getString(R.string.report_pane_temptation),
        getString(R.string.report_pane_temptation_sub),
    ) { onFeelTemptation() })
    content.addView(reportPane(
        getString(R.string.report_pane_appsite),
        getString(R.string.report_pane_appsite_sub),
    ) { onReportAppSite() })

    // THE HONEST EXIT deliberately does NOT live on this page. It appears as its own full
    // screen (showBypassOffer) at the moment of a bypass attempt - see
    // maybeShowBypassOffer(). A card sitting here read as a permanent door handle.

    // STATISTICS IS NOT HERE EITHER, and that is deliberate. "When it happens, where, and
    // what leads into it" is a chart of your own relapses: interesting to us while tuning
    // the app, and a place to go and re-read the worst week of your year for the person
    // actually using it. It is a developer tool now - see setupMainScreen(). Don't put it
    // back on the page people open when they are already struggling.

    // Quiet on purpose: almost nobody needs these, and the ones who do will look for them.
    content.addView(TextView(this).apply {
        text = getString(R.string.report_settings)
        textSize = Type.footnote; gravity = Gravity.CENTER; setTextColor(Palette.labelTertiary)
        isClickable = true; isFocusable = true
        setPadding(0, dp(Space.lg), 0, dp(Space.sm))
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
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val reason = BypassWatch.lastReason(this)
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.bypass_title)))
    root.addView(TextView(this).apply {
        text = (if (reason != null) getString(R.string.bypass_moment, reason) else "") +
            getString(R.string.bypass_body, LoosenLimit.LIFETIME_MAX)
        textSize = 15f; setTextColor(Palette.labelSecondary); setLineSpacing(0f, 1.25f)
        setPadding(0, (4 * dp).toInt(), 0, (16 * dp).toInt())
    })
    root.addView(grow())
    root.addView(bigChoice(getString(R.string.bypass_look_anyway), Palette.warningText) { onLookAnyway() })
    root.addView(Button(this).apply {
        text = getString(R.string.common_not_now); setAllCaps(false)
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
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val canEdit = AttractionFilter.canEdit(this)
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.report_settings)))
    val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(c)
    })
    setContentWithThumb(root) { showReportScreen() }

    c.addView(TextView(this).apply {
        text = getString(R.string.adult_section_looks)
        textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.labelTertiary)
        setPadding(0, 0, 0, (8 * dp).toInt())
    })
    c.addView(TextView(this).apply {
        text = getString(R.string.adult_intro)
        textSize = 14f; setTextColor(Palette.labelSecondary); setLineSpacing(0f, 1.2f)
        setPadding(0, 0, 0, (14 * dp).toInt())
    })

    if (!canEdit) {
        c.addView(TextView(this).apply {
            text = getString(R.string.adult_locked, modeDisplayName(Mode.current(this@MainActivity)))
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.warningText)
            setLineSpacing(0f, 1.15f)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Radius.control * dp; setColor(Palette.warningSoft)
            }
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
        })
    }

    fun switchRow(label: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = glassBg(); elevation = 1f * dp
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
                setTextColor(Palette.label)
            })
            addView(TextView(this@MainActivity).apply {
                text = sub; textSize = 12f; setTextColor(Palette.labelTertiary)
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

    switchRow(getString(R.string.adult_block_women),
        getString(R.string.adult_block_women_sub),
        { AttractionFilter.blockFemale(this) }, { AttractionFilter.setBlockFemale(this, it) })
    switchRow(getString(R.string.adult_block_men),
        getString(R.string.adult_block_men_sub),
        { AttractionFilter.blockMale(this) }, { AttractionFilter.setBlockMale(this, it) })

    c.addView(TextView(this).apply {
        text = getString(R.string.adult_medical_note)
        textSize = 12f; setTextColor(Palette.labelTertiary); setLineSpacing(0f, 1.15f)
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
 * │  Whenever you touch anything that branches on Mode (greyscale, block              │
 * │  thresholds, sensors, lock behaviour), re-read those lists and fix them.          │
 * └──────────────────────────────────────────────────────────────────────────────────┘
 */
private fun showModeRules() {
    inSubPage = true; onReportScreen = false
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val current = Mode.current(this)
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.moderules_title)))
    root.addView(TextView(this).apply {
        text = getString(R.string.moderules_subtitle)
        textSize = 14f; setTextColor(Palette.labelTertiary); setPadding(0, 0, 0, (12 * dp).toInt())
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
                cornerRadius = Radius.card * dp
                setColor(Palette.surface)
                setStroke(((if (highlight) 2.5f else 1.5f) * dp).toInt(),
                    if (highlight) accent else Palette.hairline)
            }
            val p = (16 * dp).toInt(); setPadding(p, (14 * dp).toInt(), p, (14 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }
        }
        card.addView(TextView(this).apply {
            text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.label)
        })
        if (sub != null) card.addView(TextView(this).apply {
            text = sub; textSize = 13f; setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        rules.forEach { rule ->
            card.addView(TextView(this).apply {
                text = "•  $rule"
                textSize = 14f; setTextColor(Palette.labelSecondary)
                setLineSpacing(0f, 1.15f)
                setPadding(0, (9 * dp).toInt(), 0, 0)
            })
        }
        list.addView(card)
    }

    sectionHeader(getString(R.string.moderules_section_always), Palette.labelTertiary)
    rulesCard(getString(R.string.moderules_always_title), getString(R.string.moderules_always_sub),
        alwaysOnRules(), Palette.successText, highlight = false)

    sectionHeader(getString(R.string.moderules_section_modes), Palette.labelTertiary)
    AppConfig.MODES.forEach { spec ->
        val isCurrent = spec.id == current
        rulesCard(
            title = modeDisplayName(spec.id),
            sub = if (isCurrent) getString(R.string.moderules_current) else null,
            rules = modeRules(spec.id),
            accent = Palette.tint,
            highlight = isCurrent,
        )
    }

    if (Mode.isLocked(this)) {
        list.addView(TextView(this).apply {
            text = getString(R.string.moderules_lock, Mode.timeLeft(this@MainActivity))
            textSize = 13f; setTextColor(Palette.warningText); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (6 * dp).toInt(), 0, (10 * dp).toInt())
        })
    }

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
    val pad = (Space.page * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(titleText(getString(R.string.log_title)).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    header.addView(Button(this).apply {
        text = getString(R.string.log_clear)
        setOnClickListener { clearLog(); Toast.makeText(this@MainActivity, getString(R.string.log_cleared), Toast.LENGTH_SHORT).show() }
    })
    root.addView(header)

    val empty = TextView(this).apply {
        text = getString(R.string.log_empty); textSize = Type.callout
        setTextColor(Palette.labelTertiary)
        setPadding(0, (Space.xl * dp).toInt(), 0, 0); visibility = View.GONE
    }
    root.addView(empty)
    // The rows live on one card rather than floating on the page, so a long log reads as a
    // single list instead of a stack of loose lines.
    val rv = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@MainActivity)
        adapter = this@MainActivity.adapter
        background = glassBg()
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        val h = (Space.sm * dp).toInt()
        setPadding(h, (Space.xs * dp).toInt(), h, (Space.xs * dp).toInt())
        clipToPadding = false
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
    val pad = (Space.page * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(titleText(getString(R.string.home_about_privacy)))
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

/**
 * One action on the adult-content page: title, a line of explanation, chevron.
 *
 * [accent] marks the single most important action on the page - it gets the tint fill and
 * white text. Exactly one pane per page should set it; if two do, neither is the primary.
 * Everything else is a glass card, so the page has one focal point instead of four
 * competing slabs of colour.
 */
private fun reportPane(title: String, sub: String, accent: Boolean = false, onClick: () -> Unit): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background =
            if (accent) tappableBg(Palette.tint, Radius.card, stroke = null, ripple = 0x33FFFFFF)
            else tappableBg(Palette.glass, Radius.card)
        elevation = dpf(if (accent) 2f else 1f)
        setPadding(dp(Space.md), dp(Space.md), dp(Space.md), dp(Space.md))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(Space.sm) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        pressable()
    }
    row.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = Type.headline; setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (accent) Palette.onFill else Palette.label)
        })
        addView(TextView(this@MainActivity).apply {
            text = sub
            textSize = Type.footnote
            setTextColor(if (accent) 0xCCFFFFFF.toInt() else Palette.labelTertiary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, dp(Space.xxs), 0, 0)
        })
    })
    row.addView(TextView(this).apply {
        text = "›"; textSize = 22f
        setTextColor(if (accent) 0xCCFFFFFF.toInt() else Palette.labelQuaternary)
        setPadding(dp(Space.xs), 0, 0, 0)
    })
    return row
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
private var waveSweep: SweepAnimator? = null

// ========================
// ── "I'm going to look anyway" (supervised loosen) flow ─────────────────────

private var inLoosenFlow = false

private var loosenHandler: Handler? = null
private var loosenRunnable: Runnable? = null
private var loosenSweep: SweepAnimator? = null

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
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.loosen_stop_good)))
    root.addView(body(message))
    root.addView(grow())
    root.addView(Button(this).apply { text = getString(R.string.common_done); setOnClickListener { showReportScreen() } })
    setContentView(root)
}

private fun loosenBlockedScreen() {
    val today = LoosenLimit.usedToday(this)
    val msg = if (today)
        getString(R.string.loosen_blocked_today)
    else
        getString(R.string.loosen_blocked_lifetime, LoosenLimit.LIFETIME_MAX)
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(bigPanic())
    root.addView(titleText(getString(R.string.loosen_blocked_title)))
    root.addView(body(msg))
    root.addView(grow())
    setContentWithThumb(root) { showReportScreen() }
}

// ── intro, one idea per screen, panic taking the top third ──────────────────
private fun loosenIntro1() {
    loosenBackAction = { stopLoosenTimer(); inLoosenFlow = false; showReportScreen() }
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(boldWordTitle(getString(R.string.loosen_intro_title), getString(R.string.loosen_intro_boldword)))
    root.addView(TextView(this).apply {
        text = getString(R.string.loosen_unlocks_avail, LoosenLimit.remaining(this@MainActivity), LoosenLimit.LIFETIME_MAX)
        textSize = 15f; setTextColor(Palette.labelSecondary); setPadding(0, (8 * dp).toInt(), 0, 0)
    })
    root.addView(TextView(this).apply {
        text = getString(R.string.loosen_intro_urge)
        textSize = 15f; setTextColor(Palette.labelSecondary); setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
    })
    root.addView(PeakCurveView(this, showMarker = false, labelTop = getString(R.string.loosen_curve_top), labelBot = getString(R.string.loosen_curve_bot)),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(captionedButton(getString(R.string.loosen_stop_instead), getString(R.string.loosen_stop_strong), Palette.successText) { openPanic() })
    root.addView(captionedButton(getString(R.string.loosen_understand), getString(R.string.loosen_understand_sub), Palette.tint) { loosenFaceActScreen() })
    setContentView(root)
}

private val NEG_FEELINGS = listOf("Regret", "Numb", "Empty", "Ashamed")
private val POS_FEELINGS = listOf("Proud", "Relieved", "Clear", "In control")

// ── Screen A: how will you feel after you unlock? (drag into the venn) ───────
private fun loosenFaceActScreen() {
    loosenBackAction = { loosenIntro1() }
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.loosen_feel_after_title)))
    root.addView(TextView(this).apply {
        text = getString(R.string.loosen_drag_after)
        textSize = 14f; setTextColor(Palette.labelSecondary); setPadding(0, 0, 0, (4 * dp).toInt())
    })
    val face = FeelingFaceView(this, NEG_FEELINGS, resources.getStringArray(R.array.feel_neg).toList(), Palette.danger, positiveInside = false,
        startZoneLabel = getString(R.string.loosen_startzone))
    root.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    val cont = continueLink(getString(R.string.common_continue)) { loosenRegret = face.nearestLabel() ?: loosenRegret; loosenFaceRideScreen() }
    face.onMoodChange = { enableLink(cont) }
    root.addView(panicBar())
    root.addView(cont)
    setContentView(root)
}

// ── Screen B: how will you feel if you wait it out? (all happy / neutral) ────
private fun loosenFaceRideScreen() {
    loosenBackAction = { stopLoosenTimer(); loosenFaceActScreen() }
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.loosen_wait_title)))
    root.addView(TextView(this).apply {
        text = getString(R.string.loosen_drag_30)
        textSize = 14f; setTextColor(Palette.labelSecondary); setPadding(0, 0, 0, (2 * dp).toInt())
    })
    val timer = TextView(this).apply {
        textSize = 28f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setTextColor(Palette.successText)
    }
    root.addView(timer)
    val face = FeelingFaceView(this, POS_FEELINGS, resources.getStringArray(R.array.feel_pos).toList(), Palette.successText, positiveInside = true)
    root.addView(face, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    val cont = continueLink(getString(R.string.common_continue)) { stopLoosenTimer(); loosenDelayChanceScreen() }
    face.onMoodChange = { enableLink(cont) }
    root.addView(panicBar())
    root.addView(cont)
    setContentView(root)
    runLoosenCountdown(timer, System.currentTimeMillis() + 30L * 60 * 1000) { timer.text = "0:00" }
}

// ── Screen C: how likely can you DELAY 30 mins? (slider, mirrors urge bands) ─
private fun loosenDelayChanceScreen() {
    loosenBackAction = { loosenFaceRideScreen() }
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.loosen_challenge_title)))
    root.addView(body(getString(R.string.loosen_challenge_body)))
    root.addView(TextView(this).apply {
        text = getString(R.string.loosen_howlikely)
        textSize = 15f; setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
    })
    val label = TextView(this).apply {
        textSize = 19f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setTextColor(Palette.successText); setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
    }
    root.addView(label)
    val seek = android.widget.SeekBar(this).apply { max = 100; progress = 50 }
    root.addView(seek)
    val ends = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    ends.addView(TextView(this).apply {
        text = getString(R.string.loosen_nochance); textSize = 12f; setTextColor(Palette.labelTertiary)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    ends.addView(TextView(this).apply {
        text = getString(R.string.loosen_gotthis); textSize = 12f; setTextColor(Palette.labelTertiary); gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    root.addView(ends)
    label.text = delayBand(seek.progress)
    val cont = continueLink(getString(R.string.loosen_continue_anyway)) { loosenOneOffScreen() }
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
    p < 20 -> getString(R.string.loosen_band_impossible)
    p < 40 -> getString(R.string.loosen_band_veryhard)
    p < 60 -> getString(R.string.loosen_band_either)
    p < 80 -> getString(R.string.loosen_band_canhold)
    else -> getString(R.string.loosen_band_gotthis)
}

// ── Screen D: is this a one-off? how it shapes the future ───────────────────
private fun loosenOneOffScreen() {
    loosenBackAction = { loosenDelayChanceScreen() }
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.loosen_oneoff_title)))
    root.addView(body(getString(R.string.loosen_oneoff_body)))
    root.addView(RecoveryBrainView(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (resources.displayMetrics.density * 8).toInt(), 0, (resources.displayMetrics.density * 4).toInt())
    }
    list.addView(pickCard(getString(R.string.loosen_oneoff_yes)) { loosenOneOffFollow(true) }.apply { gravity = Gravity.CENTER })
    root.addView(list)
    root.addView(panicBar())
    setContentView(root)
}

private fun loosenOneOffFollow(oneOff: Boolean) {
    loosenBackAction = { loosenOneOffScreen() }
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(if (oneOff) getString(R.string.loosen_follow_title_yes) else getString(R.string.loosen_follow_title_no)))
    root.addView(body(if (oneOff)
        getString(R.string.loosen_follow_body_yes)
    else
        getString(R.string.loosen_follow_body_no)))
    root.addView(grow())
    root.addView(bigChoice(getString(R.string.loosen_wait_it_out), Palette.successText) {
        LoosenLog.record(this, "stopped", loosenRegret, loosenFix, 0)
        loosenStop(getString(R.string.loosen_stop_msg_hard))
    })
    root.addView(continueLink(getString(R.string.loosen_continue_anyway_short)) { loosenFixScreen() }.also { enableLink(it) })
    root.addView(grow())
    setContentView(root)
}

// ── reuse the temptation emotion picker, then where they are ────────────────
private fun loosenFixScreen() {
    loosenBackAction = { loosenOneOffScreen() }
    pickMultiWithCustomScreen(getString(R.string.loosen_emotions_q), Opts.FEELINGS, "feeling",
        onBack = { loosenBack() }) { feels -> loosenFix = feels.joinToString(", "); loosenPlaceScreen() }
}

private fun loosenPlaceScreen() {
    loosenBackAction = { loosenFixScreen() }
    pickWithCustomScreen(getString(R.string.loosen_place_q), Opts.LOCATIONS, "location",
        onBack = { loosenBack() }) { loc ->
        loosenFix = listOfNotNull(loosenFix?.takeIf { it.isNotBlank() }, loc).joinToString("  \u00b7  ")
        loosenUrgeGraphScreen()
    }
}

// ── the urge curve: they tap where they think they are on the wave ──────────
private fun loosenUrgeGraphScreen() {
    loosenBackAction = { loosenPlaceScreen() }
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.loosen_wave_title)))
    root.addView(TextView(this).apply {
        text = getString(R.string.loosen_wave_body)
        textSize = 14f; setTextColor(Palette.labelSecondary); setPadding(0, 0, 0, (4 * dp).toInt())
    })
    val resp = TextView(this).apply {
        textSize = 16f; gravity = Gravity.CENTER; setTextColor(Palette.successText)
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
    }
    val cont = continueLink(getString(R.string.loosen_continue_anyway)) { loosenWaitScreen() }
    val graph = PeakTapView(this, threshold = 0.30f) { _, correct ->
        if (correct) {
            resp.text = getString(R.string.loosen_wave_correct)
            enableLink(cont)
        } else {
            resp.text = getString(R.string.loosen_wave_wrong)
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
    loosenBackAction = { loosenStop(getString(R.string.loosen_stop_msg_stepback)) }
    if (!LoosenWait.isActive(this)) LoosenWait.start(this, 5L * 60 * 1000)
    val endAt = System.currentTimeMillis() + LoosenWait.remaining(this)
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
    }
    content.addView(TextView(this).apply {
        text = getString(R.string.loosen_wait_short); textSize = 21f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    // the only time readout - updates each minute, no ticking seconds
    val sub = TextView(this).apply {
        text = getString(R.string.loosen_wait_sub); textSize = 16f; gravity = Gravity.CENTER
        setTextColor(Palette.labelSecondary); setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
    }
    content.addView(sub)
    // the sweep on the page (no dark card), matching the temptation pages
    val sweep = SweepPanelView(this, Palette.sweep)
    content.addView(FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams((230 * dp).toInt(), (230 * dp).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = (12 * dp).toInt()
        }
        addView(sweep, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    })

    // the enticing primary; tapping it groups the "give it longer" options
    content.addView(GlowButton(this, getString(R.string.loosen_lock5)) { showLoosenLongerDialog() }.apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (62 * dp).toInt()
        ).apply { bottomMargin = (8 * dp).toInt() }
    })
    // the temptation-style exit, caption now inside the button
    content.addView(captionedButton(getString(R.string.temp_put_down), getString(R.string.temp_put_down_sub), Palette.successText) {
        LoosenLog.record(this, "stopped", loosenRegret, loosenFix, 0)
        try { finishAffinity() } catch (_: Throwable) { setupMainScreen() }
    })
    content.addView(grow())
    // revealed once the wait is up, pinned to the very bottom
    val doneContinue = continueLink(getString(R.string.loosen_waited_continue)) { loosenCommitStart() }
    content.addView(doneContinue)
    val root = ScrollView(this).apply {
        setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(content)
    }
    setContentView(root)
    runLoosenMinuteCountdown(sub, endAt) {
        enableLink(doneContinue); sub.setTextColor(Palette.successText); sub.setTypeface(sub.typeface, Typeface.BOLD)
    }
    loosenSweep = SweepAnimator(sweep).also { it.start(cycles = null) }
}

private fun showLoosenLongerDialog() {
    val dp = resources.displayMetrics.density
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (20 * dp).toInt(); setPadding(p, (8 * dp).toInt(), p, 0)
    }
    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.loosen_longer_title))
        .setView(box)
        .setNegativeButton(getString(R.string.loosen_keep5), null)
        .create()
    fun option(label: String, ms: Long) {
        box.addView(bigChoice(label, Palette.successText) {
            LoosenWait.start(this, ms); dialog.dismiss(); loosenWaitScreen()
        })
    }
    option(getString(R.string.loosen_lock10), 10L * 60 * 1000)
    option(getString(R.string.loosen_lock30), 30L * 60 * 1000)
    option(getString(R.string.loosen_lock2h), 2L * 60 * 60 * 1000)
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
        0 -> commitConfirmScreen(getString(R.string.loosen_step1), getString(R.string.loosen_honest_head),
            getString(R.string.loosen_honest_stmt), { loosenAdmit }, { loosenAdmit = it }, getString(R.string.loosen_honest_btn))
        1 -> commitNoteScreen(getString(R.string.loosen_step2))
        2 -> commitConfirmScreen(getString(R.string.loosen_step3), getString(R.string.loosen_promise_head),
            getString(R.string.loosen_promise_stmt), { loosenWontRepeat }, { loosenWontRepeat = it }, getString(R.string.loosen_promise_btn))
        3 -> commitDurationScreen(getString(R.string.loosen_step4))
    }
}

private fun commitConfirmScreen(step: String, heading: String, statement: String,
    get: () -> Boolean, set: (Boolean) -> Unit, continueLabel: String) {
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
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
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(stepText(step))
    root.addView(titleText(getString(R.string.loosen_note_title)))
    root.addView(TextView(this).apply {
        text = getString(R.string.loosen_note_private); textSize = 13f; setTextColor(Palette.labelSecondary)
        setPadding(0, 0, 0, (8 * dp).toInt())
    })
    val note = EditText(this).apply {
        hint = getString(R.string.loosen_note_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP or Gravity.START; minLines = 3; setText(loosenNote ?: "")
    }
    root.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(Button(this).apply {
        text = getString(R.string.loosen_note_btn)
        setOnClickListener {
            val t = note.text.toString().trim()
            if (t.isEmpty()) { Toast.makeText(this@MainActivity, getString(R.string.loosen_note_toast), Toast.LENGTH_SHORT).show() }
            else { loosenNote = t; commitStep++; renderCommitStep() }
        }
    })
    setContentView(root)
}

private fun commitDurationScreen(step: String) {
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(stepText(step))
    root.addView(titleText(getString(R.string.loosen_duration_title)))
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val btns = linkedMapOf<Int, Button>()
    listOf(1, 2, 5).forEach { m ->
        val b = Button(this).apply {
            text = getString(R.string.loosen_min, m); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btns[m] = b; row.addView(b)
    }
    root.addView(row)
    root.addView(grow())
    val cont = Button(this)
    val refresh = {
        btns.forEach { (m, b) -> b.setTypeface(Typeface.DEFAULT, if (m == loosenDuration) Typeface.BOLD else Typeface.NORMAL) }
        cont.text = getString(R.string.loosen_unlock_for, loosenDuration)
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
    val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
    val root = vbox(pad)
    root.addView(titleText(getString(R.string.loosen_unlocked_title, loosenDuration)))
    val countdown = TextView(this).apply {
        textSize = 40f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
    }
    root.addView(countdown)
    root.addView(body(getString(R.string.loosen_unlocked_body)))
    root.addView(grow())
    root.addView(bigChoice(getString(R.string.loosen_go), Palette.tint) { moveTaskToBack(true) })
    root.addView(Button(this).apply { text = getString(R.string.common_done); setOnClickListener { showReportScreen() } })
    setContentView(root)
    runLoosenCountdown(countdown, System.currentTimeMillis() + LoosenWindow.remaining(this)) {
        countdown.text = getString(R.string.loosen_relocked)
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
    text = s; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.labelSecondary)
    setPadding(0, 0, 0, (resources.displayMetrics.density * 4).toInt())
}
private fun panicBar(): Button = bigChoice(getString(R.string.loosen_stop_instead), Palette.successText) { openPanic() }

private fun bigPanic(): Button {
    val dp = resources.displayMetrics.density
    val third = resources.displayMetrics.heightPixels / 3
    return Button(this).apply {
        text = getString(R.string.loosen_stop_instead); setAllCaps(false)
        setTextColor(Palette.onFill); setTypeface(typeface, Typeface.BOLD); textSize = 20f
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Radius.card * dp; setColor(Palette.successText)
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, third)
            .apply { bottomMargin = (12 * dp).toInt() }
        setOnClickListener { openPanic() }
    }
}

private fun urgeGraphView(): View {
    val dp = resources.displayMetrics.density
    val v = object : View(this) {
        val act = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.danger; style = Paint.Style.STROKE; strokeWidth = 3 * dp }
        val wait = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.successText; style = Paint.Style.STROKE; strokeWidth = 3 * dp }
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
    val pad = (Space.page * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(titleText(getString(R.string.panic_title)))
    val pacer = TextView(this).apply {
        textSize = 30f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
    }
    root.addView(pacer)
    root.addView(TextView(this).apply {
        text = getString(R.string.panic_body)
        textSize = 14f; gravity = Gravity.CENTER; setTextColor(Palette.labelSecondary)
        setPadding(0, 0, 0, (12 * dp).toInt())
    })
    val grounding = listOf(
        getString(R.string.panic_ground_1),
        getString(R.string.panic_ground_2),
        getString(R.string.panic_ground_3),
        getString(R.string.panic_ground_4),
    )
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    grounding.forEachIndexed { i, s ->
        list.addView(TextView(this).apply {
            text = getString(R.string.panic_ground_line, i + 1, s); textSize = 15f
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        })
    }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(list)
    })
    val lockApps = bigChoice(getString(R.string.panic_lock_apps), Palette.successText) {}
    lockApps.setOnClickListener {
        Lockdown.start(this); lockApps.text = getString(R.string.panic_apps_locked); lockApps.isEnabled = false
        Toast.makeText(this, getString(R.string.panic_lockdown_toast), Toast.LENGTH_LONG).show()
    }
    root.addView(lockApps)
    root.addView(bigChoice(getString(R.string.panic_lock_screen), Palette.tint) { lockPhoneNow() })
    root.addView(Button(this).apply {
        text = getString(R.string.panic_okay)
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
            Toast.makeText(this, getString(R.string.panic_cant_lock), Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(this, getString(R.string.panic_need_lock), Toast.LENGTH_SHORT).show()
    }
}

// ── shared bits for this flow ──────────────────────────────────────────────
private fun panicButton(): Button = bigChoice(getString(R.string.panic_button), Palette.dangerText) { openPanic() }

/** The full-width filled action. Design-system button: tint fill, ripple, press-scale. */
private fun bigChoice(label: String, color: Int, onClick: () -> Unit): Button {
    return Button(this).apply {
        text = label; setAllCaps(false)
        textSize = Type.headline
        setTextColor(Palette.onFill); setTypeface(typeface, Typeface.BOLD)
        stateListAnimator = null                 // kill the Material lift; ours is flatter
        background = tappableBg(color, Radius.control, stroke = null, ripple = 0x33FFFFFF)
        elevation = dpf(1.5f)
        val p = dp(Space.md); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(Space.xs) }
        setOnClickListener { onClick() }
        pressable()
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
            if (rem <= 0) { label.text = getString(R.string.loosen_continue_now); onDone() }
            else {
                val mins = ((rem + 59_999) / 60_000).toInt()
                label.text = getString(R.string.loosen_continue_in, mins, if (mins == 1) "" else "s")
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
    loosenSweep?.stop(); loosenSweep = null
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

/**
 * Set for one setContentView call, to suppress the standard entrance.
 *
 * Two callers: the tab bar (which animates its content itself, so the floating pill can
 * stay still - see withBottomBar), and a tab/back RESTORE, where the page is not arriving
 * at all, it is being uncovered. Same one-shot pattern as [noThumb] above.
 */
private var skipEnter = false

/** The landing screen: rendered raw, with no thumb back button. */
private fun setContentNoThumb(content: View) {
    noThumb = true
    try { setContentView(content) } finally { noThumb = false }
}

override fun setContentView(view: View) {
    // The page background lives here, not on 60 individual screens. Anything that wants
    // to sit ON the page (a card, a row) gets Palette.surface / glass; the page itself is
    // always Palette.bg, which is what stops screens drifting apart tonally.
    if (view.background == null) view.setBackgroundColor(Palette.bg)
    val enter = !skipEnter
    skipEnter = false
    if (noThumb) {
        if (enter) view.enterFromBelow()
        currentContent = view
        super.setContentView(view)
        return
    }
    val frame = android.widget.FrameLayout(this).apply {
        setBackgroundColor(Palette.bg)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    frame.addView(view, android.widget.FrameLayout.LayoutParams(
        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
    val size = dp(56)
    val thumb = thumbBack { onBackPressed() }
    frame.addView(
        thumb,
        android.widget.FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = (resources.displayMetrics.heightPixels * 0.20f).toInt()
            marginEnd = dp(Space.md)
        },
    )
    // EVERY page gets the same entrance, for free, because it happens here. The content
    // fades up; the back button just fades, so it doesn't appear to slide around the
    // screen between pages (it is the one element that never moves).
    if (enter) {
        view.enterFromBelow()
        thumb.alpha = 0f
        thumb.animate().alpha(1f).setStartDelay(Motion.fast / 2)
            .setDuration(Motion.base).setInterpolator(Motion.easeOut).start()
    }
    currentContent = frame
    super.setContentView(frame)
}

// Declares where THIS page's back goes (subBack), then renders it. The thumb button is
// added by the setContentView override above - this no longer adds one itself.
private fun setContentWithThumb(content: View, onBack: () -> Unit) {
    onReportScreen = false; onTemptationsTab = false; onDevScreen = false
    inTemptationFlow = false; inLoosenFlow = false; inAppSiteFlow = false
    inSubPage = true
    subBack = onBack
    setContentView(content)
}

// =====================================================================================
//  THE TWO TABS KEEP THEIR PLACE
// =====================================================================================
//
//  Tapping Temptations and then Overview again used to rebuild Overview from scratch:
//  back to the top of the page, and back out of whatever you had opened. That is fine for
//  a link and wrong for a TAB. A tab is not a destination you travel to, it is a room you
//  step out of and back into, and the room is supposed to be how you left it.
//
//  So each tab remembers the LIVE VIEW you were last looking at inside it - not a rebuilt
//  copy, the actual instance - which is what makes the scroll position, the half-open
//  sections and the sub-page you had opened all come back for free. Rebuilding and then
//  trying to reapply a scroll offset gets you a flicker and, on any page whose height
//  depends on live data, the wrong offset.
//
//  Memory is written ONLY when you leave a tab sideways (a tab tap or a swipe) and is
//  consumed the moment you come back. Anything else - the back button, a fresh entry from
//  a flow, updateScreen() rebuilding the dashboard - drops it, so a stale page can never
//  resurface later: setupHomeScreen and showTemptationsTab both clear their own slot.
private class TabMemory(
    val view: View,
    val subBack: (() -> Unit)?,
    val onReport: Boolean,
    val onDev: Boolean,
    val inSub: Boolean,
)


/** The view currently on screen (the thumb frame, where there is one). */
private var currentContent: View? = null
/** Which tab's stack we are in - a sub-page belongs to the tab it was opened from. */
private var activeTab = 0
/** True only while onBackPressed is walking a page's declared back target. */
private var inBackNav = false
private val tabMemory = HashMap<Int, TabMemory>()

/**
 * Called at the top of a tab root's rebuild: this tab is the one we are in, and its old
 * snapshot is now void - the page is being rebuilt from current data, and withBottomBar
 * records the fresh one a moment later.
 */
private fun claimTab(index: Int) {
    activeTab = index
    tabMemory.remove(index)
}

/** Snapshot whatever is on screen as tab [index]'s saved state. */
private fun rememberTab(index: Int) {
    val v = currentContent ?: return
    tabMemory[index] = TabMemory(v, subBack, onReportScreen, onDevScreen, inSubPage)
}

/**
 * Put tab [to]'s saved page back on screen, exactly as it was left - scroll position,
 * open sections and all. Returns false if there is nothing to put back.
 *
 * The identity check matters: if the saved view IS what is already on screen, restoring it
 * would be a no-op that swallowed a real navigation (it happens when you swipe tabs from a
 * sub-page and then press back). Drop the snapshot and let the caller build fresh.
 */
private fun restoreTab(to: Int): Boolean {
    val saved = tabMemory[to] ?: return false
    // Already on screen: restoring would swallow a real navigation. Leave the snapshot
    // where it is - the caller rebuilds, and the rebuild replaces it (claimTab).
    if (saved.view === currentContent) return false
    // The snapshot is NOT consumed. Going Overview -> sub-page -> back -> sub-page -> back
    // has to work every time, and each of those backs is a fresh restore of the same page.
    // It is cleared only when the tab is genuinely rebuilt.
    activeTab = to
    (saved.view.parent as? ViewGroup)?.removeView(saved.view)
    onReportScreen = saved.onReport; onDevScreen = saved.onDev; inSubPage = saved.inSub
    subBack = saved.subBack
    onHomeScreen = !saved.inSub && to == 0
    onTemptationsTab = !saved.inSub && to == 1
    if (onTemptationsTab) markTabSeen("temptations")
    noThumb = true; skipEnter = true       // uncovered, not arriving: no entrance animation
    try { setContentView(saved.view) } finally { noThumb = false }
    return true
}

/**
 * Move to the other tab, saving this one exactly as it stands.
 *
 * [to] is the tab INDEX used by withBottomBar: 0 Overview, 1 Temptations.
 */
private fun switchTab(to: Int) {
    if (to == activeTab) return
    rememberTab(activeTab)
    activeTab = to
    if (!restoreTab(to)) { if (to == 0) setupHomeScreen() else showTemptationsTab() }
}

/**
 * Swipe left/right to change tab, anywhere inside a tab - including its sub-pages, which
 * is the point: swiping off Adult content and back returns you to Adult content.
 *
 * NOT during a flow (temptation, loosen, report-an-app) and NOT at a setup gate the mode
 * is holding up. Those are screens the user is meant to finish or answer, and a stray
 * horizontal flick must not be a way out of one.
 */
private fun tabSwipeAllowed(): Boolean {
    if (inTemptationFlow || inLoosenFlow || inAppSiteFlow) return false
    if (inPermissionFlow || atMandatoryGate()) return false
    return onHomeScreen || onTemptationsTab || onReportScreen || onDevScreen || inSubPage
}

private val tabSwipe by lazy {
    android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: android.view.MotionEvent?, e2: android.view.MotionEvent,
            velocityX: Float, velocityY: Float,
        ): Boolean {
            val start = e1 ?: return false
            if (!tabSwipeAllowed()) return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            // Deliberately fussy, because the pages underneath scroll VERTICALLY: it has
            // to be long enough to be meant, fast enough to be a flick, and clearly more
            // sideways than not, or a slightly diagonal scroll would change tab under you.
            if (kotlin.math.abs(dx) < dpf(72f)) return false
            if (kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.6f) return false
            if (kotlin.math.abs(velocityX) < dpf(320f)) return false
            switchTab(if (dx < 0) 1 else 0)      // drag left = move right along the bar
            return true
        }
    })
}

override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
    // OBSERVE, never consume: every touch still reaches the page underneath, so scrolling,
    // ripples and taps behave exactly as they did before the gesture existed.
    tabSwipe.onTouchEvent(ev)
    return super.dispatchTouchEvent(ev)
}

/** Every screen's title, one place. See Design.kt's pageTitle for the type decisions. */
private fun titleText(t: String): TextView = pageTitle(t)

// ── ride-it-out countdown ──────────────────────────────────────────────────
private fun stopRideTimer() {
    rideRunnable?.let { rideHandler?.removeCallbacks(it) }
    rideRunnable = null
    rideHandler = null
    breatheOn = false
    waveSweep?.stop(); waveSweep = null
}


private fun onLookAnyway() {
    startLoosenFlow()
}
// The "Report relapse" pane and its whole step-by-step flow have been REMOVED. The
// options lists below outlived it: they are shared with the temptation and loosen
// flows (see optCodeList / baseFor), so they stay.

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

/** One rounded, full-width tappable option card. */
private fun pickCard(label: String, onClick: () -> Unit): TextView {
    return TextView(this).apply {
        text = label
        textSize = Type.headline
        setTextColor(Palette.label)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Space.md), dp(Space.md), dp(Space.md), dp(Space.md))
        background = tappableBg(Palette.glass, Radius.control)
        elevation = dpf(1f)
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(Space.xs) }
        setOnClickListener { onClick() }
        pressable()
    }
}

// ── nicer option rows: emoji icon (vertically centred) + label + lighter sub ──
private data class Choice(
    val value: String,               // STABLE key: stored + compared + icon-keyed (English)
    /** A drawable res for the leading glyph (see optionIcon). Emoji used to live here. */
    val icon: Int? = null,
    val sub: String? = null,
    val tint: Int = Palette.surfaceSunken,
    val group: String? = null,
    val label: String = value,       // localized DISPLAY text (defaults to value / custom entries)
)

private fun optCodeList(category: String?): List<String>? = when (category) {
    "feeling" -> Opts.FEELINGS
    "location" -> Opts.LOCATIONS
    "activity" -> ACTIVITIES
    "screen" -> Opts.SCREEN_TYPES
    "urge" -> Opts.URGE_LEVELS
    else -> null
}
/** Localized display label for a stored English option value; the value itself for custom entries. */
private fun optLabel(category: String?, value: String): String {
    val list = optCodeList(category) ?: return value
    val i = list.indexOf(value); if (i < 0) return value            // custom / unknown -> as-is
    val arrId = resources.getIdentifier("opt_$category", "array", packageName)
    return if (arrId == 0) value else resources.getStringArray(arrId).getOrElse(i) { value }
}
private fun tgroupResId(g: TGroup, suffix: String): Int =
    resources.getIdentifier("tgroup_${g.name.lowercase()}_$suffix", "string", packageName)
private fun tgroupShort(g: TGroup): String = getString(tgroupResId(g, "short"))
private fun tgroupExample(g: TGroup): String = getString(tgroupResId(g, "example"))
private fun tgroupTitle(g: TGroup): String = getString(tgroupResId(g, "title"))

private fun urgeExample(level: String): String {
    val i = Opts.URGE_LEVELS.indexOf(level); if (i < 0) return ""
    return resources.getStringArray(R.array.opt_urge_examples).getOrElse(i) { "" }
}

private fun metaFor(category: String, v: String): Choice = when (category) {
    "screen" -> Choice(v, screenIcon(v), label = optLabel("screen", v))
    "location" -> Choice(v, locationIcon(v), label = optLabel("location", v))
    "activity" -> Choice(v, activityIcon(v), label = optLabel("activity", v))
    "feeling" -> feelingMeta(v).copy(label = optLabel("feeling", v))
    else -> Choice(v, label = optLabel(category, v))
}

// ── THE OPTION GLYPHS ───────────────────────────────────────────────────────────────
//  These four maps used to return emoji. On a page whose whole job is to be read calmly
//  by someone mid-urge, a column of full-colour cartoon faces is the loudest thing on the
//  screen and the least informative - and it rendered differently on every phone. They are
//  stroke marks now, drawn on the same 24dp grid as the rest of the app's icons.
//
//  The FEELINGS are deliberately not faces. A line that goes flat, a line that jags, an
//  arrow pointing down: the mark says the shape of the mood without acting it out.
private fun screenIcon(v: String) = when (v) {
    "Phone" -> R.drawable.ic_opt_phone; "Tablet" -> R.drawable.ic_opt_tablet
    "Computer / laptop" -> R.drawable.ic_opt_laptop; "TV" -> R.drawable.ic_opt_tv
    "Someone else's screen" -> R.drawable.ic_opt_eye; else -> R.drawable.ic_opt_display
}
private fun locationIcon(v: String) = when (v) {
    "Bedroom" -> R.drawable.ic_opt_bed; "Bathroom" -> R.drawable.ic_opt_droplet
    "Living room" -> R.drawable.ic_opt_sofa; "Kitchen" -> R.drawable.ic_opt_pan
    "Office / desk" -> R.drawable.ic_opt_briefcase; "Out / in public" -> R.drawable.ic_opt_tree
    else -> R.drawable.ic_opt_pin
}
private fun activityIcon(v: String) = when (v) {
    "In bed / trying to sleep" -> R.drawable.ic_opt_bed; "Just woke up" -> R.drawable.ic_opt_sunrise
    "Scrolling social media" -> R.drawable.ic_opt_phone; "Watching videos or TV" -> R.drawable.ic_opt_tv
    "Browsing the web" -> R.drawable.ic_opt_globe
    "Putting off something I should do" -> R.drawable.ic_opt_hourglass
    "Just finished work or study" -> R.drawable.ic_opt_briefcase
    "Bored with nothing to do" -> R.drawable.ic_opt_bored
    "After something stressful" -> R.drawable.ic_opt_bolt
    "Winding down at night" -> R.drawable.ic_opt_moon
    else -> R.drawable.ic_opt_repeat
}
// feelings carry a group + a subtle tint so the screen reads as grouped bands
private fun feelingMeta(v: String): Choice = when (v) {
    "Anxious / on edge" -> Choice(v, R.drawable.ic_opt_zigzag, null, Palette.warningSoft, "On edge")
    "Stressed" -> Choice(v, R.drawable.ic_opt_bolt, null, Palette.warningSoft, "On edge")
    "Frustrated / angry" -> Choice(v, R.drawable.ic_opt_flame, null, Palette.dangerSoft, "Wound up")
    "Low / down" -> Choice(v, R.drawable.ic_opt_arrow_down, null, Palette.surfaceSunken, "Shut down / flat")
    "Lonely" -> Choice(v, R.drawable.ic_opt_person, null, Palette.surfaceSunken, "Shut down / flat")
    "Tired" -> Choice(v, R.drawable.ic_opt_moon, null, Palette.surfaceSunken, "Shut down / flat")
    "Neutral" -> Choice(v, R.drawable.ic_opt_line, null, Palette.surfaceSunken, "Shut down / flat")
    "Bored" -> Choice(v, R.drawable.ic_opt_bored, null, Palette.surfaceSunken, "Bored")
    "Happy / excited" -> Choice(v, R.drawable.ic_opt_sparkle, null, Palette.successSoft, "Feeling good")
    else -> Choice(v, R.drawable.ic_opt_line)
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
            cornerRadius = Radius.control * dp; setColor(tint)
            if (selected) setStroke((2 * dp).toInt(), Palette.successText)
        }
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * dp).toInt() }
    }
}
/** The option row's leading glyph. Bare and monotone, like every other icon in the app. */
private fun optionIcon(icon: Int?): View? {
    if (icon == null || icon == 0) return null
    return iconBadge(icon, glyph = Palette.labelSecondary)
}
private fun textCol(label: String, sub: String?): LinearLayout {
    val dp = resources.displayMetrics.density
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = 17f; setTextColor(Palette.label)
        })
        if (!sub.isNullOrEmpty()) addView(TextView(this@MainActivity).apply {
            text = sub; textSize = 13f; setTextColor(Palette.labelTertiary); setPadding(0, (3 * dp).toInt(), 0, 0)
        })
    }
}
private fun checkRow(choice: Choice, checked: Boolean, onToggle: () -> Unit): View {
    val dp = resources.displayMetrics.density
    val card = rowCard(choice.tint, checked)
    card.addView(TextView(this).apply {
        text = if (checked) "\u2611" else "\u2610"; textSize = 22f
        setTextColor(if (checked) Palette.successText else Palette.labelTertiary)
        setPadding(0, 0, (12 * dp).toInt(), 0)
    })
    optionIcon(choice.icon)?.let { card.addView(it) }
    card.addView(textCol(choice.label, choice.sub))
    card.setOnClickListener { onToggle() }
    return card
}
private fun optionRow(choice: Choice, onClick: () -> Unit): View {
    val card = rowCard(choice.tint, false)
    optionIcon(choice.icon)?.let { card.addView(it) }
    card.addView(textCol(choice.label, choice.sub))
    card.setOnClickListener { onClick() }
    return card
}
private fun addOwnRow(onClick: () -> Unit): View =
    optionRow(Choice(getString(R.string.picker_add_own_row), R.drawable.ic_opt_plus), onClick)

/** Big primary Continue that brightens and grows once something is selected. */
private fun bigContinue(label: String, onClick: () -> Unit): Button {
    val dp = resources.displayMetrics.density
    return Button(this).apply {
        text = label; setAllCaps(false); setTextColor(Palette.onFill)
        setTypeface(typeface, Typeface.BOLD); textSize = 16f
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Radius.control * dp; setColor(Palette.divider)
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
        if (active) Palette.successText else Palette.divider)
    btn.textSize = if (active) 18f else 16f
    btn.animate().scaleX(if (active) 1.03f else 1f).scaleY(if (active) 1.03f else 1f).setDuration(140).start()
}

/** A quiet, lowercase "continue anyway" link that stays disabled until they've engaged. */
private fun continueLink(label: String, onClick: () -> Unit): Button {
    val dp = resources.displayMetrics.density
    return Button(this).apply {
        text = label; setAllCaps(false); setTextColor(Palette.tintDeep); textSize = 15f
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
        setTextColor(Palette.onFill); setTypeface(typeface, Typeface.BOLD)
        setLineSpacing((2 * dp), 1f)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Radius.control * dp; setColor(color)
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
private fun urgeScaleScreen(title: String, onBack: (() -> Unit)?, onPick: (String) -> Unit) {
    val dp = resources.displayMetrics.density
    val pad = (Space.page * dp).toInt()
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
text = getString(R.string.urge_high); textSize = 12f; gravity = Gravity.CENTER
        setTextColor(0x33000000); setPadding(0, 0, 0, (6 * dp).toInt())
    })
    val red = Palette.danger
    val blue = Palette.series[1]
    val ordered = Opts.URGE_LEVELS.reversed()   // Overwhelming (top) -> Barely there (bottom)
    ordered.forEachIndexed { i, level ->
        val f = if (ordered.size > 1) i.toFloat() / (ordered.size - 1) else 0f
        center.addView(urgeCard(optLabel("urge", level), urgeExample(level), lerpColor(red, blue, f)) { onPick(level) })
    }
    center.addView(TextView(this).apply {
text = getString(R.string.urge_low); textSize = 12f; gravity = Gravity.CENTER
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
            cornerRadius = Radius.control * dp; setColor(color)
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
        setTextColor(Palette.onFill)
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
    btn.text = if (locked) getString(R.string.proto_break_locked, Mode.timeLeft(this))
               else getString(R.string.report_protocol)
}

private fun startWeekStrict() {
    if (Mode.isLocked(this)) return
    AlertDialog.Builder(this)
        .setTitle("Start week-long strict mode?")
        .setMessage("Strict mode will stay on for 7 days. You won't be able to switch back to Relaxed until it ends.")
        .setPositiveButton("Start") { _, _ ->
            Mode.startWeekStrict(this)
            refreshModeUi()
            Toast.makeText(this, getString(R.string.proto_7day_toast), Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}


    private var entriesJob: kotlinx.coroutines.Job? = null
    private var shownStep: Step? = null

    // The setup gate in order. MONITORING/OVERLAY are OS permissions; FIREFOX/EXTENSION are
    // the browser half (see BrowserSetup) - watching a browser from outside cannot see images,
    // which is the extension's job. LOCK is the one-time uninstall-lock offer.
    private enum class Step { MONITORING, OVERLAY, FIREFOX, EXTENSION, LOCK, READY }

    /**
     * The steps the "Step N of M" counter covers. LOCK is deliberately absent: it is an OFFER,
     * not a requirement, and dev builds skip it entirely (AppConfig.DEV_MODE) - which is what
     * made the flow announce "4 of 5" and then simply stop.
     */
    private val GATE_STEPS = listOf(Step.MONITORING, Step.OVERLAY, Step.FIREFOX, Step.EXTENSION)

    /**
     * The steps that were outstanding when this run of the gate started, held for the run.
     *
     * Numbering against the fixed list of four would show gaps ("1, 2, 4" when Firefox is
     * already installed); recomputing what's left on every screen would renumber under the
     * user ("2 of 3", then "1 of 2" once they finish one). Planning once does neither.
     *
     * PERSISTED, not just remembered: every step sends the user out to another app (Settings,
     * the Play Store, Firefox) and enabling the accessibility service restarts us outright, so
     * an in-memory plan is lost precisely when it is being used - which reads as the counter
     * renumbering itself mid-flow. Cleared when the gate is finished or abandoned.
     */
    private fun gatePlan(): List<Step>? =
        setupPrefs().getString("gate_plan", null)
            ?.split(',')
            ?.mapNotNull { n -> runCatching { Step.valueOf(n) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

    private fun saveGatePlan(plan: List<Step>) =
        setupPrefs().edit().putString("gate_plan", plan.joinToString(",") { it.name }).apply()

    /** Finished or walked away from: the next run gets a plan built from fresh facts. */
    private fun clearGatePlan() = setupPrefs().edit().remove("gate_plan").apply()

    /** "Step 2 of 3\n<name>" for the screen being shown, planning the run if needed. */
    private fun stepTitle(step: Step, name: String): String {
        // Rebuild when there is no plan, or when reality moved outside it (they uninstalled
        // Firefox halfway through, say) - a stale plan must never mis-number a real step.
        var plan = gatePlan()
        if (plan == null || step !in plan) {
            plan = GATE_STEPS.filter { it == step || !stepSatisfied(it) }
            saveGatePlan(plan)
        }
        return getString(R.string.step_counter, plan.indexOf(step) + 1, plan.size) + "\n" + name
    }

    private fun stepSatisfied(step: Step): Boolean = when (step) {
        Step.MONITORING -> isAccessibilityEnabled()
        Step.OVERLAY    -> Settings.canDrawOverlays(this)
        Step.FIREFOX    -> BrowserSetup.firefoxInstalled(this)
        Step.EXTENSION  -> BrowserSetup.extensionConfirmed(this)
        else            -> true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Installs that predate the Off mode never stored a choice but were forced through
        // the permission gate - both permissions granted means monitoring was live.
        Mode.migrateIfUnset(this, isAccessibilityEnabled() && Settings.canDrawOverlays(this))
        markFirstOpen()
        FilterData.init(this)          // load word/app/domain lists from assets/filter/
        BlockRules.load(this)
        updateScreen()
    }

    override fun onResume() {
        super.onResume()
        AppBlocklist.refresh(this)
        // onStop stops the beacon page's scanner; returning to the app must restart it
        // IMMEDIATELY (start() skips ensureScanning's restart cool-down), or the page
        // shows "not heard" for up to ~12s and looks like the sensors dropped.
        beaconScanner?.start(); pressureMon?.start()
        locationMonitor?.start()
        updateScreen()   // re-checks prerequisites every time the app is foregrounded
    }

    override fun onStop() {
        super.onStop()
        sensorMonitor?.stop(); sensorMonitor = null
        // A GPS listener left running behind a dark screen is the most expensive thing
        // this app could leak. Kept (not cleared) so onResume can restart it if the
        // home-area page is still the one on screen - same handling as the beacons.
        locationMonitor?.stop()
        // Don't scan for beacons (or read the barometer) with the screen off; the
        // debug page's tick restarts both on resume.
        beaconScanner?.stop(); pressureMon?.stop()
        // Don't leave a breathing orb posting frame callbacks at a screen nobody is looking at.
        habitSweep?.stop(); habitSweep = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        inBackNav = true
        try { dispatchBack() } finally { inBackNav = false }
    }

    private fun dispatchBack() {
        when {
            // A mode change waiting on the permissions: back CANCELS it. Nothing to undo -
            // the mode was never committed - so this just drops it and carries on.
            pendingMode != null -> { pendingMode = null; inPermissionFlow = false; clearGatePlan(); updateScreen() }
            // The gate is MANDATORY: the mode is above Off and a permission is missing or
            // has been revoked. Back cannot be the way to keep a mode nothing enforces, so
            // it falls back to Off. (If strict is LOCKED, setMode refuses and the gate stays
            // put - which is exactly what a lock is for.)
            // ...and if the RATCHET refuses Off (they have been Strict at some point), it
            // falls back to RELAXED instead. That fallback is load-bearing, not tidiness:
            // from Strict up, SetupGuard covers the whole phone while the setup is unfinished,
            // and this screen is the only route to a lower mode - the gate replaces the mode
            // spinner, so without a landing spot here "ratchet + unfinished setup" would be a
            // phone nobody can use. Relaxed still enforces everything it promises without the
            // browser half, and the gate stays up in here until they finish it properly.
            atMandatoryGate() -> {
                clearGatePlan()
                val dropped = Mode.setMode(this, Mode.OFF) || Mode.setMode(this, Mode.RELAXED)
                if (dropped)
                    Toast.makeText(
                        this,
                        getString(R.string.mode_reverted_toast, modeDisplayName(Mode.current(this))),
                        Toast.LENGTH_LONG,
                    ).show()
                updateScreen()
            }
            // Backing out of a VOLUNTARY permission screen is the same as "Not now" -
            // otherwise the flow flag stays armed and the screen reappears on resume.
            inPermissionFlow -> { inPermissionFlow = false; updateScreen() }
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
    //
    // FOUR things are only MANDATORY while the adult-content monitoring mode is above Off
    // (the fresh-install default): the two OS permissions, Firefox, and our Firefox add-on
    // (see BrowserSetup for why the browser half is part of the gate at all). In Off you land
    // straight on the main screen; the app nudges instead: a one-time popup once you've seen
    // all three tabs (or 30 minutes in), then a quiet amber banner on Overview. Both routes
    // set inPermissionFlow, which walks the same prereq screens VOLUNTARILY (with a "Not now"
    // escape). Completing them bumps the mode to Relaxed - and from that moment the setup is
    // mandatory again: drop any part of it and you're back at the gate.

    // The full-screen uninstall-lock prompt shows only during FIRST setup (persisted
    // below). The arousal page instead shows a dismissible centred popup EVERY time it
    // opens while unprotected (showUnprotectedPopup) - the home page nags nowhere.
    private fun setupPrefs() = getSharedPreferences("setup", Context.MODE_PRIVATE)
    private fun lockPromptSeen(): Boolean = setupPrefs().getBoolean("lock_prompt_seen", false)
    private fun markLockPromptSeen() =
        setupPrefs().edit().putBoolean("lock_prompt_seen", true).apply()

    // ── Permission nudges (only while the mode is Off - see the gate comment below) ──
    // A one-time centred popup fires 3 seconds after the third main tab has been seen;
    // after that (or ~30 min after first open, whichever comes first) Overview carries a
    // quiet amber banner instead. Both routes just set inPermissionFlow and let the
    // ordinary prereq screens do the walking.
    private val PERM_NUDGE_AFTER_MS = 30L * 60 * 1000
    private var permPopupScheduled = false
    // Whether the last setupHomeScreen() build included the banner - lets updateScreen()
    // rebuild Overview exactly once when the nudge first becomes due mid-session.
    private var homeBuiltWithNudge = false

    private fun firstOpenAt(): Long = setupPrefs().getLong("first_open_at", 0L)
    private fun markFirstOpen() {
        if (firstOpenAt() == 0L)
            setupPrefs().edit().putLong("first_open_at", System.currentTimeMillis()).apply()
    }
    private fun permPopupDone(): Boolean = setupPrefs().getBoolean("perm_popup_done", false)
    private fun markPermPopupDone() =
        setupPrefs().edit().putBoolean("perm_popup_done", true).apply()

    private fun markTabSeen(tab: String) {
        if (!setupPrefs().getBoolean("seen_$tab", false))
            setupPrefs().edit().putBoolean("seen_$tab", true).apply()
        maybeSchedulePermPopup()
    }
    // Overview + Temptations. Productivity used to be the third, but it is a sub-page now
    // (see withBottomBar) and gating the permissions offer on a page nobody has to visit
    // would leave the offer permanently un-armed for most people.
    private fun allTabsSeen(): Boolean =
        listOf("overview", "temptations")
            .all { setupPrefs().getBoolean("seen_$it", false) }

    /** 3 seconds after the last of the tabs is first seen, offer the flow - once ever. */
    private fun maybeSchedulePermPopup() {
        if (permPopupScheduled || permPopupDone()) return
        if (!Mode.isOff(this) || corePermsGranted() || !allTabsSeen()) return
        permPopupScheduled = true
        Handler(Looper.getMainLooper()).postDelayed({
            permPopupScheduled = false
            if (isFinishing || isDestroyed) return@postDelayed
            if (permPopupDone() || !Mode.isOff(this) || corePermsGranted()) return@postDelayed
            showEnablePermissionsPopup()
        }, 3_000)
    }

    /** Should Overview carry the amber "set up the permissions" banner right now? */
    private fun shouldNudgePermissions(): Boolean =
        Mode.isOff(this) && !setupComplete() &&
            (permPopupDone() || System.currentTimeMillis() - firstOpenAt() > PERM_NUDGE_AFTER_MS)

    /** The subtle amber strip at the top of Overview. Tapping it starts the flow. */
    private fun permissionNudgeBanner(): View {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = getString(R.string.home_banner_blocking_off)
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.warningText)
            setLineSpacing(0f, 1.15f)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Radius.control * dp; setColor(Palette.warningSoft)
                setStroke((1.5f * dp).toInt(), Palette.warning)
            }
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (10 * dp).toInt() }
            setOnClickListener { inPermissionFlow = true; updateScreen() }
        }
    }

    // The one-time "turn the blocking on?" offer, same look as showUnprotectedPopup.
    private fun showEnablePermissionsPopup() {
        markPermPopupDone()      // once, ever - the Overview banner takes over from here
        val dp = resources.displayMetrics.density
        val dialog = AlertDialog.Builder(this).create()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Radius.card * dp; setColor(Palette.warningSoft); setStroke((1.5f * dp).toInt(), Palette.warning)
            }
            val p = (18 * dp).toInt(); setPadding(p, (10 * dp).toInt(), p, p)
        }
        card.addView(TextView(this).apply {
            text = "✕"; textSize = 18f; setTextColor(Palette.warningText)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END }
            val t = (8 * dp).toInt(); setPadding(t, t, t, t)
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.perm_ready_title); textSize = 17f
            setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.warningText)
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.perm_ready_body)
            textSize = 13f; setTextColor(Palette.warningText); setPadding(0, (6 * dp).toInt(), 0, (12 * dp).toInt())
        })
        card.addView(bigChoice(getString(R.string.common_continue), Palette.successText) {
            dialog.dismiss()
            inPermissionFlow = true
            updateScreen()
        })
        dialog.setView(card)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.show()
    }

    private var onReportScreen = false
    private var inSubPage = false
    private var onHomeScreen = false
    private var onTemptationsTab = false
    private var onDevScreen = false
    private var subBack: (() -> Unit)? = null
    private var sensorMonitor: SensorMonitor? = null
    private var locationMonitor: LocationMonitor? = null
    private var beaconScanner: BeaconScanner? = null
    private var pressureMon: PressureMonitor? = null
    // The beacon pages' UI ticker. One shared handle so each page (and the wizard)
    // kills the previous page's ticker instead of leaking it across navigation.
    private var beaconUi: Handler? = null
    // Same idea for the home-area page: its tick refreshes the fix's AGE, which changes
    // even when no new fix arrives.
    private var homeAreaUi: Handler? = null
    private var reportBackTarget: () -> Unit = { showTemptationsTab() }

    /** True when the user is walking the permission screens by choice (mode still Off). */
    private var inPermissionFlow = false

    /**
     * A mode the user picked that is NOT committed yet, because it needs the two core
     * permissions and they aren't on. It lands in updateScreen the moment both are granted.
     * Parking it (rather than setting the mode and gating afterwards) is what stops the back
     * button leaving the app in a mode with nothing behind it.
     */
    private var pendingMode: String? = null

    private fun corePermsGranted(): Boolean =
        isAccessibilityEnabled() && Settings.canDrawOverlays(this)

    /**
     * EVERYTHING a mode above Off needs: the two OS permissions plus the browser half.
     * This - not corePermsGranted - is what lets a mode change land, so uninstalling Firefox
     * is no more a way to keep an unenforced mode than revoking accessibility is.
     */
    private fun setupComplete(): Boolean =
        corePermsGranted() && BrowserSetup.firefoxInstalled(this) &&
            BrowserSetup.extensionConfirmed(this)

    /** True while the setup gate is up because the CURRENT mode demands it - no way past. */
    private fun atMandatoryGate(): Boolean =
        shownStep in setOf(Step.MONITORING, Step.OVERLAY, Step.FIREFOX, Step.EXTENSION) &&
            !Mode.isOff(this) && !setupComplete()

    private fun currentStep(): Step {
        // Above Off the permissions are mandatory; in Off they're only shown while the
        // user has voluntarily entered the flow (popup / banner / "Not now" backs out)
        // or has a mode change waiting on them (pendingMode).
        val needPerms = !Mode.isOff(this) || inPermissionFlow || pendingMode != null
        return when {
            needPerms && !isAccessibilityEnabled()       -> Step.MONITORING
            needPerms && !Settings.canDrawOverlays(this) -> Step.OVERLAY
            // Firefox is VERIFIABLE, so this step clears itself the moment they come back
            // from the store. The extension is not, so that one ends in their own word.
            needPerms && !BrowserSetup.firefoxInstalled(this)  -> Step.FIREFOX
            needPerms && !BrowserSetup.extensionConfirmed(this) -> Step.EXTENSION
            corePermsGranted() && !AppConfig.DEV_MODE && !lockPromptSeen() -> Step.LOCK
            else                                         -> Step.READY
        }
    }

    private fun updateScreen() {
        // A mode change parked at the gate (see modeSpinner) lands HERE, once the WHOLE
        // setup is genuinely done - never at the moment it was picked.
        val pending = pendingMode
        if (pending != null && setupComplete()) {
            pendingMode = null
            inPermissionFlow = false
            if (Mode.setMode(this, pending)) {
                Toast.makeText(this, getString(R.string.mode_on_toast, modeDisplayName(pending)), Toast.LENGTH_SHORT).show()
                // The mode the user picked has only just landed, so the house offer that
                // belongs to it lands here too (see modeSpinner). It takes the screen, so
                // there is nothing left for the rest of this pass to draw.
                //
                // `shownStep` is recorded on the way out, and that is load-bearing: this
                // return skips the line further down that normally does it, so the NEXT
                // updateScreen (the one that comes with onResume, straight after the
                // location dialog the house offer sent the user to) saw a stale step, took
                // itself to be a fresh arrival at READY, and rebuilt the dashboard on top
                // of the house page the user was halfway through.
                if (maybeAskAboutHouse(pending)) { shownStep = Step.READY; return }
            }
        }
        // Finishing the voluntary flow turns monitoring on at its lowest level. From here
        // every setup step is mandatory (currentStep) until the mode is set back to Off.
        if (inPermissionFlow && setupComplete()) {
            inPermissionFlow = false
            if (Mode.isOff(this)) {
                Mode.setMode(this, Mode.RELAXED)
                Toast.makeText(this, getString(R.string.perm_protection_on, modeDisplayName(Mode.RELAXED)), Toast.LENGTH_SHORT).show()
            }
        }
        val step = currentStep()
        // ⚠️ 2026-08-11 - DO NOT STOMP A SUB-PAGE. This is the "it closes and I have to tap
        // it again" bug.
        //
        // updateScreen runs on every onResume, which includes returning from a system
        // permission dialog or from Settings - i.e. exactly the moment a set-up page has
        // just got what it sent the user away for. The READY branch below rebuilds the
        // dashboard, and it fires whenever `shownStep` happens not to be READY yet: the
        // house offer (maybeAskAboutHouse) returns early without ever recording the step, so
        // granting location from the house page threw the page away and dropped the user
        // back on the dashboard, one tap from where they already were.
        //
        // Nothing is lost by staying put: `step == READY` means every mandatory permission
        // is in place, so there is no gate to show. If one has genuinely been revoked, step
        // is NOT READY and the gate below still takes the screen, sub-page or no sub-page -
        // which is the one case where interrupting the user is the correct thing to do.
        if (step == Step.READY && inSubPage) {
            shownStep = step
            return
        }
        if (step == Step.READY && shownStep == Step.READY) {
            // The nudge banner can become DUE while Overview is already built (resumed
            // from recents past the 30-min mark, say). Rebuild once so it appears.
            if (onHomeScreen && !homeBuiltWithNudge && shouldNudgePermissions()) {
                setupHomeScreen()
                return
            }
            renderStatus()   // already on the main screen - just refresh the dots
            return
        }
        shownStep = step
        // The gate is about to take the whole screen, so whatever sub-page was open is gone.
        // Saying so keeps the guard above honest: a stale `inSubPage` would otherwise make
        // the pass AFTER this one (the user has just granted the thing and come back) return
        // early and leave them looking at the gate they had already satisfied.
        inSubPage = false
        // "Not now" only exists while the flow is voluntary - above Off there's no way past.
        val voluntary = Mode.isOff(this)
        val notNow: (() -> Unit)? =
            if (voluntary) { { inPermissionFlow = false; pendingMode = null; clearGatePlan(); updateScreen() } } else null
        when (step) {
            Step.MONITORING -> showPrereq(
                stepTitle(Step.MONITORING, getString(R.string.step_monitoring_title)),
                getString(R.string.step_monitoring_body),
                getString(R.string.step_monitoring_button),
                { openAccessibilitySettings() },
                if (voluntary) getString(R.string.common_not_now) else null,
                notNow,
            )
            Step.OVERLAY -> showPrereq(
                stepTitle(Step.OVERLAY, getString(R.string.step_overlay_title)),
                getString(R.string.step_overlay_body),
                getString(R.string.step_overlay_button),
                { requestOverlayPermission() },
                if (voluntary) getString(R.string.common_not_now) else null,
                notNow,
            )
            Step.FIREFOX -> showPrereq(
                stepTitle(Step.FIREFOX, getString(R.string.step_firefox_title)),
                getString(R.string.step_firefox_body),
                getString(R.string.step_firefox_button),
                { openFirefoxInStore() },
                if (voluntary) getString(R.string.common_not_now) else null,
                notNow,
            )
            Step.EXTENSION -> {
                // We cannot see inside Firefox, so this step closes on the user's say-so -
                // which makes WHERE that button sits matter. Before they have been to the
                // page, opening it is the real action. Once they have been (and Firefox may
                // well have killed us in the meantime, hence the persisted flag), confirming
                // is, so the two swap places and "I've added it" becomes the primary button.
                val been = BrowserSetup.extensionPageVisited(this)
                val confirm = { BrowserSetup.setExtensionConfirmed(this, true); updateScreen() }
                showPrereq(
                    stepTitle(Step.EXTENSION, getString(R.string.step_extension_title)),
                    getString(R.string.step_extension_body, BrowserSetup.EXTENSION_URL),
                    if (been) getString(R.string.step_extension_done)
                    else getString(R.string.step_extension_button),
                    if (been) confirm else ({ openExtensionPage() }),
                    if (been) getString(R.string.step_extension_reopen)
                    else getString(R.string.step_extension_done),
                    if (been) ({ openExtensionPage() }) else confirm,
                    if (voluntary) getString(R.string.common_not_now) else null,
                    notNow,
                    // Before the swap the confirm is still the one that ends the flow, so it
                    // must not read as quietly as the "Not now" beside it.
                    emphasiseSecondary = !been,
                )
            }
            Step.LOCK -> showLockPrompt { markLockPromptSeen(); updateScreen() }
            // Through the gate: the next run gets a fresh plan.
            Step.READY -> { clearGatePlan(); setupHomeScreen() }
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
                cornerRadius = Radius.card * dp; setColor(Palette.warningSoft); setStroke((1.5f * dp).toInt(), Palette.warning)
            }
            val p = (18 * dp).toInt(); setPadding(p, (10 * dp).toInt(), p, p)
        }
        card.addView(TextView(this).apply {
            text = "\u2715"; textSize = 18f; setTextColor(Palette.warningText)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END }
            val t = (8 * dp).toInt(); setPadding(t, t, t, t)
            isClickable = true; isFocusable = true
            setOnClickListener { dialog.dismiss() }
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.unprotected_title); textSize = 17f
            setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.warningText)
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.unprotected_body)
            textSize = 13f; setTextColor(Palette.warningText); setPadding(0, (6 * dp).toInt(), 0, (12 * dp).toInt())
        })
        card.addView(bigChoice(getString(R.string.unprotected_enable), Palette.successText) {
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
                getString(R.string.lock_on_title),
                getString(R.string.lock_on_body),
                getString(R.string.common_continue),
                { onDone() },
            )
        } else {
            showPrereq(
                getString(R.string.lock_protect_title),
                getString(R.string.lock_protect_body),
                getString(R.string.lock_enable),
                {
                    UninstallGuard.setEnabled(this, true)
                    startActivity(UninstallGuard.activationIntent(this))
                    onDone()
                },
                getString(R.string.lock_skip),
                { onDone() },
            )
        }
    }

    /**
     * The house offer, shown once when Strict or Super hardcore is chosen and no home
     * point exists yet (see HomeRule.shouldAsk for the once-per-mode rule).
     *
     * An OFFER, not a gate. It is deliberately not part of SetupGuard: the setup steps
     * that ARE enforced (overlay, Firefox, the add-on) are the ones without which the mode
     * does not work at all, and Strict works perfectly well with no idea where you live.
     * Locking someone's phone until they hand over background location would also be the
     * single most suspicious thing this app could do, and it would be doing it for a
     * feature that is a refinement rather than the point.
     *
     * Returns true if the offer took the screen.
     */
    private fun maybeAskAboutHouse(mode: String): Boolean {
        if (!HomeRule.shouldAsk(this, mode)) return false
        HomeRule.markAsked(this, mode)
        val back = { showReportScreen() }
        showPrereq(
            getString(R.string.house_ask_title),
            getString(R.string.house_ask_body, modeDisplayName(mode)) + "\n\n" +
                getString(
                    if (mode == Mode.SUPERHARDCORE) R.string.house_ask_super
                    else R.string.house_ask_strict,
                ),
            getString(R.string.house_ask_set),
            { houseBack = back; showHouseArea() },
            getString(R.string.house_ask_later),
            back,
        )
        return true
    }

    private fun showPrereq(
        title: String,
        body: String,
        buttonText: String,
        onContinue: () -> Unit,
        secondaryText: String? = null,
        onSecondary: (() -> Unit)? = null,
        tertiaryText: String? = null,
        onTertiary: (() -> Unit)? = null,
        emphasiseSecondary: Boolean = false,
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
                setTypeface(typeface, if (emphasiseSecondary) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (emphasiseSecondary) Palette.successText else currentTextColor)
                setOnClickListener { onSecondary?.invoke() }
            }
        }
        findViewById<Button>(R.id.prereq_tertiary).apply {
            if (tertiaryText == null) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = tertiaryText
                setOnClickListener { onTertiary?.invoke() }
            }
        }
    }

    /** Play Store page for Firefox, falling back to the web store on devices with no Play app. */
    private fun openFirefoxInStore() {
        val store = Intent(Intent.ACTION_VIEW, Uri.parse(BrowserSetup.STORE_URI))
        try {
            startActivity(store)
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BrowserSetup.STORE_URL)))
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.step_firefox_nostore), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * The add-on page, opened IN Firefox on purpose - "Add to Firefox" only does anything
     * there. If Firefox has gone missing since the last step, fall back to whatever can open
     * a link rather than dropping the user on a dead button.
     */
    private fun openExtensionPage() {
        BrowserSetup.markExtensionPageVisited(this)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BrowserSetup.EXTENSION_URL))
        BrowserSetup.firefoxPackage(this)?.let { intent.setPackage(it) }
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BrowserSetup.EXTENSION_URL)))
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.step_extension_nobrowser), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupMainScreen() {
        onReportScreen = false; onHomeScreen = false; onTemptationsTab = false
        onDevScreen = true
        inTemptationFlow = false; inLoosenFlow = false
        inAppSiteFlow = false; inSubPage = false
        stopRideTimer(); stopLoosenTimer()
        entriesJob?.cancel()

        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        content.addView(titleText("Developer tools"))
        content.addView(TextView(this).apply {
            text = "Diagnostics and block-rule management. Not shown to end users when dev mode is off."
            textSize = 13f; setTextColor(Palette.labelTertiary); setPadding(0, 0, 0, (10 * dp).toInt())
        })
        // The log is the thing you actually come here for while tuning, so it sits first.
        content.addView(homeCard("View log", "The full monitoring log.") { showLogPage() })
        // ...and the word filter second, because the log is mostly read to answer "why did
        // THAT get blocked", which is a question this page exists to answer properly.
        content.addView(homeCard("Word filter",
            "Every word group, what it scores, the cutoff, and the exact screens we watch " +
                "for - all for the mode you are in right now.") { showFilterBreakdown() })
        content.addView(homeCard("System console", "Current mode, thresholds, and what's on or off.") { showDevConsole() })
        // Was a pane on the adult-content page; it is diagnostics, not something to hand a
        // user in a bad moment (see the note in showReportScreen).
        content.addView(homeCard(getString(R.string.stats_title),
            "Progress, context, temptations, relapses and unlocks - the charts behind the log.") { showStatsMenu() })
        // Premium override: pretend this install has paid. Gates nothing yet - see Premium.
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = "Premium mode"; textSize = 15f
                    setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Act as if this install has paid. Nothing is gated on it yet - it is here so " +
                        "the premium features can be built behind a real flag."
                    textSize = 12f; setTextColor(Palette.labelTertiary)
                })
            })
            addView(android.widget.Switch(this@MainActivity).apply {
                isChecked = Premium.isOn(this@MainActivity)
                setOnCheckedChangeListener { _, on -> Premium.setOn(this@MainActivity, on) }
            })
        })
        content.addView(homeCard(getString(R.string.settings_language), getString(R.string.settings_language_subtitle)) { showLanguagePicker() })
        content.addView(homeCard(getString(R.string.settings_currency), getString(R.string.settings_currency_subtitle)) { showCurrencyPicker() })
        content.addView(homeCard("Sensor debug", "Live tilt / lying-down and ambient light readings.") { showSensorDebug() })
        content.addView(homeCard("Home area (location)",
            if (HomeArea.isSet(this)) "Home is saved. Live distance, accuracy and at-home / away verdict."
            else "Not set up. Stand in the house and save it, then watch the live verdict.") {
            houseBack = { setupMainScreen() }; showHouseArea()
        })
        content.addView(homeCard("Grayscale setup", "Turn on the strict-mode grayscale filter.") { showGreyscaleSetup() })
        content.addView(homeCard("Preview uninstall prompt", "See the lock prompt (it's hidden in dev mode).") { showLockPrompt { setupMainScreen() } })
        content.addView(homeCard("Recent blocks", "What's been blocked lately.") { showRecentBlocks() })
        content.addView(homeCard("Manage block rules", "Add or remove blocked sites and apps.") { showManageRules() })
        content.addView(homeCard("Whitelisted apps", "The always-allowed apps - block one with a tap.") { showBlockApps() })
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
        content.addView(homeCard("Clear block rules",
            "Wipe all block rules, strikes and what we know about each app.") {
            BlockRules.clear(this); BlockEscalation.clear(this); AppTimedBlock.clear(this)
            // Everything an app's standing is built from: how long we have seen it, and how
            // often we have had to close it. Leaving it behind would keep apps on the short
            // ladder after a wipe that says it wiped everything.
            AppTrust.clear(this); RepeatGate.clearAll()
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
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText("Grayscale in strict mode"))
        root.addView(TextView(this).apply {
            text = "Colour is a big part of what makes feeds and images pull at you. Making the whole " +
                "screen grayscale strips that out - simple, and surprisingly powerful.\n\n" +
                "Android won't let an app switch grayscale on for you (it's a protected system " +
                "setting), so you turn it on once yourself. In strict mode, keep it on."
            textSize = 14f; setTextColor(Palette.labelSecondary); setPadding(0, 0, 0, (14 * dp).toInt())
        })
        val status = TextView(this).apply {
            textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, (14 * dp).toInt())
        }
        root.addView(status)
        val on = Greyscale.isOn(this)
        status.text = if (on) "Grayscale is ON \u2713" else "Grayscale is OFF"
        status.setTextColor(if (on) Palette.successText else Palette.dangerText)

        root.addView(bigChoice("Open display settings", Palette.tint) { Greyscale.openGrayscaleSetting(this) })
        root.addView(TextView(this).apply {
            text = "How to turn it on:\n" +
                "1. Open Settings \u2192 Accessibility.\n" +
                "2. Go to Vision enhancements (called Colour and motion on some phones).\n" +
                "3. Tap Colour correction and toggle the slider On.\n" +
                "4. Scroll to the bottom and choose Greyscale."
            textSize = 13f; setTextColor(Palette.labelTertiary); setPadding(0, (12 * dp).toInt(), 0, (16 * dp).toInt())
        })

        // Optional lock: block the Colour-correction page so greyscale can't be turned off.
        val lockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Radius.card * dp; setColor(Palette.surface); setStroke((1.5f * dp).toInt(), Palette.hairline)
            }
            val p = (14 * dp).toInt(); setPadding(p, p, p, p)
        }
        lockRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = "Lock the Colour correction page"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Once greyscale is on, block that Settings page so it can't be turned back off. Turn this off here first if you need to change it."
                textSize = 12f; setTextColor(Palette.labelTertiary); setPadding(0, (2 * dp).toInt(), 0, 0)
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
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText("Sensor debug"))
        root.addView(TextView(this).apply {
            text = "Live readings. Tilt/lying-down come from the accelerometer; light from the ambient light sensor."
            textSize = 13f; setTextColor(Palette.labelTertiary); setPadding(0, 0, 0, (12 * dp).toInt())
        })

        fun bigLine() = TextView(this).apply { textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label); setPadding(0, (6 * dp).toInt(), 0, 0) }
        fun subLine() = TextView(this).apply { textSize = 14f; setTextColor(Palette.labelSecondary) }

        fun badge() = TextView(this).apply {
            textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setTextColor(Palette.onFill)
            val p = (8 * dp).toInt(); setPadding(p * 2, p, p * 2, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (5 * dp).toInt(); bottomMargin = (5 * dp).toInt() }
        }
        root.addView(sectionTitle("Posture"))
        val lyingBadge = TextView(this).apply {
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setTextColor(Palette.onFill)
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

        val note = TextView(this).apply { textSize = 12f; setTextColor(Palette.labelTertiary); setPadding(0, (16 * dp).toInt(), 0, 0) }
        root.addView(note)

        root.addView(sectionTitle("Greyscale"))
        val greyLine = subLine(); val greyHint = TextView(this).apply { textSize = 12f; setTextColor(Palette.labelTertiary); setPadding(0, (2 * dp).toInt(), 0, 0) }
        root.addView(greyLine); root.addView(greyHint)

        sensorMonitor?.stop()
        val monitor = SensorMonitor(this)
        sensorMonitor = monitor

        fun refresh() {
            val lying = monitor.lyingDown
            lyingBadge.text = if (lying) "  Lying down  " else "  Upright  "
            lyingBadge.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Radius.card * dp; setColor(if (lying) Palette.success else Palette.labelQuaternary)
            }
            fun paint(tv: TextView, on: Boolean, label: String) {
                tv.text = if (on) "  $label  \u2713  " else "  $label  "
                tv.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Radius.card * dp; setColor(if (on) Palette.success else Palette.divider)
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

    // ── Home area (GPS) ──────────────────────────────────────────────────────
    // "Is the phone at the house, or out?" - the coarse counterpart to the room
    // beacons below. Live readout plus the one manual set-up step: stand in the
    // house and press the button. See HomeArea.kt for the rule (and for why a
    // VPN cannot move any of this), and HomeRule for what it now DOES: in Super
    // hardcore, at the house, one word detection closes an app outright.
    //
    // Reachable two ways since it started enforcing something: the house row on
    // the dashboard (sensorsConsole) and Developer tools. The page is written for
    // the first of those - a user setting their house up - with the diagnostics
    // kept underneath, because when this goes wrong it goes wrong quietly and the
    // numbers are the only way to see it.
    /** This app's page in system Settings - the only route to "Allow all the time" on 11+. */
    private fun openAppSettings() {
        startActivity(Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ))
    }

    /**
     * [focusSave] - the user has just come back from granting a permission, so the page is
     * re-entered on the step they are now ON rather than at the top of the one they have
     * finished. Without it, granting location dropped them back at a page header and left
     * them to work out for themselves that the next thing to do was several screens down.
     */
    private fun showHouseArea(focusSave: Boolean = false) {
        inSubPage = true
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        content.addView(titleText(getString(R.string.house_title)))

        fun subLine() = TextView(this).apply { textSize = 14f; setTextColor(Palette.labelSecondary) }
        fun tinyLine() = TextView(this).apply { textSize = 12f; setTextColor(Palette.labelTertiary) }

        // ── Permissions live on this page (there is no other route to them) ──
        // TWO grants, asked for in order: "while using" first, then "all the time" on its
        // own. Asking for both together makes Android 11+ grant neither. See HomeArea.
        content.addView(sectionTitle(getString(R.string.house_sec_permissions)))
        val permLine = subLine()
        content.addView(permLine)
        val grantBtn = bigChoice(getString(R.string.house_grant_1), Palette.tint) {
            requestPermissions(HomeArea.requiredPermissions(), REQ_HOME_LOCATION)
        }
        val bgBtn = bigChoice(getString(R.string.house_grant_2), Palette.tint) {
            // Android 10 can still do this through the dialog. From 11 the dialog has no
            // "all the time" option at all, so the settings page IS the flow - dropping
            // the user there with instructions beats a button that silently does nothing.
            if (HomeArea.backgroundRequestable()) {
                requestPermissions(HomeArea.backgroundPermission(), REQ_HOME_BACKGROUND)
            } else {
                Toast.makeText(this, getString(R.string.house_grant_settings_hint), Toast.LENGTH_LONG).show()
                openAppSettings()
            }
        }
        val appSettingsBtn = bigChoice(getString(R.string.house_open_settings), Palette.labelSecondary) { openAppSettings() }
        content.addView(grantBtn); content.addView(bgBtn); content.addView(appSettingsBtn)

        val locWarn = TextView(this).apply {
            text = getString(R.string.house_location_off)
            textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.dangerText); setPadding(0, (8 * dp).toInt(), 0, 0)
            visibility = View.GONE; isClickable = true; isFocusable = true
            setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
        content.addView(locWarn)

        val pill = TextView(this).apply {
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setTextColor(Palette.onFill)
            val p = (10 * dp).toInt(); setPadding(p * 2, p, p * 2, p)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * dp).toInt(); bottomMargin = (10 * dp).toInt()
            }
        }
        val mockLine = TextView(this).apply {
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.dangerText); visibility = View.GONE
        }

        locationMonitor?.stop()
        val monitor = LocationMonitor(this)
        locationMonitor = monitor
        homeAreaUi?.removeCallbacksAndMessages(null)
        val ui = Handler(Looper.getMainLooper()); homeAreaUi = ui

        val stamp = java.text.SimpleDateFormat("d MMM", java.util.Locale.UK)

        // ── The houses ───────────────────────────────────────────────────────────────
        // There can be more than one (HomeArea.MAX_HOUSES) and there is no editing one:
        // you add where you are now, and you ask for one to be removed. In Strict and above
        // that request then waits, silently - see HouseLock.
        //
        // The cards are built ONCE per page entry - everything that changes them re-enters
        // the page - and refresh() only writes the live distance inside them.
        val houses = HomeArea.houses(this)
        val housesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val houseDistLines = mutableListOf<Pair<HomeArea.House, TextView>>()
        val saveReady = tinyLine()
        val modeName = modeDisplayName(Mode.current(this))

        fun reloadPage() { ui.removeCallbacksAndMessages(null); showHouseArea() }

        /** The fix we would save right now, or null having said why not. */
        fun usableFix(): Location? {
            val loc = monitor.last
            return when {
                loc == null -> {
                    Toast.makeText(this, getString(R.string.house_no_fix), Toast.LENGTH_LONG).show(); null
                }
                HomeArea.ageMs(loc) > HomeArea.MAX_FIX_AGE_MS -> {
                    Toast.makeText(this, getString(R.string.house_stale_fix), Toast.LENGTH_LONG).show(); null
                }
                else -> loc
            }
        }

        // The watch's gate opens on a house existing, so every save hands it the fix that
        // just became one - otherwise the dashboard says "not set up" for up to a minute
        // after the user has plainly set it up.
        fun addHere(loc: Location) {
            val added = HomeArea.addHouse(this, loc)
            if (added == null) {
                Toast.makeText(this, getString(R.string.house_add_full, HomeArea.MAX_HOUSES), Toast.LENGTH_LONG).show()
                return
            }
            val n = HomeArea.houses(this).indexOfFirst { it.id == added.id } + 1
            Toast.makeText(this, getString(R.string.house_added_toast, n,
                Math.round(HomeArea.usableAccuracy(loc)), loc.provider ?: "?"), Toast.LENGTH_LONG).show()
            HomeAreaWatch.offer(this, loc)
            reloadPage()
        }

        /**
         * "Remove this house."
         *
         * Under the lock this only ever RECORDS THE REQUEST. The toast says "Noted." and
         * nothing else: no date, no countdown - a deadline to hold out for is precisely
         * what a month's wait is meant to deny (see HouseLock).
         */
        fun onRemove(house: HomeArea.House, n: Int) {
            when {
                // Already asked for: the only thing left to offer is calling it off.
                house.deleteRequestedAt > 0L -> AlertDialog.Builder(this)
                    .setTitle(getString(R.string.house_n, n))
                    .setMessage(getString(R.string.house_remove_pending))
                    .setPositiveButton(getString(R.string.house_remove_cancel)) { _, _ ->
                        HomeArea.cancelDelete(this, house.id)
                        Toast.makeText(this, getString(R.string.house_remove_cancelled_toast), Toast.LENGTH_SHORT).show()
                        reloadPage()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                HouseLock.deleteNeedsWait(this) -> AlertDialog.Builder(this)
                    .setTitle(getString(R.string.house_remove_locked_title, n))
                    .setMessage(getString(R.string.house_remove_locked_body, modeName))
                    .setPositiveButton(getString(R.string.house_remove_locked_yes)) { _, _ ->
                        HomeArea.requestDelete(this, house.id)
                        Toast.makeText(this, getString(R.string.house_remove_asked_toast), Toast.LENGTH_SHORT).show()
                        reloadPage()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                else -> AlertDialog.Builder(this)
                    .setTitle(getString(R.string.house_remove_now_title, n))
                    .setMessage(getString(R.string.house_remove_now_body))
                    .setPositiveButton(getString(R.string.house_remove)) { _, _ ->
                        HomeArea.removeHouse(this, house.id)
                        Toast.makeText(this, getString(R.string.house_removed_toast), Toast.LENGTH_SHORT).show()
                        reloadPage()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        for ((i, house) in houses.withIndex()) {
            val n = i + 1
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = Radius.card * dp
                    setStroke((1 * dp).toInt(), Palette.hairline)
                }
                val q = (12 * dp).toInt(); setPadding(q, q, q, q)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (8 * dp).toInt() }
            }
            // Numbered only when there is more than one - "House 1" on its own reads like a
            // filing system for something the user thinks of simply as home.
            if (houses.size > 1) card.addView(TextView(this).apply {
                text = getString(R.string.house_n, n)
                textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
            })
            val distLine = subLine()
            houseDistLines.add(house to distLine)
            card.addView(distLine)
            card.addView(tinyLine().apply {
                text = String.format(java.util.Locale.UK, "%.5f, %.5f  ·  %s",
                    house.lat, house.lon, stamp.format(java.util.Date(house.setAt))) +
                    (if (house.accuracy >= 0f) "  ·  ±${Math.round(house.accuracy)} m" else "")
            })
            if (house.accuracy > HomeArea.RADIUS_M) card.addView(tinyLine().apply {
                text = getString(R.string.house_vague_fix)
                setTextColor(Palette.warningText)
            })
            if (house.deleteRequestedAt > 0L) card.addView(tinyLine().apply {
                text = getString(R.string.house_remove_pending)
                setTextColor(Palette.warningText)
            })
            card.addView(TextView(this).apply {
                text = getString(R.string.house_remove)
                textSize = 14f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Palette.dangerText); gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = Radius.control * dp; setStroke((1.5f * dp).toInt(), Palette.dangerText)
                }
                val h = (10 * dp).toInt(); setPadding(h, (8 * dp).toInt(), h, (8 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (10 * dp).toInt() }
                isClickable = true; isFocusable = true
                setOnClickListener { onRemove(house, n) }
            })
            housesBox.addView(card)
        }
        if (houses.isEmpty()) housesBox.addView(subLine().apply { text = getString(R.string.house_notset) })

        val saveBtn = bigChoice(getString(R.string.house_save), Palette.tint) {
            usableFix()?.let { addHere(it) }
        }
        val addBtn = bigChoice(getString(R.string.house_add), Palette.tint) {
            val loc = usableFix() ?: return@bigChoice
            if (!HouseLock.canAdd(this))
                Toast.makeText(this, getString(R.string.house_add_full, HomeArea.MAX_HOUSES), Toast.LENGTH_LONG).show()
            else addHere(loc)
        }

        content.addView(sectionTitle(getString(R.string.house_sec_point)))
        content.addView(pill)
        content.addView(housesBox)
        content.addView(saveBtn); content.addView(addBtn); content.addView(saveReady)
        content.addView(mockLine)

        fun refresh() {
            val granted = HomeArea.hasPermissions(this)
            val access = HomeArea.access(this)
            permLine.text = when (access) {
                HomeArea.Access.ALWAYS -> getString(R.string.house_perm_always)
                HomeArea.Access.WHILE_USING -> getString(R.string.house_perm_foreground)
                HomeArea.Access.NONE -> getString(R.string.house_perm_none)
            }
            permLine.setTextColor(when (access) {
                HomeArea.Access.ALWAYS -> Palette.successText
                HomeArea.Access.WHILE_USING -> Palette.warningText
                HomeArea.Access.NONE -> Palette.dangerText
            })
            grantBtn.visibility = if (granted) View.GONE else View.VISIBLE
            bgBtn.visibility = if (access == HomeArea.Access.WHILE_USING) View.VISIBLE else View.GONE
            appSettingsBtn.visibility = if (access == HomeArea.Access.ALWAYS) View.GONE else View.VISIBLE
            locWarn.visibility = if (granted && !HomeArea.locationEnabled(this)) View.VISIBLE else View.GONE

            val loc = monitor.last
            val (pillText, colour) = when (HomeArea.verdict(this, loc)) {
                HomeArea.Verdict.HOME -> getString(R.string.house_pill_home) to Palette.success
                HomeArea.Verdict.AWAY -> getString(R.string.house_pill_away) to Palette.tint
                HomeArea.Verdict.MAYBE -> getString(R.string.house_pill_maybe) to Palette.warning
                HomeArea.Verdict.UNKNOWN -> getString(R.string.house_pill_unknown) to Palette.labelQuaternary
            }
            pill.text = pillText
            pill.background = GradientDrawable().apply { cornerRadius = Radius.card * dp; setColor(colour) }
            pill.visibility = if (houses.isEmpty()) View.GONE else View.VISIBLE

            mockLine.visibility = if (loc != null && HomeArea.isMock(loc)) View.VISIBLE else View.GONE
            mockLine.text = getString(R.string.house_mock)

            // Each card's live distance - the only part of the list that moves while the
            // page is open (everything else re-enters the page).
            for ((house, line) in houseDistLines) {
                line.text = if (loc == null) getString(R.string.house_dist_unknown)
                    else getString(R.string.house_dist_now, Math.round(HomeArea.distanceTo(house, loc)))
            }
            saveBtn.visibility = if (granted && houses.isEmpty()) View.VISIBLE else View.GONE
            addBtn.visibility = if (granted && houses.isNotEmpty()) View.VISIBLE else View.GONE
            // Say whether pressing it will work BEFORE it is pressed, rather than with a
            // toast telling you to go and stand near a window once you already have.
            saveReady.visibility = if (granted) View.VISIBLE else View.GONE
            val usable = loc != null && HomeArea.ageMs(loc) <= HomeArea.MAX_FIX_AGE_MS
            saveReady.text = when {
                !granted -> ""
                usable -> getString(R.string.house_save_ready, Math.round(HomeArea.usableAccuracy(loc)))
                else -> getString(R.string.house_save_waiting)
            }
            saveReady.setTextColor(if (usable) Palette.successText else Palette.labelTertiary)
        }

        // The fix's AGE keeps changing when nothing else does, so tick regardless of updates.
        val tick = object : Runnable {
            override fun run() { refresh(); ui.postDelayed(this, 1_000L) }
        }
        monitor.onUpdate = {
            runOnUiThread {
                // This page runs GPS the whole time it is open, so it holds a far better fix
                // than the background watch's cheap subscription ever sees. Pass it on, or
                // the dashboard row behind this page goes on disagreeing with the page.
                HomeAreaWatch.offer(this, monitor.last)
                refresh()
            }
        }
        if (HomeArea.hasPermissions(this)) monitor.start()
        refresh(); ui.postDelayed(tick, 1_000L)

        val root = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true; addView(content)
        }
        // Came back from a permission dialog: land on the step that is now current instead
        // of at the top of the one just finished. One-shot - it must not fight the user's
        // own scrolling on every layout pass.
        if (focusSave && HomeArea.hasPermissions(this)) {
            root.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        // Whichever of the two is on screen: the first-house save, or -
                        // for someone who already has one - the add button.
                        val focus = if (saveBtn.visibility == View.VISIBLE) saveBtn else addBtn
                        root.smoothScrollTo(0, (focus.top - pad).coerceAtLeast(0))
                        focus.animate().scaleX(1.04f).scaleY(1.04f).setDuration(220)
                            .withEndAction {
                                focus.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
                            }.start()
                    }
                })
        }
        setContentWithThumb(root) {
            ui.removeCallbacksAndMessages(null); homeAreaUi = null
            monitor.stop(); locationMonitor = null
            houseBack()
        }
    }

    // ── Room detection (KKM K11 beacons) ─────────────────────────────────────
    // One card per room (rooms are user-defined, 2-8 beacons): a true / maybe (probs
    // is) / maybe (probs not) / false pill, a big live dBm readout, one red-amber-
    // green meter PER BEACON (zones learned per room), and the check bars. The
    // decision is PAIRWISE: the current tuple of all beacons' levels is matched
    // against recorded spots - see RoomBeacons.kt.
    // "Select the room the sensor(s) will be in, or type your own" - the same picker
    // (and the same custom-options store) as the relapse flow's Where-were-you page.
    private fun showAddRoomChooser() {
        inSubPage = true
        subBack = { if (RoomBeacons.rooms(this).isEmpty()) setupHomeScreen() else showRoomBeaconDebug() }
        val existing = RoomBeacons.rooms(this)
        val base = Opts.LOCATIONS.filter { it != "Out / in public" && it.lowercase() !in existing }
        pickWithCustomScreen(
            "Which room are you setting up?", base, "location", onBack = null,
            subtitle = "You're setting up ONE room for now - its sensor(s) go in this room. " +
                "You can add more rooms later from the sensors page.",
        ) { name ->
            if (!RoomBeacons.addRoom(this, name)) Toast.makeText(this,
                "Couldn't add that (duplicate, or ${RoomBeacons.MAX_ROOMS}-room limit)", Toast.LENGTH_LONG).show()
            showRoomBeaconDebug()
        }
    }

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
                "Back", { setupHomeScreen() },
            )
            return
        }

        // No rooms yet: name the first one before anything else.
        if (RoomBeacons.rooms(this).isEmpty()) { showAddRoomChooser(); return }

        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun warnLine(msg: String, onClick: () -> Unit) = TextView(this).apply {
            text = msg; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.dangerText); setPadding(0, 0, 0, (8 * dp).toInt())
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
                setTextColor(Palette.onFill)
                val p = (8 * dp).toInt(); setPadding(p * 2, p / 2, p * 2, p / 2)
            }
            val big = TextView(this@MainActivity).apply {
                textSize = 34f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(Palette.label)
            }
            val note = TextView(this@MainActivity).apply {
                text = "⚠ maybe, probs am - treating as true"
                textSize = 12f; setTextColor(Palette.warningText); visibility = View.GONE
            }
            val sub = TextView(this@MainActivity).apply { textSize = 12f; setTextColor(Palette.labelTertiary) }
            val metersBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            val meterRows = mutableListOf<Pair<TextView, SignalMeterView>>()
            val checksBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            val checkRows = mutableListOf<Triple<LinearLayout, TextView, TextView>>()
            val summary = TextView(this@MainActivity).apply { textSize = 13f; setTextColor(Palette.labelSecondary); setPadding(0, (6 * dp).toInt(), 0, 0) }
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
                    2 -> Palette.successText; 1 -> Palette.successText
                    -1 -> Palette.danger; else -> Palette.warningText
                })
                meter.update(m.current, m.zone, m.openTop)
            }
        }

        fun renderChecks(card: RoomCard, checks: List<RoomPresence.Check>) {
            if (card.checkRows.size != checks.size) {
                card.checksBox.removeAllViews(); card.checkRows.clear()
                repeat(checks.size) {
                    val label = TextView(this).apply {
                        textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.onFill)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val value = TextView(this).apply { textSize = 11f; setTextColor(Palette.onFill) }
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
                    cornerRadius = Radius.chip * dp
                    setColor(when (c.state) { 1 -> Palette.success; -1 -> Palette.danger; else -> Palette.warning })
                }
            }
        }

        for (card in cards) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = Radius.card * dp; setColor(Palette.surface); setStroke((1.5f * dp).toInt(), Palette.hairline)
                }
                val p = (14 * dp).toInt(); setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (10 * dp).toInt() }
            }
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            header.addView(TextView(this).apply {
                text = roomTitle(card.room); textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(card.pill)
            header.addView(TextView(this).apply {
                text = "✕"; textSize = 18f; setTextColor(Palette.labelTertiary)
                val p = (8 * dp).toInt(); setPadding(p, (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
                isClickable = true; isFocusable = true
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Remove ${roomTitle(card.room)}?")
                        .setMessage("Deletes this room and its calibration from the list. The sensor hardware itself isn't touched.")
                        .setPositiveButton("Remove") { _, _ ->
                            RoomBeacons.removeRoom(this@MainActivity, card.room)
                            RoomPresence.reset()
                            showRoomBeaconDebug()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            })
            box.addView(header)
            box.addView(card.note)
            box.addView(card.big); box.addView(card.sub)
            box.addView(card.metersBox)
            box.addView(card.checksBox); box.addView(card.summary)

            // How many sensors this room should have (1-4). Once a room is calibrated,
            // any count change means recalibrating - so it's confirmed, and confirming
            // wipes the calibration so the card honestly says "Set up this room".
            val count = RoomBeacons.sensorCount(this, card.room)
            fun changeCount(newCount: Int) {
                fun apply() {
                    RoomBeacons.setSensorCount(this@MainActivity, card.room, newCount)
                    if (RoomBeacons.isCalibrated(this@MainActivity, card.room)) {
                        RoomBeacons.setSamples(this@MainActivity, card.room, emptyList())
                    }
                    RoomPresence.reset()
                    if (newCount > count) Toast.makeText(this@MainActivity,
                        "Run set-up to assign sensor ${RoomBeacons.sensorLetter(newCount - 1)}", Toast.LENGTH_SHORT).show()
                    showRoomBeaconDebug()
                }
                if (RoomBeacons.isCalibrated(this@MainActivity, card.room)) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Change sensor count?")
                        .setMessage("This room is already set up. Changing the number of sensors means you'll have to RECALIBRATE it (run set-up again).")
                        .setPositiveButton("Change - I'll recalibrate") { _, _ -> apply() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else apply()
            }
            if (count == 1) {
                // The upgrade path, made unmissable.
                box.addView(TextView(this).apply {
                    text = "＋  Add a second sensor (recommended - much more accurate)"
                    textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.tint)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = Radius.control * dp; setStroke((1.5f * dp).toInt(), Palette.tint)
                    }
                    val p = (10 * dp).toInt(); setPadding(p, p, p, p)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (8 * dp).toInt() }
                    isClickable = true; isFocusable = true
                    setOnClickListener { changeCount(2) }
                })
            } else {
                box.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                    addView(TextView(this@MainActivity).apply {
                        text = "Sensors in this room"; textSize = 13f; setTextColor(Palette.labelSecondary)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    fun stepBtn(label: String, onClick: () -> Unit) = addView(TextView(this@MainActivity).apply {
                        text = label; textSize = 20f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                        setTextColor(Palette.tint)
                        background = GradientDrawable().apply {
                            cornerRadius = Radius.chip * dp; setStroke((1.5f * dp).toInt(), Palette.tint)
                        }
                        layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
                        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
                    })
                    stepBtn("−") { changeCount(count - 1) }
                    addView(TextView(this@MainActivity).apply {
                        text = "$count"; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Palette.label); gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams((34 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                    stepBtn("+") { if (count < RoomBeacons.MAX_SENSORS) changeCount(count + 1) }
                })
            }

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

        // Rooms are user-defined: 1 to 8, each with 1-4 sensors.
        content.addView(Button(this).apply {
            text = "Add a room…"; setAllCaps(false)
            setOnClickListener { showAddRoomChooser() }
        })

        // Debug: enforce the room guard regardless of mode, so blocking can be tested
        // without flipping the whole phone into strict.
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
            addView(TextView(this@MainActivity).apply {
                text = "Block non-whitelisted apps while in any of these rooms (debug - any mode)"
                textSize = 13f; setTextColor(Palette.labelSecondary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(android.widget.Switch(this@MainActivity).apply {
                isChecked = RoomBeacons.debugGuard(this@MainActivity)
                setOnCheckedChangeListener { _, on -> RoomBeacons.setDebugGuard(this@MainActivity, on) }
            })
        })

        // Raw feed of every advertiser - developer diagnostics only, hidden until asked.
        val feed = TextView(this).apply {
            textSize = 11f; setTextColor(Palette.labelSecondary); typeface = Typeface.MONOSPACE
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

        content.addView(Button(this).apply {
            text = "How we determine what room you're in ›"; setAllCaps(false)
            setOnClickListener { showRoomHowItWorks() }
        })

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

            val statuses = RoomPresence.evaluate(this, scanner, press)
            for (card in cards) {
                val st = statuses[card.room] ?: continue
                // Only IN and the upper (treated-as-true) slice of maybe show as true;
                // plain maybe-probs-am is back to amber and never blocks.
                card.pill.text = when (st.verdict) {
                    RoomPresence.Verdict.IN, RoomPresence.Verdict.MAYBE_IN_TRUE -> "  true  "
                    RoomPresence.Verdict.MAYBE_IN -> "  maybe (probs is)  "
                    RoomPresence.Verdict.MAYBE_OUT -> "  maybe (probs not)  "
                    RoomPresence.Verdict.OUT -> "  false  "
                }
                card.pill.background = GradientDrawable().apply {
                    cornerRadius = Radius.card * dp
                    setColor(when (st.verdict) {
                        RoomPresence.Verdict.IN, RoomPresence.Verdict.MAYBE_IN_TRUE -> Palette.success
                        RoomPresence.Verdict.MAYBE_IN -> Palette.warning
                        RoomPresence.Verdict.MAYBE_OUT -> Palette.warningText
                        RoomPresence.Verdict.OUT -> Palette.labelTertiary
                    })
                }
                card.note.visibility =
                    if (st.verdict == RoomPresence.Verdict.MAYBE_IN_TRUE) View.VISIBLE else View.GONE
                card.big.text = st.rssi?.let { "$it dBm" } ?: "-- dBm"
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
            setupHomeScreen()
        }
    }

    // Plain-language explanation page. Deliberately basic - simple bullets, no styling.
    private fun showRoomHowItWorks() {
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText("How we determine what room you're in"))
        fun section(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
            setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
        })
        fun bullets(t: String) = root.addView(TextView(this).apply {
            text = t; textSize = 14f; setTextColor(Palette.labelSecondary)
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
            "- Put each sensor closest to where the risk actually is. In a bedroom: at the bed - the headboard or bedside table. This is the most important rule.\n" +
                "- In a bathroom: the back of a cupboard, or a shelf near where the phone would get used.\n" +
                "- Sensors sharing a room go at opposite ends, as low to the floor as possible.\n" +
                "- Sit each sensor on a piece of aluminium foil about 8cm × 8cm (just enough to cover its base) - it steadies the signal.\n" +
                "- Anywhere works as long as it never moves. If you move a sensor, redo the set-up.",
        )
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFillViewport = true; addView(root)
        }
        setContentWithThumb(scroll) { showRoomBeaconDebug() }
    }

    /** One place the set-up wizard asks the user to be. core = defines super green + true. */
    private data class CalSpot(val title: String, val instruction: String, val core: Boolean = false)

    // The static inside spots. One "right next to sensor X" spot per assigned sensor
    // is prepended dynamically in the wizard, so every sensor gets its close-up.
    private val staticInsideSpots = listOf(
        CalSpot("Middle of the room", "Stand in the middle of the room."),
        CalSpot("Far corner", "Go to the far corner or edge of the room - as far from the sensors as it gets."),
        CalSpot("Opposite far corner", "Now a different far corner or edge of the room."),
    )

    // The most important readings of all: where the phone would ACTUALLY get used.
    // These are the spots that can produce a 'true'.
    private val temptationSpots = listOf(
        CalSpot("Where you'd actually use the phone", "Sit or lie EXACTLY where you'd really use the phone in this room - on the bed, in the chair. Get comfortable and hold the phone the way you really would.", core = true),
        CalSpot("Same place, held differently", "Stay there, but change it up: other hand, resting on your lap, lying back - a realistic variation of the same spot.", core = true),
    )

    // ── Room set-up wizard ────────────────────────────────────────────────────
    // One instruction per screen, big text, with the room named LARGE on every page.
    // Flow: find each of the room's sensors (A, B… - however many were chosen on the
    // room card; already-assigned slots are skipped), place all sensors, the spots
    // (one per sensor + statics + temptation), a 15 s walk around the room, then a
    // free-roam pass around the house tagging false readings. Every recording captures
    // ALL sensors at once - the values come as a set, and that set is what's matched.
    private fun showRoomSetup(room: String) {
        val roomName = room.replaceFirstChar { it.uppercase() }
        val roomsNow = RoomBeacons.rooms(this)
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val scanner = beaconScanner ?: BeaconScanner(this).also { beaconScanner = it }
        val press = pressureMon ?: PressureMonitor(this).also { pressureMon = it }
        press.start()
        beaconUi?.removeCallbacksAndMessages(null)
        val ui = Handler(Looper.getMainLooper()); beaconUi = ui
        val collected = mutableListOf<RoomBeacons.Sample>()

        fun bigBody(t: String) = TextView(this).apply {
            text = t; textSize = 17f; setTextColor(Palette.labelSecondary)
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }
        fun bigCountdown() = TextView(this).apply {
            textSize = 64f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setTextColor(Palette.successText)
        }
        fun liveLine() = TextView(this).apply {
            textSize = 16f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }
        // Renders one wizard screen and kills the previous screen's tickers.
        fun show(step: String, title: String, vararg views: View) {
            ui.removeCallbacksAndMessages(null)
            val root = vbox(pad)
            // The room, unmissable, on every wizard page.
            root.addView(TextView(this).apply {
                text = roomName.uppercase(); textSize = 22f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Palette.tint); letterSpacing = 0.08f
            })
            root.addView(stepText(step.uppercase()))
            root.addView(TextView(this).apply {
                text = title; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
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
        // Every assigned sensor in the house, strongest first - so standing next to
        // "bedroom A" you SEE bedroom A on top, bedroom B under it, and so on.
        fun liveTick(live: TextView) = tick(300) {
            scanner.ensureScanning(); press.start()
            val rows = RoomBeacons.assignedSensors(this, room).map { (r, slot, m) ->
                Pair("$r ${RoomBeacons.sensorLetter(slot)}", scanner.kalmanRssi(m))
            }.sortedByDescending { it.second ?: Int.MIN_VALUE }
            live.text = if (rows.isEmpty()) "no sensors assigned yet"
                else rows.joinToString("\n") { (label, v) ->
                    label.padEnd(13) + (v?.let { "$it dBm" } ?: "not heard")
                }
            val ownHeard = ownMac()?.let { scanner.kalmanRssi(it) } != null
            live.setTextColor(if (ownHeard) Palette.label else Palette.dangerText)
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
                bigChoice("Finish", Palette.successText) { showRoomBeaconDebug() })
        }

        // ── Phase: free-roam around the house, tagging false readings ─────────────
        fun goRoam() {
            // Persist everything so far - the live indicator below runs on the full set.
            RoomBeacons.setSamples(this, room, collected)
            RoomPresence.reset()
            fun tagPage() {
            val indicator = TextView(this).apply { gravity = Gravity.CENTER; setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt()) }
            var tagging = false
            val tagBtn = bigChoice("TAG FALSE READING HERE", Palette.dangerText) {}
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
                    // The two verdicts that read as true are the loud error out here.
                    RoomPresence.Verdict.IN, RoomPresence.Verdict.MAYBE_IN_TRUE -> {
                        indicator.text = "IT THINKS YOU'RE IN THE ${roomName.uppercase()}!\nThat's wrong - press the red button."
                        indicator.textSize = 22f; indicator.setTypeface(indicator.typeface, Typeface.BOLD)
                        indicator.setTextColor(Palette.dangerText)
                    }
                    RoomPresence.Verdict.MAYBE_IN, RoomPresence.Verdict.MAYBE_OUT -> {
                        val which = if (st.verdict == RoomPresence.Verdict.MAYBE_IN) "probs is" else "probs not"
                        indicator.text = "MAYBE ($which)\nBorderline here - press the red button to teach it."
                        indicator.textSize = 18f; indicator.setTypeface(indicator.typeface, Typeface.BOLD)
                        indicator.setTextColor(Palette.warningText)
                    }
                    else -> {
                        indicator.text = "false ✓  (correct)"
                        indicator.textSize = 13f; indicator.setTypeface(null, Typeface.NORMAL)
                        indicator.setTextColor(Palette.labelTertiary)
                    }
                }
            }
            }

            // Gate: they must actually LEAVE the room before tagging starts, or the
            // first "false reading" they'd tag is the inside of their own room.
            show("Set up · $roomName · step outside", "First - step OUT of the $roomName",
                bigBody(
                    "Leave the $roomName now, and set the door the way it usually sits.\n\n" +
                        "Once you're outside, confirm below - then walk around the REST of the " +
                        "house and fix any incorrect readings.",
                ),
                bigChoice("I've stepped outside the room", Palette.tint) { tagPage() })
        }

        // ── Phase: 15 s walk around the room (outliers get trimmed later) ─────────
        fun goWander() {
            val mac = ownMac() ?: run { showRoomBeaconDebug(); return }
            val live = liveLine()
            val countdown = bigCountdown()
            val prog = TextView(this).apply { textSize = 13f; setTextColor(Palette.labelTertiary); gravity = Gravity.CENTER }
            var wandering = false
            var got = 0
            val startBtn = bigChoice("Start - walk around the room", Palette.tint) {}
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
            val spots = RoomBeacons.assignedSensors(this, room)
                .filter { it.first == room }
                .map { (_, slot, _) ->
                    val letter = RoomBeacons.sensorLetter(slot)
                    CalSpot("Right next to sensor $letter",
                        "Stand about an arm's length from SENSOR $letter in the $roomName.", core = true)
                } + staticInsideSpots + temptationSpots
            if (i >= spots.size) { goWander(); return }
            val spot = spots[i]
            val mac = ownMac() ?: run { showRoomBeaconDebug(); return }
            val live = liveLine()
            val countdown = bigCountdown()
            val sampleBtn = bigChoice("Sample this spot (hold still 3 s)", Palette.tint) {}
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
            val placements = roomsNow.mapNotNull { r ->
                val letters = (0 until RoomBeacons.sensorCount(this, r))
                    .filter { RoomBeacons.beaconMacAt(this, r, it) != null }
                    .map { RoomBeacons.sensorLetter(it) }
                val rn = r.replaceFirstChar { c -> c.uppercase() }
                when {
                    letters.isEmpty() -> null
                    letters.size == 1 -> "· $rn: its sensor at the risk spot"
                    else -> "· $rn: sensor A at the risk spot; ${letters.drop(1).joinToString(", ")} spread at the other ends of the room"
                }
            }.joinToString("\n")
            show("Set up · $roomName · place the sensors", "Put EVERY sensor in its place",
                bigBody(
                    "KEY RULE: put each sensor closest to where the risk actually is - bedroom: " +
                        "at the bed (headboard or bedside table); bathroom: back of a cupboard or " +
                        "a shelf near where the phone would get used.\n\n" +
                        "HOW to place each one:\n" +
                        "· sensors in the same room go at OPPOSITE ends of the room\n" +
                        "· as LOW to the floor as possible\n" +
                        "· sit each one on a piece of ALUMINIUM FOIL, roughly 8cm × 8cm - doesn't " +
                        "have to be exact, just enough to cover its base (it steadies the signal)\n\n" +
                        placements + "\n\n" +
                        "They must never move once calibrated - if you move one later, redo the " +
                        "set-up.\n\nCome back here with your phone once they're all in place.",
                ),
                bigChoice("All in place - start calibrating", Palette.tint) { collected.clear(); goSpot(0) })
        }

        // Finds one sensor (hold-against-phone → strongest → confirm), for slot A-D.
        lateinit var assignFlow: (String, String, Int, () -> Unit) -> Unit
        fun confirmPage(target: String, step: String, slot: Int, candidate: BeaconScanner.Beacon, onDone: () -> Unit) {
            val targetName = target.replaceFirstChar { it.uppercase() }
            val letter = RoomBeacons.sensorLetter(slot)
            show(step, "Found it",
                bigBody(
                    "Strongest signal right now:\n\n${candidate.name ?: "unnamed beacon"}\n${candidate.mac}\n" +
                        "${Math.round(candidate.smoothedRssi)} dBm\n\nIs that the sensor in your hand?",
                ),
                bigChoice("Yes - this is $targetName sensor $letter", Palette.successText) {
                    RoomBeacons.setBeaconMacAt(this, target, slot, candidate.mac)
                    scanner.expectedMacs = RoomBeacons.allAssignedMacs(this).toSet()
                    onDone()
                },
                Button(this).apply { text = "No - scan again"; setAllCaps(false); setOnClickListener { assignFlow(target, step, slot, onDone) } })
        }

        assignFlow = fun(target: String, step: String, slot: Int, onDone: () -> Unit) {
            val letter = RoomBeacons.sensorLetter(slot)
            val countdown = bigCountdown()
            val live = TextView(this).apply {
                textSize = 15f; setTextColor(Palette.labelTertiary); gravity = Gravity.CENTER
                setPadding(0, (6 * dp).toInt(), 0, 0)
            }
            var finding = false
            val startBtn = bigChoice("Start the 3-second scan", Palette.tint) {}
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
                val currentSlot = RoomBeacons.beaconMacAt(this, target, slot)
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
                            best.smoothedRssi < -65 -> Toast.makeText(this@MainActivity,
                                "Strongest device is only ${Math.round(best.smoothedRssi)} dBm - too weak to be touching the phone. Press the beacon against the phone's back and try again.",
                                Toast.LENGTH_LONG).show()
                            else -> confirmPage(target, step, slot, best, onDone)
                        }
                    }
                })
            }
            show(step, "Find SENSOR $letter",
                bigBody(
                    (if (slot > 0) "This one lives at a DIFFERENT part of the $target from the others.\n\n" else "") +
                        "1. Make sure the sensor is switched on: hold its button for ~3 seconds until " +
                        "its light blinks. (Brand new? Pull the battery tab out first.)\n\n" +
                        "2. Hold the sensor flat against the BACK of your phone.\n\n" +
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

        // Assign every sensor slot the room card asked for (1-4); slots that already
        // have a sensor are skipped silently, then everything is placed and calibrated.
        lateinit var assignSlots: (Int) -> Unit
        assignSlots = fun(i: Int) {
            val count = RoomBeacons.sensorCount(this, room)
            if (i >= count) { placePage(); return }
            if (RoomBeacons.beaconMacAt(this, room, i) != null) { assignSlots(i + 1); return }
            assignFlow(room,
                "Set up · sensor ${RoomBeacons.sensorLetter(i)} of ${RoomBeacons.sensorLetter(count - 1)}",
                i) { assignSlots(i + 1) }
        }

        val existing = RoomBeacons.beaconMac(this, room)
        when {
            existing == null -> assignSlots(0)
            !RoomBeacons.isCalibrated(this, room) -> assignSlots(0)
            else -> show("Recalibrate", "Recalibrate $roomName",
                bigBody(
                    "This room is already set up. Keep the same sensor(s) and redo the " +
                        "calibration walk, or start from scratch?",
                ),
                bigChoice("Keep sensor(s) - recalibrate", Palette.tint) { assignSlots(0) },
                Button(this).apply {
                    text = "Start over - pick the sensors again"; setAllCaps(false)
                    setOnClickListener {
                        RoomBeacons.setBeaconMac(this@MainActivity, room, null)
                        assignSlots(0)
                    }
                })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Re-enter the page: it shows the readings if granted, the explainer again if not.
        if (requestCode == REQ_BEACON_PERMS) showRoomBeaconDebug()
        // Step 1 granted: come straight back and offer step 2 ("all the time"), which has
        // to be its own request. Step 2's result lands here too - on Android 11+ it will
        // usually be a denial, and the page then points at Settings instead.
        // ...and once "while using" is in, come back ON the next step (saving the home
        // point) rather than at the top of the page. Being bounced back to a header you
        // have already dealt with is what made this feel like the app had closed on you.
        if (requestCode == REQ_HOME_LOCATION || requestCode == REQ_HOME_BACKGROUND) {
            showHouseArea(focusSave = HomeArea.hasPermissions(this) && !HomeArea.isSet(this))
        }
    }

    // Read-only snapshot of everything the app is currently doing.
    // ═════════════════════════════════════════════════════════════════════════════════
    //  WORD FILTER  (Developer tools → Word filter)
    // ═════════════════════════════════════════════════════════════════════════════════
    //  Everything the content filter does, for the mode the user is actually in, built FROM
    //  the filter (FilterCatalogue) and the page rules (AppConfig.GUARDED_SCREENS) rather
    //  than written alongside them - a hand-written version is wrong the first time
    //  somebody edits a word list.
    //
    //  IT IS A REFERENCE PAGE, SO IT IS BUILT TO BE SCANNED, NOT READ. Rules to keep:
    //    • every number that matters is large, coloured, and near the top;
    //    • EVERY list on this page is tappable - if it says "552 hosts", you can see them;
    //    • every rule that is easy to misread carries a worked SCORES / DOESN'T SCORE pair,
    //      because an abstract description of a context gate teaches nobody anything;
    //    • prose is one line under the thing it explains, never a paragraph on its own.
    //
    //  Dev-tools pages are hardcoded English by convention here (see showDevConsole): they
    //  are diagnostics, not product surface, and ~100 dev-only keys in strings.xml would be
    //  a translation bill for nobody's benefit.
    // ═════════════════════════════════════════════════════════════════════════════════

    /** Kept so returning from a drill-in lands where you left, not at the top. */
    private var filterScroll: ScrollView? = null
    private var filterScrollY = 0

    /** Colour for a points badge: louder the more one hit is worth. */
    private fun pointsTone(points: Int): Pair<Int, Int> = when {
        points >= FilterTuning.EXPLICIT_WEIGHT -> Palette.dangerSoft to Palette.dangerText
        points >= FilterTuning.STRONG_WEIGHT -> Palette.warningSoft to Palette.warningText
        points > 0 -> Palette.tintSoft to Palette.tintDeep
        else -> Palette.surfaceSunken to Palette.labelSecondary
    }

    private fun behaviourTone(b: FilterCatalogue.Behaviour): Int = when (b) {
        FilterCatalogue.Behaviour.BLOCKS_ALONE -> Palette.dangerText
        FilterCatalogue.Behaviour.NEEDS_A_SECOND -> Palette.labelSecondary
        FilterCatalogue.Behaviour.ONLY_IN_CONTEXT -> Palette.warningText
        FilterCatalogue.Behaviour.NO_SCORE -> Palette.labelTertiary
    }

    // ── THE MODE COLOUR SYSTEM ──────────────────────────────────────────────────────
    //  One colour per mode, used on every card that behaves differently between them, so
    //  "what changes if I go stricter" is answerable by looking rather than by reading.
    //  Escalating warmth: grey, teal, amber, red.
    private data class ModeChip(val id: String, val label: String, val colour: Int)

    private val MODE_CHIPS = listOf(
        ModeChip(Mode.OFF, "Off", Palette.labelQuaternary),
        ModeChip(Mode.RELAXED, "Relaxed", Palette.tint),
        ModeChip(Mode.STRICT, "Strict", Palette.warning),
        ModeChip(Mode.SUPERHARDCORE, "Hardcore", Palette.danger),
    )

    /** Is [g] switched on in the given mode? Off means monitoring is off entirely. */
    private fun activeInMode(g: FilterCatalogue.Group, modeId: String): Boolean = when (modeId) {
        Mode.OFF -> false
        Mode.RELAXED -> g.activeIn(true, false)
        Mode.STRICT -> g.activeIn(false, false)
        else -> g.activeIn(false, true)
    }

    /** The key, at the top of the page: what the four colours mean. */
    private fun modeKeyCard(): View {
        val dp = resources.displayMetrics.density
        val here = Mode.current(this)
        val card = glassCard(Space.md)
        card.addView(ruleHeading("THE COLOURS BELOW", Palette.labelTertiary))
        for (m in MODE_CHIPS) {
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
                addView(TextView(this@MainActivity).apply {
                    text = "\u25CF"; textSize = 15f; setTextColor(m.colour)
                    setPadding(0, 0, (10 * dp).toInt(), 0)
                })
                addView(TextView(this@MainActivity).apply {
                    text = AppConfig.modeName(m.id) + if (m.id == here) "   (you are here)" else ""
                    textSize = Type.callout
                    setTypeface(typeface, if (m.id == here) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (m.id == here) Palette.label else Palette.labelSecondary)
                })
            })
        }
        card.addView(TextView(this).apply {
            text = "A filled dot means the rule is on in that mode. A hollow one means it " +
                "scores nothing there."
            textSize = Type.caption; setTextColor(Palette.labelTertiary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        return card
    }

    /**
     * The four dots for one rule, in the key's order: filled where it is on, hollow where it
     * is not. No labels - the key at the top of the page carries those, and repeating
     * "Off Relaxed Strict Hardcore" on forty cards is noise, not information.
     */
    private fun modeDots(activePerMode: List<Boolean>): View {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, 0)
            MODE_CHIPS.forEachIndexed { i, m ->
                val on = activePerMode.getOrElse(i) { false }
                addView(TextView(this@MainActivity).apply {
                    text = if (on) "\u25CF" else "\u25CB"
                    textSize = 15f
                    setTextColor(if (on) m.colour else Palette.labelQuaternary)
                    setPadding(0, 0, (7 * dp).toInt(), 0)
                })
            }
        }
    }

    private fun showFilterBreakdown() {
        val settings = BorderlineScorer.Settings.of(this)
        val relaxed = settings.relaxed
        val superHardcore = settings.superHardcore
        val modeName = AppConfig.modeName(Mode.current(this))
        val webBar = BorderlineScorer.webBar()

        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText("Word filter"))
        root.addView(TextView(this).apply {
            text = "Everything below is what is live in $modeName, right now."
            textSize = Type.callout; setTextColor(Palette.labelSecondary)
            setPadding(0, 0, 0, (4 * dp).toInt())
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true; addView(list)
        }
        filterScroll = scroll
        root.addView(scroll)
        setContentWithThumb(root) { filterScrollY = 0; setupMainScreen() }
        // Coming back from a word list should land where you left, not at the top.
        scroll.post { scroll.scrollTo(0, filterScrollY) }

        // ── building blocks ──────────────────────────────────────────────────────────
        /** A big section break. Rule, then the heading - reads as a new chapter. */
        fun section(title: String, blurb: String? = null) {
            list.addView(View(this).apply {
                setBackgroundColor(Palette.tint)
                layoutParams = LinearLayout.LayoutParams((34 * dp).toInt(), (3 * dp).toInt())
                    .apply { topMargin = (30 * dp).toInt() }
            })
            list.addView(TextView(this).apply {
                text = title
                textSize = Type.title2; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Palette.label); letterSpacing = -0.02f
                setPadding(0, (8 * dp).toInt(), 0, 0)
            })
            if (blurb != null) list.addView(TextView(this).apply {
                text = blurb
                textSize = Type.footnote; setTextColor(Palette.labelSecondary)
                setLineSpacing(0f, Type.lineSpacing)
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })
            list.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(1, (10 * dp).toInt())
            })
        }

        fun note(t: String) = list.addView(TextView(this).apply {
            text = t; textSize = Type.footnote; setTextColor(Palette.labelTertiary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (10 * dp).toInt())
        })

        // ═════════════════════════════════════════════════════════════════════════════
        //  0. THE MODE KEY  —  everything below is colour-coded against this
        // ═════════════════════════════════════════════════════════════════════════════
        list.addView(modeKeyCard())

        // ═════════════════════════════════════════════════════════════════════════════
        //  1. THE CUTOFF
        // ═════════════════════════════════════════════════════════════════════════════
        section("The cutoff", "Words add up to a score. These are the scores that block.")

        val webCard = glassCard(Space.md)
        webCard.addView(ruleHeading("A WEB PAGE", Palette.dangerText))
        webCard.addView(bigNumberRow("$webBar", "points blocks it", "", Palette.dangerText))
        webCard.addView(separator())
        webCard.addView(smallRule("or ONE Core word, Loud phrase or site name. Always enough, " +
            "on its own.", true))
        list.addView(webCard)

        val appCard = glassCard(Space.md)
        appCard.addView(ruleHeading("ANY OTHER APP SCREEN", Palette.dangerText))
        appCard.addView(bigNumberRow("${FilterTuning.APP_THRESHOLD}", "points blocks it", "",
            Palette.dangerText))
        appCard.addView(separator())
        appCard.addView(smallRule("under ${FilterTuning.APP_LONG_TEXT_WORDS} words on screen:  " +
            "one Core word blocks it", true))
        appCard.addView(smallRule("over ${FilterTuning.APP_LONG_TEXT_WORDS} words on screen:  " +
            "a Core word needs a second different word", true))
        list.addView(appCard)

        val ssCard = glassCard(Space.md)
        ssCard.addView(ruleHeading("AND SEARCH HAS TO BE SAFE", Palette.dangerText))
        ssCard.addView(smallRule("Google is the only search engine allowed, in every mode", true))
        ssCard.addView(smallRule("a URL that switches SafeSearch off is blocked, and recorded " +
            "as a bypass attempt - there is no innocent way to arrive at \"&safe=off\"", true))
        ssCard.addView(smallRule("ordinary searching and image search are NOT touched - web " +
            "browsing is monitored lightly, because the Firefox add-on is what covers images", true))
        list.addView(ssCard)

        // ═════════════════════════════════════════════════════════════════════════════
        //  2. THE WORDS
        // ═════════════════════════════════════════════════════════════════════════════
        section("What each word is worth",
            "One hit, before any multiplier. Tap a row for the full word list.")
        for (g in FilterCatalogue.GROUPS) {
            list.addView(filterGroupCard(g))
        }
        note("Grey rows are switched OFF in $modeName and score nothing at all right now.")

        // ═════════════════════════════════════════════════════════════════════════════
        //  3. CONTEXT GATES
        // ═════════════════════════════════════════════════════════════════════════════
        section("Words that only count in context",
            "Some words are ordinary English until something else on the page changes that. " +
                "On their own they score ZERO - not a little, zero. This is the rule that " +
                "keeps \"hot chocolate\" and \"tight deadline\" out of it.")
        val gated = FilterCatalogue.GROUPS.filter {
            it.behaviour == FilterCatalogue.Behaviour.ONLY_IN_CONTEXT && it.activeIn(relaxed, superHardcore)
        }
        if (gated.isEmpty()) {
            val none = glassCard(Space.md)
            none.addView(bodyText("No context-gated groups are switched on in $modeName."))
            list.addView(none)
        } else {
            for (g in gated) list.addView(gateCard(g))
        }
        list.addView(filterGroupCard(FilterCatalogue.PERSON_WORDS))
        note("\"Person words\" is worth 0 because it is the TRIGGER list, not a score. It " +
            "never adds a point of its own - it sits next to a gated word and switches it on.")

        // ═════════════════════════════════════════════════════════════════════════════
        //  4. EXCEPTIONS
        // ═════════════════════════════════════════════════════════════════════════════
        section("Exceptions",
            "The sharpest tool in the filter, and the first place to look when something is " +
                "blocking that shouldn't.")
        list.addView(exceptionsCard())
        note("It is a hand-written list, one line per word: \"the word: the neighbours that " +
            "excuse it\". Tap it to read the whole thing.")

        // ═════════════════════════════════════════════════════════════════════════════
        //  5. EVASION
        // ═════════════════════════════════════════════════════════════════════════════
        section("Spelling it differently",
            "Five mechanisms, all running BEFORE any word list is consulted - so none of them " +
                "needs a new spelling to be added to a list first.")
        for (s in FilterCatalogue.EVASION) list.addView(scalerCard(s))

        // ═════════════════════════════════════════════════════════════════════════════
        //  6. ONE WORD IS A QUESTION, NOT AN ANSWER  (RepeatGate / AppTrust)
        // ═════════════════════════════════════════════════════════════════════════════
        section("One word is a question, not an answer",
            "Everything above decides whether a screen COUNTS. This decides whether counting " +
                "once is enough to take the app away. Inside an app, it never is.")
        val gate = glassCard(Space.md)
        gate.addView(ruleHeading("INSIDE AN APP", Palette.warningText))
        gate.addView(bodyText(
            "A detection opens a case instead of closing the app. Everything is then ignored " +
                "for a fixed wait - not counted, not remembered - and it takes a FRESH " +
                "detection after the wait to move up a rung. One screen fires dozens of " +
                "events and a question you typed stays on screen while you read the answer; " +
                "the waits are what stop those counting as separate looks.",
        ))
        gate.addView(separator())
        gate.addView(smallRule("an app you have had a while:  detection  →  " +
            "${RepeatGate.WAIT_FIRST_MS / 1000}s ignored  →  detection  →  " +
            "${RepeatGate.WAIT_SECOND_MS / 1000}s ignored  →  detection  →  blocked " +
            "(${RepeatGate.HITS_KNOWN} in all)", true))
        gate.addView(smallRule("an app we have closed for content before:  detection  →  " +
            "${RepeatGate.WAIT_SECOND_MS / 1000}s ignored  →  detection  →  blocked " +
            "(${RepeatGate.HITS_REPEAT})", true))
        gate.addView(smallRule("a NEW app:  blocked on the first one", true))
        gate.addView(smallRule("${RepeatGate.CASE_MS / 60_000} minutes with no further " +
            "detection in that app  →  the case is dropped, unconfirmed", true))
        gate.addView(smallRule("${RepeatGate.QUIET_RESET_MS / 60_000} minutes with nothing " +
            "detected in ANY app  →  every count everywhere goes back to zero", true))
        list.addView(gate)

        val trust = glassCard(Space.md)
        trust.addView(ruleHeading("WHAT MAKES AN APP \"NEW\"", Palette.dangerText))
        trust.addView(bodyText(
            "An app you have had for months has earned some benefit of the doubt: nearly " +
                "everything it has ever shown you was fine. An app installed on Tuesday has " +
                "earned nothing - a run of fresh installs is what looking for a way round a " +
                "blocker looks like from the outside.",
        ))
        trust.addView(separator())
        trust.addView(smallRule("KNOWN:  on the phone ${AppTrust.ESTABLISHED_DAYS}+ days, OR " +
            "seen in the foreground on ${AppTrust.ESTABLISHED_DAYS_SEEN}+ separate days", true))
        trust.addView(smallRule("NEW:  neither of those yet", true))
        trust.addView(smallRule("counted in DAYS, never in opens or minutes - days cannot be " +
            "manufactured in an afternoon by an app that wants to look established", true))
        trust.addView(smallRule("install date comes from Android, but if we have been watching " +
            "the app for longer than that, the longer one wins - a phone-to-phone restore " +
            "makes every app on the device look new", true))
        trust.addView(smallRule("nothing here needs the usage-access permission: the guard is " +
            "already watching the foreground, so it just counts", true))
        list.addView(trust)

        // The one thing that DOES change the ladder: where the phone is, in one mode.
        val houseOn = superHardcore
        val house = glassCard(Space.md)
        house.addView(ruleHeading(
            if (houseOn) "ON IN $modeName - WHEN YOU ARE AT HOME".uppercase()
            else "SUPER HARDCORE ONLY - OFF IN $modeName".uppercase(),
            if (houseOn) Palette.dangerText else Palette.labelTertiary))
        house.addView(bodyText(
            "The house is where it happens - the sofa, the bedroom, the hours after everyone " +
                "else has gone to bed. So in Super hardcore, and only there, being at home " +
                "collapses the whole ladder above to ONE: the first detection closes the app, " +
                "with no second look. Everywhere else the ordinary ladder applies.",
        ))
        house.addView(separator())
        house.addView(smallRule(
            when {
                !HomeArea.isSet(this) ->
                    "your house is NOT saved yet, so this cannot fire - set it up in " +
                        "Overview → Where you are"
                !houseOn -> "your house is saved; this mode does not act on it"
                HomeRule.atHome() -> "the phone says you are AT HOME: one detection is enough " +
                    "right now"
                else -> "the phone says you are out (or cannot tell): the ordinary ladder is " +
                    "in force"
            },
            HomeArea.isSet(this)))
        house.addView(smallRule("only a settled \"at home\" counts - \"maybe\" and \"can't " +
            "tell\" leave the ordinary ladder exactly as it is, because being unsure must " +
            "never make anything stricter", true))
        house.addView(smallRule("GPS and nearby Wi-Fi, on this device, nothing sent anywhere - " +
            "and a VPN cannot move it: it is satellites and radios, not an IP address", true))
        list.addView(house)

        note("Apart from that one rule, mode does not change any of this. What Strict and Super " +
            "hardcore change is what counts as a detection in the first place - more word lists " +
            "live, a lower bar, the fragment lists - not how many it takes to close an app.")

        // ═════════════════════════════════════════════════════════════════════════════
        //  6b. THE APP THAT KEEPS ALMOST BLOCKING
        // ═════════════════════════════════════════════════════════════════════════════
        section("The app that keeps almost blocking",
            if (relaxed) "Strict and above only - off in $modeName."
            else "One borderline screen means nothing. Minutes of them, in one app, is a feed.")
        val bw = glassCard(Space.md)
        bw.addView(ruleHeading(
            if (relaxed) "OFF IN $modeName".uppercase() else "ON IN $modeName".uppercase(),
            if (relaxed) Palette.labelTertiary else Palette.warningText))
        bw.addView(bodyText(
            "Every ${BorderlineWatch.SAMPLE_MS / 1000} seconds, the screen in front counts " +
                "once. Scoring ${FilterTuning.BORDERLINE_FLOOR}+ (or carrying a suspicious " +
                "near-miss spelling) fills the bucket by one; a clean screen drains it by " +
                "one. So it measures how much of the last few minutes was borderline - not " +
                "how long the app has been open.",
        ))
        bw.addView(separator())
        bw.addView(smallRule("bucket reaches ${BorderlineWatch.WARN_AT}  (about " +
            "${BorderlineWatch.WARN_AT * BorderlineWatch.SAMPLE_MS / 60_000} min of it)  →  a " +
            "warning screen for ${BorderlineWatch.WARN_HOLD_MS / 1000} seconds, saying " +
            "exactly what happens next", !relaxed))
        bw.addView(smallRule("bucket reaches ${BorderlineWatch.BLOCK_AT}  (about " +
            "${BorderlineWatch.BLOCK_AT * BorderlineWatch.SAMPLE_MS / 60_000} min)  →  the " +
            "app closes for ${BorderlineWatch.PENALTY_LABEL}", !relaxed))
        bw.addView(smallRule("nothing counts from more than " +
            "${BorderlineWatch.WINDOW_MS / 60_000} minutes ago", !relaxed))
        bw.addView(smallRule("draining means a clean stretch undoes it - the bucket cannot " +
            "be filled by an app you simply left open", !relaxed))
        list.addView(bw)
        note("Nothing here has crossed the line, which is exactly why no single screen can be " +
            "acted on. Relaxed is left alone on purpose: it is the mode that promises to act " +
            "only on things that HAVE crossed the line.")

        // ═════════════════════════════════════════════════════════════════════════════
        //  7. SCALERS AND CAPS
        // ═════════════════════════════════════════════════════════════════════════════
        section("What scales a score", "These multiply what everything above is worth.")
        for (s in FilterCatalogue.SCALERS) list.addView(scalerCard(s))

        section("The caps", "Why one word can never block a page on its own.")
        for (s in FilterCatalogue.CAPS) list.addView(scalerCard(s))

        // ═════════════════════════════════════════════════════════════════════════════
        //  8. THE LISTS
        // ═════════════════════════════════════════════════════════════════════════════
        section("The app lists", "Which apps get looked at, and how. Tap any of them to read.")
        list.addView(listCard(
            "Whitelist  ·  never looked at", AppConfig.SAFE_APPS.size, "apps",
            "No read, no scan, no screenshot, no log. Nothing on this page applies to them.",
            "Apps with no public feed and no arbitrary adult content: maps, messaging, " +
                "banking, utilities. Skipping them outright is most of why the monitor is " +
                "cheap to run. Edit the set in Developer tools → Whitelisted apps.",
            Palette.successText,
        ) { AppConfig.SAFE_APPS_BY_NAME.entries.sortedBy { it.key }.map { "${it.key}  -  ${it.value}" } })
        list.addView(listCard(
            "Greylist  ·  scanned AND time-limited", AppConfig.GREYLIST_APPS.size, "apps",
            "Scored like anything else, and additionally capped at ${GreyUsage.LIMIT_MIN} " +
                "minutes an hour.",
            "Social and short-form apps that MAY carry adult content but are also genuinely " +
                "used. Blocking them outright is a fight nobody wins; a time budget is the " +
                "honest middle. Never whitelisted, whatever else changes.",
            Palette.warningText,
        ) { AppConfig.GREYLIST_APPS_BY_NAME.entries.sortedBy { it.key }.map { "${it.key}  -  ${it.value}" } })
        list.addView(listCard(
            "Blocked browsers  ·  covered on sight", AppConfig.BLOCKED_BROWSERS.size, "packages",
            "Every browser except Firefox is covered the moment it opens.",
            "One browser is the whole strategy: the address bar can be read, the add-on can " +
                "be required, and there is no second surface to police. Anything Android " +
                "reports as able to open a web link is ALSO treated as a browser at runtime, " +
                "so this list is a floor, not a ceiling.",
            Palette.dangerText,
        ) { entryList(AppConfig.BLOCKED_BROWSERS.sorted()) })
        list.addView(listCard(
            "Allowed browsers", AppConfig.ALLOWED_BROWSERS.size, "packages",
            "The only browsers left usable.",
            "",
            Palette.successText,
        ) { entryList(AppConfig.ALLOWED_BROWSERS.sorted()) })

        section("The blacklist",
            "Hand-maintained lists of apps and sites, blocked outright in every mode above " +
                "Off. No score, no threshold.")
        list.addView(listCard(
            "Blacklisted apps & sites", BlockedCategories.ALL.size, "categories",
            "User-uploaded feeds, sexual content, livestreams and dating, VPNs.",
            "Each category is a pair of plain text files. Tap to read them.",
            Palette.dangerText, openPage = { showBlacklistPage() },
        ))

        section("Other things never scanned")
        list.addView(listCard(
            "Trusted domains  ·  word scoring skipped", AppConfig.SAFE_DOMAINS.size, "domains",
            "The word scorer never runs here. The domain blocklist still does.",
            "Wikipedia, Stack Overflow, the NHS, Mayo Clinic and friends. Anatomy words are " +
                "unavoidable on a health site, and nobody should be blocked from looking up a " +
                "symptom. A bare domain covers its subdomains.",
            Palette.successText,
        ) { entryList(AppConfig.SAFE_DOMAINS.sorted()) })
        val surfaces = glassCard(Space.md)
        surfaces.addView(ruleHeading("SURFACES BEYOND THE FOREGROUND APP", Palette.tintDeep))
        surfaces.addView(smallRule("SPLIT SCREEN - both panes are checked, not just the focused " +
            "one. A blocked app in the other half used to be fully usable.", true))
        surfaces.addView(smallRule("PICTURE-IN-PICTURE - a PiP window is never focused and never " +
            "active, so a video from a blocked app used to keep playing over the home screen. " +
            "Now covered like any other visible app.", true))
        surfaces.addView(smallRule("IN-APP BROWSERS - a link opened inside Instagram, Reddit or " +
            "Telegram renders in the app's own WebView with no address bar. The domain is now " +
            "read out of the app's chrome, so every domain rule applies there too. It is scored " +
            "at the APP bar, not the web bar: the image add-on is not watching inside Instagram.", true))
        surfaces.addView(smallRule("NOTIFICATIONS - read and scored, never covered. There is " +
            "nothing to cover: the system draws them. A stream of adult-scoring notifications " +
            "from one app counts toward that app's borderline pattern.", true))
        surfaces.addView(smallRule("RECENT APPS - the carousel shows live thumbnails of " +
            "everything open, and is NOT covered. Blocking the app switcher would trap you; the " +
            "thumbnail is a moment, the app itself is already blocked.", false))
        list.addView(surfaces)

        val skip = glassCard(Space.md)
        skip.addView(smallRule("The home screen / launcher - it carries the name of every app " +
            "on the device, which is somebody else's text, not something you chose to look at", true))
        skip.addView(smallRule("Keyboards - they draw their own window over whatever is underneath", true))
        skip.addView(smallRule("This app", true))
        skip.addView(smallRule("The browser's OWN screens - its tab list, settings and history. " +
            "Covering those would trap you on the one screen you need in order to close a bad " +
            "tab. The watched screens further down are the deliberate exception", true))
        list.addView(skip)

        // ═════════════════════════════════════════════════════════════════════════════
        //  9. BLOCKED WITHOUT A SCORE
        // ═════════════════════════════════════════════════════════════════════════════
        // ═════════════════════════════════════════════════════════════════════════════
        section("Tamper watch",
            "Two things we cannot prevent but can now notice. Neither goes anywhere - " +
                "there is no partner and no server; this is the local version.")
        val tw = glassCard(Space.md)
        val slip = TamperWatch.clockSlip(this)
        val gap = TamperWatch.lastGap(this)
        tw.addView(smallRule(
            if (slip == null) "System clock: never moved unexpectedly"
            else "System clock was moved by ${slip.second / 60_000} min on " +
                java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(slip.first)) +
                " - every timer in the app is wall-clock based, so that ends them all",
            slip == null))
        tw.addView(smallRule(
            if (gap == null) "Coverage: no gaps recorded"
            else "The guard was not running for ${gap.second / 60_000} min on " +
                java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(gap.first)) +
                " - safe mode, a force stop, or a battery manager",
            gap == null))
        tw.addView(smallRule(
            if (BatteryGuard.isExempt(this)) "Battery optimisation: exempt, the service can stay up"
            else "Battery optimisation is ON for this app - the system may kill the guard. " +
                "Tap to fix.",
            BatteryGuard.isExempt(this)))
        if (!BatteryGuard.isExempt(this)) {
            tw.isClickable = true
            tw.setOnClickListener { BatteryGuard.request(this) }
        }
        list.addView(tw)

        val installs = InstallLog.recent(this)
        list.addView(listCard(
            "Apps installed since monitoring started", installs.size, "installs",
            "A run of installs during Strict looks exactly like somebody working through " +
                "VPNs until one sticks.",
            "Local only, capped at 60 entries, package names not contents.",
            if (installs.any { it.second != "not on any list" }) Palette.dangerText else Palette.successText,
            entries = { installs.map { Entry(it.first, it.second) } },
        ))

        // ═════════════════════════════════════════════════════════════════════════════
        section("Blocked with no score at all",
            "These never reach the word filter. No threshold, no multiplier, no appeal. " +
                "Tap any of them to see what is on the list.")
        list.addView(listCard(
            "Known adult domains", -1,
            if (DomainBlocklist.isReady) "~550k hosts" else "not loaded in this process",
            "Built once from public blocklists, then cached on the device.",
            "Far too large to page through here - use the Try it box, or the log, to check a " +
                "specific host. The service builds it in the background on first run.",
            if (DomainBlocklist.isReady) Palette.dangerText else Palette.labelTertiary,
            null,
        ))
        list.addView(listCard(
            "The hand-maintained ban list", AlwaysBlocklist.DOMAINS.size, "hosts",
            "Reddit and its mirrors and frontends, imageboards, borderline shops.",
            "Banned in EVERY mode above Off, deliberately: a bypass surface that Relaxed lets " +
                "through is still a bypass surface.",
            Palette.dangerText,
        ) { entryList(AlwaysBlocklist.DOMAINS.sorted()) })
        list.addView(listCard(
            "Search engines other than Google", SearchEngineBlocklist.DOMAINS.size, "hosts",
            "Google is the only search engine allowed, in every mode.",
            "Only the SEARCH host is listed, never a whole company - \"search.yahoo.com\", " +
                "not \"yahoo.com\", so Yahoo Mail still works. Self-hosted metasearch has no " +
                "fixed domain and cannot be enumerated; add instances as you meet them.",
            Palette.dangerText,
        ) { entryList(SearchEngineBlocklist.DOMAINS.sorted()) })
        list.addView(listCard(
            "Strict-only hosts", StrictOnlyBlocklist.DOMAINS.size, "hosts",
            if (relaxed) "Not in force in $modeName." else "In force in $modeName.",
            "Currently empty by design - everything it held was promoted to the always-banned " +
                "list. The mechanism stays for a host that genuinely should be allowed in " +
                "Relaxed but not above it.",
            if (relaxed) Palette.labelTertiary else Palette.dangerText,
        ) { entryList(StrictOnlyBlocklist.DOMAINS.sorted()) })
        list.addView(listCard(
            "Your own ban list", BlockRules.all().size, "rules",
            "The sites, pages and keywords you banned yourself from.",
            "Edit them in Developer tools → Manage block rules.",
            Palette.dangerText,
        ) { entryList(BlockRules.all().sorted()) })

        // ═════════════════════════════════════════════════════════════════════════════
        //  10. WATCHED SCREENS
        // ═════════════════════════════════════════════════════════════════════════════
        section("Specific screens we watch for",
            "Not words - these are recognised by the text ON them, and each one was added " +
                "from a real log entry. If an OS update changes the wording, that is what " +
                "breaks, so the matched text is listed here to be fixed.")
        for (g in AppConfig.GUARDED_SCREENS) list.addView(guardedScreenCard(g))

        // ═════════════════════════════════════════════════════════════════════════════
        //  11. TRY IT
        // ═════════════════════════════════════════════════════════════════════════════
        section("Try it", "Type anything and see exactly what it scores, and why, in $modeName.")
        val input = EditText(this).apply {
            hint = "a title, a URL, a line of page text…"
            textSize = Type.callout; setTextColor(Palette.label); setHintTextColor(Palette.labelQuaternary)
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
            background = surfaceBg(Palette.surface, Radius.control)
        }
        list.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val resultCard = glassCard(Space.md).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = (10 * dp).toInt(); bottomMargin = (40 * dp).toInt()
            }
        }
        val result = TextView(this).apply {
            textSize = Type.footnote; setTextColor(Palette.labelSecondary)
            setLineSpacing(0f, Type.lineSpacing)
            typeface = Typeface.MONOSPACE
        }
        resultCard.addView(result)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                result.text = scoreExplanation(e?.toString().orEmpty(), settings)
            }
        })
        list.addView(resultCard)
        result.text = scoreExplanation("", settings)
    }

    // ── the page's own components ────────────────────────────────────────────────────

    /** A small all-caps heading INSIDE a card, marking which rule the card is about. */
    private fun ruleHeading(text: String, tone: Int): TextView = TextView(this).apply {
        this.text = text; textSize = Type.caption
        setTypeface(typeface, Typeface.BOLD); setTextColor(tone); letterSpacing = 0.08f
        setPadding(0, 0, 0, dp(Space.xs))
    }

    /** The page's headline figure: a big numeral, a label, and the reasoning under it. */
    private fun bigNumberRow(number: String, label: String, why: String, tone: Int): View {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = number; textSize = 40f
                setTypeface(typeface, Typeface.BOLD); setTextColor(tone)
            })
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = Type.headline
                setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
                setLineSpacing(0f, Type.lineSpacing)
                setPadding((12 * dp).toInt(), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        box.addView(TextView(this).apply {
            text = why; textSize = Type.footnote; setTextColor(Palette.labelSecondary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        return box
    }

    /** A single "and this is also true" line inside a card. Never a two-column squeeze. */
    private fun smallRule(text: String, on: Boolean): TextView = TextView(this).apply {
        this.text = (if (on) "•  " else "○  ") + text
        textSize = Type.footnote
        setTextColor(if (on) Palette.labelSecondary else Palette.labelTertiary)
        setLineSpacing(0f, Type.lineSpacing)
        setPadding(0, dp(Space.xxs), 0, dp(Space.xxs))
    }

    /** A worked example pair: what scores, and the near-identical thing that doesn't. */
    private fun exampleLines(parent: LinearLayout, scores: String, passes: String) {
        val dp = resources.displayMetrics.density
        if (scores.isNotBlank()) parent.addView(TextView(this).apply {
            text = "SCORES:  $scores"; textSize = Type.caption
            setTextColor(Palette.dangerText); setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
        if (passes.isNotBlank()) parent.addView(TextView(this).apply {
            text = "PASSES:  $passes"; textSize = Type.caption
            setTextColor(Palette.successText); setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, (3 * dp).toInt(), 0, 0)
        })
    }

    /**
     * One row of the "what each word is worth" table: status, name, points badge, how it
     * behaves, how many words, and a way in to the list.
     */
    private fun filterGroupCard(
        g: FilterCatalogue.Group,
    ): View {
        val dp = resources.displayMetrics.density
        val (badgeFill, badgeText) = pointsTone(g.points)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = tappableBg(Palette.glass)
            elevation = dpf(1f)
            val p = dp(Space.md); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(Space.xs) }
            isClickable = true; isFocusable = true
            setOnClickListener { showFilterGroup(g) }
            pressable()
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = g.name; textSize = Type.headline
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Palette.label)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(badge(
                if (g.points > 0) "${g.points} pts" else "no score", badgeFill, badgeText,
            ))
            addView(TextView(this@MainActivity).apply {
                text = "›"; textSize = 20f; setTextColor(Palette.labelQuaternary)
                setPadding(dp(Space.xs), 0, 0, 0)
            })
        })
        card.addView(TextView(this).apply {
            text = g.what; textSize = Type.footnote
            setTextColor(Palette.labelSecondary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, dp(Space.xxs), 0, 0)
        })
        if (g.examples.isNotBlank()) card.addView(TextView(this).apply {
            text = g.examples; textSize = Type.footnote; setTextColor(Palette.labelTertiary)
            setPadding(0, dp(Space.xxs) / 2, 0, 0)
        })
        exampleLines(card, g.scores, g.passes)
        // "Blocks on its own" and "only counts in context" are real rules. "Needs a second
        // word" is not - it is just the arithmetic of the score against the bar, and saying
        // it out loud only invites the question "in what context?".
        val rule = when (g.behaviour) {
            FilterCatalogue.Behaviour.BLOCKS_ALONE -> "One is enough on its own"
            FilterCatalogue.Behaviour.ONLY_IN_CONTEXT -> "Only counts in context"
            else -> null
        }
        card.addView(TextView(this).apply {
            text = listOfNotNull(rule, "${g.entries().size} entries").joinToString("  ·  ")
            textSize = Type.caption
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(behaviourTone(g.behaviour))
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, dp(Space.xs), 0, 0)
        })
        card.addView(modeDots(MODE_CHIPS.map { activeInMode(g, it.id) }))
        return card
    }

    /** A context-gated group, spelled out: the trigger, then a worked pair. */
    private fun gateCard(g: FilterCatalogue.Group): View {
        val dp = resources.displayMetrics.density
        val card = glassCard(Space.md)
        card.addView(TextView(this).apply {
            text = g.name; textSize = Type.headline
            setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
        })
        card.addView(TextView(this).apply {
            text = "Worth ${g.points} pts - but ONLY if there is ${g.gate}.\n" +
                "Otherwise it is worth nothing at all."
            textSize = Type.footnote; setTextColor(Palette.warningText)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, (4 * dp).toInt(), 0, 0)
        })
        exampleLines(card, g.scores, g.passes)
        return card
    }

    /** The exceptions explainer - tappable, because "how does that work" is the question. */
    private fun exceptionsCard(): View {
        val dp = resources.displayMetrics.density
        val g = FilterCatalogue.EXCEPTIONS
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = tappableBg(Palette.glass); elevation = dpf(1f)
            val p = dp(Space.md); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(Space.xs) }
            isClickable = true; isFocusable = true
            setOnClickListener { showFilterGroup(g) }
            pressable()
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "An innocent neighbour DELETES the match"
                textSize = Type.headline
                setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
                setLineSpacing(0f, Type.lineSpacing)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(badge("${g.entries().size} words", Palette.successSoft, Palette.successText))
            addView(TextView(this@MainActivity).apply {
                text = "›"; textSize = 20f; setTextColor(Palette.labelQuaternary)
                setPadding(dp(Space.xs), 0, 0, 0)
            })
        })
        card.addView(TextView(this).apply {
            text = "Not \"scores less\" - scores nothing at all."
            textSize = Type.footnote; setTextColor(Palette.successText)
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
        })
        card.addView(TextView(this).apply {
            text = "It is a hand-written list. Each banned word carries its own set of " +
                "innocent neighbours, and if one of them turns up within " +
                "${FilterTuning.EXCEPTION_WINDOW} words, that match is thrown away.\n\n" +
                "\"naked mole rat\"  →  0\n" +
                "\"nude lipstick\"  →  0\n" +
                "\"vaginal health\"  →  0\n" +
                "\"summa cum laude\"  →  0\n\n" +
                "Looked up by the matched word AND its family head, so the entry for " +
                "\"nude\" also covers \"nudes\" and \"nudity\"."
            textSize = Type.footnote; setTextColor(Palette.labelSecondary)
            setLineSpacing(0f, Type.lineSpacing)
        })
        return card
    }

    /** A reference list: name, count badge, what it does, and a way in to read it. */
    private fun listCard(
        name: String, count: Int, unit: String, what: String, note: String, tone: Int,
        entries: (() -> List<Entry>)? = null,
        openPage: (() -> Unit)? = null,
    ): View {
        val dp = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val tappable = entries != null || openPage != null
            background = if (tappable) tappableBg(Palette.glass) else glassBg()
            elevation = dpf(1f)
            val p = dp(Space.md); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(Space.xs) }
            if (tappable) {
                isClickable = true; isFocusable = true
                setOnClickListener {
                    if (openPage != null) openPage()
                    else showWordList(name, if (count >= 0) "$count $unit" else unit, what, note, entries!!())
                }
                pressable()
            }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = name; textSize = Type.headline
                setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
                setLineSpacing(0f, Type.lineSpacing)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(badge(
                if (count >= 0) "$count $unit" else unit,
                if (tone == Palette.dangerText) Palette.dangerSoft
                else if (tone == Palette.successText) Palette.successSoft
                else if (tone == Palette.warningText) Palette.warningSoft
                else Palette.surfaceSunken,
                tone,
            ))
            if (entries != null || openPage != null) addView(TextView(this@MainActivity).apply {
                text = "›"; textSize = 20f; setTextColor(Palette.labelQuaternary)
                setPadding(dp(Space.xs), 0, 0, 0)
            })
        })
        card.addView(TextView(this).apply {
            text = what; textSize = Type.footnote; setTextColor(Palette.labelSecondary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, dp(Space.xxs), 0, 0)
        })
        if (note.isNotBlank()) card.addView(TextView(this).apply {
            text = note; textSize = Type.caption; setTextColor(Palette.labelTertiary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, dp(Space.xs), 0, 0)
        })
        return card
    }

    /** A multiplier / cap / evasion card: name, the effect as a badge, what it does. */
    private fun scalerCard(s: FilterCatalogue.Scaler): View {
        val dp = resources.displayMetrics.density
        val hasList = s.entries != null
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (hasList) tappableBg(Palette.glass) else glassBg()
            elevation = dpf(1f)
            val p = dp(Space.md); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(Space.xs) }
            if (hasList) {
                isClickable = true; isFocusable = true
                setOnClickListener {
                    showWordList(s.name, s.effect, s.what, s.note, entryList(s.entries!!()))
                }
                pressable()
            }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = s.name; textSize = Type.headline
                setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
                setLineSpacing(0f, Type.lineSpacing)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(badge(s.effect, Palette.tintSoft, Palette.tintDeep))
            if (hasList) addView(TextView(this@MainActivity).apply {
                text = "›"; textSize = 20f; setTextColor(Palette.labelQuaternary)
                setPadding(dp(Space.xs), 0, 0, 0)
            })
        })
        card.addView(TextView(this).apply {
            text = s.what; textSize = Type.footnote; setTextColor(Palette.labelSecondary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, dp(Space.xxs), 0, 0)
        })
        if (s.note.isNotBlank()) card.addView(TextView(this).apply {
            text = s.note; textSize = Type.caption; setTextColor(Palette.labelTertiary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, dp(Space.xs), 0, 0)
        })
        return card
    }

    /** One watched SCREEN: where it is, when the guard is armed, and the text we match. */
    private fun guardedScreenCard(g: AppConfig.GuardedScreen): View {
        val dp = resources.displayMetrics.density
        val bounce = g.action == AppConfig.GuardAction.BOUNCE
        val card = glassCard(Space.md)
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = g.name; textSize = Type.headline
                setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
                setLineSpacing(0f, Type.lineSpacing)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(badge(
                if (bounce) "SENT HOME" else "COVERED",
                if (bounce) Palette.warningSoft else Palette.dangerSoft,
                if (bounce) Palette.warningText else Palette.dangerText,
            ))
        })
        card.addView(TextView(this).apply {
            text = "in ${g.where}"; textSize = Type.footnote; setTextColor(Palette.labelTertiary)
            setPadding(0, dp(Space.xxs), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = g.why; textSize = Type.footnote; setTextColor(Palette.labelSecondary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, dp(Space.xs), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "WHEN:  ${g.whenArmed}"; textSize = Type.caption
            setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.tintDeep)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, dp(Space.xs), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "MATCHED ON:\n" + g.matches.joinToString("\n") { "  $it" }
            textSize = Type.caption; setTextColor(Palette.labelTertiary)
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, dp(Space.xs), 0, 0)
        })
        return card
    }

    /**
     * The "Try it" box's answer: the score, the verdict at each bar, and the full breakdown -
     * including for text that does NOT block, which is usually the more interesting case when
     * you are tuning ("why is this sitting on 12?").
     *
     * Two scoring passes, not one per verdict: explain() gives the score and the breakdown,
     * and the web verdict falls out of comparing it to webBar(). Only the in-app verdict
     * needs its own pass, because it also weighs how much text is on screen. This runs on
     * every keystroke, so it is worth not doing five times over.
     */
    private fun scoreExplanation(
        text: String, settings: BorderlineScorer.Settings,
    ): String {
        if (text.isBlank()) return "Nothing typed yet."
        val ex = BorderlineScorer.explain(text, null, null, settings)
            ?: return "Scores 0.\nNothing in there is on any list."
        val bar = BorderlineScorer.webBar()
        val web = ex.score >= bar
        val inApp = BorderlineScorer.evaluateInApp(text, null, null, settings) != null
        val sb = StringBuilder()
        sb.append("SCORE ${ex.score}\n")
        sb.append("read as a page TITLE, so every hit\n")
        sb.append("counts x${FilterTuning.TITLE_URL_MULTIPLIER}. The same words in\n")
        sb.append("body text would score about half.\n\n")
        sb.append(if (web) "BLOCKED" else "allowed").append("  as a web page (bar $bar)\n")
        sb.append(if (inApp) "BLOCKED" else "allowed")
            .append("  on an app screen (bar ${FilterTuning.APP_THRESHOLD})\n")
        if (ex.suspicious > 0) {
            sb.append("\n${ex.suspicious} suspicious near-miss spelling(s)\n")
        }
        if (ex.contributions.isNotEmpty()) {
            sb.append("\nWHAT SCORED\n")
            for (c in ex.contributions) {
                sb.append("  \"${c.word}\"\n")
                sb.append("    ${c.tier}, ${c.count}x, ${c.points} pts, ${c.pct}%")
                if (c.capped) sb.append(", capped at ${FilterTuning.PER_WORD_CAP}")
                sb.append('\n')
            }
            sb.append("\nThe block screen would name:\n  ")
            sb.append(BorderlineScorer.topContributors(ex.contributions).joinToString(", ") { it.word })
        }
        return sb.toString().trimEnd()
    }

    /** The words inside one group. Back returns to the breakdown page, where you left it. */
    private fun showFilterGroup(group: FilterCatalogue.Group) {
        val head = buildString {
            append(if (group.points > 0) "${group.points} points per hit" else "Scores nothing")
            if (group.behaviour == FilterCatalogue.Behaviour.BLOCKS_ALONE) {
                append("  ·  one is enough on its own")
            }
            group.gate?.let { append("\nOnly counts with $it.") }
            val on = MODE_CHIPS.filter { activeInMode(group, it.id) }.map { AppConfig.modeName(it.id) }
            append("\nOn in: " + if (on.isEmpty()) "no mode" else on.joinToString(", "))
        }
        showWordList(group.name, head, group.what, group.note, entryList(group.entries()))
    }

    /**
     * THE BLACKLIST, on its own page: four categories, each a pair of plain text files.
     * Split out of the word-filter page because none of it is word scoring - these are flat
     * lists that block outright, and mixing them into the scoring explanation made both
     * harder to read.
     */
    private fun showBlacklistPage() {
        filterScrollY = filterScroll?.scrollY ?: filterScrollY
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText("Blacklist"))
        root.addView(TextView(this).apply {
            text = "Blocked outright in every mode above Off. No score, no threshold, no appeal."
            textSize = Type.callout; setTextColor(Palette.labelSecondary)
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true; addView(list)
        })
        setContentWithThumb(root) { showFilterBreakdown() }

        for (cat in BlockedCategories.ALL) {
            val apps = BlockedCategories.apps(cat)
            val doms = BlockedCategories.domains(cat)
            list.addView(TextView(this).apply {
                text = cat.title.uppercase(); textSize = Type.caption
                setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.labelTertiary)
                letterSpacing = 0.06f
                setPadding((2 * dp).toInt(), (20 * dp).toInt(), 0, (6 * dp).toInt())
            })
            list.addView(listCard(
                "${cat.appsTitle} (apps)", apps.size, "apps", cat.why, "From ${cat.appsFile}.",
                Palette.dangerText,
                entries = { apps.entries.sortedBy { it.key }.map { Entry(it.key, it.value) } },
            ))
            list.addView(listCard(
                "${cat.appsTitle} (websites)", doms.size, "hosts",
                "The web half of the same category.", "From ${cat.domainsFile}.",
                Palette.dangerText,
                entries = { doms.sorted().map { Entry(it) } },
            ))
        }
        list.addView(TextView(this).apply {
            text = "An app and its website are SEPARATE decisions. Facebook and YouTube are " +
                "blocked as apps but reachable as sites, because in a browser the address is " +
                "visible, the page text is scored and the image add-on is watching from the " +
                "inside. None of that is true inside an app. To ban one outright, add its " +
                "domain to that category's domains file."
            textSize = Type.footnote; setTextColor(Palette.labelTertiary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding((2 * dp).toInt(), (20 * dp).toInt(), (2 * dp).toInt(), (40 * dp).toInt())
        })
    }

    /**
     * One row of a reference list: a name, and optionally the technical detail under it.
     * "Instagram" / "com.instagram.android"; "naked" / "mole, rat, eye, truth".
     */
    data class Entry(val main: String, val sub: String? = null)

    /**
     * A list of entries with its explanation above it. Shared by the word groups, the
     * multipliers and every reference list on the page.
     *
     * Rendered as ONE TextView with spans rather than a view per row: some of these run to
     * several hundred entries, and a view each makes the page crawl on the way in. The spans
     * give the same result - name in bold, detail indented and quiet underneath - for the
     * cost of a single layout pass.
     */
    private fun showWordList(
        title: String, head: String, what: String, note: String, entries: List<Entry>,
    ) {
        filterScrollY = filterScroll?.scrollY ?: filterScrollY
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText(title))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true; addView(list)
        })
        setContentWithThumb(root) { showFilterBreakdown() }

        val card = glassCard(Space.md)
        card.addView(TextView(this).apply {
            text = head; textSize = Type.callout
            setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.label)
            setLineSpacing(0f, Type.lineSpacing)
        })
        card.addView(TextView(this).apply {
            text = what; textSize = Type.footnote; setTextColor(Palette.labelSecondary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        if (note.isNotBlank()) {
            card.addView(separator())
            card.addView(TextView(this).apply {
                text = note; textSize = Type.footnote; setTextColor(Palette.labelTertiary)
                setLineSpacing(0f, Type.lineSpacing)
            })
        }
        list.addView(card)

        list.addView(TextView(this).apply {
            text = "${entries.size} ENTRIES"; textSize = Type.caption
            setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.labelTertiary)
            letterSpacing = 0.06f
            setPadding((2 * dp).toInt(), (18 * dp).toInt(), 0, (8 * dp).toInt())
        })
        val words = glassCard(Space.md).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams)
                .apply { bottomMargin = (40 * dp).toInt() }
        }
        words.addView(TextView(this).apply {
            text = spannedEntries(entries)
            textSize = Type.callout; setTextColor(Palette.label)
            setLineSpacing(0f, Type.lineSpacing)
        })
        list.addView(words)
    }

    /**
     * Plain strings as entries. A line carrying a "word: detail" split - which is exactly
     * the shape of exceptions.txt and family_groups.txt - becomes a bold word with its
     * detail indented underneath, because a wall of "naked: mole, rat, eye, truth, gun"
     * lines is unreadable and the word is the thing you are scanning for.
     */
    private fun entryList(items: List<String>): List<Entry> = items.map { line ->
        val colon = line.indexOf(':')
        if (colon > 0) Entry(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
        else Entry(line)
    }

    /** Name in bold; detail, if any, indented and quiet on the next line. */
    private fun spannedEntries(entries: List<Entry>): CharSequence {
        if (entries.isEmpty()) return "(empty)"
        val sb = android.text.SpannableStringBuilder()
        for ((i, e) in entries.withIndex()) {
            if (i > 0) sb.append("\n")
            val from = sb.length
            sb.append(e.main)
            sb.setSpan(
                android.text.style.StyleSpan(Typeface.BOLD), from, sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val sub = e.sub
            if (!sub.isNullOrBlank()) {
                sb.append("\n     ")
                val subFrom = sb.length
                sb.append(sub)
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(Palette.labelTertiary),
                    subFrom, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                sb.setSpan(
                    android.text.style.RelativeSizeSpan(0.88f),
                    subFrom, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return sb
    }

    private fun showDevConsole() {
        inSubPage = true
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText(getString(R.string.dev_console_title)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); addView(list)
        })
        setContentWithThumb(root) { setupMainScreen() }

        fun header(t: String) = list.addView(TextView(this).apply {
            text = t.uppercase(); textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.labelTertiary)
            setPadding((2 * dp).toInt(), (16 * dp).toInt(), 0, (6 * dp).toInt())
        })
        fun row(label: String, value: String, on: Boolean? = null) = list.addView(TextView(this).apply {
            val dot = when (on) { true -> "\u25CF  "; false -> "\u25CB  "; null -> "" }
            text = "$dot$label:  $value"; textSize = 14f
            setTextColor(when (on) { true -> Palette.success; false -> Palette.labelTertiary; null -> Palette.labelSecondary })
            setPadding(0, (5 * dp).toInt(), 0, (5 * dp).toInt())
        })

        val modeId = Mode.current(this)
        val spec = AppConfig.MODES.firstOrNull { it.id == modeId }
        header("Mode")
        row("Current mode", spec?.displayName ?: modeId)
        row("Week-long strict lock", if (Mode.isLocked(this)) "locked - ${Mode.timeLeft(this)}" else "off", Mode.isLocked(this))
        row("Page flag threshold", "${spec?.flagThreshold ?: "-"} (score \u2265 this is flagged)")
        row("Flag when lying down", if (spec?.flagLyingDown == true) "on" else "off", spec?.flagLyingDown == true)
        row("Night guard light trigger", spec?.nightGuardLuxBelow?.let { "\u2264 ${it.toInt()} lux" } ?: "off",
            spec?.nightGuardLuxBelow != null)

        // DEBUG BUILDS ONLY - the only way past the two ratchets, and it exists so a test
        // device can be moved between modes without wiping its data. Mode.forceMode is
        // itself inert in a release build; this row is hidden there as well, because an
        // override you can see but not use reads as a door (see modeSpinner's note on
        // options that are always refused).
        if (BuildConfig.IS_TESTING) {
            list.addView(TextView(this).apply {
                text = "Set mode (debug build)"; textSize = 12f
                setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.labelTertiary)
                setPadding((2 * dp).toInt(), (12 * dp).toInt(), 0, (4 * dp).toInt())
            })
            AppConfig.MODES.forEach { m ->
                list.addView(TextView(this).apply {
                    val here = m.id == modeId
                    text = (if (here) "\u25CF  " else "\u25CB  ") + m.displayName
                    textSize = 14f
                    setTextColor(if (here) Palette.success else Palette.tint)
                    setPadding(0, (7 * dp).toInt(), 0, (7 * dp).toInt())
                    isClickable = !here; isFocusable = !here
                    if (!here) setOnClickListener {
                        if (Mode.forceMode(this@MainActivity, m.id)) {
                            Toast.makeText(this@MainActivity,
                                "Mode forced to ${m.displayName}", Toast.LENGTH_SHORT).show()
                            showDevConsole()
                        }
                    }
                })
            }
        }

        header("Blocking")
        row("Reels / shorts / feeds", if (ShortForm.enabled()) "blocked" else "allowed", ShortForm.enabled())
        row("Active block rules", "${BlockRules.all().size}")
        row("Domain strike threshold", "${AppConfig.DOMAIN_STRIKE_THRESHOLD} strikes/day \u2192 block")
        row("Domain block length", "${AppConfig.DOMAIN_BLOCK_MS / 60000} min")
        row("Safe apps (skip scan)", "${AppConfig.SAFE_APPS.size}")
        row("Greylisted apps (time-limited)", "${AppConfig.GREYLIST_APPS.size}")
        row("Trusted domains (skip heuristic)", "${AppConfig.SAFE_DOMAINS.size}")
        row("Detections to close an app",
            "${RepeatGate.HITS_KNOWN} known / ${RepeatGate.HITS_REPEAT} blocked before / " +
                "${RepeatGate.HITS_NEW} new")
        row("Waits between detections",
            "${RepeatGate.WAIT_FIRST_MS / 1000}s then ${RepeatGate.WAIT_SECOND_MS / 1000}s " +
                "(ignored, not counted)")
        row("Open detection cases", "${RepeatGate.summary().size}")

        header("Where you are")
        row("Home point", if (HomeArea.isSet(this)) "saved" else "not set", HomeArea.isSet(this))
        row("Location access", when (HomeArea.access(this)) {
            HomeArea.Access.ALWAYS -> "all the time"
            HomeArea.Access.WHILE_USING -> "only while the app is open"
            HomeArea.Access.NONE -> "not granted"
        }, HomeArea.access(this) == HomeArea.Access.ALWAYS)
        row("Verdict", HomeAreaContext.label() +
            (if (HomeAreaContext.distanceM >= 0f) " (${Math.round(HomeAreaContext.distanceM)} m)" else ""))
        row("One detection is enough here",
            if (HomeRule.oneDetectionIsEnough(this)) "YES - super hardcore, at home" else "no",
            HomeRule.oneDetectionIsEnough(this))

        header("Permissions")
        row("Page monitoring", if (isAccessibilityEnabled()) "on" else "off", isAccessibilityEnabled())
        row("Block overlay", if (Settings.canDrawOverlays(this)) "on" else "off", Settings.canDrawOverlays(this))
        val lock = UninstallGuard.isEnabled(this) && UninstallGuard.isAdminActive(this)
        row("Uninstall lock", if (lock) "on" else "off", lock)
        // Why the App-info page may not be bouncing: something on it is still ungranted, and
        // that page is the only place left to grant it. Named rather than just flagged - see
        // GrantWindow.
        val pending = GrantWindow.outstanding(this)
        row("App-info page bounce",
            if (pending.isEmpty()) "armed"
            else "STOOD DOWN - still to grant: ${pending.joinToString(", ")}",
            pending.isEmpty())

        header("Active timers")
        row("App lockdown", if (Lockdown.isActive(this)) "${minLeft(Lockdown.remaining(this))} left" else "none", Lockdown.isActive(this))
        row("Unlock window", if (LoosenWindow.isActive(this)) "${minLeft(LoosenWindow.remaining(this))} left" else "none", LoosenWindow.isActive(this))
        row("Unlock wait", if (LoosenWait.isActive(this)) "${minLeft(LoosenWait.remaining(this))} left" else "none", LoosenWait.isActive(this))
        row("Unlocks left (lifetime)", "${LoosenLimit.remaining(this)} of ${LoosenLimit.LIFETIME_MAX}")

        header("Build")
        row("Dev mode", if (AppConfig.DEV_MODE) "on" else "off", AppConfig.DEV_MODE)
        row("Premium mode", if (Premium.isOn(this)) "on (dev override)" else "off", Premium.isOn(this))
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
        return if (m > 0) Units.mins(this, m) else Units.secs(this, s)
    }

    private fun setDot(view: TextView, label: String, on: Boolean) {
        view.text = "${if (on) "\u25CF" else "\u25CB"}  $label - ${if (on) "On" else "Off"}"
        view.setTextColor(if (on) Palette.success else Palette.labelTertiary)
    }

    /** A self-contained mode dropdown (used on the sexual-urge page). Drives Mode
     *  directly and resets itself if strict is locked. Does NOT touch dashboard views. */
    private fun modeSpinner(): Spinner {
        val dp = resources.displayMetrics.density
        val sp = Spinner(this)
        // Looked like inert text before, so nobody realised the mode was theirs to change.
        // An outlined pill reads as a control.
        // ...except once the ratchet has closed, when there is genuinely nothing to pick:
        // it goes grey and inert rather than pretending to be a live control (see below).
        val ratchetShut = Mode.everSuperHardcore(this)
        sp.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Radius.card * dp
            setColor(if (ratchetShut) Palette.surfaceSunken else Palette.surface)
            setStroke((1.5f * dp).toInt(), if (ratchetShut) Palette.labelQuaternary else Palette.tint)
        }
        val px = (14 * dp).toInt(); val py = (6 * dp).toInt()
        sp.setPadding(px, py, px, py)
        // THE RATCHET, MADE VISIBLE. Once Strict has been entered, Off is gone for good -
        // Mode.setMode has always refused it, but the picker went on offering it and then
        // snapped back with a toast. An option that is always refused is not a choice, it
        // is a trap: it reads as "you can still turn this off" right up until you can't.
        // Take it off the list instead, and say why underneath the picker.
        //
        // THE SUPER HARDCORE RATCHET is the same idea taken to its end: once that mode has
        // been entered, EVERY lower mode leaves the list for good and the picker stops
        // being a picker at all. Mode.setMode refuses them anyway; showing them greyed or
        // showing them at all would just be an invitation to keep trying the handle.
        val modes = AppConfig.MODES.filter {
            when {
                ratchetShut -> it.id == Mode.SUPERHARDCORE
                it.id == Mode.OFF && Mode.everStrict(this) -> false
                else -> true
            }
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes.map { modeDisplayName(it.id) })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = adapter
        fun curIdx() = modes.indexOfFirst { it.id == Mode.current(this) }.coerceAtLeast(0)
        sp.setSelection(curIdx())
        sp.isEnabled = !ratchetShut
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val chosen = modes.getOrNull(pos)?.id ?: return
                if (chosen == Mode.current(this@MainActivity)) return
                // Super hardcore needs the uninstall lock first - a mode this strict is
                // pointless if the app can just be deleted in a weak moment.
                if (chosen == Mode.SUPERHARDCORE &&
                    !(UninstallGuard.isEnabled(this@MainActivity) && UninstallGuard.isAdminActive(this@MainActivity))) {
                    Toast.makeText(this@MainActivity,
                        getString(R.string.mode_needs_lock_toast), Toast.LENGTH_LONG).show()
                    sp.setSelection(curIdx())
                    showLockPrompt { }
                    return
                }
                // A ONE-WAY DOOR gets one clear question before it shuts, and it is asked
                // HERE - ahead of the permission gate below, so the answer is given before
                // the choice can be parked rather than after it has quietly landed. Not a
                // nag and not a friction tax: there is no undo behind this one, so the user
                // has to be able to say afterwards that they knew exactly what they picked.
                if (chosen == Mode.SUPERHARDCORE) {
                    sp.setSelection(curIdx())   // keep showing the real mode until they say yes
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.mode_super_confirm_title))
                        .setMessage(getString(R.string.mode_super_confirm_msg))
                        .setPositiveButton(getString(R.string.mode_super_confirm_yes)) { _, _ ->
                            if (!corePermsGranted()) {
                                pendingMode = Mode.SUPERHARDCORE   // lands from updateScreen()
                                updateScreen()
                            } else if (Mode.setMode(this@MainActivity, Mode.SUPERHARDCORE)) {
                                Toast.makeText(this@MainActivity,
                                    getString(R.string.mode_on_toast, modeDisplayName(Mode.SUPERHARDCORE)),
                                    Toast.LENGTH_SHORT).show()
                                // Redrawn rather than nudged: the picker itself changes shape
                                // once the ratchet shuts (every lower mode leaves the list).
                                if (!maybeAskAboutHouse(Mode.SUPERHARDCORE)) showReportScreen()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    return
                }
                // Anything above Off makes the two core permissions MANDATORY. Don't
                // COMMIT the mode until they're actually on: park it and gate. Setting it
                // first and gating afterwards meant the back button walked away from the
                // gate still in the new mode, with nothing enforcing it.
                if (chosen != Mode.OFF && !corePermsGranted()) {
                    pendingMode = chosen
                    sp.setSelection(curIdx())   // shows the real mode until the change lands
                    updateScreen()
                    return
                }
                if (Mode.setMode(this@MainActivity, chosen)) {
                    if (chosen == Mode.OFF) {
                        Toast.makeText(this@MainActivity,
                            getString(R.string.mode_off_toast), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.mode_on_toast, modeDisplayName(chosen)), Toast.LENGTH_SHORT).show()
                    }
                    // Strict and above can use where you are, and this is the moment to ask
                    // - the user has just decided to be serious, which is exactly when
                    // setting the house up sounds like a good idea rather than an intrusion.
                    maybeAskAboutHouse(chosen)
                } else if (chosen == Mode.OFF && Mode.everStrict(this@MainActivity) && !Mode.isLocked(this@MainActivity)) {
                    // THE RATCHET refused it: they've been Strict at some point, so Off is
                    // gone from this install for good. Still a "turn the blocking off"
                    // attempt, so it is recorded like one.
                    BypassWatch.record(this@MainActivity, BypassWatch.Reason.LEAVE_STRICT)
                    Toast.makeText(this@MainActivity, getString(R.string.mode_ratchet_toast), Toast.LENGTH_LONG).show()
                    sp.setSelection(curIdx())
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

    /**
     * The "Where you are" console on the home page, just above STATUS.
     *
     * Two kinds of row, coarse first: the HOUSE (GPS - at the house or out), then one row
     * per ROOM (beacons). Before the user has beacons the room half is the door into the
     * purchase/set-up flow; afterwards it is one dot per room: green = set up and able to
     * receive, amber = set up but we can't receive right now (Bluetooth off / permission
     * revoked), grey = not set up yet.
     *
     * The house row is ALWAYS here, beacons or not - it needs no hardware, it is the only
     * place outside Developer tools that the house is set up from, and in Super hardcore it
     * is the thing deciding whether one word closes an app (HomeRule).
     */
    private fun sensorsConsole(): View {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (24 * dp).toInt() }
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.sensors_console_header); textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.labelTertiary)
            setPadding((2 * dp).toInt(), 0, 0, (6 * dp).toInt())
        })
        box.addView(houseRow())
        if (!RoomBeacons.ownsSensors(this)) {
            box.addView(TextView(this).apply {
                text = getString(R.string.sensors_none)
                textSize = 14f; setTextColor(Palette.labelTertiary)
                isClickable = true; isFocusable = true
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                setOnClickListener { showSensorGate() }
            })
            return box
        }
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
            .adapter?.isEnabled == true
        val canReceive = bt && RoomBeacons.hasPermissions(this)
        if (RoomBeacons.rooms(this).isEmpty()) {
            box.addView(TextView(this).apply {
                text = getString(R.string.sensors_no_rooms)
                textSize = 14f; setTextColor(Palette.labelTertiary)
                isClickable = true; isFocusable = true
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                setOnClickListener { showRoomBeaconDebug() }
            })
            return box
        }
        for (room in RoomBeacons.rooms(this)) {
            val calibrated = RoomBeacons.isCalibrated(this, room)
            val colour: Int; val label: String
            when {
                !calibrated -> { colour = Palette.labelTertiary; label = getString(R.string.sensors_status_notsetup) }
                !canReceive -> { colour = Palette.warning; label = getString(R.string.sensors_status_nodata) }
                else -> { colour = Palette.success; label = getString(R.string.sensors_status_on) }
            }
            box.addView(TextView(this).apply {
                text = getString(R.string.sensors_room_row, room.replaceFirstChar { it.uppercase() }, label)
                textSize = 14f; setTextColor(colour)
                isClickable = true; isFocusable = true
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                setOnClickListener { showRoomBeaconDebug() }
            })
        }
        return box
    }

    /**
     * The house row: whether the phone knows where home is, and - once it does - where it
     * currently thinks it is.
     *
     * Two lines, because two different things can be wrong and they need different fixes:
     * the STATE (not set up / set up but starved of location / live) and, under it, what
     * that means for blocking in the mode the user is actually in. A row that only said
     * "At home" would be a readout; this one is the rule.
     */
    private fun houseRow(): View {
        val dp = resources.displayMetrics.density
        val set = HomeArea.isSet(this)
        val access = HomeArea.access(this)
        val verdict = HomeAreaContext.verdict
        val colour: Int; val state: String
        when {
            !set -> { colour = Palette.labelTertiary; state = getString(R.string.house_state_notset) }
            access == HomeArea.Access.NONE || !HomeArea.locationEnabled(this) ->
                { colour = Palette.warning; state = getString(R.string.house_state_nolocation) }
            // Granted only "while using": it reads on this screen and goes dark the moment
            // the user leaves, which is precisely when it was supposed to be watching.
            access == HomeArea.Access.WHILE_USING ->
                { colour = Palette.warning; state = getString(R.string.house_state_foreground) }
            verdict == HomeArea.Verdict.HOME ->
                { colour = Palette.success; state = getString(R.string.house_state_home) }
            verdict == HomeArea.Verdict.AWAY ->
                { colour = Palette.success; state = getString(R.string.house_state_away) }
            // Set up, permitted, and the watch is subscribed: this is WORKING, and it says
            // so in green, whatever the current mode does or doesn't do with the answer. A
            // grey row for a feature that is running reads as "broken" or "off", and the
            // gap between the reading and the next reading is not either of those. (The
            // verdict falls back to unknown between fixes; the state does not.)
            HomeAreaWatch.armed ->
                { colour = Palette.success; state = getString(R.string.house_state_checking) }
            else -> { colour = Palette.labelTertiary; state = getString(R.string.house_state_unsure) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true; isFocusable = true
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener { houseBack = { setupHomeScreen() }; showHouseArea() }
        }
        row.addView(TextView(this).apply {
            text = getString(R.string.house_row, state)
            textSize = 14f; setTextColor(colour)
        })
        row.addView(TextView(this).apply {
            text = when {
                !set && Mode.isSuperHardcore(this@MainActivity) -> getString(R.string.house_sub_super_unset)
                !set -> getString(R.string.house_sub_unset)
                Mode.isSuperHardcore(this@MainActivity) ->
                    if (HomeRule.atHome()) getString(R.string.house_sub_super_active)
                    else getString(R.string.house_sub_super_idle)
                else -> getString(R.string.house_sub_other_modes)
            }
            textSize = 12f; setTextColor(Palette.labelTertiary)
            setLineSpacing(0f, Type.lineSpacing)
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        return row
    }

    // The gate in front of the room-detection set-up: do they actually have beacons yet?
    private fun showSensorGate() {
        inSubPage = true
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText(getString(R.string.sensors_gate_title)))
        root.addView(TextView(this).apply {
            text = getString(R.string.sensors_gate_body)
            textSize = 15f; setTextColor(Palette.labelSecondary); setPadding(0, (4 * dp).toInt(), 0, (16 * dp).toInt())
        })
        root.addView(bigChoice(getString(R.string.sensors_gate_yes), Palette.labelTertiary) {
            RoomBeacons.setOwnsSensors(this, true)
            showRoomBeaconDebug()
        })
        root.addView(bigChoice(getString(R.string.sensors_gate_no), Palette.labelTertiary) {
            showSensorPitch()
        })
        setContentWithThumb(root) { setupHomeScreen() }
    }

    // The two-minute pitch for someone without beacons, then the (future) shop door.
    private fun showSensorPitch() {
        inSubPage = true
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText(getString(R.string.sensors_gate_title)))
        root.addView(TextView(this).apply {
            text = getString(R.string.sensors_pitch_body)
            textSize = 15f; setTextColor(Palette.labelSecondary); setLineSpacing(0f, 1.35f)
            setPadding(0, (4 * dp).toInt(), 0, (18 * dp).toInt())
        })
        root.addView(bigChoice(getString(R.string.sensors_pitch_order), Palette.tint) { showSensorOrderPage() })
        setContentWithThumb(root) { showSensorGate() }
    }

    private fun showSensorOrderPage() {
        inSubPage = true
        val dp = resources.displayMetrics.density; val pad = (Space.page * dp).toInt()
        val root = vbox(pad)
        root.addView(titleText(getString(R.string.sensors_order_title)))
        root.addView(TextView(this).apply {
            text = getString(R.string.sensors_order_body)
            textSize = 16f; setTextColor(Palette.labelSecondary); setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        setContentWithThumb(root) { showSensorPitch() }
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
            text = getString(R.string.status_header); textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.labelTertiary)
            setPadding((2 * dp).toInt(), 0, 0, (6 * dp).toInt())
        })
        fun row(label: String, on: Boolean, onClick: () -> Unit) = box.addView(TextView(this).apply {
            text = getString(R.string.status_row, if (on) "\u25CF" else "\u25CB", label, getString(if (on) R.string.status_on_label else R.string.status_off_label))
            textSize = 14f; setTextColor(if (on) Palette.success else Palette.labelTertiary)
            isClickable = true; isFocusable = true; setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            setOnClickListener { onClick() }
        })
        row(getString(R.string.status_page_monitoring), isAccessibilityEnabled()) {
            openAccessibilitySettings()
        }
        row(getString(R.string.status_block_overlay), Settings.canDrawOverlays(this)) { requestOverlayPermission() }
        row(getString(R.string.status_uninstall_lock), UninstallGuard.isEnabled(this) && UninstallGuard.isAdminActive(this)) { toggleUninstallGuard() }
        // The add-on is the one thing here we cannot actually read the state of, so this row
        // shows what the user TOLD us in setup - and stays theirs to correct. Switching it off
        // is honest, not a loophole: it puts them straight back on the gate.
        row(getString(R.string.status_image_addon), BrowserSetup.extensionConfirmed(this)) {
            if (BrowserSetup.extensionConfirmed(this)) {
                BrowserSetup.setExtensionConfirmed(this, false)
                Toast.makeText(this, getString(R.string.status_addon_off_toast), Toast.LENGTH_LONG).show()
                updateScreen()
            } else {
                inPermissionFlow = true
                updateScreen()
            }
        }
        val timers = mutableListOf<String>()
        if (Lockdown.isActive(this)) timers.add(getString(R.string.status_lockdown, minLeft(Lockdown.remaining(this))))
        if (LoosenWindow.isActive(this)) timers.add(getString(R.string.status_unlock_window, minLeft(LoosenWindow.remaining(this))))
        if (LoosenWait.isActive(this)) timers.add(getString(R.string.status_unlock_wait, minLeft(LoosenWait.remaining(this))))
        if (Mode.isLocked(this)) timers.add(getString(R.string.status_week_strict, Mode.timeLeft(this)))
        if (timers.isNotEmpty()) box.addView(TextView(this).apply {
            text = timers.joinToString("\n"); textSize = 13f; setTextColor(Palette.labelTertiary)
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
        BlockRules.add(this, rule, BlockRules.Note(BlockRules.Origin.MANUAL))
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
