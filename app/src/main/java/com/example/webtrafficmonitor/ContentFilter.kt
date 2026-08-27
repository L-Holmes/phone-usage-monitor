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

    // Plain host/txt sources the APP can fetch and parse itself.
    //
    // 2026-08-04: HaGeZi's lists were added alongside the three originals. They are actively
    // maintained, far larger, and - this is the part that matters - they cover the two
    // categories our hand-written files cannot realistically keep up with:
    //
    //   • doh-vpn-proxy-bypass  ~17,500 hosts. Our hand-written domains_vpn.txt has 40. Ours
    //     is the readable core that survives a list going stale; theirs is the one that
    //     survives somebody actually shopping for a VPN.
    //   • nosafesearch          ~200 hosts. Search engines that cannot enforce SafeSearch,
    //     which is precisely the set we want gone given Google is our only allowed engine.
    //
    // All of it lands in ONE merged set. Categorised reporting would need one set per list
    // and roughly triple the memory; the block screen already names a category when one of
    // our own files matched, which is the case where the distinction is useful.
    val NETWORK_SOURCES: List<String> = listOf(
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
        "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/pornography-hosts",
        "https://raw.githubusercontent.com/blocklistproject/Lists/master/porn.txt",
        // HaGeZi - "wildcard" format is one bare host per line, which parseHost already reads.
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/nsfw-onlydomains.txt",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/doh-vpn-proxy-bypass-onlydomains.txt",
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/nosafesearch-onlydomains.txt",
    )
    // .tar.gz archives handled by the build script only (not fetched in-app):
    val SCRIPT_ONLY_SOURCES: List<String> = listOf(
        "https://dsi.ut-capitole.fr/blacklists/download/adult.tar.gz",     // UT1 mixed_adult
        "https://dsi.ut-capitole.fr/blacklists/download/lingerie.tar.gz",  // UT1 lingerie
    )

    private const val BUNDLED_ASSET = "blocklist/adult_hosts.txt.gz"
    /** The old cache: one host per line, gzipped. Read once on upgrade, then deleted. */
    private const val LEGACY_CACHE = "adult_hosts_cache.txt.gz"
    /** The cache we keep now: sorted 64-bit fingerprints, nothing else. See the note below. */
    private const val CACHE_NAME = "adult_hosts.fp"
    private const val CACHE_MAGIC = 0x57544D31            // "WTM1"

    // ═════════════════════════════════════════════════════════════════════════════════
    //  WHY THIS IS A LongArray AND NOT A HashSet<String>
    // ═════════════════════════════════════════════════════════════════════════════════
    //  It was `HashSet<String>` of ~550,000 hosts, and that cost about **eighty megabytes
    //  of Java heap** - roughly a hundred bytes per host once you count the String object,
    //  its byte array, the HashMap.Node and the table slot. It was, by a distance, the
    //  largest thing in the process.
    //
    //  That is not merely wasteful, it is the mechanism behind half the 2026-08-27 report.
    //  A background process holding 80MB is a prime candidate for the low-memory killer, and
    //  the moment you ask the Play Store to download a few apps the phone goes looking for
    //  exactly that. Killing this process stops all blocking and leaves the app unable to
    //  start. Being small is a RELIABILITY feature here, not a tidiness one.
    //
    //  So we no longer keep the hosts. We keep a sorted array of 64-bit fingerprints of
    //  them - 8 bytes each, 4.4MB for the lot, allocated once and never touched again -
    //  and answer isBlocked() with a binary search.
    //
    //  ── "SO IT CAN BE WRONG?" ────────────────────────────────────────────────────────
    //  In principle a hash can collide, and blocking a site that is not on the list would be
    //  the worst kind of bug this app has. So, the arithmetic, out loud:
    //
    //    P(one innocent host collides with any of 550,000 entries) = 550_000 / 2^64
    //                                                             ≈ 0.00000000000003
    //
    //  At ten million lookups - far more than a phone will do in years - the expected number
    //  of false blocks is about three in ten million. It is many orders of magnitude below
    //  the chance that the machine-built list simply contains a wrong domain, which is a
    //  thing that demonstrably happens and is why domains_trusted.txt outranks this list
    //  (see the note in AccessibilityService.evaluateBlock). The safety valve for a wrong
    //  answer already exists and covers this case identically.
    //
    //  Nothing enumerates this list - the UI shows a count, and the block screen names the
    //  host it was asked about - so giving up the strings costs no feature.
    @Volatile private var fingerprints: HostFingerprints? = null
    @Volatile private var loading = false

    val isReady: Boolean get() = fingerprints != null

    /** How many hosts are loaded. 0 when the list has not been built yet. */
    val size: Int get() = fingerprints?.size ?: 0

    /** Load once: cache → (seed from bundled asset) → download & cache. Safe to call repeatedly. */
    fun warmUp(context: Context) {
        if (fingerprints != null || loading) return
        loading = true
        val app = context.applicationContext
        Thread {
            try {
                val cache = java.io.File(app.filesDir, CACHE_NAME)
                val legacy = java.io.File(app.filesDir, LEGACY_CACHE)
                when {
                    cache.exists() && cache.length() > 0L -> {
                        fingerprints = readCache(cache)?.let { HostFingerprints(it) }
                        Log.i("DomainBlocklist", "loaded ${size} hosts from cache")
                    }
                    // Upgrading from the old text cache: convert it in place, once. Streamed,
                    // so the 550k Strings this replaces are never all in memory at the same
                    // time - which is the entire point of the change.
                    legacy.exists() && legacy.length() > 0L -> {
                        val converted = readHostStream(java.util.zip.GZIPInputStream(legacy.inputStream()))
                        fingerprints = HostFingerprints(converted)
                        writeCache(cache, converted)
                        legacy.delete()
                        Log.i("DomainBlocklist", "converted ${size} hosts from the old cache")
                    }
                    else -> {
                        // Seed from the bundled asset (if any) so blocking works immediately.
                        tryLoadAsset(app)?.let { fingerprints = HostFingerprints(it) }
                        // Then build from the network and cache it for next time.
                        val built = downloadAndBuild()
                        if (built != null && built.isNotEmpty()) {
                            writeCache(cache, built)
                            fingerprints = HostFingerprints(built)
                            Log.i("DomainBlocklist", "built ${built.size} hosts from network; cached")
                        } else if (fingerprints == null) {
                            Log.w("DomainBlocklist", "no cache, no asset, no network - blocklist empty for now")
                        }
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
        val dir = context.applicationContext.filesDir
        java.io.File(dir, CACHE_NAME).delete()
        java.io.File(dir, LEGACY_CACHE).delete()
        fingerprints = null
        warmUp(context)
    }

    /** True if the host, or any of its parent domains, is on the adult blocklist. */
    fun isBlocked(host: String): Boolean = fingerprints?.contains(host) == true

    /**
     * A growing long[]. Not a List<Long> - that would box every entry and put us straight
     * back into the megabytes this change exists to remove.
     */
    private class LongList(initial: Int = 1 shl 16) {
        var data = LongArray(initial)
            private set
        var size = 0
            private set

        fun add(v: Long) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        /** Sorted, deduplicated, trimmed to length. The array this returns is the final one. */
        fun finish(): LongArray {
            val out = data.copyOf(size)
            java.util.Arrays.sort(out)
            var n = 0
            for (i in out.indices) if (n == 0 || out[i] != out[n - 1]) out[n++] = out[i]
            return if (n == out.size) out else out.copyOf(n)
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────────────
    private fun tryLoadAsset(context: Context): LongArray? = try {
        readHostStream(java.util.zip.GZIPInputStream(context.assets.open(BUNDLED_ASSET)))
    } catch (t: Throwable) { null }

    /** Read a one-host-per-line stream straight into fingerprints. No host is ever kept. */
    private fun readHostStream(input: java.io.InputStream): LongArray {
        val list = LongList()
        input.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val h = line.trim()
                if (h.isNotEmpty() && !h.startsWith("#")) list.add(HostFingerprints.of(h))
            }
        }
        return list.finish()
    }

    private fun downloadAndBuild(): LongArray? {
        val list = LongList(1 shl 19)
        var anyOk = false
        for (url in NETWORK_SOURCES) {
            try {
                fetchInto(url, list)
                anyOk = true
            } catch (t: Throwable) {
                Log.w("DomainBlocklist", "fetch failed $url: ${t.message}")
            }
        }
        if (!anyOk || list.size == 0) return null
        return list.finish()
    }

    /** Stream one source into [out]. Parsed and hashed line by line; nothing accumulates. */
    private fun fetchInto(urlStr: String, out: LongList) {
        val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 45_000; requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "web-traffic-monitor")
        }
        try {
            conn.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { line -> parseHost(line)?.let { out.add(HostFingerprints.of(it)) } }
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

    // ── the cache file ────────────────────────────────────────────────────────────────
    //  magic, count, then `count` big-endian longs. Deliberately NOT gzipped: hashes are
    //  incompressible, so gzip would spend CPU to save nothing, and reading raw means the
    //  load is one sequential read with no parsing and no transient objects at all - the
    //  peak memory of a cold start is the finished array and a 64KB buffer.
    private const val CHUNK_LONGS = 8192

    private fun readCache(file: java.io.File): LongArray? {
        java.io.DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != CACHE_MAGIC) return null
            val count = input.readInt()
            if (count <= 0 || count > MAX_HOSTS) return null
            val out = LongArray(count)
            val buf = ByteArray(CHUNK_LONGS * 8)
            var i = 0
            while (i < count) {
                val n = minOf(CHUNK_LONGS, count - i)
                input.readFully(buf, 0, n * 8)
                val lb = java.nio.ByteBuffer.wrap(buf, 0, n * 8).asLongBuffer()
                lb.get(out, i, n)
                i += n
            }
            return out
        }
    }

    private fun writeCache(file: java.io.File, values: LongArray) {
        val tmp = java.io.File(file.parentFile, file.name + ".tmp")
        java.io.DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(CACHE_MAGIC)
            out.writeInt(values.size)
            val buf = ByteArray(CHUNK_LONGS * 8)
            var i = 0
            while (i < values.size) {
                val n = minOf(CHUNK_LONGS, values.size - i)
                val lb = java.nio.ByteBuffer.wrap(buf, 0, n * 8).asLongBuffer()
                lb.put(values, i, n)
                out.write(buf, 0, n * 8)
                i += n
            }
        }
        // Rename last, so a cache half-written when the process died is never read back.
        if (!tmp.renameTo(file)) tmp.delete()
    }

    /** Sanity bound on a cache header, so a corrupt file cannot ask for a gigabyte. */
    private const val MAX_HOSTS = 5_000_000
}


