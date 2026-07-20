package com.example.webtrafficmonitor

import android.content.Context

// =====================================================================================
//  TEXT FILTER  —  the single home for all "banned WORDS" scoring logic.
// =====================================================================================
//
//  This is the MASTER copy; the Firefox extension's content/textfilter.js is kept in step
//  FROM here. The two stay in lock-step: same word tiers, same word families, same per-word
//  innocent-context exceptions, same "no single word ever blocks alone" guarantee. Edit this
//  file, then port the change into the plugin — see guide-keep-word-monitoring-consistent.txt.
//
//  On TOP of the shared JS model, the Kotlin side keeps three app-specific extras the
//  extension doesn't have:
//    * leetspeak / stretched-letter normalisation  (p0rn / pooorn → porn)  + the
//      VARIANT_* evasion-spelling lists,
//    * the gendered "who is the filter for" switches (AttractionFilter / GenderedTerms),
//    * medical-context dampening (MedicalContext).
//
//  ── THE TIERS (strongest → weakest) ────────────────────────────────────────────────
//  Two modes (relaxed vs strict, from Mode). Strict is a strict SUPERSET of relaxed:
//  anything that blocks in relaxed also blocks in strict.
//
//  CORE / EXPLICIT (w15 == THRESHOLD) — sexual in essentially EVERY context. ONE hit
//      blocks outright ("porn", "blowjob", "milf"). Also the VARIANT_EXPLICIT evasion
//      spellings ("pornhub", "fap", "coom"). Never softened, never damped away.
//  MIXED (w8) — strongly sexual but with real innocent uses ("nude", "naked", "topless",
//      "xxx"): art history, lab mice, finance ("naked options"), phone masks. NEVER blocks
//      alone — needs a SECOND distinct signal.
//  SUPPORT / STRONG (w8) — clearly sexual anatomy/slang ("cock", "vagina", "sex", "slut").
//      Also never blocks alone (SINGLE_WORD_MAX). Most have medical EXCEPTIONS.
//  SUBTLE (w2, strict only) — suggestive with plenty of innocent uses ("bikini",
//      "lingerie", "cleavage"). PER_WORD_CAP tames "bikini ×10 = bad, bikini ×2 = fine".
//  DUAL (w3, strict, context-only) — sexual in some contexts, innocent in others ("hot",
//      "wet", "girls"). Scores ONLY when an indicator is within CONTEXT_WINDOW words.
//  AMBIGUOUS (w2, strict, context-only) — usually NOT about people ("sheer", "webcam");
//      needs a person/sexual word nearby.
//  COMBO (w7, relaxed only) — "hot"/"sexy"/"hottie" right next to a PERSON word; strict
//      already scores these via DUAL/SUBTLE.
//  PHRASES — word ORDER carries the meaning ("try on haul", "leaked nudes"). LOUD phrases
//      count in both modes; SOFT (fashion) phrases are strict-only.
//
//  ── TWO GLOBAL SOFTENERS (both modes) ───────────────────────────────────────────────
//  1. NO SINGLE WORD EVER BLOCKS, except CORE/EXPLICIT: each word FAMILY's total is capped
//     at SINGLE_WORD_MAX = THRESHOLD-1, so one word — repeated fifty times, in the title,
//     anywhere — cannot block a page by itself. Blocking takes at least two DIFFERENT
//     signals.
//  2. WORD FAMILIES: inflections count as ONE signal, not several — "nude" + "nudes" is one
//     family ("nude"), not two corroborating words.
//
//  In BOTH modes, EXCEPTIONS veto a match in an innocent context: "naked mole rat",
//  "nude lipstick", "vaginal health", "summa cum laude", "pussy riot", "hardcore punk".
// =====================================================================================


