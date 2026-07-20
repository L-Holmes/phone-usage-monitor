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
object BannedWords {

    // CORE — one occurrence is enough to block. Sexual in essentially every context.
    val CORE: Set<String> = setOf(
        "bdsm", "blowjob", "bukkake", "buttplug", "camgirl", "camwhore", "creampie",
        "cuckold", "cumshot", "cunnilingus", "deepthroat", "dildo", "dildos", "doggystyle",
        "dominatrix", "ejaculation", "fellatio", "femdom", "fisting", "footjob", "gangbang",
        "gloryhole", "handjob", "hentai", "incest", "jerkoff", "masturbate", "masturbating",
        "masturbation", "milf", "onlyfans", "orgasm",
        "orgasms", "orgy", "pegging", "porn", "porno", "pornographic", "pornography",
        "pornstar", "rimjob", "scissoring", "sextape", "sextoy", "sextoys", "squirting",
        "strapon", "threesome", "titfuck", "titjob",
    )

    // MIXED — strongly sexual, real innocent uses. NEVER blocks alone.
    val MIXED: Set<String> = setOf(
        "naked", "nude", "nudes", "nudity", "topless", "xxx",
    )

    // SUPPORT — clearly sexual, but common enough as anatomy/insult/slang that one stray
    // mention shouldn't nuke a page. Two in the body (or one in the title/URL).
    val SUPPORT: Set<String> = setOf(
        "anal", "analsex", "ballsack", "bareback", "bbw", "boob", "boobies", "boobs",
        "clit", "clitoris", "cock", "cocks", "cum", "cumming", "cunt", "ejaculate",
        "erection", "foreskin", "hardcore", "hardon", "horny", "labia", "nipple", "nipples",
        "nsfw", "penetration", "penis", "pussies", "pussy", "semen", "sex", "slut", "sluts",
        "slutty", "smut", "sodomy", "spunk", "tits", "titties", "titty", "twat", "vagina",
        "vaginal", "vulva", "wank", "wanking", "whore", "whores", "xrated",
    )

    // COMBO — suggestive words that count ONLY within CONTEXT_WINDOW of a PERSON word.
    // Relaxed-only: strict scores these via its DUAL/SUBTLE tiers.
    val COMBO: Set<String> = setOf(
        "hot", "sexy", "hottie", "hotties",
    )

    // ── STRICT-ONLY tiers (merged in when NOT relaxed) ────────────────────────────────

    // Always-sexual but too context-dependent for relaxed ("fingering" — guitar fingering).
    val EXTRA_EXPLICIT: Set<String> = setOf(
        "fingering",
    )

    val EXTRA_SUBTLE: Set<String> = setOf(
        "arousal", "aroused", "bikini", "bikinis", "booty", "bosom", "bra", "braless",
        "breast", "breasts", "busty", "butt", "buttock", "buttocks", "cleavage", "curves",
        "curvy", "erotic", "erotica", "escort", "escorts", "fetish", "flirt", "flirty",
        "foreplay", "garter", "hooters", "intercourse", "intimate", "kinky", "lapdance",
        "lewd", "libido", "lingerie", "lust", "lustful", "negligee", "panties", "provocative",
        "raunchy", "revealing", "risque", "seduce", "seduction", "seductive", "sensual",
        "sexual", "sexuality", "sexy", "showgirl", "skimpy", "spank", "spanking", "strip",
        "stripper", "striptease", "suggestive", "swimsuit", "temptress", "thong", "thongs",
        "underwear", "undress", "undressing", "voluptuous", "panty",
    )

    val EXTRA_DUAL: Set<String> = setOf(
        "adult", "ass", "babe", "babes", "bang", "banged", "banging", "blow", "blown",
        "bombshell", "cheeks", "chick", "chicks", "dirties", "dirty", "doll", "dolls",
        "exposed", "fuck", "fucked", "fucking", "gentlemen", "girl", "girls", "hookup", "hot",
        "hottie", "hotties", "hump", "humping", "kink", "ladies", "lady", "load", "loads",
        "mature", "moan", "moaning", "naughty", "package", "petite", "pole", "rack", "ride",
        "riding", "score", "screw", "screwed", "screwing", "spread", "stud", "suck", "sucking",
        "tease", "teen", "teens", "thick", "tight", "toy", "toys", "vixen", "wet", "women",
    )