// ── The blocklist's lookup half, on its own so it can be tested ──────────────────────
/**
 * A set of hosts, stored as SORTED 64-BIT FINGERPRINTS rather than as strings, answering
 * "is this host, or any parent domain of it, in the set?".
 *
 * Split out of DomainBlocklist for one reason: DomainBlocklist needs a Context, a network
 * and an 8MB cache file, and none of that can run in a JVM unit test - so the part that
 * actually decides whether a page is blocked was, until 2026-08-27, untested. This class
 * needs none of it. See HostFingerprintsTest.
 *
 * The memory reasoning, and the collision arithmetic, are on DomainBlocklist.
 */
class HostFingerprints(private val sorted: LongArray) {

    val size: Int get() = sorted.size

    /**
     * The PARENT WALK. "cdn.images.example.com" is checked as itself, then
     * "images.example.com", then "example.com" - so a list entry covers its subdomains -
     * and stops before "com", because a bare TLD entry would block the internet.
     */
    fun contains(host: String): Boolean {
        var cur = host.lowercase().trim().removePrefix("www.")
        if (cur.isEmpty()) return false
        while (true) {
            if (java.util.Arrays.binarySearch(sorted, of(cur)) >= 0) return true
            val dot = cur.indexOf('.')
            if (dot < 0) return false
            cur = cur.substring(dot + 1)
            if (cur.indexOf('.') < 0) return false   // don't test a bare TLD
        }
    }

