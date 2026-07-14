# Room beacons (KKM K11) — setup & testing guide

What's built, how the detection maths works, and an idiot-proof testing
walkthrough. Written assuming you have never touched a Bluetooth beacon.

## The "downstairs under the bedroom" fix (2026-07-14), up front

You stood on the floor below the bedroom and it said `true`, solidly in the
green. That is *exactly* the failure mode of judging by one beacon's loudness:
2.4 GHz radio goes straight through a ceiling, so "one floor below the beacon"
is often *louder* than "far corner of the room". No threshold on a single signal
can ever fix that — from that spot, the number genuinely looks like "in the room".

What fixes it is the **pattern across both beacons** (fingerprinting — the
honest version of "triangulation"): downstairs, the bedroom beacon may sound
in-room-ish, but the *bathroom* beacon does not sound the way it sounds from
inside the bedroom. The pair of numbers is distinguishable even when each number
alone isn't. So:

- **Both beacons are now mandatory before calibration.** The wizard will not let
  you calibrate a room until both beacons are assigned and placed in their rooms
  (it walks you through assigning the second one mid-wizard if needed).
- **Every calibration spot records both beacons** (a fingerprint per spot).
- **The decision is now fingerprint-first**: the live pair of readings is
  compared against all your calibration spots (inside *and* outside ones) and
  the 3 nearest spots vote. The single-beacon threshold gate only acts as a
  fallback when fingerprint data is missing. The card's "why" line tells you
  which spot it matched: `Signal pattern across both beacons matches "The
  sneaky spot" → OUT.`
- **"The sneaky spot" is a required calibration step**: the wizard sends you
  directly above/below the room (or down the hall in a single-storey home) so
  your exact failure case is in the calibration data from day one.
- **"It's wrong right now — fix this spot…"** button on each room card: stand
  wherever it's wrong, tell it INSIDE or OUTSIDE, it listens for 3 s and learns
  that spot permanently. Wrong answers become one-tap training data instead of
  bug reports.
- Also: optional wizard spots removed (6 required spots now), samples cut from
  5 s to 3 s, and state flips are debounced (a change must hold 1.5 s).

## All the techniques — what's in use and what's on the shelf

You asked to research everything available. Sensors/data we can get on a phone,
and the verdict on each:

**In use now**
1. **Dual-beacon RSSI fingerprinting with k-NN voting** — the workhorse (above).
   True triangulation needs distances; RSSI can't honestly give distances, but
   fingerprint matching against calibrated spots is the accepted indoor-
   positioning equivalent and needs no extra hardware.
2. **Inside AND outside calibration spots** — the boundary is learned from both
   sides, including the above/below-floor spot.
3. **Corrective samples** — the fix-this-spot button; fingerprints improve with
   use instead of rotting.
4. **EMA smoothing** (per-beacon), **hysteresis gate** (fallback path),
   **1.5 s flip debounce**, **10 s absence timeout**, and **cross-room
   exclusivity** (if both rooms still claim IN, the better fingerprint match
   wins).

**Available later, in rough order of bang-for-buck**
5. **A third beacon** (~£10) — the single biggest accuracy jump available.
   Fingerprints get dramatically more distinctive with each extra anchor; the
   code already handles N rooms/beacons.
