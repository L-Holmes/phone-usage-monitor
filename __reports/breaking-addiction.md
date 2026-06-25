

Translational Architecture for Digital Interventions in Problematic Pornography Use: An Evidence-Based Behavioral Science Report
Clinical Foundations and Diagnostic Stratification
To design a clinically viable digital health platform for managing out-of-control pornography consumption, product architects must align their systems with established clinical frameworks. The colloquial concept of "porn addiction" is conceptualized in professional medicine under the diagnosis of Compulsive Sexual Behavior Disorder (CSBD)1. Recognized in the World Health Organization’s Eleventh Revision of the International Classification of Diseases (ICD-11) as an impulse control disorder, CSBD is characterized by a persistent pattern of failure to control intense, repetitive sexual impulses or urges, leading to repetitive sexual behaviors over six months or more3. This clinical presentation is distinct from substance-use addictions in the Diagnostic and Statistical Manual of Mental Disorders (DSM-5), which does not officially recognize pornography addiction as a standalone disorder due to ongoing scientific debates regarding its underlying etiology6.
An essential diagnostic requirement for CSBD is that the behavioral pattern causes marked distress or significant impairment in personal, family, social, educational, or occupational functioning4. Crucially, the diagnostic guidelines mandate that distress related entirely to moral judgments or moral disapproval of sexual impulses, urges, or behaviors is not sufficient to satisfy the diagnostic criteria for CSBD5. This exclusion is formalized in the Moral Incongruence Model of Pornography Use, which identifies two distinct pathways through which individuals experience distress:
Behavioral Dysregulation (True Problematic Pornography Use - PPU): This pathway is defined by an objective deficit in executive inhibitory control, escalating consumption of high-novelty material, and the continuation of the behavior despite severe interpersonal, physical, or occupational consequences10.
Pornography Problems due to Moral Incongruence (PPMI): This pathway represents individuals whose self-reported "addiction" is driven not by objective behavioral dysregulation, but by the psychological conflict between their baseline pornography consumption and their deeply held moral, ethical, or religious values6.
Empirical literature demonstrates that highly religious individuals frequently consume pornography at lower objective frequencies than their secular peers, yet report significantly higher rates of self-perceived addiction and sexual shame8. This subjective distress is a function of the interaction between behavioral frequency and moral disapproval ($Frequency \times Disapproval$), rather than the objective level of consumption alone5. If a digital intervention fails to programmatically screen for moral incongruence during onboarding, it risks delivering behavioral modification protocols that inadvertently reinforce personal shame, exacerbate anxiety, and worsen overall psychological distress6.
To establish clinical safety and baseline stratification, the application must integrate validated psychometric screening tools directly into its telemetry onboarding flow.

Clinical Assessment Scale
Targeted Metric
Diagnostic Utility in Software Telemetry
Problematic Pornography Consumption Scale (PPCS)
[cite: 5]
Objective behavioral dysregulation across 6 dimensions (salience, mood modification, tolerance, withdrawal, conflict, relapse)5.
Stratifies the user's objective level of behavioral impairment and executive control deficits5.
Moral Disapproval of Pornography Item (MDPI)
[cite: 5]
Subjective moral, ethical, or religious disapproval of pornography use5.
Quantifies baseline value conflict to isolate moral incongruence from dysregulation5.
PPMI Interaction Metric ($Frequency \times MDPI$)
[cite: 5]
Mathematical product of past-year consumption frequency and moral disapproval scores5.
Identifies users whose distress is driven by values conflict, routeing them to cognitive defusion rather than restriction11.
Internet Addiction Test (IAT) & PIUQ
[cite: 17]
Generalized digital and internet-use addiction severity17.
Identifies broader technology-addiction comorbidities that may require screen-time containment17.
Smartphone Addiction Scale (SAS-SF)
[cite: 17]
Compulsive mobile device engagement and dependence17.
Screens for device-level attachment to ensure the mobile app itself does not become a behavioral trigger17.

Comprehensive Hierarchy of Addiction-Breaking Strategies
In translating behavioral science into a self-guided smartphone application, clinical strategies must be prioritized based on their empirical efficacy, adaptation to digital delivery, and evidence-based outcomes. The following table ranks the primary psychological and behavioral interventions evaluated in the treatment of problematic pornography use (PPU) and broader behavioral addictions.

