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
//  FRAGMENTS (strict+, see ModeFragments) — blunt SUBSTRINGS of the title/URL, including
//      spaced-out spellings ("ling eri", "bik ini"), for someone typing around the filter.
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

    // ── IN-APP SCREENS ARE HELD TO A TIGHTER BAR THAN WEB PAGES ──────────────────────
    // An app feed (Instagram, Reddit, TikTok, X) is the harder problem, not the easier one:
    // there is no address bar to read, no domain to blocklist, no extension to lean on, and
    // the content arrives already personalised to whatever you last lingered on. So an app
    // screen blocks at APP_THRESHOLD, below the web THRESHOLD.
    //
    // The single-word guarantee still holds, but it has to be enforced DIFFERENTLY here.
    // On the web it comes free: SINGLE_WORD_MAX (14) sits just under THRESHOLD (15), so one
    // word family can never reach the bar alone. A lower app bar breaks that arithmetic - 14
    // clears 11 easily - so below the web threshold we require APP_MIN_FAMILIES distinct
    // families to have scored. "Blocking takes at least two different signals" is the rule
    // that keeps "nude lipstick" out of the block list, and it survives the tighter bar.
    const val APP_THRESHOLD = 11
    const val APP_MIN_FAMILIES = 2

    // How much a SOFT gendered word is still worth when that side of the filter is switched
    // off. Not zero — "bikini" on an actual porn page should still nudge the needle, it just
    // shouldn't block a swimwear shop on its own. See GenderedTerms.
    const val GENDER_OFF_MULTIPLIER = 0.25f

    // Medical/clinical context found on the page (see MedicalContext) multiplies the whole
    // score by this. Someone looking up a symptom must not be blocked. A damper, not an
    // exemption: a porn page that says "doctor" once still needs only a little more evidence.
    const val MEDICAL_DAMPEN = 0.35f

    // ── FIREFOX IS THE ONE PLACE WE ARE NOT THE ONLY GUARD ───────────────────────────
    // Everywhere else this scorer is all there is. Inside Firefox, with our image add-on
    // installed and confirmed, a second filter is reading the page from the INSIDE - it
    // sees the actual images, which is the thing accessibility text can never see. Two
    // filters stacked at the same bar means twice the false positives on the one surface
    // where a false positive is most annoying (ordinary browsing).
    //
    // So the web threshold is multiplied by this for pages in Firefox when the add-on is
    // confirmed: 15 -> 21. Nothing else changes - the domain blocklist, the ban list and
    // the search-engine rule are all still absolute, because those are judgements the
    // add-on does not duplicate. Only the HEURISTIC gets the wider berth.
    const val PLUGIN_COVERED_MULTIPLIER = 1.4f
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


// ── MODE-GATED FRAGMENTS: the "typed around the filter" tier ──────────────────────────
//  Blunt SUBSTRINGS of the page TITLE and URL, deliberately including spaced-out spellings
//  ("ling eri", "bik ini", "red dit") to catch someone typing around a block. Gated by mode:
//    * SUPER_HARDCORE — only while the mode is Super hardcore.
//    * STRICT_PLUS    — Strict or Super hardcore.
//  Nothing here fires in Relaxed (or Off).
//
//  ⚠️ 2026-08-04 — WHY THESE ARE NO LONGER A VERDICT ON THEIR OWN
//  This list used to block OUTRIGHT: one substring anywhere in a title and the app was
//  covered, with no score and no explanation. That is how an ordinary app ended up blocked
//  by the word "browser", and how "necklace" got caught by "lace" — a substring match is far
//  too blunt to be a verdict. ("haul" is in "overhaul", "scroller" is in every UI toolkit.)
//
//  So every fragment now carries a WEIGHT and goes through BorderlineScorer like any other
//  signal. That buys the SINGLE_WORD_MAX guarantee for free: a soft fragment can never block
//  anything by itself, no matter how many times it appears. Only the [hard] ones — the site
//  names, and Reddit in Super hardcore — still count like a CORE word and block on one hit.
//
//  [family] is the CONCEPT, not the spelling, so "bikini" the word and "bik ini" the evasion
//  are ONE signal instead of two corroborating ones. It deliberately uses the same family
//  heads as family_groups.txt, so the word tier and this tier share one budget.
object ModeFragments {

    data class Fragment(
        /** Matched as a plain substring of the normalised title/URL — NOT a whole word. */
        val text: String,
        /** The concept this belongs to; shared with the word tier's family head. */
        val family: String,
        val weight: Int,
        /** true = counts like a CORE word: exempt from the single-signal cap, blocks alone. */
        val hard: Boolean = false,
    )

