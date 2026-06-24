# NEXT STEPS:
- prevent app from being deleted


+======

NEXT TODO:


------
THEN SORT THESE:

-------


- integrate the whitelisting of common apps....
    - Add more whitelisted apps - think of common ones that are deffo fine..
    (google maps.. waze... messaging apps like whatsapp, facebook (but not ones with reels etc. like insta or snapchat... )) especially ones from 
    (use your knowledge of common safe, non-social media apps and add more!)
    (I think we possbly already do this, just want to extend)
    ideally no screenshotting / processing if we know we're on a whitelist app.. 
    (to save processing etc. for the user!)




---------

maybe if its a non-web app..
    - and they end up on the 'forever block'...
    perhaps instead:
maybe make it so if you press back a couple times... then it unblocks the app? (unless you manually add it to the permanat block list...) --> just in case like insta etc.. 


        

-----------------

TODO AFTER THAT:

-------------------

NEXT TODOS:
- test issue with the screenshot taker turning off
    - ensure that always stays on!
- Make it so that the app can't be deleted...(?)
    - settings etc. and then toggling the settings
    - will want a   toggle in our app though to turn this off optionally!
- Load in an open source nsfw domains list (blacklist)
    - auto add them all to ban
- Load in an open source nsfw words list (blacklist of words)
    - auto add them all to ban
- Load in an open source nsfw app list 
    - auto add them all to blacklist

- integrate the whitelisting of trusted domains
- integrate the whitelisting of trusted domains

- Load in an open source trusted domains list (blacklist)
    - auto add them all to whitelist
- Load in an open source trusted non-sexual app list 
    - auto add them all to whitelist


- Add my own custom words blocklist(?)   (seperate from the main list.. somewhere obvious in the code for me to edit.. like a constant at the top...)
    - for strict mode...
    - like bkni etc.

------------------------------------------------------------------------------------



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


--------


# way down the line:
Add my guide pictures for setup.  (enabling things in the settings initially...)
consider switching chrome as the main browser...
