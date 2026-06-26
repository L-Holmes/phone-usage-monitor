

At some point:
- for relaxed vs strict mode..
    - have some obvious map somewhere.. of all the things our app does in text form... and then each mapped to like a number... 1 or 2 representing relaxed and strict...
    - so then i can easily change just that map, and know what will activate in each mode (we may add more modes in future which is why we are doing this!)




**2. update the strictness levels next — the loosen-flow and the timing engine both need these to exist.**
   - Define modes the rest of the app reads: e.g. normal / strict / super-strict, each doing progressively more (slower image loads → greylist pauses → block unverified apps → no images at all). Your existing slowed-image-loading folds in here as a low rung.
   - Include a one-tap "lock me in strict for a week" mode (you need this for the holiday plan in #9 — once it's on, it shouldn't be easy to flip off).
   - You said you'll define exactly what each level blocks yourself — leaving that to you.

**3. Build the Report screen — this is the core loop. Button on the main screen → screen split into 3 panes, each a big clickable third:**
   - **(a) Report relapse** — quick form: "on this device? y/n", a *private* note on what happened (stays on device, never shown back as judgement), tap-to-pick panels in a scroll list so it's fast. After logging, walk them back through what led to it — where, what time, what they were doing/feeling just before. Logging honestly *earns* progress; it's never punished.
   - **(b) I feel temptation** — one tap, rate the urge, then the app just helps them ride it out (urges come in 15–30 min waves — the job is to give the wave somewhere to break, not to kill it). Needs zero visibility into their screen, which is why it's your cheapest high-value feature.
   - **(c) I'm going to look anyway** (the renamed loosen flow) — full mechanics:
     - Forces them through the app, not another device.
     - First they answer a couple of evidence-based (CBT/ACT) questions — pick from options.
     - Anti-mash: they have to pick the *same option twice in a row* to confirm it, so they can't just hammer random answers to speed through.
     - Anti-muscle-memory: the option buttons change position each time, so they can't autopilot the sequence.
     - Capped number of attempts per day.
     - Then an automatic 5-minute wait before anything unlocks. Offer an *optional* extend — 10 min, an hour, till tomorrow — and make the longer wait the easy, low-effort, encouraged choice (one tap, no friction), while the short path stays the slightly more deliberate one.
     - Then they pick a duration from a short list: 1 / 2 / 5 minutes, with **2 as the default middle option** to nudge them toward the low end.
     - During that window: blocklist (hardcore) stays fully up, greylist pause lifts, image friction stays on as best-effort. Auto re-locks the second the timer's up.
     - The panic button lives in here too — lock screen + a physical action (breathing / cold / sharp sensory). No separate feature.
     - ...
     - + ), you said they admit what they're doing, say "what they'll look at", and say "that they won't do it next time". 

**4. Add a 4th option to that screen: "Report an app/site."**
   - Guided: pick the app from a dropdown or paste the URL 
        → mark it greylist or blocklist.
   - User sets this when calm; the app just honours it later. 
   No content detection, no screenshots. 
   Greylist = apply a pause when opened; blocklist = block outright.

**5. Auto-raise strictness at high-risk times, then learn their personal ones.**
   - Defaults from the research: 
        - hard at 11pm–1am, bumps at 9–10am and the 3–4pm slump, plus Sundays and work-from-home days. 
   - Then the actual product: learn *their* real peaks from their own logged events (#1) 
   and override the generic times, once we have a certain amount of data...
   - Fire the coping prompt *before* the window opens, not after. 
   - Driven off their reported relapses — which is exactly why #1 is first.

**6. Build the progress view — non-resetting, and this is where the reward actually lives.**
   - It's a streak counter that *doesn't* drop to zero. Track rolling consistency (clean days out of the last 30),
       with a "lapse day" buffer so one slip nudges the number down a little instead of wiping it.
       The 30→0 reset is the exact thing that makes people go "well I've blown it" and binge — that's why you avoid it.
   - The reward is real stats they care about, not a tree or a smiley (you're right, those won't move anyone): 
        - time reclaimed, a trend line heading the right way, estimated hours/£ saved per year. 
        - That doubles as your productivity hook.
   - Light milestone text is fine (first week, first holiday survived) *as long as* missing one never resets or "kills" anything.

**7. Build the productivity surface + short-form blocking — the public face and the sell.**
   - Reels/shorts/feed blocking is just another category inside the strictness system from #2.
   - Put the "time wasted per year" stat front and centre. This is what makes it a believable productivity / anti-doomscroll app with the porn side quieter underneath.
   - Your "design a logo" task belongs here — make it look like a normal focus app. Discreet icon, non-triggering notifications. Do it anytime.

**8. Keep onboarding smooth — default to strict, let them change it after.**
   - No screener quiz, per your call. Drop straight in on strict; they can dial it down later if they want.
   - One thing that costs you nothing: keep the supportive content (urge-surf, the acceptance-style stuff) always non-shaming and available, so the people whose problem is shame rather than loss-of-control aren't made worse by the default strictness. That handles the moral-incongruence concern without an annoying quiz.

**9. Recommendations / guidance page — content, slots in last.**
   - The holiday plan: go away → flip on "lock strict for a week" (#2) → when back, out-of-the-house, see-people, social-club stuff (loneliness is a real driver, so this earns its place). Coping-rehearsal scenarios can live here too.
   - No tracking needed on this — they'll know where they're at.



-------

recommendations for the step by step todo:
- go on holiday
- buy things to replace your phone... so you don't need it...
- super strict lock for a week after holiday..
- try and be out of the house as much as possible...
    - spend their money if they have to.. on things that aren't addictive...
    - on seeing friends especially... or at social clubs...

DONT DO SOFTCORE mode...
    - thats just wrong..
    - if they're about to go.. 
    - just only block super strict things.. but maybe turn off certain things so that they can access stuff?!?!?!


MAKE THEM WAIT 5 MINUTES! (OR 10... or 20... or 30! and then ask again- like food eating addiction theory...)



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


NEXT TODOS:
- integrate the whitelisting of trusted domains
- Load in an open source trusted domains list (blacklist)
    - auto add them all to whitelist
- Load in an open source trusted non-sexual app list 
    - auto add them all to whitelist
- Add my own custom words blocklist(?)   (seperate from the main list.. somewhere obvious in the code for me to edit.. like a constant at the top...)
    - for strict mode...
    - like bkni etc.
- greylist of apps? like tiktok?!?!?

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
