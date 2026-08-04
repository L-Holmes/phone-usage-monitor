package com.example.webtrafficmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trusted-domain list, and the over-blocking it exists to prevent.
 *
 * This is the one list where being wrong is worse than the thing we are preventing. When
 * the UK rolled out ISP-level adult filters the casualties included rape crisis centres,
 * domestic violence services, sex education charities and porn-addiction recovery sites -
 * and filtering studies have measured ~27% of sites about condoms blocked at the LEAST
 * aggressive settings.
 *
 * These tests are the standing guarantee that we do not repeat that. If one fails, somebody
 * has removed a helpline from the allowlist or put it on a blocklist, and the fix is to put
 * it back, not to change the test.
 */
class TrustedDomainsTest {

    /**
     * Read the asset straight off disk. FilterData.set() goes through the Android asset
     * manager, which does not exist in a JVM test - it would quietly return an empty set
     * and every assertion here would be vacuous. This is testing the FILE's contents, so
     * reading the file is the honest way to do it.
     */
    private fun asset(name: String): Set<String> {
        val candidates = listOf(
            java.io.File("src/main/assets/filter/$name"),
            java.io.File("app/src/main/assets/filter/$name"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("cannot find $name from ${java.io.File(".").absolutePath}")
        return f.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private val trusted = asset("domains_trusted.txt")

    private fun isTrusted(host: String): Boolean = hostOrParentIn(host, trusted)

    @Test
    fun `the list actually loaded`() {
        // Guards against this whole file silently passing on an empty set.
        assertTrue("expected a substantial trusted list, got ${trusted.size}", trusted.size > 100)
    }

    @Test
    fun `crisis and abuse support is trusted`() {
        listOf(
            "rainn.org", "rapecrisis.org.uk", "thehotline.org", "refuge.org.uk",
            "womensaid.org.uk", "childline.org.uk", "nspcc.org.uk", "victimsupport.org.uk",
        ).forEach { assertTrue("$it must never be blockable", isTrusted(it)) }
    }

    @Test
    fun `suicide prevention is trusted`() {
        listOf("samaritans.org", "988lifeline.org", "crisistextline.org", "papyrus-uk.org")
            .forEach { assertTrue("$it must never be blockable", isTrusted(it)) }
    }

    @Test
    fun `sexual health and sex education is trusted`() {
        // The single most over-blocked category in the published research.
        listOf(
            "sexwise.org.uk", "brook.org.uk", "plannedparenthood.org", "scarleteen.com",
            "tht.org.uk", "fpa.org.uk", "hiv.gov",
        ).forEach { assertTrue("$it must never be blockable", isTrusted(it)) }
    }

    @Test
    fun `lgbt support is trusted`() {
        listOf("stonewall.org.uk", "thetrevorproject.org", "switchboard.lgbt", "mermaidsuk.org.uk")
            .forEach { assertTrue("$it must never be blockable", isTrusted(it)) }
    }

    @Test
    fun `recovery from the thing this app exists for is trusted`() {
        // The UK rollout blocked porn-addiction recovery sites. Ours must not.
        listOf("fightthenewdrug.org", "nofap.com", "rebootnation.org", "yourbrainonporn.com")
            .forEach { assertTrue("$it must never be blockable", isTrusted(it)) }
    }

    @Test
    fun `subdomains of a trusted domain are trusted`() {
        assertTrue(isTrusted("www.rainn.org"))
        assertTrue(isTrusted("support.samaritans.org"))
        assertTrue(isTrusted("en.wikipedia.org"))
    }

    @Test
    fun `the allowlist does not accidentally cover the things we block`() {
        // A trusted entry outranks EVERY block, so a wrong one here is a permanent hole.
        listOf(
            "pornhub.com", "onlyfans.com", "xvideos.com", "reddit.com", "tiktok.com",
            "nordvpn.com", "chaturbate.com", "janitorai.com", "4chan.org",
        ).forEach { assertFalse("$it must NOT be on the trusted list", isTrusted(it)) }
    }

    @Test
    fun `the translate proxy is deliberately not trusted`() {
        // It is the oldest filter bypass there is, and it lives on a Google domain that the
        // rest of the allowlist is full of. Easy to add by accident; must never be there.
        assertFalse(isTrusted("translate.google.com"))
        assertFalse(isTrusted("translate.googleusercontent.com"))
        assertFalse(isTrusted("webcache.googleusercontent.com"))
        // ...while the Google services people genuinely need still are.
        assertTrue(isTrusted("mail.google.com"))
        assertTrue(isTrusted("maps.google.com"))
    }

    @Test
    fun `no domain is on both the trusted list and a block list`() {
        val blocked = buildSet {
            addAll(asset("domains_adult.txt"))
            addAll(asset("domains_ugc.txt"))
            addAll(asset("domains_strangers.txt"))
            addAll(asset("domains_vpn.txt"))
            addAll(asset("domains_bypass.txt"))
            addAll(asset("domains_ai_companion.txt"))
            addAll(asset("domains_banned.txt"))
            addAll(asset("domains_search_engines.txt"))
        }
        assertTrue("expected a substantial block list, got ${blocked.size}", blocked.size > 200)
        val both = trusted intersect blocked
        assertTrue("a domain on both lists is a contradiction: $both", both.isEmpty())
    }
}
