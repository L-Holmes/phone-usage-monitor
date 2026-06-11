package com.example.webtrafficmonitor.block

import android.content.Context

/**
 * The list of things to block, plus a per-session allow list for "report
 * incorrect block".
 *
 * A rule is matched against only the current page's domain, title and app — never
 * the full on-screen text, so an autocomplete suggestion or an embedded resource
 * mentioning a domain does not trigger a block.
 *
 *  - A rule containing a dot is a DOMAIN rule: "redgifs.com" blocks redgifs.com
 *    and its subdomains; "i.reddit.com" blocks only that exact subdomain.
 *  - A rule without a dot is a KEYWORD rule, matched against the page title (and
 *    the app package): "wolf" blocks pages titled like "Wolf - Wikipedia".
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

    /** True if the current page should be blocked. */
    fun matches(domain: String?, title: String?, packageName: String?): Boolean {
        if (rules.isEmpty()) return false

        val key = (domain ?: packageName)?.lowercase()
        if (key != null && key in sessionAllow) return false

        val host = domain?.lowercase()
        val titleText = title?.lowercase()
        val pkg = packageName?.lowercase()

        return rules.any { rule ->
            if ('.' in rule) {
                // Domain rule: exact host or a subdomain of it.
                host != null && (host == rule || host.endsWith(".$rule"))
            } else {
                // Keyword rule: match the page title or the app package.
                (titleText?.contains(rule) == true) || (pkg?.contains(rule) == true)
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