    // Super-hardcore-only: Reddit, and the ways it gets typed around a filter. Reddit itself
    // is on the always-banned domain list; these catch it being reached some other way.
    val SUPER_HARDCORE: List<Fragment> = listOf(
        "reddit", "redd", "red dit", "re ddit", "reddi t", "redd it",
        "eddit", "e ddit", "r eddit",
    ).map { Fragment(it, "reddit", FilterTuning.EXPLICIT_WEIGHT, hard = true) }

    // Strict and above.
    val STRICT_PLUS: List<Fragment> = listOf(
        // scrolller.com, a porn aggregator. The three-l spelling is unmistakable, so it
        // still blocks on its own; the ordinary "scroller" spelling is a real word (every
        // UI toolkit has one), so that one only corroborates.
        Fragment("scrolller", "scrolller", FilterTuning.EXPLICIT_WEIGHT, hard = true),
        Fragment("scroller", "scrolller", FilterTuning.SUBTLE_WEIGHT),

        // "lingerie" itself is already SUBTLE in the word list. The SPACED spellings are
        // somebody working around a filter, which is far better evidence than the word.
        Fragment("lin gerie", "lingerie", FilterTuning.STRONG_WEIGHT),
        Fragment("lin geri", "lingerie", FilterTuning.STRONG_WEIGHT),
        Fragment("ling eri", "lingerie", FilterTuning.STRONG_WEIGHT),
        Fragment("lingeri", "lingerie", FilterTuning.SUBTLE_WEIGHT),

        // "lace" is in "necklace", "shoelace", "fireplace". Weakest weight there is: it can
        // corroborate a page that already looks wrong, and it can never block one alone.
        Fragment("lace", "lace", FilterTuning.SUBTLE_WEIGHT),

        // The full "try on haul" is a LOUD phrase (phrases_loud.txt) and still blocks by
        // itself. These are the mangled spellings; "haul" bare is in "overhaul", "u-haul".
        Fragment("try on hau", "haul", FilterTuning.PHRASE_LOUD_WEIGHT),
        Fragment("t ry on haul", "haul", FilterTuning.PHRASE_LOUD_WEIGHT),
        Fragment("try n haul", "haul", FilterTuning.PHRASE_LOUD_WEIGHT),
        Fragment("haul", "haul", FilterTuning.SUBTLE_WEIGHT),

        // "sheer" is already AMBIGUOUS in the word list; the split spellings are evasion.
        Fragment("shee r", "sheer", FilterTuning.STRONG_WEIGHT),
        Fragment("sh eer", "sheer", FilterTuning.STRONG_WEIGHT),

        // "bikini" is already SUBTLE in the word list; again, only the split spellings here.
        Fragment("bik ini", "bikini", FilterTuning.STRONG_WEIGHT),
        Fragment("bi kini", "bikini", FilterTuning.STRONG_WEIGHT),
        Fragment("ikini", "bikini", FilterTuning.SUBTLE_WEIGHT),

        // REMOVED 2026-08-04: "browser" / "brow ser". They were blocking ordinary apps for
        // saying the word "browser", which is not evidence of anything. Do not add generic
        // vocabulary here — a fragment has to be a spelling nobody types by accident.
    )

    /** The fragments in force. Empty in Relaxed and Off. */
    fun active(strict: Boolean, superHardcore: Boolean): List<Fragment> = when {
        superHardcore -> SUPER_HARDCORE + STRICT_PLUS
        strict -> STRICT_PLUS
        else -> emptyList()
    }
}


// =====================================================================================
//  FILTER CATALOGUE  —  the filter, described in its own words
// =====================================================================================
/**
 * Every scoring group in one list: what it is worth, how it behaves, when it is switched
 * on, and the actual words in it. It exists so the "Word filter" page in Developer tools
 * can be generated FROM the filter rather than written alongside it — a hand-written page
 * describing this file would be wrong the first time somebody edited a .txt, and a filter
 * you cannot see inside is one nobody can tune.
 *
 * It is pure description: nothing here decides anything. If you add a tier to
 * BorderlineScorer, add its row here too, or the page quietly stops telling the truth.
 */
object FilterCatalogue {

    /**
     * How a group behaves once it has scored. This is the thing people actually need to
     * know about a word, and "8 pts" on its own never conveys it.
     */
    enum class Behaviour {
        /** One hit reaches the bar by itself. CORE words, LOUD phrases, hard fragments. */
        BLOCKS_ALONE,
        /** Scores freely, but the family cap means it can never reach the bar alone. */
        NEEDS_A_SECOND,
        /** Scores NOTHING at all unless the right kind of word is nearby. */
        ONLY_IN_CONTEXT,
        /** Never scores. It is a trigger for other groups, or a veto. */
        NO_SCORE,
    }

