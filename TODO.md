

TODO NEXT:
- capture extra data;
    - led down;
        - use the gyroscope to determine if they're led down, looking at phone? (i.e. either on their back, or more likely on their side??!?!)
        - want another flag in the mode / strictness settings for whether they're likely led down or not..
    - hmmmm
        - do we ask them... or their phone.. to detect like light levels in the room?
            - could we auto capture that, and log as one of the variables?
        - want a flag for the light level threshold... but mapped to an enum like 'dark', 'dull', normal, bright...
    (if you could research and set pre-existing thresholds.. for like light levels and whether they're led down, based off your exising knowledge / the research / open source projects...
     --> then maybe have an additional debug page accessible from the sexual urge settings page... for debugging,, that just shows the gyro angle, and a green 'led down' if it thinks they are..
     --> and the same for hte light levels... it shows the number then the equivalent enum...
    - yeah add this to the dev page...
- enforce greyscale if in strict mode?
    - GREYSCALE FILTERS! (ESPECIALLY AT HIGH-RISK TIMES, IF NOT ALL THE TIME!!!)
- make all greylist apps requirre the breath in orb thing..? (including app store etc?)
- LINK UP HTE PLUGIN
- dopamine baseline masurement
    TODO- bulk this idea out...
    - user sees scale over time
    - can click to see how to lower the threshold.
- exnted the ucrrent landing screen; 
    - represent time wasted in other ways
        - money made in that time..
        - social connections...
        - health etc...
        (and vice versa- if they are improving, show what they are ptoentially gaining back (although of course will be more time focused...))


MANUAL TODO:
- move across my 'stay focused' blocks
- block all app store vpns...
- add the holiday stuff? / guideance?
   - The holiday plan: go away → flip on "lock strict for a week" (#2) → when back, out-of-the-house, see-people, social-club stuff (loneliness is a real driver, so this earns its place).
   - Coping-rehearsal scenarios can live here too.
   - Need to keep themselves busy!
   - No tracking needed on this — they'll know where they're at.

------------------------------

TODO LATER:

- add ads??
- FINISH THE PLUGIN + integrate plugin enforcement...

- keyword integration for plugin?? (When deciding whether to block pictures or not...)

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


==========
JUST GENUINE QUESTIONS;
- (maybe make the temptation log locked behind a passcode?)


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
