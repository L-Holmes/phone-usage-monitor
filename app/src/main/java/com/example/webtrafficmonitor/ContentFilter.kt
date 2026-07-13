package com.example.webtrafficmonitor

import android.content.Context
import android.util.Log
import java.util.zip.GZIPInputStream

// =====================================================================================
//  CONTENT FILTER  —  the single home for all "banned words / banned sites" logic.
// =====================================================================================
//
//  Everything that decides whether a page looks sexual/adult lives here:
//    * the four hardcoded word tiers (below),
//    * BorderlineScorer  — turns page text/title/URL into a score and a block reason,
//    * DomainBlocklist   — the ~550k-host adult blocklist (loaded from the bundled .gz),
//    * DomainStrikes     — repeat-offender domains get blocked for a while,
//    * DomainGreylist    — our own list of mixed-content sites (Reddit, etc.) to limit.
//
//  The word lists used to live in assets/words/*.txt and were read at runtime; they are
//  now hardcoded here so the whole thing is one source-controlled module.
//
//  ── THE FOUR WORD TIERS (strongest → weakest) ──────────────────────────────────────
//
//  EXPLICIT (weight 6) — hardcore / clinical terms that are sexual in essentially every
//      context, with no innocent use ("blowjob", "pussy", "porn"). Two in the body — or
//      one in the title/URL, which counts double — is enough to block. Also act as
//      "indicators" that switch on a nearby dual-meaning word.
//
//  STRONG (weight 4) — always-adult words that mean the adult thing ~99% of the time
//      ("sex", "slut", "onlyfans", "naked"). They count on their own (no neighbour
//      needed) but weigh a little less, so a single stray use is tolerated. Also
//      indicators. ("sex" is the one word here with real innocent uses — it must repeat
//      to block; move it to DUAL if a sex-ed page ever trips.)
//
//  SUBTLE (weight 2) — suggestive terms that lean adult but have plenty of innocent uses
//      (swimwear, fashion, fitness): "bikini", "lingerie", "cleavage". One alone should
//      not block; several — or one alongside other signals — should. PER_WORD_CAP gives
//      the "bikini ×10 = bad, bikini ×2 = fine" behaviour. Also indicators.
//
//  DUAL (weight 3, ONLY in context) — words that are sexual in some contexts and innocent
//      in others: "hot", "wet", "girls", "bang". They count for NOTHING on their own; a
//      dual word only scores when an indicator (any EXPLICIT/STRONG/SUBTLE word) is within
//      CONTEXT_WINDOW words of it. "hot chocolate" → 0; "hot naked teens" → "hot"+"teens".
//
//  There is no phrase matching — word combinations are handled purely by the DUAL context
//  rule. Multi-word entries (e.g. "see through") therefore never match; they are kept in
//  the list only for the record.
//
//  TUNING: to make a word stronger/weaker, move it between the four sets below, or adjust
//  the weights / THRESHOLD / CONTEXT_WINDOW / PER_WORD_CAP constants.
// =====================================================================================


// ── Scoring knobs ────────────────────────────────────────────────────────────────────
object FilterTuning {
    const val EXPLICIT_WEIGHT = 6
    const val STRONG_WEIGHT = 4
    const val SUBTLE_WEIGHT = 2
    const val DUAL_SEXUAL_WEIGHT = 3

    // PHRASES (see BannedPhrases). A LOUD phrase in a title is meant to block on its own:
    // 7 x TITLE_URL_MULTIPLIER = 14, comfortably over THRESHOLD.
    const val PHRASE_LOUD_WEIGHT = 7
    const val PHRASE_SOFT_WEIGHT = 3

    const val CONTEXT_WINDOW = 4     // a DUAL word counts only if an indicator is this near
    const val PER_WORD_CAP = 5       // one word can contribute at most this many times
    const val THRESHOLD = 10         // score at/above this → block
    const val TITLE_URL_MULTIPLIER = 2   // hits in the title or URL count double

    // How much a SOFT gendered word is still worth when that side of the filter is switched
    // off. Not zero - "bikini" on an actual porn page should still nudge the needle, it just
    // shouldn't block a swimwear shop on its own. See GenderedTerms.
    const val GENDER_OFF_MULTIPLIER = 0.25f

