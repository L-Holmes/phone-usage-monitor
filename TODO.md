

Resume this session with:
claude --resume ecd479f3-4e19-492a-88f0-7d4a2476b670


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



Next task:
Think how to actually block the user. 
--> in an ideal world: 
Say they're on chrome then visit wikipedia.com and say we detect it needs to be banned, just like give a 404 type page saying website can't be found. So then they have to press the back button to return to the Google search.

Or alternatively show an overlay which says that the screen content has been identified by [our app] as being distracting to purpose. 
Then an option to report incorrect blockage, or ideally a go back button which lets them go back. 

OR alternatively, let them browse the website but block all images / videos from loading. 

I want them to be able to then reopen the chrome app and continue browsing you see... 

E.g. I Google search for "hi" then I Google search for "elephants". If elephants is blocked, I want to be able to then go back on the app... 
I appreciate this may not be possible depending on the app though, so maybe we just block it, and then the user has to like manually swipe off the app to block it... 


---

Maybe pre the system back button once before then blocking? 

For browsers though, must be browser agnostic.. not a hsrdcoded lost... 


---

Will want a way to test that.. 
I'm thinking either you can click on an entry, or have a text box where you can type an entry in (obviously in the future we'll process the results and determine whether block needed...).
You know what the log format is etc. pick what will be best to help you test. 


To jtoe:
- obviously there is a variety of different scenarios so we'll want a variety of tests.
- some we will just have to do the best we can. And that's okay.
- obviously things were the user is likely to be doing other work, like a web browser, we'll want to put as a higher priority to allow them to carry on whatever, whilst still blocking.
- things like a random app... Not so much...
- and of course, maybe some random apps may be difficult and not interact with back button well etc. again, in those cases, happy just having the user swipe off the app to close it, presuming they can then re-access it...

Actually, for yourself you can have a blacklist and use that for testing, but please still add the manual selection or text box for me to manually 