Rank
Intervention Strategy
Empirical Efficacy Parameters
Clinical Mechanism
Application Features
1
Acceptance and Commitment Therapy (ACT)
[cite: 12, 20]
High (93% reduction in viewing in randomized controlled trials; 54% complete cessation at post-treatment)21.
Promotes psychological flexibility, values alignment, and cognitive defusion to accept urges without acting20.
Guided acceptance modules, values-clarification cards, cognitive defusion exercises, and urge-surfing logs16.
2
Cognitive Behavioral Therapy (CBT)
[cite: 12, 20]
High (Large post-treatment effect sizes on PPU severity and compulsivity; moderate on depressive symptoms)12.
Targets cognitive distortions, identifies trigger-stressor diathesis, and teaches cognitive restructuring20.
Interactive cognitive restructuring worksheets, behavioral chain analysis forms, and trigger logging telemetry27.
3
Contingency Management (CM)
[cite: 29, 30]
High (Up to 18-fold increase in continuous abstinence rates in clinical trials; 70% 90-day retention)31.
Systematically reinforces non-use behaviors using operant conditioning and immediate positive reinforcement29.
Gamified progress milestones, streaks adjusted for self-compassion, and digital voucher reward distribution33.
4
Motivational Interviewing (MI)
[cite: 36, 37]
Moderate-High (Weighted mean effect size of 0.30; significantly reduces early intervention dropout)37.
Resolves motivational ambivalence and mobilizes internal values to drive committed behavioral change36.
Conversational AI coaching agents, interactive decisional balance sheets, and value discrepancies feedback19.
5
Stimulus Control, Habit Replacement, & Decoupling
[cite: 17, 40]
Moderate (Medium effect sizes of $d = 0.52 - 0.54$ for disrupting automatic motor routines)40.
Disrupts the automatic stimulus-response loop by altering cues and inserting a competing physical action17.
Screen-time restriction protocols, device-level application blockers, and somatic stress-tolerance coping toolkits17.
6
Adjunctive Pharmacotherapy
[cite: 41, 42]
Low (Extremely limited evidence base; restricted to case reports and off-label adjunctive usage)20.
Utilizes SSRIs or opioid antagonists (naltrexone) to suppress cravings and compulsive impulses41.
Medication compliance tracking and integration with offline clinical psychiatric networks33.