    // Medical/clinical context found on the page (see MedicalContext) multiplies the whole
    // score by this. Someone looking up a symptom must not be blocked.
    const val MEDICAL_DAMPEN = 0.35f
}


// ── The four hardcoded word tiers ────────────────────────────────────────────────────
object BannedWords {

    val EXPLICIT: Set<String> = setOf(
        "anal", "analsex", "ballsack", "bareback", "bbw", "bdsm", "blowjob", "bukkake",
        "buttplug", "camgirl", "camwhore", "clit", "clitoris", "cock", "cocks", "creampie",
        "cuckold", "cum", "cumming", "cumshot", "cunnilingus", "cunt", "deepthroat", "dildo",
        "dildos", "doggystyle", "dominatrix", "ejaculate", "ejaculation", "erection",
        "fellatio", "femdom", "fingering", "fisting", "footjob", "foreskin", "gangbang",
        "gloryhole", "handjob", "hardcore", "hentai", "horny", "incest", "jerkoff", "labia",
        "masturbate", "masturbating", "masturbation", "milf", "nipple", "nipples", "nsfw",
        "nude", "nudes", "nudity", "orgasm", "orgasms", "orgy", "pegging", "penetration",
        "penis", "porn", "porno", "pornographic", "pornography", "pornstar", "pussies",
        "pussy", "rimjob", "scissoring", "semen", "sextape", "sextoy", "sextoys", "sodomy",
        "spunk", "squirting", "strapon", "threesome", "titfuck", "titjob", "tits", "titties",
        "titty", "twat", "vagina", "vaginal", "vulva", "wank", "wanking", "xxx",
    )

    val STRONG: Set<String> = setOf(
        "boob", "boobies", "boobs", "hardon", "naked", "onlyfans", "sex", "slut", "sluts",
        "slutty", "smut", "topless", "whore", "whores", "xrated",
    )

    val SUBTLE: Set<String> = setOf(
        "arousal", "aroused", "bikini", "bikinis", "booty", "bosom", "bra", "braless",
        "breast", "breasts", "busty", "butt", "buttock", "buttocks", "cleavage", "curves",
        "curvy", "erotic", "erotica", "escort", "escorts", "fetish", "flirt", "flirty",
        "foreplay", "garter", "hooters", "intercourse", "intimate", "kinky", "lapdance",
        "lewd", "libido", "lingerie", "lust", "lustful", "negligee", "panties", "provocative",
        "raunchy", "revealing", "risque", "seduce", "seduction", "seductive", "sensual",
        "sexual", "sexuality", "sexy", "showgirl", "skimpy", "spank", "spanking", "strip",
        "stripper", "striptease", "suggestive", "swimsuit", "temptress", "thong", "thongs",
        "underwear", "undress", "undressing", "voluptuous", "webcam", "sheer", "transparent",
        "tights", "panty", "pantyhose", "cosplay",
        // multi-word entries kept for the record; they never match (no phrase matching):
        // "see through", "try on", "try on haul"
    )

    val DUAL: Set<String> = setOf(
        "adult", "ass", "babe", "babes", "bang", "banged", "banging", "blow", "blown",
        "bombshell", "cheeks", "chick", "chicks", "dirties", "dirty", "doll", "dolls",
        "exposed", "fuck", "fucked", "fucking", "gentlemen", "girl", "girls", "hookup", "hot",
        "hottie", "hotties", "hump", "humping", "kink", "ladies", "lady", "load", "loads",
        "mature", "moan", "moaning", "naughty", "package", "petite", "pole", "rack", "ride",
        "riding", "score", "screw", "screwed", "screwing", "spread", "stud", "suck", "sucking",
        "tease", "teen", "teens", "thick", "tight", "toy", "toys", "vixen", "wet", "women",
    )

