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
}