    // Context-only, weakest: frequently NOT about people at all (curtains, video calls, UI,
    // hosiery). Score NOTHING alone — only with a person/sexual word within CONTEXT_WINDOW.
    val EXTRA_AMBIGUOUS: Set<String> = setOf(
        "sheer", "transparent", "webcam", "tights", "cosplay", "pantyhose",
    )

    // "Person" words — being near one switches on an AMBIGUOUS word, a COMBO word, or a
    // PHRASE. ("is there anything about women/people nearby?", done locally per match.)
    val PERSON: Set<String> = setOf(
        "girl", "girls", "woman", "women", "lady", "ladies", "babe", "babes",
        "chick", "chicks", "teen", "teens", "model", "models", "gf", "girlfriend",
        "wife", "she", "her",
    )

    // ── EVASION SPELLINGS (Kotlin-only; the JS relies on the domain blocklist for these) ──
    // Deliberate misspellings, shortenings and slang used to get around a filter. Folded in
    // so they score like the tier they stand in for. Leetspeak and stretched letters do NOT
    // belong here — the scorer normalises every token first (deleet / collapse), so "p0rn",
    // "b00bs", "seeexy" already resolve to real list words. Only genuinely DIFFERENT
    // spellings go here. Everything below is unambiguously adult, so it scores at CORE.
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

    // Evasion spellings that DO have innocent uses. Context-gated like any DUAL word (score
    // nothing alone). Strict-only. Keep tight: every entry appears on ordinary pages.
    val VARIANT_DUAL: Set<String> = setOf(
        "goon", "gooning", "goonin", "edging", "thicc", "thicce", "phat",
        "hoe", "hoes", "buns", "melons", "smash",
    )

    // ── WORD FAMILIES ─────────────────────────────────────────────────────────────────
    // Inflections of the same word are ONE signal, not several. Caps (PER_WORD_CAP and
    // SINGLE_WORD_MAX) and any breakdown work per FAMILY.
    private val FAMILY_GROUPS: List<List<String>> = listOf(
        listOf("nude", "nudes", "nudity"),
        listOf("boob", "boobs", "boobies"),
        listOf("tits", "titties", "titty"),
        listOf("slut", "sluts", "slutty"),
        listOf("whore", "whores"),
        listOf("pussy", "pussies"),
        listOf("cock", "cocks"),
        listOf("cum", "cumming"),
        listOf("nipple", "nipples"),
        listOf("wank", "wanking"),
        listOf("vagina", "vaginal"),
        listOf("dildo", "dildos"),
        listOf("orgasm", "orgasms"),
        listOf("sextoy", "sextoys"),
        listOf("masturbate", "masturbating", "masturbation"),
        listOf("porn", "porno", "pornographic", "pornography"),
        listOf("breast", "breasts"),
        listOf("buttock", "buttocks"),
        listOf("thong", "thongs"),
        listOf("hottie", "hotties"),
        listOf("bikini", "bikinis"),
    )
    private val FAMILY: Map<String, String> =
        HashMap<String, String>().apply { for (g in FAMILY_GROUPS) for (w in g) put(w, g[0]) }

