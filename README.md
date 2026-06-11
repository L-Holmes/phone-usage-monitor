# Web Traffic Monitor

An Android accountability app, in Kotlin. It records what is actually shown on
the screen, across every app and browser, with no root and no network MITM.

## What it does

- Records the website domain, a rough page title, and the visible on-screen text (via the Accessibility service)
- Takes a downscaled screenshot every few seconds so you can see the images/videos that were on screen (via screen capture)
- Works in any app or browser, because it reads the screen, not the network
- Stores everything locally on the device in a database
- Shows it all in a scrollable list in the app
- Blocks pages that match a block rule by covering the screen with a full-screen "Blocked" overlay (with Go back / Leave / Report buttons)
- Domain detection and blocking are browser-agnostic on purpose (they scan for a hostname rather than relying on a specific browser), so they keep working when browsers change

## What we can and cannot detect (honest limits, for future dev)

What we CAN get (no root, via Accessibility + screen capture):
- The website domain, from the address bar. In Chrome this is only the registrable domain (reddit.com), in Firefox it can include the path.
- The page title (e.g. "Wolf - Wikipedia"), usually, across browsers — but not guaranteed for every app/page.
- A sample of the visible on-screen text, across any app or browser.
- Periodic screenshots of the screen.
- Domain + title + text are captured in private/incognito browsing too (accessibility still works there).

What we CANNOT get (do not pretend otherwise):
- Full URLs / exact page paths reliably. Chrome exposes only the registrable domain; to identify a specific page use the title/text. Getting real URLs everywhere would need root or a proxy/MITM with an installed certificate, which we are not doing.
- Image or video URLs (HTTPS hides them). We rely on the screenshot pixels instead.
- Screenshots in private/incognito tabs, and in banking/DRM apps: these come out BLACK. Android's FLAG_SECURE blocks screen capture there and it cannot be bypassed without root. Page monitoring (domain/title/text) still works in private mode; only the image is black.

Consequences for blocking:
- Block a whole site with a domain rule (wikipei). Works on actual visits, in any browser, including private mode.
- Block a specific page with a title keyword (wolf), or by tapping its row. Cannot block by full URL path.

## How it works (for later maintenance)

- PageMonitorAccessibilityService: reads the foreground app, domain, title and text. The domain comes only from the browser address bar (read while it is not being edited), and is remembered until the app or address changes. It decides blocking from the domain and title only, never from arbitrary on-screen text, so autocomplete suggestions and embedded resources do not trigger blocks. Event-driven and throttled.
- ScreenCaptureService: a foreground service holding a MediaProjection. Frames are downscaled and saved as JPEGs every 3 seconds.
- BlockRules: the list of things to block (a simple stand-in for the real classifier until that exists). A rule with a dot is a domain rule ("wikipedia.com" blocks the site and its subdomains; "i.reddit.com" blocks only that subdomain). A rule without a dot is a keyword matched against the page title ("wolf" blocks pages titled like "Wolf - Wikipedia").
- OverlayController: draws/removes the full-screen block cover. The cover is opaque (hides content) but not focusable, so the system Back action still reaches the app underneath.
- Room database (monitor.db) stores one row per page view or screenshot. In testing builds (BuildConfig.IS_TESTING) data older than 10 minutes is auto-deleted so it does not pile up.
- MainActivity shows the list, the three on/off/permission controls, the block-rule controls, and Clear blocks / Clear log buttons.
- Launchers and the system UI are skipped so the log is not full of noise.
- All third-party libraries are Apache-2.0 or compatible (AndroidX, Room, Coil, Kotlin coroutines). No GPL, so it is fine for a paid app.

## How blocking behaves

- The cover only appears on an actual web page (when the address bar is readable). The tab switcher, the home screen, and non-browser apps are never covered, so a blocked tab's thumbnail in the switcher does not trap you — you can open the switcher and close the tab.
- Go back: fires the system Back. If it reaches allowed content the cover clears automatically; if Back cannot go anywhere, the cover stays. It is debounced (about 0.8s) so an impatient double-tap does not skip back two pages; tap again after a moment to keep going back.
- Leave: goes to the home screen and clears the cover. This is the always-works escape hatch for any app.
- Report incorrect block: lets the current page through until the app is restarted. The block screen names the rule that matched (e.g. "wolf") so you know why.
- This is the realistic no-root limit: we cannot show a fake 404 inside the browser, and we cannot block only the images on an HTTPS page. Covering the screen and offering Back/Leave is what is reliably possible.

## How to test blocking (until the real classifier exists)

- To block a whole site: type its domain (e.g. wikipedia.com) into the box and tap Block.
- To block a topic: type a keyword (e.g. wolf) and tap Block — it matches the page title.
- To block one specific page: tap that row in the list (it blocks by the page title, so other pages on the same site stay allowed).
- Then open the site/page and the block cover appears. Use Clear blocks to remove rules, Clear log to empty the list.
- Blocking only fires when you actually load the page (from the address bar), not when a domain merely appears in a suggestion or on the page.

## Already set up on this computer (you do not need to touch this)

- Android SDK in ~/Android/Sdk (adb, platform 34, build-tools 34), on PATH via ~/.bashrc
- Gradle wrapper (./gradlew), no separate Gradle install needed
- The app and the on-device test both build and pass

## Do this once on the computer (only the first time)

- In a terminal in this folder run: sudo ./setup-udev.sh
- It asks for your password. This lets Linux talk to the phone over USB.

## Do this on the phone (to actually start monitoring)

You only need steps 1 to 3 once per phone. The app must be installed first
(./gradlew installDebug, or Claude does it for you).

1. Plug the phone into the computer with a USB data cable.
2. Turn on USB debugging: Settings, About phone, tap Build number 7 times, then Settings, Developer options, turn on USB debugging. Tap Allow on the popup.
3. Open the Web Traffic Monitor app.
4. Tap ENABLE next to Page monitoring. This opens Accessibility settings. Find Web Traffic Monitor in the list and turn it on, then come back.
5. Tap START next to Screen capture. Tap Allow / Start now on the popup that asks to record the screen.
6. Tap GRANT next to Block overlay permission. Turn on "Allow display over other apps" for Web Traffic Monitor, then come back. (Needed only if you want blocking.)
7. That is it. Use the phone normally. Open the app again any time to see the scrollable list of what was recorded.

To stop: tap STOP for screen capture, and turn the Accessibility service off in settings.

## Run the on-device test

- ./gradlew connectedAndroidTest
- This builds, installs, runs the test on the phone, and reports pass or fail.
- Report: app/build/reports/androidTests/connected/

## How Claude interacts with the phone

- adb devices                                   list connected phones
- ./gradlew installDebug                         build and install the app
- ./gradlew connectedAndroidTest                 run the on-device test
- adb exec-out screencap -p > shot.png           screenshot the phone
- adb shell run-as com.example.webtrafficmonitor cat databases/monitor.db   read the recorded data
- adb logcat                                     watch device logs

## Not done yet (next steps)

- Analysing the screenshots/text to decide if content is appropriate, and feeding
  that decision into the blocker instead of the manual block rules. The data is
  captured and stored now; the analysis step (for example an on-device classifier)
  can be added on top later.
- A whitelist of apps to skip.
