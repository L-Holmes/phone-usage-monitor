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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// =====================================================================================
// BLOCK EVENT LOG  (the tracking system — everything else reads from this)
// =====================================================================================
//
// Lives in its own file (same package), like ContentFilter.kt. Can be pasted into
// Main.kt as another "// ===" section if you prefer; it only depends on Room +
// coroutines + android.content.
//
// WHAT THIS IS — and what it ISN'T:
//   This is NOT the MonitorEntry log. That one records EVERYTHING on screen and is
//   wiped after ~10 minutes (it lives in monitor.db with destructiveMigration on).
//   THIS log records ONLY block events — the moments the app actually covered a page
//   or an app — and it is meant to survive ~3 months so we can see the pattern.
//
//   Because of that 3-month lifetime it gets its OWN database (block_events.db) with
//   NO destructive migration: a schema change here MUST ship a real Room Migration,
//   or you'll throw away the history this whole feature exists to keep.
//
// FOUR PIECES:
//   RecentAppsTracker - tiny in-memory ring buffer of "what was open just before".
//   BlockEvent        - one recorded block (url if web, app, time, day-of-week, before).
//   BlockSummary      - the grouped rollup kept AFTER raw events are binned at 3 months.
//   BlockEventLog     - the API: record / recent / remove / auto-compact.
//   BlockPattern      - turns events or a summary into a human line ("clusters ~5pm…").


// --------------------------------------------------------------
// RecentAppsTracker
// --------------------------------------------------------------

/**
 * Remembers the last few foreground apps so a block event can record "what was open
 * just before". In memory only — "just before" is a short window, so a process
 * restart forgetting it is fine. Fed from the accessibility service on every real
 * foreground change (TYPE_WINDOW_STATE_CHANGED).
 *
 * The current app is excluded at read time, so for a web block on a browser you get
 * the apps used BEFORE the browser, and for an app block you get the apps before it.
 */
object RecentAppsTracker {

    private const val MAX = 6
    private const val WINDOW_MS = 2 * 60 * 1000L   // "just before" = the last 2 minutes

    // Launchers aren't interesting as "what was open before" — everyone passes through
    // the home screen. (systemui / keyboards / our own app never reach us: the service
    // filters them before calling onForeground.)
    private val IGNORE = setOf(
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher",
        "com.android.launcher3",
        "com.microsoft.launcher",
    )

    private val lock = Any()
    private val recent = ArrayDeque<Pair<String, Long>>()   // (pkg, atMillis), oldest first

    fun onForeground(pkg: String?) {
        if (pkg.isNullOrBlank() || pkg in IGNORE) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // Don't store the same app twice in a row — just refresh its time.
            if (recent.lastOrNull()?.first == pkg) recent.removeLast()
            recent.addLast(pkg to now)
            while (recent.size > MAX) recent.removeFirst()
        }
    }

    /** Distinct packages foregrounded in the last [WINDOW_MS], newest first, minus [current]. */
    fun recentlyBefore(current: String?): List<String> {
        val now = System.currentTimeMillis()
        val cur = current?.lowercase()
        synchronized(lock) {
            return recent.reversed()
                .asSequence()
                .filter { now - it.second <= WINDOW_MS }
                .map { it.first }
                .filter { it.lowercase() != cur }
                .distinct()
                .take(MAX)
                .toList()
        }
    }

    fun clear() = synchronized(lock) { recent.clear() }
}


// --------------------------------------------------------------
// BlockEvent  (the row)
// --------------------------------------------------------------

/**
 * One block. [url]/[host] are filled only for web/page blocks; an app block leaves
 * them null. [dayOfWeek] and [hourOfDay] are stored (not just derived) so the 3-month
 * rollup is a cheap group-by and doesn't have to re-bucket timestamps across zones.
 */
