# Room beacons (KKM K11) — setup & testing guide

What's built, how the detection works, and the testing walkthrough. Written
assuming you have never touched a Bluetooth beacon.

## What changed in this round (2026-07-14, round 3)

- **All sensors, all the time, AND-ed rules.** Every evaluation now reads: both
  beacons (as 3-second *medians* — a range of recent values, never one reading),
  the learned in-room ranges, the k-NN pattern match over every calibrated spot,
  and the **barometer**. Each rule is one bar on the room's card, and the room
  only reads **true when every bar is green**. The threshold prose ("IN when
  louder than…") is gone.
- **The bars** (per room):
  1. *Beacon heard* — heard within the last 10 s.
  2. *Signal level* — 3 s median vs the quietest calibrated in-room level.
  3. *Partner beacon* — the **other** room's beacon must currently sound the way
     it sounded from inside this room (range learned in calibration). Downstairs
     under the bedroom, the bedroom beacon can sound right but the bathroom
     beacon does not — this bar goes red.
  4. *Pattern match* — 3 nearest calibrated spots vote on the full two-beacon
     pattern; the bar names the spot it matched.
  5. *No floor change* — barometer: pressure moves ~0.12 hPa per metre, so a
     floor change is a sharp step; right after one, turning true is blocked for
     8 s. (Hidden on phones without a barometer.)
  6. *Held steady* — the combined answer must hold 1.5 s before flipping.
- **Calibration captures real behaviour, not just standing around:**
  - 4 static spots inside (next to beacon, middle, two far corners);
  - 2 **temptation spots** — sitting/lying exactly where the phone would really
    get used, sampled twice with the phone held differently (the most important
    readings of all);
  - 2 **entry walks** — you start away from the room, walk in at normal pace,
    tap "I just stepped in" at the door, settle, tap again. The app records a
    continuous trace (one fingerprint per second), so the data contains what the
    signal looks like *just before and while entering* — versus places like
    downstairs that merely sound similar. Two walks, different routes, different
    final spots;
  - the **tagged outside tour** — just outside the door, the neighbouring room,
    directly above/below (skippable in single-storey homes), and the hallway.
    During the tour the screen shows a *subtle* grey "false ✓ (correct)" — and a
    *huge red warning* if it wrongly thinks you're in the room, with the tag
    button right there to teach it. One time, all tagged. (This replaces the old
    "fix this spot" button.)
- **"Heard Ns ago" climbing forever — fixed.** Android silently downgrades or
  kills long-running unfiltered BLE scans (~30 min cap on many phones); the old
  code only restarted scans it *knew* had failed. The scanner now cycles itself
  whenever it has heard nothing for 8 s or has been running 10 min, with a
  cool-down so Android's restart throttle can't be tripped. You should never
  need to leave and re-enter the page again.

## The one thing to understand first

**You never "connect" or "pair" the K11 to your phone**, and users never install
KKM's app. A beacon shouts "I'm here!" over Bluetooth a few times a second; the
phone listens and measures loudness (**RSSI**, dBm, closer to 0 = nearer: −40 in
your hand, −90 far away). All the intelligence is on the phone.

Code map: `RoomBeacons.kt` — config + samples, `RoomPresence` rule engine,
`BeaconScanner` (with the self-healing watchdog), `PressureMonitor` (barometer).
`Main.kt` — the cards page (`showRoomBeaconDebug`) and wizard (`showRoomSetup`).

---

## Part 1 — one-time developer setup

1. **Power each beacon on**: pull the battery tab if there is one, then hold the
   button ~3 s until the LED blinks. It then broadcasts ~once a second for a
   year+ on one battery.
2. **(Optional, dev-only)** KBeaconPro (KKM's app, default password ten zeros)
   to sanity-check a beacon is alive, name them, set the advertising interval to
   ~300–500 ms (snappier medians), or experiment with lower Tx power to shrink a
   beacon's audible bubble. End users never do any of this.
3. **Install the app** as usual (`./deploy.sh` or `./gradlew installDebug`).

## Part 2 — testing walkthrough

1. Phone: Bluetooth ON, Location ON (red tap-to-fix warnings appear otherwise).
2. App → home → **🔧 Dev tools** → **Room detection (beacons)**. Grant the
   permissions (choose **Precise** location).
3. Check the **scan health line**: `Scanning · 5.8 adverts/s from 7 devices ·
   barometer on`, numbers moving. Stuck at 0.0/s or red = scanning problem;
   nothing else matters until it's fixed.
4. **Bedroom → "Set up this room"** and follow the big text. Full route: find
   bedroom beacon → find bathroom beacon (mandatory — the rules need both) →
   place both → 4 inside spots → 2 temptation spots → 2 entry walks → outside
   tour. Budget ~10 minutes; this is the one-time cost of accuracy.
5. Back on the page: the card shows the big live dBm and the rule bars. Wander
   the bedroom → all bars green → `true`. Walk out → a bar goes red (usually
   Pattern match or Signal level) → `false` within seconds.
6. **The regression test**: downstairs directly under the bedroom. Expect
   `false` — watch *which* bars catch it (Partner beacon and Pattern match are
   the designed catchers; No floor change blocks the transition while you're
   still on the stairs). If it ever reads true somewhere outside, recalibrate —
   the tour step will loudly show the failure at tag time.
7. **Bathroom → "Set up this room"** (its own walk; the wizard reminds you).

### Troubleshooting

- **Adverts/s stuck at 0.0** → Location off, permission "Approximate" instead of
  Precise, or a red Android error code on the scan line — report the number.
- **Beacon never found in step 1** → it isn't broadcasting: hold its button ~3 s
  until the LED blinks; verify with KBeaconPro if unsure.
- **A room flickers** → look at which bar is flapping. Signal level → the
  quietest calibrated spot was louder than where you actually sit: recalibrate
  and make the temptation spots realistic. Partner beacon → the other beacon
  may have been moved: redo set-up.
- **True somewhere it shouldn't be** → recalibrate that room; make sure the tour
  included the offending area (it shows the failure live at that moment).

### Tuning knobs (`RoomBeacons.kt`)

| Knob | Now | Meaning |
|---|---|---|
| `MEDIAN_WINDOW_MS` | 3 s | The "range of recent values" every check uses. |
| `SIGNAL_SLACK_DB` | 4 | Allowed below the quietest in-room level. |
| `PARTNER_SLACK_DB` | 8 | Allowed outside the partner's in-room range. |
| k (in `fingerprintVote`) | 3 | Nearest calibrated spots that vote. |
| `FLOOR_SHIFT_HPA` | 0.30 | Pressure step that counts as a floor change. |
| `FLOOR_HOLD_MS` | 8 s | How long a floor change blocks turning true. |
| `FLIP_MS` | 1.5 s | How long a flip must hold before it's believed. |
| `TIMEOUT_MS` | 10 s | Beacon silent this long → false. |

## Part 3 — when we ship to users

- Beacons ship powered on (or a one-line "hold the button until it blinks" card).
- The user experience is exactly the wizard: big text, one instruction per
  screen, both-beacons enforced, real usage spots and entry walks included, and
  the tour teaches every common outside place with instant feedback. Productise
  by launching the same wizard from onboarding instead of Dev tools.
- A dead battery reads as `false` ("Beacon heard" bar red), never a crash.
- Privacy: passive listening only; nothing leaves the phone; config lives in
  local SharedPreferences (`room_beacons`).

## Honest limits & the remaining shelf

- The rules are as good as the calibration. The tour and walks exist because
  radio in a real house always finds one more weird spot — expect to recalibrate
  once after the first week of real use.
- Barometer absolute values are useless (weather); it is used only as a
  floor-*change* detector, and phones without one simply skip that bar.
- Still on the shelf if two beacons + rules aren't enough: a **third beacon**
  (biggest win, code already handles it), Wi-Fi RSSI as extra anchors
  (Android-throttled), stair detection from the accelerometer, Wi-Fi RTT / UWB
  (need hardware users won't have).
- Detection needs the app open on this page (scanning stops with the screen
  off, deliberately). Background version = foreground service with
  `SCAN_MODE_BALANCED`; not built yet.
