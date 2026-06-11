package com.example.webtrafficmonitor.block

import android.content.Context

/**
 * The list of things to block, plus a per-session allow list for "report
 * incorrect block". Rules are simple lowercase substrings matched against the
 * domain, title, on-screen text and package name. A rule like "wikipedia.org"
 * therefore blocks "en.wikipedia.org", and a rule like "elephant" blocks any
 * page whose visible text mentions it.
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

    /** True if the current screen should be blocked. */
    fun matches(domain: String?, title: String?, text: String?, packageName: String?): Boolean {
        if (rules.isEmpty()) return false

        val key = (domain ?: packageName)?.lowercase()
        if (key != null && key in sessionAllow) return false

        val haystack = buildString {
            domain?.let { append(it).append(' ') }
            title?.let { append(it).append(' ') }
            packageName?.let { append(it).append(' ') }
            text?.let { append(it) }
        }.lowercase()
        if (haystack.isBlank()) return false

        return rules.any { it in haystack }
    }

    private fun persist(context: Context) {
        // Store a copy; SharedPreferences must not be handed its own live set.
        prefs(context).edit().putStringSet(KEY, HashSet(rules)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
