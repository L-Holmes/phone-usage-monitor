package com.example.webtrafficmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing rule: a primer ("reddit", "live cam") and a partner ("girl", "sexy") in one
 * app inside PAIR_WINDOW_MS is a block; either one alone, for any length of time, is not.
 *
 * Driven through a fake clock. Every case here is a promise the block screen makes.
 */
class PrimerWatchTest {

    private val t0 = 1_000_000L
    private val min = 60_000L

    private fun fresh(pkg: String): String { PrimerWatch.clear(); return pkg }

    @Test
    fun `a primer alone never pairs, however long it stays`() {
        val app = fresh("app.reddit.alone")
        for (i in 0..30) {
            assertNull(PrimerWatch.noteAt(app, listOf("reddit"), emptyList(), t0 + i * 20_000L))
        }
        assertTrue("...but it primes the multiplier", PrimerWatch.pairing(app) == null)
    }

    @Test
    fun `a partner alone never pairs either`() {
        val app = fresh("app.girl.alone")
        for (i in 0..30) {
            assertNull(PrimerWatch.noteAt(app, emptyList(), listOf("girl"), t0 + i * 20_000L))
        }
    }

    @Test
    fun `reddit then girl three minutes later is a pair`() {
        val app = fresh("app.reddit.girl")
        assertNull(PrimerWatch.noteAt(app, listOf("reddit"), emptyList(), t0))
        val p = PrimerWatch.noteAt(app, emptyList(), listOf("girl"), t0 + 3 * min)
        assertTrue("must pair", p != null)
        assertEquals("reddit", p!!.first)
        assertEquals("girl", p.second)
        assertEquals(3 * min, p.gapMs)
        assertTrue("the first report is fresh", p.fresh)
        // The next event in the same app keeps the pair, but it is no longer new.
        val again = PrimerWatch.noteAt(app, emptyList(), emptyList(), t0 + 3 * min + 1_000L)
        assertTrue(again != null && !again.fresh)
    }

    @Test
    fun `girl then reddit pairs too - the order does not matter`() {
        val app = fresh("app.girl.reddit")
        assertNull(PrimerWatch.noteAt(app, emptyList(), listOf("girl"), t0))
        val p = PrimerWatch.noteAt(app, listOf("reddit"), emptyList(), t0 + 2 * min)
        assertTrue(p != null)
        assertEquals("girl", p!!.first)
        assertEquals("reddit", p.second)
    }

    @Test
    fun `both on one screen is a pair with no gap`() {
        val app = fresh("app.same.screen")
        val p = PrimerWatch.noteAt(app, listOf("live cam"), listOf("sexy"), t0)
        assertTrue(p != null)
        assertEquals(0L, p!!.gapMs)
    }

    @Test
    fun `outside the window they do not pair`() {
        val app = fresh("app.too.late")
        assertNull(PrimerWatch.noteAt(app, listOf("reddit"), emptyList(), t0))
        assertNull(
            "six minutes later is outside a five-minute window",
            PrimerWatch.noteAt(app, emptyList(), listOf("girl"), t0 + FilterTuning.PAIR_WINDOW_MS + min),
        )
    }

    @Test
    fun `a pair lapses once one half is stale`() {
        val app = fresh("app.lapse")
        PrimerWatch.noteAt(app, listOf("reddit"), listOf("girl"), t0)
        assertTrue(PrimerWatch.noteAt(app, emptyList(), emptyList(), t0 + 4 * min) != null)
        assertNull(PrimerWatch.noteAt(app, emptyList(), emptyList(), t0 + FilterTuning.PAIR_WINDOW_MS + 1_000L))
        // And when they meet again it is a NEW pairing, reported fresh.
        val p = PrimerWatch.noteAt(app, listOf("reddit"), listOf("girl"), t0 + 20 * min)
        assertTrue(p != null && p.fresh)
    }

    @Test
    fun `trails are per app`() {
        fresh("x")
        assertNull(PrimerWatch.noteAt("app.one", listOf("reddit"), emptyList(), t0))
        assertNull(
            "a partner in a different app is not this app's partner",
            PrimerWatch.noteAt("app.two", emptyList(), listOf("girl"), t0 + min),
        )
    }
}
