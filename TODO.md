


Resume this session with:
claude --resume ecd479f3-4e19-492a-88f0-7d4a2476b670



----

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



Notes from local testing:
- If I type a url in the search bar, and as part of the auto correct of the browser app has it (e.g. m.youtube.com), the block screen comes up.
    - i.e. When the thing I'm using loads things in from other pages... I don't want it to be blocked until I actually visit that page... 
    -> I only want that to happen for if the actual page is loaded!
    -> Same as if say I open a page, which then loads in a provider (i.e. i.reddit.com)... it will block that page!
    --> Also, I wouldn't want just all of i.reddit.com to be banned, only the specific subdomain..
    - the thing is i don't want to have to store the screenshots though... so we'll have to do some text solution...
    - sometimes we will want to block an entire domain, like redgifs.com ... but ideally only when the person actually visits that website...


- also add an easy way to clear the browsing history as it builds up really fast.
    - also have an ignore list for that- create it yourself..
    - things like comsec.android.app.launcher ... 

- also automatically clear things older than say 10 mins for now...
    - as we don't need more than that for testing purposes...
    - (but obviously link that to some sort of debug or IS_PRODUCTION flag... or IS_TESTING (probs best, yeah lets do IS_TESTING)) 


- also do you think we need specific integration for like chromium based or firefox based browsers? so we can see the specific url and page contents etc? (or can that be done via screen reader etc?) or youtube / common social media apps with search?

- also quick test:
    - type in 'wolf wikipedia' in google search
    - click on the wikipedia page
    - go back
    - go to our app
        --> I don't really even know which entry is actually the right one! illl assume its en.wikipedia.org -> wolf - wikipedia
            - but then wikipedia is blocked! when it should be that specific page! 
            - but then after a second, the page unblocks and i can use it as usual? like i didn't even click anything and im on the app... and i can visit the wolf page... 
            - so the block doesn't seem to persist.. and then i can click on wolf wikipedia all i want....
        - the block thing should persist whilst im on the blocked page..
        - and it should only block the specific page... not the whole website...

Lets not overly worry about being perfect at this stage, lets just ensure we don't overblock and that the blocking itself actually works...


---------

New notes from local testing:
- when I type in 'wolf' to the block list, and then visit the wolf wikipedia page, it says 'Blocked \n en.wikipedia.org \n ...' surely it should say it blocked on 'wolf' right?
    - it seems to function fine though, and allow me to use the rest of wikipedia... so... just the text issue I guess...
- also i added the wikipedia spider man page url to the blocklist, but that obviously didn't work.
    - im not sure what youve done? beaus ein the logs we dont have a url so how are you planning to block by url?
    - it just says en.wikipedia.org \n Spider-Man - Wikipedia... 
    - please don't just make things up.. you know we'd need a specific integration to get the urls.. either do that, or stop lying about it!
    -> well.. i mean it seems to work with top level? like 'apps.apple.com' as that actually matches what we have in our logs... 
    - seems to work with reddit.com etc... but yeah will this be consistent for any website?

- it doesn't seem to work when private browing in firefox?
    - all the screenshots are blank...  / black.
    - also, it seems to record only screenshots... it doesn't seem to get any actual websites back...
    - THIS IS A BIG PROBLEM!
    
- also, if i have a tab that is blocked, and i go on tab view, the block screen come sup even though ive not clickedon the tab!
    - so i can't close it and then carry on browsing!


- also with the go back button, there is an annoying delay where i clicked, but then it doesn't instantl go back so im able to click again in that time but then i go back towo pages.
    - now, its good to keep going back pages so i can escape from deep i na website back to google search, but this delay is annoying me! add some fix. either to the delay, or if that not possible some inidcation to me or something.. or even a seperate button.. like keep going back...


If things aren't possible. be honest. and record that clearly in simple bullet points in the readme for future dev reference.
- If we need specific integration to get urls then we do that if its possible, or just accpet it isn't! im fine with that!
- also add to the readme a list o fwhat we can detect vi
    - screenshots and limitations (ideally bypass privvate mode!)
    - can we always gte page titles? or just top level domain teec?