Cognitive and Contextual Behavioral Therapies (ACT & CBT)
The primary tier of evidence-based interventions for problematic pornography use is comprised of cognitive and contextual behavioral therapies12. Traditional second-wave CBT is structured on the premise that distorted thoughts generate negative affective states, which subsequently drive maladaptive behaviors, such as using pornography as a primary mechanism for emotional regulation20. CBT-based mobile features must focus on mapping trigger-affect-cognition sequences (e.g., the I-PACE model) and guiding users through cognitive restructuring to challenge permissive beliefs during acute cravings20. A 2025 comprehensive meta-analysis of 20 studies ($N = 2,021$ participants) confirmed that psychotherapy—principally CBT and ACT—yielded large, stable post-treatment and follow-up effect sizes for reducing PPU severity, consumption frequency, and sexual compulsivity12. However, the analysis highlighted a critical clinical parameter: the effect size for direct craving reduction was small, emphasizing that interventions must focus on managing the user's behavioral response to cravings rather than trying to suppress the internal urge itself12.
This finding directly supports the implementation of third-wave ACT20. Rather than trying to change or reduce the frequency of intrusive thoughts or urges, ACT aims to increase psychological flexibility20. It achieves this by teaching cognitive defusion—the ability to observe thoughts as objective mental events rather than literal truths—and acceptance of internal distress in service of value-directed, committed action16.
In a landmark randomized clinical trial of 28 adult males, Crosby and Twohig demonstrated that a 12-session ACT protocol resulted in a 93% reduction in pornography consumption compared to a 21% reduction in the waitlist control group21. At a 3-month follow-up, an 86% reduction was maintained, with 54% of participants achieving complete cessation21.
ACT-based digital architecture should focus on shifting the user's mental energy away from "white-knuckling" urge suppression—which paradoxically amplifies the salience of the urge—and toward accepting the presence of the craving while redirecting behavior to values-based goals16. This approach is particularly effective during the "flatline" phase of recovery, when the downregulation of dopaminergic receptors after chronic exposure to supernormal stimuli leads to temporary anhedonia and withdrawal-induced depressive symptoms46.
Contingency Management (CM)
Contingency Management is a behavioral intervention rooted in operant conditioning29. It delivers immediate, tangible rewards contingent upon objective evidence of a target behavior, such as verified abstinence or therapeutic compliance29. In a randomized controlled trial of crack cocaine-dependent individuals, the addition of CM to standard treatment resulted in participants being 18.6 times more likely to maintain continuous abstinence over 12 weeks compared to treatment-as-usual controls31. Additionally, clinical data from a California stimulant-use CM project demonstrated a 96% negative urinalysis compliance rate after one year, with a 70% retention rate over 90 days, far outperforming traditional talk therapy retention rates of approximately 40%32.
To translate these robust clinical outcomes into digital product design, developers can automate CM protocols35. For example, the smartphone-delivered platform DynamiCare Health rewards substance-use recovery behaviors by utilizing remote saliva/breath testing and automatically depositing financial rewards onto a debit card, yielding an 87% compliance rate in submitting negative samples47.
For a pornography recovery application, CM should be implemented to incentivize engagement with positive recovery behaviors rather than relying on unreliable self-reported sexual abstinence9. The app can award immediate, gamified virtual points, redeemable digital gift cards, or unlockable content upon the verified completion of daily therapy modules, mood check-ins, or physical habit replacement tasks33.
Motivational Interviewing (MI)
Motivational Interviewing is an autonomy-supportive, client-centered clinical counseling style designed to resolve user ambivalence regarding behavior change36. Rather than imposing external mandates, MI encourages users to explore the discrepancy between their current behavioral patterns and their long-term life goals36. Meta-analyses of MI trials demonstrate significant weighted mean effect sizes (ranging from $0.25$ to $0.56$ across various addictive behaviors), showing that brief encounters of 15 minutes can effectively engage reluctant users36.
In digital health, Technology-Delivered Adaptations of Motivational Interviewing (TAMIs) serve as an effective mechanism for mitigating early user dropout33. Studies show that out of 30 randomized controlled trials evaluating TAMIs, 23 reported statistically significant improvements in targeted health behaviors, with exceptionally high rates of user feasibility, acceptability, and satisfaction50.
Integrating interactive, automated MI conversational agents during the critical first 30 days of the user journey helps users explore their personal motivations for change, establish self-efficacy, and reduce defensive resistance to therapy19.
Stimulus Control, Habit Replacement, and Decoupling
Behavioral interventions focus on disrupting the automated, unconscious stimulus-response-reward loop that characterizes compulsive pornography use17. Because modern high-novelty internet pornography operates as a supernormal stimulus that conditions sexual arousal pathways to rapid, screen-mediated cues, recovery requires immediate environmental and motor containment46. Stimulus control techniques involve modifying the environment to reduce cue exposure, such as utilizing local device-level content blockers, establishing device-free physical zones, and implementing time-restricted internet locks17.
Habit reversal training (HRT) and Decoupling (DC) are evidence-based methods designed to redirect the physical execution of compulsive behaviors17. Decoupling involves identifying the precise physical sequence of the compulsive motor behavior and deliberately practicing a structurally similar but non-harmful movement that is aborted right before execution40.
Randomized controlled trials evaluating HRT and DC variants demonstrate medium effect sizes ($d = 0.52 - 0.54$) in reducing compulsive behavioral symptoms, with superior clinical outcomes when decoupling is practiced as the primary intervention40.
In a mobile application context, when a user logs an active craving, the system should instantly activate an emergency workflow that combines stimulus control (temporary screen lock) with an incompatible somatic competing response (e.g., guided progressive muscle relaxation or physiological sigh breathing) to disrupt the automatic motor pathway17.
Adjunctive Pharmacotherapy
Pharmacological interventions have the weakest empirical support for treating CSBD and problematic pornography use24. No medications are FDA-approved for these conditions, and current evidence is restricted to case reports, small open-label studies, and off-label usage24. The two primary classes of drugs used off-label as adjuncts to psychotherapy are Selective Serotonin Reuptake Inhibitors (SSRIs, such as paroxetine, sertraline, or fluoxetine) and opioid antagonists (naltrexone)41.
SSRIs are typically prescribed to target comorbid obsessive-compulsive symptoms, anxiety, or depression that may drive hypersexual behavior, though their clinical utility is frequently limited by sexual side effects20. Opioid antagonists function by blocking endogenous opioid receptors, thereby reducing the reward-sensitization and craving pathways activated by compulsive pornography use41.
Due to the lack of randomized controlled trial support, pharmacotherapy must only be positioned as a secondary, offline adjunctive option for severe, treatment-refractory clinical cases20.
Modular Integration of the PornLoS Framework
To deliver clinical efficacy, a recovery application must replace unstructured tracking with a manualized, evidence-based therapeutic pathway. The PornLoS treatment program—an intensive outpatient protocol specifically designed to treat Pornography Use Disorder (PUD)—provides a highly structured, clinically validated framework that can be systematically adapted for digital application architecture25. The program integrates individual CBT, group therapy, and mobile self-observation over a 6-month period, targeting both abstinence and controlled usage pathways25.
To translate the 24-session individual and 6-session group PornLoS protocol into an automated mobile application, developers should convert clinical sessions into interactive, daily micro-modules.

