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


// NOTE: This whole module is intentionally kept in ONE file.
// These classes would normally live in separate files / sub-packages;
// they are consolidated here on purpose to make development easier.
// Major sections (// ===) mark what used to be sub-folders;
// subsections (// ---) mark what used to be separate files.
// Regenerate with merge_kt.py -- do not re-split by hand.

// =====================================================================================
// APP
// =====================================================================================


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
private fun showReportScreen() {
    onReportScreen = true
    inRelapseFlow = false
    inTemptationFlow = false
    inLoosenFlow = false
    stopLoosenTimer()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    // Colours are easy to change — just edit these four.
    root.addView(reportPane("Report an app/site", 0xFF34464E.toInt()) { onReportAppSite() })
    root.addView(reportPane("I feel temptation", 0xFF3E535C.toInt()) { onFeelTemptation() })
    root.addView(reportPane("I'm going to look anyway", 0xFF48606A.toInt()) { onLookAnyway() })
    root.addView(reportPane("Report relapse", 0xFF526D78.toInt()) { onReportRelapse() })
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
    Toast.makeText(this, "Report an app/site \u2014 coming soon", Toast.LENGTH_SHORT).show()
}
private fun onFeelTemptation() {
    startTemptationFlow()
}

// ── "I feel temptation" flow ───────────────────────────────────────────────
private enum class TStep { TRIGGER, SAW, URGE, RIDE, RERATE }

private var inTemptationFlow = false
private var temptationStep = 0
private var tTrigger: String? = null
private var tSaw: String? = null
private var tUrgeStart: Int? = null
private var tUrgeEnd: Int? = null

private var rideHandler: Handler? = null
private var rideRunnable: Runnable? = null
private var rideEndAt = 0L

private val DEFAULT_TRIGGERS = listOf(
    "Social media",
    "Saw something suggestive",
    "Bored / restless",
    "Stressed or overwhelmed",
    "Lonely",
    "Tired / can't sleep",
    "Lying in bed",
    "A thought or memory popped up",
    "Habit / autopilot",
    "Upset after something",
)
private val SAW_OPTIONS = listOf(
    "Nothing yet \u2014 just the urge",
    "Suggestive / partial (swimwear, lingerie\u2026)",
    "Explicit nudity",
    "Sexual or pornographic content",
    "A suggestive message or chat",
    "Someone in person",
    "A memory or fantasy",
)

private fun startTemptationFlow() {
    onReportScreen = true
    inTemptationFlow = true
    tTrigger = null; tSaw = null; tUrgeStart = null; tUrgeEnd = null
    temptationStep = 0
    renderTemptationStep()
}

private fun temptationAdvance() { stopRideTimer(); temptationStep++; renderTemptationStep() }

private fun temptationBack() {
    stopRideTimer()
    if (temptationStep <= 0) { inTemptationFlow = false; showReportScreen(); return }
    temptationStep--
    renderTemptationStep()
}

private fun renderTemptationStep() {
    stopRideTimer()
    temptationStep = temptationStep.coerceIn(0, TStep.values().lastIndex)
    when (TStep.values()[temptationStep]) {
        TStep.TRIGGER -> temptationTriggerStep()

        TStep.SAW -> reportChoiceScreen(
            "What did you see?", SAW_OPTIONS, onBack = ::temptationBack,
        ) { tSaw = it; temptationAdvance() }

        TStep.URGE -> urgeScaleScreen(
            "How strong is the urge right now?",
            "1 = barely there   \u00b7   10 = overwhelming",
            onBack = ::temptationBack,
        ) { tUrgeStart = it; temptationAdvance() }

        TStep.RIDE -> temptationRideScreen()

        TStep.RERATE -> urgeScaleScreen(
            "How strong is the urge now?",
            "Be honest \u2014 there's no wrong answer.",
            onBack = ::temptationBack,
        ) { tUrgeEnd = it; renderTemptationFeedback() }
    }
}

private fun temptationTriggerStep() {
    val opts = (DEFAULT_TRIGGERS + TriggerOptions.all(this)).distinct() + "Enter new\u2026"
    reportChoiceScreen("What set it off?", opts, onBack = ::temptationBack) { choice ->
        if (choice == "Enter new\u2026") promptCustomTrigger()
        else { tTrigger = choice; temptationAdvance() }
    }
}

