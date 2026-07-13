package com.example.webtrafficmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * You must never be able to ban a search engine out from under yourself.
 *
 * Banning "google.com" (or its bare results path) blocks every future search on it - including
 * the search you'd need to work out how to undo it. A SPECIFIC search term still bans fine;
 * only the engine itself is protected.
 */
class BlockRulesProtectionTest {

    @Test
    fun `search engines themselves cannot be banned`() {
        listOf(
            "google.com",
            "www.google.com",
            "google.co.uk",
            "duckduckgo.com",
            "ecosia.org",
            "google.com/search",          // the bare results path is just as fatal
            "google.co.uk/search",
            "youtube.com/results",
        ).forEach {
            assertTrue("'$it' must be protected from being blocked", BlockRules.isProtected(it))
        }
    }

    @Test
    fun `a specific search term is still blockable`() {
        listOf(
            "google.com/search?q=porn",
            "duckduckgo.com/?q=porn",
            "youtube.com/results?search_query=porn",
        ).forEach {
            assertFalse("'$it' should still be blockable", BlockRules.isProtected(it))
        }
    }

    @Test
    fun `ordinary sites and pages are still blockable`() {
        listOf(
            "pornhub.com",
            "reddit.com",
            "reddit.com/r/nsfw",
            "google.com/maps",            // a non-search page on the same domain: fair game
        ).forEach {
            assertFalse("'$it' should still be blockable", BlockRules.isProtected(it))
        }
    }
}