    data class Group(
        val name: String,
        /** What ONE hit is worth before any multiplier. 0 for the trigger-only groups. */
        val points: Int,
        val behaviour: Behaviour,
        /** What must be nearby for this to count at all. Null unless ONLY_IN_CONTEXT. */
        val gate: String? = null,
        /** One short line: what this group IS. */
        val what: String,
        /** Examples, in the user's language. Kept to three or four. */
        val examples: String = "",
        /** The caveat worth knowing, shown on the drill-in page. */
        val note: String = "",
        /** Is this group live for the given mode? */
        val activeIn: (relaxed: Boolean, superHardcore: Boolean) -> Boolean,
        /** The entries, for the drill-in list. */
        val entries: () -> List<String>,
    )

    private val ALWAYS: (Boolean, Boolean) -> Boolean = { _, _ -> true }
    private val STRICT_UP: (Boolean, Boolean) -> Boolean = { relaxed, _ -> !relaxed }
    private val RELAXED_ONLY: (Boolean, Boolean) -> Boolean = { relaxed, _ -> relaxed }
    private val SUPER_ONLY: (Boolean, Boolean) -> Boolean = { _, superHardcore -> superHardcore }

    private const val NEAR_SEXUAL = "a sexual word within ${FilterTuning.CONTEXT_WINDOW} words"
    private const val NEAR_PERSON = "a person word within ${FilterTuning.CONTEXT_WINDOW} words"
    private const val NEAR_EITHER =
        "a sexual word OR a person word within ${FilterTuning.CONTEXT_WINDOW} words"

