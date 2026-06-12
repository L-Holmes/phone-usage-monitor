


okay, do you think perhaps we could use the faster version as well? alongside it? Like switch between
  the fast one and the slow one?

And then perhaps if we are really getting rate hit, we can maybe do the fast one say up to every three screenshots? 

Please feel free to do your own testing for the weights with this 'new' model that you speak of...


-----------
Is there any way we can process the images in parallel?
    -> they take ages to come through.
    -> Lets also decrease the frequency of the screenshots slighlty.
        - I mean... obviously the image processing takes like just over a second (well with rust at least!)
            - but the screenshots seem way behind in my testing...
    - again, keep in modern smart phones in mind and think what they can handle...
        - optimise, based on your own knowledge!
    - i don't want to reduce the model itself as i need the accuracy...
    - maybe we need some log or something to know how long its taking... so we can optimise how many screenshots we're taking...

Lets also add a whitelist for apps to not screenshot on if possible...
    - please be sensible:
        - like:
            - settings
            - com.sec.android.app.launcher
            - com.google.android.googlequicksearchbox
            - etc.


here are my logs by the way:
adb logcat -s NsfwClassifier
--------- beginning of main
06-12 13:14:55.628 26666 26716 I NsfwClassifier: staged model -> /data/user/0/com.example.webtrafficmonitor/files/nsfw/model.onnx (344538902 bytes)
06-12 13:14:56.952 26666 26716 I NsfwClassifier: model ready: model.onnx input=pixel_values size=384 threshold=0.11


===========

# THEN
- now add Blocking rules:
    - If we get 8 'close borderlines' in a row (anything between 0.5 and 0.6 inclusive), then we block with reason 'suspicious content detected.. precaution'...
        - (if possible, if we get 1 or 2 dotted around as part of that, then we just ignore those...)
        - e.g. 0.5, 0.4, 0.1, 0.54, 0.52, 0.1, 0.59 etc.. that would be picked up, as if we remove the groups of 1 or 2 'outliers' then we have our pattern.
    - if we get 2 'probably not allowed' in a row (anything between 0.6 < x <= 0.7), then block, with reason 'probable distracting content detected'
    - If we get 1 'clear not allowed' (anything over 0.7) then block. with reason 'clear distracting content detected'


-----------------

TODO AFTER THAT:
- prevent 'report incorrect block' from allowing a blocked app to be unlocked...
- Change the functionality of the report this thing... 
    - just make it just do nothing for now... don't make it unblock an app...

-------------------

I reckon...
- integrate the image adam cod processing...
- test issue with the screenshot taker turning off
    - ensure that always stays on!
- Make it so that the app can't be deleted...(?)
- Add my own custom words blocklist(?)
    - for strict mode...
    - like bkni etc.
- Load in an open source nsfw domains list (blacklist)
    - auto add them all to ban
- Load in an open source nsfw words list (blacklist of words)
    - auto add them all to ban
- Load in an open source nsfw app list 
    - auto add them all to whitelist
- Load in an open source trusted domains list (blacklist)
    - auto add them all to whitelist
- Load in an open source trusted non-sexual app list 
    - auto add them all to whitelist

- integrate the whitelisting....
    - don't care about whats happening.. ideally no screenshotting / processing if we know we're on a whitelist app.. althgouh that may be difficult to determine

------------------------------------------------------------------------------------

Next steps:
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


----------
Hmmmm
Have a feature where if we feel that theyve been looking at borderline stuff for a while..
    - maybe kick them off...