private fun promptCustomTrigger() {
    val input = EditText(this).apply {
        hint = "What set it off?"
        inputType = InputType.TYPE_CLASS_TEXT
        val p = (20 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
    }
    AlertDialog.Builder(this)
        .setTitle("Add a trigger")
        .setView(input)
        .setPositiveButton("Add") { _, _ ->
            val name = input.text.toString().trim().replace("\n", " ")
            if (name.isNotEmpty()) {
                TriggerOptions.add(this, name)   // remembered for next time (max 20)
                tTrigger = name
                temptationAdvance()
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun urgeScaleScreen(
    title: String, subtitle: String, onBack: (() -> Unit)?, onPick: (Int) -> Unit,
) {
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    if (onBack != null) root.addView(backText(onBack))
    root.addView(titleText(title))
    root.addView(TextView(this).apply {
        text = subtitle; textSize = 13f; setTextColor(0xFF6B7075.toInt())
        setPadding(0, 0, 0, (20 * dp).toInt())
    })
    fun rowOf(range: IntRange): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in range) row.addView(Button(this).apply {
            text = "$n"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onPick(n) }
        })
        return row
    }
    root.addView(rowOf(1..5))
    root.addView(rowOf(6..10))
    setContentView(root)
}

private fun temptationRideScreen() {
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(backText(::temptationBack))
    root.addView(titleText("Ride the wave"))

    val countdown = TextView(this).apply {
        textSize = 44f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
    }
    val status = TextView(this).apply {
        text = "An urge is a wave: it rises, peaks, and fades \u2014 usually within 15\u201330 " +
            "minutes. You don't have to fight it. Just let it pass."
        textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF6B7075.toInt())
        setPadding(0, 0, 0, (12 * dp).toInt())
    }
    root.addView(countdown); root.addView(status)

    val steps = listOf(
        "Name it: \u201cThis is an urge. It's a wave, not a command.\u201d",
        "Breathe slowly \u2014 in for 4, hold 4, out for 6. Five times.",
        "Notice where you feel it. Watch it like a wave from the shore; don't fight it, don't feed it.",
        "Change your surroundings: stand up, get water, splash your face, step outside.",
        "Put the phone down \u2014 or lock the apps away below.",
        "If you can, message someone you trust. You don't have to say why.",
        "Let the timer run. You only have to get through this one wave.",
    )
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    steps.forEachIndexed { i, s ->
        list.addView(TextView(this).apply {
            text = "${i + 1}.  $s"; textSize = 15f
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        })
    }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(list)
    })

    val lockBtn = Button(this)
    lockBtn.text = if (Lockdown.isActive(this)) "Apps already locked down"
                   else "Lock down distracting apps (30 min)"
    lockBtn.isEnabled = !Lockdown.isActive(this)
    lockBtn.setOnClickListener {
        confirmLockdown {
            lockBtn.text = "Distracting apps locked down"
            lockBtn.isEnabled = false
        }
    }
    root.addView(lockBtn)

    root.addView(Button(this).apply {
        text = "The urge has passed \u2014 I'm okay"
        setOnClickListener { stopRideTimer(); temptationAdvance() }   // -> RERATE
    })

    setContentView(root)
    startRideTimer(countdown, status, 15L * 60 * 1000)   // 15-min wave; counts down on screen
}

