
------------------
------------------
------------------
------------------

Task:
- want to add more entries to the 'temptations' page...
here are my ideas for what to add;

```
list.addView(homeCard("Endless Scrolling / Brain Rot 🤳", "Break the infinite feed loop.") {
        /*
         * Overview:
         * This section tackles the modern problem of endless, low-effort content consumption.
         *
         * Covers:
         * - TikTok, Instagram Reels, YouTube Shorts, Reddit feeds, forums, and other infinite scroll platforms
         * - The "just one more scroll" loop
         * - Passive consumption that fragments attention and leaves users feeling mentally drained
         * - Short-form content designed around constant novelty and dopamine seeking
         * - The feeling of losing time while consuming content without intention
         */
        reportBackTarget = { showTemptationsTab() }; showReportScreen()
    })

    list.addView(homeCard("Binge Watching 📺", "Escape loops through endless viewing.") {
        /*
         * Overview:
         * This section tackles long-form passive entertainment consumption.
         *
         * Covers:
         * - Netflix, YouTube videos, streaming platforms, and autoplay loops
         * - Watching for hours longer than intended
         * - Using shows/videos as an escape from boredom, discomfort, stress, or difficult tasks
         * - The difference between intentional entertainment and losing control of viewing time
         * - Autoplay and recommendation systems encouraging continued consumption
         */
        reportBackTarget = { showTemptationsTab() }; showReportScreen()
    })

    list.addView(homeCard("Social Comparison / Social Media 👥", "Break the comparison and validation loop.") {
        /*
         * Overview:
         * This section tackles the psychological effects of comparing yourself to others online.
         *
         * Covers:
         * - Seeing friends, acquaintances, influencers, and peers presenting curated versions of their lives
         * - FOMO (fear of missing out)
         * - Seeking validation through likes, comments, views, and social approval
         * - Comparing achievements, appearance, lifestyle, and status against others
         * - Feeling behind, inadequate, or anxious after consuming social media content
         */
        reportBackTarget = { showTemptationsTab() }; showReportScreen()
    })

    list.addView(homeCard("Phone Checking 📱", "Stop the automatic checking habit.") {
        /*
         * Overview:
         * This section tackles compulsive phone checking and unconscious device habits.
         *
         * Covers:
         * - Unlocking the phone repeatedly without a clear purpose
         * - Checking notifications, messages, and apps out of habit
         * - Picking up the phone during moments of boredom or discomfort
         * - The automatic reflex of reaching for the phone throughout the day
         * - Rebuilding awareness and control over phone usage
         */
        reportBackTarget = { showTemptationsTab() }; showReportScreen()
    })

    list.addView(homeCard("News Cycles / Existential Anxiety 📰", "Step away from endless worry.") {
        /*
         * Overview:
         * This section tackles unhealthy news consumption and anxiety caused by constant exposure to world events.
         *
         * Covers:
         * - Obsessively checking news updates throughout the day
         * - Consuming negative stories repeatedly without taking meaningful action
         * - Feeling overwhelmed by global events, politics, disasters, and problems outside personal control
         * - The cycle of fear, uncertainty, checking, and temporary relief
         * - Separating being informed from being consumed by information
         */
        reportBackTarget = { showTemptationsTab() }; showReportScreen()
    })

    list.addView(homeCard("Gaming & Reward Loops 🎮", "Understand digital reward cycles.") {
        /*
         * Overview:
         * This section tackles gaming habits driven by reward systems and progression loops.
         *
         * Covers:
         * - Games designed around achievements, unlocks, streaks, levels, and rewards
         * - Chasing progress and dopamine hits through virtual goals
         * - Playing longer than intended
         * - Using gaming to avoid boredom, stress, or responsibilities
         * - Recognising when entertainment becomes a compulsive reward loop
         */
        reportBackTarget = { showTemptationsTab() }; showReportScreen()
    })

    list.addView(homeCard("Impulse Shopping 💳", "Break the buying-for-a-feeling loop.") {
        /*
         * Overview:
         * This section tackles online shopping driven by impulse and instant gratification.
         *
         * Covers:
         * - Amazon, Temu, discount sites, ads, and constant product exposure
         * - Buying because of boredom, excitement, stress, or the feeling of getting a deal
         * - Limited-time offers and recommendation systems encouraging unnecessary purchases
         * - The dopamine hit of ordering and receiving packages
         * - Building more intentional spending habits
         */
        reportBackTarget = { showTemptationsTab() }; showReportScreen()
    })


```

obviously for each, we'll want to wire in functions specific to each of the new pages..
then create the pages (e.g. a bit like the sexual arousal page.. but much simpler) for each... keep them more basic though- think what would actually be helpful. We don't want to overwhelm them or give too many options.
(I can always add more later...)


TODO NEXT:
- add N slip to list of rules...

