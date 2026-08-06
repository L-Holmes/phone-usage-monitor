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
    private const val KEY_NOTES = "rule_notes"

    /** A keyword must appear this many times in on-screen TEXT to block (title/URL need only 1). */
    private const val TEXT_HITS_NEEDED = 2

    private val rules = linkedSetOf<String>()
    private val timedRules = HashMap<String, Long>()   // rule -> blocked-until (millis)
    private val sessionAllow = mutableSetOf<String>()

    // ── WHY each rule exists ─────────────────────────────────────────────────────────
    //
    //  A rule on its own is not an explanation. "Blocked site: google.com/search?q=..."
    //  says what happened and nothing about why - which reads as arbitrary, and when the
    //  block IS wrong there is nothing there to argue with. The information exists at the
    //  moment the rule is created (a score, the words behind it, a run of strikes, a
    //  deliberate tap) and was being thrown away.
    //
    //  So every rule carries a note, and the block cover reads it back. Notes are
    //  best-effort: an old rule from before this existed simply has none, and everything
    //  degrades to the sentence we showed before.

    enum class Origin { AUTO_BLOCK, DOMAIN_STRIKE, MANUAL, PRESET, UNKNOWN }

    /** [words] are the top scoring words at the time, for an AUTO_BLOCK. */
    data class Note(
        val origin: Origin,
        val at: Long = System.currentTimeMillis(),
        val score: Int? = null,
        val words: List<String> = emptyList(),
    )

    private val notes = HashMap<String, Note>()

    fun note(rule: String): Note? = notes[rule.trim().lowercase()]

    /**
     * The headline sentence for a rule - what KIND of thing it blocks. A search-term rule
     * is the one that mattered: "google.com/search?q=big+boobs" is a URL, not a sentence,
     * and reading your own block cover should not require decoding a query string.
     */
    fun describe(context: Context, rule: String): String {
        val r = rule.trim().lowercase()
        searchTermOf(r)?.let { return context.getString(R.string.br_blocked_search, it, r.substringBefore('/')) }
        return when (kindOf(r)) {
            Kind.PAGE -> context.getString(R.string.br_blocked_page, r)
            Kind.DOMAIN -> context.getString(R.string.br_blocked_site, r)
            else -> context.getString(R.string.br_blocked_keyword, r)
        }
    }

    enum class Kind { SEARCH, PAGE, DOMAIN, KEYWORD }

    fun kindOf(rule: String): Kind {
        val r = rule.trim().lowercase()
        return when {
            searchTermOf(r) != null -> Kind.SEARCH
            '/' in r -> Kind.PAGE
            '.' in r -> Kind.DOMAIN
            else -> Kind.KEYWORD
        }
    }

    /** The human-readable search term inside a search-term rule, or null if it isn't one. */
    fun searchTermOf(rule: String): String? {
        val r = rule.trim().lowercase()
        val q = r.indexOf('?')
        if (q <= 0) return null
        val term = r.substring(q + 1).substringAfter('=', "")
        if (term.isBlank()) return null
        val readable = try {
            java.net.URLDecoder.decode(term.replace('+', ' '), "UTF-8")
        } catch (t: Throwable) { term }
        return readable.ifBlank { null }
    }

    /** How this rule got on the list, or null for a rule from before notes existed. */
    fun whyLine(context: Context, rule: String): String? {
        val n = note(rule) ?: return null
        // SECOND resolution, not MINUTE: a rule the user created ten seconds ago by tapping
        // "go back" would otherwise be described as added "0 minutes ago".
        val ago = if (n.at > 0) android.text.format.DateUtils.getRelativeTimeSpanString(
            n.at, System.currentTimeMillis(), android.text.format.DateUtils.SECOND_IN_MILLIS,
        ).toString() else return null
        return when (n.origin) {
            Origin.AUTO_BLOCK ->
                if (n.score != null && n.words.isNotEmpty())
                    context.getString(R.string.br_why_auto_scored, ago, n.score, n.words.joinToString(", "))
                else if (n.score != null)
                    context.getString(R.string.br_why_auto_score, ago, n.score)
                else context.getString(R.string.br_why_auto, ago)
            Origin.DOMAIN_STRIKE -> context.getString(R.string.br_why_strike, ago)
            Origin.MANUAL -> context.getString(R.string.br_why_manual, ago)
            Origin.PRESET -> context.getString(R.string.br_why_preset)
            Origin.UNKNOWN -> null
        }
    }

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
        loadNotes(prefs.getString(KEY_NOTES, null))
        pruneProtected(context)   // clears a search engine that was banned before the guard existed
    }

    private fun loadNotes(raw: String?) {
        notes.clear()
        if (raw.isNullOrBlank()) return
        try {
            val obj = org.json.JSONObject(raw)
            for (key in obj.keys()) {
                val o = obj.optJSONObject(key) ?: continue
                val origin = try { Origin.valueOf(o.optString("o", "UNKNOWN")) } catch (_: Throwable) { Origin.UNKNOWN }
                val words = o.optJSONArray("w")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList()
                notes[key] = Note(
                    origin = origin,
                    at = o.optLong("t", 0L),
                    score = if (o.has("s")) o.optInt("s") else null,
                    words = words,
                )
            }
        } catch (t: Throwable) {
            android.util.Log.w("BlockRules", "could not read rule notes", t)
        }
    }

    private fun notesJson(): String {
        val obj = org.json.JSONObject()
        for ((rule, n) in notes) {
            obj.put(rule, org.json.JSONObject().apply {
                put("o", n.origin.name)
                put("t", n.at)
                n.score?.let { put("s", it) }
                if (n.words.isNotEmpty()) put("w", org.json.JSONArray(n.words))
            })
        }
        return obj.toString()
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

    fun add(context: Context, rule: String, note: Note? = null) {
        val cleaned = rule.trim().lowercase()
        if (cleaned.isEmpty()) return
        if (isProtected(cleaned)) {
            android.util.Log.w("BlockRules", "refusing to block the search engine itself: $cleaned")
            return
        }
        rules.add(cleaned)
        // Never let a re-add with no note overwrite a good one: the first telling of why
        // a rule exists is the true one.
        if (note != null) notes[cleaned] = note
        persist(context)
    }

    /**
     * Drop any protected rule that made it into the store before the guard existed - this is
     * what un-bricks a user who already has "google.com" banned.
     */
    private fun pruneProtected(context: Context) {
        val bad = rules.filter { isProtected(it) } + timedRules.keys.filter { isProtected(it) }
        if (bad.isEmpty()) return
        bad.forEach { rules.remove(it); timedRules.remove(it); notes.remove(it) }
        android.util.Log.w("BlockRules", "removed protected rule(s): $bad")
        persist(context)
    }

    fun remove(context: Context, rule: String) {
        val cleaned = rule.trim().lowercase()
        rules.remove(cleaned)
        notes.remove(cleaned)
        persist(context)
    }

    /** Block [rule] for [durationMs] (e.g. a domain for an hour). Never shortens an existing timer. */
    fun addTimed(context: Context, rule: String, durationMs: Long, note: Note? = null) {
        val cleaned = rule.trim().lowercase()
        if (cleaned.isEmpty()) return
        if (isProtected(cleaned)) {
            android.util.Log.w("BlockRules", "refusing to timed-block the search engine itself: $cleaned")
            return
        }
        val until = System.currentTimeMillis() + durationMs
        timedRules[cleaned] = maxOf(timedRules[cleaned] ?: 0L, until)
        if (note != null) notes[cleaned] = note
        persist(context)
    }

    /** Wiping your own ban list is a bypass attempt like any other - see BypassWatch. */
    fun clear(context: Context) {
        BypassWatch.record(context, BypassWatch.Reason.WIPE_RULES)
        rules.clear()
        timedRules.clear()
        notes.clear()
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
        // Notes for rules that no longer exist are dead weight (and would resurrect a
        // stale "why" if the same rule came back for a different reason).
        notes.keys.retainAll { it in rules || it in timedRules }
        prefs(context).edit()
            .putStringSet(KEY, HashSet(rules))
            .putStringSet(KEY_TIMED, timedRules.entries.mapTo(HashSet()) { "${it.key}|${it.value}" })
            .putString(KEY_NOTES, notesJson())
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
//
// WHO OWNS THE SWITCH: Temptations ▸ Endless Scrolling / Brain Rot, via TemptationBlocks
// and the "scrolling" spec - whose blockPatterns ARE AppConfig.SHORT_FORM_PATTERNS. The
// Productivity page used to carry a second switch over the same set; it was removed
// rather than kept in sync, because two controls over one rule set is a bug with a
// waiting period. setEnabled() therefore has no caller in the UI any more and enabled()
// is a READ used by the dev console status row - which still reports correctly, because
// the brain-rot switch writes exactly these patterns. If you add a UI switch back, make
// it the only one.
object ShortForm {
    val PATTERNS = AppConfig.SHORT_FORM_PATTERNS
    fun enabled(): Boolean = PATTERNS.all { it in BlockRules.all() }
    fun setEnabled(context: Context, on: Boolean) {
        if (on) PATTERNS.forEach { BlockRules.add(context, it, BlockRules.Note(BlockRules.Origin.PRESET)) }
        else PATTERNS.forEach { BlockRules.remove(context, it) }
    }
}


// =====================================================================================
// TemptationBlocks  (the "block what feeds this" switch on each Temptations page)
// =====================================================================================
// Exactly like ShortForm, but per category: flipping it on adds that category's curated
// patterns to BlockRules, drops its greyApps to the GREY tier (time-limited, not banned -
// a hard ban on Instagram would just get the whole feature switched off in a huff), and
// BANS its blockApps outright (game and news apps, where a time limit is no deterrent).
// Nothing bespoke lives here; a new category is a new AppConfig.TemptationSpec.
object TemptationBlocks {

    fun hasBlocks(spec: AppConfig.TemptationSpec): Boolean =
        spec.blockPatterns.isNotEmpty() || spec.greyApps.isNotEmpty() || spec.blockApps.isNotEmpty()

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
            spec.blockPatterns.forEach { BlockRules.add(context, it, BlockRules.Note(BlockRules.Origin.PRESET)) }
            spec.greyApps.forEach { AppRules.setApp(context, it, AppRules.GREY) }
            spec.blockApps.forEach { AppRules.setApp(context, it, AppRules.BLOCK) }
        } else {
            spec.blockPatterns.forEach { BlockRules.remove(context, it) }
            (spec.greyApps + spec.blockApps).forEach { AppRules.remove(context, isApp = true, it) }
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

    // getters, not vals: the underlying lists load from assets/filter/ at startup (FilterData),
    // so capturing them once at object-init could grab an empty set before init ran.
    val SAFE_APPS: Set<String> get() = AppConfig.SAFE_APPS
    val SAFE_DOMAINS: Set<String> get() = AppConfig.SAFE_DOMAINS
    val GREYLIST_APPS: Set<String> get() = AppConfig.GREYLIST_APPS

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
// =====================================================================================
// TamperWatch  —  the clock, and the gaps
// =====================================================================================
/**
 * Two documented ways to defeat a blocker that neither blocks nor detects them, until now.
 *
 * ── THE CLOCK ────────────────────────────────────────────────────────────────────────
 * Every timed thing in this app - AppTimedBlock, Lockdown, LoosenWindow, GreyUsage, the
 * week-long strict lock - is wall-clock based. Winding the system clock forward ends all of
 * them at once. It is one of the first tricks in every published Family Link bypass.
 *
 * The fix is not to stop using the wall clock (a block that has to survive a reboot needs
 * it), it is to keep a SECOND, monotonic reading alongside it. `elapsedRealtime()` counts
 * from boot and cannot be set by anyone. If the two disagree by more than a rounding error,
 * the wall clock moved on its own, and we can say so.
 *
 * ── THE GAPS ─────────────────────────────────────────────────────────────────────────
 * Safe Mode boots Android with third-party apps disabled: this service does not run, nothing
 * is blocked, and ordinarily we would never find out. Same for a force-stop that sticks, or
 * an OEM battery manager killing us overnight.
 *
 * We cannot prevent either. What we CAN do is notice: a heartbeat every few minutes, and on
 * the next start, compare where we were with where we are. A gap with no matching boot is
 * time this app was supposed to be watching and wasn't. Competitors send that to an
 * accountability partner; with no partner (a deliberate choice) it goes to the user, which
 * is the honest local version of the same idea.
 */
object TamperWatch {

    private const val PREFS = "tamper_watch"
    private const val KEY_WALL = "last_wall"
    private const val KEY_ELAPSED = "last_elapsed"
    private const val KEY_GAP_AT = "gap_at"
    private const val KEY_GAP_MS = "gap_ms"
    private const val KEY_CLOCK_AT = "clock_at"
    private const val KEY_CLOCK_BY = "clock_by"

    /** How often the heartbeat is written. Cheap; it is two longs. */
    const val HEARTBEAT_MS = 2 * 60 * 1000L

    /** A gap longer than this counts as "the guard was not running". */
    private const val GAP_THRESHOLD_MS = 5 * 60 * 1000L

    /** Wall-clock movement beyond this, with no matching elapsed movement, is tampering. */
    private const val CLOCK_SLIP_MS = 2 * 60 * 1000L

    /**
     * Write the heartbeat, and report anything odd since the last one. Called on a timer by
     * the service, and once at startup.
     *
     * Returns true if this beat detected tampering (a clock jump or an unexplained gap), so
     * the caller can react immediately rather than waiting for someone to open the app.
     */
    @Synchronized
    fun beat(ctx: Context): Boolean {
        val p = prefs(ctx)
        val wall = System.currentTimeMillis()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val lastWall = p.getLong(KEY_WALL, 0L)
        val lastElapsed = p.getLong(KEY_ELAPSED, 0L)
        var flagged = false

        if (lastWall > 0L) {
            val wallDelta = wall - lastWall
            val elapsedDelta = elapsed - lastElapsed
            val rebooted = elapsedDelta < 0            // elapsedRealtime resets on boot
            if (!rebooted) {
                // Both clocks should advance together. If the wall clock ran away from the
                // monotonic one, somebody set it.
                val slip = wallDelta - elapsedDelta
                if (kotlin.math.abs(slip) > CLOCK_SLIP_MS) {
                    p.edit().putLong(KEY_CLOCK_AT, wall).putLong(KEY_CLOCK_BY, slip).apply()
                    flagged = true
                }
                // A long stretch with no heartbeat, while the device was up, means we were
                // not running: safe mode, a force stop, or an OEM battery killer.
                if (elapsedDelta > GAP_THRESHOLD_MS) {
                    p.edit().putLong(KEY_GAP_AT, wall).putLong(KEY_GAP_MS, elapsedDelta).apply()
                    flagged = true
                }
            }
        }
        p.edit().putLong(KEY_WALL, wall).putLong(KEY_ELAPSED, elapsed).apply()
        return flagged
    }

    /** ms the clock was moved by, and when - or null if it has not been. */
    fun clockSlip(ctx: Context): Pair<Long, Long>? {
        val p = prefs(ctx)
        val at = p.getLong(KEY_CLOCK_AT, 0L)
        return if (at == 0L) null else at to p.getLong(KEY_CLOCK_BY, 0L)
    }

    /** How long the guard was last not running, and when - or null if it always has been. */
    fun lastGap(ctx: Context): Pair<Long, Long>? {
        val p = prefs(ctx)
        val at = p.getLong(KEY_GAP_AT, 0L)
        return if (at == 0L) null else at to p.getLong(KEY_GAP_MS, 0L)
    }

    /** Read and clear, for a notice shown once. */
    fun acknowledge(ctx: Context) =
        prefs(ctx).edit().remove(KEY_GAP_AT).remove(KEY_GAP_MS)
            .remove(KEY_CLOCK_AT).remove(KEY_CLOCK_BY).apply()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


// =====================================================================================
// BorderlineWatch  —  the app that keeps ALMOST blocking
// =====================================================================================
/**
 * A single borderline screen means nothing. "hot", "girls", "tight" - ordinary words that
 * score a few points and are supposed to. That is why one of them can never block anything,
 * and it is the right call every single time you look at one screen.
 *
 * Look at ten MINUTES of screens and it is a different question. An app that keeps coming
 * back at eight points, over and over, is not a coincidence: it is a feed that has worked
 * out what holds your attention, and the filter watching one frame at a time will never
 * see it. Neither would you, in the moment - that is rather the point of the feed.
 *
 * So this counts. Borderline readings for one app inside a rolling WINDOW_MS:
 *   • at WARN_AT   → a WARNING cover for a few seconds, saying plainly what is about to
 *                    happen and why. Nothing is taken away yet.
 *   • at BLOCK_AT  → the app closes for PENALTY_MS.
 *
 * STRICT AND ABOVE ONLY. In Relaxed this is exactly the sort of thing that should be left
 * alone: nothing here has crossed the line, and Relaxed is the mode that says it will only
 * act on things that have.
 *
 * Counters are in memory only, like CheckingGuard's. They live in the accessibility
 * service's process, which runs all day, and losing them on a restart costs one lenient
 * window - which is the right way round for a rule that acts on a suspicion.
 */
object BorderlineWatch {

    // ── Tuning: what "borderline for five minutes" actually means ────────────────────
    // A LEAKY BUCKET, sampled at MIN_GAP_MS. Every ~10 seconds the screen in front counts
    // once: a borderline reading fills the bucket by one, a clean one drains it by one.
    // So the bucket measures the PROPORTION of the last few minutes that was borderline,
    // not merely how long the app has been open, and a feed with the occasional bad frame
    // never gets there while one that is mostly bad does.
    //
    // The numbers below mean: warn after roughly one and a half minutes of near-continuous
    // borderline content, block after roughly three. The window caps how far back it can
    // reach, so nothing accumulated an hour ago can be held against you now.

    /** How long a borderline reading stays on the books. */
    const val WINDOW_MS = 5 * 60 * 1000L
    /** One reading per app per this long, so a burst of events counts once. */
    const val SAMPLE_MS = 10_000L
    /** Borderline readings in the bucket before the user is warned. (~1.5 min) */
    const val WARN_AT = 9
    /** ...and before the app is shut. (~3 min) */
    const val BLOCK_AT = 18
    /** How long the warning cover stays up. Long enough to read, short enough not to punish. */
    const val WARN_HOLD_MS = 8_000L
    const val PENALTY_MS = 60 * 60 * 1000L
    const val PENALTY_LABEL = "an hour"

    enum class Action { NONE, WARN, BLOCK }

    private val hits = HashMap<String, ArrayDeque<Long>>()
    private val lastAt = HashMap<String, Long>()
    private val warned = HashSet<String>()
    @Volatile private var warnPkg: String? = null
    @Volatile private var warnUntil = 0L

    /**
     * Record what the screen in front of [pkg] looks like and say what should happen.
     *
     * Safe to call on every event: only one reading per SAMPLE_MS is taken, because fifty
     * events about the same unchanged screen are one look at one screen, not fifty.
     */
    fun record(pkg: String, borderline: Boolean): Action =
        recordAt(pkg, borderline, System.currentTimeMillis())

    /**
     * [record] with the clock passed in. The tuning here is all about elapsed time, so the
     * tests drive it through a fake clock rather than sitting through three real minutes.
     */
    @Synchronized
    internal fun recordAt(pkg: String, borderline: Boolean, now: Long): Action {
        if (now - (lastAt[pkg] ?: 0L) < SAMPLE_MS) return Action.NONE
        lastAt[pkg] = now
        val q = hits.getOrPut(pkg) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > WINDOW_MS) q.removeFirst()
        if (!borderline) {
            // A clean screen DRAINS the bucket by one rather than emptying it. Emptying it
            // would make the whole rule trivial to sit out - one clean frame between every
            // bad one and it never fires. Draining keeps it honest in both directions.
            if (q.isNotEmpty()) q.removeFirst()
            if (q.isEmpty()) warned.remove(pkg)
            return Action.NONE
        }
        q.addLast(now)
        return when {
            q.size >= BLOCK_AT -> { reset(pkg); Action.BLOCK }
            q.size >= WARN_AT && warned.add(pkg) -> {
                warnPkg = pkg; warnUntil = now + WARN_HOLD_MS
                Action.WARN
            }
            else -> Action.NONE
        }
    }

    /** True while the warning cover for [pkg] should be up. */
    fun warningUp(pkg: String?): Boolean =
        pkg != null && pkg == warnPkg && System.currentTimeMillis() < warnUntil

    /**
     * How full the bucket is, as of the last reading taken. Not re-pruned here on purpose:
     * recordAt prunes on every call, so this is accurate the moment it matters, and leaving
     * it alone keeps the readout free of a clock this object otherwise never needs.
     */
    @Synchronized
    fun pressure(pkg: String): Int = hits[pkg]?.size ?: 0

    /** A clean screen, or a real block: either way this app's run of near-misses is over. */
    @Synchronized
    fun clear(pkg: String?) {
        if (pkg == null) return
        hits.remove(pkg); lastAt.remove(pkg); warned.remove(pkg)
        if (warnPkg == pkg) warnUntil = 0L
    }

    @Synchronized
    private fun reset(pkg: String) {
        hits.remove(pkg); lastAt.remove(pkg); warned.remove(pkg)
    }
}


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
 *  - DuckDuckGo (com.duckduckgo.mobile.android) IS blocked (it's in AppConfig.BLOCKED_BROWSERS):
 *    only Firefox is allowed, so the DDG search engine can't be reached through its own app.
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
        if (pkg in ALLOWED_BROWSERS) return null         // only Firefox; DuckDuckGo is NOT here
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
    private val ALLOWED_BROWSERS get() = AppConfig.ALLOWED_BROWSERS

    // ================================================================
    // EDIT BELOW - the browser package names to block. All lowercase.
    // Only Firefox is in ALLOWED_BROWSERS; every other browser (DuckDuckGo included)
    // is blocked, whether it's in the static list below or detected at runtime.
    // ================================================================
    private val BLOCKED_BROWSERS get() = AppConfig.BLOCKED_BROWSERS
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

    private val ALLOW_SUBSTRINGS get() = AppConfig.LOCKDOWN_ALLOWED_SUBSTRINGS

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