// ── Scoring knobs (mirrors STRICT_TUNING / RELAXED_TUNING in textfilter.js) ───────────
object FilterTuning {
    const val EXPLICIT_WEIGHT = 15       // CORE tier — instant (== THRESHOLD)
    const val MIXED_WEIGHT = 8           // "nude"-type — needs a second distinct signal
    const val STRONG_WEIGHT = 8          // SUPPORT tier — never blocks alone
    const val SUBTLE_WEIGHT = 2          // strict-only suggestive
    const val DUAL_SEXUAL_WEIGHT = 3     // strict-only, context-gated
    const val AMBIGUOUS_WEIGHT = 2       // strict-only, person/sexual word required nearby
    const val COMBO_WEIGHT = 7           // relaxed-only: hot/sexy + person combo

    // PHRASES (see BannedPhrases). LOUD is treated like the CORE tier (exempt from the
    // single-word cap) so a loud phrase in a title — 8 × TITLE_URL_MULTIPLIER = 16 — blocks
    // on its own. SOFT is a weak, strict-only corroborator.
    const val PHRASE_LOUD_WEIGHT = 8
    const val PHRASE_SOFT_WEIGHT = 3

    const val CONTEXT_WINDOW = 4         // a DUAL/AMBIGUOUS/COMBO word counts only within this
    const val EXCEPTION_WINDOW = 3       // an innocent-context word within this vetoes a match
    const val PER_WORD_CAP = 5           // one family can contribute at most this many times
    const val SINGLE_WORD_MAX = 14       // = THRESHOLD-1: one family can NEVER block alone
    const val THRESHOLD = 15             // score at/above this → block
    const val DEFINITE_THRESHOLD = 30    // score at/above this → "definite nsfw" band
    const val TITLE_URL_MULTIPLIER = 2   // hits in the title or URL count double

    // How much a SOFT gendered word is still worth when that side of the filter is switched
    // off. Not zero — "bikini" on an actual porn page should still nudge the needle, it just
    // shouldn't block a swimwear shop on its own. See GenderedTerms.
    const val GENDER_OFF_MULTIPLIER = 0.25f

    // Medical/clinical context found on the page (see MedicalContext) multiplies the whole
    // score by this. Someone looking up a symptom must not be blocked. A damper, not an
    // exemption: a porn page that says "doctor" once still needs only a little more evidence.
    const val MEDICAL_DAMPEN = 0.35f
}


// ── The hardcoded word tiers (kept 1:1 with textfilter.js) ────────────────────────────
// Every tier now loads from a text file under src/main/resources/filter/words/en/ via
// FilterData (English master, unioned with the device language if we ship one). Getters, not
// vals, so they read after the classpath is available and stay in step if a file changes.
// EDIT THE WORDS in those .txt files, not here.
object BannedWords {

    val CORE: Set<String> get() = FilterData.langSet("words_core.txt")
    val MIXED: Set<String> get() = FilterData.langSet("words_mixed.txt")
    val SUPPORT: Set<String> get() = FilterData.langSet("words_support.txt")
    val COMBO: Set<String> get() = FilterData.langSet("words_combo.txt")

    // ── STRICT-ONLY tiers (merged in when NOT relaxed) ────────────────────────────────
    val EXTRA_EXPLICIT: Set<String> get() = FilterData.langSet("words_extra_explicit.txt")
    val EXTRA_SUBTLE: Set<String> get() = FilterData.langSet("words_subtle.txt")
    val EXTRA_DUAL: Set<String> get() = FilterData.langSet("words_dual.txt")
    val EXTRA_AMBIGUOUS: Set<String> get() = FilterData.langSet("words_ambiguous.txt")
    val PERSON: Set<String> get() = FilterData.langSet("words_person.txt")

    // Evasion spellings (leetspeak/stretch are handled by the scorer's normaliser, not here).
    val VARIANT_EXPLICIT: Set<String> get() = FilterData.langSet("variant_explicit.txt")
    val VARIANT_DUAL: Set<String> get() = FilterData.langSet("variant_dual.txt")

    // ── WORD FAMILIES ─────────────────────────────────────────────────────────────────
    // "head, inflection1, inflection2" per line in family_groups.txt. Parsed once, cached.
    @Volatile private var familyCache: Map<String, String>? = null
    private fun family(): Map<String, String> = familyCache ?: run {
        val map = HashMap<String, String>()
        for (line in FilterData.langLines("family_groups.txt")) {
            val words = line.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (words.isNotEmpty()) for (w in words) map[w] = words[0]
        }
        map.also { familyCache = it }
    }

