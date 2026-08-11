package com.example.webtrafficmonitor

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The ladder between "a word was detected" and "the app is gone".
 *
 * The property that matters is the one the rule exists for: a single word - one question
 * typed, one message received, one screen firing fifty accessibility events - must NEVER
 * close an app the user has had for a while, however loudly that one screen scores. What
 * must close it is the same thing happening again, after a wait, in the same app.
 *
 * The waits and windows are tens of seconds and tens of minutes, so these drive the object
 * through a fake clock rather than sitting through them.
 */
class RepeatGateTest {

    private val known = AppTrust.Tier.KNOWN
    private val repeat = AppTrust.Tier.REPEAT
    private val new = AppTrust.Tier.NEW

    @Before
    fun reset() = RepeatGate.clearAll()

    @Test
    fun `one detection on a known app blocks nothing`() {
        assertEquals(
            RepeatGate.Verdict.HOLD,
            RepeatGate.recordAt("app.known", known, 1_000_000L),
        )
    }

    @Test
    fun `a screen firing fifty events is still one detection`() {
        var t = 1_000_000L
        val verdicts = (1..50).map { RepeatGate.recordAt("app.burst", known, t + it * 100L) }
        // Everything after the first lands inside the wait, so nothing is counted...
        verdicts.forEach { assertEquals(RepeatGate.Verdict.HOLD, it) }
        assertEquals("only the first detection counted", 1, RepeatGate.hits("app.burst"))
        // ...and the app is still a rung away from being closed once the wait ends.
        t += RepeatGate.WAIT_FIRST_MS + 1
        assertEquals(RepeatGate.Verdict.HOLD, RepeatGate.recordAt("app.burst", known, t))
    }

    @Test
    fun `three spaced detections close a known app`() {
        var t = 1_000_000L
        assertEquals(RepeatGate.Verdict.HOLD, RepeatGate.recordAt("app.three", known, t))
        t += RepeatGate.WAIT_FIRST_MS + 1
        assertEquals(RepeatGate.Verdict.HOLD, RepeatGate.recordAt("app.three", known, t))
        t += RepeatGate.WAIT_SECOND_MS + 1
        assertEquals(RepeatGate.Verdict.BLOCK, RepeatGate.recordAt("app.three", known, t))
        assertEquals("a block closes the case", 0, RepeatGate.hits("app.three"))
    }

    @Test
    fun `the second wait is the short one`() {
        var t = 1_000_000L
        RepeatGate.recordAt("app.spacing", known, t)
        t += RepeatGate.WAIT_FIRST_MS + 1
        RepeatGate.recordAt("app.spacing", known, t)
        // Inside the second wait: ignored, so the third rung is not reached yet.
        t += RepeatGate.WAIT_SECOND_MS - 1_000
        assertEquals(RepeatGate.Verdict.HOLD, RepeatGate.recordAt("app.spacing", known, t))
        assertEquals(2, RepeatGate.hits("app.spacing"))
        t += 2_000
        assertEquals(RepeatGate.Verdict.BLOCK, RepeatGate.recordAt("app.spacing", known, t))
    }

    @Test
    fun `an app we have closed before gets two rungs, not three`() {
        var t = 1_000_000L
        assertEquals(RepeatGate.Verdict.HOLD, RepeatGate.recordAt("app.again", repeat, t))
        t += RepeatGate.WAIT_SECOND_MS + 1
        assertEquals(RepeatGate.Verdict.BLOCK, RepeatGate.recordAt("app.again", repeat, t))
    }

    @Test
    fun `a new app is closed on the first detection`() {
        assertEquals(
            RepeatGate.Verdict.BLOCK,
            RepeatGate.recordAt("app.fresh", new, 1_000_000L),
        )
    }

    @Test
    fun `a confirmed block stands instead of being re-decided`() {
        var t = 1_000_000L
        assertEquals(RepeatGate.Verdict.BLOCK, RepeatGate.recordAt("app.stands", new, t))
        // The very next event from the same screen must not take the cover down again.
        t += 200
        assertEquals(RepeatGate.Verdict.HELD, RepeatGate.recordAt("app.stands", new, t))
        // ...and it keeps standing for as long as detections keep arriving.
        t += RepeatGate.BLOCK_HOLD_MS - 1_000
        assertEquals(RepeatGate.Verdict.HELD, RepeatGate.recordAt("app.stands", new, t))
        // Once they stop for the whole hold, the block lapses and counting starts over.
        t += RepeatGate.BLOCK_HOLD_MS + 1
        assertEquals(RepeatGate.Verdict.HOLD, RepeatGate.recordAt("app.stands", known, t))
        assertEquals(1, RepeatGate.hits("app.stands"))
    }

    @Test
    fun `a case that is never confirmed is dropped`() {
        var t = 1_000_000L
        RepeatGate.recordAt("app.lapsed", known, t)
        assertEquals(1, RepeatGate.hits("app.lapsed"))
        // Come back after the case window: the first word was just a word.
        t += RepeatGate.CASE_MS + 1
        assertEquals(RepeatGate.Verdict.HOLD, RepeatGate.recordAt("app.lapsed", known, t))
        assertEquals("counting starts again from one", 1, RepeatGate.hits("app.lapsed"))
    }

    @Test
    fun `an hour with nothing detected anywhere wipes every case`() {
        var t = 1_000_000L
        RepeatGate.recordAt("app.one", known, t)
        t += RepeatGate.WAIT_FIRST_MS + 1
        RepeatGate.recordAt("app.one", known, t)
        RepeatGate.recordAt("app.two", known, t)
        assertEquals(2, RepeatGate.hits("app.one"))

        t += RepeatGate.QUIET_RESET_MS + 1
        // The detection that ends the quiet hour is itself the first rung of a fresh case.
        assertEquals(RepeatGate.Verdict.HOLD, RepeatGate.recordAt("app.one", known, t))
        assertEquals(1, RepeatGate.hits("app.one"))
        assertEquals("the other app's case went too", 0, RepeatGate.hits("app.two"))
    }

    @Test
    fun `an ignored detection does not keep the case alive`() {
        val start = 1_000_000L
        RepeatGate.recordAt("app.dwell", known, start)                       // counted
        RepeatGate.recordAt("app.dwell", known, start + RepeatGate.WAIT_FIRST_MS / 2)  // ignored
        // The case ages from the COUNTED hit. Sitting on a screen that keeps scoring inside
        // the wait cannot hold a case open past its window.
        val later = start + RepeatGate.CASE_MS + 1
        assertEquals(RepeatGate.Verdict.HOLD, RepeatGate.recordAt("app.dwell", known, later))
        assertEquals(1, RepeatGate.hits("app.dwell"))
    }

    @Test
    fun `apps are counted separately`() {
        var t = 1_000_000L
        RepeatGate.recordAt("app.a", known, t)
        RepeatGate.recordAt("app.b", known, t)
        t += RepeatGate.WAIT_FIRST_MS + 1
        RepeatGate.recordAt("app.a", known, t)
        assertEquals(2, RepeatGate.hits("app.a"))
        assertEquals("app B's own case is untouched", 1, RepeatGate.hits("app.b"))
    }
}
