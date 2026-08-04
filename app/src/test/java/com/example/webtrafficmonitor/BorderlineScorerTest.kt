package com.example.webtrafficmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The content filter, tested on the JVM (BorderlineScorer is pure Kotlin - no Android).
 *
 * Two jobs, and the second one matters just as much as the first:
 *   1. the evasions and phrases we promised to catch DO block;
 *   2. ordinary pages DON'T. Every entry in the "must not block" list below is a real
 *      false-positive risk from the word lists. If you add a word or phrase and one of
 *      these starts failing, the word is too broad - fix the word, not the test.
 */
class BorderlineScorerTest {

    private fun blocksAsTitle(s: String) = BorderlineScorer.evaluate(s, null, null) != null
    private fun scoreAsTitle(s: String) = BorderlineScorer.score(s, null, null)?.score ?: 0

    @Test
    fun `shortenings and evasion spellings are caught`() {
        listOf(
            "nake", "nekked", "nekkid", "nudez", "noods",
            "seggs", "fap", "coomer", "thot", "bewbs", "hentia", "onlyfanz",
        ).forEach {
            assertTrue("expected '$it' to block as a title", blocksAsTitle(it))
        }
    }

    @Test
    fun `leetspeak and stretched letters normalise onto the real word`() {
        listOf("p0rn", "pr0n", "s3x", "b00bs", "pooorn", "seeexy", "PORN").forEach {
            assertTrue("expected '$it' to score", scoreAsTitle(it) > 0)
        }
        // ...and the loudest of them block outright.
        assertTrue(blocksAsTitle("p0rn"))
        assertTrue(blocksAsTitle("pooorn"))
    }

    @Test
    fun `phrases are caught - none of these words are bannable alone`() {
        listOf(
            "try on haul", "nip slip", "no pants", "no leggings", "no bra",
            "see through top", "wardrobe malfunction", "bikini haul", "leaked nudes",
            "nothing underneath", "camel toe", "thirst trap",
        ).forEach {
            assertTrue("expected phrase '$it' to block as a title", blocksAsTitle(it))
        }
    }

    @Test
    fun `phrases still match through punctuation and casing`() {
        assertTrue(blocksAsTitle("Try-On HAUL!! (new)"))
        assertTrue(blocksAsTitle("nip-slip"))
    }

    @Test
    fun `ordinary pages are not blocked`() {
        listOf(
            "hello world",
            "the rules of chess",
            "hot chocolate recipe",
            "python list comprehension",
            "how to edge a lawn properly",
            "chicken breast recipe",
            "gmail inbox",
            "bank of scotland login",
            "premier league results",
            "wikipedia - the free encyclopedia",
            "how to tie a tie",
            "weather forecast tomorrow",
        ).forEach {
            assertTrue(
                "'$it' must NOT block (scored ${scoreAsTitle(it)})",
                !blocksAsTitle(it),
            )
        }
    }

    @Test
    fun `context-gated words score nothing on their own`() {
        // "goon" / "thicc" / "hoe" are DUAL: they need a sexual neighbour to count.
        assertEquals(0, scoreAsTitle("garden hoe"))
        assertEquals(0, scoreAsTitle("the goon squad"))
    }

    // ── Medical context ──────────────────────────────────────────────────────────────

    @Test
    fun `looking up a symptom is not blocked`() {
        listOf(
            "vaginal discharge - symptoms and treatment",
            "breast lump when should I see a doctor",
            "testicular pain causes nhs",
            "painful periods treatment",
            "erectile dysfunction symptoms",
            "cervical screening smear test",
        ).forEach {
            assertTrue(
                "medical query '$it' must NOT block (scored ${scoreAsTitle(it)})",
                !blocksAsTitle(it),
            )
        }
    }

    @Test
    fun `medical context is a damper not a free pass`() {
        // A porn page that happens to say "doctor" is still porn.
        assertTrue(blocksAsTitle("free porn videos xxx doctor roleplay blowjob"))
    }

    // ── The attraction switches ──────────────────────────────────────────────────────

    private val womenOff = BorderlineScorer.Settings(blockFemale = false, blockMale = true, relaxed = false)
    private val menOff = BorderlineScorer.Settings(blockFemale = true, blockMale = false, relaxed = false)