    /** The family head for a word ("nudes" → "nude"), or the word itself if it has no family. */
    fun famOf(w: String): String = family()[w] ?: w
}


// ── EXCEPTIONS: the innocent-context escape hatch (both modes) ────────────────────────
// If any of these tokens sits within FilterTuning.EXCEPTION_WINDOW words of the match, the
// match scores NOTHING. Looked up by the matched word AND its family head, so 'nude' also
// covers "nudes"/"nudity", 'vagina' covers "vaginal", 'whore' covers "whores", etc.
//   "naked mole rat" / "with the naked eye"     → 0
//   "nude coloured makeup" / "nude lipstick"    → 0
//   "hardcore punk" / "hardcore gaming"         → 0
//   "vaginal health", "breast cancer", "summa cum laude", "pussy riot", "blue tits" (birds)
object BannedWordExceptions {
    // Parsed once from exceptions.txt ("word: n1, n2, ..." per line), cached. Keyed by the
    // matched word AND its family head (see hasExceptionNear). EDIT THE FILE, not here.
    @Volatile private var cache: Map<String, Set<String>>? = null
    val MAP: Map<String, Set<String>>
        get() = cache ?: run {
            val map = HashMap<String, Set<String>>()
            for (line in FilterData.langLines("exceptions.txt")) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val word = line.substring(0, colon).trim()
                val neighbours = line.substring(colon + 1).split(',')
                    .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                if (word.isNotEmpty() && neighbours.isNotEmpty()) map[word] = neighbours
            }
            map.also { cache = it }
        }
}


// ── Who is being sexualised (gendered switches) ───────────────────────────────────────
// Two switches (see AttractionFilter) let someone turn DOWN the sexualised-women or the
// sexualised-men side of the filter. Default: both fully on. Only the SOFT, suggestive
// words/phrases below are affected — anything CORE keeps full weight no matter what.
// Data now lives in src/main/resources/filter/words/en/gendered_*.txt (loaded via FilterData,
// English ∪ device language). Edit the .txt files, not here.
object GenderedTerms {
    val SOFT_FEMALE: Set<String> get() = FilterData.langSet("gendered_female.txt")
    val SOFT_MALE: Set<String> get() = FilterData.langSet("gendered_male.txt")
    val PHRASES_FEMALE: Set<String> get() = FilterData.langSet("gendered_phrases_female.txt")
    val PHRASES_MALE: Set<String> get() = FilterData.langSet("gendered_phrases_male.txt")
}


// ── Medical / clinical context (whole-page damper) ────────────────────────────────────
// If any of these appear on the page, the sexual score is heavily damped (MEDICAL_DAMPEN).
// A damper, not an exemption: a porn page that says "doctor" doesn't get a free pass, it
// just needs more evidence. (The per-word EXCEPTIONS above are the finer-grained veto; this
// is the broad safety net for a page that is medical top-to-bottom.)
object MedicalContext {
    val WORDS: Set<String> get() = FilterData.langSet("medical_context.txt")
}


// ── Phrases: word ORDER carries the meaning ───────────────────────────────────────────
// The word tiers only see ONE word at a time, which is why "try on haul", "nip slip" and
// "no leggings" sail through — none is bannable alone. Phrases match against a normalised
// copy of the page, so punctuation and hyphens don't matter ("Try-On Haul!!" → "try on haul").
//
// LOUD = the phrase itself is the giveaway; both modes; treated like CORE (blocks in a title).
// SOFT = leans adult but has a real innocent life (a genuine fashion haul); strict-only; weak.
object BannedPhrases {

    val LOUD: Set<String> get() = FilterData.langSet("phrases_loud.txt")

    val SOFT: Set<String> get() = FilterData.langSet("phrases_soft.txt")
}


// ── The two "sexualised women / sexualised men" switches. Both default ON. ─────────────
/**
 * LOCKED OUTSIDE RELAXED MODE. In strict or super hardcore these are forced back on and
 * cannot be changed — otherwise the first thing a bad night does is flip a switch.
 */