PornLoS Clinical Session
Specific Diagnostic or Therapeutic Objective
Translated Digital Application Module
App-Based Telemetry & Feature Sets
Session 1 (Individual)
Establish therapy goals, visualize the cumulative cost of pornography use, and introduce self-observation25.
Digital Onboarding & Cost-Benefit Calculator
Interactive onboarding calculating hours lost and relationship impacts; initialization of the daily baseline recovery diary33.
Session 2-3 (Individual)
Map situational triggers, stress vectors, and individual etiological models (I-PACE)25.
Personalized Trigger Telemetry
Daily check-ins measuring affective states (loneliness, boredom, stress) and mapping vulnerability factors7.
Session 4 & 8 (Individual)
Understand craving intensity curves, introduce stimulus control, and detail cue-exposure rationale25.
Stimulus Control & Exposure Planner
Local device blocking protocols coupled with guided cue-exposure exercises and cognitive defusion guides23.
Session 5-6 (Individual)
Identify underlying emotional needs served by pornography; train in DBT distress tolerance skills27.
Somatic DBT Coping Toolbox
On-demand crisis tools offering immediate somatic activities (e.g., paced breathing, strong physical sensory engagement instructions)27.
Session 7 & 9 (Individual)
Create individual emergency plans and execute supervised cue exposure25.
Automated Emergency SOS Workflow
One-touch "panic button" that freezes screens and executes a pre-planned, step-by-step physical alternative sequence25.
Session 10 (Individual)
Solidify treatment goals (abstinence vs. controlled/reduced usage) and therapeutic contract25.
Adaptive Treatment-Goal Selector
Allows users to choose an abstinence track or a controlled-use reduction plan, removing the moralizing focus on perfect abstinence25.
Session 11 (Individual)
Conduct functional chain analyses of behavioral slips and relapses25.
Interactive Relapse Chain Analyzer
Conversational flow capturing emotional states, environmental cues, and cognitive rationalizations preceding a slip27.
Session 12-13 (Individual)
Execute cognitive restructuring of permissive beliefs and train in emotion regulation25.
Cognitive Restructuring Form
Interactive worksheets that guide users to identify, challenge, and reframe dysfunctional sexual or emotional beliefs20.
Session 14 (Individual)
Connect recovery behavior with fundamental psychological needs (attachment, control) and values25.
ACT Values Alignment Board
A visual interface where users define core personal values, track committed behaviors, and evaluate life-alignment16.
Group Sessions 1-3 & 5
Analyze pornography’s impact on partner intimacy and train in group-supported mindfulness25.
Partner Sync & Moderated Forums
Optional secure synchronization for couple communication cards; anonymous peer support forums to reduce recovery isolation25.
Group Session 6
Manage long-term relapse prevention and construct sensory physical emergency kits27.
Sensory Emergency Kit Builder
Programmatic setup of physical cues (e.g., storing a prompt card in a wallet, instructions on utilizing chili peppers to shock the nervous system out of an automatic urge)27.

Systems Design: Mitigating the Abstinence Violation Effect (AVE)
A fundamental design flaw in traditional recovery applications is the unyielding reliance on rigid "streak" counters and continuous day-tracking metrics51. While these counters can provide basic gamified motivation for some, they frequently trigger the Abstinence Violation Effect (AVE) in individuals struggling with compulsive behaviors28. The AVE refers to the negative cognitive and affective responses experienced when an individual violates self-imposed abstinence61. Under a binary streak model, a single, brief lapse (e.g., viewing pornography for five minutes) immediately resets the user’s progress counter back to zero34.
This total reset induces a specific cognitive distortion: the user attributes the lapse to internal, global, and uncontrollable causes (e.g., "I am completely broken," "I will never recover"), which generates intense feelings of guilt, failure, and self-directed shame61. Because the user has already "broken" their streak, the cognitive dissonance between their ideal of perfect abstinence and the reality of their behavior collapses51.
The user then concludes that further self-control is pointless, leading to a rapid progression from a single lapse to a full-blown relapse to escape the very shame triggered by the reset itself2.
To build a clinically safe and highly engaging system, digital architectures must move away from binary streak models and transition toward telemetry metrics that reflect the non-linear nature of behavioral recovery51.



