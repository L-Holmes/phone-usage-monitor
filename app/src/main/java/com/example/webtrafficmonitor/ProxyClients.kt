package com.example.webtrafficmonitor

import android.content.Context

// =====================================================================================
//  THIRD-PARTY CLIENTS  —  the same blocked service, reached through somebody else's app
// =====================================================================================
//
//  ⚠️ THE HOLE THIS CLOSES  (2026-08-27)
//
//  Every app list in this project blocks a SERVICE by blocking the app that service
//  ships. Reddit is `com.reddit.frontpage`; Flickr is `com.flickr.android`. That is a
//  complete answer right up until somebody installs a DIFFERENT app that signs in to the
//  same account and shows the same feed - and there are dozens of those for every service
//  worth blocking. The report that started this was FlickFolio: Flickr's own app is on
//  the blocked list, and FlickFolio walked straight past it, because a package-name block
//  is a block on one icon rather than on a service.
//
//  Two answers, and they are meant to work together:
//
//    1. apps_clients.txt - the well-known ones, blocked from the first run like any other
//       category. Cheap, exact, and permanently incomplete.
//
//    2. THIS FILE - the detector, which is the part that matters. It watches for a service
//       giving itself away inside an app that is not that service's app:
//
//         • the service's OWN DOMAIN on screen. A third-party client cannot avoid this:
//           to sign in to Reddit it has to send you to Reddit, and that sign-in page is a
//           web page with an address we can already read (§2.5, readInAppBrowserHost).
//           One sighting is proof, and it is proof of the strongest kind - the app is
//           displaying the blocked service's own website.
//
//         • the service's OWN VOCABULARY. "subreddit", "photostream", "reblog": words
//           only one product's interface uses. Two different ones is a suspicion, not a
//           verdict; it takes a second sighting minutes later before anything is blocked,
//           for the same reason RepeatGate exists - one screen is a question, not an
//           answer.
//
//  What is detected is REMEMBERED (ProxyClients) and treated exactly like an app on the
//  blocked list, with the evidence recorded alongside it - because a detector that blocks
//  a whole app and cannot say why is one nobody can argue with when it gets it wrong.
//  Every detection is reversible from the app's own UI.
// =====================================================================================


/**
 * What each blocked service looks like from the outside. Parsed from
 * assets/filter/clients_services.txt - see that file for the format and for why each kind
 * of evidence is worth what it is.
 */
object ClientMarkers {

    data class Service(
        val id: String,
        val label: String,
        /** Hosts that belong to this service. One of these inside an app is proof. */
        val domains: Set<String>,
        /** Phrases only this service's own interface uses. Two make a suspect. */
        val strong: List<String>,
        /** Substrings of an app's name/package. Never proof; worth one marker. */
        val hints: List<String>,
    )

    /** Two different strong markers on one screen before an app is even a suspect. */
    const val MIN_MARKERS = 2

    @Volatile private var parsed: List<Service>? = null

    val ALL: List<Service>
        // langLines, not lines: this file lives under resources/filter/words/en/ rather
        // than in assets, for two reasons. Its "strong" markers are English UI phrases, so a
        // translated client needs the device language's file unioned in like every other
        // word list - and resources are readable from the pure-JVM tests, which is the only
        // way the false-positive cases below get tested at all (ClientMarkersTest).
        get() = parsed ?: parse(FilterData.langLines("clients_services.txt")).also { parsed = it }

    fun byId(id: String?): Service? = ALL.firstOrNull { it.id == id }