object AttractionFilter {
    private const val PREFS = "attraction_filter"
    private const val KEY_FEMALE = "block_female"
    private const val KEY_MALE = "block_male"

    /** May the user change these right now? Only in Relaxed (or Off). */
    fun canEdit(c: Context): Boolean = Mode.isRelaxed(c) || Mode.isOff(c)

    fun blockFemale(c: Context): Boolean =
        if (!canEdit(c)) true else prefs(c).getBoolean(KEY_FEMALE, true)

    fun blockMale(c: Context): Boolean =
        if (!canEdit(c)) true else prefs(c).getBoolean(KEY_MALE, true)

    fun setBlockFemale(c: Context, on: Boolean) {
        if (canEdit(c)) prefs(c).edit().putBoolean(KEY_FEMALE, on).apply()
    }

    fun setBlockMale(c: Context, on: Boolean) {
        if (canEdit(c)) prefs(c).edit().putBoolean(KEY_MALE, on).apply()
    }

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


// ── The scorer: text/title/URL → score + block reason ────────────────────────────────
// The engine mirrors computeDetailed() in textfilter.js: per-FAMILY accumulation with a
// shared PER_WORD_CAP, the SINGLE_WORD_MAX guarantee (no non-CORE family blocks alone),
// the DUAL/AMBIGUOUS/COMBO context gates, and the innocent-context EXCEPTION veto. Layered
// on top (Kotlin-only): leetspeak/stretch candidates, the gender multiplier, and the
// whole-page medical damper.
object BorderlineScorer {

    data class Result(val score: Int, val reason: String)

    /**
     * The switches in force for this scoring pass. Passed in rather than read from a cache,
     * so flipping a switch or changing mode takes effect on the very next page. [relaxed]
     * selects the RELAXED tier set (suggestive tiers off); strict is a superset.
     */
    data class Settings(val blockFemale: Boolean, val blockMale: Boolean, val relaxed: Boolean) {
        companion object {
            val ALL_ON = Settings(true, true, relaxed = false)
            fun of(c: Context) = Settings(
                AttractionFilter.blockFemale(c),
                AttractionFilter.blockMale(c),
                relaxed = Mode.isRelaxed(c) || Mode.isOff(c),
            )
        }
    }

    /** The tier sets in force for the current mode. Cached per mode (they only depend on it). */
    private class ActiveSets(relaxed: Boolean) {
        val explicit: Set<String> = BannedWords.CORE + BannedWords.VARIANT_EXPLICIT
        val mixed: Set<String> = BannedWords.MIXED
        val strong: Set<String> =
            if (relaxed) BannedWords.SUPPORT
            else BannedWords.SUPPORT + BannedWords.EXTRA_EXPLICIT
        val combo: Set<String> = if (relaxed) BannedWords.COMBO else emptySet()
        val subtle: Set<String> = if (relaxed) emptySet() else BannedWords.EXTRA_SUBTLE
        val dual: Set<String> =
            if (relaxed) emptySet() else BannedWords.EXTRA_DUAL + BannedWords.VARIANT_DUAL
        val ambiguous: Set<String> = if (relaxed) emptySet() else BannedWords.EXTRA_AMBIGUOUS

        // Any non-dual sexual word switches on a nearby DUAL word.
        val indicators: Set<String> = explicit + mixed + strong + subtle
        // A sexual indicator OR a person word switches on an AMBIGUOUS word / PHRASE.
        val ambigIndicators: Set<String> = indicators + BannedWords.PERSON
    }

    @Volatile private var relaxedSets: ActiveSets? = null
    @Volatile private var strictSets: ActiveSets? = null
    private fun active(relaxed: Boolean): ActiveSets =
        if (relaxed) (relaxedSets ?: ActiveSets(true).also { relaxedSets = it })
        else (strictSets ?: ActiveSets(false).also { strictSets = it })

