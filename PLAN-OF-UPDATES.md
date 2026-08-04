# PLAN OF UPDATES — closing the gap with the established blockers

**Written 2026-08-04. For review, not yet actioned.**

## What this is

I looked at how the long-running adult-content blockers describe their own systems —
Covenant Eyes, Accountable2You, Ever Accountable, BlockerX, Canopy, Tech Lockdown,
CleanBrowsing — plus the community DNS blocklists they all lean on (HaGeZi, oisd,
StevenBlack), and the published write-ups of how people actually get round Android
parental controls.

Then I checked each finding against what this app already does. **Everything below is a
gap we genuinely have.** Things they do that we already do (uninstall lock, device admin,
guarded Settings pages, bypass-attempt recording, single-browser policy, word scoring,
domain blocklists, greylist time budgets, supervised unlock) are not listed.

### How to read the priorities

- **P0** — a hole someone will walk through today. Nothing else matters as much.
- **P1** — a real gap the mature products all closed years ago.
- **P2** — worth having; not load-bearing.
- **P3** — parity/polish.

### Honest caveat on sourcing

These companies do not publish git logs or their blocklists. What is public is their
marketing, docs, support articles, changelogs, and third-party technical write-ups. So
sections below are drawn from **what they say their systems must cover**, from the
**public blocklist projects** (which ARE open, and are the most directly reusable thing
found), and from **published bypass write-ups**. Where something is my own inference from
our code rather than sourced, it says so.

---

# 1. THE BIG ONE: we ship an image classifier we never load

## 1.1 Wire up the on-device NSFW model — **DEFERRED (2026-08-04, your call)**

`app/src/main/assets/nsfw/` contains `model.onnx`, `preproc.json` and `thresholds.json`.
`stageNsfwModel` in `app/build.gradle.kts` copies them in on every build. **No Kotlin code
anywhere loads them.** We are shipping a quantised ViT NSFW classifier as dead weight in
the APK.

This is the single biggest capability difference between us and Covenant Eyes. Their
entire product is: screenshot the screen periodically, classify it on-device, act on the
result. We read *text* — so an app showing images with no incriminating words is invisible
to us. That is most of the actual problem.

**Work:**
- `AccessibilityService.takeScreenshot()` (API 30+) gives us frames without MediaProjection
  and without a persistent notification. That is the route; we have no screenshot code at
  all today (no `takeScreenshot`, no `MediaProjection` anywhere in the tree).
- ONNX Runtime for Android to run the model; feed it the thresholds from `thresholds.json`.
- Sampling policy — this is where it lives or dies on battery. Suggest: only when the
  foreground app is scannable, only on window-content-changed after a settle delay, hard
  rate limit (e.g. 1 frame / 2s), and skip entirely while the screen is off or the keyguard
  is up.
- Feed the result into the existing machinery rather than inventing a new path: an image
  verdict should become a `BorderlineScorer`-equivalent signal so `BorderlineWatch` can
  count borderline *images* the same way it counts borderline text.

**Why it still matters:** every other item on this list is a refinement of a text filter that
cannot see pictures. Left for later deliberately, not forgotten.

## 1.2 Privacy story for image classification — **DEFERRED with 1.1**

Covenant Eyes blurs on-device, encrypts, and sends to a human. We have no accountability
partner and no server. Our honest version is **classify on-device, keep nothing**: no
frame ever written to disk, no frame ever leaving the device, only a score retained.

That is a stronger privacy position than any competitor and it should be stated loudly in
the UI. It also has to be *true* — decide it now, because retrofitting "we never store
frames" onto code that already caches them is how this goes wrong.

---

# 2. SURFACES WE DO NOT WATCH AT ALL

## 2.1 SafeSearch is not enforced — **P0**

We block every search engine except Google. We do **nothing** about Google itself having
SafeSearch off. Nothing in the codebase mentions safesearch (grep: zero hits). Google
Images with SafeSearch off is one of the highest-yield surfaces there is, and we currently
allow it.