private fun confirmLockdown(onStarted: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle("Lock down apps for 30 minutes?")
        .setMessage("Everything except the essentials \u2014 calls, texts, alarms, your home " +
            "screen \u2014 gets blocked for 30 minutes, so the urge has time to pass. " +
            "This can't be cancelled early.")
        .setPositiveButton("Lock down") { _, _ ->
            Lockdown.start(this)
            Toast.makeText(this, "Locked down for 30 minutes. Essentials still work.",
                Toast.LENGTH_LONG).show()
            onStarted()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun renderTemptationFeedback() {
    stopRideTimer()
    inTemptationFlow = false
    onReportScreen = true
    val start = tUrgeStart; val end = tUrgeEnd
    val msg = when {
        start != null && end != null && end < start ->
            "Your urge went from $start to $end. That's the wave breaking \u2014 and you did it by " +
                "riding it out, not by fighting it. Remember this next time: it really does pass."
        start != null && end != null && end == start ->
            "It's holding at $end for now \u2014 that's okay. Waves sometimes take longer than one " +
                "sitting. Keep screens away and let the next stretch of time pass. You haven't acted " +
                "on it, and that's the win."
        start != null && end != null ->
            "It's risen a little, to $end. That happens, and it doesn't mean you're failing. Step " +
                "further from screens, and if you haven't yet, lock the apps down below. The peak is " +
                "usually close \u2014 and after the peak, it falls."
        else ->
            "You took a moment instead of acting on autopilot. That's the whole game, and you just " +
                "won a round of it."
    }
    val dp = resources.displayMetrics.density
    val pad = (20 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(TextView(this).apply {
        text = "Nice work"; textSize = 24f; setTypeface(typeface, Typeface.BOLD)
    })
    content.addView(TextView(this).apply {
        text = msg; textSize = 16f; setPadding(0, (12 * dp).toInt(), 0, 0)
    })
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(content)
    })
    root.addView(Button(this).apply {
        text = "Done"; setOnClickListener { setupMainScreen() }
    })
    setContentView(root)
}

// ========================
// ── "I'm going to look anyway" (supervised loosen) flow ─────────────────────
private data class LQ(val q: String, val options: List<String>)

private val LOOSEN_QUESTIONS = listOf(
    LQ("Be honest \u2014 in an hour, how will you feel about this?",
        listOf("Glad I did it", "I'll regret it", "Numb / nothing", "I already know I'll regret it")),
    LQ("What are you actually trying to change right now?",
        listOf("I'm bored", "I'm stressed or anxious", "I'm lonely", "It's just autopilot")),
    LQ("The urge is here. Can you let it be here without obeying it?",
        listOf("I can try", "Not right now", "I don't want to")),
)

private var inLoosenFlow = false
private var loosenInQuestions = false
private var lqIndex = 0
private var lqPending: String? = null
private val lqAnswers = mutableListOf<String>()
private var loosenWaitEndAt = 0L
private var loosenNote: String? = null
private var loosenAdmit = false
private var loosenWontRepeat = false
private var loosenDuration = 2          // default middle option, nudges low

private var loosenHandler: Handler? = null
private var loosenRunnable: Runnable? = null

private fun startLoosenFlow() {
    onReportScreen = true
    inLoosenFlow = true
    loosenInQuestions = false
    lqIndex = 0; lqPending = null; lqAnswers.clear()
    loosenWaitEndAt = 0L
    loosenNote = null; loosenAdmit = false; loosenWontRepeat = false; loosenDuration = 2
    if (!LoosenLimit.canUse(this)) { loosenBlockedScreen() } else { loosenIntroScreen() }
}

private fun loosenBack() {
    if (loosenInQuestions && lqPending != null) { lqPending = null; renderLoosenQuestion(); return }
    stopLoosenTimer()
    inLoosenFlow = false; loosenInQuestions = false
    showReportScreen()
}

private fun loosenBlockedScreen() {
    stopLoosenTimer(); loosenInQuestions = false
    val today = LoosenLimit.usedToday(this)
    val msg = if (today)
        "You've already used your one unlock for today. It resets tomorrow \u2014 and that wait is " +
            "doing its job. The urge will pass."
    else
        "You've used all ${LoosenLimit.LIFETIME_MAX} of your lifetime unlocks, by your own earlier " +
            "choice. There are no more. You've got this without it."
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(panicButton())
    root.addView(titleText("Not available right now"))
    root.addView(TextView(this).apply { text = msg; textSize = 16f })
    val spacer = View(this); root.addView(spacer, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(Button(this).apply { text = "Back"; setOnClickListener { setupMainScreen() } })
    setContentView(root)
}

private fun loosenIntroScreen() {
    stopLoosenTimer(); loosenInQuestions = false
    val remaining = LoosenLimit.remaining(this)
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(panicButton())
    root.addView(backText { stopLoosenTimer(); inLoosenFlow = false; showReportScreen() })
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(titleText("Before you unlock"))
    content.addView(TextView(this).apply {
        text = "This is a slow, supervised unlock \u2014 not a free pass, and only on this device.\n\n" +
            "You have $remaining of ${LoosenLimit.LIFETIME_MAX} unlocks left for life, and one a day.\n\n" +
            "A few honest questions first, then a 5-minute wait. If the urge passes before then \u2014 " +
            "and it often does \u2014 even better."
        textSize = 15f
    })
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(content)
    })
    root.addView(Button(this).apply {
        text = "Continue"
        setOnClickListener { lqIndex = 0; lqPending = null; lqAnswers.clear(); renderLoosenQuestion() }
    })
    setContentView(root)
}

private fun renderLoosenQuestion() {
    loosenInQuestions = true
    stopLoosenTimer()
    if (lqIndex >= LOOSEN_QUESTIONS.size) { loosenInQuestions = false; loosenWaitScreen(); return }
    val q = LOOSEN_QUESTIONS[lqIndex]
    val confirming = lqPending != null
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(panicButton())
    root.addView(backText { loosenBack() })
    root.addView(titleText(q.q))
    root.addView(TextView(this).apply {
        text = if (confirming) "Tap the SAME answer again to lock it in."
               else "Question ${lqIndex + 1} of ${LOOSEN_QUESTIONS.size}"
        textSize = 13f; setTextColor(0xFF6B7075.toInt())
        setPadding(0, 0, 0, (12 * dp).toInt())
    })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    q.options.shuffled().forEach { opt -> list.addView(pickCard(opt) { onLoosenAnswer(opt) }) }
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(list)
    })
    setContentView(root)
}

