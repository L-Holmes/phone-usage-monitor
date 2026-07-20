package com.example.webtrafficmonitor

import android.content.Context
import android.util.Log
import java.util.zip.GZIPInputStream

// =====================================================================================
//  CONTENT FILTER (DOMAINS)  —  the "banned / greylisted SITES" half of the filter.
// =====================================================================================
//
//  Everything that decides whether a HOST looks adult lives here:
//    * DomainBlocklist   — the ~550k-host adult blocklist (loaded from the bundled .gz),
//    * DomainStrikes     — repeat-offender domains get blocked for a while,
//    * DomainGreylist    — our own list of mixed-content sites (Reddit, etc.) to limit.
//
//  The "banned WORDS" half — the text/title/URL scorer and its word tiers — lives in its
//  own file, TextFilter.kt (BorderlineScorer + BannedWords + friends), so it can be kept
//  in lock-step with the Firefox extension's textfilter.js port.
// =====================================================================================


// ── The ~550k-host adult domain blocklist ─────────────────────────────────────────────
//  The app builds this itself, ONCE: on first run it downloads the source lists, dedups
//  them, and caches the result to internal storage (gzipped). Every run after that loads
//  straight from the cache — no re-download. If the network is unavailable on that first
//  run, it falls back to the bundled asset (assets/blocklist/adult_hosts.txt.gz) if present,
//  so blocking still works; the next run with a connection builds and caches for good.
//
//  REQUIREMENTS / NOTES:
//    * Needs INTERNET permission in AndroidManifest.xml:
//        <uses-permission android:name="android.permission.INTERNET"/>
//    * Only the three plain host-format sources are fetched in-app (they're ~99.9% of the
//      hosts). The two UT1 sources are .tar.gz archives — awkward to unpack on-device — so
//      they're left to the build script. Their combined contribution is a few hundred hosts.
//    * Runs on a background thread; isBlocked() just returns false until the set is ready.
//    * To force a fresh rebuild (e.g. a settings button), call rebuild(context).
object DomainBlocklist {

    // Plain host/txt sources the APP can fetch and parse itself:
    val NETWORK_SOURCES: List<String> = listOf(
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
        "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/pornography-hosts",
        "https://raw.githubusercontent.com/blocklistproject/Lists/master/porn.txt",
    )
    // .tar.gz archives handled by the build script only (not fetched in-app):
    val SCRIPT_ONLY_SOURCES: List<String> = listOf(
        "https://dsi.ut-capitole.fr/blacklists/download/adult.tar.gz",     // UT1 mixed_adult
        "https://dsi.ut-capitole.fr/blacklists/download/lingerie.tar.gz",  // UT1 lingerie
    )

    private const val BUNDLED_ASSET = "blocklist/adult_hosts.txt.gz"
    private const val CACHE_NAME = "adult_hosts_cache.txt.gz"

    @Volatile private var hosts: HashSet<String>? = null
    @Volatile private var loading = false

    val isReady: Boolean get() = hosts != null

    /** Load once: cache → (seed from bundled asset) → download & cache. Safe to call repeatedly. */
    fun warmUp(context: Context) {
        if (hosts != null || loading) return
        loading = true
        val app = context.applicationContext
        Thread {
            try {
                val cache = java.io.File(app.filesDir, CACHE_NAME)
                if (cache.exists() && cache.length() > 0L) {
                    hosts = readGz(java.util.zip.GZIPInputStream(cache.inputStream()))
                    Log.i("DomainBlocklist", "loaded ${hosts?.size ?: 0} hosts from cache")
                } else {
                    // Seed from the bundled asset (if any) so blocking works immediately.
                    tryLoadAsset(app)?.let { hosts = it }
                    // Then build from the network and cache it for next time.
                    val built = downloadAndBuild()
                    if (built != null && built.isNotEmpty()) {
                        writeGz(cache, built)
                        hosts = built
                        Log.i("DomainBlocklist", "built ${built.size} hosts from network; cached")
                    } else if (hosts == null) {
                        Log.w("DomainBlocklist", "no cache, no asset, no network — blocklist empty for now")
                    }
                }
            } catch (t: Throwable) {
                Log.w("DomainBlocklist", "warmUp failed: ${t.message}")
            } finally {
                loading = false
            }
        }.start()
    }

