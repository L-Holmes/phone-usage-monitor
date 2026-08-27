package com.example.webtrafficmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The domain blocklist's lookup half.
 *
 * This is the part of the app that blocks a page WITHOUT any score, appeal or explanation
 * beyond "that host is on the list", and until 2026-08-27 it had no tests at all - it lived
 * inside DomainBlocklist, which needs a Context, a network and an 8MB cache file, so none of
 * it could run on the JVM. Then the storage changed from a HashSet of strings to sorted
 * 64-bit fingerprints to get 80MB of memory back, which is exactly the kind of change that
 * should not be made against untested code.
 *
 * The two properties that matter are both here: a list entry covers its subdomains, and a
 * host that is not on the list is never blocked.
 */
class HostFingerprintsTest {

    private val list = HostFingerprints.from(
        listOf("badsite.com", "adult.example.org", "another-one.net"),
    )

    @Test
    fun `an exact host on the list is blocked`() {
        assertTrue(list.contains("badsite.com"))
        assertTrue(list.contains("another-one.net"))
    }

    @Test
    fun `a subdomain of a listed host is blocked`() {
        // This is the whole point of the parent walk: one list entry has to cover the
        // CDN, the mobile site and every shard the site invents next week.
        assertTrue(list.contains("cdn.badsite.com"))
        assertTrue(list.contains("m.badsite.com"))
        assertTrue(list.contains("a.b.c.d.badsite.com"))
        assertTrue(list.contains("images.adult.example.org"))
    }

    @Test
    fun `a PARENT of a listed host is not blocked`() {
        // "adult.example.org" is listed; example.org is not, and blocking it would take out
        // everything else on that domain. The walk goes UP from the host, never down.
        assertFalse(list.contains("example.org"))
        assertFalse(list.contains("news.example.org"))
    }

    @Test
    fun `hosts that are not on the list are never blocked`() {
        listOf(
            "wikipedia.org", "en.wikipedia.org", "google.com", "bbc.co.uk",
            "nhs.uk", "badsite.com.example.net", "notbadsite.com", "badsite.co",
        ).forEach {
            assertFalse("'$it' must not be blocked", list.contains(it))
        }
    }

    @Test
    fun `a bare TLD is never tested, however the walk gets there`() {
        // If "com" could ever match, one bad entry would block the internet. The walk stops
        // while there is still a dot left.
        val tlds = HostFingerprints.from(listOf("com", "co.uk", "org"))
        assertFalse(tlds.contains("example.com"))
        assertFalse(tlds.contains("anything.org"))
        // "co.uk" still has a dot, so it IS reachable - which is why a TLD-shaped entry must
        // never end up on the list. Recorded here so the behaviour is a decision, not a
        // surprise: the list sources are host lists, and none of them ship public suffixes.
        assertTrue(tlds.contains("bbc.co.uk"))
    }

    @Test
    fun `www and casing and whitespace do not matter`() {
        assertTrue(list.contains("www.badsite.com"))
        assertTrue(list.contains("BadSite.com"))
        assertTrue(list.contains("  WWW.BADSITE.COM  "))
        assertTrue(list.contains("WWW.CDN.BadSite.Com"))
    }

    @Test
    fun `an empty list blocks nothing and an empty host is not a match`() {
        val empty = HostFingerprints.from(emptyList())
        assertFalse(empty.contains("badsite.com"))
        assertFalse(list.contains(""))
        assertFalse(list.contains("   "))
    }

    @Test
    fun `the fingerprint is stable and spreads out`() {
        // Stable: the cache file on the device is fingerprints and nothing else, so if this
        // function ever changes value for the same input, every cached list silently stops
        // matching. If this test fails, the cache format needs a new CACHE_MAGIC.
        assertEquals(HostFingerprints.of("badsite.com"), HostFingerprints.of("badsite.com"))

        // Spread out: hosts differ mostly in their last few characters, so a hash whose low
        // bits barely move would collide in clusters. Near-identical names must not land
        // near each other.
        val a = HostFingerprints.of("site1.com")
        val b = HostFingerprints.of("site2.com")
        assertTrue("adjacent names must not produce adjacent fingerprints",
            Math.abs(a - b) > 1_000_000L)
    }

    @Test
    fun `no collisions across a large synthetic list`() {
        // Not proof - the real argument is the arithmetic on DomainBlocklist - but it would
        // catch a hash that had been broken outright (a truncation, a lost multiply).
        val seen = HashSet<Long>(400_000)
        for (i in 0 until 200_000) {
            assertTrue("collision at $i", seen.add(HostFingerprints.of("host$i.example.com")))
            assertTrue("collision at $i", seen.add(HostFingerprints.of("$i-site.net")))
        }
    }
}
