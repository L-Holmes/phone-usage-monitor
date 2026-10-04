package com.example.webtrafficmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reddit's own handles. One is a mention; several DIFFERENT ones on one screen is Reddit.
 * Every negative case here is something an ordinary screen really says.
 */
class RedditMarksTest {

    @Test
    fun `a feed of subreddits and users is reddit`() {
        val feed = """
            r/pics • Posted by u/someone_123 • 5h
            What a view
            r/AskReddit • Posted by u/Another-One • 2h
        """.trimIndent()
        assertEquals(
            setOf("r/pics", "u/someone_123", "r/askreddit", "u/another-one"),
            RedditMarks.find(feed),
        )
        assertNotNull(RedditMarks.onReddit(null, feed))
    }

    @Test
    fun `the old slash-first style counts`() {
        assertNotNull(RedditMarks.onReddit("/r/pics", "see /r/aww and /u/spez"))
    }

    @Test
    fun `one or two marks are a mention, not a feed`() {
        assertNull(RedditMarks.onReddit(null, "The thread on r/news blew up, says u/spez."))
        assertNull(RedditMarks.onReddit("r/pics", "r/pics r/pics r/pics - one sub, fifty times"))
    }

    @Test
    fun `things that look like marks but are not`() {
        for (text in listOf(
            "max 3000 r/min, idle 800 r/min, redline 7000 r/mins",   // revolutions per minute
            "ALT 40 U/L, AST 35 U/L, insulin 5 IU/mL, 2 U/kg",         // lab units
            "w/r/t the r/c car and the u/s scan",                       // short abbreviations
            "https://mail.google.com/mail/u/0/#inbox and example.com/r/pics/comments/abc",
            "reddit.com/r/pics reddit.com/r/aww reddit.com/r/funny",    // a URL path is not a label
            "and/or either/or his/her r/2 u/3",
        )) {
            assertEquals("'$text' must carry no marks", emptySet<String>(), RedditMarks.find(text))
        }
    }

    @Test
    fun `names have reddit's own length limits`() {
        assertEquals(setOf("r/abc"), RedditMarks.find("r/abc"))
        assertEquals(emptySet<String>(), RedditMarks.find("r/ab"))
        assertEquals(emptySet<String>(), RedditMarks.find("r/" + "a".repeat(22)))
    }
}