    // ── EVASION SPELLINGS ────────────────────────────────────────────────────────────
    // Deliberate misspellings, shortenings and slang used to get around a filter. These
    // are folded into the tiers below, so they score exactly like the word they stand in
    // for.
    //
    // NOTE what you do NOT need to list here: leetspeak and stretched letters. The scorer
    // normalises every token before matching (see deleet / collapse in BorderlineScorer),
    // so "p0rn", "pr0n", "s3x", "b00bs", "pooorn" and "seeexy" all already resolve to
    // words that are in the lists above. Only add genuinely DIFFERENT spellings here.
    //
    // Everything in this set has no real innocent use, so it sits at EXPLICIT weight.
    // If you add something with an innocent meaning (e.g. "goon", "hoe"), put it in
    // VARIANT_DUAL instead - it will then only score next to another sexual word.
    val VARIANT_EXPLICIT: Set<String> = setOf(
        // naked / nude
        "nake", "nakey", "nekked", "nekkid", "nakd", "nekid", "nood", "noods", "nudez", "nudz",
        // porn
        "pron", "prn0", "pr0no", "prnhub", "pornhub", "xnxx", "xvideos", "redtube", "youporn",
        "brazzers", "spankbang", "motherless", "xhamster",
        // sex
        "seggs", "secks", "sechs", "sexo", "sexx", "sexxx",
        // breasts
        "bewbs", "bewb", "boobz", "titz", "tiddies", "tiddy", "tittys", "milkers",
        // masturbation / the community's own words
        "fap", "fapping", "fapped", "faps", "fapper", "coom", "coomer", "cooming",
        "masterbate", "masterbating", "masterbation", "masturbait", "gooner",
        "cumz", "cummin", "jizz", "jizzed",
        // anatomy misspellings
        "pussi", "pusy", "pusssy", "vajayjay", "coochie", "cooter", "phuck", "fukk",
        // adult platforms / genres
        "onlyfanz", "onlyfan", "fansly", "chaturbate", "stripchat", "camsoda", "myfreecams",
        "hentia", "hentay", "ahegao", "ecchi", "futanari", "futa", "doujin", "doujinshi",
        "rule34", "r34", "lewds", "lewding", "nudify", "deepnude", "thot", "thots", "thotty",
    )

    // Evasion spellings that DO have innocent uses. Context-gated, like any DUAL word, so
    // they score NOTHING on their own. Keep this list tight: every entry here is a word
    // that appears on ordinary pages, and the only thing saving us is the context rule.
    // ("edge" and "cake" were tried and pulled - far too common to be worth the noise.)
    val VARIANT_DUAL: Set<String> = setOf(
        "goon", "gooning", "goonin", "edging", "thicc", "thicce", "phat",
        "hoe", "hoes", "buns", "melons", "smash",
    )

    // Any non-dual sexual word "switches on" a nearby dual word.
    val INDICATORS: Set<String> = EXPLICIT + STRONG + SUBTLE + VARIANT_EXPLICIT
}


// ── Phrases ──────────────────────────────────────────────────────────────────────────
// The word tiers can only ever see ONE word at a time, which is why "try on haul",
// "nip slip" and "no leggings" all sailed straight through: not one of those words is
// banned on its own, and they never will be. Phrases are matched against the page text as
// a whole, so word ORDER carries the meaning.
//
// LOUD  = the phrase itself is the giveaway. One of these in a title blocks on its own.
// SOFT  = leans adult, but has a real innocent life (a genuine fashion haul). Needs help
//         from something else on the page to reach the threshold.
//
// TO ADD ONE: lower case, single spaces, letters and digits only - it is matched against a
// normalised copy of the page, so punctuation and hyphens in the real text don't matter
// ("try-on haul" and "Try On Haul!!" both match "try on haul").
// ── Who is being sexualised ──────────────────────────────────────────────────────────
// Two switches (see AttractionFilter) let someone turn DOWN the sexualised-women or the
// sexualised-men side of the filter. Default: both fully on.
//
// WHAT THIS IS FOR: a straight woman shopping for lingerie, or a gay man who has no
// interest in bikini content, should not be fighting the filter all day. It is an
// accessibility valve, not an escape hatch.
//
// WHAT IT DOES *NOT* TOUCH - and this is the important bit:
//   Only the SOFT, suggestive words and phrases below are affected. Anything EXPLICIT
//   ("porn", "blowjob", "milf", "onlyfans"...) keeps its full weight no matter what these
//   switches say, because that is pornography regardless of who you're attracted to.
//   Turning a switch off lets you look at swimwear. It does not let you look at porn.
object GenderedTerms {