@Entity(tableName = "block_events")
data class BlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val dayOfWeek: Int,            // Calendar.DAY_OF_WEEK: 1 = Sunday … 7 = Saturday (local)
    val hourOfDay: Int,            // 0..23 (local)
    val kind: String,             // KIND_WEB or KIND_APP
    val packageName: String?,     // the app it happened on
    val host: String? = null,     // domain, web blocks only
    val url: String? = null,      // full blocked URL, when we had one
    val reason: String? = null,   // why it was blocked (handy when reviewing the list)
    val score: Int? = null,       // content score, when the block came from the scorer
    val recentApps: String? = null, // packages open just before, CSV, newest first
) {
    companion object {
        const val KIND_WEB = "web"
        const val KIND_APP = "app"

        /** Build an event stamped with the current local time / day / hour. */
        fun now(
            kind: String,
            packageName: String?,
            host: String? = null,
            url: String? = null,
            reason: String? = null,
            score: Int? = null,
            recentApps: List<String> = emptyList(),
        ): BlockEvent {
            val cal = Calendar.getInstance()
            return BlockEvent(
                timestamp = cal.timeInMillis,
                dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
                hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
                kind = kind,
                packageName = packageName,
                host = host,
                url = url,
                reason = reason,
                score = score,
                recentApps = recentApps.takeIf { it.isNotEmpty() }?.joinToString(","),
            )
        }
    }

    fun recentAppsList(): List<String> =
        recentApps?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}


// --------------------------------------------------------------
// BlockSummary  (kept after raw events are binned)
// --------------------------------------------------------------

/**
 * The grouped rollup written when raw events pass the retention age. One row per
 * compaction covering [periodStart]..[periodEnd]. Histograms are stored as small CSV
 * strings — "keeps the pattern, kills the storage": a few hundred rows of raw events
 * collapse into ~40 numbers.
 */
@Entity(tableName = "block_summaries")
data class BlockSummary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val periodStart: Long,
    val periodEnd: Long,
    val total: Int,
    val byHourCsv: String,         // 24 counts, hour 0..23
    val byDayOfWeekCsv: String,    // 7 counts, day 1..7 (Sun..Sat) at index 0..6
    val topApps: String? = null,   // "pkg:count;pkg:count" (top 5)
    val createdAt: Long = System.currentTimeMillis(),
)


// --------------------------------------------------------------
// BlockEventDao / BlockEventDatabase
// --------------------------------------------------------------

@Dao
interface BlockEventDao {

    @Insert suspend fun insert(event: BlockEvent)

    /** For the "recent entries" screen — newest first, only the last [since] window. */
    @Query("SELECT * FROM block_events WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun since(since: Long): List<BlockEvent>

    @Query("SELECT * FROM block_events ORDER BY timestamp DESC")
    suspend fun all(): List<BlockEvent>

    /** Remove a single entry — the "mark false / remove" action. */
    @Query("DELETE FROM block_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM block_events WHERE timestamp < :cutoff ORDER BY timestamp ASC")
    suspend fun before(cutoff: Long): List<BlockEvent>

    @Query("DELETE FROM block_events WHERE timestamp < :cutoff")
    suspend fun deleteBefore(cutoff: Long)

    @Query("DELETE FROM block_events") suspend fun clearEvents()

    @Insert suspend fun insertSummary(summary: BlockSummary)

    @Query("SELECT * FROM block_summaries ORDER BY periodStart ASC")
    suspend fun summaries(): List<BlockSummary>
}

@Database(entities = [BlockEvent::class, BlockSummary::class], version = 1, exportSchema = false)
abstract class BlockEventDatabase : RoomDatabase() {

    abstract fun dao(): BlockEventDao

    companion object {
        @Volatile private var instance: BlockEventDatabase? = null

        fun get(context: Context): BlockEventDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BlockEventDatabase::class.java,
                    "block_events.db",
                )
                    // NOTE: deliberately NO fallbackToDestructiveMigration here.
                    // Unlike monitor.db, this data is meant to live ~3 months; a schema
                    // change must ship a proper Migration or it wipes the history.
                    .build().also { instance = it }
            }
    }
}


// --------------------------------------------------------------
// BlockEventLog  (the API)
// --------------------------------------------------------------

/**
 * The one entry point the rest of the app uses.
 *
 *   recordWeb / recordApp  — call from the service the moment a NEW cover appears.
 *   recent(context, ms)    — the past-window list for the "remove false ones" screen.
 *   remove(context, id)    — delete one entry the user marked false.
 *   summaries(context)     — the post-3-month rollups.
 *
 * Compaction runs itself: on insert (throttled) it rolls events older than the
 * retention age into a BlockSummary, then deletes them.
 */
object BlockEventLog {

    // Keep raw events this long, then summarise + bin. Short in testing so you can
    // actually watch compaction happen instead of waiting a quarter of a year.
    private val RETENTION_MS: Long =
        if (BuildConfig.IS_TESTING) 5L * 60 * 1000           // 5 min in testing builds
        else 90L * 24L * 60L * 60L * 1000L                   // ~3 months

