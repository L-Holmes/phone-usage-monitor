

Tell me what to do in an idiot proof way; before and after of the code that needs changing.

+======

NEXT TODO:

Ask claude to summarise in simple markdown bullet points all of the block conditions that we have discussed...
literally super concise saying hwat the block condition is, and then the punishment..
    - then like a nested bullet point for what happens if the user carries on going...
And add any other things that claude as identified from the code as far as blocking conditions as well...
group by images... 
and then with just text etc... 
and then with images.. group by whether its in a web browser or just a random app..


------
THEN SORT THESE:


when the user presses 'go back' after a website block of any kind
    -> they need a clearer indication for when they've gone back, and they are still on a blocked page.
        -> and then et another indication for when they are on a different blocked page th (not the exact same subdomain) and they are still blocked..
            -> as they may be 20 pages deep in wikipedia, get blocked, then perpetually stuck in a state of not knowing what is going on...
    -> like i know it changes the text from say saying 'dog' block to 'wikipedia.org', but prehaps an even more obvoius indication like gone back, but the page is still blocked, keep pressing back or exit the app'...
    .. and again, some obvious indication that theyve gone to a different page...


Also, in my testing, it will block the subdomain.. but then if i say exit the app / go back to start page, then type in that main domain again, it is not blocked..
    - and it never seems to get blocked permanently even with repeated visits to the (banned dog subdomain)... it should ban the entire domain for like an hour  after like 3 times... 
    ---> OOH! actually, maybe it does, but it only seems to appear once i go on a submain.. (e.g. wikipedia.org


Also, the blocking and the association between the website and the blocked thing is a bit broken...
    - it seemed to block google.com... even though it was wikipedia i was testing with...
    -> please make whatever logic that is much less likely to block the wrong page... 
    again, not sure what happened... but do extra verification checks and estimation etc. 
    do not let the wrong website get blocked!
    - perhaps the block was a bit late, and i went from wikipedia to google and then it blocked google??....

In a similar way, if i was on google images, and searched something which brough up images which then got banned... 
    - it banned the entirety of google.com!?!? 
        (even though every google search has a different subdomain!?!?) and it wasn't like the subdomain got blocked first! it just went straight in with the block.
... same on giphy.com... even though subdomain, the main domain got blocked instead..
    but then i press go back, on the same domain, and its not blocked!


Also, i got around 5 in a row of the 0.5 < x 0.6 scoring things.. and i didn't get blocked out!?!?
    but then i tested and if i get 5 successive in a row it did work...
    Lets cut it down to 3 in a row.
    and also review whetever logic is checking for even if there are two image entires in between which aren't at that level... it needs to be 3 image entries in a row that are all <0.5 for it to break the streak! other wise the counter keeps increasing! and if it gets to 4, it does a block!

Also, even though it said 'google.com domain blocked' after being on google images... i press ' go back', im on the same google image results, and yet i don't get blocked!?!? 


Also i got multiple blocks all within like 20 mins and i didn't get blocked from the domain or the app! come On!!!! what is that logic doing!?!?!
    please review all that logic!!!

    You know what? Please add a scrollable section to the main app that records these things..
        -> Like number of strikes for a given app, and for a given domain... and then identified banned subdomains... perhaps add a button like 'view ban list', which opens that page...


again, it said app blocked, i keep scrolling and then the warning just disappears !?!?!? 

so annoying how i keep getting blocked, press back, and then carry on on google images!?!?

also again, if i ban the word 'dog', but then i'm able to type 'dog' into google images..
    - evne though there is dog in the url, dog on the page titles, dog in the onscreen text which we should be scanning for the banned word...
        and dog in all of the image results names??!??! 

-------


- integrate the whitelisting of common apps....
    - Add more whitelisted apps - think of common ones that are deffo fine..
    (google maps.. waze... messaging apps like whatsapp, facebook (but not ones with reels etc. like insta or snapchat... )) especially ones from 
    (use your knowledge of common safe, non-social media apps and add more!)
    (I think we possbly already do this, just want to extend)
    ideally no screenshotting / processing if we know we're on a whitelist app.. 
    (to save processing etc. for the user!)

- stop screen capture from turning off..
    TODO: I think maybe it happens if i lock my phone? 
    it just seems to turn itself off randomly..
        - i don't even notice it happen!
    --> maybe prevent user from being able to view any app that isn't in the whiltelist 
    - and be able to detect why! (i guess through the adb debugging?)
    - yeah i think its if i turn the screen off (just to sleep, not off) then it turns it off..
        - but i want it to restart! it needs to!


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