    /** Suggestive terms about WOMEN's bodies/clothing. Softened when the women switch is off. */
    val SOFT_FEMALE: Set<String> = setOf(
        "bikini", "bikinis", "lingerie", "bra", "braless", "panties", "panty", "pantyhose",
        "thong", "thongs", "negligee", "swimsuit", "tights", "cleavage", "busty", "bosom",
        "boob", "boobs", "boobies", "breast", "breasts", "booty", "curves", "curvy",
        "voluptuous", "hooters", "showgirl", "temptress", "skimpy", "garter", "underwear",
        "topless", "milkers", "bewbs", "bewb", "boobz", "titz", "tiddies", "tiddy",
    )

    /** Suggestive terms about MEN's bodies. Softened when the men switch is off. */
    val SOFT_MALE: Set<String> = setOf(
        "shirtless", "abs", "sixpack", "bulge", "speedo", "speedos", "hardon", "beefcake",
        "hunk", "hunks", "himbo", "dadbod", "musclebound",
    )

    /** Phrases that only make sense as sexualised-women content. */
    val PHRASES_FEMALE: Set<String> = setOf(
        "bikini haul", "lingerie haul", "underwear haul", "swimwear try on", "braless try on",
        "no bra", "no panties", "nip slip", "nipple slip", "camel toe", "micro bikini",
        "bikini body", "curvy model", "plus size model", "boudoir shoot",
        "naked girls", "nude girls", "hot girls", "sexy girls", "naked women",
    )

    /** Phrases that only make sense as sexualised-men content. */
    val PHRASES_MALE: Set<String> = setOf(
        "shirtless men", "hot guys", "sexy men", "male stripper", "naked men",
    )
}


// ── Medical / clinical context ───────────────────────────────────────────────────────
// "vaginal discharge", "testicular lump", "breast screening" — the anatomy words are the
// same, the intent could not be more different, and someone must never be blocked from
// looking up a symptom.
//
// If any of these words appear on the page, the sexual score is heavily damped (see
// FilterTuning.MEDICAL_DAMPEN). It is a DAMPER, not an exemption: a porn page that happens
// to contain the word "doctor" doesn't get a free pass, it just needs more evidence.
//
// NOTE ON PUBLIC LISTS: there is no well-known MIT-licensed "medical context" word list -
// the public ones (LDNOOBW and friends) are profanity lists, which is the opposite problem.
// This is our own, so add to it freely when you find a gap.
object MedicalContext {
    val WORDS: Set<String> = setOf(
        "symptom", "symptoms", "diagnosis", "diagnosed", "treatment", "treated", "infection",
        "infected", "discharge", "itching", "itchy", "rash", "swelling", "swollen", "lump",
        "lumps", "pain", "painful", "bleeding", "cramps", "doctor", "gp", "clinic", "clinical",
        "nhs", "hospital", "nurse", "medical", "medicine", "prescription", "antibiotics",
        "cancer", "screening", "smear", "biopsy", "cyst", "thrush", "yeast", "bacterial",
        "vaginosis", "uti", "cystitis", "std", "sti", "chlamydia", "herpes", "hpv",
        "contraception", "contraceptive", "pregnancy", "pregnant", "menstrual", "menstruation",
        "period", "periods", "menopause", "ovulation", "fertility", "endometriosis",
        "prostate", "testicular", "erectile", "dysfunction", "puberty", "hormone", "hormonal",
        "surgery", "examination", "health", "healthcare", "gynaecologist", "gynecologist",
        "urologist", "dermatologist", "mastectomy", "mammogram",
    )
}


object BannedPhrases {

    val LOUD: Set<String> = setOf(
        // the "technically clothed" genre
        "nip slip", "nipple slip", "wardrobe malfunction", "accidental exposure",
        "see through", "see thru", "sheer top", "sheer dress", "nothing underneath",
        "no panties", "no underwear", "no bra", "no pants", "no leggings", "no clothes",
        "without underwear", "without a bra", "without panties", "braless try on",
        // haul / try-on as a delivery vehicle
        "try on haul", "tryon haul", "lingerie haul", "bikini haul", "sheer haul",
        "transparent haul", "underwear haul", "nude haul", "see through haul",
        "mesh haul", "micro bikini",
        // the obvious ones
        "leaked nudes", "leaked onlyfans", "onlyfans leak", "sex tape", "adult film",
        "free porn", "porn site", "live cam", "cam girls", "webcam girls", "camel toe",
        "thirst trap", "hidden cam", "spy cam", "strip tease", "pole dance",
        "naked girls", "nude girls", "hot girls", "sexy girls", "naked women",
        "only fans", "adult content", "not safe for work",
    )

