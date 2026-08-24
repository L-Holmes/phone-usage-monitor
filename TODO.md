

just simplify pages in general- strip out anything we don't need. reduce complexity...





=======================================================================
=======================================================================
=======================================================================
=======================================================================


hmmm use sensors to also determine if youre in the house?
then could have things that aren't allowed in house...
then somethings just not allowed in bedroom....

=======================================================================
=======================================================================
=======================================================================
=======================================================================

------------------
------------------
------------------

MANUAL TODO:
- add the holiday stuff? / guideance?
   - The holiday plan: go away → flip on "lock strict for a week" (#2) → when back, out-of-the-house, see-people, social-club stuff (loneliness is a real driver, so this earns its place).
has the above already been done?

------------------------------

- is it too bias towards straight men?
    (Do I add a mode for prectijg people attracted to males?)
    (How about lesbians who are searching for underwear? Or scrolling through vinted?)

======

# MONEY MAKING THINGS

- Pay to..
    - remove search history(?)
    - remove search history... anonymously (without going through us..)?
    - ????
    - have priority when unblockng a part of the phone?
    - super hardcore mode?


- superhardcore users have to verify with us before downloading an app that we do not know about?
    - so an app that isn't on the white/grey/black list...

- adverts
- need some sort of feedback system

=======================================================================

# way down the line:
Add my guide pictures for setup.  (enabling things in the settings initially...)

beacons:
- beacon in bedroom
- beacon next to door where user puts phone...
    Even as simple as: "if you're outside of your home, its unlikely you'll be tempted"
    or... If you want to look at stuff... leave your house... simple as that(!)
- prevent phone from being used in bed?!?!? (or say.. in the toilet etc/!!?!?)

=======================================================================
=======================================================================
=======================================================================
=======================================================================
UP TO HERE


# =============
# MY IDEAAS!!!!::
# =============
- financial
    - they put 20 pounds into our account.
    - every time they pass a day (week? to give us more money?), they get a pound back.
    --> don't penalize them.. only reward them..
- trusted person for the unlock passcodes
    (a) They set a passcode (which is done by a trusted person...)
    - perhaps;  
        - they request.
        - give an excuse
        - pass excuse into AI...
            - prepare a list of common excuses...
            - or common reasons why they'd want to unlock the app...
            - then have settings ready in the app, ready for those types of 
        - if the excuse can be handled without giving full unlock privelleges...
            - then only unlock certain parts...
        - for the code itself:
            - as a lock code, we have the base (666666), 
            - and then maybe a device ID that they can find in their settings somewhere??
            (we don't want to store data!)
        (hmmmm perhaps;
            - give 5 mins if its desperate? surely that's enough for most things?!?!?
            - (and as a one off- they'll have to contact us...
            - OOH! or make them pay us to have a one off?
- perhaps we can lock down if we detect they're using a vpn?
- monitor for activities like downloading new apps...
    (??? but then what do we do?)
- extend the 'im going to look anyway'
    - prevent novelty
    - --> let them [initially] pick from pre defined small list... Never add novelty...
- extend the 'holiday mode' concept: 
    - will want like google maps and what not..
    - so maybe block web browsing, only allow google maps, waze... maybe spotify? (maybe I let them choose?)
- extend location knowing to producitity;
    - social apps blocked at desk
    - YouTube disabled after entering bathroom too long
    - “focus mode” when sitting at work setup
- home area (GPS, 50m radius) - DONE: HomeArea.kt + Developer tools -> "Home area (location)".
  tracked all day by HomeAreaWatch inside the accessibility service (so it runs with the
  app closed), published to HomeAreaContext. asks for "Allow all the time".
  still to do:
    - actually enforce it (in home = apps blocked). nothing reads HomeAreaContext yet.
    - real setup flow for end users (not the dev button), + maybe more than one home point
    - away = adult content is a much lower risk -> could relax those checks when out
    - VERIFY ON DEVICE: background location is throttled to a few fixes/hour for apps
      Android considers "background". an app bound as an accessibility service should
      count as foreground, so we should be fine - but check the change log on the dev
      page after a walk with the app shut. if it IS throttled, the fallback is a
      foreground service with a location type (= permanent notification).
    - (VPNs are a non-issue: a VPN changes the IP a website geolocates, not GPS/wifi.
       a mock-location app IS an issue - HomeArea.isMock flags it, nothing acts on it yet)


---------
cost for premium:
$20 or $30

---------


=======================================================================
=======================================================================
=======================================================================
=======================================================================

Defo down theline;
- the 'pay myself back' system.