    @Test
    fun `turning the women switch off lets swimwear and lingerie through`() {
        listOf("bikini haul", "lingerie try on", "best bra for summer", "swimsuit shopping").forEach {
            assertTrue(
                "'$it' should pass with women's filter off (scored " +
                    "${BorderlineScorer.score(it, null, null, womenOff)?.score ?: 0})",
                BorderlineScorer.evaluate(it, null, null, womenOff) == null,
            )
        }
        // ...but with it ON (the default), the loud ones still block.
        assertTrue(blocksAsTitle("bikini haul"))
    }

    @Test
    fun `an explicit signal damped by a gender switch does not force a block`() {
        // "one explicit signal always blocks" must not quietly override the gender switches.
        // "bikini haul" is a LOUD phrase, so it IS explicit-tier - but with the sexualised-
        // women filter turned down it is worth a quarter, and a quarter of a signal is not
        // the unmistakable thing that rule is about. (Regression: it blocked here once.)
        assertTrue("a damped loud phrase must not block on the explicit rule",
            BorderlineScorer.evaluate("bikini haul", null, null, womenOff) == null)
        // ...while an UNDAMPED explicit signal in the same sentence still does.
        assertTrue(BorderlineScorer.evaluate("bikini haul porn", null, null, womenOff) != null)
    }

    @Test
    fun `switching a side off can NEVER unblock explicit porn`() {
        // This is the whole safety property of the feature. If this test ever fails, the
        // switches have become an escape hatch and the feature is broken.
        listOf(
            "free porn videos", "blowjob compilation", "milf onlyfans leak",
            "hardcore anal xxx", "pornhub", "nude girls fucking",
        ).forEach {
            assertTrue("'$it' must still block with women's filter off",
                BorderlineScorer.evaluate(it, null, null, womenOff) != null)
            assertTrue("'$it' must still block with men's filter off",
                BorderlineScorer.evaluate(it, null, null, menOff) != null)
            assertTrue("'$it' must still block with both off",
                BorderlineScorer.evaluate(
                    it, null, null, BorderlineScorer.Settings(false, false, relaxed = false),
                ) != null)
        }
    }

    // ── In-app screens: a tighter bar, same safety property ──────────────────────────

    private fun blocksInApp(s: String) = BorderlineScorer.evaluateInApp(null, null, s) != null
    private fun scoreInBody(s: String) = BorderlineScorer.score(null, null, s)?.score ?: 0

    @Test
    fun `the in-app bar is genuinely tighter than the web bar`() {
        // Body text (no title multiplier) that lands in the 11..14 band: it is not enough
        // for a web page, and IS enough inside an app. This is the whole point of the
        // separate threshold - if these two ever agree, APP_THRESHOLD has stopped doing
        // anything and the change has been silently undone.
        assertTrue(
            "APP_THRESHOLD must sit below THRESHOLD",
            FilterTuning.APP_THRESHOLD < FilterTuning.THRESHOLD,
        )
        val band = "nude sexy babe"
        val score = scoreInBody(band)
        assertTrue(
            "'$band' should land in the in-app band, scored $score",
            score in FilterTuning.APP_THRESHOLD until FilterTuning.THRESHOLD,
        )
        assertTrue("'$band' must not block as a web page", BorderlineScorer.evaluate(null, null, band) == null)
        assertTrue("'$band' must block inside an app", blocksInApp(band))
    }

    @Test
    fun `one word still cannot block an app screen on its own`() {
        // The single-word guarantee is what keeps "nude lipstick" and "naked eye" out of
        // the block list. On the web it falls out of the arithmetic (SINGLE_WORD_MAX is
        // THRESHOLD-1); at the lower in-app bar it has to be enforced by the distinct-family
        // rule instead, so it needs its own test.
        listOf("nude", "nude nude nude nude nude", "sexy", "sexy sexy sexy sexy sexy").forEach {
            assertTrue(
                "one word ('$it', scored ${scoreInBody(it)}) must not block an app screen",
                !blocksInApp(it),
            )
        }
    }

