package com.example.webtrafficmonitor

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

// =====================================================================================
// CONTENT FILTER  (sexual-content text scoring + self-updating adult-domain blocklist)
// =====================================================================================
//
// Can live in its own file (it does here) OR be pasted into Main.kt as another
// "// ===" section — same package, only depends on android.content + java.io/net.
//
// Three independent pieces:
//
//   WordLists        - loads the editable word tiers from assets/words/*.txt
//   BorderlineScorer - scores a page's text/title/URL for sexual content
//   DomainBlocklist  - reads the merged adult-domain list that was BUNDLED into the
//                      APK at build time (regenerated fresh each build by
//                      build_adult_blocklist.py). It extracts the bundled list once
//                      per install, then binary-searches it on disk. No networking,
//                      no INTERNET permission, no runtime downloads.
//                      runtime, merges them on-device, and binary-searches the
//                      result on disk. NOTHING is hardcoded/bundled — the list is
//                      always as fresh as the last refresh (default: weekly).
//
// Word tiers, strongest to weakest (all are small, editable, bundled assets):
//   explicit_sexual.txt  EXPLICIT_WEIGHT   hardcore/clinical, always sexual
//   strong_sexual.txt    STRONG_WEIGHT     always-adult ("sex","slut"), count alone
//   subtle.txt           SUBTLE_WEIGHT     suggestive ("bikini"), accumulate
//   dual_meaning.txt     DUAL_SEXUAL_WEIGHT ambiguous ("hot","girls"), context only
//
// Runs ALONGSIDE BlockRules and the NSFW image model; changes neither.


// --------------------------------------------------------------
// WordLists
// --------------------------------------------------------------

/**
 * Loads the four editable word tiers from `assets/words/` into in-memory sets the
 * [BorderlineScorer] reads. The lists are tiny, so plain sets are fine. Call [load]
 * once (e.g. in the accessibility service's onServiceConnected). Reads are
 * best-effort: a missing/garbled file just leaves that set empty.
 */
object WordLists {

    @Volatile var explicit: Set<String> = emptySet(); private set
    @Volatile var strong: Set<String> = emptySet(); private set
    @Volatile var subtle: Set<String> = emptySet(); private set
    @Volatile var dual: Set<String> = emptySet(); private set

    /** Any explicit, strong, OR subtle word — these "sexualise" a nearby dual word. */
    @Volatile var indicators: Set<String> = emptySet(); private set

    @Volatile var isReady = false; private set

    fun load(context: Context) {
        val app = context.applicationContext
        explicit = readSet(app, "words/explicit_sexual.txt")
        strong = readSet(app, "words/strong_sexual.txt")
        subtle = readSet(app, "words/subtle.txt")
        dual = readSet(app, "words/dual_meaning.txt")
        indicators = explicit + strong + subtle
        isReady = explicit.isNotEmpty() || strong.isNotEmpty() ||
            subtle.isNotEmpty() || dual.isNotEmpty()
        android.util.Log.i(
            "WordLists",
            "loaded explicit=${explicit.size} strong=${strong.size} " +
                "subtle=${subtle.size} dual=${dual.size}",
        )
    }

    private fun readSet(context: Context, path: String): Set<String> = try {
        context.assets.open(path).bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.lowercase() }
                .toCollection(LinkedHashSet())
        }
    } catch (t: Throwable) {
        android.util.Log.w("WordLists", "could not read assets/$path; using empty set", t)
        emptySet()
    }
}


// --------------------------------------------------------------
// BorderlineScorer
// --------------------------------------------------------------