    /** Raw score for logging/flagging; null when nothing sexual was found. */
    fun score(title: String?, url: String?, text: String?, s: Settings = Settings.ALL_ON): Result? {
        val v = compute(title, url, text, s)
        return if (v <= 0) null else Result(v, reasonFor(v))
    }

    /** Non-null (with a block reason) only when the score reaches the block THRESHOLD. */
    fun evaluate(title: String?, url: String?, content: String?, s: Settings = Settings.ALL_ON): Result? {
        val v = compute(title, url, content, s)
        return if (v >= FilterTuning.THRESHOLD) Result(v, reasonFor(v)) else null
    }

    private fun reasonFor(score: Int): String = "Sexual / adult content (score $score)"

    // A hit worth counting: which family, what tier, base weight, and the exact matched word
    // (for the gender multiplier). base == 0 means "recognised but scores nothing here".
    private data class Hit(val fam: String, val tier: String, val base: Int, val word: String)

    private fun compute(title: String?, url: String?, body: String?, set: Settings): Int {
        val a = active(set.relaxed)
        val counted = HashMap<String, Int>()   // family -> capped occurrences, shared across fields
        val points = HashMap<String, Float>()   // family -> points so far (for SINGLE_WORD_MAX)
        var total = 0f

        // CORE / EXPLICIT points (and LOUD phrases) are kept SEPARATE from everything else,
        // because the medical damper below must NOT touch them: a page saying "porn" is porn
        // even if it also says "health", but a page saying "vagina"/"sex"/"vulva" in a wall
        // of medical text must not block. So CORE always counts full; the softer tiers get
        // damped when the page reads medical.
        var explicitTotal = 0f
        var otherTotal = 0f

        val fields = listOf(
            tokenize(title) to FilterTuning.TITLE_URL_MULTIPLIER,
            tokenize(url) to FilterTuning.TITLE_URL_MULTIPLIER,
            tokenize(body) to 1,
        )
        for ((words, mult) in fields) {
            for (i in words.indices) {
                val hit = resolve(words, i, a) ?: continue
                if (hit.base == 0) continue
                val p = add(hit.fam, hit.tier, hit.base, mult, hit.word, set, counted, points)
                if (hit.tier == "explicit") explicitTotal += p else otherTotal += p
            }
        }

        // Phrases are matched on the whole (normalised) field, because the meaning is in the
        // ORDER — no single word of "try on haul" is bannable, the three together plainly are.
        for ((text, mult) in listOf(
            normalise(title) to FilterTuning.TITLE_URL_MULTIPLIER,
            normalise(url) to FilterTuning.TITLE_URL_MULTIPLIER,
            normalise(body) to 1,
        )) {
            val (loud, soft) = scorePhrases(text, mult, set, counted, points)
            explicitTotal += loud
            otherTotal += soft
        }

        // Looking up a symptom is not looking at porn. Damp the SOFT signals hard when the
        // page reads medical; CORE/LOUD are never damped, so real porn still blocks.
        if (hasMedicalContext(title, body)) otherTotal *= FilterTuning.MEDICAL_DAMPEN
        return Math.round(explicitTotal + otherTotal)
    }

    /**
     * Classify token [i]: try it as written AND leet/stretch-normalised, so "p0rn"/"pooorn"
     * land on "porn". Returns the first candidate that lands in a tier — vetoed to null if an
     * innocent-context EXCEPTION sits nearby, or scored 0 if a context tier has no indicator.
     */
    private fun resolve(words: List<String>, i: Int, a: ActiveSets): Hit? {
        for (w in candidates(words[i])) {
            val fam = BannedWords.famOf(w)
            when {
                w in a.explicit -> return hitOrVeto(words, i, fam, "explicit", FilterTuning.EXPLICIT_WEIGHT, w)
                w in a.mixed -> return hitOrVeto(words, i, fam, "mixed", FilterTuning.MIXED_WEIGHT, w)
                w in a.strong -> return hitOrVeto(words, i, fam, "strong", FilterTuning.STRONG_WEIGHT, w)
                w in a.subtle -> return hitOrVeto(words, i, fam, "subtle", FilterTuning.SUBTLE_WEIGHT, w)
                w in a.dual ->
                    return if (hasSetNear(words, i, i, a.indicators)) hitOrVeto(words, i, fam, "dual", FilterTuning.DUAL_SEXUAL_WEIGHT, w)
                    else Hit(fam, "dual", 0, w)
                w in a.ambiguous ->
                    return if (hasSetNear(words, i, i, a.ambigIndicators)) hitOrVeto(words, i, fam, "ambiguous", FilterTuning.AMBIGUOUS_WEIGHT, w)
                    else Hit(fam, "ambiguous", 0, w)
                w in a.combo ->
                    return if (hasSetNear(words, i, i, BannedWords.PERSON)) hitOrVeto(words, i, fam, "combo", FilterTuning.COMBO_WEIGHT, w)
                    else Hit(fam, "combo", 0, w)
            }
        }
        return null
    }