    /** The SCORING groups, strongest first. */
    val GROUPS: List<Group> = listOf(
        Group(
            name = "Core / explicit",
            points = FilterTuning.EXPLICIT_WEIGHT,
            behaviour = Behaviour.BLOCKS_ALONE,
            what = "Sexual in essentially every context.",
            examples = "porn · blowjob · milf · hentai",
            note = "The only tier nothing softens. The medical damper skips it, the gender " +
                "switches skip it, and the one-word ceiling does not apply to it. One hit " +
                "anywhere and the page is blocked.",
            activeIn = ALWAYS,
        ) { BannedWords.CORE.sorted() },

        Group(
            name = "Evasion spellings",
            points = FilterTuning.EXPLICIT_WEIGHT,
            behaviour = Behaviour.BLOCKS_ALONE,
            what = "Deliberate misspellings and adult site names.",
            examples = "pornhub · fap · seggs · onlyfanz",
            note = "Leetspeak and stretched letters are NOT in this list and never need to " +
                "be: p0rn and pooorn are normalised onto \"porn\" by the scorer before any " +
                "list is consulted. This list is for spellings normalising can't reach.",
            activeIn = ALWAYS,
        ) { BannedWords.VARIANT_EXPLICIT.sorted() },

        Group(
            name = "Loud phrases",
            points = FilterTuning.PHRASE_LOUD_WEIGHT,
            behaviour = Behaviour.BLOCKS_ALONE,
            what = "Word ORDER is the giveaway, not the words.",
            examples = "try on haul · leaked nudes · nip slip",
            note = "Not one word of \"try on haul\" is bannable; the three in that order " +
                "plainly are. Counted like Core, so ×2 in a title reaches the bar alone. " +
                "Matched against the page with punctuation stripped, so \"Try-On HAUL!!\" " +
                "still matches.",
            activeIn = ALWAYS,
        ) { BannedPhrases.LOUD.sorted() },

        Group(
            name = "Mixed",
            points = FilterTuning.MIXED_WEIGHT,
            behaviour = Behaviour.NEEDS_A_SECOND,
            what = "Strongly sexual, but with real innocent uses.",
            examples = "nude · naked · topless · xxx",
            note = "Art history, lab mice, \"naked options\" in finance, phone cases. Far too " +
                "useful a word to block a page on its own, so it never does.",
            activeIn = ALWAYS,
        ) { BannedWords.MIXED.sorted() },

        Group(
            name = "Support / strong",
            points = FilterTuning.STRONG_WEIGHT,
            behaviour = Behaviour.NEEDS_A_SECOND,
            what = "Clearly sexual anatomy and slang.",
            examples = "cock · vagina · sex · slut",
            note = "Most of these carry medical exceptions - see the Exceptions section. " +
                "\"vaginal health\" and \"breast cancer\" score nothing at all.",
            activeIn = ALWAYS,
        ) { BannedWords.SUPPORT.sorted() },

        Group(
            name = "Strict-only explicit extras",
            points = FilterTuning.STRONG_WEIGHT,
            behaviour = Behaviour.NEEDS_A_SECOND,
            what = "Always sexual, but too context-dependent to run in Relaxed.",
            activeIn = STRICT_UP,
        ) { BannedWords.EXTRA_EXPLICIT.sorted() },

        Group(
            name = "Combo",
            points = FilterTuning.COMBO_WEIGHT,
            behaviour = Behaviour.ONLY_IN_CONTEXT,
            gate = NEAR_PERSON,
            what = "\"hot\" and \"sexy\", but only when they are about a PERSON.",
            examples = "\"hot women\" scores · \"hot chocolate\" scores nothing",
            note = "RELAXED ONLY. In Strict these same words are already covered by the " +
                "Subtle and Dual groups, so keeping Combo on as well would double-count them.",
            activeIn = RELAXED_ONLY,
        ) { BannedWords.COMBO.sorted() },

        Group(
            name = "Dual",
            points = FilterTuning.DUAL_SEXUAL_WEIGHT,
            behaviour = Behaviour.ONLY_IN_CONTEXT,
            gate = NEAR_SEXUAL,
            what = "Sexual in some contexts, completely innocent in others.",
            examples = "hot · wet · girls · tight",
            note = "On their own these are ordinary English and score ZERO. They only start " +
                "counting once the page has already said something sexual nearby.",
            activeIn = STRICT_UP,
        ) { BannedWords.EXTRA_DUAL.sorted() },

        Group(
            name = "Evasion spellings (dual)",
            points = FilterTuning.DUAL_SEXUAL_WEIGHT,
            behaviour = Behaviour.ONLY_IN_CONTEXT,
            gate = NEAR_SEXUAL,
            what = "Community slang that also has an innocent life.",
            examples = "\"garden hoe\" scores nothing · \"the goon squad\" scores nothing",
            activeIn = STRICT_UP,
        ) { BannedWords.VARIANT_DUAL.sorted() },

        Group(
            name = "Subtle",
            points = FilterTuning.SUBTLE_WEIGHT,
            behaviour = Behaviour.NEEDS_A_SECOND,
            what = "Suggestive, with plenty of innocent uses.",
            examples = "bikini · lingerie · cleavage · underwear",
            note = "Worth very little each, which is the point: it takes a pile of them to " +
                "matter. That is what makes \"bikini ×10\" different from \"bikini ×2\".",
            activeIn = STRICT_UP,
        ) { BannedWords.EXTRA_SUBTLE.sorted() },

        Group(
            name = "Ambiguous",
            points = FilterTuning.AMBIGUOUS_WEIGHT,
            behaviour = Behaviour.ONLY_IN_CONTEXT,
            gate = NEAR_EITHER,
            what = "Usually not about people at all.",
            examples = "sheer · webcam · cosplay · transparent",
            note = "\"sheer drop\" and \"webcam driver\" score nothing. These need either a " +
                "sexual word or a person word beside them before they count.",
            activeIn = STRICT_UP,
        ) { BannedWords.EXTRA_AMBIGUOUS.sorted() },

        Group(
            name = "Soft phrases",
            points = FilterTuning.PHRASE_SOFT_WEIGHT,
            behaviour = Behaviour.NEEDS_A_SECOND,
            what = "Lean adult, but have a genuine innocent life.",
            examples = "try on · fashion haul · gym fit",
            note = "A real fashion haul is a real thing. A weak corroborator, nothing more.",
            activeIn = STRICT_UP,
        ) { BannedPhrases.SOFT.sorted() },

        Group(
            name = "Typed-around fragments",
            points = FilterTuning.SUBTLE_WEIGHT,
            behaviour = Behaviour.NEEDS_A_SECOND,
            what = "Chunks of text, including spaced-out spellings, in the title or URL only.",
            examples = "ling eri · bik ini · lace · haul",
            note = "These are SUBSTRINGS, so they match inside other words - \"lace\" is in " +
                "\"necklace\", \"haul\" is in \"overhaul\". That is exactly why they are " +
                "scored rather than absolute: before 2026-08-04 one of these blocked a page " +
                "outright, which is how apps ended up blocked for saying \"browser\".\n\n" +
                "Each fragment's own weight is listed below. A few are marked BLOCKS ALONE - " +
                "those are unmistakable site names, not vocabulary. They are checked against " +
                "the title and URL only, never the body text, because a page of ordinary " +
                "text would find these everywhere.",
            activeIn = STRICT_UP,
        ) { ModeFragments.STRICT_PLUS.map { fragmentLine(it) } },

        Group(
            name = "Reddit fragments",
            points = FilterTuning.EXPLICIT_WEIGHT,
            behaviour = Behaviour.BLOCKS_ALONE,
            what = "Reddit, and the ways it gets typed around a filter.",
            examples = "red dit · r eddit · reddi t",
            note = "SUPER HARDCORE ONLY. Reddit is on the always-banned domain list in every " +
                "mode anyway; these catch it being reached some other way.",
            activeIn = SUPER_ONLY,
        ) { ModeFragments.SUPER_HARDCORE.map { fragmentLine(it) } },
    )