    private const val COMPACT_INTERVAL_MS = 12L * 60 * 60 * 1000  // check at most ~twice a day
    private const val DEDUPE_MS = 8_000L                          // swallow rapid re-blocks

    private const val PREFS = "block_event_log"
    private const val KEY_LAST_COMPACT = "last_compact_at"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private var lastSig: String? = null
    private var lastAt = 0L

    // ── recording ────────────────────────────────────────────────────────────

    /** A page/content block just appeared (browser or in-app web view). */
    fun recordWeb(
        context: Context,
        packageName: String?,
        host: String?,
        url: String?,
        reason: String?,
        score: Int? = null,
    ) =
        record(
            context,
            BlockEvent.now(
                kind = BlockEvent.KIND_WEB,
                packageName = packageName,
                host = host,
                url = url,
                reason = reason,
                score = score,
                recentApps = RecentAppsTracker.recentlyBefore(packageName),
            ),
        )

    /** A whole-app block just appeared. */
    fun recordApp(context: Context, packageName: String?, reason: String?) =
        record(
            context,
            BlockEvent.now(
                kind = BlockEvent.KIND_APP,
                packageName = packageName,
                reason = reason,
                recentApps = RecentAppsTracker.recentlyBefore(packageName),
            ),
        )

    fun record(context: Context, event: BlockEvent) {
        // The cover can re-arm on a back-tap onto the same page, and the app-block
        // recheck loop reposts often — collapse those into one event. (Mirrors the
        // dedupe BlockEscalation already uses.)
        val sig = "${event.kind}|${event.packageName}|${event.host}|${event.url}"
        synchronized(lock) {
            if (sig == lastSig && event.timestamp - lastAt < DEDUPE_MS) return
            lastSig = sig
            lastAt = event.timestamp
        }
        val dao = BlockEventDatabase.get(context).dao()
        scope.launch {
            dao.insert(event)
            maybeCompact(context, dao)
        }
    }

    // ── reading / editing ────────────────────────────────────────────────────

    /** Events from the last [sinceMs] (e.g. the past hour), newest first. */
    suspend fun recent(context: Context, sinceMs: Long): List<BlockEvent> =
        BlockEventDatabase.get(context).dao().since(System.currentTimeMillis() - sinceMs)

    /** The "mark false / remove" action for a single recent entry. */
    fun remove(context: Context, id: Long) {
        val dao = BlockEventDatabase.get(context).dao()
        scope.launch { dao.deleteById(id) }
    }

    suspend fun summaries(context: Context): List<BlockSummary> =
        BlockEventDatabase.get(context).dao().summaries()

    /** Dev-only nuke of everything this log holds (events + rollups). */
    fun clear(context: Context) {
        val dao = BlockEventDatabase.get(context).dao()
        scope.launch { dao.clearEvents() }
        RecentAppsTracker.clear()
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ── compaction ───────────────────────────────────────────────────────────

    private suspend fun maybeCompact(context: Context, dao: BlockEventDao) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_COMPACT, 0L) < COMPACT_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_COMPACT, now).apply()

        val cutoff = now - RETENTION_MS
        val old = dao.before(cutoff)
        if (old.isEmpty()) return

        dao.insertSummary(summarise(old))   // write the rollup FIRST…
        dao.deleteBefore(cutoff)            // …then bin the raw rows.
    }

    private fun summarise(events: List<BlockEvent>): BlockSummary {
        val byHour = IntArray(24)
        val byDow = IntArray(8)             // index 1..7 (Calendar), 0 unused
        val appCounts = HashMap<String, Int>()
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        for (e in events) {
            if (e.hourOfDay in 0..23) byHour[e.hourOfDay]++
            if (e.dayOfWeek in 1..7) byDow[e.dayOfWeek]++
            e.packageName?.let { appCounts[it] = (appCounts[it] ?: 0) + 1 }
            if (e.timestamp < min) min = e.timestamp
            if (e.timestamp > max) max = e.timestamp
        }
        val topApps = appCounts.entries.sortedByDescending { it.value }.take(5)
            .joinToString(";") { "${it.key}:${it.value}" }
        return BlockSummary(
            periodStart = min,
            periodEnd = max,
            total = events.size,
            byHourCsv = byHour.joinToString(","),
            byDayOfWeekCsv = (1..7).joinToString(",") { byDow[it].toString() },
            topApps = topApps.ifBlank { null },
        )
    }
}