    /** The family head for a word ("nudes" → "nude"), or the word itself if it has no family. */
    fun famOf(w: String): String = FAMILY[w] ?: w
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
    val MAP: Map<String, Set<String>> = mapOf(
        "naked" to setOf("mole", "rat", "rats", "eye", "eyes", "truth", "gun", "singularity",
            "ape", "chef", "lunch", "bike", "cowboy", "flame", "dna", "cell", "cells", "juice",
            // finance ("naked options/shorts") and tech ("naked domain")
            "option", "options", "short", "shorts", "shorting", "put", "puts", "call",
            "calls", "domain", "domains", "objects", "wines", "statistics", "economics"),
        "nude" to setOf("colour", "color", "coloured", "colored", "colours", "colors", "shade",
            "shades", "lipstick", "lipsticks", "makeup", "palette", "palettes", "heels",
            "pumps", "tone", "tones", "beige", "nail", "nails", "polish", "blush",
            "foundation", "eyeshadow", "lip", "lips", "gloss", "matte",
            // art history and lab animals
            "painting", "paintings", "portrait", "portraits", "art", "arts", "artist",
            "artists", "sculpture", "sculptures", "figure", "figures", "drawing",
            "drawings", "sketch", "sketches", "study", "studies", "gallery", "museum",
            "exhibition", "renaissance", "mice", "mouse", "descending"),
        "topless" to setOf("car", "cars", "convertible", "jeep", "roadster"),
        "sex" to setOf("same", "opposite", "education", "gender", "ratio", "chromosome",
            "offender", "offenders", "trafficking", "discrimination", "assault", "abuse",
            "crime", "crimes", "determination", "cells", "cell",
            "ed", "biology", "reproduction", "characteristics", "hormone", "hormones",
            "differences", "worker", "workers", "work"),
        "hardcore" to setOf("punk", "gaming", "gamer", "gamers", "band", "music", "metal",
            "techno", "rave", "fan", "fans", "mode", "difficulty", "minecraft", "parkour",
            "speedrun", "level"),
        "nsfw" to setOf("tag", "tags", "filter", "filters", "toggle", "setting", "settings",
            "label", "labels", "subreddit", "flair", "blur", "blurred", "hidden", "policy"),
        "bareback" to setOf("riding", "rider", "horse", "horses", "rodeo", "bronc"),
        "cock" to setOf("rooster", "hen", "gun", "pistol", "hammer", "crow", "crowed", "crows",
            "bird", "birds", "tail", "fight", "fighting", "weather"),
        "pussy" to setOf("cat", "cats", "willow", "willows", "kitten", "kittens", "feline", "riot"),
        "tits" to setOf("bird", "birds", "blue", "great", "coal", "marsh", "crested", "nest",
            "feeder", "garden", "woodland"),
        "anal" to setOf("retentive",
            // medicine, vets and fish anatomy
            "fissure", "fissures", "fistula", "cancer", "gland", "glands", "sac",
            "abscess", "itching", "fin", "fins", "stage"),
        "erection" to setOf("building", "buildings", "construction", "scaffold", "scaffolding",
            "steel", "tent", "mast", "crane", "structure", "statue"),
        "horny" to setOf("toad", "toads", "lizard", "frog", "owl", "coral", "beetle", "skin", "layer"),
        "orgy" to setOf("violence", "destruction", "spending", "self"),
        "semen" to setOf("analysis", "sample", "bovine", "bull", "stallion", "cattle", "breeding",
            "quality", "count"),
        // medical / health contexts
        "vagina" to setOf("health", "doctor", "doctors", "medical", "medicine", "infection",
            "infections", "birth", "delivery", "discharge", "exam", "examination",
            "cancer", "yeast", "bacterial", "thrush", "dryness", "ph", "microbiome",
            "symptom", "symptoms", "treatment", "clinic", "gynecologist",
            "gynaecologist", "obstetric", "smear", "swab", "ultrasound", "anatomy",
            "tissue", "prolapse", "mesh"),
        "penis" to setOf("health", "medical", "doctor", "doctors", "cancer", "anatomy",
            "circumcision", "urology", "urologist", "envy", "fracture", "curvature"),
        // more genital anatomy — overwhelmingly medical/educational when it appears at all
        "vulva" to setOf("health", "medical", "doctor", "doctors", "anatomy", "cancer",
            "vulvar", "vulvodynia", "itching", "itch", "pain", "swelling", "swollen", "sore",
            "lichen", "sclerosus", "cyst", "dermatology", "exam", "examination", "biopsy",
            "symptom", "symptoms", "treatment", "clinic", "gynecologist", "gynaecologist"),
        "clitoris" to setOf("health", "medical", "anatomy", "clitoral", "doctor", "hood",
            "swelling", "pain", "development", "surgery", "reduction", "phimosis"),
        "clitoral" to setOf("health", "medical", "anatomy", "hood", "surgery", "swelling", "pain"),
        "labia" to setOf("health", "medical", "anatomy", "majora", "minora", "swelling",
            "swollen", "cyst", "surgery", "labiaplasty", "reduction", "pain", "sore",
            "asymmetry", "tear", "doctor", "gynecologist", "gynaecologist"),
        "foreskin" to setOf("retraction", "retract", "tight", "phimosis", "paraphimosis",
            "circumcision", "hygiene", "health", "swelling", "smegma", "balanitis", "care"),
        "ejaculate" to setOf("premature", "treatment", "delayed", "delay", "retrograde",
            "unable", "cannot", "difficulty"),
        "vaginal" to setOf("health", "discharge", "infection", "dryness", "birth", "delivery",
            "mesh", "prolapse", "thrush", "bleeding", "swab", "examination", "atrophy"),
        "breast" to setOf("cancer", "feeding", "feed", "fed", "milk", "pump", "pumping", "chicken",
            "screening", "mammogram", "stroke", "reduction", "augmentation", "tissue",
            "exam", "lump", "lumps", "biopsy", "implant", "implants", "density"),
        "nipple" to setOf("baby", "bottle", "bottles", "confusion", "shield", "shields", "cream",
            "chafing", "discharge", "inverted", "cracked", "breastfeeding", "sore"),
        "ejaculation" to setOf("premature", "treatment", "delayed", "delay", "retrograde"),
        // discourse / news / help contexts
        "whore" to setOf("attention", "babylon"),
        "slut" to setOf("shaming", "shame", "shamed", "walk"),
        "cum" to setOf("laude", "summa", "magna"),
        "porn" to setOf("addiction", "addicted", "quit", "quitting", "blocker", "blockers",
            "block", "blocking", "blocks", "filter", "filters", "ban", "bans", "banned", "law",
            "laws", "bill", "age", "verification", "revenge"),
        "masturbate" to setOf("quit", "quitting", "addiction", "stop", "stopping", "health",
            "effects", "myth", "myths"),
        // tech
        "penetration" to setOf("testing", "test", "tests", "tester", "testers", "pen", "market",
            "depth", "armor", "armour", "network"),
        "boob" to setOf("tube"),
    )
}