DONE - breathing animation
    - Auto-start: was hung off a single OnGlobalLayoutListener, and driven by ValueAnimator -
      which completes INSTANTLY when the system animator duration scale is 0 (battery saver,
      "Remove animations", Developer Options). That is the "sometimes doesn't start" bug. Now
      driven by Choreographer + our own clock, started from two independent triggers.
    - Backup: "If nothing happens, tap to enter" flashes on entry; a 1.5s watchdog re-shows it
      and lets a tap through if the orb genuinely never moved, so you can never be trapped.
    - Fills the screen again: BreathOrbView takes an OrbFill - COVER for the big overlay
      (reaches the corners), INSCRIBE (unchanged) for the boxed in-app arousal pages.
    - Lag: the orb was allocating a new RadialGradient every single frame. It is now built
      once per resize and scaled with a matrix, and the loop runs at ~30fps not 60.
    - 2FA: breathing is now FIRST OPEN PER APP PER DAY (BreathingGate), so tabbing to an
      authenticator no longer re-gates you. Super hardcore breathes on every open.

    !! NOT DONE - "if they swipe off / close the app, it is then reset".
       Android gives an accessibility service no dependable "app was swiped away" signal.
       Backgrounded-but-alive apps have no visible window, and a cold-start heuristic is
       useless for the apps we gate (Fenix is single-activity, so resume and fresh launch
       look identical). Options: leave the daily pass as-is, or take PACKAGE_USAGE_STATS
       and watch for ACTIVITY_DESTROYED, then call BreathingGate.reset(pkg) - that is the
       only hook needed. See the KNOWN GAP note on BreathingGate in UserState.kt.