private fun onLoosenAnswer(opt: String) {
    val pending = lqPending
    when {
        pending == null -> { lqPending = opt; renderLoosenQuestion() }            // select -> confirm
        opt == pending -> {                                                       // confirmed
            lqAnswers.add(opt); lqPending = null; lqIndex++; renderLoosenQuestion()
        }
        else -> {                                                                 // mismatch -> restart this Q
            lqPending = null
            Toast.makeText(this, "Not the same answer \u2014 starting this one again.",
                Toast.LENGTH_SHORT).show()
            renderLoosenQuestion()
        }
    }
}

private fun loosenWaitScreen() {
    loosenInQuestions = false
    if (loosenWaitEndAt <= System.currentTimeMillis()) {
        loosenWaitEndAt = System.currentTimeMillis() + 5L * 60 * 1000             // 5-min minimum
    }
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(panicButton())
    root.addView(titleText("A short wait first"))
    val countdown = TextView(this).apply {
        textSize = 44f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
    }
    root.addView(countdown)
    root.addView(TextView(this).apply {
        text = "Most urges fade in this time. Giving yourself more space is the kinder choice \u2014 " +
            "and it's just one tap."
        textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF6B7075.toInt())
        setPadding(0, 0, 0, (16 * dp).toInt())
    })
    // The easy, encouraged, one-tap longer waits:
    root.addView(bigChoice("Wait an hour \u2014 recommended", 0xFF2E7D32.toInt()) {
        loosenWaitEndAt = System.currentTimeMillis() + 60L * 60 * 1000; loosenWaitScreen()
    })
    root.addView(bigChoice("Leave it till tomorrow", 0xFF2E7D32.toInt()) { loosenTillTomorrow() })
    root.addView(bigChoice("+ 10 more minutes", 0xFF3E535C.toInt()) {
        loosenWaitEndAt += 10L * 60 * 1000; loosenWaitScreen()
    })
    // The short path: plain, and only after the minimum wait is up.
    val continueBtn = Button(this).apply {
        text = "No thanks \u2014 continue now"; visibility = View.GONE
        setOnClickListener { loosenCommitScreen() }
    }
    root.addView(continueBtn)
    setContentView(root)
    runLoosenCountdown(countdown, loosenWaitEndAt) { continueBtn.visibility = View.VISIBLE }
}

private fun loosenTillTomorrow() {
    stopLoosenTimer(); inLoosenFlow = false; loosenInQuestions = false   // nothing consumed
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(titleText("Good call."))
    root.addView(TextView(this).apply {
        text = "You chose to wait it out. By tomorrow this urge will be long gone \u2014 and you " +
            "still have your unlocks. That took strength."
        textSize = 16f; setPadding(0, (12 * dp).toInt(), 0, 0)
    })
    val spacer = View(this); root.addView(spacer, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(Button(this).apply { text = "Done"; setOnClickListener { setupMainScreen() } })
    setContentView(root)
}

private fun loosenCommitScreen() {
    stopLoosenTimer(); loosenInQuestions = false
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(panicButton())
    root.addView(backText { loosenWaitScreen() })

    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(titleText("Last step"))
    val admitBtn = checkButton()
    content.addView(admitBtn)
    content.addView(TextView(this).apply {
        text = "What will you look at? (private \u2014 just for your own honesty)"
        textSize = 14f; setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
    })
    val noteInput = EditText(this).apply {
        hint = "Name it plainly\u2026"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP or Gravity.START; minLines = 2
        setText(loosenNote ?: "")
    }
    content.addView(noteInput)
    val wontBtn = checkButton()
    content.addView(wontBtn)
    content.addView(TextView(this).apply {
        text = "For how long?"; textSize = 14f
        setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
    })
    val durRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val durBtns = linkedMapOf<Int, Button>()
    listOf(1, 2, 5).forEach { mins ->
        val b = Button(this).apply {
            text = "$mins min"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        durBtns[mins] = b; durRow.addView(b)
    }
    content.addView(durRow)
    root.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(content)
    })

    val unlockBtn = Button(this)
    root.addView(unlockBtn)

    val refresh = {
        admitBtn.text = (if (loosenAdmit) "\u2611  " else "\u2610  ") +
            "I'm choosing this, knowing how I'll feel after."
        wontBtn.text = (if (loosenWontRepeat) "\u2611  " else "\u2610  ") + "I won't do this next time."
        durBtns.forEach { (m, b) -> b.setTypeface(typeface, if (m == loosenDuration) Typeface.BOLD else Typeface.NORMAL) }
        unlockBtn.text = "Unlock for $loosenDuration min"
        unlockBtn.isEnabled = loosenAdmit && loosenWontRepeat
    }
    admitBtn.setOnClickListener { loosenAdmit = !loosenAdmit; refresh() }
    wontBtn.setOnClickListener { loosenWontRepeat = !loosenWontRepeat; refresh() }
    durBtns.forEach { (m, b) -> b.setOnClickListener { loosenDuration = m; refresh() } }
    unlockBtn.setOnClickListener {
        loosenNote = noteInput.text.toString().trim().ifBlank { null }
        loosenUnlock()
    }
    refresh()
    setContentView(root)
}