    /** A scoring hit, unless an innocent-context word vetoes it ("naked mole rat" → nothing). */
    private fun hitOrVeto(words: List<String>, i: Int, fam: String, tier: String, base: Int, word: String): Hit =
        if (hasExceptionNear(words, i, word)) Hit(fam, tier, 0, word) else Hit(fam, tier, base, word)

    /**
     * Add one hit for [fam]. Two caps apply: PER_WORD_CAP on occurrences, and — for every
     * tier except explicit — SINGLE_WORD_MAX on the family's total points, the guarantee that
     * no single word can ever block a page by itself. Returns the points actually added.
     */
    private fun add(
        fam: String, tier: String, base: Int, mult: Int, word: String,
        set: Settings, counted: HashMap<String, Int>, points: HashMap<String, Float>,
    ): Float {
        val c = counted.getOrDefault(fam, 0)
        if (c >= FilterTuning.PER_WORD_CAP) return 0f     // over cap: ignored
        counted[fam] = c + 1
        var pts = base * mult * genderMultiplier(word, set)
        if (tier != "explicit") {
            val room = FilterTuning.SINGLE_WORD_MAX - points.getOrDefault(fam, 0f)
            pts = minOf(pts, maxOf(0f, room))
        }
        if (pts <= 0f) return 0f
        points[fam] = points.getOrDefault(fam, 0f) + pts
        return pts
    }

    /**
     * What one word is worth after the gender switches. A SOFT gendered word is knocked down
     * to GENDER_OFF_MULTIPLIER when its side is off; CORE words are never touched, so turning
     * a switch off can never unblock pornography.
     */
    private fun genderMultiplier(word: String, set: Settings): Float {
        if (!set.blockFemale && word in GenderedTerms.SOFT_FEMALE) return FilterTuning.GENDER_OFF_MULTIPLIER
        if (!set.blockMale && word in GenderedTerms.SOFT_MALE) return FilterTuning.GENDER_OFF_MULTIPLIER
        return 1f
    }

    private fun phraseMultiplier(phrase: String, set: Settings): Float {
        if (!set.blockFemale && phrase in GenderedTerms.PHRASES_FEMALE) return FilterTuning.GENDER_OFF_MULTIPLIER
        if (!set.blockMale && phrase in GenderedTerms.PHRASES_MALE) return FilterTuning.GENDER_OFF_MULTIPLIER
        return 1f
    }

    /** Returns (loudPoints, softPoints) — kept apart so the medical damper spares the loud ones. */
    private fun scorePhrases(
        text: String, mult: Int, set: Settings,
        counted: HashMap<String, Int>, points: HashMap<String, Float>,
    ): Pair<Float, Float> {
        if (text.isBlank()) return 0f to 0f
        fun run(phrases: Set<String>, weight: Int, tier: String): Float {
            var s = 0f
            for (p in phrases) {
                if (!text.contains(" $p ")) continue
                val fam = "phrase:$p"
                val c = counted.getOrDefault(fam, 0)
                if (c >= FilterTuning.PER_WORD_CAP) continue
                counted[fam] = c + 1
                var pts = weight * mult * phraseMultiplier(p, set)
                if (tier != "explicit") {
                    val room = FilterTuning.SINGLE_WORD_MAX - points.getOrDefault(fam, 0f)
                    pts = minOf(pts, maxOf(0f, room))
                }
                if (pts <= 0f) continue
                points[fam] = points.getOrDefault(fam, 0f) + pts
                s += pts
            }
            return s
        }
        // LOUD is treated like CORE (exempt from the single-word cap) so a loud phrase in a
        // title blocks alone; SOFT is a weak, strict-only corroborator.
        val loud = run(BannedPhrases.LOUD, FilterTuning.PHRASE_LOUD_WEIGHT, "explicit")
        val soft = if (!set.relaxed) run(BannedPhrases.SOFT, FilterTuning.PHRASE_SOFT_WEIGHT, "phrase") else 0f
        return loud to soft
    }