    private fun parse(lines: List<String>): List<Service> {
        val out = ArrayList<Service>()
        var id: String? = null
        var label = ""
        var domains = emptySet<String>()
        var strong = emptyList<String>()
        var hints = emptyList<String>()

        fun flush() {
            val theId = id ?: return
            out.add(Service(theId, label.ifBlank { theId }, domains, strong, hints))
        }

        for (line in lines) {
            val key = line.substringBefore(':', "").trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "service" -> { flush(); id = value.lowercase(); label = ""; domains = emptySet(); strong = emptyList(); hints = emptyList() }
                "label" -> label = value
                "domains" -> domains = split(value).toSet()
                "strong" -> strong = split(value)
                "hints" -> hints = split(value)
            }
        }
        flush()
        return out
    }

    private fun split(value: String): List<String> =
        value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    // ── the three tests ───────────────────────────────────────────────────────────────

    /**
     * PROOF. The service's own host is on screen inside this app.
     *
     * Matched with the same parent walk every other host rule uses, so "oauth.reddit.com"
     * and "www.reddit.com" both land on the "reddit.com" entry.
     */
    fun serviceForHost(host: String?): Service? {
        if (host.isNullOrBlank()) return null
        return ALL.firstOrNull { hostOrParentIn(host, it.domains) }
    }

    /**
     * SUSPICION. How many of one service's own phrases are on this screen, and which
     * service scored highest. [appName] contributes at most ONE marker, and only when the
     * screen has already produced something real - a name is a hint about what an app
     * claims to be, not evidence about what it is doing.
     *
     * Returns null unless a single service reaches [MIN_MARKERS].
     */
    fun suspectFromScreen(text: String?, appName: String?): Match? {
        if (text.isNullOrBlank()) return null
        val haystack = text.lowercase()
        val name = appName?.lowercase().orEmpty()
        var best: Match? = null
        for (service in ALL) {
            val found = distinct(service.strong.filter { it in haystack })
            if (found.isEmpty()) continue                       // a name alone proves nothing
            // The longest name hint that matches, not the first: "boost for" says far more
            // about what an app is than "reddit" does, and it is what the user will be shown
            // as the reason. List order should not decide which evidence gets quoted.
            val nameHit = service.hints.filter { it in name }.maxByOrNull { it.length }
            val markers = found.size + (if (nameHit != null) 1 else 0)
            if (markers < MIN_MARKERS) continue
            if (best == null || markers > best.markers) {
                best = Match(service, markers, found.take(3), nameHit)
            }
        }
        return best
    }

    /**
     * TWO SPELLINGS OF ONE WORD ARE ONE MARKER.
     *
     * "subreddit" and "subreddits" are both in the list, and a screen saying "subreddits"
     * once contains both - which would clear the two-marker bar off a single word and undo
     * the whole point of asking for two. Same shape as the word filter's FAMILIES rule: a
     * marker that is contained in another matched marker is the same signal, not a second
     * one. It also means the list can carry inflections without arming a trap.
     */
    private fun distinct(found: List<String>): List<String> =
        found.filter { candidate -> found.none { it != candidate && candidate in it } }

    data class Match(
        val service: Service,
        val markers: Int,
        /** The phrases that actually matched, for the "why" line. */
        val found: List<String>,
        /** The name hint that matched, if any. */
        val nameHit: String?,
    ) {
        /** The evidence, in the user's words. Every detection has to be able to say this. */
        fun why(): String {
            val words = found.joinToString(", ") { "\"$it\"" }
            return if (nameHit == null) words else "$words, and the name says \"$nameHit\""
        }
    }

    /**
     * IS THIS URL A SIGN-IN HANDOFF?
     *
     * The other half of the domain signal, and the half that catches the modern clients.
     *
     * A client that opens the service's sign-in page in a WebView IT OWNS is caught by
     * serviceForHost, because the host is read out of that app's own chrome. But most apps
     * now use a Custom Tab instead - the sign-in page opens in the BROWSER, the browser is
     * the app in front, and browsers are excluded from all of this for good reason.
     *
     * What still gives it away is the URL. An OAuth handoff is not a page anybody browses
     * to: "reddit.com/api/v1/authorize?client_id=..." only ever appears because an app asked
     * for it. So a browser showing an AUTHORIZE URL for a blocked service, with a plausible
     * app behind it in the recents, is the same confession arriving by a different route.
     *
     * ⚠️ It is deliberately the AUTHORIZE PATH and not the host. "The browser is on
     * reddit.com and app X was open a minute ago" would blame X for an ordinary browse; a
     * page nobody can reach without an app asking for it cannot be an ordinary browse.
     */
    private val AUTH_PATHS = listOf(
        "/oauth", "/authorize", "/api/v1/authorize", "/login/oauth",
        "/oauth2/authorize", "/services/oauth", "/signin/oauth", "/auth/authorize",
    )

    fun serviceForAuthUrl(host: String?, url: String?): Service? {
        if (url.isNullOrBlank()) return null
        val service = serviceForHost(host) ?: return null
        val path = url.lowercase().substringAfter("://", url.lowercase())
            .substringAfter('/', "")
        if (path.isEmpty()) return null
        return if (AUTH_PATHS.any { ("/$path").startsWith(it) || "/$path".contains(it) }) service
        else null
    }

    /**
     * IS THIS APP EVEN A CANDIDATE?
     *
     * ⚠️ THE EXCLUSIONS BELOW ARE THE WHOLE SAFETY STORY. Everything this detector does
     * ends in a whole app being blocked, so the question is not "could this be a client"
     * but "is there an innocent reason for this app to be showing me the word subreddit".
     * For each of these there plainly is:
     *
     *   • BROWSERS show anything at all; that is their job, and a web page is already
     *     handled by the domain rules.
     *   • THE PLAY STORE AND SETTINGS describe other apps for a living. A Play Store
     *     listing for Reddit is not a Reddit client, and it says "subreddit" all over it.
     *   • LAUNCHERS carry every app name on the device.
     *   • OUR OWN APP's block lists are made of these words.
     *   • An app already blocked by a category needs no second reason.
     */
    fun isScannable(pkg: String?, ownPackage: String): Boolean {
        if (pkg.isNullOrBlank() || pkg == ownPackage) return false
        val p = pkg.lowercase()
        if (AppBlocklist.isBrowser(p)) return false
        if (p in AppConfig.GUARDED_SETTINGS_PACKAGES) return false     // Play Store, Settings
        if (p in AppConfig.NOT_LOGGED_PACKAGES) return false           // launchers
        if (p in AppConfig.IGNORED_PACKAGES) return false
        if (BlockedCategories.appCategory(p) != null) return false     // already blocked
        return true
    }
}