/**
 * Decides whether a web page's text looks sexual by adding up weighted "hits" and
 * comparing the total to [THRESHOLD]. Returns a [Result] (score + reason) when it
 * decides to block, else null.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * HOW THE SCORE IS BUILT
 * ─────────────────────────────────────────────────────────────────────────────
 * The page is split into lowercase word tokens. Four signals add points:
 *
 *  1. EXPLICIT word  -> + EXPLICIT_WEIGHT each   (hardcore; counts anywhere)
 *  2. STRONG word    -> + STRONG_WEIGHT  each    (always-adult; counts anywhere)
 *  3. SUBTLE word    -> + SUBTLE_WEIGHT  each    (suggestive; counts anywhere)
 *  4. DUAL word      -> + DUAL_SEXUAL_WEIGHT each, BUT ONLY when a sexual INDICATOR
 *                       (any explicit/strong/subtle word) is within CONTEXT_WINDOW
 *                       words of it. This is the "is this 'hot' a sexual 'hot'?"
 *                       check:
 *                         "hot chocolate"   -> no indicator near "hot"  -> 0
 *                         "hot naked girls" -> "naked" near "hot"/"girls" -> count
 *                         "women in tech"   -> no indicator near "women" -> 0
 *
 * REPETITION & CAP: a single word can count at most PER_WORD_CAP times, so one word
 * screamed 50× can't dominate — but repetition still matters up to the cap. This is
 * the "bikini ×10 = bad, bikini ×2 = fine" knob.
 *
 * TITLE / URL: hits there are multiplied by TITLE_URL_MULTIPLIER, since a word in
 * the address/title is far more telling than one buried in body text.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * TUNING CHEAT-SHEET
 * ─────────────────────────────────────────────────────────────────────────────
 *   Too many false blocks?  -> raise THRESHOLD, lower the weights, or shrink
 *                              CONTEXT_WINDOW.
 *   Missing real porn?      -> lower THRESHOLD, raise weights, add words to the lists.
 *   "hot" etc. too jumpy?   -> shrink CONTEXT_WINDOW (e.g. 2), or move the word out
 *                              of dual_meaning.txt.
 *   Single explicit word    -> set EXPLICIT_WEIGHT >= THRESHOLD. (Default needs two
 *   should block instantly?    explicit in the body, or one in the title/URL.)
 *
 * Worked numbers with defaults (THRESHOLD 10): two explicit body words = 12 (block);
 * one explicit in the title = 6×2 = 12 (block); "sex" ×3 body = 4×3 = 12 (block);
 * "bikini" ×6+ body = 2×6 = 12 (block); "hot naked girls" = naked(4)+hot(3)+girls(3)
 * = 10 (block); "hot chocolate" = 0.
 *
 * NOTE: the accessibility service samples up to MAX_TEXT_CHARS (1000) of page text;
 * raise that constant to count repeats over more of a long page.
 */
object BorderlineScorer {

    // ── TUNABLES ───────────────────────────────────────────────────────────────
    const val THRESHOLD = 40                // calculated score at which we decide to block a webpage
    const val EXPLICIT_WEIGHT = 6
    const val STRONG_WEIGHT = 4
    const val SUBTLE_WEIGHT = 2
    const val DUAL_SEXUAL_WEIGHT = 3
    const val PER_WORD_CAP = 6
    const val CONTEXT_WINDOW = 5
    const val TITLE_URL_MULTIPLIER = 2
    // ─────────────────────────────────────────────────────────────────────────────

    data class Result(val score: Int, val reason: String)

    fun evaluate(title: String?, url: String?, text: String?): Result? {
        if (!WordLists.isReady) return null

        val bodyTokens = tokenize(text)
        val fieldTokens = tokenize(title) + tokenize(url)   // title + URL = one strong bucket
        if (bodyTokens.isEmpty() && fieldTokens.isEmpty()) return null

        var score = 0
        var explicitHits = 0
        var strongHits = 0
        var subtleHits = 0
        val dualWordsSeen = LinkedHashSet<String>()

        // ---- BODY ----
        val bExplicit = countMembers(bodyTokens, WordLists.explicit)
        val bStrong = countMembers(bodyTokens, WordLists.strong)
        val bSubtle = countMembers(bodyTokens, WordLists.subtle)
        explicitHits += bExplicit.values.sum()
        strongHits += bStrong.values.sum()
        subtleHits += bSubtle.values.sum()
        score += bExplicit.values.sum() * EXPLICIT_WEIGHT
        score += bStrong.values.sum() * STRONG_WEIGHT
        score += bSubtle.values.sum() * SUBTLE_WEIGHT
        for ((word, n) in countSexualisedDual(bodyTokens)) {
            dualWordsSeen += word
            score += n * DUAL_SEXUAL_WEIGHT
        }

        // ---- TITLE + URL (stronger) ----
        if (fieldTokens.isNotEmpty()) {
            val fExplicit = countMembers(fieldTokens, WordLists.explicit)
            val fStrong = countMembers(fieldTokens, WordLists.strong)
            val fSubtle = countMembers(fieldTokens, WordLists.subtle)
            explicitHits += fExplicit.values.sum()
            strongHits += fStrong.values.sum()
            subtleHits += fSubtle.values.sum()
            score += fExplicit.values.sum() * EXPLICIT_WEIGHT * TITLE_URL_MULTIPLIER
            score += fStrong.values.sum() * STRONG_WEIGHT * TITLE_URL_MULTIPLIER
            score += fSubtle.values.sum() * SUBTLE_WEIGHT * TITLE_URL_MULTIPLIER
            // In short fields, a dual word counts if ANY indicator is present in the field.
            if (fieldTokens.any { it in WordLists.indicators }) {
                for ((word, n) in countMembers(fieldTokens, WordLists.dual)) {
                    dualWordsSeen += word
                    score += n * DUAL_SEXUAL_WEIGHT * TITLE_URL_MULTIPLIER
                }
            }
        }

        if (score < THRESHOLD) return null
        return Result(score, buildReason(score, explicitHits, strongHits, subtleHits, dualWordsSeen))
    }