    private fun hasMedicalContext(title: String?, body: String?): Boolean {
        for (w in tokenize(title)) if (w in MedicalContext.WORDS) return true
        for (w in tokenize(body)) if (w in MedicalContext.WORDS) return true
        return false
    }

    /** Is one of [word]'s EXCEPTIONS within EXCEPTION_WINDOW? Falls back to the family head. */
    private fun hasExceptionNear(words: List<String>, i: Int, word: String): Boolean {
        val ex = BannedWordExceptions.MAP[word] ?: BannedWordExceptions.MAP[BannedWords.famOf(word)] ?: return false
        val lo = maxOf(0, i - FilterTuning.EXCEPTION_WINDOW)
        val hi = minOf(words.lastIndex, i + FilterTuning.EXCEPTION_WINDOW)
        for (j in lo..hi) if (j != i && words[j] in ex) return true
        return false
    }

    /** Is any word of [set] within CONTEXT_WINDOW of the span lo..hi? (Leet-aware neighbours.) */
    private fun hasSetNear(words: List<String>, lo: Int, hi: Int, set: Set<String>): Boolean {
        val a = maxOf(0, lo - FilterTuning.CONTEXT_WINDOW)
        val b = minOf(words.lastIndex, hi + FilterTuning.CONTEXT_WINDOW)
        for (j in a..b) {
            if (j in lo..hi) continue
            if (candidates(words[j]).any { it in set }) return true
        }
        return false
    }

    /** The spellings we'll accept for one token: as typed, de-leeted, and de-stretched. */
    private fun candidates(token: String): List<String> {
        val out = ArrayList<String>(3)
        out.add(token)
        val d = deleet(token)
        if (d != token) out.add(d)
        val c = collapse(d)
        if (c != d && c != token) out.add(c)
        return out
    }

    /** Digit/symbol substitutions: p0rn → porn, s3x → sex, b00bs → boobs. */
    private fun deleet(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when (ch) {
                    '0' -> 'o'; '1' -> 'i'; '3' -> 'e'; '4' -> 'a'
                    '5' -> 's'; '7' -> 't'; '@' -> 'a'; '$' -> 's'
                    else -> ch
                },
            )
        }
        return sb.toString()
    }

    /**
     * Stretched letters: "pooorn" → "porn", "seeexy" → "sexy". Collapses to ONE letter, which
     * does mangle honest doubles ("boobs" → "bobs") — fine, because candidates() also checks
     * the word as typed, and "boobs" matches there.
     */
    private fun collapse(s: String): String {
        if (s.length < 3) return s
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            if (i == 0 || s[i] != s[i - 1]) sb.append(s[i])
        }
        return sb.toString()
    }

    /**
     * The page as one padded, single-spaced, letters-and-digits-only string, so a phrase can
     * be found with a plain " phrase " contains(). The padding gives word boundaries for
     * free: " no bra " will not match inside "piano brand".
     */
    private fun normalise(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return " " + s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
    }

    /**
     * Lowercase, split on anything that isn't a letter or DIGIT, keep tokens length >= 2.
     * Digits are kept deliberately: strip them and "p0rn" tokenises to "p"/"rn" and no amount
     * of de-leeting can save it.
     */
    private fun tokenize(s: String?): List<String> {
        if (s.isNullOrEmpty()) return emptyList()
        return s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
    }
}
