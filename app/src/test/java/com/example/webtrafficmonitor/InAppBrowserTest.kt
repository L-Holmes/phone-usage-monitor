package com.example.webtrafficmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the domain out of an app's own in-app browser (§2.5).
 *
 * This is the rule that decides whether a piece of on-screen text is treated as THE PAGE'S
 * ADDRESS. It matters in both directions:
 *
 *   • too strict and Instagram's built-in browser stays invisible to every domain rule we
 *     have, which was the hole this closed;
 *   • too loose and a page can nominate its own host by quoting a URL in its text - which
 *     would let a page dodge a block, or pin a block on an innocent third party.
 *
 * So the rule is deliberately narrow: the ENTIRE string has to be an address.
 */
class InAppBrowserTest {

    @Test
    fun `a bare domain is an address`() {
        assertEquals("instagram.com", InAppBrowser.bareHost("instagram.com"))
        assertEquals("instagram.com", InAppBrowser.bareHost("  instagram.com  "))
        assertEquals("news.bbc.co.uk", InAppBrowser.bareHost("news.bbc.co.uk"))
        assertEquals("example.com", InAppBrowser.bareHost("EXAMPLE.COM"))
    }

    @Test
    fun `a full url is an address`() {
        assertEquals("example.com", InAppBrowser.bareHost("https://example.com/some/path?q=1"))
        assertEquals("example.com", InAppBrowser.bareHost("http://example.com"))
        assertEquals("m.example.com", InAppBrowser.bareHost("https://m.example.com/x#frag"))
    }

    @Test
    fun `prose that merely mentions a domain is NOT an address`() {
        // The whole point. If these were accepted, any page could name its own host.
        listOf(
            "see instagram.com for more",
            "Visit example.com today",
            "example.com is down",
            "Shared from reddit.com",
            "Read more at bbc.co.uk",
        ).forEach { assertNull("'$it' must not read as an address", InAppBrowser.bareHost(it)) }
    }

    @Test
    fun `ordinary interface text is not an address`() {
        listOf(
            null, "", "   ", "Done", "Share", "Open in browser", "Loading...",
            "Page 1 of 3", "12:04", "No internet connection",
        ).forEach { assertNull("'$it' must not read as an address", InAppBrowser.bareHost(it)) }
    }

    @Test
    fun `a filename is not an address`() {
        // The nastiest near-miss: no whitespace, has a dot, but is not a host.
        listOf("photo.jpg", "report.pdf", "index.html", "v1.2.3", "archive.zip").forEach {
            assertNull("'$it' must not read as an address", InAppBrowser.bareHost(it))
        }
        // ...but a real URL that happens to point at a file is still a real URL, because
        // the host is unambiguous once there is a path.
        assertEquals("example.com", InAppBrowser.bareHost("https://example.com/photo.jpg"))
    }

    @Test
    fun `something absurdly long is rejected rather than parsed`() {
        val huge = "a".repeat(400) + ".com"
        assertNull(InAppBrowser.bareHost(huge))
    }
}