/**
 * The apps this device has actually caught being a client of a blocked service.
 *
 * A confirmed entry behaves exactly like an app on apps_clients.txt - the difference is
 * that it carries its own evidence and the user can undo it, because a detector that
 * blocks a whole app and cannot say why is one nobody can argue with when it is wrong.
 */
object ProxyClients {

    private const val PREFS = "proxy_clients"
    private const val CONFIRMED = "svc_"      // pkg -> service id
    private const val WHY = "why_"            // pkg -> the evidence line
    private const val AT = "at_"              // pkg -> when it was confirmed
    private const val SUSPECT = "sus_"        // pkg -> "serviceId|count|lastAt|why"
    private const val CLEARED = "ok_"         // pkg -> the user said it is not a client

    /**
     * A suspicion needs a SECOND sighting, and not straight away. One screen fires dozens
     * of accessibility events and stays in front while you read it; without a gap the
     * "second look" would be the same look, counted twice.
     */
    const val SETTLE_MS = 60_000L
    /** A suspicion nobody has corroborated in a week was probably a coincidence. */
    const val FORGET_MS = 7L * 24 * 60 * 60 * 1000

    data class Detected(
        val pkg: String,
        val serviceId: String,
        val why: String,
        val at: Long,
    ) {
        val label: String get() = ClientMarkers.byId(serviceId)?.label ?: serviceId
    }

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The service this app was caught being a client of, or null. */
    fun detected(c: Context, pkg: String?): Detected? {
        if (pkg.isNullOrBlank()) return null
        val p = prefs(c)
        if (p.getBoolean(CLEARED + pkg, false)) return null
        val svc = p.getString(CONFIRMED + pkg, null) ?: return null
        return Detected(pkg, svc, p.getString(WHY + pkg, "").orEmpty(), p.getLong(AT + pkg, 0L))
    }

