


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