    companion object {
        /**
         * FNV-1a 64 over the host's bytes, then a splitmix64 finalizer.
         *
         * FNV alone is fine for a hash table but its low bits are poorly mixed, and we
         * binary search a sorted array rather than mask into buckets - so every bit has to
         * earn its place. The finalizer is what makes the collision arithmetic honest.
         *
         * Hosts are ASCII by the time they reach here (parseHost rejects anything else), so
         * iterating characters and iterating bytes are the same thing.
         */
        fun of(host: String): Long {
            var h = -0x340d631b7bdddcdbL                   // FNV-1a 64 offset basis
            for (c in host) {
                h = h xor (c.code.toLong() and 0xFF)
                h *= 0x100000001b3L                        // FNV prime
            }
            var z = h
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        /** Build from host names. Used by the tests and by anything holding a small list. */
        fun from(hosts: Collection<String>): HostFingerprints {
            val out = LongArray(hosts.size)
            var i = 0
            for (h in hosts) out[i++] = of(h.lowercase().trim())
            java.util.Arrays.sort(out)
            return HostFingerprints(out)
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
    val DOMAINS: Set<String> get() = FilterData.set("domains_greylist.txt")

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
    val DOMAINS: Set<String> get() = FilterData.set("domains_search_engines.txt")
    fun isBlocked(host: String): Boolean = hostOrParentIn(host, DOMAINS)
}


// ── Always-banned hosts: blocked in EVERY mode the monitor runs in ────────────────────
//  The whole hand-maintained ban list: reddit frontends/mirrors/viewers, reddit itself,
//  borderline shopping/fashion and imageboards. Used to be Strict+ only (2026-08-01
//  promotion): but a bypass surface that Relaxed lets through is a bypass surface, full
//  stop - dropping to Relaxed must not be the way around the list. A bare domain also
//  covers its subdomains. NB the extension's domains.js still holds these as strict-only -
//  mirror the promotion there next time the plugin is touched.
object AlwaysBlocklist {
    val DOMAINS: Set<String> get() = FilterData.set("domains_banned.txt")
    fun isBlocked(host: String): Boolean = hostOrParentIn(host, DOMAINS)
}


// ── IN-APP BROWSERS: is this string an ADDRESS, or a sentence mentioning one? ─────────
/**
 * The rule behind reading a domain out of an app's own in-app browser (§2.5). Pure, so it
 * can be tested - it is the part that decides whether a piece of on-screen text is treated
 * as THE PAGE'S ADDRESS, and getting it wrong in the permissive direction would let a page
 * block itself simply by quoting a URL, or misattribute a block to the wrong site.
 *
 * The rule: the ENTIRE trimmed string has to be a host or a URL. Any whitespace and it is
 * prose, not an address bar.
 */
object InAppBrowser {

    private const val MAX_LEN = 300

    private val HOST = Regex(
        """^(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})(?:[/?#]\S*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Final labels that are almost always a FILE, not a top-level domain. In-app browsers
     * show downloads and attachments in the same chrome as the address, and "photo.jpg" is
     * a perfectly well-formed hostname as far as a regex is concerned.
     *
     * Note that .zip, .mov, .app and .dev are all genuine TLDs. In this one context - a
     * string sitting in an app's browser chrome - a filename is the likelier reading, and
     * the cost of being wrong is only that one domain rule does not fire on one page. The
     * cost the other way is treating every downloaded file as the page's address.
     */
    private val FILE_ENDINGS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "ico",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf",
        "zip", "rar", "tar", "gz", "apk", "exe", "dmg", "iso",
        "mp3", "mp4", "mov", "avi", "mkv", "wav", "webm", "flac", "m4a",
        "html", "htm", "css", "js", "json", "xml", "md", "log", "tmp", "bak",
    )

    fun bareHost(raw: String?): String? {
        val t = raw?.trim() ?: return null
        if (t.isEmpty() || t.length > MAX_LEN) return null
        if (t.any { it.isWhitespace() }) return null
        val host = HOST.find(t)?.groupValues?.get(1)?.lowercase() ?: return null
        // A bare "photo.jpg" is a download, not the page you are on.
        if ('/' !in t && host.substringAfterLast('.') in FILE_ENDINGS) return null
        return host
    }
}


// ── SAFESEARCH: Google is the only engine we allow, so it has to actually be safe ─────
/**
 * We block every search engine except Google. That is worth very little on its own, because
 * Google with SafeSearch OFF is one of the highest-yield adult surfaces there is - image
 * search in particular. Every established blocker treats forcing SafeSearch as table
 * stakes; until 2026-08-04 we did nothing about it at all.
 *
 * We cannot set the setting for them - it lives in a Google account, and DNS-level
 * forcing (forcesafesearch.google.com) is not available to an app that does no DNS. What we
 * CAN do is refuse the page when the URL EXPLICITLY says SafeSearch is off.
 *
 * That is the whole scope, deliberately. Ordinary searching is untouched, image search is
 * untouched - this fires only on "&safe=off", where somebody has gone and turned the safety
 * filter off. There is no innocent route to that string.
 *
 * Treated as a bypass attempt rather than an ordinary block, because there is no innocent
 * way to arrive at `&safe=off`.
 */
object SafeSearch {

    /** Hosts this applies to. Only Google - everything else is blocked outright anyway. */
    private val ENGINES = listOf("google.")

    /** The query strings that mean "SafeSearch is off". */
    private val OFF_MARKERS = listOf("safe=off", "safe=images", "safeui=off", "safe=0")

    fun isSearchHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return ENGINES.any { h.contains(it) }
    }

    /** True when the URL explicitly turns SafeSearch off. */
    fun isExplicitlyOff(url: String?): Boolean {
        val u = url?.lowercase() ?: return false
        return OFF_MARKERS.any { it in u }
    }

    // REMOVED 2026-08-04: image-search blocking. Google Images is an ordinary tool and
    // blocking it is over-blocking, full stop. Web browsing is monitored LIGHTLY here -
    // the Firefox add-on is what covers images, and it covers them properly by looking at
    // the pictures rather than by refusing the page. Do not add this back.
}


// =====================================================================================
//  BLOCKED CATEGORIES  —  the hand-maintained app + site lists, by category
// =====================================================================================
/**
 * Four categories, each an APP list and a SITE list, each in its own file under
 * assets/filter/. Adding a site or an app means editing a text file, never this code -
 * which is the whole reason they are files: a list that lives in Kotlin can only be changed
 * by someone who can build the app, and these lists change far more often than the code
 * around them does.
 *
 * The categories are separate rather than one big blocklist because the BLOCK SCREEN says
 * which one caught you, and "blocked: VPN app" and "blocked: adult site" are different
 * sentences that deserve different reactions. The dev console lists them the same way.
 *
 * All four are enforced in EVERY mode above Off. None of them is a judgement call the
 * filter has to make - a VPN is a VPN in Relaxed too - so none of them is mode-gated.
 *
 * ── AN APP AND ITS WEBSITE ARE SEPARATE DECISIONS ──────────────────────────────────
 * Deliberately so. Facebook and YouTube are blocked as APPS but reachable as SITES,
 * because in a browser the address is visible, the page text is scored, and the image
 * add-on is watching from the inside - none of which is true in an app. If you want one of
 * them banned outright, add the domain to its category's domains file.
 */
object BlockedCategories {

    data class Category(
        val id: String,
        /** What the block screen and the dev console call it. */
        val title: String,
        /** One line: why this category exists at all. */
        val why: String,
        /** What to call the lists themselves, so a card never just says "Apps". */
        val appsTitle: String,
        val appsFile: String,
        val domainsFile: String,
    )

    val ALL: List<Category> = listOf(
        Category(
            "ugc", "User-uploaded video & photo feeds",
            "Endless media uploaded by strangers, tuned to whatever you last lingered on. " +
                "Inside an app there is no address to read and no add-on to lean on.",
            appsTitle = "User content upload & sharing",
            "apps_ugc.txt", "domains_ugc.txt",
        ),
        Category(
            "adult", "Sexualised content",
            "The hand-maintained core of the adult block, kept separate from the downloaded " +
                "blocklist so a host can never quietly fall out of it.",
            appsTitle = "Sexualised content",
            "apps_adult.txt", "domains_adult.txt",
        ),
        Category(
            "strangers", "Livestreams, forums, image boards, dating",
            "An endless supply of strangers and no moderation you can rely on.",
            appsTitle = "Livestreams, forums & dating",
            "apps_strangers.txt", "domains_strangers.txt",
        ),
        Category(
            "bypass", "Filter bypass services",
            why = "They re-serve somebody else's page from their own domain, which is exactly " +
                "what defeats a host-based block. None of them hosts anything itself.",
            appsTitle = "Translation proxies, caches & archives",
            appsFile = "apps_bypass.txt", domainsFile = "domains_bypass.txt",
        ),
        Category(
            "ai_companion", "AI companions & NSFW chatbots",
            why = "A category that barely existed when the older blockers wrote their lists, " +
                "and the fastest-growing surface there is.",
            appsTitle = "AI companion & chatbot",
            appsFile = "apps_ai_companion.txt", domainsFile = "domains_ai_companion.txt",
        ),
                Category(
            "clients", "Third-party apps for blocked services",
            why = "Blocking a service by blocking the app it ships is a block on one icon. " +
                "These are the same feeds, signed in to through somebody else's app or " +
                "re-served from somebody else's domain - which is exactly what a " +
                "package-name block and a host block both miss.",
            appsTitle = "Third-party clients & front-ends",
            appsFile = "apps_clients.txt", domainsFile = "domains_clients.txt",
        ),
        Category(
            "vpn", "VPNs, proxies and anonymisers",
            "The one tool that defeats every network-level control at once - and reaching " +
                "for one is itself the signal worth acting on.",
            appsTitle = "VPNs, proxies & anonymisers",
            "apps_vpn.txt", "domains_vpn.txt",
        ),
    )

    /** Friendly name → package, for one category. Cached by FilterData. */
    fun apps(c: Category): Map<String, String> = FilterData.map(c.appsFile)

    /** The hosts for one category. Cached by FilterData. */
    fun domains(c: Category): Set<String> = FilterData.set(c.domainsFile)

    /** The category that blocks this package, or null. */
    fun appCategory(pkg: String?): Category? {
        if (pkg.isNullOrBlank()) return null
        val p = pkg.lowercase()
        return ALL.firstOrNull { p in apps(it).values }
    }

    /** The category that blocks this host (or a parent domain of it), or null. */
    fun hostCategory(host: String?): Category? {
        if (host.isNullOrBlank()) return null
        return ALL.firstOrNull { hostOrParentIn(host, domains(it)) }
    }
}


// ── Strict+ blocklist: hosts blocked when NOT in Relaxed ──────────────────────────────
//  EMPTY since 2026-08-01 - everything it held was promoted to AlwaysBlocklist above. The
//  mechanism (and its file, domains_strict_only.txt) stays, so a host that genuinely should
//  be allowed in Relaxed but not Strict+ has somewhere to go later.
object StrictOnlyBlocklist {
    val DOMAINS: Set<String> get() = FilterData.set("domains_strict_only.txt")
    fun isBlocked(host: String): Boolean = hostOrParentIn(host, DOMAINS)
}


// ── Mode-gated keyword blocks: MOVED (2026-08-04) ─────────────────────────────────────
//  The old `ModeKeywords` lived here and blocked a page OUTRIGHT on a blunt substring match
//  against the title/URL, with no score behind it. That is what blocked ordinary apps for
//  saying "browser", and "necklace" for containing "lace".
//
//  It is now `ModeFragments` in TextFilter.kt: the same spaced-out spellings, but each one
//  carries a WEIGHT and is scored by BorderlineScorer with everything else, so a soft
//  fragment can corroborate a block and can no longer be one. Words belong in the words
//  file; this one is the domains file.