    /** Delete the cache and rebuild from the network on the next warmUp. */
    fun rebuild(context: Context) {
        java.io.File(context.applicationContext.filesDir, CACHE_NAME).delete()
        hosts = null
        warmUp(context)
    }

    /** True if the host, or any of its parent domains, is on the adult blocklist. */
    fun isBlocked(host: String): Boolean {
        val set = hosts ?: return false
        var cur = host.lowercase().removePrefix("www.")
        while (true) {
            if (cur in set) return true
            val dot = cur.indexOf('.')
            if (dot < 0) return false
            cur = cur.substring(dot + 1)
            if (cur.indexOf('.') < 0) return false   // don't test a bare TLD
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────────────
    private fun tryLoadAsset(context: Context): HashSet<String>? = try {
        readGz(java.util.zip.GZIPInputStream(context.assets.open(BUNDLED_ASSET)))
    } catch (t: Throwable) { null }

    private fun downloadAndBuild(): HashSet<String>? {
        val set = HashSet<String>(600_000)
        var anyOk = false
        for (url in NETWORK_SOURCES) {
            try {
                fetchHosts(url).forEach { set.add(it) }
                anyOk = true
            } catch (t: Throwable) {
                Log.w("DomainBlocklist", "fetch failed $url: ${t.message}")
            }
        }
        return if (anyOk && set.isNotEmpty()) set else null
    }

    private fun fetchHosts(urlStr: String): List<String> {
        val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 45_000; requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "web-traffic-monitor")
        }
        try {
            return conn.inputStream.bufferedReader().useLines { seq ->
                seq.mapNotNull { parseHost(it) }.toList()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Parse a hosts/txt line ("0.0.0.0 domain", "127.0.0.1 domain", or just "domain"). */
    private fun parseHost(line: String): String? {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#")) return null
        val parts = l.split(Regex("\\s+"))
        var host = (if (parts.size >= 2) parts[1] else parts[0]).lowercase().removePrefix("www.")
        val hash = host.indexOf('#'); if (hash >= 0) host = host.substring(0, hash)
        host = host.trim()
        if (host.isEmpty() || host == "localhost" || !host.contains('.') ||
            host.contains('/') || host.any { it.isWhitespace() }) return null
        return host
    }

    private fun readGz(input: java.util.zip.GZIPInputStream): HashSet<String> {
        val set = HashSet<String>(600_000)
        input.bufferedReader().useLines { lines ->
            lines.forEach { val h = it.trim(); if (h.isNotEmpty() && !h.startsWith("#")) set.add(h) }
        }
        return set
    }

    private fun writeGz(file: java.io.File, set: Set<String>) {
        java.util.zip.GZIPOutputStream(file.outputStream()).bufferedWriter().use { w ->
            for (h in set) { w.write(h); w.newLine() }
        }
    }
}


// ── Repeat-offender domains: a handful of strikes → blocked for a while ───────────────
//  Uses AppConfig.DOMAIN_STRIKE_THRESHOLD / DOMAIN_BLOCK_MS. Self-contained (SharedPrefs).
object DomainStrikes {
    private const val PREFS = "domain_strikes"

    /** Record a strike against a domain; returns true once it tips into a block. */
    fun strike(context: Context, host: String): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "n_" + host.lowercase()
        val n = p.getInt(key, 0) + 1
        p.edit().putInt(key, n).apply()
        if (n >= AppConfig.DOMAIN_STRIKE_THRESHOLD) {
            p.edit().putLong("until_" + host.lowercase(), System.currentTimeMillis() + AppConfig.DOMAIN_BLOCK_MS).apply()
            return true
        }
        return false
    }

    fun isBlocked(context: Context, host: String): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = p.getLong("until_" + host.lowercase(), 0L)
        return until > System.currentTimeMillis()
    }

    fun reset(context: Context, host: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("n_" + host.lowercase()).remove("until_" + host.lowercase()).apply()
    }
}


// ── Our own greylist of MIXED-content sites (limit, don't block) ──────────────────────
//  There is no clean community "greylist" repo — the community social lists block sites
//  outright and even lump Reddit in with Facebook — so this is a hand-curated list of
//  sites that carry a real feed of user content (some of it adult) but are also broadly
//  useful. Treat like the greylisted APPS: allow, but on a time budget rather than block.
//  (Provides the data + a matcher; wiring it into the per-domain time limit in the
//  service is a small follow-up.)
object DomainGreylist {
    val DOMAINS: Set<String> = setOf(
        "reddit.com", "redd.it",
        "x.com", "twitter.com", "t.co",
        "tumblr.com",
        "imgur.com",
        "pinterest.com",
        "deviantart.com",
        "quora.com",
        "9gag.com",
        "twitch.tv",
        "discord.com",
        "tiktok.com",
        "instagram.com",
        "snapchat.com",
        "facebook.com", "fb.com",
        "vk.com",
        "flickr.com",
        "wattpad.com",
    )

