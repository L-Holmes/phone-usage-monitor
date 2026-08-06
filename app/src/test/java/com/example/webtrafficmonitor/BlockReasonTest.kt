package com.example.webtrafficmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A block cover has to be able to say what a rule actually blocks.
 *
 * The rule that made this necessary is the search-term rule: it is stored as a URL
 * ("google.com/search?q=big+boobs"), and a user reading their own block cover should not
 * have to decode a query string to find out what they are being told off for.
 */
class BlockReasonTest {

    @Test
    fun `a search rule reads back as its term`() {
        assertEquals("big boobs", BlockRules.searchTermOf("google.com/search?q=big+boobs"))
        assertEquals("hot yoga", BlockRules.searchTermOf("duckduckgo.com/?q=hot%20yoga"))
        assertEquals("wolves", BlockRules.searchTermOf("youtube.com/results?search_query=wolves"))
    }

    @Test
    fun `everything that is not a search has no term`() {
        listOf(
            "reddit.com",
            "reddit.com/r/something",
            "porn",
            "google.com/search?q=",     // engine with an empty term
        ).forEach {
            assertNull("'$it' is not a search-term rule", BlockRules.searchTermOf(it))
        }
    }

    @Test
    fun `rules are classified by shape`() {
        assertEquals(BlockRules.Kind.SEARCH, BlockRules.kindOf("google.com/search?q=big+boobs"))
        assertEquals(BlockRules.Kind.PAGE, BlockRules.kindOf("reddit.com/r/something"))
        assertEquals(BlockRules.Kind.DOMAIN, BlockRules.kindOf("redgifs.com"))
        assertEquals(BlockRules.Kind.KEYWORD, BlockRules.kindOf("porn"))
    }
}