    /**
     * PROOF: the service's own site was on screen inside this app. Confirmed on the spot,
     * with no corroboration wanted - see the note at the top of this file.
     * Returns true if this is the moment it became confirmed.
     */
    fun proof(c: Context, pkg: String, service: ClientMarkers.Service, why: String): Boolean {
        val p = prefs(c)
        if (p.getBoolean(CLEARED + pkg, false)) return false      // the user has overruled us
        if (p.getString(CONFIRMED + pkg, null) == service.id) return false   // already known
        p.edit()
            .putString(CONFIRMED + pkg, service.id)
            .putString(WHY + pkg, why)
            .putLong(AT + pkg, System.currentTimeMillis())
            .remove(SUSPECT + pkg)
            .apply()
        return true
    }

    /**
     * SUSPICION: the service's vocabulary was on screen. Recorded, and confirmed only on a
     * second sighting at least [SETTLE_MS] later. Returns true if THIS sighting is the one
     * that confirmed it.
     */
    fun suspect(c: Context, pkg: String, service: ClientMarkers.Service, why: String): Boolean {
        val p = prefs(c)
        if (p.getBoolean(CLEARED + pkg, false)) return false
        if (p.getString(CONFIRMED + pkg, null) != null) return false
        val now = System.currentTimeMillis()
        val prior = p.getString(SUSPECT + pkg, null)?.split('|')
        val sameService = prior != null && prior.getOrNull(0) == service.id
        val lastAt = prior?.getOrNull(2)?.toLongOrNull() ?: 0L
        val stale = now - lastAt > FORGET_MS
        val corroborates = sameService && !stale && now - lastAt >= SETTLE_MS

        if (corroborates) {
            val firstWhy = prior?.getOrNull(3).orEmpty()
            p.edit()
                .putString(CONFIRMED + pkg, service.id)
                .putString(WHY + pkg, if (firstWhy.isBlank()) why else "$firstWhy; then $why")
                .putLong(AT + pkg, now)
                .remove(SUSPECT + pkg)
                .apply()
            return true
        }
        // Not yet - either the first sighting, a different service, or too soon after the
        // last one. Only rewrite the record when it is genuinely new, so the SETTLE_MS gap
        // is measured from the FIRST sighting rather than being pushed forward by every
        // event the same screen fires.
        if (!sameService || stale) {
            p.edit().putString(SUSPECT + pkg, "${service.id}|1|$now|$why").apply()
        }
        return false
    }

    /** The user says this is not a client. Permanent, and it survives a re-detection. */
    fun clear(c: Context, pkg: String) {
        prefs(c).edit()
            .putBoolean(CLEARED + pkg, true)
            .remove(CONFIRMED + pkg).remove(WHY + pkg).remove(AT + pkg).remove(SUSPECT + pkg)
            .apply()
    }

    /** Undo a clear(), so the detector may act on this app again. */
    fun unclear(c: Context, pkg: String) {
        prefs(c).edit().remove(CLEARED + pkg).apply()
    }

    fun isCleared(c: Context, pkg: String?): Boolean =
        !pkg.isNullOrBlank() && prefs(c).getBoolean(CLEARED + pkg, false)

    /** Everything confirmed, newest first. */
    fun all(c: Context): List<Detected> {
        val p = prefs(c)
        return p.all.keys
            .filter { it.startsWith(CONFIRMED) }
            .map { it.removePrefix(CONFIRMED) }
            .mapNotNull { detected(c, it) }
            .sortedByDescending { it.at }
    }

    /** Apps the user has overruled, so the review screen can show what it is NOT acting on. */
    fun cleared(c: Context): List<String> =
        prefs(c).all.keys.filter { it.startsWith(CLEARED) }.map { it.removePrefix(CLEARED) }.sorted()
}