6. **Barometer** (`TYPE_PRESSURE`, most phones have one) — air pressure drops
   ~0.12 hPa per metre of height, so a floor change is a clear, fast step in the
   reading. Absolute pressure drifts with weather (can't say "you're on floor
   2"), but *relative* change over seconds is reliable: "pressure just changed by
   a floor's worth" is a strong veto for the upstairs/downstairs confusion. Best
   next sensor if the fingerprints alone don't fully kill it.
7. **Beacon Tx power tuning** (dev-side, via KBeaconPro once, before shipping) —
   turning the K11's transmit power down shrinks its audible bubble so the floor
   below hears much less. Free to try on your unit: worth an experiment.
8. **Wi-Fi RSSI fingerprinting** — home APs as extra anchors; Android throttles
   foreground scans (~4 per 2 min) so it's a slow, coarse signal. Cheap add-on,
   modest gain.
9. **Accelerometer stair/step detection** — "user just climbed stairs" as a
   floor-change hint; pairs naturally with the barometer. We already have
   `SensorMonitor` for accel.
10. **Wi-Fi RTT (802.11mc)** — real metre-level ranging, but needs a compatible
    router; rare in homes. Not shippable as a requirement.
11. **UWB** — centimetre-level, flagship phones only, needs UWB anchors (the K11
    isn't one). Not our market.
12. **Magnetometer fingerprints** — research-grade, unstable across phone
    orientation; skip.

---

## The one thing to understand first

**You never "connect" or "pair" the K11 to your phone.** A beacon is a tiny
radio that shouts "I'm here!" over Bluetooth a few times every second, to nobody
in particular. Our app just *listens* and measures how loud each shout is
(**RSSI**, in dBm — a negative number, **closer to zero = closer**: −40 is "in
your hand", −90 is "far away / behind two walls"). No pairing screen, nothing
installed on the beacon, and **users never need KKM's app** — KBeaconPro is
mentioned once below purely as an optional developer diagnostic.

## How detection works now

1. **Assign both beacons** — the wizard identifies each beacon by asking you to
   hold it against the phone and taking the strongest signal (it refuses
   candidates that look too far away or already belong to the other room).
2. **Place both**, wherever they'll permanently live (bedside table is fine —
   calibration is for that exact position; move a beacon → redo set-up).
3. **Calibration walk** — 6 required spots, ~3 s each: next to the beacon,
   middle, two far corners, just outside the door, and the sneaky spot
   (above/below the room). Each spot stores what *both* beacons sound like.
4. **Live decision** — beacon heard in the last 10 s, then the 3 nearest
   calibration spots vote on the current two-beacon pattern; flips need to hold
   1.5 s; if both rooms say IN, the better pattern match wins.

Code: `RoomBeacons.kt` (config, thresholds, fingerprint vote, presence engine,
scanner, meter view) and `Main.kt` (`showRoomBeaconDebug`, `showRoomSetup`).

---

## Part 1 — one-time developer setup

1. **Power each beacon on**: pull the battery tab if there is one, then hold the
   button ~3 s until the LED blinks. Done — it broadcasts about once a second
   for a year+ on one battery.
2. **(Optional, dev-only)** KBeaconPro (KKM's app, default password ten zeros)
   to sanity-check a beacon is alive, name them, set advertising interval to
   ~300–500 ms, or experiment with lower Tx power (see technique 7). End users
   never do any of this.
3. **Install the app** as usual (`./deploy.sh` or `./gradlew installDebug`).

## Part 2 — testing walkthrough

1. Phone: Bluetooth ON, Location ON (Android hides beacons from apps otherwise —
   red tap-to-fix warnings appear on the page if you forget).
2. App → home → **🔧 Dev tools** → **Room detection (beacons)**. First time:
   grant permissions (choose **Precise** location).
3. Check the **scan health line**: `Scanning · 5.8 adverts/s from 7 nearby
   devices`, numbers moving. Stuck at 0.0/s or red error = scanning problem;
   nothing else matters until that's fixed (see Troubleshooting).
4. **Bedroom card → "Set up this room"** and do what the big text says. It will
   route you through: find bedroom beacon → find bathroom beacon → place both →
   the 6-spot walk. Live dBm is on every screen.
5. Back on the main page: wander the bedroom → `true`; leave → `false` within
   ~10 s. The "why" line narrates each decision, naming the calibration spot the
   current pattern matches.
6. **Your regression test**: go downstairs and stand directly under the bedroom
   beacon. Expect `false` with the why-line pointing at "The sneaky spot". If
   any spot anywhere is wrong: stand there → **"It's wrong right now — fix this
   spot…"** → say INSIDE or OUTSIDE → 3 s → it's learned.
7. **Bathroom card → "Set up this room"** (its own calibration walk; the wizard
   reminds you at the end of the bedroom one).

### Troubleshooting

- **Adverts/s stuck at 0.0** → Location off, permission granted as "Approximate"
  (needs Precise), or the scan died — the line shows the Android error code;
  report it.
- **Beacon never appears in "Find the beacon"** → it isn't broadcasting: hold
  its button ~3 s until the LED blinks; verify with KBeaconPro if unsure.
- **Wizard warns inside/outside sound almost the same** → beacon too close to
  the door/wall; move it deeper into the room, redo set-up.
- **Wrong answer anywhere** → that's not a bug report any more, it's one tap:
  fix-this-spot from the room's card, standing at the offending spot.

### Tuning knobs (`RoomBeacons.kt`)

| Knob | Now | Meaning |
|---|---|---|
| k (in `fingerprintVote`) | 3 | Nearest calibration spots that vote. |
| `FLIP_MS` | 1.5 s | How long a flip must hold before it's believed. |
| `HYSTERESIS_DB` | 2 | Enter/exit gap for the fallback gate. |
| `TIMEOUT_MS` | 10 s | Not heard for this long → false. |
| `SAMPLE_MS` | 3 s | Length of each calibration/correction sample. |
| EMA weight | 0.3 | Smoothing; lower = steadier but slower. |

---

## Part 3 — when we ship beacons to users

- Ship beacons powered on (or a one-line "hold the button until it blinks" card).
- The user experience is exactly the wizard you tested — big text, one
  instruction per screen, both-beacons-first enforced, sneaky spot included, and
  the fix-this-spot button doubles as the support path ("it's wrong on the
  landing" → stand there, two taps). Productising = launching the same wizard
  from onboarding instead of Dev tools.
- Users never install KBeaconPro, never pair anything, never see this guide.
- Dead battery/removed beacon reads as `false` (never a crash); replacement is a
  coin cell.
- Privacy: passive listening only; nothing leaves the phone; config lives in
  local SharedPreferences (`room_beacons`).

## Honest limits

- Fingerprinting is as good as its calibration spots. The sneaky spot and the
  corrective button exist precisely because radio in a real house always finds
  one more weird spot; expect to teach it a spot or two in week one.
- The meter bar visualises the *fallback gate* (one beacon vs its thresholds).
  When fingerprints are active the vote is what decides — read the "why" line,
  which names the matched spot.
- Adjacent/stacked small rooms remain the hard case; a third beacon and/or the
  barometer are the next tools if two beacons + corrections aren't enough.
- Detection currently needs the app open on the debug page (scanning stops with
  the screen off, deliberately). A background version = a foreground service
  with `SCAN_MODE_BALANCED`; not built yet.