    @Test
    fun `ordinary app screens are not blocked`() {
        listOf(
            "Messages  Mum  see you at 6  Dad  ok  Sam  running late",
            "Maps  turn left onto High Street in 200 metres",
            "Settings  Battery  Display  Sound  Notifications  Storage",
            "Calendar  Tuesday  dentist 9:00  standup 10:00  gym 18:00",
            "Photos  Camera Roll  Screenshots  Favourites  Recently deleted",
            "chicken breast recipe  preheat the oven  season the breast  rest for 5 minutes",
        ).forEach {
            assertTrue(
                "app screen '$it' must NOT block (scored ${scoreInBody(it)})",
                !blocksInApp(it),
            )
        }
    }

    @Test
    fun `anything that blocks on the web still blocks in an app`() {
        // The in-app bar is strictly tighter, so this must hold for every case above.
        listOf(
            "free porn videos", "blowjob compilation", "hardcore anal xxx",
            "leaked nudes", "try on haul", "p0rn",
        ).forEach {
            assertTrue("'$it' blocks on the web, so it must block in an app", blocksInApp(it))
        }
    }

    // ── Mode-gated FRAGMENTS (ModeFragments) ─────────────────────────────────────────
    // These are blunt SUBSTRING matches, which is why they used to be a disaster: as an
    // outright block they covered ordinary apps for saying "browser" and caught "necklace"
    // on "lace". They are scored now, so the tests that matter are the ones below.

    private val superHardcore =
        BorderlineScorer.Settings(blockFemale = true, blockMale = true, relaxed = false, superHardcore = true)

    @Test
    fun `a soft fragment can never block on its own`() {
        // Every one of these was an OUTRIGHT block before 2026-08-04. A substring is not
        // evidence: "lace" is in "necklace", "haul" is in "overhaul", every UI toolkit has
        // a "scroller". If one of these starts failing, a fragment's weight is too high.
        listOf(
            "handmade lace necklace",
            "fireplace tiles",
            "a complete overhaul of the tax system",
            "u haul truck rental",
            "custom scroller component react",
            "sheer curtains for the living room",
        ).forEach {
            assertTrue(
                "'$it' must NOT block on a fragment alone (scored ${scoreAsTitle(it)})",
                !blocksAsTitle(it),
            )
        }
    }

    @Test
    fun `browser is no longer a banned word at all`() {
        // The report that started this: ordinary apps covered because the word "browser"
        // was on screen. It is generic vocabulary and must score NOTHING.
        assertEquals(0, scoreAsTitle("open in browser"))
        assertEquals(0, scoreAsTitle("browser settings"))
    }

    @Test
    fun `hard fragments still block on their own`() {
        // The site names are unmistakable spellings; they keep CORE-tier behaviour.
        assertTrue("scrolller must still block", blocksAsTitle("scrolller"))
        // Reddit stays Super-hardcore-only, exactly as before.
        assertTrue(
            "reddit must block in super hardcore",
            BorderlineScorer.evaluate("red dit pics", null, null, superHardcore) != null,
        )
        assertTrue(
            "reddit must NOT block below super hardcore",
            BorderlineScorer.evaluate("red dit pics", null, null) == null,
        )
    }

    @Test
    fun `fragments still corroborate - several weak signals do block`() {
        // The point of scoring them rather than dropping them: one is nothing, a pile is a
        // page. None of these is bannable alone; together they are not a hardware shop.
        assertTrue(blocksAsTitle("lace lingerie bikini sheer"))
    }

    @Test
    fun `an evasion spelling is the same signal as the word, not a second one`() {
        // "bikini" and "bik ini" share a family, so the single-word guarantee still holds
        // across the two spellings - spacing a word out must not manufacture corroboration.
        assertTrue(
            "'bik ini' must not block alone (scored ${scoreAsTitle("bik ini")})",
            !blocksAsTitle("bik ini"),
        )
        assertTrue(
            "'bikini bik ini bikinis' must not block on one concept " +
                "(scored ${scoreAsTitle("bikini bik ini bikinis")})",
            !blocksAsTitle("bikini bik ini bikinis"),
        )
    }

    // ── Showing the working ──────────────────────────────────────────────────────────