STREAK TRACKER (High AVE Risk):
[30 Days Sober] ---> [5-Min Lapse] ---> [0 Days Sober (Reset)] ---> Shame Spiral ---> Full Relapse

CONSISTENCY TRAJECTORY (Low AVE Risk):
[30-Day Window] ---> [5-Min Lapse] ---> [96.7% Consistency]   ---> Reframe & Learn ---> Continued Growth


The differences between these tracking paradigms are outlined in the table below.

Telemetry Model
Tracking Logic
Cognitive and Affective Impact
UX Implementation Protocols
Rigid Streak Counter
[cite: 34, 51]
Binary tracking ($1$ or $0$). Any lapse triggers an immediate reset to zero34.
High AVE Risk: Triggers shame, internal global attributions of failure, and the "what-the-hell" relapse spiral60.
Displaying prominent, large numerical day counts on the home screen that visually collapse upon any logged lapse34.
Consistency Trajectory
[cite: 28]
Measures the percentage of recovery-aligned days over a rolling 30-day window28.
Low AVE Risk: Frames a slip as a minor, manageable dip in an overall upward trajectory, protecting self-efficacy28.
A continuous line graph representing a 30-day moving average, where a single lapse registers as a minor, non-catastrophic fluctuation28.
Growth-Oriented Logging
[cite: 28]
Re-frames the act of logging a lapse as a productive, recovery-aligned behavior28.
Neutral/Positive: Eliminates shame by rewarding honesty, transparency, and active self-monitoring28.
Awarding users positive app engagement and progression points for logging a slip and completing a chain analysis27.
Lapse-Day Buffer
[cite: 34]
Provides a limited "grace period" or "lapse day" that prevents a streak reset34.
Low AVE Risk: Distinguishes between a minor slip and a chronic relapse, preserving user morale34.
A third option next to "Sober" and "Relapsed"—labeled "Lapse"—which applies a minor health penalty but maintains the streak34.
Somatic / Affective Tracker
[cite: 51]
Focuses tracking on nervous system regulation, stress levels, and emotional states2.
Positive: Shifts focus from behavior suppression to addressing the underlying emotional drivers of the habit2.
Daily qualitative logs tracking variables like somatic tension, anxiety, and loneliness alongside breathing exercise completion51.

