package com.example.webtrafficmonitor

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// =====================================================================================
// RELAPSE LOG  (private, on-device relapse reports + pattern feedback)
// =====================================================================================
//
// Lives in its own file (same package), like ContentFilter.kt and BlockEventLog.kt.
//
// PHILOSOPHY (important — keep it this way):
//   Logging is never punished. The whole point is to make honest reporting feel safe
//   and useful, so the user keeps doing it and a pattern emerges. The free-text note
//   is PRIVATE: stored on device, never shown back as judgement. Feedback is framed as
//   "here's what we're noticing together", never "you failed".
//
// Its own database (relapse.db) so these reports are NOT touched by the block-event
// 90-day compaction — relapse history is meant to last. No destructive migration: a
// schema change here must ship a real Room Migration.
//
// FOUR PIECES:
//   RelapseReport / Dao / Db - the stored reports.
//   RoomOptions              - custom "other" rooms the user typed, reused next time (cap 20).
//   RelapseDraft             - mutable holder the UI fills in across the steps.
//   RelapseLog               - record / read / analyse (the pattern % feedback).


// --------------------------------------------------------------
// RelapseReport
// --------------------------------------------------------------

/**
 * One relapse report. [timestamp]/[dayOfWeek]/[hourOfDay] are auto-captured and stored
 * the same way BlockEvent stores them, so the two can be grouped on the same time
 * buckets later. Everything else is the user's tap answers, plus an optional private note.
 */
@Entity(tableName = "relapse_reports")
data class RelapseReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val dayOfWeek: Int,            // Calendar.DAY_OF_WEEK: 1 = Sunday … 7 = Saturday (local)
    val hourOfDay: Int,           // 0..23 (local)
    val onThisDevice: Boolean,
    val atHome: Boolean? = null,
    val room: String? = null,     // only when atHome == true
    val alone: Boolean? = null,
    val activity: String? = null, // what they were doing just before
    val feeling: String? = null,
    val urge: String? = null,     // mild / strong / overwhelming (optional)
    val note: String? = null,     // PRIVATE — never shown back as judgement
)


// --------------------------------------------------------------
// RelapseReportDao / RelapseDatabase
// --------------------------------------------------------------

@Dao
interface RelapseReportDao {

    @Insert suspend fun insert(report: RelapseReport)

    @Query("SELECT * FROM relapse_reports ORDER BY timestamp DESC")
    suspend fun all(): List<RelapseReport>

    @Query("SELECT COUNT(*) FROM relapse_reports")
    suspend fun count(): Int

    @Query("DELETE FROM relapse_reports") suspend fun clear()
}

@Database(entities = [RelapseReport::class], version = 1, exportSchema = false)
abstract class RelapseDatabase : RoomDatabase() {

    abstract fun dao(): RelapseReportDao

    companion object {
        @Volatile private var instance: RelapseDatabase? = null

        fun get(context: Context): RelapseDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RelapseDatabase::class.java,
                    "relapse.db",
                )
                    // No destructive migration: relapse history must persist. A schema
                    // change here needs a proper Migration.
                    .build().also { instance = it }
            }
    }
}


// --------------------------------------------------------------
// RoomOptions  (custom rooms the user typed; reused on future reports)
// --------------------------------------------------------------

/**
 * Stores the "Other…" room names the user types so they become tappable options next
 * time — no re-typing. Capped at [MAX]; once full, the oldest drops off. Stored as a
 * newline-joined string in SharedPreferences (order preserved, unlike a StringSet).
 */
object RoomOptions {

    private const val PREFS = "relapse_rooms"
    private const val KEY = "rooms"
    private const val MAX = 20

    fun all(context: Context): List<String> = read(context)

    fun add(context: Context, name: String) {
        val clean = name.trim().replace("\n", " ")
        if (clean.isEmpty()) return
        val list = read(context).toMutableList()
        if (list.any { it.equals(clean, ignoreCase = true) }) return   // no duplicates
        list.add(clean)
        while (list.size > MAX) list.removeAt(0)                       // keep the newest 20
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, list.joinToString("\n")).apply()
    }

    private fun read(context: Context): List<String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}


// --------------------------------------------------------------
// RelapseDraft  (filled in across the UI steps, then saved)
// --------------------------------------------------------------

data class RelapseDraft(
    var onThisDevice: Boolean? = null,
    var atHome: Boolean? = null,
    var room: String? = null,
    var alone: Boolean? = null,
    var activity: String? = null,
    var feeling: String? = null,
    var urge: String? = null,
    var note: String? = null,
) {
    /** Stamp it with the current local time/day/hour and turn it into a stored report. */
    fun toReport(): RelapseReport {
        val cal = Calendar.getInstance()
        return RelapseReport(
            timestamp = cal.timeInMillis,
            dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
            hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
            onThisDevice = onThisDevice ?: true,
            atHome = atHome,
            room = room,
            alone = alone,
            activity = activity,
            feeling = feeling,
            urge = urge,
            note = note,
        )
    }
}