    val SOFT: Set<String> = setOf(
        "try on", "tryon", "haul video", "clothing haul", "fashion haul", "swimsuit haul",
        "tight dress", "short skirt", "mini skirt", "crop top", "yoga pants", "leggings haul",
        "gym fit", "workout fit", "body check", "before and after body", "beach body",
        "bikini body", "hot tub", "shower scene", "bed scene", "massage video",
        "asmr girl", "girl next door", "curvy model", "plus size model", "fitness model",
        "swimwear try on", "boudoir shoot", "glamour shoot", "photo shoot bikini",
    )
}


// ── Who the filter is switched on for ────────────────────────────────────────────────
/**
 * The two "sexualised women / sexualised men" switches. Both default ON.
 *
 * LOCKED OUTSIDE RELAXED MODE. In strict or super hardcore these are forced back on and
 * cannot be changed - otherwise the first thing a bad night does is flip a switch. See
 * [canEdit].
 */
object AttractionFilter {
    private const val PREFS = "attraction_filter"
    private const val KEY_FEMALE = "block_female"
    private const val KEY_MALE = "block_male"

    /** May the user change these right now? Only in Relaxed. */
    fun canEdit(c: Context): Boolean = Mode.isRelaxed(c)

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
object BorderlineScorer {

    data class Result(val score: Int, val reason: String)

    /**
     * The switches in force for this scoring pass. Passed in rather than read from a cache,
     * so flipping a switch or changing mode takes effect on the very next page.
     */
    data class Settings(val blockFemale: Boolean, val blockMale: Boolean) {
        companion object {
            val ALL_ON = Settings(true, true)
            fun of(c: Context) =
                Settings(AttractionFilter.blockFemale(c), AttractionFilter.blockMale(c))
        }
    }

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

    private fun compute(title: String?, url: String?, body: String?, set: Settings): Int {
        val counted = HashMap<String, Int>()   // per-word occurrence cap, shared across fields
        var total = 0f
        total += scoreField(tokenize(title), FilterTuning.TITLE_URL_MULTIPLIER, counted, set)
        total += scoreField(tokenize(url), FilterTuning.TITLE_URL_MULTIPLIER, counted, set)
        total += scoreField(tokenize(body), 1, counted, set)
        // Phrases are matched on the text as a whole, because the meaning is in the ORDER -
        // no single word of "try on haul" is bannable, and the three together plainly are.
        total += scorePhrases(normalise(title), FilterTuning.TITLE_URL_MULTIPLIER, counted, set)
        total += scorePhrases(normalise(url), FilterTuning.TITLE_URL_MULTIPLIER, counted, set)
        total += scorePhrases(normalise(body), 1, counted, set)

        // Looking up a symptom is not looking at porn. Damp the whole score hard when the
        // page reads as medical - a damper, not an exemption, so an actual porn page that
        // says "doctor" once still needs only a little more evidence.
        if (hasMedicalContext(title, body)) total *= FilterTuning.MEDICAL_DAMPEN

        return Math.round(total)
    }

    private fun hasMedicalContext(title: String?, body: String?): Boolean {
        for (w in tokenize(title)) if (w in MedicalContext.WORDS) return true
        for (w in tokenize(body)) if (w in MedicalContext.WORDS) return true
        return false
    }