- when in strict mode, make it so that the user cannot use any non-whitelisted apps when lying down or if they are in the dark, as determined by out existing sensor data.
    - Also, add them to our log when the user fails / slips / is detected to be looking at bad conent (i.e. a page block or self reported etc). so that we can see the trends in that data as well..
    - (and of course in our summary / graph tables add those as a graph and in the summary say that 'the light level is usually..' and 'your screen rotation is usually (upright / led down)


- dopamine baseline masurement
    TODO- bulk this idea out...
    --> How is the dopamine baseline calculated?
        --> time spent... (obvoiusly weight this higher than the others!)
            == worst ===
            - adult content (what our app blocks)
            - tiktok / youtube shorts / insta reels / ... fast paced video content (have seperate functions for like 'is_on_fast_paced_video' so we can update it as time goes on seperately.., same for all these, if we could detect these things off their seperate apps or from the websites that would be ideal!)
            - social media, fast paced ones...: snapchat / etc
            - live things.. like twitch etc.
            - reddit / forums/ news etc.
            - long form video content (youtube)
            - ??? (you fill in the rest)
            - ... impulse / instant gratitfication.. like amazon.. temu.. door dash.. uber eats.. etc. 
            - ... gambling..
            - ... mobile gaming.. 
            == least worst ==

            have an algorithm where if they spend like x amount of time, they get 'y' multiplier... lets have the max at like if you spend 4 hours or more, then they get the max multiplier...
            sort of linear before then.. perhaps slightly curved to punish higher amounts more... 
        --> Phone unlocks per hour... 
        --> time of day (late night particularly bad... then early morning as soon as wake.. mid of day not as bad)
        --> "Urgent Open" Metric (velocity_to_open): Did they unlock their phone and immediately open TikTok within 2 seconds?
        --> High checks of a particular app per hour... (e.g. opening snapchat 50 times in an hour..)
        - less interactions = generally better... not constrnatly scrolling or tapping screen..
        - delay between the wakeup (e.g. an alarm going off- is that possible to detect?) and using apps. e.g. any of the tiktok / snapchat / reddit /youtube video immediately after waking is very bad!
        - use led down is quite bad (we already capture the sensor data for that)
        - use in the dark is quite bad as well (we already capture the sensor data for that)

        - put the algorithm in a really obvious place with obvoius text description so manual intervention can be done by devs!
        -----
        then have 'anti rules' (these are obviously much less affective and take a lot longer to 'decrease' the dopamine baseline...
        - time spent with screen off, no media playing... 
        ----
        - display this as a line chart over time.
        - also as like a vertical scale which says the percentile in, then says like good, bad, very poor, etc.
    - optional manual input
        (don't use this to count towards the score... or perhaps have a seperate section that clearly says this is an estimate of your dopamine baseline based of user inputted habits...)
        * **Screen-Free Reflection / Mind-Wandering** (Yoga, walking alone, sitting doing nothing, meditation, staring out a window)
        - light / anerobic excercise (like walking etc)
        * **Intense Physical Training** (Weightlifting, running, sports, high-intensity workouts)
        - making money.. / working on business / planning on how business / side income etc. / **High-Leverage Building** (Working towards a future business, strategic planning, building projects, financial management) / - Working towards future goals (business, career, money, planning, learning)
        * **Active Creation** (Writing, painting, playing an instrument, cooking, woodworking)
        - other - Deep work / focused work
        * **Deep Offline Focus** (Reading a physical book, studying complex topics, deep work without a screen)
        * **In-Person Socializing** (Spending quality time with family, friends, or partners without phones)
        * **Restorative Sleep / Deep Rest** (NOT naps, non-sleep deep rest protocols)
        - diet / consumables... ## Health Inputs Healthy eating Caffeine Alcohol Other substanc
    - can click a button with guidence on how see how to lower the threshold.
        - then have a seperate section that is specifically targetted towards their data...


- exnted the ucrrent landing screen / general productivity related things:; 
    - represent time wasted in other ways
        - money made in that time..
        - social connections...
        - health etc...
        (and vice versa- if they are improving, show what they are ptoentially gaining back (although of course will be more time focused...))
    - then have additional things like inputting hourly wage... or how much they make an hour on their 'side hustle'... 
        (also we add our own average hourly wage there instead... for calculations)
        - this will be in an optional 'about you' section... where it says 'we use this data to estimate what you are missing out on via your productivity...

- There are likely other paths to clear history in Firefox that won't hit these exact strings — e.g. "Clear browsing data on quit" in settings, deleting a single history entry via long-press, or per-site "Delete" from the history list. This blocks the main "Delete browsing data" flow you captured. If you monitor another path, send me its title/content dump and I'll add the keywords.

LATER:
- LINK UP HTE PLUGIN
    - once hte plugin ready..
        - integrate, and search for the specific text that shows on screen when the plgin blocks a page- so we can detect plugin detections(!)



MANUAL TODO:
- move across my 'stay focused' blocks
- block all app store vpns...
- add the holiday stuff? / guideance?
   - The holiday plan: go away → flip on "lock strict for a week" (#2) → when back, out-of-the-house, see-people, social-club stuff (loneliness is a real driver, so this earns its place).
   - Coping-rehearsal scenarios can live here too.
   - Need to keep themselves busy!
   - No tracking needed on this — they'll know where they're at.



------------------------------

---

I'll also want a flagged side list of things that are "attracted to women" only .. (like  so women can shop for lingerie etc.) 


----





Record activity before after after a block comes up... 
(E.g. minus 5 mins and plus 10 minutes around the block?) 
- then log that specific thing to a list of "break reports"
(Obviously if there's multiple in a short time... Then only log one..)
(E.g. within the minus 5 plus 10 minutes range...) 


------------------------------

TODO LATER:

- add ads??
- FINISH THE PLUGIN + integrate plugin enforcement...

------------


OOOH!
Like Strava... And other mapping apps.. 
Can we determine where they are on a map?
--> then if they're out of the house.. they can't doom scroll.. 
--> but more importantly.. if they're out of the house.. we don't have to worry about them with adult content. 
(BUT! Will this be affected by VPNs??) 

-------


In the productivity side.. 
+ "How you compare to the average person".. as far as productivity etc... 


====
Add 'founder mode'
- locks you out of non crucial apps in morning till you fill in the questionnaire..

(Same with food tracking??? 
- separate app?)
(Same app for ease?)

======
Still thinking I might have a super duper strict mode... 
Where I block people out of all non whitelisted apps until they re-enable the screen monitoring... 


======
You know the things where we have true or false to toggle depending on the mode (relaxed or strict)?

Can we integrate the gyroscope into that? 
And the light sensor? 

So if gyroscope is active:
- definitely go more strict
- (have a super strict mode?)
- yeah I think turn on super strict mode... (Don't allow any non whitelisted apps??) 

====================================================================


- Donors have no adds.
- we give extra founder mode help to them...
- Pay to..
    - remove search history(?)
    - remove search history... anonymously (without going through us..)?
    - ????
    - have priority when unblockng a part of the phone?


======================


-----
- Money, adverts
- need some sort of feedback system
- is it too bias towards straight men?
(Do I add a mode for prectijg people attracted to males?)
(How about lesbians who are searching for underwear? Or scrolling through vinted?)

- how do I solve the issue of people seeing sexual content in social media?
- --> have some sort of "time on screen" of a sensual image?
- or frequency / number of these sensual images in a short amount of time??

^^^^^^^^^^^
Perhaps the above could be "down the line" things to add later.... 




=======================================================================


-------


DONT DO SOFTCORE mode...
    - thats just wrong..
    - if they're about to go.. 
    - just only block super strict things.. but maybe turn off certain things so that they can access stuff?!?!?!


MAKE THEM WAIT 5 MINUTES! (OR 10... or 20... or 30! and then ask again- like food eating addiction theory...)



-------
????

Then even perhaps a seperate config / file(s) for like preference things like all of the feeling potions and loctions and other things from the reports page (be extra careful with this one as i know htey are linked to specific emojis as well!)


---------


NEXT TODOS:
- integrate the whitelisting of trusted domains
- Add my own custom words blocklist(?)   (seperate from the main list.. somewhere obvious in the code for me to edit.. like a constant at the top...)
    - for strict mode...
    - like bkni etc.

------------------------------------------------------------------------------------


# way down the line:
Add my guide pictures for setup.  (enabling things in the settings initially...)
consider switching chrome as the main browser...

hmmm;
    - take image.
    - extract the subject (can that be done fast?) (i.e. remove the background)...
    - then determine if contains humans, based of pixel colours...



beacons:
- beacon in bedroom
- beacon next to door where user puts phone...
    Even as simple as: "if you're outside of your home, its unlikely you'll be tempted"
    or... If you want to look at stuff... leave your house... simple as that(!)
- prevent phone from being used in bed?!?!? (or say.. in the toilet etc/!!?!?)


# =============
# MY IDEAAS!!!!::
# =============
- financial
    - they put 20 pounds into our account.
    - every time they pass a day (week? to give us more money?), they get a pound back.
    --> don't penalize them.. only reward them..
- Location
    - prevent internet device from high risk areas (bedroom etc)
    -> will need the beacon for this...
    - Also;
        - buy seperate alarm clock
        - No charging in bedroom

- some guideance / products that help with;...  
    - boredom
    - stress
    - loneliness...
- OOH
    - reduce in stages;
        - sessions per day...
        - session length (keep updating them how long they have..)
        - 
- trusted person for the unlock passcodes
    - but what if they don't have a 'trusted person'?
    - ... I guess we are their 'trusted person'...
    --> If they asked a trusted person...
        - they'd probably give an excuse
        - the trusted person would probably give it to them anyway...
    - problem is, its easier for them to just request the code from us...
    - and we aren't going to make them call.. we don't want to have to handle calls..
    - perhaps;  
        - they request.
        - give an excuse
        - pass excuse into AI...
        - if the excuse can be handled without giving full unlock privelleges...
        - then have specific sub functions for that?
        (Perhaps;
            - prepare a list of common excuses...
            - or common reasons why they'd want to unlock the app...
            - then have settings ready in the app, ready for those types of 
            - PERHAPS
                - as a lock code, we have the base (666666), and then maybe a device ID that they can find in their settings somewhere??
        (perhaps;
            - give 5 mins if its desperate? surely that's enough for most things?!?!?
            - (and as a one off- they'll have to contact us...
            - OOH! or make them pay us to have a one off?
- ???
    - physical device lockbox??
    - USB data blockers!?!?!?
- OOOOH 
    - WHAT IF THEY ARE SEARCHING IN OTHER LANGUAGES?!??!?
- oooh
    - could we have an excercise device... which much be completed in order to then unlock the phone or whatever???
- OOOHWHH
    - could we have a wearable that can accurately detect arrousal? surely not...
- ..
    - determine how long theyve been scrolling / on a single url for?!??!?!
    - / on a single website for?
- hmmm
    - no access until micro goal (like 2000 steps... etc?)
        - dont know if we'd need a wearable for that..?
- hmmm...
    - randomly show popup of something that they wouldnt want to look at whilst... _distracted_...
        - siblings.. same gender people... etc?
- make them watch video of themself.. or people close to them!??!
    - before committing???
- hmmmm
    - perhaps we can lock down if we detect they're using a vpn?
    - OR even if they're using data?
    - because if we know home wifi... then we know where they are..
        - (home wifi ip may change though!)



================
- maybe the user will want to have their phone on holiday for like google maps and what not..
    - so maybe block web browsing, only allow google maps, waze... maybe spotify? (maybe I let them choose?)


---------------------
## general productivity related;

App monitors angle of phone for slouching... Asks you to not slouch... 

App maybe asks you to look outside / take a break for a bit (e.g. in like 10 mins time... (Unless you're working!?!?) Or only block non essential apps?? 


==========
JUST GENUINE QUESTIONS;
- (maybe make the temptation log locked behind a passcode?)


=======================================
ADVERTISING


- have to let your boyfriend/child/friend choose for themselves to use it.
- you can't force it upon anyone!

Sell the benefits.. let them choose..


================================

Defo down theline;
- the 'pay myself back' system.
- the 'which room I'm in' system.

could add different features / variations if i know location... e.g.;
    - phone becomes grayscale in bedroom
    - social apps blocked at desk
    - YouTube disabled after entering bathroom too long
    - “focus mode” when sitting at work setup

================
Questions for user testing:
- Is the 'complete lock out' good for really addicted users?
    - How restricted should it be? (e.g.allow for only static images etc?)
    - I-> i dont want to go too far and have them bypass my app you see!
