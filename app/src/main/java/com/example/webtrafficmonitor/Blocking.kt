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
// BLOCK
// =====================================================================================


// --------------------------------------------------------------
// BlockRules
// --------------------------------------------------------------


/**
 * The list of things to block, plus a per-session allow list for "report
 * incorrect block".
 *
 * A rule is matched against only the current page's domain and title - never the
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
        pruneProtected(context)   // clears a search engine that was banned before the guard existed
    }

    fun all(): List<String> = rules.toList()

    /** "rule - Xm left" lines for the ban-list screen (expired ones pruned). */
    fun allTimed(): List<String> {
        pruneExpired()
        val now = System.currentTimeMillis()
        return timedRules.entries.map { "${it.key}  -  ${(it.value - now) / 60_000} min left" }.sorted()
    }

    /**
     * Rules we refuse to store, no matter who asks.
     *
     * Banning a whole search engine ("google.com") or its bare results path
     * ("google.com/search") bricks the web: every future search on it is blocked, and the
     * user can't even look up how to undo it. A SPECIFIC search still bans fine
     * ("google.com/search?q=..."), and so does any other page on the domain - only the
     * engine itself is protected.
     *
     * This is a belt-and-braces guard on the STORE, not just on the escalation path, so it
     * holds however the rule got here - escalation, a typo in the manual add box, or a bug.
     */
    fun isProtected(rule: String): Boolean {
        val r = rule.trim().lowercase().removePrefix("www.")
        if (r.isEmpty()) return false
        if ('?' in r) return false                       // a specific search term: allowed
        val hostPath = r.substringBefore('?')
        val host = hostPath.substringBefore('/')
        if ('/' !in hostPath) {
            // bare domain rule: protected if it IS a search engine
            return isSearchEngineHost(host)
        }
        // page rule with no query: protected only if it's the engine's own results path
        val path = "/" + hostPath.substringAfter('/', "")
        return engineFor(host, path) != null
    }

    fun add(context: Context, rule: String) {
        val cleaned = rule.trim().lowercase()
        if (cleaned.isEmpty()) return
        if (isProtected(cleaned)) {
            android.util.Log.w("BlockRules", "refusing to block the search engine itself: $cleaned")
            return
        }
        rules.add(cleaned)
        persist(context)
    }

    /**
     * Drop any protected rule that made it into the store before the guard existed - this is
     * what un-bricks a user who already has "google.com" banned.
     */
    private fun pruneProtected(context: Context) {
        val bad = rules.filter { isProtected(it) } + timedRules.keys.filter { isProtected(it) }
        if (bad.isEmpty()) return
        bad.forEach { rules.remove(it); timedRules.remove(it) }
        android.util.Log.w("BlockRules", "removed protected rule(s): $bad")
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
        if (isProtected(cleaned)) {
            android.util.Log.w("BlockRules", "refusing to timed-block the search engine itself: $cleaned")
            return
        }
        val until = System.currentTimeMillis() + durationMs
        timedRules[cleaned] = maxOf(timedRules[cleaned] ?: 0L, until)
        persist(context)
    }

    /** Wiping your own ban list is a bypass attempt like any other - see BypassWatch. */
    fun clear(context: Context) {
        BypassWatch.record(context, BypassWatch.Reason.WIPE_RULES)
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
     * times - so "dog" typed into Google Images is caught via the URL/results,
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
    //  SEARCH-ENGINE WHITELIST - sites that put the search term in a query param
    // ─────────────────────────────────────────────────────────────────────────
    //  Normally a blocked page becomes a PATH rule with the query dropped, so
    //  "reddit.com/nsfw?sort=high" and "...?sort=low" are both caught by the rule
    //  "reddit.com/nsfw". But on a search engine the path is generic ("/search")
    //  and the real content is the "?q=..." term - dropping the query there would
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
// These are ordinary BlockRules patterns - page rules where only the feed should go
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
// TemptationBlocks  (the "block what feeds this" switch on each Temptations page)
// =====================================================================================
// Exactly like ShortForm, but per category: flipping it on adds that category's curated
// patterns to BlockRules and drops its apps to the GREY tier (time-limited, not banned -
// a hard ban on Instagram would just get the whole feature switched off in a huff).
// Nothing bespoke lives here; a new category is a new AppConfig.TemptationSpec.
object TemptationBlocks {

    fun hasBlocks(spec: AppConfig.TemptationSpec): Boolean =
        spec.blockPatterns.isNotEmpty() || spec.greyApps.isNotEmpty()

    /**
     * Keyed on the PATTERNS only, never the apps: several of these apps (TikTok, Instagram)
     * are on the built-in greylist already, so asking AppRules would report "on" before the
     * user had touched anything.
     */
    fun enabled(spec: AppConfig.TemptationSpec): Boolean {
        if (spec.blockPatterns.isEmpty()) return false
        val rules = BlockRules.all()
        return spec.blockPatterns.all { it in rules }
    }

    fun setEnabled(context: Context, spec: AppConfig.TemptationSpec, on: Boolean) {
        if (on) {
            spec.blockPatterns.forEach { BlockRules.add(context, it) }
            spec.greyApps.forEach { AppRules.setApp(context, it, AppRules.GREY) }
        } else {
            spec.blockPatterns.forEach { BlockRules.remove(context, it) }
            spec.greyApps.forEach { AppRules.remove(context, isApp = true, it) }
        }
    }
}


// =====================================================================================
// Whitelist  (apps/domains we trust enough to skip processing; plus a greylist)
// =====================================================================================
// SAFE_APPS: no public scrolling feed and no arbitrary adult content - so the service
//   skips the screenshot/scan/log entirely (big battery + CPU saving).
// SAFE_DOMAINS: genuinely safe sites - exempt from the heuristic borderline scorer
//   (fewer false positives, less work). Explicit user block rules still apply.
// GREYLIST_APPS: social / short-form apps that MAY contain bad stuff - never whitelisted;
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

        // Do NOT refresh lastAt inside the dedupe branch - that made it a SLIDING
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

    /** "domain - N strike(s) today" lines for the ban-list screen. */
    @Synchronized
    fun summary(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.entries
            .filter { it.key.startsWith("count:") }
            .map { "${it.key.removePrefix("count:")}  -  ${it.value} strike(s) today" }
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
 * SAME app inside that window earns a hard 90-minute block - browser or not. Kept
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
        // through. (The content-strike ladder - 5 min / tomorrow / permanent - is
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

    /** "package - strikes, status" lines for the ban-list screen. */
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
            "$pkg  -  $strikes strike(s), $status"
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
     * True if [packageName] is ANY known browser - blocked, allowed (DuckDuckGo),
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
    // EDIT BELOW - the browser package names to block. All lowercase.
    // DuckDuckGo is in ALLOWED_BROWSERS above, so it stays allowed even
    // if dynamic detection finds it.
    // ================================================================
    private val BLOCKED_BROWSERS = AppConfig.BLOCKED_BROWSERS
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

    fun hosts(context: Context): List<Pair<String, String>> =    // (tier, host) - always GREY
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
// Lockdown  (temporary 30-min "allow-list only" mode)
// =====================================================================================
/**
 * While active, the accessibility service covers every app EXCEPT the essentials below.
 * Browsers, social, games - all off the table - so an urge has nowhere to go. Calls,
 * texts, alarms, contacts and the home screen still work, so the phone isn't bricked.
 * (systemui, keyboards and this app itself are already let through upstream.)
 *
 * Can't be cancelled early on purpose - that's the commitment. It just expires after
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