// --------------------------------------------------------------
// RelapseFeedback  (what the final screen shows)
// --------------------------------------------------------------

data class RelapseFeedback(
    val total: Int,                 // reports logged including this one
    val lines: List<String>,        // the "what we noticed" pattern lines
    val encouragement: String,
)


// --------------------------------------------------------------
// RelapseLog  (the API)
// --------------------------------------------------------------

object RelapseLog {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val DAYS = arrayOf(
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
    )

    fun record(context: Context, report: RelapseReport) {
        val dao = RelapseDatabase.get(context).dao()
        scope.launch { dao.insert(report) }
    }

    suspend fun all(context: Context): List<RelapseReport> =
        RelapseDatabase.get(context).dao().all()

    /**
     * Compare [report] against the user's [priors] (their earlier reports — this one is
     * excluded) and build gentle, useful pattern feedback. Pure function: no DB, no UI.
     */
    fun analyze(report: RelapseReport, priors: List<RelapseReport>): RelapseFeedback {
        val n = priors.size
        val lines = mutableListOf<String>()

        if (n == 0) {
            lines.add(
                "This is your first report, so there's nothing to compare it against yet. " +
                    "From here on, every one helps a pattern come into focus.",
            )
            return RelapseFeedback(1, lines, encouragement(1))
        }

        val sameTime = priors.count { hourDiff(it.hourOfDay, report.hourOfDay) <= 2 }
        lines.add(
            "It happened around ${hourLabel(report.hourOfDay)} \u2014 the same time of day as " +
                "${pct(sameTime, n)} of your past reports.",
        )

        val sameDay = priors.count { it.dayOfWeek == report.dayOfWeek }
        lines.add(
            "It fell on a ${dayName(report.dayOfWeek)}, like ${pct(sameDay, n)} of your past reports.",
        )

        report.feeling?.let { f ->
            val same = priors.count { it.feeling == f }
            lines.add("You felt ${f.lowercase()} \u2014 so did ${pct(same, n)} of your past reports.")
        }

        report.atHome?.let { home ->
            val same = priors.count { it.atHome == home }
            val where = if (home) "at home" else "away from home"
            lines.add("You were $where, like ${pct(same, n)} of your past reports.")
        }

        if (report.alone == true) {
            val same = priors.count { it.alone == true }
            lines.add("You were on your own \u2014 true for ${pct(same, n)} of your past reports.")
        }

        if (report.activity != null) {
            val same = priors.count { it.activity == report.activity }
            if (same > 0) {
                lines.add(
                    "Just before, you were \"${report.activity}\" \u2014 the same lead-in as " +
                        "${pct(same, n)} of your past reports.",
                )
            }
        }

        if (n in 1..3) {
            lines.add("(Only a few reports so far, so these numbers will sharpen as you log more.)")
        }

        return RelapseFeedback(n + 1, lines, encouragement(n + 1))
    }

    private fun encouragement(total: Int): String = when {
        total <= 1 ->
            "You did the right thing by logging this \u2014 honestly, that's the hardest part. " +
                "Be kind to yourself: this is the start of understanding it, not a scorecard."
        total < 5 ->
            "You did the right thing by logging this. Each report is a piece of the puzzle, and " +
                "you're already starting to see its shape. This isn't about guilt \u2014 it's about understanding."
        else ->
            "You did the right thing by logging this. $total reports in, the patterns are getting " +
                "clearer \u2014 and a pattern you can see is one you can plan around. You're closer to " +
                "beating this than you were before."
    }

    // ── small helpers ──────────────────────────────────────────────────────────

    private fun pct(part: Int, whole: Int): String =
        if (whole <= 0) "0%" else "${Math.round(part * 100.0 / whole)}%"

    /** Circular distance between two hours, so 23:00 and 01:00 are 2 apart, not 22. */
    private fun hourDiff(a: Int, b: Int): Int {
        val d = Math.abs(a - b)
        return Math.min(d, 24 - d)
    }

    private fun hourLabel(h: Int): String {
        val suffix = if (h < 12) "am" else "pm"
        val twelve = (h % 12).let { if (it == 0) 12 else it }
        return "$twelve$suffix"
    }

    private fun dayName(dow: Int): String = DAYS.getOrElse(dow - 1) { "that day" }
}