    /**
     * The TRIGGER list. It scores nothing, ever - it is what switches the context-gated
     * groups above on. Called out separately because "0 points" in a list of scores reads
     * as a mistake rather than as a different job.
     */
    val PERSON_WORDS = Group(
        name = "Person words",
        points = 0,
        behaviour = Behaviour.NO_SCORE,
        what = "The trigger list for the context-gated groups.",
        examples = "girl · woman · model · she",
        note = "These NEVER add a single point. Their only job is to sit next to an " +
            "Ambiguous or Combo word and switch it on. \"sheer\" alone is nothing; " +
            "\"sheer\" next to \"model\" counts.",
        activeIn = ALWAYS,
    ) { BannedWords.PERSON.sorted() }

    /** The innocent-context veto. Its own thing: it doesn't scale a score, it deletes one. */
    val EXCEPTIONS = Group(
        name = "Innocent-context exceptions",
        points = 0,
        behaviour = Behaviour.NO_SCORE,
        what = "Neighbours that throw a match away entirely.",
        examples = "naked mole rat · nude lipstick · vaginal health · pussy riot",
        note = "If one of a word's listed neighbours sits within ${FilterTuning.EXCEPTION_WINDOW} " +
            "words of it, that match scores NOTHING - not less, nothing. This is the sharpest " +
            "tool in the filter and the first place to look when something is blocking that " +
            "shouldn't.\n\nRead each line as \"word: the neighbours that excuse it\". Looked " +
            "up by the matched word AND by its family head, so an entry for \"nude\" also " +
            "covers \"nudes\" and \"nudity\".",
        activeIn = ALWAYS,
    ) { FilterData.langLines("exceptions.txt").sorted() }

    /** Everything that SCALES a score rather than adding to it. */
    data class Scaler(
        val name: String,
        /** The multiplier, in the page's words. */
        val effect: String,
        val what: String,
        val note: String = "",
        val entries: (() -> List<String>)? = null,
    )

    val SCALERS: List<Scaler> = listOf(
        Scaler(
            "In the title or the URL",
            "×${FilterTuning.TITLE_URL_MULTIPLIER}",
            "A word in the title or address counts double. Body text counts once.",
            "It is a much stronger signal: nobody's page title mentions this by accident.",
        ),
        Scaler(
            "The page reads medical",
            "×${FilterTuning.MEDICAL_DAMPEN}",
            "Any clinical word on the page damps every SOFT signal, hard.",
            "A damper, not an exemption. Core words and Loud phrases are never touched, so a " +
                "porn page that happens to say \"doctor\" still blocks - but looking up a " +
                "symptom does not.",
        ) { MedicalContext.WORDS.sorted() },
        Scaler(
            "Sexualised women, switch off",
            "×${FilterTuning.GENDER_OFF_MULTIPLIER}",
            "Softens the suggestive words about women's bodies and clothing.",
            "Locked fully ON outside Relaxed. Core words are never affected by either " +
                "switch, so turning one off can never unblock pornography.",
        ) { (GenderedTerms.SOFT_FEMALE + GenderedTerms.PHRASES_FEMALE).sorted() },
        Scaler(
            "Sexualised men, switch off",
            "×${FilterTuning.GENDER_OFF_MULTIPLIER}",
            "Softens the suggestive words about men's bodies.",
            "Same rule: Core words are never affected.",
        ) { (GenderedTerms.SOFT_MALE + GenderedTerms.PHRASES_MALE).sorted() },
        Scaler(
            "Firefox, with the image add-on",
            "bar ×${FilterTuning.PLUGIN_COVERED_MULTIPLIER}",
            "Raises the score a page needs, rather than lowering what words are worth.",
            "The add-on is reading the same page from the inside and can see the IMAGES, " +
                "which text can never do. Two filters at the same bar just doubles the false " +
                "positives on the one surface where they are most annoying.",
        ),
    )

