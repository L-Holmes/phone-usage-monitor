package com.example.webtrafficmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The third-party client detector's matching half (see ProxyClients.kt).
 *
 * Two jobs, and as with the word filter the second one matters more than the first:
 *   1. an app that IS a client for a blocked service is recognised;
 *   2. an app that merely MENTIONS one is not. Everything this detector decides ends in a
 *      whole app being taken away, so a false positive here is expensive - it is not one
 *      page blocked, it is somebody's photo editor gone.
 *
 * The stateful half (one sighting vs two, the user's override) lives in ProxyClients and
 * needs a Context, so it is not covered here.
 */
class ClientMarkersTest {

    private fun match(text: String, name: String = "") =
        ClientMarkers.suspectFromScreen(text, name)

    // ── the services actually load ───────────────────────────────────────────────────

    @Test
    fun `the marker file parses into services`() {
        val all = ClientMarkers.ALL
        assertTrue("no services parsed - check clients_services.txt", all.size >= 8)
        val reddit = ClientMarkers.byId("reddit")
        assertNotNull(reddit)
        assertEquals("Reddit", reddit!!.label)
        assertTrue("reddit.com" in reddit.domains)
        assertTrue(reddit.strong.isNotEmpty())
        // Every service must carry all three kinds of evidence, or it is only half wired up.
        for (s in all) {
            assertTrue("${s.id} has no domains", s.domains.isNotEmpty())
            assertTrue("${s.id} has no strong markers", s.strong.isNotEmpty())
            assertTrue("${s.id} has no name hints", s.hints.isNotEmpty())
        }
    }

    // ── PROOF: the service's own host ────────────────────────────────────────────────

    @Test
    fun `a service host is recognised, subdomains included`() {
        assertEquals("reddit", ClientMarkers.serviceForHost("reddit.com")?.id)
        assertEquals("reddit", ClientMarkers.serviceForHost("oauth.reddit.com")?.id)
        assertEquals("reddit", ClientMarkers.serviceForHost("www.reddit.com")?.id)
        assertEquals("flickr", ClientMarkers.serviceForHost("api.flickr.com")?.id)
        assertEquals("x", ClientMarkers.serviceForHost("api.twitter.com")?.id)
    }

    @Test
    fun `an unrelated host is not a service`() {
        listOf("wikipedia.org", "google.com", "example.com", "reddit.example.com", null)
            .forEach { assertNull("'$it' must not match", ClientMarkers.serviceForHost(it)) }
    }

    // ── PROOF: the sign-in handoff (a Custom Tab, not the app's own WebView) ─────────

    @Test
    fun `an authorize url is a sign-in handoff`() {
        assertEquals("reddit", ClientMarkers.serviceForAuthUrl(
            "reddit.com", "https://www.reddit.com/api/v1/authorize?client_id=abc&scope=read")?.id)
        assertEquals("flickr", ClientMarkers.serviceForAuthUrl(
            "flickr.com", "https://www.flickr.com/services/oauth/authorize?oauth_token=x")?.id)
        assertEquals("x", ClientMarkers.serviceForAuthUrl(
            "twitter.com", "https://api.twitter.com/oauth/authenticate?oauth_token=x")?.id)
    }

    @Test
    fun `ordinary browsing of a service is NOT a handoff`() {
        // This is the whole safety property of the handoff rule. "The browser is on
        // reddit.com and app X was open a minute ago" must never blame X - people browse.
        // Only a page nobody can reach without an app asking for it counts.
        listOf(
            "https://www.reddit.com/",
            "https://www.reddit.com/r/all",
            "https://www.flickr.com/photos/someone/",
            "https://twitter.com/home",
        ).forEach {
            assertNull("'$it' must not read as a sign-in handoff",
                ClientMarkers.serviceForAuthUrl("reddit.com", it)
                    ?: ClientMarkers.serviceForAuthUrl("flickr.com", it)
                    ?: ClientMarkers.serviceForAuthUrl("twitter.com", it))
        }
    }

    @Test
    fun `an authorize url on an unrelated site is not a handoff`() {
        assertNull(ClientMarkers.serviceForAuthUrl(
            "example.com", "https://example.com/oauth/authorize?client_id=abc"))
        assertNull(ClientMarkers.serviceForAuthUrl("reddit.com", null))
        assertNull(ClientMarkers.serviceForAuthUrl(null, "https://reddit.com/api/v1/authorize"))
    }

    // ── SUSPICION: the service's own vocabulary ──────────────────────────────────────

    @Test
    fun `two of a service's own words make it a suspect`() {
        val m = match("Browse your subreddits  ·  1,204 post karma  ·  Sort by hot")
        assertEquals("reddit", m?.service?.id)
        assertTrue("the evidence must name what matched", m!!.why().contains("subreddit"))
    }

    @Test
    fun `the flickr client that started this is recognised`() {
        val m = match(
            "Photostream   Sets   Groups   Faves\nYour photostream  ·  Flickr Pro expires soon",
            "FlickFolio com.snapwood.flickfolio",
        )
        assertEquals("flickr", m?.service?.id)
    }

    @Test
    fun `ONE word is never enough`() {
        // The single-signal rule, again. One word is a question, not an answer.
        assertNull(match("Browse your subreddits"))
        assertNull(match("Your photostream"))
        assertNull(match("reblog"))
    }

    @Test
    fun `two spellings of one word are one marker`() {
        // "subreddits" contains "subreddit", and both are on the list. Counting that as two
        // would clear the bar off a single word - the same trap the word filter's FAMILIES
        // rule exists to close. This test is the reason ClientMarkers.distinct() exists.
        assertNull(match("subreddit subreddits subreddit"))
        assertNull(match("Reblog  ·  reblogged by 4 people"))
        assertNull(match("Retweet  ·  12 retweets"))
    }

    @Test
    fun `the app name alone is never enough`() {
        // A name is a hint about what an app CLAIMS to be. On its own it proves nothing -
        // and plenty of innocent apps have "snap", "pin" or "flick" in their names.
        assertNull(match("Nothing to see here", "Boost for Reddit com.rubenmayayo.reddit"))
        assertNull(match("Take a photo", "SnapSeed com.niksoftware.snapseed"))
        assertNull(match("Pin this to your wall", "PinPoint com.example.pinpoint"))
    }

    @Test
    fun `the app name lifts ONE real word to a suspicion`() {
        // One real marker plus a name that says what it is = two. This is the case a
        // curated list would have caught anyway, arriving at the same answer by itself.
        val m = match("Browse your subreddits", "Boost for Reddit com.rubenmayayo.reddit")
        assertEquals("reddit", m?.service?.id)
        assertTrue("the name hit must be quoted", m!!.why().contains("the name says"))
    }

    @Test
    fun `ordinary app screens are never suspects`() {
        // Every one of these is a realistic screen from an app that must not be blocked.
        listOf(
            "Messages  Mum  see you at 6  Dad  ok  Sam  running late",
            "Settings  Battery  Display  Sound  Notifications  Storage",
            "Calendar  Tuesday  dentist 9:00  standup 10:00  gym 18:00",
            "Photos  Camera Roll  Screenshots  Favourites  Recently deleted",
            "Your order is on its way  ·  Track delivery  ·  Rate your driver",
            "Upvote this answer  ·  1.2k karma  ·  Ask a question",  // a Q&A app, not Reddit
            "Share to  ·  Copy link  ·  Save image  ·  Report post",
            "Watch later  ·  Downloads  ·  My list  ·  Continue watching",  // a streaming app
        ).forEach {
            assertNull("'$it' must not be a suspect (matched ${match(it)?.service?.id})", match(it))
        }
    }

    @Test
    fun `generic forum vocabulary does not make an app Reddit`() {
        // Deliberate: "upvote", "downvote" and "karma" are NOT strong markers, because every
        // forum client on earth has them. If someone adds them to clients_services.txt this
        // test is what should stop them.
        assertNull(match("Upvote  Downvote  312 karma  Sort by new  Reply"))
    }

    @Test
    fun `matching is case and punctuation tolerant enough`() {
        assertNotNull(match("SUBREDDITS  ·  Post Karma 4,102"))
        assertNotNull(match("Your PHOTOSTREAM — Flickr Pro"))
    }

    // ── the exclusions, which are the whole safety story ─────────────────────────────

    @Test
    fun `the apps that legitimately talk about other apps are never scanned`() {
        val us = "com.example.webtrafficmonitor"
        // The Play Store's listing for Reddit says "subreddit" all over it; Settings lists
        // every app on the phone; a launcher carries every app name there is; and this app's
        // own block lists are made of these words.
        assertTrue(!ClientMarkers.isScannable("com.android.vending", us))
        assertTrue(!ClientMarkers.isScannable("com.android.settings", us))
        assertTrue(!ClientMarkers.isScannable("com.sec.android.app.launcher", us))
        assertTrue(!ClientMarkers.isScannable("com.android.systemui", us))
        assertTrue(!ClientMarkers.isScannable(us, us))
        assertTrue(!ClientMarkers.isScannable(null, us))
        // Anything else is fair game.
        assertTrue(ClientMarkers.isScannable("com.snapwood.flickfolio", us))
        // The browser and already-blocked-app exclusions are real and matter just as much,
        // but they read app lists out of assets/, which need an Android Context - so they
        // cannot be asserted from here. See the note on ClientMarkers.isScannable.
    }
}