// --------------------------------------------------------------
// BlockPattern  (human-readable pattern, for live data or a summary)
// --------------------------------------------------------------

/**
 * Turns counts into a sentence like:
 *   "12 Jan 2026 – 11 Apr 2026: 184 blocks. Clusters around ~5pm and ~11pm. Most on Friday."
 *
 * Works on a stored [BlockSummary] OR on a live list of [BlockEvent] (so the UI can
 * show the same insight before the first compaction has ever run).
 */
object BlockPattern {

    private val DAY_NAMES = arrayOf(
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
    )

    fun describe(summary: BlockSummary): String =
        describe(
            total = summary.total,
            periodStart = summary.periodStart,
            periodEnd = summary.periodEnd,
            byHour = parse(summary.byHourCsv, 24),
            byDow = parse(summary.byDayOfWeekCsv, 7),
        )

    fun describe(events: List<BlockEvent>): String {
        if (events.isEmpty()) return "No blocks recorded yet."
        val byHour = IntArray(24)
        val byDow = IntArray(7)            // index 0..6 = day 1..7 (Sun..Sat)
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        for (e in events) {
            if (e.hourOfDay in 0..23) byHour[e.hourOfDay]++
            if (e.dayOfWeek in 1..7) byDow[e.dayOfWeek - 1]++
            if (e.timestamp < min) min = e.timestamp
            if (e.timestamp > max) max = e.timestamp
        }
        return describe(events.size, min, max, byHour, byDow)
    }

    private fun describe(
        total: Int,
        periodStart: Long,
        periodEnd: Long,
        byHour: IntArray,
        byDow: IntArray,
    ): String {
        val df = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val clusters = hourClusters(byHour).map { hourLabel(it) }
        val busiest = (0..6).filter { byDow[it] > 0 }.maxByOrNull { byDow[it] }
        return buildString {
            append(df.format(Date(periodStart))).append(" \u2013 ").append(df.format(Date(periodEnd)))
            append(": ").append(total).append(if (total == 1) " block. " else " blocks. ")
            if (clusters.isNotEmpty()) {
                append("Clusters around ").append(clusters.joinToString(" and ")).append(". ")
            }
            if (busiest != null) append("Most on ").append(DAY_NAMES[busiest]).append(".")
        }.trim()
    }

    /**
     * Representative peak hours: hours at >=60% of the busiest hour, with adjacent
     * hours merged into one cluster (each reported at its own busiest hour). The scan
     * starts on a quiet hour so a late-night run that wraps 23->0 isn't split in two.
     */
    private fun hourClusters(byHour: IntArray): List<Int> {
        val peak = byHour.maxOrNull() ?: 0
        if (peak <= 0) return emptyList()
        val threshold = maxOf(1, (peak * 6 + 9) / 10)        // ceil(0.6 * peak)
        val hot = BooleanArray(24) { byHour[it] >= threshold }
        if (hot.all { it }) return listOf(byHour.indices.maxByOrNull { byHour[it] } ?: 0)

        val startGap = (0 until 24).first { !hot[it] }       // begin on a quiet hour
        val used = BooleanArray(24)
        val reps = mutableListOf<Int>()
        for (offset in 0 until 24) {
            val h = (startGap + offset) % 24
            if (!hot[h] || used[h]) continue
            var best = h
            var bestCount = byHour[h]
            var k = h
            var steps = 0
            while (steps < 24 && hot[k % 24] && !used[k % 24]) {
                used[k % 24] = true
                if (byHour[k % 24] > bestCount) { bestCount = byHour[k % 24]; best = k % 24 }
                k++; steps++
            }
            reps.add(best)
        }
        return reps.sorted()
    }

    private fun hourLabel(h: Int): String {
        val suffix = if (h < 12) "am" else "pm"
        val twelve = (h % 12).let { if (it == 0) 12 else it }
        return "~$twelve$suffix"
    }

    private fun parse(csv: String, size: Int): IntArray {
        val out = IntArray(size)
        csv.split(",").forEachIndexed { i, v -> if (i < size) out[i] = v.trim().toIntOrNull() ?: 0 }
        return out
    }
}