    /** The two caps: the reason a single word can never block anything. */
    val CAPS: List<Scaler> = listOf(
        Scaler(
            "Same word, over and over",
            "max ${FilterTuning.PER_WORD_CAP}×",
            "One word family counts at most ${FilterTuning.PER_WORD_CAP} times, however " +
                "often it appears.",
        ),
        Scaler(
            "Ceiling for one word",
            "${FilterTuning.SINGLE_WORD_MAX} pts",
            "One family can never earn more than this in total.",
            "This is the whole safety property: ${FilterTuning.SINGLE_WORD_MAX} sits one point " +
                "under the web bar of ${FilterTuning.THRESHOLD}, so a block ALWAYS takes at " +
                "least two different signals. Core words and Loud phrases are the deliberate " +
                "exception.",
        ),
        Scaler(
            "Word families",
            "count as one",
            "Inflections are one signal, not several: \"nude\" + \"nudes\" is one word.",
            "This is what makes the ceiling above actually hold - without it, spelling a word " +
                "three ways would manufacture its own corroboration. A typed-around fragment " +
                "shares its family with the real word for the same reason.",
        ) { FilterData.langLines("family_groups.txt").sorted() },
    )

    private fun fragmentLine(f: ModeFragments.Fragment): String =
        "\"${f.text}\"  —  ${f.weight} pts, counts as \"${f.family}\"" +
            if (f.hard) "  —  BLOCKS ALONE" else ""
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

    /**
     * ONE signal's share of a score — what the block screen shows the user so a block is
     * never just a number. [word] is the family head ("bikini" covers "bikinis" and the
     * "bik ini" fragment), [count] is how many times it was seen, [counted] how many of
     * those actually scored (the rest hit PER_WORD_CAP), and [pct] its share of the total.
     */
    data class Contribution(
        val word: String,
        val tier: String,
        val count: Int,
        val counted: Int,
        val points: Int,
        val pct: Int,
        val capped: Boolean,
    )

    data class Result(
        val score: Int,
        val reason: String,
        /** Biggest first. Empty from [score], which is only ever asked for a number. */
        val contributions: List<Contribution> = emptyList(),
    )

