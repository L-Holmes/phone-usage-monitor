
RUN:
./deploy.sh

DEBUG:
adb logcat -s NsfwClassifier ScreenCapture


screen capturing not possible on android..
    on my samsung- it always resets...



------------
readme:


Phone testing:
# first build stages the 329 MB model (slower); later builds skip it
./gradlew :app:assembleDebug   
# or adb install -r the apk
./deploy.sh                     
# watch model load + any errors
adb logcat -s NsfwClassifier ScreenCapture

./gradlew :app:assembleDebug && ./deploy.sh



===============================
# blocking rules


## 🖼️ Images (screenshot NSFW scoring)

Scoring runs on screen captures (score 0–1; **0.50 = threshold**). Detection tiers:

- **Clear** — one frame scores > 0.75 → **block**
- **Probable** — two frames 0.6–0.75 (tolerating ≤2 stray frames) → **block**
- **Borderline** — five frames 0.5–0.6 (tolerating ≤2 stray frames) → **block**

What the block *does* depends on where you are:

### …in a web browser
- **Condition:** any image tier fires while in a browser → **block the exact subdomain** + strike the domain
  - Keep going back / return to same subdomain → stays blocked (it's now permanent)
  - 3 strikes on that domain in one day → **whole domain blocked**

### …in a normal app
- **Condition:** any image tier fires in a non-browser app → **10s warning, then app blocked 5 min**
  - Re-enter & flagged again → **blocked until tomorrow**
  - Flagged again after that → **blocked forever**
- **Condition:** 5 blocks on the same app within 10 min → **90 min block** (browser or not, overrides ladder)

## 🔤 Text / URL / keyword / app (no image)

- **Domain rule** (entry contains a dot, e.g. `redgifs.com`) → blocks that site + subdomains
  - Dismiss → that subdomain added permanently + domain strike (3/day → whole domain)
- **Keyword rule** (no dot, e.g. `wolf`) → blocks pages whose **title** matches
- **Blocked browser** (package in blocklist, e.g. Chrome/Firefox) → whole app covered; reopening re-covers instantly
- **Runtime-detected browsers** → auto-added to that same browser block (DuckDuckGo & Firefox Focus stay allowed)
- **App-screen guard** → Firefox Focus stealth/privacy settings blocked (can't screenshot-blind the app); **cannot** be "allowed for session"

## ⚙️ Other behaviours Claude spotted in the code

- **Whitelist never captured/scored:** system UI, settings, launchers, dialer, installers, your own app — and any app while a block cover is already showing
- **"Report" button** = false-positive escape: dismisses with **no block and no strike** (the only safe valve)
- **"Leave" button** = Back, Back, Home (can't force-swipe an app off — no Android API for it)
- **Stale-detection guard:** if you've switched apps before the (delayed) score lands, the block is dropped rather than cover the wrong app
- **Block only shows on the app it was detected on** (frames now carry their source app)
- **Timed/midnight windows expire, but strikes never reset** — so "forever" stays reachable

One inconsistency worth flagging: **dismissing a web image-block via Go back/Leave is permanent immediately**, but a **plain domain/keyword block** only escalates to permanent on dismiss *after* striking — slightly different strictness between the two web paths. Want me to align them?


====================================


# WHAT THE APP DOES
- Monitors text: 
    - Monitors searches / screen textual content, regardless of the app
    - Montors urls , website titles, website content
- Montors the screen itself
    - every few seconds, it monitors the screen.
    - uses a pre-trained algorithm to determine whether there is imagery of a sexual nature on screen
    - if there is, it prevents the user from seeing it.

# Also does;
- Limits user to only one web browser for better control...


# Additional notes:
- Everything is kept on the app, nothing sent away.
    - even works without an internet connection.


# Why existing things don't work:
- either cost too much
    - (very expensive)
- buggy
- send away your data across the internet
- rely on flaky practices in order to do the montoring...
    - which may not work or break...
- simply can't trust them...
    - there is no reason to...

- its not always easy to get an accountability partner...

- text/url based: 
    - e.g. stay focused
        - doesn't work on less-common browsers... (doesn't capture any keywords or urls etc!)
        - doesn't monitor actual screen content... allowing you to bypass...
        - annoying terrable user interface...
            - and lots of wasted features...
    - e.g. bulldog
        - costs a lot...
        - its embarrasing to have an app on your phone that specificall lists itself as a p*** blocker...


# CAVEATS

- Can't get web traffic
    - not really possible to do locally...
    - maybe with a vpn... but that is messy...

- Can't capture screenshots on private mode windows
    - Can't prevent browsers from accessing private mode
    - only a few select apps have no private mode (duckduckgo, firefox focus, etc)
    - its impossible to realistically enable screenshotting in private mode...
    --> uesr must always manually enable this themselves.
    SO:
    - need to limit to a specific browser
        - from well maintained trustworthy browsers...
        only really duckduckgo.com and firefox focus..
        - but with duckduckgo.com, we can't get the full url and page content is also quite awkward...
        - so we use firefox focus instead...

- Safe search:
    - either company (can't do that via app store)
    - or parent controls (awkward for one person)
    - so not really possible...
    - there are no well maintained browsers that have safe search...

- Firefox focus:
    - App must: 
        - Not have private mode
        - expose full urls & page content
    - user can enable 'stealth' mode, which prevents screenshots
    - but we've hardcoded a block to prevent people from accessing that setting...



============

# Breaking the patterns
We have broke down the patterns of addiction...
- Want a Quick dopamine hit...
    - Slow you down...
    - Give alternate, low dopamine hits?!?!?



# what makes us different;
- Let you use your smartphone...
    - You sort of need one in this modern day and age...
- And... they're useful!
    - We came back after admitting that they are useufl...
- you can use your phone without being addicted!
