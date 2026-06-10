# Web Traffic Monitor

An Android accountability app, in Kotlin. It records what is actually shown on
the screen, across every app and browser, with no root and no network MITM.

## What it does

- Records the website domain, a rough page title, and the visible on-screen text (via the Accessibility service)
- Takes a downscaled screenshot every few seconds so you can see the images/videos that were on screen (via screen capture)
- Works in any app or browser, because it reads the screen, not the network
- Stores everything locally on the device in a database
- Shows it all in a scrollable list in the app
- Domain detection is browser-agnostic on purpose (it scans for a hostname rather than relying on a specific browser), so it keeps working when browsers change

## What it deliberately does not do

- It does not decrypt HTTPS, so it never sees full URLs, image URLs, or page paths. You get the domain plus the on-screen text and the screenshot instead. This is the same approach Covenant Eyes / Ever Accountable / Accountable2You use.
- No root, no certificate installation, no VPN.

## How it works (for later maintenance)

- PageMonitorAccessibilityService: reads the foreground app, domain, title and text. Event-driven and throttled, so it is cheap.
- ScreenCaptureService: a foreground service holding a MediaProjection. Frames are downscaled and saved as JPEGs every 3 seconds.
- Room database (monitor.db) stores one row per page view or screenshot.
- MainActivity shows the list and the two on/off controls.
- All third-party libraries are Apache-2.0 or compatible (AndroidX, Room, Coil, Kotlin coroutines). No GPL, so it is fine for a paid app.

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
6. That is it. Use the phone normally. Open the app again any time to see the scrollable list of what was recorded.

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

- Analysing the screenshots/text to decide if content is appropriate. The data
  is captured and stored now; the analysis step (for example an on-device
  classifier) can be added on top later.
- A whitelist of apps to skip.