    /**
     * What one word is worth, after the gender switches. A SOFT gendered word is knocked
     * down to GENDER_OFF_MULTIPLIER when its side is switched off; EXPLICIT words are never
     * touched, so turning a switch off can never unblock pornography.
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

    private fun scoreField(
        words: List<String>, mult: Int, counted: HashMap<String, Int>, set: Settings,
    ): Float {
        var s = 0f
        for (i in words.indices) {
            val (w, base) = weigh(words, i)
            if (base == 0) continue
            val c = counted.getOrDefault(w, 0)
            if (c >= FilterTuning.PER_WORD_CAP) continue
            counted[w] = c + 1
            s += base * mult * genderMultiplier(w, set)
        }
        return s
    }

    /**
     * The weight of word [i], trying the token as written AND its normalised form, so
     * "p0rn" / "pooorn" / "PORN" all land on "porn". Returns the form that matched (for the
     * per-word cap) and its weight.
     */
    private fun weigh(words: List<String>, i: Int): Pair<String, Int> {
        for (w in candidates(words[i])) {
            val base = when {
                w in BannedWords.EXPLICIT -> FilterTuning.EXPLICIT_WEIGHT
                w in BannedWords.VARIANT_EXPLICIT -> FilterTuning.EXPLICIT_WEIGHT
                w in BannedWords.STRONG -> FilterTuning.STRONG_WEIGHT
                w in BannedWords.SUBTLE -> FilterTuning.SUBTLE_WEIGHT
                w in BannedWords.DUAL || w in BannedWords.VARIANT_DUAL ->
                    if (hasIndicatorNear(words, i)) FilterTuning.DUAL_SEXUAL_WEIGHT else 0
                else -> 0
            }
            if (base > 0) return w to base
        }
        return words[i] to 0
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

    /** Digit/symbol substitutions: p0rn -> porn, s3x -> sex, b00bs -> boobs. */
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
     * Stretched letters: "pooorn" -> "porn", "seeexy" -> "sexy". Runs collapse all the way
     * to ONE letter, which does mangle honest doubles ("boobs" -> "bobs") - that's fine,
     * because candidates() checks the word as typed as well, and "boobs" matches there.
     */
    private fun collapse(s: String): String {
        if (s.length < 3) return s
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            if (i == 0 || s[i] != s[i - 1]) sb.append(s[i])
        }
        return sb.toString()
    }

    private fun hasIndicatorNear(words: List<String>, i: Int): Boolean {
        val lo = maxOf(0, i - FilterTuning.CONTEXT_WINDOW)
        val hi = minOf(words.lastIndex, i + FilterTuning.CONTEXT_WINDOW)
        for (j in lo..hi) {
            if (j == i) continue
            if (candidates(words[j]).any { it in BannedWords.INDICATORS }) return true
        }
        return false
    }

    private fun scorePhrases(
        text: String, mult: Int, counted: HashMap<String, Int>, set: Settings,
    ): Float {
        if (text.isBlank()) return 0f
        var s = 0f
        fun run(phrases: Set<String>, weight: Int) {
            for (p in phrases) {
                if (!text.contains(" $p ")) continue
                val key = "phrase:$p"
                val c = counted.getOrDefault(key, 0)
                if (c >= FilterTuning.PER_WORD_CAP) continue
                counted[key] = c + 1
                s += weight * mult * phraseMultiplier(p, set)
            }
        }
        run(BannedPhrases.LOUD, FilterTuning.PHRASE_LOUD_WEIGHT)
        run(BannedPhrases.SOFT, FilterTuning.PHRASE_SOFT_WEIGHT)
        return s
    }

    /**
     * The page as one padded, single-spaced, letters-and-digits-only string, so a phrase can
     * be found with a plain " phrase " contains(). The padding is what gives us word
     * boundaries for free: " no bra " will not match inside "piano brand".
     */
    private fun normalise(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return " " + s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
    }

    /**
     * Lowercase, split on anything that isn't a letter or DIGIT, keep tokens of length >= 2.
     * Digits are kept deliberately: strip them and "p0rn" tokenises to "p"/"rn" and no
     * amount of de-leeting can save it.
     */
    private fun tokenize(s: String?): List<String> {
        if (s.isNullOrEmpty()) return emptyList()
        return s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
    }
}


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
    fun isGreylisted(host: String): Boolean {
        var cur = host.lowercase().removePrefix("www.")
        while (true) {
            if (cur in DOMAINS) return true
            val dot = cur.indexOf('.')
            if (dot < 0) return false
            cur = cur.substring(dot + 1)
            if (cur.indexOf('.') < 0) return false
        }
    }
}