Every serious product treats forcing SafeSearch as table stakes; HaGeZi even ships a list
of engines that *don't support* SafeSearch so they can be blocked outright.

**Work:**
- Detect `&safe=off` / `&safe=images` in the URL and block, treating it as a bypass attempt.
- Detect a Google results page whose text shows SafeSearch is off and block it.
- Consider blocking `google.com/imghp` and `tbm=isch` (image search) outright in Strict+.
- Adopt HaGeZi's `nosafesearch.txt` (206 entries) into our search-engine list — it is
  maintained and catches engines we have never heard of.

## 2.2 Translation proxies, caches and archives — **P0**

Named in every bypass write-up, and we block none of them. A blocked site is trivially
reachable through:

- `translate.google.com` / `translate.googleusercontent.com` (the classic; still works)
- `webcache.googleusercontent.com`, `cache:` searches
- `archive.org/web`, `web.archive.org`, `archive.ph` / `.today` / `.is`
- text extractors and reader proxies (`12ft.io`, `r.jina.ai`, print-to-PDF services)

**Work:** a new `domains_bypass.txt` category. This is a small file with a very high
return, and it should be Relaxed-and-above like the rest.

## 2.3 URL shorteners — **P1**

Our host check reads the address bar. `bit.ly/xyz` shows a host we do not block, and the
redirect lands before we re-read. HaGeZi ships 9,979 shortener domains.

**Work:** add the list; treat a shortener host as "unknown destination" and re-check
aggressively after navigation rather than blocking outright (blocking all shorteners
outright will annoy legitimately).

## 2.4 AI companion / NSFW chatbot apps — **P1, and this one is new**

The category barely existed when the incumbents wrote their lists, and it has exploded
through 2025–26: Character.AI, Janitor AI, Candy AI, Crushon.AI, SpicyChat, Muah.AI,
Replika, Chai, Talkie, plus the "uncensored" AI image generators. Several ship on Play.

We have no coverage. Our word filter will catch *some* of the conversation text, but the
apps themselves are unlisted.

**Work:** a fifth `BlockedCategories` entry — `apps_ai_companion.txt` /
`domains_ai_companion.txt`. This is an area where we could be genuinely ahead rather than
catching up, because the incumbents are visibly behind on it.

## 2.5 In-app browsers and Custom Tabs — **DONE 2026-08-04**

`readAddressBarText()` looks for Firefox toolbar view-ids. A link opened inside Instagram,
Reddit, Telegram or Discord renders in a WebView or Chrome Custom Tab with **no address
bar we recognise** — so `host` is null, and every domain-based rule (the 550k blocklist,
the ban list, all four category lists) silently does not apply. Only the word scorer runs.

*(This is my inference from our code, not from a competitor write-up — but Family Link
bypasses via embedded WebViews are documented, so the vector is real.)*

