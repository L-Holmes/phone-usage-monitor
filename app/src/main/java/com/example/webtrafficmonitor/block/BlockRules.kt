package com.example.webtrafficmonitor.block

import android.content.Context

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

    private val rules = linkedSetOf<String>()
    private val sessionAllow = mutableSetOf<String>()

    fun load(context: Context) {
        val saved = prefs(context).getStringSet(KEY, emptySet()) ?: emptySet()
        rules.clear()
        rules.addAll(saved)
    }

    fun all(): List<String> = rules.toList()

    fun add(context: Context, rule: String) {
        val cleaned = rule.trim().lowercase()
        if (cleaned.isEmpty()) return
        rules.add(cleaned)
        persist(context)
    }

    fun clear(context: Context) {
        rules.clear()
        persist(context)
    }

    /** Lets the current page through until the app process restarts. */
    fun allowForSession(key: String?) {
        if (!key.isNullOrBlank()) sessionAllow.add(key.lowercase())
    }

    /**
     * Returns the rule that blocks this page, or null if it is allowed. Matching
     * uses only the page domain and title, so it is shown only for actual web
     * pages (the caller passes a non-null domain only when an address bar is
     * visible).
     */
    fun matchedRule(domain: String?, title: String?): String? {
        if (rules.isEmpty()) return null

        val host = domain?.lowercase()
        if (host != null && host in sessionAllow) return null

        val titleText = title?.lowercase()

        return rules.firstOrNull { rule ->
            if ('.' in rule) {
                // Domain rule: exact host or a subdomain of it.
                host != null && (host == rule || host.endsWith(".$rule"))
            } else {
                // Keyword rule: match the page title.
                titleText?.contains(rule) == true
            }
        }
    }

    private fun persist(context: Context) {
        // Store a copy; SharedPreferences must not be handed its own live set.
        prefs(context).edit().putStringSet(KEY, HashSet(rules)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
