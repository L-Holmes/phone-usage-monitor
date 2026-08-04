package com.example.webtrafficmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SafeSearch enforcement. Google is the only search engine we allow, which is worth very
 * little if Google itself is running with SafeSearch off - image search in particular. This
 * pins both directions: the off-switch is caught, and ordinary searching is left alone.
 */
class SafeSearchTest {

    @Test
    fun `the safesearch off markers are caught`() {
        listOf(
            "https://www.google.com/search?q=x&safe=off",
            "https://google.co.uk/search?q=x&safe=images&tbm=isch",
            "https://www.google.com/search?q=x&safeui=off",
            "HTTPS://WWW.GOOGLE.COM/SEARCH?Q=X&SAFE=OFF",
        ).forEach {
            assertTrue("'$it' should read as SafeSearch off", SafeSearch.isExplicitlyOff(it))
        }
    }

    @Test
    fun `ordinary searching is not treated as a bypass`() {
        listOf(
            "https://www.google.com/search?q=how+to+tie+a+tie",
            "https://www.google.com/search?q=safety+boots",   // contains "safe", not "safe=off"
            "https://www.google.com/search?q=x&safe=active",
            null,
            "",
        ).forEach {
            assertFalse("'$it' must not read as SafeSearch off", SafeSearch.isExplicitlyOff(it))
        }
    }

    @Test
    fun `image search is recognised in each of its forms`() {
        listOf(
            "https://www.google.com/search?q=x&tbm=isch",
            "https://www.google.com/imghp",
            "https://www.google.com/search?q=x&udm=2",
        ).forEach {
            assertTrue("'$it' should read as image search", SafeSearch.isImageSearch(it))
        }
        assertFalse(SafeSearch.isImageSearch("https://www.google.com/search?q=x"))
    }

    @Test
    fun `only google is treated as the allowed engine`() {
        assertTrue(SafeSearch.isSearchHost("www.google.com"))
        assertTrue(SafeSearch.isSearchHost("google.co.uk"))
        // Everything else is blocked outright by SearchEngineBlocklist, so it must not
        // quietly fall into the SafeSearch path and be treated as allowed-if-safe.
        assertFalse(SafeSearch.isSearchHost("bing.com"))
        assertFalse(SafeSearch.isSearchHost("duckduckgo.com"))
        assertFalse(SafeSearch.isSearchHost(null))
    }
}