    @Test
    fun `a block explains itself with the words that carried it`() {
        val r = BorderlineScorer.evaluate("lace lingerie bikini sheer", null, null)!!
        assertTrue("a block must come with contributions", r.contributions.isNotEmpty())
        // Biggest share first, and the shares add up to the score.
        assertEquals(r.contributions.sortedByDescending { it.points }, r.contributions)
        assertEquals(r.score, r.contributions.sumOf { it.points })
        // The words are readable, not internal keys ("phrase:"/family prefixes stripped).
        assertTrue(r.contributions.none { it.word.contains(':') })
        val shown = BorderlineScorer.topContributors(r.contributions)
        assertTrue("something must be worth showing", shown.isNotEmpty())
        assertEquals("lingerie", shown.first().word)
    }

    @Test
    fun `a phrase block names the phrase, not one of its words`() {
        val r = BorderlineScorer.evaluate("try on haul", null, null)!!
        assertTrue(
            "expected the phrase itself in ${r.contributions.map { it.word }}",
            r.contributions.any { it.word == "try on haul" },
        )
    }

    // ── Evasion: spelling it differently must not be a way through ───────────────────

    @Test
    fun `a word split across spaces is still that word`() {
        listOf("p o r n", "pr o n", "po rn", "c u m s h o t", "s e x t a p e").forEach {
            assertTrue("'$it' must block (scored ${scoreAsTitle(it)})", blocksAsTitle(it))
        }
    }

    @Test
    fun `joining short words does not invent hits in ordinary text`() {
        // The join pass only glues SHORT tokens and only accepts an EXACT banned word, so
        // ordinary English - which is mostly short words - must produce nothing.
        listOf(
            "i am up to no good",
            "it is on the way in",
            "a bit of it is out of my way",
            "we go to the car at ten to two",
        ).forEach {
            assertEquals("'$it' must score nothing", 0, scoreAsTitle(it))
        }
    }

    @Test
    fun `lookalike letters from other alphabets are folded`() {
        // Cyrillic "о" renders identically to Latin "o" and would otherwise split the token.
        assertTrue("Cyrillic o in porn must still block", blocksAsTitle("pоrn"))
        assertTrue("Cyrillic а in anal must still score", scoreAsTitle("аnal sex") > 0)
    }

    @Test
    fun `a doubled letter is caught outright, not merely flagged`() {
        // "pornn" / "blowjjob" collapse onto the real word, so they are full hits - which is
        // strictly better than being flagged. Assert the stronger outcome so nobody
        // "fixes" this into the weaker one.
        listOf("pornn", "blowjjob", "sexxtape").forEach {
            assertTrue("'$it' must block outright", blocksAsTitle(it))
        }
    }

    @Test
    fun `a banned word with a character wedged in is suspicious, not innocent`() {
        // A NEW letter (not a doubled one, which collapse already handles) is the commonest
        // way round a word list. These score a little and, more importantly, are COUNTED -
        // see BorderlineWatch.
        listOf("poarn", "pxorn", "blowzjob", "hentzai").forEach {
            assertTrue("'$it' should be flagged suspicious",
                BorderlineScorer.read(it, null, null).suspicious > 0)
        }
    }

    @Test
    fun `near-miss detection does not fire on ordinary words`() {
        // The test is one-directional on purpose: deleting a character from the TOKEN must
        // land on a banned word. The reverse would flag "um" off "cum" and "corn" off "porn".
        listOf(
            "corn", "born", "horn", "sports", "concert", "column", "custom",
            "analysis", "assistant", "class", "grass", "documents", "scunthorpe",
        ).forEach {
            assertEquals("'$it' must not be suspicious", 0,
                BorderlineScorer.read(it, null, null).suspicious)
        }
    }

    // ── In-app: how much text is on screen changes what one word means ───────────────

    @Test
    fun `one unmistakable word blocks a short app screen`() {
        assertTrue("a short screen saying it once must block", blocksInApp("porn"))
        assertTrue(blocksInApp("pron"))
    }

    @Test
    fun `the same word inside a wall of text needs corroboration`() {
        val filler = (1..FilterTuning.APP_LONG_TEXT_WORDS + 20).joinToString(" ") { "news" }
        assertTrue(
            "one mention in a long article must NOT block an app screen",
            !blocksInApp("$filler porn $filler"),
        )
        // ...but a long screen that is actually about it still blocks.
        assertTrue(
            "a long screen with real corroboration must still block",
            blocksInApp("$filler porn blowjob milf $filler"),
        )
    }