    /** True if the host, or any parent domain, is on our greylist. */
    fun isGreylisted(host: String): Boolean = hostOrParentIn(host, DOMAINS)
}


// ── Shared host walk: is [host] (or a parent domain) in [set]? ────────────────────────
//  "en.wikipedia.org" walks wikipedia.org, but never a bare TLD. Used by every domain
//  matcher below (matches the JS parentWalk in domains.js).
internal fun hostOrParentIn(host: String, set: Set<String>): Boolean {
    var cur = host.lowercase().removePrefix("www.")
    while (true) {
        if (cur in set) return true
        val dot = cur.indexOf('.')
        if (dot < 0) return false
        cur = cur.substring(dot + 1)
        if (cur.indexOf('.') < 0) return false   // don't test a bare TLD
    }
}


// ── Search engines: block ALL of them except Google (every mode) ──────────────────────
//  The user's rule: Google is the only search engine allowed — everything else, DuckDuckGo
//  included, is off the table so a "safe" alternative engine can't be used to run the same
//  searches. A bare domain also covers its subdomains (hostOrParentIn). Only the SEARCH host
//  is listed, not a whole company: "search.brave.com" (not brave.com), "search.yahoo.com"
//  (not yahoo.com — Yahoo Mail stays reachable).
//  NB: self-hosted metasearch (SearX/SearXNG) has no fixed domain, so it can't be enumerated
//  here; add specific instances as you meet them.
//  MIRROR TO JS: add these to a matching block list in domains.js.
object SearchEngineBlocklist {
    val DOMAINS: Set<String> = setOf(
        "duckduckgo.com", "duck.com",
        "bing.com",
        "search.brave.com",
        "ecosia.org",
        "qwant.com",
        "startpage.com",
        "search.yahoo.com",
        "yandex.com", "yandex.ru",
        "baidu.com",
        "ask.com",
        "search.aol.com",
        "mojeek.com",
        "you.com",
        "presearch.com",
        "swisscows.com",
        "gibiru.com",
        "metager.org",
        "dogpile.com",
        "searchencrypt.com",
        "onesearch.com",
        "lukol.com",
        "kagi.com",
        "perplexity.ai",
        "phind.com",
        "yep.com",
        "excite.com",
        "lycos.com",
        "webcrawler.com",
        "search.marginalia.nu",
        "searx.be", "searx.tiekoetter.com",   // a couple of well-known public SearX instances
    )