    /**
     * The switches in force for this scoring pass. Passed in rather than read from a cache,
     * so flipping a switch or changing mode takes effect on the very next page. [relaxed]
     * selects the RELAXED tier set (suggestive tiers off); strict is a superset.
     * [superHardcore] adds the Super-hardcore-only fragments (see ModeFragments).
     */
    data class Settings(
        val blockFemale: Boolean,
        val blockMale: Boolean,
        val relaxed: Boolean,
        val superHardcore: Boolean = false,
    ) {
        companion object {
            val ALL_ON = Settings(true, true, relaxed = false)
            fun of(c: Context) = Settings(
                AttractionFilter.blockFemale(c),
                AttractionFilter.blockMale(c),
                relaxed = Mode.isRelaxed(c) || Mode.isOff(c),
                superHardcore = Mode.isSuperHardcore(c),
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

    /** One family's running total during a pass. Becomes a [Contribution] on the way out. */
    private class Detail(val tier: String) {
        var count = 0       // eligible occurrences, INCLUDING the ones the cap threw away
        var counted = 0     // occurrences that actually scored
        var points = 0f
    }

    /** What one scoring pass produced: the score, and the per-family breakdown behind it. */
    private class Tally(val score: Int, val detail: Map<String, Detail>) {
        /** Distinct signals that actually scored — what the in-app threshold gates on. */
        val families: Int get() = detail.count { it.value.points > 0f }
    }

    /** Raw score for logging/flagging; null when nothing sexual was found. */
    fun score(title: String?, url: String?, text: String?, s: Settings = Settings.ALL_ON): Result? {
        val v = compute(title, url, text, s).score
        return if (v <= 0) null else Result(v, reasonFor(v))
    }

    /**
     * Non-null (with a block reason) only when the score reaches the block THRESHOLD.
     *
     * [thresholdMultiplier] raises (or lowers) the bar for this one call — see
     * FilterTuning.PLUGIN_COVERED_MULTIPLIER, the one caller that passes anything but 1.
     */
    fun evaluate(
        title: String?, url: String?, content: String?,
        s: Settings = Settings.ALL_ON,
        thresholdMultiplier: Float = 1f,
    ): Result? {
        val t = compute(title, url, content, s)
        return if (t.score >= webBar(thresholdMultiplier)) resultOf(t) else null
    }

    /**
     * The same judgement for a NON-WEB app screen, at the tighter APP_THRESHOLD - see the
     * note on that constant. Anything at or above the ordinary web THRESHOLD blocks exactly
     * as it would in a browser; between APP_THRESHOLD and THRESHOLD it blocks only when at
     * least APP_MIN_FAMILIES distinct word families contributed, so one word still can't do
     * it alone.
     */
    fun evaluateInApp(title: String?, url: String?, content: String?, s: Settings = Settings.ALL_ON): Result? {
        val t = compute(title, url, content, s)
        if (t.score >= FilterTuning.THRESHOLD) return resultOf(t)
        if (t.score >= FilterTuning.APP_THRESHOLD && t.families >= FilterTuning.APP_MIN_FAMILIES) {
            return resultOf(t)
        }
        return null
    }

    /**
     * The full breakdown WITHOUT a verdict: the score and what built it, whether or not it
     * blocks. What the dev console's "try it" box needs - "why did this score 12" is the
     * question you ask about text that did NOT block, so [evaluate] can't answer it.
     */
    fun explain(title: String?, url: String?, content: String?, s: Settings = Settings.ALL_ON): Result? {
        val t = compute(title, url, content, s)
        return if (t.score <= 0) null else resultOf(t)
    }

    /**
     * The effective block bar for a web page after [multiplier] - the one place that
     * arithmetic lives, so a screen that reports the bar can never disagree with [evaluate].
     */
    fun webBar(multiplier: Float = 1f): Int = bar(FilterTuning.THRESHOLD, multiplier)

    /**
     * The handful of signals worth putting in front of a user: everything at or above
     * SHOW_PCT of the score, falling back to the top few when the score is spread thinly
     * across many small signals. (Mirrors selectContributors() in the extension.)
     */
    fun topContributors(all: List<Contribution>, max: Int = MAX_SHOWN): List<Contribution> {
        val big = all.filter { it.pct >= SHOW_PCT }
        return (if (big.isEmpty()) all.take(FALLBACK_SHOWN) else big).take(max)
    }

    private const val SHOW_PCT = 30
    private const val FALLBACK_SHOWN = 3
    private const val MAX_SHOWN = 4

    /** The effective block bar after a multiplier, never below 1. */
    private fun bar(base: Int, multiplier: Float): Int =
        maxOf(1, Math.round(base * multiplier))

    private fun resultOf(t: Tally): Result =
        Result(t.score, reasonFor(t.score), contributionsOf(t))

    /** The per-family detail as a user-facing list, biggest share first. */
    private fun contributionsOf(t: Tally): List<Contribution> =
        t.detail.entries
            .filter { it.value.points > 0f }
            .map { (fam, d) ->
                val pts = Math.round(d.points)
                Contribution(
                    // "phrase:try on haul" / "fragment:bik ini" read as the thing itself.
                    word = fam.substringAfter(':'),
                    tier = d.tier,
                    count = d.count,
                    counted = d.counted,
                    points = pts,
                    pct = if (t.score > 0) Math.round(d.points * 100f / t.score) else 0,
                    capped = d.count > d.counted,
                )
            }
            .sortedByDescending { it.points }

    private fun reasonFor(score: Int): String = "Sexual / adult content (score $score)"

    // A hit worth counting: which family, what tier, base weight, and the exact matched word
    // (for the gender multiplier). base == 0 means "recognised but scores nothing here".
    private data class Hit(val fam: String, val tier: String, val base: Int, val word: String)

    private fun compute(title: String?, url: String?, body: String?, set: Settings): Tally {
        val a = active(set.relaxed)
        // family -> occurrences + points so far. Shared across every field and every tier,
        // so PER_WORD_CAP and SINGLE_WORD_MAX apply to a CONCEPT, not to one spelling of it.
        val detail = HashMap<String, Detail>()

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
                val p = add(hit.fam, hit.tier, hit.base, mult, genderMultiplier(hit.word, set), detail)
                if (hit.tier == TIER_EXPLICIT) explicitTotal += p else otherTotal += p
            }
        }

        // Phrases are matched on the whole (normalised) field, because the meaning is in the
        // ORDER — no single word of "try on haul" is bannable, the three together plainly are.
        for ((text, mult) in listOf(
            normalise(title) to FilterTuning.TITLE_URL_MULTIPLIER,
            normalise(url) to FilterTuning.TITLE_URL_MULTIPLIER,
            normalise(body) to 1,
        )) {
            val (loud, soft) = scorePhrases(text, mult, set, detail)
            explicitTotal += loud
            otherTotal += soft
        }

        // Mode-gated FRAGMENTS (ModeFragments): title and URL only, never the body. They are
        // substring matches, so letting them loose on a whole page of text would find "lace"
        // in every "necklace" on a shopping page. Title and URL are where someone types
        // around a filter, and that is the only place this tier has ever earned its keep.
        for ((text, mult) in listOf(
            normalise(title) to FilterTuning.TITLE_URL_MULTIPLIER,
            normalise(url) to FilterTuning.TITLE_URL_MULTIPLIER,
        )) {
            val (hard, soft) = scoreFragments(text, mult, set, detail)
            explicitTotal += hard
            otherTotal += soft
        }

        // Looking up a symptom is not looking at porn. Damp the SOFT signals hard when the
        // page reads medical; CORE/LOUD are never damped, so real porn still blocks. The
        // per-family detail is damped alongside the total, or the shares we show the user
        // would be percentages of a number that no longer exists.
        if (hasMedicalContext(title, body)) {
            otherTotal *= FilterTuning.MEDICAL_DAMPEN
            for (d in detail.values) if (d.tier != TIER_EXPLICIT) d.points *= FilterTuning.MEDICAL_DAMPEN
        }
        return Tally(Math.round(explicitTotal + otherTotal), detail)
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
                w in a.explicit -> return hitOrVeto(words, i, fam, TIER_EXPLICIT, FilterTuning.EXPLICIT_WEIGHT, w)
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

    /** The tier that is exempt from every softener: CORE words, LOUD phrases, hard fragments. */
    private const val TIER_EXPLICIT = "explicit"

    /**
     * Add one hit for [fam]. Two caps apply: PER_WORD_CAP on occurrences, and — for every
     * tier except explicit — SINGLE_WORD_MAX on the family's total points, the guarantee that
     * no single word can ever block a page by itself. Returns the points actually added.
     *
     * [detail] carries the running tally AND the breakdown the block screen shows: `count`
     * is every eligible occurrence, `counted` only the ones that got through the cap, so a
     * page saying "bikini" fifty times can be shown as capped rather than as fifty hits.
     */
    private fun add(
        fam: String, tier: String, base: Int, mult: Int, gender: Float,
        detail: HashMap<String, Detail>,
    ): Float {
        val d = detail.getOrPut(fam) { Detail(tier) }
        d.count++
        if (d.counted >= FilterTuning.PER_WORD_CAP) return 0f     // over cap: ignored
        var pts = base * mult * gender
        if (tier != TIER_EXPLICIT) {
            pts = minOf(pts, maxOf(0f, FilterTuning.SINGLE_WORD_MAX - d.points))
        }
        if (pts <= 0f) return 0f
        d.counted++
        d.points += pts
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
        text: String, mult: Int, set: Settings, detail: HashMap<String, Detail>,
    ): Pair<Float, Float> {
        if (text.isBlank()) return 0f to 0f
        fun run(phrases: Set<String>, weight: Int, tier: String): Float {
            var s = 0f
            for (p in phrases) {
                if (!text.contains(" $p ")) continue
                s += add("phrase:$p", tier, weight, mult, phraseMultiplier(p, set), detail)
            }
            return s
        }
        // LOUD is treated like CORE (exempt from the single-word cap) so a loud phrase in a
        // title blocks alone; SOFT is a weak, strict-only corroborator.
        val loud = run(BannedPhrases.LOUD, FilterTuning.PHRASE_LOUD_WEIGHT, TIER_EXPLICIT)
        val soft = if (!set.relaxed) run(BannedPhrases.SOFT, FilterTuning.PHRASE_SOFT_WEIGHT, "phrase") else 0f
        return loud to soft
    }

    /**
     * MODE-GATED FRAGMENTS (see ModeFragments) against one normalised field. Returns
     * (hardPoints, softPoints), split for the medical damper exactly like the phrases.
     *
     * Every fragment goes through the SAME add() as every word, which is the whole point of
     * the 2026-08-04 rework: a soft fragment shares its family's SINGLE_WORD_MAX budget, so
     * "lace" — or a dozen "lace"s — can corroborate a block but can never be one.
     */
    private fun scoreFragments(
        text: String, mult: Int, set: Settings, detail: HashMap<String, Detail>,
    ): Pair<Float, Float> {
        if (text.isBlank()) return 0f to 0f
        var hard = 0f
        var soft = 0f
        for (f in ModeFragments.active(strict = !set.relaxed, superHardcore = set.superHardcore)) {
            if (!text.contains(f.text)) continue
            val tier = if (f.hard) TIER_EXPLICIT else "fragment"
            // Keyed by FAMILY, not by spelling: "bik ini" lands on the same budget as the
            // word "bikini", so an evasion and the real word are one signal, not two. The
            // gender switches read the family too, so turning the women side down softens
            // "bik ini" exactly as much as it softens "bikini".
            val pts = add(f.family, tier, f.weight, mult, genderMultiplier(f.family, set), detail)
            if (f.hard) hard += pts else soft += pts
        }
        return hard to soft
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