    // ── The 2026-08-04 word additions ────────────────────────────────────────────────

    @Test
    fun `the almost-always-bad additions block or nearly do`() {
        assertTrue("downblouse is a genre term, not a word with another life",
            blocksAsTitle("downblouse"))
        assertTrue("upskirt likewise", blocksAsTitle("upskirt"))
        assertTrue("'no clothes' is a loud phrase", blocksAsTitle("no clothes"))
    }

    @Test
    fun `the borderline additions never block on their own`() {
        // These were added as borderline ON PURPOSE. If one of them starts blocking alone,
        // it has been promoted into the wrong tier.
        listOf("pokies", "jiggle", "jiggling", "pjs", "pyjamas", "pajamas").forEach {
            assertTrue("'$it' must not block alone (scored ${scoreAsTitle(it)})", !blocksAsTitle(it))
        }
    }

    @Test
    fun `the innocent senses of the borderline additions are protected`() {
        // "pokies" is Australian for poker machines far more often than anything else, and
        // "jiggle" is an engineering word. Both must survive their own everyday use.
        // Note: an exception only reaches EXCEPTION_WINDOW words, so these assert "does not
        // block" where the innocent word is further off than that - the single-word ceiling
        // is what protects those, and it is the guarantee that actually matters.
        assertTrue("pokies must never block alone",
            !blocksAsTitle("the pokies paid out big at the local club last night"))
        listOf(
            "the pokies at the local club",
            "nsw pokies gambling reform",
            "jiggle physics in game engines",
            "jiggle the handle to fix a loose valve",
            "kids pajama party ideas",
            "matching family christmas pajamas",
        ).forEach {
            assertEquals("'$it' must score nothing", 0, scoreAsTitle(it))
        }
    }

    @Test
    fun `pjs only counts next to something sexual`() {
        // DUAL tier: ordinary on its own, evidence beside a sexual word.
        assertEquals(0, scoreAsTitle("comfy pjs for winter"))
        assertTrue("beside a sexual word it should count",
            scoreAsTitle("naked pjs strip") > scoreAsTitle("comfy pjs for winter"))
    }

    // ── The bar Firefox is held to when the add-on is also watching ──────────────────

    @Test
    fun `the web bar is flat, and one core signal always beats it`() {
        // Firefox is the only allowed browser and the add-on is mandatory in setup, so there
        // is no "is the plugin on today" branch - the web bar is simply WEB_THRESHOLD.
        assertEquals(FilterTuning.WEB_THRESHOLD, BorderlineScorer.webBar())
        assertTrue("the web bar must sit above the app bar",
            FilterTuning.WEB_THRESHOLD > FilterTuning.APP_THRESHOLD)
        assertTrue("no single word may reach the web bar",
            FilterTuning.SINGLE_WORD_MAX < FilterTuning.WEB_THRESHOLD)

        // ONE unmistakable signal blocks regardless of the score arithmetic. This is the
        // property that matters most: it must not be possible to dilute "porn" away.
        listOf("porn", "blowjob", "pornhub", "downblouse", "try on haul", "p o r n").forEach {
            assertTrue("'$it' must block a web page on its own", blocksAsTitle(it))
        }
        // ...even buried in a wall of ordinary text, where the score is a rounding error.
        val filler = (1..300).joinToString(" ") { "news" }
        assertTrue("one core word in a long page still blocks the WEB",
            BorderlineScorer.evaluate(null, null, "$filler porn $filler") != null)
    }

    @Test
    fun `a pile of soft words below the web bar does not block`() {
        // The counterpart: no Core word, so it is pure arithmetic - and it must fall short.
        val soft = "lace lingerie bikini"
        assertTrue("'$soft' (scored ${scoreAsTitle(soft)}) must stay under the web bar",
            !blocksAsTitle(soft))
        // Enough of them together, and it does block.
        val more = "lace lingerie bikini sheer"
        assertTrue("'$more' (scored ${scoreAsTitle(more)}) should clear it", blocksAsTitle(more))
    }

}
