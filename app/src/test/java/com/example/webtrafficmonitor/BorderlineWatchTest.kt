package com.example.webtrafficmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The leaky bucket behind "this app keeps almost blocking".
 *
 * The property that matters is not "does it eventually fire" - anything counting upwards
 * does that. It is that a run of borderline screens fires it and an ordinary mixed session
 * does NOT, because this rule acts on something that never crossed the line, and a false
 * positive here takes an app away for an hour on a suspicion.
 *
 * SAMPLE_MS is the awkward part: the real clock would make this test three minutes long, so
 * these drive it through a fake one.
 */
class BorderlineWatchTest {

    /** Feed [n] readings for a fresh package, one per sample tick, and report the actions. */
    private fun run(pkg: String, readings: List<Boolean>): List<BorderlineWatch.Action> {
        BorderlineWatch.clear(pkg)
        var t = 1_000_000L
        return readings.map { borderline ->
            t += BorderlineWatch.SAMPLE_MS
            BorderlineWatch.recordAt(pkg, borderline, t)
        }
    }

    @Test
    fun `a sustained run of borderline screens warns and then blocks`() {
        val actions = run("app.sustained", List(BorderlineWatch.BLOCK_AT) { true })
        assertTrue(
            "a solid run must produce a warning",
            actions.contains(BorderlineWatch.Action.WARN),
        )
        assertEquals(
            "and the block must land on the BLOCK_AT-th reading",
            BorderlineWatch.Action.BLOCK, actions.last(),
        )
        // The warning has to come first, and well before the block.
        assertTrue(actions.indexOf(BorderlineWatch.Action.WARN) < actions.lastIndex)
    }

    @Test
    fun `an ordinary mixed session never fires`() {
        // One borderline screen in every three - a normal feed with the occasional bad frame.
        // Draining must hold this below the line indefinitely.
        val mixed = (1..120).map { it % 3 == 0 }
        val actions = run("app.mixed", mixed)
        assertTrue(
            "a mixed session must never block (got ${actions.filter { it != BorderlineWatch.Action.NONE }})",
            actions.none { it == BorderlineWatch.Action.BLOCK },
        )
    }

    @Test
    fun `a clean stretch undoes a bad one`() {
        // Right up to the edge of the warning...
        val actions = run("app.recovered", List(BorderlineWatch.WARN_AT - 1) { true })
        assertTrue(actions.none { it != BorderlineWatch.Action.NONE })
        // ...then a clean stretch, which must drain it back to nothing.
        var t = 1_000_000L + BorderlineWatch.SAMPLE_MS * BorderlineWatch.WARN_AT
        repeat(BorderlineWatch.WARN_AT) {
            t += BorderlineWatch.SAMPLE_MS
            BorderlineWatch.recordAt("app.recovered", false, t)
        }
        assertEquals("the bucket must be empty again", 0, BorderlineWatch.pressure("app.recovered"))
    }

    @Test
    fun `one busy second is one reading, not fifty`() {
        BorderlineWatch.clear("app.burst")
        val t = 2_000_000L
        repeat(50) { BorderlineWatch.recordAt("app.burst", true, t + it) }
        assertEquals("a burst of events about one screen counts once", 1,
            BorderlineWatch.pressure("app.burst"))
    }

    @Test
    fun `readings older than the window stop counting`() {
        BorderlineWatch.clear("app.stale")
        var t = 3_000_000L
        repeat(BorderlineWatch.WARN_AT - 1) {
            t += BorderlineWatch.SAMPLE_MS
            BorderlineWatch.recordAt("app.stale", true, t)
        }
        // Come back much later: everything on the books has aged out.
        t += BorderlineWatch.WINDOW_MS * 2
        assertEquals(BorderlineWatch.Action.NONE, BorderlineWatch.recordAt("app.stale", true, t))
        assertEquals("only the new reading survives", 1, BorderlineWatch.pressure("app.stale"))
    }
}
