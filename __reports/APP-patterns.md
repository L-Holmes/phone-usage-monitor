
("is it happy moments or sad moments?") assumes the trigger is *emotional*. The survey data agrees with you — stress, boredom, loneliness. But the experimental data in here quietly contradicts that. The "habitual decoupling paradox" shows that in heavier, more habituated users — exactly the people who most need your app — the behaviour has gone *automatic and cue-bound*. It's triggered by context (time of day, being alone, phone in hand, a specific room) rather than a conscious feeling, and in lab studies a negative mood sometimes *reduced* craving rather than driving it.

The design implication is big: an app that leans hard on "what are you feeling right now?" will generate noise and miss the real predictors, which are *temporal and contextual*. So the most translatable patterns are the structural ones an app can actually detect passively — and emotion becomes a secondary, confirmatory signal rather than the main engine.

Sorted most → least translatable:

- **Time-of-day risk windows — your single highest-yield, cheapest signal.** The nocturnal peak (11pm–1am) is the global high-risk window, with secondary workday spikes (9am, 10am, 4pm) and a notable Monday ~3pm "slump" relapse spike. This needs no sensors — just a clock — and the pattern is robust. Feed it straight into the JITAI from your first doc: pre-emptively deliver a coping prompt, values check, or soft lock *before* the user's known window opens, not after they've already opened a browser. Learn each user's personal peak from their own logged episodes and tighten the timing.

- **Context / "high-risk situation" detection (the decoupling reframe).** The real triggers for habituated users are cues: alone, at home, unstructured time, device within reach. Detectable via time + day-of-week + location (home) + device state, and optionally calendar ("no events for 3 hours on a Saturday night"). When the app detects the *combination* — home, alone, late, weekend — it fires the intervention. This is where your previous "change location / break the routine" idea actually earns its keep: you're disrupting the stimulus-response loop at the cue, before the emotion even shows up.

- **The "alone + unstructured time + no oversight" cluster, especially WFH and Sundays.** Sunday is the weekly traffic peak; remote work is a named driver (31% say lack of oversight influenced their use). These are *predictable opportunity windows*. Let users flag their own (e.g. "I work from home Tues/Thurs," "Sunday afternoons are hard") and have the app proactively scaffold those blocks with structure — a planned activity, a check-in, a competing task.

- **Emotional-state triggers (stress ~45%, boredom ~34%, loneliness) — useful, but secondary and stage-dependent.** Keep mood logging, but treat it as a *learning* input, not the trigger model. It matters most for earlier-stage/impulsive users and the negative-reinforcement pathway. Two nuances worth designing around: **boredom is under-stimulation** (the answer is a competing stimulating activity, which connects to your "replace it" question — not a dopamine substitute, but genuine engagement), whereas **stress is over-arousal** (the answer is downregulation — breathing, somatic tools). Same log, opposite intervention. Loneliness is fully mediated by emotion-regulation difficulty, so route it to connection + regulation skills, not blockers.

- **Recovery-phase awareness (pre-empting the flatline).** This isn't a trigger — it's a predictable *timeline*, which makes it gold for relapse prevention. The flatline (roughly weeks 3–6: anhedonia, zero libido, loss of morning erections) reliably causes panic ("I've broken myself permanently"), and that panic drives relapse. If the app knows where the user is on the curve, it can deliver reassurance and normalising education *exactly* when that fear peaks. This pairs directly with the anti-AVE point from last time: the flatline is the moment your framing either saves the user or loses them.

- **Seasonal / calendar cycles — real but low-priority.** Sex-related searches peak in Dec–Jan and Jun–Jul, with Christmas week the annual high. It's a genuine pattern but modest (≈16% of variance) and slow-moving. Cheap to act on: raise check-in intensity and pre-load content during the user's known high-risk season. Nice-to-have, not a core mechanic.

- **Physiological signals via wearables (stress/cortisol).** Elevated cortisol amplifies the brain's salience for sexual cues, so a wearable stress/HRV spike is a plausible real-time risk flag for the JITAI. Promising, but sensor-dependent and noisier — a v2 enhancement, not an MVP dependency.

- **Demographic risk-stratification at onboarding.** Young males are the highest-risk cohort; earlier age of first exposure predicts adult problems. This isn't a real-time strategy — it's a *segmentation* input that tunes initial risk-scoring and which track someone starts on. Modest, set-and-forget.

- **Comorbidity screening + referral (the safety layer, not an intervention).** Very high overlap with depression (~40%), anxiety (46–96%), ADHD, and alcohol use. The ADHD link is worth a personalization note — those users are chasing stimulation, so the "competing engaging activity" lever matters more for them. But the real job here is a screener that *routes severe cases out* to human help, not something your app treats itself.

- **Escalation / severity phenotype as a risk flag.** Patterns like rapidly increasing session duration or novelty-seeking are markers of habituation and higher severity. Worth detecting as a *severity/escalation signal* that bumps someone to a more intensive track — but keep this clinical and high-level; you want to flag trajectory, not get into specifics.

Two make-or-break notes:

**Lead with passive context, not active mood prompts.** The decoupling paradox is the whole reason most habit-trackers underperform — they ask the user to introspect about an emotion that, for the habituated user, isn't actually driving the episode. Make time + context the primary predictor; use mood as a quiet confirmatory layer the model learns from over time.

**Personalise the windows fast.** Population averages (11pm, Sunday, December) are your *cold-start* defaults. The real value is the app learning each user's own signature — their times, their days, their contexts — within the first couple of weeks, and narrowing the JITAI onto those. The generic version is a blunt instrument; the personalised version is the product.

Want me to fold both of these lists — the intervention strategies from before and these pattern/trigger signals — into a single prioritised build roadmap (MVP → v2), with the JITAI logic spec'd out as the connective tissue between them?


==========
- detect timing
    - 11pm - 1am, highest risk zone
        - perhaps at 11pm... we lock down super hard-- no images at all... restricted usage for non-verified apps...
    - morning; 9am, 10am...
    - work day slump; 3pm, 4pm (will get worse with more work from home!)
- +
    - learn peaks from user's personal logged episodes...
    - add them as the highest priority times...
    - +sundays aparently worst? (and work from home days)
- calendar...
    - Sex-related searches peak in Dec–Jan and Jun–Jul, with Christmas week the annual high.
- detect if home alone?
    - can we do that? 
- hmmm. emotoins not as important..
    - stress 45%.. boredom 34%...
    - reduce stress...? (breathing? therapy?)
    - boredom...? (make them do a dopamine detox...)
- remove novelty??