// ── Who is being sexualised (gendered switches) ───────────────────────────────────────
// Two switches (see AttractionFilter) let someone turn DOWN the sexualised-women or the
// sexualised-men side of the filter. Default: both fully on. Only the SOFT, suggestive
// words/phrases below are affected — anything CORE keeps full weight no matter what.
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


// ── Medical / clinical context (whole-page damper) ────────────────────────────────────
// If any of these appear on the page, the sexual score is heavily damped (MEDICAL_DAMPEN).
// A damper, not an exemption: a porn page that says "doctor" doesn't get a free pass, it
// just needs more evidence. (The per-word EXCEPTIONS above are the finer-grained veto; this
// is the broad safety net for a page that is medical top-to-bottom.)
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
        // broadened so a page that is medical/educational top-to-bottom is recognised as such
        "gynaecology", "gynecology", "urology", "dermatology", "obstetrics", "obstetric",
        "genital", "genitals", "genitalia", "reproductive", "sexually", "vulvar", "vulvodynia",
        "labiaplasty", "circumcision", "phimosis", "balanitis", "wellbeing", "condition",
        "conditions", "disorder", "disorders", "syndrome", "inflammation", "inflamed",
        "diagnostic", "clinician", "patient", "patients", "nhs.uk", "healthline", "webmd",
        "mayo", "planned", "parenthood",
    )
}


// ── Phrases: word ORDER carries the meaning ───────────────────────────────────────────
// The word tiers only see ONE word at a time, which is why "try on haul", "nip slip" and
// "no leggings" sail through — none is bannable alone. Phrases match against a normalised
// copy of the page, so punctuation and hyphens don't matter ("Try-On Haul!!" → "try on haul").
//
// LOUD = the phrase itself is the giveaway; both modes; treated like CORE (blocks in a title).
// SOFT = leans adult but has a real innocent life (a genuine fashion haul); strict-only; weak.
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
