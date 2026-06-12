
readme:


Phone testing:
# first build stages the 329 MB model (slower); later builds skip it
./gradlew :app:assembleDebug   
# or adb install -r the apk
./deploy.sh                     
# watch model load + any errors
adb logcat -s NsfwClassifier ScreenCapture


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