    fun isBlocked(host: String): Boolean = hostOrParentIn(host, DOMAINS)
}


// ── Strict+ blocklist: our own hand-maintained hosts, blocked when NOT in Relaxed ─────
//  These aren't on the big adult blocklist (reddit frontends/mirrors, reddit itself, and a
//  few "borderline" shopping/fashion sites). We don't want them blocked in Relaxed, so the
//  service only consults this when the mode is Strict or Super hardcore. A bare domain also
//  covers its subdomains. Mirrors StrictOnlyBlocklist in domains.js — keep the two in sync.
object StrictOnlyBlocklist {
    val DOMAINS: Set<String> = setOf(
        // ── Reddit frontends / mirrors / viewers ──────────────────────────────────────
        "photon-reddit.com", "lite.redgifs.com", "peekstr.com", "redlite.app",
        "scrolller.com", "rdx.overdevs.com", "viewri.com", "troddit.com",
        "ghostddit.pages.dev", "search.pullpush.io", "reditr.com", "reddit-viewer.com",
        "infini.wtf", "inini.wtf", "redditp.com", "reddit-stream.com", "reveddit.com",
        "rosint.dev", "subranking.com", "veo.world",
        // ── Reddit itself (Strict+ only; Relaxed keeps it greylisted/limited) ─────────
        "reddit.com", "redd.it",
        // ── Borderline shopping / fashion (Strict+ only) ──────────────────────────────
        "etsy.com", "depop.com", "shein.com",
        // ── Imageboards / "mixed" forums (Strict+ only) ───────────────────────────────
        // No canonical open-source list exists for these (the maintained host lists lump in
        // mainstream social), so this is hand-curated — add freely as you meet more.
        "4chan.org", "4channel.org", "8kun.top", "2ch.hk", "kohlchan.net",
        "endchan.org", "endchan.net", "soyjak.party", "sportschan.org",
        "wizchan.org", "lainchan.org",
    )

    fun isBlocked(host: String): Boolean = hostOrParentIn(host, DOMAINS)
}


// ── Mode-gated keyword blocks (title / URL substring match) ───────────────────────────
//  Unlike the adult text scorer, these are blunt substring matches on the page TITLE and URL
//  — deliberately including spaced-out fragments ("red dit", "ling eri", "bik ini") to catch
//  someone typing around a block. They are gated by mode, NOT scored:
//    * SUPER_HARDCORE — only when the mode is Super hardcore.
//    * STRICT_PLUS    — when the mode is Strict OR Super hardcore.
//  Nothing here fires in Relaxed (or Off). Mirrors the mode-gated keyword tiers you keep in
//  the extension. NOTE: short fragments like "lace" / "haul" WILL also match innocent words
//  ("necklace", "overhaul") — that's the intended bluntness, but keep it in mind.
object ModeKeywords {

    // Very hardcore: Super-hardcore-only. Reddit and the ways it gets typed around a filter.
    val SUPER_HARDCORE: List<String> = listOf(
        "reddit", "redd", "red dit", "re ddit", "reddi t", "redd it",
        "eddit", "e ddit", "r eddit",
    )

    // Hardcore: Strict and above.
    val STRICT_PLUS: List<String> = listOf(
        "scrolller", "scroller",
        "lingerie", "lin gerie", "lin geri", "lingeri", "ling eri",
        "lace",
        "try on haul", "try on hau", "t ry on haul", "try n haul", "haul",
        "sheer", "shee r", "sh eer",
        "browser", "brow ser",
        "bikini", "bik ini", "bi kini", "ikini",
    )

    /** The keyword that blocks this title/URL for the current mode, or null. */
    fun match(context: Context, title: String?, url: String?): String? {
        val active: List<String> = when {
            Mode.isSuperHardcore(context) -> SUPER_HARDCORE + STRICT_PLUS
            Mode.isStrict(context) -> STRICT_PLUS
            else -> return null                       // Relaxed / Off: nothing
        }
        val haystack = ((title ?: "") + "  " + (url ?: "")).lowercase()
        if (haystack.isBlank()) return null
        return active.firstOrNull { it in haystack }
    }
}
