

---------

---------

approach:   
    - Is the website on the whitelist?
        -> if yes, allow and don't monitor.
    - if its a search in a browser..
        - is the search conetents trying to get nsfw results?
            - could i test the search myself with google with safe search on and see if the number of nsfw results is greater than 0??? 
                (or some open source google equivalent)
    - Anaylse the url:
        -> if yes, then block the entire website...
    - Analyse the title:
        -> if yes, then block the entire website...
    - analyse the content loosely
        -> try and determine just how nsfw it appears...
    - For each image:
        - Is it a real life nsfw image? 
            -> yes
            -> no
        - Is the image on the community flagged list?
        - show
            - (let community flag up any art etc. that is of a sexual nature)



-------------

Monitor the 

now implement app:
- monitor all internet requests, regardless of what app is being used. 
    - ensure that this is done locally- without the need to rely on external dns / use wifi etc.. 
    - stays on the android device. 
    - ideally a method that is likely to work for many years to come, don't want to over-rely on things that may break.
    - (i.e. only things like urls, images / videos loaded...), I'm monitoring the images / videos being seen, and just a rough idea of the url and website page title etc...)
        - ignore anything else...
- use reliable, well maintained open source (can use with a paid app) packages / pre-existing code, that is super well maintained and reliable and trustworthy.
    - if it doesn't exist, we'll just have to write our own
- show the list of monitored things in a scrollable list in the app 
(we will process this list later)
- no hacks, no root, must be possible for a regular person to enable the phone to do this.. 
- don't break the internet — it has to forward traffic normally so the phone still works while monitoring
- i'm fine with imperfect data. if you can't get the full url or page title because of https, just log the domain/hostname and move on. stability > completeness
- only log the metadata i need 
- needs to be play store friendly.
- use kotlin of course
- if you use open source, check the license works for a paid app (apache2/mit/bsd is fine, avoid gpl if possible). if nothing good exists, just write the small bit ourselves
- keep battery and RAM usage sane...
- code should be simple and readable, i'll be maintaining this later so no clever one-liners

--------------------------------------------------------------------------------------------------------------------------------------------------------------------

=== DEBIAN 13 TESTING ===

Also:
- have optional hardcoded flag in the test.
    - if enabled, it creates json outputs for each model, and their maps, so that I can inspect the results they gave... (defaults to true, the flag that is)

[3]
Combine the models.
- combine them in a way that we get a single 'score' on how 'nsfw' something is...

[4]
Same as [2], but multithreaded (optimise the amount for whatever would work on most modern smartphones... but obviously still keep testing on debian for now- we'll do the switchover later...)

[5]
Quick run - eliminate obvoius NSFW
- Is there anything that runs really quick and finds really obvoius NSFW things? just so we can quickly eliminate any really nsfw?

[6]
Quick run - eliminate obvoius NOT NSFW
- Anything that runs really quick which identifies things that are clearly not nsfw??? (again to quickly cut down on processing time)

[7]


=== ANDROID SWITCHOVER ===


[8]
have fetched images be blank initially whilst our code runs
    - once passed, show the image...                                  


=== OOOH ! ===
- want to also monitor website content for banned words...


=== DOWN THE LINE ===

Hmmm.. possibly in the android app, we have a way for users to report things that weren't caught... 
    --> e.g. like art... 
    --> and then we can try and find a way to block those...

Like a community based thing where you report things...

then we:
- monitor websites that have lots of reports...
- potentially add the flagged images to a training set
    - and get someone to manually tag them... 
    - or possibly even community have like 5 seconds to tag, then it goes off (to stop bad habits...)



-------
whitelist safe websites (again, community backed...)

