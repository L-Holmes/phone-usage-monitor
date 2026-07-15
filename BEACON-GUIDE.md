# Room beacons (KKM K11) — setup & testing guide

What's built, how the detection works, and the testing walkthrough.

## Round 9 (2026-07-15): strict-mode enforcement is live

The beacons now do their actual job. **RoomGuard** (in `RoomBeacons.kt`) is an
all-day watcher owned by the accessibility service:

- **When it arms**: mode is strict (or super hardcore), at least one room is
  fully set up, and the Bluetooth-scanning permissions are granted. Otherwise it
  idles — no scanning, no battery, no blocking. It re-checks that gate every
  15 s, and scans in low-power balanced mode (not the debug page's low-latency).
- **What it does**: evaluates room presence every 2 s. On a verdict of `true`
  it sets the active room; every non-essential app then gets the block cover
  with a room-specific message ("Protected room: you're in the Bedroom. In
  strict mode only calls, texts and other essentials open here. Step out of the
  Bedroom and everything unlocks."). The essentials whitelist is the night
  guard's: dialer, SMS, contacts, clock/alarm, camera, maps, launcher. Once
  engaged it latches through "maybe (probs is)" — shifting around in bed can't
  flap the cover — and releases when you genuinely leave (probs-not / false),
  at which point the cover clears itself within ~half a second.
- **Fails open**, same doctrine as the night guard: Bluetooth off, permissions
  missing, beacons silent, no calibration → no block. It never locks you out on
  a guess. (Android delivers no unfiltered scan results while the screen is off;
  that's fine — blocking only matters with the screen on, and readings resume a
  second or two after waking.)
- Walking into a protected room mid-scroll is caught without any app switch:
  the guard's own tick re-evaluates the foreground app, mirroring the night
  guard's sensor callback.
- The debug page's scan-health line now shows the guard state: `guard idle
  (needs strict mode + a calibrated room)` / `guard armed` / `guard BLOCKING
  (bedroom)`. The debug page and the guard keep separate hysteresis states
  (they scan independently), so having the page open doesn't fight the guard.
- The in-app "How we determine…" page gained a "What happens in strict mode"
  section.

**To test**: set up the bedroom, switch to Strict on the home page, open any
non-essential app, walk into the bedroom and lie on the bed → cover appears
naming the room within a few seconds; walk out → it clears on its own. Calls
and texts keep working throughout.

## What changed in round 8 (2026-07-14)

- **Stairs regression explained + fixed.** Round 7 dropped the "partner beacon
  outside its learned band → false" hard rule, leaving only distance grading —
  and its 8–12 dB shell graded the stairs as "maybe (probs not)". The hard rule
  is back (any beacon heard at a level never seen from inside the room → false)
  and the ladder is tighter: true ≤ 4 dB from a usage spot, probs-is ≤ 7,
  probs-not ≤ 10, beyond → false.
- **"True while the meter shows yellow" explained + fixed.** Real mismatch: the
  meter bands (±2 dB) were *stricter* than the true-decision tolerance (5 dB),
  so the verdict could fire while a needle sat in amber. Bands and thresholds
  are now aligned (super band = usage readings ±4 = TRUE_DIST), so a true can
  only happen with needles in super green. Not the average — there was no
  averaging in the verdict until now.
- **Recent-average booster (new).** The engine keeps ~10 s of history of the
  match distances. If the recent average is good AND consistent (spread ≤ 4 dB,
  6+ points), it counts as much as the instant value — a solidly-in-the-room
  reading can't be knocked off by one wobble, and a steady borderline reading
  gets credit for its consistency. The "Nearest known spot" bar shows both:
  `in 5 (avg 4) · usage 6 (avg 5) · out 12 dB`.
- **Super green is now visibly dark** (near-solid dark green core inside the
  pale green inside the yellow).
- **Tagging takes 1 second** (was 3).
- **Dual-sensor mode**: a toggle on the Room detection page. When on, each room
  gets TWO sensors at opposite ends; the wizard finds sensor A then sensor B for
  the room, the place step says where each goes, and every recording/match/meter
  simply treats B as one more beacon in the tuple. Rooms need re-setup after
  switching the toggle.

## What changed in round 7 (2026-07-14): fully pairwise + any number of beacons

- **"They come in pairs" is now the whole engine.** Yes — every sample always
  recorded all beacons; what was wrong is that the *gates* were still per-beacon
  ranges. Now the decision itself is pairwise: the distance between "now" and
  any recorded spot is the **worst single-beacon difference**, so a spot only
  matches when EVERY beacon is close to what was recorded there. The verdict
  ladder is entirely distance-based:
  - within 5 dB of a **usage (core) spot** → `true`
  - within 8 dB of anything recorded inside → `maybe (probs is)`
  - within 12 dB → `maybe (probs not)`
  - matches a tagged false spot better than anything inside (3 dB margin), or
    matches nothing inside at all, or the own beacon is silent/out of its amber
    band → `false`
  Your downstairs stats are the test case: (bedroom −72, bathroom −85) vs the
  in-room far-end recording (−72, −76) is a distance of **9 dB** (the bathroom
  disagrees), so it can no longer count as "matching the far end" — even
  *before* any tag exists there. Per-beacon meters remain as visualisation.
  This is also why downstairs kept firing during tagging: the old grader fell
  back to per-beacon bands when outside data was thin. The new ladder never
  falls back to single-beacon logic.
- **Entry walks removed** (steps, recording, pattern debug — all gone). The
  wizard is now: find beacon(s) → place → 6 spots → 15 s room walk → roam & tag.
- **Any number of beacons (2–8).** Rooms are user-defined: an "Add a room…"
  button on the Room detection page (name it, set it up), and Remove room in
  each card's Reset dialog. All matching, meters, wizard and snapshots run over
  the full set of assigned beacons automatically; the wizard only insists on a
  minimum of two in the house.

## What changed in round 6 (2026-07-14, after the downstairs stats)

Your downstairs numbers (bedroom −72 + bathroom −85, vs in-room bedroom
−70…−60 with bathroom ≥ −75) were the key: each value alone sits inside that
beacon's in-room range (the far corners legitimately read that low), so no
per-beacon band can ever separate them. What separates them is the **pair**.

- **Tagging now has real teeth: the joint "Known spots" check.** Every
  evaluation compares the current Kalman levels of BOTH beacons *together*
  against every recorded spot — inside recordings, walk traces, and your tagged
  false readings. If the pair is decisively nearest an outside recording (3 dB
  margin), the room is hard **false**, even when each meter individually shows
  amber/green. So tagging downstairs once teaches it the exact
  (−72, −85)-shaped signature. It shows as its own bar with the distances
  ("in 9 vs out 3"). Answer to "does tagging change the yellow range?": no —
  zones are built only from *inside* readings; tags work through this joint
  check and the maybe-grader.
- **Three-tier bands with a SUPER-green core.** Super green = your temptation
  spots (trimmed, ±2) — "definitely in". Green = the solid in-room range
  (trimmed, ±2) — "probably in". Yellow = the untrimmed tails ±6 — much wider
  than before, and deliberately covering the tail ends where your false
  positives lived: a tail reading can now never claim more than "uncertain".
- **true requires super green on every beacon.** In bed → true. Elsewhere in
  the room → green → "maybe (probs is)". Downstairs → tails + joint veto →
  false. The meters draw all three bands (bright green core inside the pale
  green inside the yellow), labelled at the super-band edges.
- **The tag button only appears when the reading is wrong** (anything other
  than false while you roam).

## What changed in round 5 (2026-07-14, after live testing)

- **"Never turns true" fixed.** The pattern bar could sit amber forever and it
  blocked true. It's gone (with "Held steady") — **true = every beacon meter
  green**, full stop. The 1.5 s debounce still runs silently, and each meter's
  label now shows the 6-second outlier-trimmed average next to the Kalman value.
- **Too close can't be red any more.** The room's own beacon has an open-topped
  zone: anything louder than the green band is still green (closer than your
  usual spot can never mean "not here"). The partner beacon keeps its two-sided
  band — a partner that's *too* loud still correctly reads as "you're probably
  in its room instead".
- **maybe split in two**: "maybe (probs is)" (amber) vs "maybe (probs not)"
  (brown), graded by comparing the live levels against every recording gathered
  in set-up (3-nearest-neighbours vote). The roam screen now shows MAYBE big and
  bold instead of a whisper.
- **Entry-walk glitch fixed** — the previous phase's ticker survived page
  changes and kept re-rendering the walk page back to "Start walking". Wizard
  tickers are now killed properly on navigation.
- **Wander step is 15 s** (was 30). **Roam is tag-only** — no continuous
  roam recording; you walk the house, watch the reading, and tag anywhere that
  isn't false.

## What changed in this round (2026-07-14, round 4)

- **The red/amber/green scales are back, one per beacon per room.** The Bedroom
  card shows a Bedroom-beacon scale AND a Bathroom-beacon scale; the Bathroom
  card shows its own pair. Each scale's bands are learned for THAT room, so all
  four differ. Green = the core readings (your temptation spots / at the
  beacon), amber = anything seen from inside the room (outliers trimmed), red =
  everything else. The dark needle is the live level, and each scale is labelled
  with which beacon it is and its current dBm — no more guessing what the other
  sensor reads.
- **true / maybe / false.** True only when *every* beacon sits in its green band
  and the pattern check matches an inside trace — i.e. you're at the risk spot
  (in bed / near the beacon). Maybe when it's close but uncertain (elsewhere in
  the room, or ambiguous). False when anything is red. Changes still need to
  hold 1.5 s.
- **Kalman filter (round 5).** Every live signal level — the big number, the
  meter needles, the zone decisions, and the wizard's recordings — now comes
  from a per-beacon **1D Kalman filter**, the industry-standard way to strip
  the noise out of raw BLE RSSI. It's tuned so measurement noise (~5 dB jitter,
  R=25) gets smoothed away while real movement (walking through a door,
  Q=4/s) is tracked within a couple of seconds; gaps in reception grow the
  filter's uncertainty so fresh data re-dominates after silence. A single raw
  reading never drives anything.
- **Patterns and outliers.** The pattern check doesn't match one value: it
  compares the **last 6 seconds as a rolling sequence** (one-second medians,
  both beacons) against every calibrated trace — entry-walk routes, the room
  wander, the roam pass, tags. Zones are built from outlier-trimmed
  calibration data.
- **Pattern debug view.** Each card has a "Patterns" button showing the expected
  end-of-approach sequences for route 1 and route 2 (both beacons) next to the
  live last-6-seconds — so you can see exactly which data is wrong when it
  misbehaves.
- **Fixed tagged spots are gone.** The wizard now ends with a **free-roam
  pass**: walk the whole house (staying out of the room), it records everywhere
  continuously as "not in the room" — ranges, not single points — while a big
  red **TAG FALSE READING HERE** button captures any spot where the live
  indicator (subtle "false ✓", loud red warning if wrong) shows a problem, and
  **Done tagging false readings** finishes. This is why "moving the phone a
  little" can no longer walk out of a single tagged range.
- **New wizard step: walk around the room for 30 s** — lots of inside readings
  so the odd weird one gets identified and trimmed.
- **Wizard flow fixed**: setting up the second room no longer asks "this room
  already has a beacon assigned?!" — a beacon assigned during the first room's
  set-up is kept silently, and the redundant second-beacon page is skipped. The
  keep/start-over question only appears when genuinely *recalibrating* an
  already-calibrated room.
- **Scan watchdog v2** (the "signal went –– until I re-entered the page" bug):
  Android downgrades long scans in a way that can keep trickling *other*
  devices' adverts while the beacons vanish — which fooled the old
  silence-based watchdog. It now also cycles the scan whenever the beacons we
  *expect* have all been silent for 15 s, and recycles every 5 min regardless.
- **"How we determine what room you're in"** — a plain-bullet page inside the
  app (top of the Room detection page), including the sensor-placement rules.

## The one thing to understand first

**You never "connect" or "pair" the K11 to your phone**, and users never install
KKM's app. A beacon shouts "I'm here!" over Bluetooth a few times a second; the
phone listens and measures loudness (**RSSI**, dBm, closer to 0 = nearer).

Code map: `RoomBeacons.kt` — samples/zones, `RoomPresence` (verdicts, sequence
pattern matcher), `BeaconScanner` (trimmed means, sequences, watchdog),
`PressureMonitor` (barometer), `SignalMeterView` (the scales). `Main.kt` — the
cards page, the wizard, the how-it-works page.

## Where to put the beacons (also shown in-app)

- **Closest to where the risk actually is.** Bedroom: at the bed — headboard or
  bedside table. This is the most important rule: it makes the green zone mean
  "at the risk spot".
- Bathroom: back of a cupboard, or a shelf near where the phone would get used.
- Anywhere works as long as it never moves; move a beacon → redo set-up.

## Testing walkthrough

1. Bluetooth ON, Location ON (red tap-to-fix warnings otherwise). App → home →
   **🔧 Dev tools** → **Room detection (beacons)** → grant permissions
   (**Precise** location).
2. Scan health line should read like `Scanning · 5.8 adverts/s from 7 devices ·
   barometer on` with moving numbers.
3. **Bedroom → "Set up this room"**: find bedroom beacon → find bathroom beacon
   → place both (bed!) → 6 spots (4 static + 2 temptation) → 15 s room wander →
   free-roam & tag. Budget ~8 minutes, once.
4. Card check: in bed → both meters super green → **true**. Standing in
   a far corner → probably **maybe** (that's by design — true means the risk
   spot). Outside → **false**.
5. **Regression test**: downstairs under the bedroom. The bathroom-beacon meter
   and/or the known-spots bar should go red → false (worst case maybe, never true).
   If not: the "Nearest known spot" bar shows the live in/usage/out distances —
   that tells you which data is off — and one tag standing right there fixes it.
6. **Bathroom → "Set up this room"** (goes straight into calibration — no
   redundant questions).

### Troubleshooting

- **Adverts/s stuck at 0.0 / red error code** → Location off, "Approximate"
  instead of Precise, or a real scan error — report the code.
- **A meter has no bands** → that beacon has no calibration data for that room
  yet: run set-up.
- **maybe when you want true in bed** → the green band is built from your
  temptation-spot samples; redo them lying/sitting exactly as you really would.
- **true somewhere outside** → roam pass again; stand at the offending spot and
  tag it.

### Tuning knobs (`RoomBeacons.kt`)

| Knob | Now | Meaning |
|---|---|---|
| `KALMAN_R` / `KALMAN_Q_PER_SEC` | 25 / 4 | Measurement noise vs how fast the true signal may drift; raise Q if the needle lags walking, lower it if it jitters. |
| `GREEN_SLACK_DB` | 3 | Green band = core readings ± this. |
| `AMBER_SLACK_DB` | 4 | Amber band = trimmed in-room readings ± this. |
| `SEQ_STEPS` | 6 | Seconds of sequence the pattern check compares. |
| `PATTERN_MARGIN` | 3.0 | How decisively inside must beat outside (or vice versa). |
| `FLOOR_SHIFT_HPA` / `FLOOR_HOLD_MS` | 0.30 / 8 s | Barometer floor-change veto. |
| `FLIP_MS` | 1.5 s | How long a verdict change must hold. |
| `TIMEOUT_MS` | 10 s | Beacon silent this long → false. |

## Shipping notes

- The wizard is the user onboarding (big text, one instruction per screen); the
  roam-and-tag pass doubles as the support path for false positives.
- Users never install KBeaconPro (dev-only diagnostic; default password ten
  zeros), never pair anything.
- Dead battery → "Beacon heard" red → false, never a crash. Nothing leaves the
  phone; config lives in SharedPreferences (`room_beacons`).
- Still on the shelf: a third beacon (biggest win; code is N-room), Wi-Fi RSSI
  anchors, stair detection via accelerometer, Wi-Fi RTT / UWB (need hardware).
- Detection needs the app open on this page (scanning stops with the screen
  off). Background version = foreground service with `SCAN_MODE_BALANCED`.