    /** Per matched word, how many times it appears in [tokens], capped at PER_WORD_CAP. */
    private fun countMembers(tokens: List<String>, set: Set<String>): Map<String, Int> {
        if (set.isEmpty() || tokens.isEmpty()) return emptyMap()
        val counts = HashMap<String, Int>()
        for (t in tokens) if (t in set) {
            val c = counts.getOrDefault(t, 0)
            if (c < PER_WORD_CAP) counts[t] = c + 1
        }
        return counts
    }

    /**
     * Dual words that are SEXUALISED by a nearby indicator. A dual word at position
     * i counts if any token within ±CONTEXT_WINDOW (not itself) is an indicator.
     * Capped per word at PER_WORD_CAP.
     */
    private fun countSexualisedDual(tokens: List<String>): Map<String, Int> {
        if (WordLists.dual.isEmpty() || tokens.isEmpty()) return emptyMap()
        val isIndicator = BooleanArray(tokens.size) { tokens[it] in WordLists.indicators }
        val counts = HashMap<String, Int>()
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t !in WordLists.dual) continue
            val lo = (i - CONTEXT_WINDOW).coerceAtLeast(0)
            val hi = (i + CONTEXT_WINDOW).coerceAtMost(tokens.size - 1)
            var sexual = false
            var j = lo
            while (j <= hi) {
                if (j != i && isIndicator[j]) { sexual = true; break }
                j++
            }
            if (sexual) {
                val c = counts.getOrDefault(t, 0)
                if (c < PER_WORD_CAP) counts[t] = c + 1
            }
        }
        return counts
    }

    /** Split into lowercase word tokens. Letters/digits make a token; else breaks. */
    fun tokenize(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in s) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
            } else if (sb.isNotEmpty()) {
                out.add(sb.toString()); sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun buildReason(
        score: Int,
        explicit: Int,
        strong: Int,
        subtle: Int,
        dualWords: Set<String>,
    ): String {
        val parts = ArrayList<String>()
        if (explicit > 0) parts += "$explicit explicit term${plural(explicit)}"
        if (strong > 0) parts += "$strong strong term${plural(strong)}"
        if (subtle > 0) parts += "$subtle suggestive term${plural(subtle)}"
        if (dualWords.isNotEmpty()) {
            val shown = dualWords.take(3).joinToString(", ") { "\"$it\"" }
            parts += "$shown in a sexual context"
        }
        return "Likely sexual content — score $score/$THRESHOLD (" + parts.joinToString("; ") + ")"
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}


// --------------------------------------------------------------
// DomainBlocklist
// --------------------------------------------------------------

/**
 * "Is this host an adult site?" — answered against the merged adult-domain list
 * that is BUNDLED into the APK as `assets/blocklist/adult_hosts.txt.gz`.
 *
 * The bundle is regenerated FRESH at build time by build_adult_blocklist.py (it
 * downloads StevenBlack + Blocklist Project + UT1, merges, drops mainstream apexes,
 * sorts, gzips). So the list is current as of each build — but the app itself does
 * NO networking, needs NO INTERNET permission, and never downloads on the device.
 *
 * WHAT IT DOES
 *   - On [warmUp] (background thread): if the bundled list hasn't been extracted for
 *     the current install yet, it gunzips the asset to private storage once; then it
 *     opens that file. Re-extraction happens automatically after each reinstall /
 *     app update (i.e. whenever you deploy a fresh build), so a new bundle is always
 *     picked up.
 *   - [isBlocked] binary-searches the sorted file on disk, so 550k+ hosts cost ~0
 *     heap (a HashSet that big risks OOM on cheap phones).
 *
 * MATCHING: a host matches if it, or any parent of it, is listed. "vids.badporn.com"
 * matches "badporn.com"; "someone.tumblr.com" matches only that exact entry —
 * "tumblr.com" itself is never blocked (the build script's NEVER_BLOCK set drops the
 * mainstream apexes but keeps their bad subdomains).
 *
 * SORT ORDER: the build script writes the file sorted with Python's sorted(). For
 * these ASCII [a-z0-9_.-] hosts that matches String.compareTo (the lookup's
 * comparison) and RandomAccessFile.readLine's byte decoding, so the on-disk binary
 * search is correct. Keep the file ASCII + sorted if you ever hand-edit it.
 *
 * IF THE ASSET IS MISSING (you forgot to run the build step): extraction fails
 * quietly, [isReady] stays false, and [isBlocked] just returns false — domain
 * blocking is inactive, the text scorer still works. Nothing crashes.
 */
object DomainBlocklist {

    private const val TAG = "DomainBlocklist"
    private const val DIR = "blocklist"
    private const val FILE_NAME = "adult_hosts.txt"
    private const val ASSET_GZ = "blocklist/adult_hosts.txt.gz"   // bundled at build time
    private const val PREFS = "domain_blocklist"
    private const val KEY_EXTRACTED_FOR = "extracted_for_update_time"

    @Volatile private var raf: RandomAccessFile? = null
    @Volatile private var length = 0L
    @Volatile var isReady = false; private set
    private val lock = Any()

    /**
     * Extract the bundled list if needed, then open it for lookups. Safe to call from
     * onServiceConnected — it does its work on a background thread.
     */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        Thread {
            try {
                ensureExtracted(app)
                openExisting(app)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "warmUp failed", t)
            }
        }.apply { isDaemon = true; name = "domain-blocklist-init" }.start()
    }

    /** True if [host] (or a parent domain of it) is in the adult blocklist. */
    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        if (raf == null) return false
        for (candidate in candidates(host)) if (containsExact(candidate)) return true
        return false
    }

    private fun listFile(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, FILE_NAME)

    /**
     * Gunzip the bundled asset into private storage ONCE per install/update. We key
     * off PackageManager.lastUpdateTime, which changes every time you reinstall
     * (adb install -r) or update the app — so a freshly built bundle is always
     * re-extracted, and normal launches skip the work.
     */
    private fun ensureExtracted(context: Context) {
        val f = listFile(context)
        val updateTime = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Throwable) {
            0L
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (f.exists() && f.length() > 0L && prefs.getLong(KEY_EXTRACTED_FOR, -1L) == updateTime) {
            return  // already extracted for this build
        }
        synchronized(lock) {
            if (f.exists() && f.length() > 0L &&
                prefs.getLong(KEY_EXTRACTED_FOR, -1L) == updateTime
            ) {
                return
            }
            raf?.let { try { it.close() } catch (_: Throwable) {} }
            raf = null
            isReady = false
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            context.assets.open(ASSET_GZ).use { raw ->
                GZIPInputStream(raw).use { gz ->
                    FileOutputStream(tmp).use { out -> gz.copyTo(out, 1 shl 16) }
                }
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            prefs.edit().putLong(KEY_EXTRACTED_FOR, updateTime).apply()
            android.util.Log.i(TAG, "extracted ${f.length()} bytes (build $updateTime)")
        }
    }

    private fun openExisting(context: Context) {
        synchronized(lock) {
            val f = listFile(context)
            if (!f.exists() || f.length() == 0L) {
                android.util.Log.w(TAG, "no list on disk; domain blocking inactive")
                return
            }
            raf?.let { try { it.close() } catch (_: Throwable) {} }
            val opened = RandomAccessFile(f, "r")
            length = opened.length()
            raf = opened
            isReady = true
            android.util.Log.i(TAG, "open: ${f.length()} bytes")
        }
    }

    /** full host, then drop leftmost labels down to the last two (also strips "www."). */
    private fun candidates(rawHost: String): List<String> {
        var host = rawHost.lowercase().trim().trim('.')
        if (host.startsWith("www.")) host = host.substring(4)
        if (host.isEmpty()) return emptyList()
        val labels = host.split('.')
        if (labels.size <= 2) return listOf(host)
        val out = ArrayList<String>()
        var i = 0
        while (labels.size - i >= 2) {
            out.add(labels.subList(i, labels.size).joinToString("."))
            i++
        }
        return out
    }

    /**
     * Binary-search the sorted file for an exact line == [key]. Each probe finds the
     * line that CONTAINS the midpoint (scanning back to the previous newline) and
     * compares it — the robust form; a naive "skip the partial line after mid"
     * version silently misses interior lines.
     */
    private fun containsExact(key: String): Boolean {
        synchronized(lock) {
            val f = raf ?: return false
            try {
                var lo = 0L
                var hi = length
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    val ls = findLineStart(f, mid)
                    f.seek(ls)
                    val line = f.readLine() ?: return false
                    val le = f.filePointer
                    when {
                        line == key -> return true
                        line < key -> lo = le     // ls <= mid < le  -> lo strictly grows
                        else -> hi = ls           // ls <= mid < hi  -> hi strictly shrinks
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "lookup failed for $key", t)
            }
            return false
        }
    }

    /** Offset of the start of the line that contains byte [pos] (scans back to prev '\n'). */
    private fun findLineStart(f: RandomAccessFile, pos: Long): Long {
        if (pos <= 0L) return 0L
        var p = pos
        while (p > 0L) {
            f.seek(p - 1)
            if (f.read() == '\n'.code) return p
            p--
        }
        return 0L
    }
}