private fun loosenUnlock() {
    LoosenLimit.consume(this)                                   // one daily + one lifetime, now
    LoosenWindow.start(this, loosenDuration * 60 * 1000L)
    loosenUnlockedScreen()
}

private fun loosenUnlockedScreen() {
    inLoosenFlow = false; loosenInQuestions = false; onReportScreen = true
    val dp = resources.displayMetrics.density
    val pad = (16 * dp).toInt()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(panicButton())
    root.addView(titleText("Unlocked for $loosenDuration min"))
    val countdown = TextView(this).apply {
        textSize = 44f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
    }
    root.addView(countdown)
    root.addView(TextView(this).apply {
        text = "The breathing orb and image friction stay on. Everything re-locks the moment the " +
            "timer ends \u2014 you don't have to do anything."
        textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF6B7075.toInt())
    })
    val spacer = View(this); root.addView(spacer, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(bigChoice("Go", 0xFF3E535C.toInt()) { moveTaskToBack(true) })
    root.addView(Button(this).apply { text = "Done"; setOnClickListener { setupMainScreen() } })
    setContentView(root)
    runLoosenCountdown(countdown, System.currentTimeMillis() + LoosenWindow.remaining(this)) {
        countdown.text = "Re-locked"
    }
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
    root.addView(titleText("Panic \u2014 let's break the loop"))
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
        "If you can, text someone \u2014 you don't have to say why.",
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
        Lockdown.start(this)
        lockApps.text = "Apps locked for 30 min"; lockApps.isEnabled = false
        Toast.makeText(this, "Locked down. Essentials still work.", Toast.LENGTH_LONG).show()
    }
    root.addView(lockApps)
    root.addView(bigChoice("Lock my phone screen now", 0xFF3E535C.toInt()) { lockPhoneNow() })
    root.addView(Button(this).apply {
        text = "I'm okay now"
        setOnClickListener {
            stopLoosenTimer()
            if (inLoosenFlow) loosenIntroScreen() else setupMainScreen()
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

private fun stopLoosenTimer() {
    loosenRunnable?.let { loosenHandler?.removeCallbacks(it) }
    loosenRunnable = null; loosenHandler = null
}


// ── shared little view helpers ─────────────────────────────────────────────
private fun backText(onBack: () -> Unit): TextView {
    val dp = resources.displayMetrics.density
    return TextView(this).apply {
        text = "\u2190 Back"; textSize = 15f
        setPadding(0, 0, 0, (8 * dp).toInt())
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
private fun startRideTimer(label: TextView, status: TextView, durationMs: Long) {
    stopRideTimer()
    rideEndAt = System.currentTimeMillis() + durationMs
    rideHandler = Handler(Looper.getMainLooper())
    rideRunnable = object : Runnable {
        override fun run() {
            val remaining = (rideEndAt - System.currentTimeMillis()).coerceAtLeast(0)
            val m = (remaining / 1000) / 60
            val s = (remaining / 1000) % 60
            label.text = String.format("%02d:%02d", m, s)
            if (remaining <= 0) {
                status.text = "The wave has likely passed its peak. You rode it out. \u2713"
            } else {
                rideHandler?.postDelayed(this, 1000)
            }
        }
    }
    rideRunnable?.run()
}

private fun stopRideTimer() {
    rideRunnable?.let { rideHandler?.removeCallbacks(it) }
    rideRunnable = null
    rideHandler = null
}


private fun onLookAnyway() {
    startLoosenFlow()
}
private fun onReportRelapse() {
    startRelapseFlow()
}

// ── Relapse report flow ────────────────────────────────────────────────────
private enum class RStep { DEVICE, HOME, ROOM, ALONE, ACTIVITY, FEELING, URGE, NOTE }

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

/** The steps in order; ROOM only appears once "At home" is chosen. */
private fun activeSteps(): List<RStep> {
    val s = mutableListOf(RStep.DEVICE, RStep.HOME)
    if (draft.atHome == true) s.add(RStep.ROOM)
    s.add(RStep.ALONE); s.add(RStep.ACTIVITY); s.add(RStep.FEELING); s.add(RStep.URGE); s.add(RStep.NOTE)
    return s
}

private fun relapseAdvance() { relapseStep++; renderRelapseStep() }

private fun relapseBack() {
    if (relapseStep <= 0) { inRelapseFlow = false; showReportScreen(); return }
    relapseStep--
    renderRelapseStep()
}

private fun renderRelapseStep() {
    val steps = activeSteps()
    relapseStep = relapseStep.coerceIn(0, steps.lastIndex)
    when (steps[relapseStep]) {
        RStep.DEVICE -> reportChoiceScreen(
            "Where did it happen?",
            listOf("Yes, on this device", "No, a different device"),
            onBack = ::relapseBack,
        ) { draft.onThisDevice = it.startsWith("Yes"); relapseAdvance() }

        RStep.HOME -> reportChoiceScreen(
            "Were you at home?",
            listOf("At home", "Out / somewhere else"),
            onBack = ::relapseBack,
        ) {
            draft.atHome = (it == "At home")
            if (draft.atHome != true) draft.room = null
            relapseAdvance()
        }

        RStep.ROOM -> roomStep()

        RStep.ALONE -> reportChoiceScreen(
            "Were you alone?",
            listOf("I was alone", "Other people were around"),
            onBack = ::relapseBack,
        ) { draft.alone = it.startsWith("I was"); relapseAdvance() }

        RStep.ACTIVITY -> reportChoiceScreen(
            "What were you doing just before?",
            ACTIVITIES,
            onBack = ::relapseBack,
        ) { draft.activity = it; relapseAdvance() }

        RStep.FEELING -> reportChoiceScreen(
            "How were you feeling?",
            FEELINGS,
            onBack = ::relapseBack,
        ) { draft.feeling = it; relapseAdvance() }

        RStep.URGE -> reportChoiceScreen(
            "How strong was the urge?",
            listOf("Mild", "Strong", "Overwhelming"),
            allowSkip = true, skipLabel = "Skip this", onSkip = ::relapseAdvance,
            onBack = ::relapseBack,
        ) { draft.urge = it; relapseAdvance() }

        RStep.NOTE -> noteStep()
    }
}

private fun roomStep() {
    val opts = (DEFAULT_ROOMS + RoomOptions.all(this)).distinct() + "Other\u2026"
    reportChoiceScreen("Which room?", opts, onBack = ::relapseBack) { choice ->
        if (choice == "Other\u2026") promptCustomRoom()
        else { draft.room = choice; relapseAdvance() }
    }
}

private fun promptCustomRoom() {
    val input = EditText(this).apply {
        hint = "Room name"
        inputType = InputType.TYPE_CLASS_TEXT
        val p = (20 * resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p)
    }
    AlertDialog.Builder(this)
        .setTitle("Add a room")
        .setView(input)
        .setPositiveButton("Add") { _, _ ->
            val name = input.text.toString().trim().replace("\n", " ")
            if (name.isNotEmpty()) {
                RoomOptions.add(this, name)   // remembered for future reports (max 20)
                draft.room = name
                relapseAdvance()
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
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


private fun refreshModeUi() {
    if (!::spinnerMode.isInitialized) return
    val wantPos = if (Mode.isStrict(this)) 1 else 0
    if (spinnerMode.selectedItemPosition != wantPos) spinnerMode.setSelection(wantPos)

    val locked = Mode.isLocked(this)
    spinnerMode.isEnabled = !locked
    val btn = findViewById<Button>(R.id.btn_strict_week)
    if (locked) {
        btn.isEnabled = false
        btn.text = "Strict locked (${Mode.daysLeft(this)})"
    } else {
        btn.isEnabled = true
        btn.text = "Start week-long strict mode"
    }
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
            onReportScreen -> setupMainScreen()
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
            Step.READY -> setupMainScreen()
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
        setContentView(R.layout.activity_main)

        statusOverlay = findViewById(R.id.status_overlay)
        statusAccessibility = findViewById(R.id.status_accessibility)
        statusLock = findViewById(R.id.status_lock)
        emptyList = findViewById(R.id.empty_list)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.btn_clear_blocks).setOnClickListener {
            BlockRules.clear(this)
            BlockEscalation.clear(this)
            AppTimedBlock.clear(this)
        }
        findViewById<Button>(R.id.btn_ban_list).setOnClickListener { showBanList() }
        findViewById<Button>(R.id.btn_recent_blocks).setOnClickListener { showRecentBlocks() }
        findViewById<Button>(R.id.btn_report).setOnClickListener { showReportScreen() }

        spinnerMode = findViewById(R.id.spinner_mode)
        val modeAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, listOf("Relaxed", "Strict"))
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMode.adapter = modeAdapter
        spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val chosen = if (pos == 0) Mode.RELAXED else Mode.STRICT
                if (chosen == Mode.current(this@MainActivity)) return   // no real change (incl. first load)
                if (Mode.setMode(this@MainActivity, chosen)) {
                    Toast.makeText(this@MainActivity,
                        if (chosen == Mode.STRICT) "Strict mode on" else "Relaxed mode on",
                        Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity,
                        "Strict mode is locked \u2014 can't switch back yet", Toast.LENGTH_SHORT).show()
                }
                refreshModeUi()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        findViewById<Button>(R.id.btn_strict_week).setOnClickListener { startWeekStrict() }
        refreshModeUi()
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener { clearLog() }

        // Status rows double as controls.
        statusOverlay.setOnClickListener { requestOverlayPermission() }
        statusAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        statusLock.setOnClickListener { toggleUninstallGuard() }

        onReportScreen = false
        inTemptationFlow = false
        inLoosenFlow = false
        stopRideTimer()
        stopLoosenTimer()
        observeEntries()
    }

    private fun renderStatus() {
        if (!::statusOverlay.isInitialized) return
        setDot(statusOverlay, "Block overlay permission", Settings.canDrawOverlays(this))
        setDot(statusAccessibility, "Page monitoring", isAccessibilityEnabled())
        setDot(statusLock, "Uninstall lock",
            UninstallGuard.isEnabled(this) && UninstallGuard.isAdminActive(this))
    }

    private fun setDot(view: TextView, label: String, on: Boolean) {
        view.text = "${if (on) "\u25CF" else "\u25CB"}  $label \u2014 ${if (on) "On" else "Off"}"
        view.setTextColor(if (on) 0xFF2E9E44.toInt() else 0xFF9AA0A6.toInt())
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
    private val uninstallPasscode = "666666"

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
        if (packageName == "org.mozilla.focus") {
            val t = title?.lowercase().orEmpty()
            val c = content?.lowercase().orEmpty()
            if ("privacy" in t || "stealth" in c) {
                return "Firefox Focus stealth/privacy settings are blocked"
            }
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
        val pageScore = if (host != null)
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
        private const val DOMAIN_BLOCK_MS = 60 * 60 * 1000L   // whole-domain block length

        private val IGNORED_PACKAGES = setOf("com.android.systemui")

        // Apps that get a calming breathing pause each time they're opened.
        private val BREATHING_APPS = setOf(
            "org.mozilla.firefox",          // Firefox
            "org.mozilla.fenix",            // Firefox Beta
            "com.google.android.youtube",   // YouTube
            "com.android.vending",          // Google Play Store
        )

        private val NOT_LOGGED_PACKAGES = setOf(
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.microsoft.launcher",
        )

        private val ADDRESS_BAR_HINTS = listOf(
            "search or enter",
            "search or type",
            "address bar",
            "enter address",
            "search address",
            "edit url",
        )

        private val HOST_PATTERN = Regex("""(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})(?:[/?#]\S*)?""", RegexOption.IGNORE_CASE)

        private const val MAX_URL_CHARS = 2048

        // Known address-bar view IDs, matched by suffix (the package prefix varies).
        // This is the list to extend if a browser's URL isn't being captured.
        // Find a browser's real id: open it, and if the URL column stays blank,
        // its bar id isn't here yet — see the README for how to discover it.
        private val ADDRESS_BAR_IDS = listOf(
            ":id/url_bar",                        // Chrome, Edge, Brave, most Chromium
            ":id/url_field",                      // Opera
            ":id/mozac_browser_toolbar_url_view", // Firefox (old toolbar)
            ":id/location_bar_edit_text",         // Samsung Internet
            ":id/omnibartextinput",               // DuckDuckGo
            "addressbar_url_box",                 // Firefox (new Compose toolbar — no :id/ prefix)
        )

        // Generic "looks like an address bar" id fragments, used as a backup in
        // isAddressBar. Paired with the host-shaped check in hostInText, this is the
        // URL-detection safety net.
        private val ADDRESS_BAR_ID_HINTS = listOf("url", "address", "location", "omnibar")

        // Diagnostics: true logs a "NODE DUMP" row for the browsers below. Turn OFF
        // once you've found the URL node.
        private const val DEBUG_DUMP_NODES = false
        private const val DUMP_INTERVAL_MS = 1500L
        private val BROWSER_DEBUG_PACKAGES = setOf("org.mozilla.firefox")

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
    private data class SearchEngine(val domain: String, val path: String, val params: List<String>)

    private val SEARCH_ENGINES = listOf(
        SearchEngine("google.",          "/search",        listOf("q")),   // incl. Images (udm=2)
        SearchEngine("duckduckgo.com",   "",               listOf("q")),
        SearchEngine("search.brave.com", "/search",        listOf("q")),
        SearchEngine("ecosia.org",       "/search",        listOf("q")),
        SearchEngine("youtube.com",      "/results",       listOf("search_query")),
        SearchEngine("amazon.",          "/s",             listOf("k")),
        SearchEngine("ebay.",            "/sch",           listOf("_nkw")),
    )

    private fun hostMatches(host: String, domain: String): Boolean {
        val h = host.removePrefix("www.")
        return if (domain.endsWith(".")) h.startsWith(domain) || h.contains(".$domain")
               else h == domain || h.endsWith(".$domain")
    }

    private fun engineFor(host: String, path: String): SearchEngine? =
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
    private const val THRESHOLD = 3   // strikes on one domain in a day -> permanent domain block

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
    private val ALLOWED_BROWSERS = setOf(
        "org.mozilla.fenix",
        "org.mozilla.firefox",
    )

    // ================================================================
    // EDIT BELOW — the browser package names to block. All lowercase.
    // DuckDuckGo is in ALLOWED_BROWSERS above, so it stays allowed even
    // if dynamic detection finds it.
    // ================================================================
    private val BLOCKED_BROWSERS = setOf(
        // --- Chrome ---
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",

        // --- Firefox / Gecko family ---
        "org.mozilla.firefox_beta",
        "org.mozilla.fennec_fdroid",
        "org.mozilla.focus",
        "org.mozilla.klar",
        "org.mozilla.rocket",
        "org.mozilla.reference.browser",
        "io.github.forkmaintainers.iceraven",
        "us.spotco.fennec_dos",

        // duckduckgo 
        "com.duckduckgo.mobile.android",

        // --- Edge / Opera / Samsung / Vivaldi / Yandex ---
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.opera.gx",
        "com.opera.touch",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "com.yandex.browser",
        "com.yandex.browser.beta",

        // --- Brave ---
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",

        // --- AOSP / stock ---
        "com.android.browser",
        "com.google.android.browser",

        // --- OEM built-ins ---
        "com.miui.browser",
        "com.mi.globalbrowser",
        "com.mi.globalbrowser.mini",
        "com.heytap.browser",
        "com.coloros.browser",
        "com.oppo.browser",
        "com.vivo.browser",
        "com.huawei.browser",

        // --- Chromium forks / FOSS ---
        "org.bromite.bromite",
        "org.cromite.cromite",
        "com.kiwibrowser.browser",
        "com.stoutner.privacybrowser.standard",
        "com.stoutner.privacybrowser.free",
        "acr.browser.lightning",
        "acr.browser.barebones",
        "jp.hazuki.yuzubrowser",
        "foundation.e.browser",
        "org.adblockplus.browser",
        "org.torproject.torbrowser",

        // --- Other popular third-party ---
        "com.ucmobile.intl",
        "com.uc.browser.en",
        "com.tencent.mtt",
        "com.qihoo.browser",
        "com.cloudmosa.puffinfree",
        "com.cloudmosa.puffin",
        "mark.via.gp",
        "mark.via",
        "com.aloha.browser",
        "com.naver.whale",
        "com.phoenix.browser",
        "com.apusapps.browser",
        "com.ksmobile.cb",
        "mobi.mgeek.tunnybrowser",

        // --- Added after testing on real devices ---
        "net.quetta.browser",      // Quetta
        "com.qwant.liberty",       // Qwant
        // "org.triple.banana",       // Banana Browser
    )
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
    private var animator: AnimatorSet? = null
    private var pulse: ValueAnimator? = null
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
        val inhaleEase = PathInterpolator(0.4f, 0f, 0.5f, 1f)
        val exhaleEase = PathInterpolator(0.2f, 0f, 0.45f, 1f)

        val inhale = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            interpolator = inhaleEase
            addUpdateListener { orb.progress = it.animatedValue as Float }
        }
        val exhale = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 6300
            interpolator = exhaleEase
            addUpdateListener { orb.progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(a: Animator) {
                    phase.text = "Breathe out"
                    controls.visibility = View.VISIBLE
                    controls.animate().alpha(0.55f).setDuration(3600).start()
                }
            })
        }

        pulse = ValueAnimator.ofFloat(0.95f, 0.6f).apply {
            duration = 1300
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { phase.alpha = it.animatedValue as Float }
            start()
        }

        animator = AnimatorSet().apply {
            playSequentially(inhale, exhale)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    pulse?.cancel(); pulse = null
                    phase.alpha = 0f
                    orb.progress = 0f
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
                }
            })
            start()
        }
    }

    fun hide() {
        pulse?.cancel(); pulse = null
        animator?.cancel(); animator = null
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
        val maxR = kotlin.math.hypot(width.toFloat(), height.toFloat()) / 2f * 1.08f
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
// LoosenLimit  (one unlock per day, five for life)
// =====================================================================================
object LoosenLimit {
    const val LIFETIME_MAX = 5
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

    private val ALLOW_SUBSTRINGS = listOf(
        "launcher", "trebuchet",                  // home screens
        "dialer", "incallui", "telecom", "phone", // calls
        "contacts",
        "messaging", "mms",                       // texts
        "deskclock", "clock", "alarm",            // alarms / timers
    )

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