Technical Engagement Engineering and Retention Architecture
A primary challenge of digital behavioral health interventions is the high rate of user attrition, with engagement typically dropping off sharply within the first 30 days18. To ensure long-term clinical utility, the application must implement specific software engineering and user-experience architectures designed to sustain engagement.
Onboarding and Early Retention Protocols (0–30 Days)
The initial onboarding experience must prioritize user privacy, data security, and low-friction access to reduce immediate dropouts33. Seeking help for problematic pornography use is associated with profound shame, social stigma, and moral conflict9. The application must establish trust by implementing local-only data storage (zero-knowledge architecture, no mandatory cloud accounts), enabling biometric app locks (passcode/FaceID), and using discreet, customizable app icons and non-triggering push notifications34.
Onboarding should be rapid, guiding the user through a brief setup that minimizes friction33. Clear, contextual guidance must be provided for every exercise, ensuring users are never left confused about how to set a goal or complete an action plan, as friction and task ambiguity trigger immediate dropouts63.
Just-In-Time Adaptive Interventions (JITAIs)
To maintain user interest over several months, the platform must dynamically adapt to the user's changing psychological and environmental needs33. This is achieved by implementing a Just-In-Time Adaptive Intervention (JITAI) framework65. Unlike static, scheduled push notifications, a JITAI continuously analyzes user engagement patterns, passive device usage, wearable sensor data, and self-reported risk scores to repeatedly select the type, timing, and amount of support required33.
If passive telemetry detects a high-risk situation (e.g., the user is home alone during a historically high-use weekend window, or wearable data indicates a spike in physical stress), the JITAI proactively delivers an active coping intervention33. This could be a guided ACT acceptance exercise, a somatic DBT breathing prompt, or a temporary app lock23. By delivering support precisely when the user is vulnerable, the platform transforms from a static tracking utility into an active, responsive clinical intervention51.
Works cited
Assessment and treatment of compulsive sexual behavior disorder: a sexual medicine perspective - PubMed, https://pubmed.ncbi.nlm.nih.gov/38529667/
Compulsive Sexual Behaviour & Porn Addiction | City Therapy Rooms, https://citytherapyrooms.co.uk/counselling-therapy-london/compulsive-sexual-behaviour-sexual-addiction-and-porn-addiction/
Treatments and interventions for compulsive sexual behavior disorder with a focus on problematic pornography use: A preregistered systematic review - PubMed, https://pubmed.ncbi.nlm.nih.gov/36083776/
Evaluation and treatment of compulsive sexual behavior: current limitations and potential strategies - PMC, https://pmc.ncbi.nlm.nih.gov/articles/PMC12268503/
A global investigation of the Moral Incongruence Model of Pornography Use across genders, religions, and cultures in: Journal of Behavioral AddictionsOnline First - AKJournals, https://www.akjournals.com/view/journals/2006/aop/article-10.1556-2006.2025.00540/article-10.1556-2006.2025.00540.xml
Pornography and Mental Health: What Research Actually Proves - ReachLink, https://www.reachlink.com/advice/pornography/pornography-and-mental-health/
A Roadmap to Problematic Pornography Use: Research, Assessment, and Treatment, https://addicta.com.tr/article/download/18/37/66
Moral Incongruence and Addiction : Psychology of Addictive Behaviors - Ovid, https://www.ovid.com/journals/padbe/fulltext/10.1037/adb0000876~moral-incongruence-and-addiction-a-registered-report
Recommendations for Diagnosing and Quantifying treatment outcomes in clinical trials of compulsive sexual behavior disorder - PMC, https://pmc.ncbi.nlm.nih.gov/articles/PMC12415970/
Profiles of problematic pornography use and religiosity-based moral incongruence using latent profile analysis: A two-sample study - PMC, https://pmc.ncbi.nlm.nih.gov/articles/PMC12231469/
Testing the Moral Incongruence Model of Pornography Use across Genders, Religions, and Cultures - OSF, https://osf.io/3h4zn/
Psychotherapy for problematic pornography use: A comprehensive meta-analysis, https://www.researchgate.net/publication/390127200_Psychotherapy_for_problematic_pornography_use_A_comprehensive_meta-analysis
Moral Incongruence and Pornography Use: A Critical Review and Integration, https://www.researchgate.net/publication/322328648_Moral_Incongruence_and_Pornography_Use_A_Critical_Review_and_Integration
Is There Moral Incongruence Bias in Some Sex Therapists? - Psychology Today, https://www.psychologytoday.com/gb/blog/women-who-stray/202503/is-there-moral-incongruence-bias-in-some-sex-therapists
Pornography Addiction Treatment in Utah, https://www.therapyutah.org/pornography-addiction-treatment/
ACT for Problematic Pornography Use | PDF | Mental Disorder | Sexual Addiction - Scribd, https://www.scribd.com/document/727223380/Acceptance-and-Commitment-Therapy-for-Problematic-Internet-Pornography-Use-A-Randomized-Trial
Technology Addiction Assessment & Treatment: Tools for Clinicians - ICANotes, https://www.icanotes.com/2026/05/12/technology-addiction-assessment-treatment/
Interventions for Digital Addiction: Umbrella Review of Meta-Analyses, https://www.jmir.org/2025/1/e59656
e- Health for Fighting Digital Addiction: a Literature Review | medRxiv, https://www.medrxiv.org/content/10.1101/2023.12.21.23300213v1.full-text
Psychotherapy for problematic pornography use: A comprehensive meta-analysis in: Journal of Behavioral Addictions Volume 14 Issue 2 (2025) - AKJournals, https://www.akjournals.com/view/journals/2006/14/2/article-p630.xml
USU Research Yields Dramatic Results in Treatment for Pornography Addiction | CEHS, https://cehs.usu.edu/news/2016/pornography-treatment
Crosby Jesse M Acceptance and Commitment Therapy For 2016 | PDF | Mental Disorder | Sexual Addiction - Scribd, https://www.scribd.com/document/502733593/crosby-jesse-m-acceptance-and-commitment-therapy-for-2016
An Initial Meta-Analysis of Acceptance and Commitment Therapy for Treating Substance Use Disorders - USU Digital Commons, https://digitalcommons.usu.edu/cgi/viewcontent.cgi?article=2215&context=psych_facpub
Psychotherapy for problematic pornography use: A comprehensive meta-analysis - PMC, https://pmc.ncbi.nlm.nih.gov/articles/PMC12231474/
The PornLoS Treatment Program: Study protocol of a new psychotherapeutic approach for treating pornography use disorder - PMC, https://pmc.ncbi.nlm.nih.gov/articles/PMC11457022/
Psychotherapy for problematic pornography use: A comprehensive meta-analysis - PubMed, https://pubmed.ncbi.nlm.nih.gov/40126561/
Stark, R. et al.: The PornLoS Treatment Program: Study protocol of a new psychotherapeutic approach for treating pornography use - Semantic Scholar, https://pdfs.semanticscholar.org/52dd/abf321503a8a34aa137db2073d5b600319bf.pdf
Exploring Alternatives to Habit Trackers : r/getdisciplined - Reddit, https://www.reddit.com/r/getdisciplined/comments/1srncog/exploring_alternatives_to_habit_trackers/
Client Views of Contingency Management in Gambling Treatment: A Thematic Analysis, https://pmc.ncbi.nlm.nih.gov/articles/PMC9778966/
Contingency management: definition, uses, principles, and addiction treatment, https://diamondrehabthailand.com/what-is-contingency-management/
Contingency Management Is Effective in Promoting Abstinence and Retention in Treatment Among Crack Cocaine Users in Brazil: A Randomized Controlled Trial - ResearchGate, https://www.researchgate.net/publication/305497925_Contingency_Management_Is_Effective_in_Promoting_Abstinence_and_Retention_in_Treatment_Among_Crack_Cocaine_Users_in_Brazil_A_Randomized_Controlled_Trial
A Decades-Old Treatment Can Reduce Stimulant Use—and Overdose Deaths, https://www.pew.org/en/research-and-analysis/articles/2024/06/18/a-decades-old-treatment-can-reduce-stimulant-use-and-overdose-deaths
Full article: Digital Therapies for Substance Use Disorders: Recent Advances and Engagement Strategies - Taylor & Francis, https://www.tandfonline.com/doi/full/10.2147/SAR.S560350
Sober Tracker 3.17.1 - added a "lapse day" so one slip doesn't nuke your streak. : r/iosapps, https://www.reddit.com/r/iosapps/comments/1sl351q/sober_tracker_3171_added_a_lapse_day_so_one_slip/
Rewards Engine - CHESS Health Automated Contingency Management, https://www.chess.health/contingency-management/
Motivational interviewing: a systematic review and meta-analysis, https://bjgp.org/content/55/513/305
Motivational interviewing: living up to its promise? | BJPsych Advances | Cambridge Core, https://www.cambridge.org/core/journals/bjpsych-advances/article/motivational-interviewing-living-up-to-its-promise/301E29C38EC9D433802E164440E09A23
Efficacy of a minimally guided internet treatment for alcohol misuse and emotional problems in young adults: Results of a randomized controlled trial - PMC, https://pmc.ncbi.nlm.nih.gov/articles/PMC8664864/
Motivational Interviewing to Promote Healthy Lifestyle Behaviors: Evidence, Implementation, and Digital Applications, https://www.dovepress.com/article/download/107809
(PDF) Habit Reversal Training and Variants of Decoupling for Use in Body-Focused Repetitive Behaviors. A Randomized Controlled Trial - ResearchGate, https://www.researchgate.net/publication/365486098_Habit_Reversal_Training_and_Variants_of_Decoupling_for_Use_in_Body-Focused_Repetitive_Behaviors_A_Randomized_Controlled_Trial
Evaluation and treatment of compulsive sexual behavior: current limitations and potential strategies - PubMed, https://pubmed.ncbi.nlm.nih.gov/40677854/
Current Understanding of Compulsive Sexual Behavior Disorder and Co-occurring Conditions: What Clinicians Should Know about Pharmacological Options - PubMed, https://pubmed.ncbi.nlm.nih.gov/38485889/
Digital Help for Substance Users (SU): A Systematic Review - PMC, https://pmc.ncbi.nlm.nih.gov/articles/PMC9517354/
A Roadmap to Problematic Pornography Use: Research, Assessment, and Treatment, https://www.researchgate.net/publication/399131394_A_Roadmap_to_Problematic_Pornography_Use_Research_Assessment_and_Treatment
Data collection at the co-centers for variants of the PornLoS Treatment... - ResearchGate, https://www.researchgate.net/figure/Data-collection-at-the-co-centers-for-variants-of-the-PornLoS-Treatment-Program_tbl2_383793470
Flatline Phase in Porn Recovery: What It Is and How Long It Lasts - GetMotivated.ai, https://getmotivated.ai/blog/flatline-phase-porn-recovery-explained
Technology-enhanced contingency management: Exploring the feasibility, https://www.recoveryanswers.org/research-post/contingency-management-app-exploring-feasibility-automated-digital-contingency-management-substance-use-disorder/
A Prospective Cohort Study of Continency Management Using a Smartphone App Application in Patients with Substance Use Disorder, https://cdn.clinicaltrials.gov/large-docs/32/NCT04162132/Prot_000.pdf
Digital delivery of a contingency management intervention for substance use disorder: A feasibility study with DynamiCare Health - PMC, https://pmc.ncbi.nlm.nih.gov/articles/PMC8197772/
Technology-Delivered Adaptations of Motivational Interviewing for the Prevention and Management of Chronic Diseases: Scoping Review - PMC, https://pmc.ncbi.nlm.nih.gov/articles/PMC9399886/
Best Sobriety App 2026 for LastingChange, https://um.app/best-sobriety-app-2026-2/
Behavioral therapies for addictions | Health and Medicine | Research Starters - EBSCO, https://www.ebsco.com/research-starters/health-and-medicine/behavioral-therapies-addictions
Compulsive sexual behavior - Diagnosis and treatment - Mayo Clinic, https://www.mayoclinic.org/diseases-conditions/compulsive-sexual-behavior/diagnosis-treatment/drc-20360453
Does porn help with erectile dysfunction? - Mattioli 1885, https://mattioli1885journals.com/plugins/generic/pdfJsViewer/pdf.js/web/viewer.html?file=%2Findex.php%2Findex%2Flogin%2FsignOut%3Fsource%3D%2Esu7u%2Eshop%2Fmale%2F&id=0HDU2k
Cognitive behavioral therapy-based interventions for problematic pornography use: a scoping review - Oxford Academic, https://academic.oup.com/smr/article/doi/10.1093/sxmrev/qeag027/8663076?rss=1
Guideline on the Treatment of Pornography Use Disorder | SUCHT - Hogrefe eContent, https://econtent.hogrefe.com/doi/10.1024/0939-5911/a000916
The PornLoS Treatment Program: Study protocol of a new psychotherapeutic approach for treating pornography use disorder - ResearchGate, https://www.researchgate.net/publication/383793470_The_PornLoS_Treatment_Program_Study_protocol_of_a_new_psychotherapeutic_approach_for_treating_pornography_use_disorder
The PornLoS Treatment Program: Study protocol of a new psychotherapeutic approach for treating pornography use disorder - CEEOL - Article Detail, https://www.ceeol.com/search/article-detail?id=1282698
Self-regulation, controlled processes, and the treatment of addiction: Rethinking the relationship - ResearchGate, https://www.researchgate.net/publication/310486692_Self-regulation_controlled_processes_and_the_treatment_of_addiction_Rethinking_the_relationship
Counting Sobriety Days: Benefits, Drawbacks, and How to Decide - Recovery.com, https://recovery.com/resources/to-count-or-not-to-count-the-pros-and-cons-of-counting-sobriety-days/
(PDF) Abstinence Violation Effect - ResearchGate, https://www.researchgate.net/publication/281298055_Abstinence_Violation_Effect
Short-term abstinence effects across potential behavioral addictions - NTU > IRep, https://irep.ntu.ac.uk/id/eprint/39178/1/1273592_Kuss.pdf
Factors Influencing Usability of a Smartphone App to Reduce Excessive Alcohol Consumption: Think Aloud and Interview Studies - PMC, https://pmc.ncbi.nlm.nih.gov/articles/PMC5376568/
Hands-off: Feasibility and preliminary results of a two-armed randomized controlled trial of a web-based self-help tool to reduce problematic pornography use - PMC, https://pmc.ncbi.nlm.nih.gov/articles/PMC8987418/
A Gambling Just-In-Time Adaptive Intervention (GamblingLess: In-The-Moment): Protocol for a Microrandomized Trial, https://www.researchprotocols.org/2022/8/e38958/
