
------------------------------------------------------------------------------------

Have issue with firefox focus... as the user acan enable blocking of screenshots... via the 'stealth mode' enable which is a toggle in the settings...

OOh!
by the way ive already switched back to using firefox focus as the main web browser and updated the code for that.


can we also, in the logs, remove 'url: ' from being shown before every entry? just list the title...

could we block it when this entry is detected:


org.mozilla.focus
package: org.mozilla.focus
url: (none)
domain: (none)
title: Privacy & Security
content/dump:
Privacy & Security
Cookies and Site Data
Block cookies
....
Stealth
...

(for ai 
 I'm thinking maybe we match on entry name (org.mozilla.focus) and the title (Privacy & Security), and then perhaps if we're keen that the content / dump contains 'stealth'
the thing is... I clicked on that entry myself to add to the block list... but then the block popup doesn't seem to show on the app... so maybe have a look at the functionality there... 

------------------------------------------------------------------------------------

Hmmm.. would it be possible to just vibe code up my own firefox based browser?
- similar to zen...
- except;

Perhaps:
- Prevent history clearing
- automatically block unsafe things
- etc.


----


Next steps:
- do i ask for a summary of what we've done?    
    - or at least more detailed description of why we haven't gone for certain approaches...
    - and a summary of what data we can or can't capture using the current system...
- App whitelist (spotify, google maps, etc)
- ensure we are monitoring web traffic? 

Then:
- some way to actually block websites on the blacklist...
    - load in some banned sexual content blacklist from somewhere...
do i close the app? 
do i do something like what 'stay focused' app does?





-------


Clear history


Start cutting down results 


Add my guide pictures for setup. 

----------

RULES:
If naughty thing on screen -> show the block page.
If naughty thing on screen, and we know the specific website subdomain.
    (and it must have at least one slash. e.g. example.com not blocked..    example.com/something... blocked...
    (and it isn't a search like google or duckduckgo for instance...)
If its on a known app / app base (chromium / firefox / youtube / tiktok)
    - try and have specific integration
    - get the exact urls if possible
    - get the search terms and text on screen as well...

----------

------------
private mode BS options:
- Limit to only a bunch of whitelisted web browsers
    - Guide user on how to update the permissions on those browsers to allow for screenshots...
- make my own browser which is either chromium or firefox based etc. which doesn't allow private mode...
- try and get the image/video urls from the browser... again, by targetting them..
- 


really... I've got two options:


1)
Are there any browser apps for android that allow you to prevent private mode from being used, and then some way in my app to then prevent the user from changing that setting once its setup?
2) 
Is there any way that the user can say be limited to just one web search app, which then they can set the setting to allow screenshots in private, and then after they come back to my app, they click say 'confirm, ive enabled screenshots in private mode', and then my app will then be able to prevent them from changing it back?


Hmmm...
perhaps we lock the user to using firefox focus??
    -> It has no private mode!

--------------------


BACKUP:
-------
OR detect if they open a private window, and block(!)
if the screenshot is black...  (just use some pixel check rather than an api or image reader... which returns true or false..
and we know they are using a web browser... (not sure how we'd determine this without a hardcoded whitelist!)
and then the next screenshot is also total black ...
But i guess maybe we are still getting the screen reader info through...
then we assume that they are browsing in private mode, and then block saying 'private mode is disabled'..?
-------


--------------------

NEXT TASK:
- I've heard rumours that we can't reliably get the page content or url... but what about accessibility features that screen readers and things for blind people use?
    (I don't want to use a vpn or rely on external things that might break...)
    - or other apps like ever accountable etc? or stay focused... surely there is a way we can fetch things using that can't we? please investigate and update readme acordingly if not...

please if you get the blind person screen reading url thing working, add those to our log list as well...

think: 
- for duckduckgo.
- aim:
    - get the url (ideal)
    - or the page title
    - or the page readable content... 
    - or the image/video urls (again, optimistic, but would be great)
-... and add those entries to our log list...

Just do what you can. 


NEXT NEXT:
also it seems screen capture keeps turning off after a while... not sure why..?
    -also sometime i click start, do share all... and then it doesn't even seem to start? but only sometimes?!? like it still says screen capture off..

------
do myself:
- where on earth is the debounce setting? Please decrease to 0.7s... and add a very minimal loading indication... I did rg "0.8" and rg "debounce" and found nothing? i want to be able to know wher eit is so i can manually update it...


---------


want to improve the go back functionality.
    -> its super slow...
    -> I don't know what page I'm going back to or even if like 


    ------

    is there a test we can do on new apps, to check whether they are a web browser or not? because what ever current methods are in place in my app don't work.
    e.g. seekly.
    think what web browsers can do and what data our app wold have avaialble to it and what tests it could viabily perform in order to determine whether a new app was a web browser...