**Done.** `readInAppBrowserHost()` walks the tree, stays OUT of the WebView subtree (inside
is page content, full of other people's domains) and takes the first node whose ENTIRE text
is a host. Every domain rule now applies inside Instagram, Reddit, Telegram and the rest.

Scored at the APP bar, not the web bar - the web bar is 21 *because* the Firefox add-on is
reading the same page from the inside, and it is not doing that inside Instagram.

The address rule is `InAppBrowser.bareHost`, pulled out as a pure object and tested: whole
string or nothing, so a page cannot nominate its own host by quoting a URL. A test caught
`photo.jpg` parsing as a hostname, so bare filenames are rejected too.

Custom Tabs needed nothing: they run under the *browser's* package with the browser's own
toolbar, so the existing address-bar read already covers them.

## 2.6 Notifications, PiP, split-screen, recents — **DONE 2026-08-04**

Untested surfaces. A notification can carry explicit text and an image preview; a
picture-in-picture video keeps playing *over* a block cover; the recent-apps carousel shows
live thumbnails of everything open.

**Verified, and three of the four were real holes.**

- **SPLIT SCREEN** - `currentForegroundPackage()` answers "what is focused", which is the
  wrong question for blocking. A blocked app in the other pane was fully usable. Blocking
  now asks `visibleAppPackages()` and covers if ANY visible app is blocked.
- **PICTURE-IN-PICTURE** - a PiP window is an application window that is never active and
  never focused, so a video from a blocked app kept playing over the home screen while the
  focused package was the launcher. Same fix. (The cover itself was fine: an accessibility
  overlay already draws above app windows, so PiP was never *over* a cover.)
- **NOTIFICATIONS** - now read and scored. Not covered, and not coverable: the system draws
  them. A notification that scores as adult content is logged and counted against that app's
  borderline pattern, so a stream of them behaves like a stream of borderline screens.
  Needed `typeNotificationStateChanged` adding to the service config.
- **RECENT APPS** - deliberately left alone. Covering the app switcher would trap the user
  in it, and the thumbnail is a moment where the app itself is already blocked.

---

# 3. BYPASS VECTORS WE DO NOT DETECT

We guard five Settings pages (device admin, app info, force stop, accessibility,
appear-on-top) and the colour-correction page. The documented Android bypass list is much
longer.

## 3.1 The Play Store uninstall path is unguarded — **P0**

`UNINSTALL_GUARD_PAGES` is only consulted when `packageName == "com.android.settings"`.
The Play Store's own app page has an **Uninstall** button. Device admin should still refuse
the uninstall, but we neither bounce nor record the attempt — so our best early-warning
signal misses the most obvious route.

**Work:** extend the guard to `com.android.vending`.

## 3.2 Safe Mode — **P1**

Safe Mode boots Android with third-party apps disabled. Our service does not run. Nothing
is blocked, nothing is logged, and we never find out it happened.

**Work:** we cannot prevent it, but we can *notice* it. Record a heartbeat; on boot, detect
a gap in the heartbeat with no corresponding screen-off, and treat it as a bypass attempt.
The mature products all report tamper gaps rather than pretending they can stop them.

## 3.3 Second users, work profiles, Private Space, Secure Folder, app cloning — **P1**

All documented Family Link bypasses. An accessibility service registered for one user does
**not** see another user's apps. Android 15's Private Space and Samsung's Secure Folder are
precisely "a place to keep apps you don't want seen". Dual Apps / App Cloning give a cloned
Instagram a *different package name*, so our blacklist misses it.

**Work:** guard the Settings pages for Users, Private Space, Secure Folder and Dual Apps;
detect the presence of a second user and surface it as unprotected.

## 3.4 Developer options / USB debugging / ADB — **DELIBERATE NON-GOAL**

`adb shell` can disable our accessibility service outright, and Developer Options is not on
our guarded list. Every competitor treats this as a hole to close.

**We are leaving it open on purpose.** ADB is the master override. An app that can lock
itself in against a cable and a computer is an app nobody can rescue themselves from when
something goes wrong - a bad build, a lost passcode, a phone being handed on. The uninstall
lock, the mode ratchet and the week-long strict lock are all deliberately one-way doors;
this is the one door that stays unlocked, and it should stay that way.

## 3.5 Private DNS / DoH — **P2 for us specifically**

Heavily emphasised by everyone else — but they are DNS filters and we are not. Our blocking
does not depend on DNS at all, so Private DNS does not defeat *us*. It defeats the router
or NextDNS layer someone runs alongside us.

**Work:** guard the Private DNS settings page so the layer underneath us stays intact.
Lower priority than the incumbents would rate it, because of our architecture.

## 3.6 Clock manipulation defeats every timer we have — **P1**

`AppTimedBlock`, `Lockdown`, `LoosenWindow`, `GreyUsage` and the 7-day strict lock all use
`System.currentTimeMillis()`. Winding the clock forward ends all of them instantly. This is
a documented bypass of Family Link's timers.

**Work:** anchor durations to `SystemClock.elapsedRealtime()` where the window is short, and
for long windows store both and treat a large disagreement as tampering. Guard the date/time
settings page.

## 3.7 Sideloading and new installs — **P1**

"Install unknown apps" plus a downloaded APK reintroduces any blocked app under any package
name. Already in our own TODO ("monitor for activities like downloading new apps").

**Work:** guard the "Install unknown apps" permission screen; listen for
`PACKAGE_ADDED`; treat a new install during Strict+ as a bypass attempt and scan it against
the category lists immediately.

## 3.8 Factory reset — **P2**

Unpreventable, but Factory Reset Protection should be recommended during setup, and the
reset page should be guarded and recorded like the others.

---

# 4. BLOCKLIST SOURCING

## 4.1 Adopt HaGeZi's maintained lists — **P1**

The most directly reusable finding. `hagezi/dns-blocklists` is actively maintained, and the
category names map almost one-to-one onto what we have just built by hand:

| HaGeZi list | Entries | Maps to |
|---|---|---|
| `nsfw.txt` | ~107,700 | our adult category + the 550k list |
| `doh-vpn-proxy-bypass.txt` | ~17,500 | our new VPN category — **far bigger than ours** |
| `nosafesearch.txt` | 206 | §2.1 |
| `gambling.medium.txt` | ~139,400 | not covered at all |
| `anti.piracy.txt` | ~39,000 | not covered |
| `dyndns.txt` | ~1,500 | not covered |
| `urlshortener.txt` | ~9,980 | §2.3 |

Our hand-written `domains_vpn.txt` has **40** entries against their 17,545. Ours will not
survive contact with someone actually looking for a VPN.

**Work:** extend `DomainBlocklist`'s existing download-and-cache mechanism to fetch several
categorised lists rather than one merged adult list, keeping our hand-maintained files as
the always-present core (they are what survives a list going stale — that is already the
documented reason `domains_adult.txt` exists separately).

## 4.2 Our hand lists stay, and stay small — **P1**

Deliberate. The downloaded lists are large and change without us; ours are the ones we can
reason about and that a maintainer can read. Keep the split.

---

# 5. THE ACCOUNTABILITY MODEL WE DON'T HAVE

## 5.1 An accountability partner — **NOT DOING (2026-08-04, your call)**

Covenant Eyes, Accountable2You and Ever Accountable are *built* on this: a second human
sees a report. Accountable2You explicitly argues filters are bypassable and that the
relationship is the mechanism. Our `BypassWatch` reasoning already agrees with them —
a determined person always wins eventually, so the goal is to make the honest path the
easy one — but we stop short of the thing that actually makes it work.

We have the raw material: `BlockEventLog`, `BypassWatch`, `RelapseLog`, streaks.

**Decision: no.** This app is on-device with no account and no server, and an
accountability partner needs both. It is the biggest thing the incumbents have that we
will not have, and that is a deliberate trade for the privacy position - which is worth
more here than feature parity.

## 5.2 Tamper notices, to the USER — **P1**

Every competitor notifies a partner when protection stops. With no partner (§5.1) the
useful version is local: "protection was off for 40 minutes on Tuesday" shown in the app.
It is what makes §3.2 worth building, and it needs no backend.

## 5.3 Battery-optimisation exemption is requested but unused — **P2**

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is declared in the manifest and never requested in
code (grep: no `isIgnoringBatteryOptimizations` anywhere). On aggressive OEM builds
(Samsung, Xiaomi, OnePlus) the service gets killed and blocking silently stops. Add it to
the setup gate alongside the other prerequisites.

---

# 6. NOT DOING

Considered and rejected on 2026-08-04, recorded so they are not re-proposed:

- **Panic button** - a user-triggered full lockdown. `Lockdown` already covers the need.
- **More stats** - blocked-attempt counters and the like. The Productivity page is enough.
- **Scheduled strictness** - time-of-day and day-of-week rules. The night guard and the room
  beacons are better signals than a clock, and both already exist.
- **Promoting the whitelist into the main UI** - it stays in Developer tools.
- **Accountability partner** (§5.1) and **ADB lockdown** (§3.4), for the reasons given above.

# 7. SUGGESTED ORDER

**First (holes people walk through today)**
1. §3.1 Play Store uninstall guard — an afternoon
2. §2.1 SafeSearch enforcement
3. §2.2 translate / cache / archive list
4. §2.3 URL shorteners

**Second**
5. §4.1 categorised blocklist downloads
6. §3.6 clock-tamper resistance
7. §0 false positives / false negatives (see below - added after review)

**Third (the vectors)**
8. §3.3–3.7 the remaining Settings guards + install monitoring
9. §2.5 in-app browsers
10. §3.2 safe-mode gap detection

**Fourth**
11. §5.2 tamper notices
12. §2.4 AI companion category

---

# 8. WHAT WE ALREADY DO BETTER

Worth keeping in view, because the plan above is a list of failings and the picture is not
one-sided:

- **On-device, no account, no server.** Every competitor ships screenshots off the device.
- **Sensor and presence signals.** The night guard (lying down / darkness) and BLE room
  beacons are not in any competitor's product.
- **The mode ratchet.** Entering Strict permanently removes Off. Blunt, honest, and rare.
- **`BypassWatch`.** Offering the supervised exit exactly when someone reaches for the
  destructive one is a genuinely better idea than a plain hard wall.
- **`BorderlineWatch`.** Acting on a *pattern* of near-misses rather than single frames is
  something the text-filter products do not attempt.
- **The word scorer is far more careful.** Tiered weights, per-word innocent-context
  exceptions, medical damping, family caps, the single-word guarantee. The blunt keyword
  lists in the cheaper competitors are what we replaced last week.

---

## Sources

- [Tech Lockdown — The Porn Blocking System](https://www.techlockdown.com/articles/block-porn)
- [Tech Lockdown — Block Porn on Android](https://www.techlockdown.com/articles/block-porn-android)
- [CleanBrowsing — How Kids Bypass Content Filters](https://cleanbrowsing.org/learn/how-kids-bypass-filters)
- [Bitdefender — How Kids Bypass Google Family Link on Android (2025)](https://www.bitdefender.com/en-us/blog/hotforsecurity/family-link-bypass-android-2025)
- [HaGeZi DNS Blocklists](https://github.com/hagezi/dns-blocklists) · [FAQ](https://github.com/hagezi/dns-blocklists/wiki/FAQ)
- [oisd NSFW blocklist](https://nsfw.oisd.nl/)
- [Covenant Eyes vs alternatives — screen accountability comparison](https://www.barchart.com/story/news/2235198/covenant-eyes-vs-alternatives-june-2026-how-the-screen-accountability-pioneer-compares-to-canopy-accountable2you-and-ever-accountable)
- [Ever Accountable — Covenant Eyes alternatives](https://everaccountable.com/blog/covenant-eyes-alternatives/)
- [Accountable2You vs Covenant Eyes](https://overcomer-app.com/blog/accountable2you-vs-covenant-eyes)
- [Canopy — How to Block Porn: Every Method Ranked](https://canopy.us/blog/how-to-block-porn/)
- [Canopy — Best Porn Blocker Apps 2026](https://canopy.us/blog/best-porn-blocker/)
- [BlockerX on Google Play](https://play.google.com/store/apps/details?id=io.funswitch.blocker)
- [Protect Young Eyes — How to Block Porn on Any Device](https://www.protectyoungeyes.com/blog-articles/how-to-block-porn-on-any-device-for-free)
- [AI companion app landscape 2026](https://aicompanionguides.com/blog/best-nsfw-ai-chat-apps-2026/)
