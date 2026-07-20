package com.example.webtrafficmonitor

import android.content.Context
import java.util.Locale

// =====================================================================================
//  FilterData  —  loads the filter's word/app/domain lists from named asset files.
// =====================================================================================
//
//  The lists that decide what to block used to be hardcoded Kotlin sets. They now live as
//  plain, easy-to-edit text files under assets/filter/ (one entry per line, "#" comments),
//  so a list can be updated without touching code, and the SAME files can be shared with the
//  Firefox extension.
//
//  ── LANGUAGE STRATEGY ───────────────────────────────────────────────────────────────
//   * LANGUAGE-NEUTRAL lists — apps (package names) and domains (hosts) — are ONE shared
//     file each: a package name or a URL is the same in every language.
//         assets/filter/domains_search_engines.txt, apps_safe.txt, …
//   * LANGUAGE-SPECIFIC lists — keywords, phrases, medical/innocent-context exceptions —
//     live under a per-language folder, English being the master/default:
//         assets/filter/words/en/words_core.txt, .../exceptions.txt, …
//     langSet() reads English ALWAYS, and UNIONs in the device language's file if we ship
//     one (so adult content is caught whatever language the page is in). Missing language →
//     just English, so nothing ever breaks.
//
//  Call init(context) once at startup (MainActivity + the service both do). Files are read
//  lazily on first use and cached; they're tiny, so the read is synchronous. If init hasn't
//  run, accessors return empty (fail-open) rather than crashing.
object FilterData {

    @Volatile private var appContext: Context? = null
    private val cache = HashMap<String, List<String>>()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** A language-neutral list, e.g. lines("domains_greylist.txt"). Cached. */
    @Synchronized
    fun lines(fileName: String): List<String> =
        cache.getOrPut(fileName) { readAsset("filter/$fileName") }

    fun set(fileName: String): Set<String> = lines(fileName).toLinkedSet()

    /**
     * A "Friendly Name = value" file → ordered map (name → value.lowercased). Lines without
     * an "=" are skipped. Used for the app lists, where the picker needs the friendly name.
     */
    fun map(fileName: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in lines(fileName)) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val name = line.substring(0, i).trim()
            val value = line.substring(i + 1).trim().lowercase()
            if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
        }
        return out
    }

    /**
     * A language-specific list: English master ∪ the device language (if shipped).
     * e.g. langSet("words_core.txt") → src/main/resources/filter/words/en/words_core.txt (+ <dev>).
     *
     * These load from the CLASSPATH (Java resources), NOT assets, deliberately: the content
     * scorer is exercised by a pure-JVM unit test (no Android Context), and classpath resources
     * are readable there as well as on-device. (Apps/domains use assets since only Android
     * touches them.)
     */
    @Synchronized
    fun langLines(fileName: String): List<String> =
        cache.getOrPut("words/*/$fileName") {
            val out = LinkedHashSet<String>()
            out += readResource("/filter/words/en/$fileName")          // master, always
            val dev = deviceLang()
            if (dev != "en") out += readResource("/filter/words/$dev/$fileName")  // union, if present
            out.toList()
        }

    fun langSet(fileName: String): Set<String> = langLines(fileName).toLinkedSet()

    private fun deviceLang(): String =
        (Locale.getDefault().language ?: "en").lowercase().ifBlank { "en" }

    /** Read a classpath resource (works on Android AND in pure-JVM unit tests). */
    private fun readResource(path: String): List<String> = try {
        FilterData::class.java.getResourceAsStream(path)?.bufferedReader()?.use { r ->
            r.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        } ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    private fun readAsset(path: String): List<String> {
        val ctx = appContext ?: return emptyList()
        return try {
            ctx.assets.open(path).bufferedReader().useLines { seq ->
                seq.map { it.substringBefore('#').trim() }   // strip inline/whole-line comments
                    .filter { it.isNotEmpty() }
                    .toList()
            }
        } catch (t: Throwable) {
            // Missing file (e.g. a language we don't ship) is normal — not an error.
            emptyList()
        }
    }

    private fun <T> List<T>.toLinkedSet(): Set<T> = LinkedHashSet(this)
}
