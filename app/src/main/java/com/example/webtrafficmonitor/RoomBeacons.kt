package com.example.webtrafficmonitor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

// =====================================================================================
//  ROOM BEACONS  -  "which room is the phone in?" from BLE beacons (KKM K11) + sensors.
// =====================================================================================
//
//  Each room gets a beacon that shouts its identity over BLE; the phone only listens
//  and measures loudness (RSSI, dBm, closer to 0 = nearer). No pairing, no KKM app.
//
//  Rooms are user-defined (1-8; each with one sensor, optionally two). CALIBRATION (the wizard) records
//  the FULL SET of every assigned beacon's level at each place: static spots, the
//  "temptation spots" where the phone actually gets used (flagged core=true - only
//  these can produce a 'true'), a 15 s walk around the room, and user-tagged "false
//  reading" spots from a roam around the house.
//
//  DECISION (RoomPresence) is PAIRWISE: the current tuple of all beacons' levels is
//  matched against the recorded spots, where distance = the WORST single-beacon
//  difference - a spot only matches when EVERY beacon agrees. Four answers:
//    true              - the tuple matches a core (usage) spot within TRUE_DIST.
//    maybe (probs is)  - it matches somewhere inside the room within NEAR_DIST.
//    maybe (probs not) - only vaguely close to anything inside (≤ FAR_DIST).
//    false             - own beacon silent/out of its amber band, the tuple matches
//                        a tagged false spot better than any inside spot, or it
//                        matches nothing recorded inside at all.
//
//  Robustness rules used everywhere:
//    - live levels come from a per-beacon 1D KALMAN FILTER (BeaconScanner.kalmanRssi):
//      the industry-standard way to strip the noise out of raw BLE RSSI - a single
//      reading never drives anything;
//    - the UI also shows a 6 s outlier-trimmed average per beacon (robustRssi);
//    - calibration data has its outliers trimmed before zones are built;
//    - answers must hold FLIP_MS before they change (silent debounce).
// =====================================================================================
object RoomBeacons {

    const val MAX_ROOMS = 8
    const val MAX_SENSORS = 4   // per room

    /** The rooms being tracked, oldest first. Starts EMPTY - the user names their
     *  first room during set-up (1-8 rooms, each with 1-4 sensors). */
    fun rooms(context: Context): List<String> {
        val raw = prefs(context).getString("rooms", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (t: Throwable) { emptyList() }
    }

    fun addRoom(context: Context, name: String): Boolean {
        val clean = name.trim().lowercase()
        val current = rooms(context)
        if (clean.isEmpty() || clean in current || current.size >= MAX_ROOMS) return false
        setRooms(context, current + clean)
        return true
    }

    fun removeRoom(context: Context, room: String) {
        setBeaconMac(context, room, null)   // clears its beacon + samples
        setRooms(context, rooms(context).filter { it != room })
    }

    private fun setRooms(context: Context, list: List<String>) =
        prefs(context).edit().putString("rooms", JSONArray(list).toString()).apply()

    /** A beacon not heard for this long counts as absent (K11 default interval ~1 s). */
    const val TIMEOUT_MS = 10_000L

    /** How long each static sample / find-the-beacon scan collects for. */
    const val SAMPLE_MS = 3_000L

    /** Tagging a false reading is quick - one second. */
    const val TAG_MS = 1_000L

    /** Treated as the reading when a beacon isn't heard at all. */
    const val SILENT_DBM = -100

    // Band slacks are aligned with RoomPresence's distance thresholds so the meters
    // agree with the verdict (a 'true' can only fire while the needles show super).
    /** Zone construction: SUPER green = core (temptation) readings, trimmed, ± this. */
    const val SUPER_SLACK_DB = 4

    /** Zone construction: green = all in-room readings (outliers trimmed) ± this. */
    const val GREEN_SLACK_DB = 3

    /** Zone construction: amber = all in-room readings including the tails ± this.
     *  Deliberately wide: the tail ends of the in-room range are where downstairs /
     *  next-door readings overlap, so they only ever count as "uncertain", never as
     *  proof of being in the room. */
    const val AMBER_SLACK_DB = 6

    /** One calibration point. core=true marks the temptation/at-the-beacon readings
     *  that define the green ("definitely in") zone. Traces record one per second. */
    class Sample(val label: String, val inRoom: Boolean, val readings: Map<String, Int>, val core: Boolean = false)

    /** Per-room, per-beacon expected ranges, three tiers deep. Outside amber = red. */
    data class Zone(
        val superLo: Int, val superHi: Int,   // core (temptation) readings: "definitely in"
        val greenLo: Int, val greenHi: Int,   // solid in-room readings: "probably in"
        val amberLo: Int, val amberHi: Int,   // the tails: "uncertain" - never proof of in
    ) {
        /** 2 = super green, 1 = green, 0 = amber, -1 = red. openTop = the room's OWN
         *  beacon: being even closer than the calibrated core is always super green. */
        fun state(value: Int, openTop: Boolean = false): Int = when {
            openTop -> when {
                value >= superLo -> 2
                value >= greenLo -> 1
                value >= amberLo -> 0
                else -> -1
            }
            value in superLo..superHi -> 2
            value in greenLo..greenHi -> 1
            value in amberLo..amberHi -> 0
            else -> -1
        }
    }

    // ── Per-room config (SharedPreferences "room_beacons") ───────────────────────────
    private const val PREFS = "room_beacons"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Slot keys: 0 = "$room.mac" (kept for data compatibility), 1.. = "$room.mac2"…
    private fun slotKey(room: String, slot: Int) = if (slot == 0) "$room.mac" else "$room.mac${slot + 1}"

    /** How many sensors the user wants in this room (1-MAX_SENSORS). */
    fun sensorCount(context: Context, room: String): Int =
        prefs(context).getInt("$room.sensors", 1).coerceIn(1, MAX_SENSORS)

    /** Change the wanted count. Shrinking drops the removed slots' sensors (and, since
     *  the tuple shape changes, the calibration when any were actually assigned). */
    fun setSensorCount(context: Context, room: String, n: Int) {
        val count = n.coerceIn(1, MAX_SENSORS)
        val e = prefs(context).edit().putInt("$room.sensors", count)
        var dropped = false
        for (slot in count until MAX_SENSORS) {
            if (prefs(context).getString(slotKey(room, slot), null) != null) dropped = true
            e.remove(slotKey(room, slot))
        }
        if (dropped) e.remove("$room.samples")
        e.apply()
    }

    fun beaconMacAt(context: Context, room: String, slot: Int): String? =
        prefs(context).getString(slotKey(room, slot), null)

    fun setBeaconMacAt(context: Context, room: String, slot: Int, mac: String?) {
        val old = beaconMacAt(context, room, slot)
        val e = prefs(context).edit()
        if (mac == null) e.remove(slotKey(room, slot)) else e.putString(slotKey(room, slot), mac)
        if (mac != old) e.remove("$room.samples")   // the tuple changed: recalibrate
        e.apply()
    }

    /** "A".."D" for a slot - how sensors are named everywhere in the UI. */
    fun sensorLetter(slot: Int): String = ('A' + slot).toString()

    fun beaconMac(context: Context, room: String): String? = beaconMacAt(context, room, 0)

    /** Clearing the primary (mac == null) resets the whole room: all slots + samples. */
    fun setBeaconMac(context: Context, room: String, mac: String?) {
        if (mac == null) {
            val e = prefs(context).edit()
            for (slot in 0 until MAX_SENSORS) e.remove(slotKey(room, slot))
            e.remove("$room.samples").remove("$room.farA").remove("$room.farB").apply()
        } else setBeaconMacAt(context, room, 0, mac)
    }

    // ── Onboarding: has the user told us they own beacons? Gates the home page's
    //    "Connected sensors" console (before this, it shows the set-up pitch instead).
    fun ownsSensors(context: Context): Boolean = prefs(context).getBoolean("owns_sensors", false)
    fun setOwnsSensors(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("owns_sensors", v).apply()

    fun beaconMac2(context: Context, room: String): String? = beaconMacAt(context, room, 1)

    /** One entry per assigned sensor: (room, slot, mac), own room's slots first. */
    fun assignedSensors(context: Context, firstRoom: String): List<Triple<String, Int, String>> =
        (listOf(firstRoom) + rooms(context).filter { it != firstRoom })
            .flatMap { r ->
                (0 until sensorCount(context, r)).mapNotNull { slot ->
                    beaconMacAt(context, r, slot)?.let { Triple(r, slot, it) }
                }
            }

    /** Every assigned beacon MAC in the house, every slot of every room. */
    fun allAssignedMacs(context: Context): List<String> =
        rooms(context).flatMap { r ->
            (0 until sensorCount(context, r)).mapNotNull { slot -> beaconMacAt(context, r, slot) }
        }.distinct()

    // ── Debug: enforce the room guard in ANY mode (normally strict-only). ──
    fun debugGuard(context: Context): Boolean = prefs(context).getBoolean("debug_guard", false)
    fun setDebugGuard(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("debug_guard", on).apply()

    // Samples are re-read every UI tick, so cache the parse keyed on the raw string.
    private val sampleCache = HashMap<String, Pair<String, List<Sample>>>()

    fun samples(context: Context, room: String): List<Sample> {
        val raw = prefs(context).getString("$room.samples", null) ?: return emptyList()
        synchronized(sampleCache) { sampleCache[room]?.let { if (it.first == raw) return it.second } }
        val parsed = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val r = o.getJSONObject("r")
                Sample(
                    o.getString("label"), o.getBoolean("in"),
                    r.keys().asSequence().associateWith { r.getInt(it) },
                    o.optBoolean("c", false),
                )
            }
        } catch (t: Throwable) { Log.w("RoomBeacons", "bad samples for $room: ${t.message}"); emptyList() }
        synchronized(sampleCache) { sampleCache[room] = raw to parsed }
        return parsed
    }

    fun setSamples(context: Context, room: String, samples: List<Sample>) {
        val arr = JSONArray()
        for (s in samples) arr.put(
            JSONObject().put("label", s.label).put("in", s.inRoom).put("c", s.core)
                .put("r", JSONObject().apply { s.readings.forEach { (k, v) -> put(k, v) } }),
        )
        prefs(context).edit().putString("$room.samples", arr.toString()).apply()
    }

    fun addSample(context: Context, room: String, sample: Sample) =
        setSamples(context, room, samples(context, room) + sample)

    fun isCalibrated(context: Context, room: String): Boolean {
        val own = beaconMac(context, room) ?: return false
        return samples(context, room).any { it.inRoom && it.readings.containsKey(own) }
    }

    /** Drop the extreme 10% at each end (needs 5+ values) - the outlier filter. */
    fun trimOutliers(sorted: List<Int>): List<Int> {
        if (sorted.size < 5) return sorted
        val k = sorted.size / 10 + 1
        return sorted.subList(k.coerceAtMost(sorted.size / 2 - 1).coerceAtLeast(0),
            (sorted.size - k).coerceAtLeast(sorted.size / 2 + 1))
    }

    /** The expected range of beacon `mac` as heard from inside `room`, or null until
     *  calibrated. Super green from the core (temptation / at-the-beacon) readings,
     *  green from the trimmed in-room readings, amber from the untrimmed tails. */
    fun zone(context: Context, room: String, mac: String): Zone? {
        val inside = samples(context, room).filter { it.inRoom }
        val all = inside.mapNotNull { it.readings[mac] }.sorted()
        if (all.isEmpty()) return null
        val trimmedAll = trimOutliers(all)
        val coreVals = inside.filter { it.core }.mapNotNull { it.readings[mac] }.sorted()
        val core = trimOutliers(if (coreVals.isNotEmpty()) coreVals else trimmedAll)
        val aLo = all.first() - AMBER_SLACK_DB
        val aHi = all.last() + AMBER_SLACK_DB
        val gLo = (trimmedAll.first() - GREEN_SLACK_DB).coerceAtLeast(aLo)
        val gHi = (trimmedAll.last() + GREEN_SLACK_DB).coerceAtMost(aHi)
        val sLo = (core.first() - SUPER_SLACK_DB).coerceAtLeast(gLo)
        val sHi = (core.last() + SUPER_SLACK_DB).coerceAtMost(gHi)
        return Zone(sLo, sHi, gLo, gHi, aLo, aHi)
    }

    // ── Permissions ──────────────────────────────────────────────────────────────────
    // Android 12+ uses BLUETOOTH_SCAN, and because we infer location from beacons the
    // manifest deliberately does NOT set neverForLocation (that flag makes Android
    // silently filter beacon frames out of scan results) - so fine location is needed
    // too. Pre-12 only needs location (the old BLUETOOTH permissions are install-time).
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) else arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

    fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}


// =====================================================================================
//  RoomPresence  -  the rule engine: sensors in, true / maybe / false out.
// =====================================================================================
object RoomPresence {

    /** true / maybe (probs is) / maybe (probs not) / false. */
    // MAYBE_IN_TRUE is the upper 40% of the maybe-probs-am band (the part nearest
    // true): the band's range and every calculation stay EXACTLY as they were - this
    // is only a label carved out of MAYBE_IN, and it's the slice shown (and enforced)
    // as true. Plain MAYBE_IN stays amber and does NOT block.
    enum class Verdict { IN, MAYBE_IN_TRUE, MAYBE_IN, MAYBE_OUT, OUT }

    /** The fraction of the maybe-probs-am band (measured from the true end) that is
     *  treated as true. Band = effAny in 0..NEAR_DIST; upper 40% = ≤ NEAR_DIST × 0.4. */
    const val NEAR_TRUE_FRACTION = 0.4

    /** A verdict change must hold this long before it's believed (applied silently). */
    const val FLIP_MS = 1_500L

    /** After a barometer floor change, turning true is blocked for this long. */
    const val FLOOR_HOLD_MS = 8_000L

    // Pairwise-distance thresholds (dB). The distance between "now" and a recorded
    // spot is the WORST per-beacon difference: readings come in tuples (one value per
    // beacon), and a spot only matches when EVERY beacon is close to what was recorded
    // there. That's what separates "downstairs" (own beacon plausible, partner 9 dB
    // off) from "far end of the room" (both beacons match the far-end recording) -
    // independent per-beacon ranges cannot make that distinction. Works for any
    // number of beacons.
    const val TRUE_DIST = 4.0    // this close to a CORE (usage-spot) recording → true
    const val NEAR_DIST = 7.0    // this close to any inside recording → maybe (probs is)
    const val FAR_DIST = 10.0    // this close → maybe (probs not); beyond → false
    const val OUT_MARGIN = 3.0   // nearest-outside beats nearest-inside by this → false

    // The recent-average booster: alongside the instantaneous distances we keep a
    // ~10 s history of them. If the recent average is good AND consistent (small
    // spread), we trust it - so a brief wobble can't drop a solid 'true', and a
    // steady borderline reading gets credited with its consistency.
    const val TREND_WINDOW_MS = 10_000L
    const val TREND_MIN_POINTS = 6
    const val TREND_MAX_SPREAD = 4.0

    /** One rule's verdict. state: 1 green, 0 grey (no data - doesn't block), -1 red. */
    data class Check(val label: String, val state: Int, val value: String)

    /** One beacon's meter on a room card: its learned zones + where it is right now. */
    data class MeterData(
        val label: String,
        val mac: String,
        val current: Int?,
        val zone: RoomBeacons.Zone?,
        val state: Int,
        val openTop: Boolean,
    )

    data class Status(
        val room: String,
        val verdict: Verdict,
        val assigned: Boolean,
        val calibrated: Boolean,
        val rssi: Int?,          // Kalman-filtered level of the room's own beacon
        val rawRssi: Int?,
        val ageMs: Long?,
        val meters: List<MeterData>,
        val checks: List<Check>,
        val summary: String,     // only used while not set up
    )

    private val lastVerdict = HashMap<String, Verdict>()
    private val pendingSince = HashMap<String, Long>()
    // Per-room history of (time, dInCore, dInAny) for the recent-average booster.
    private val trend = HashMap<String, ArrayDeque<Triple<Long, Double, Double>>>()
    fun reset() { lastVerdict.clear(); pendingSince.clear(); trend.clear() }

    /** Windowed mean of a distance series, or null unless it has enough points AND is
     *  consistent (max-min spread within TREND_MAX_SPREAD). */
    private fun steadyAverage(values: List<Double>): Double? {
        if (values.size < TREND_MIN_POINTS) return null
        val mn = values.min(); val mx = values.max()
        if (mx - mn > TREND_MAX_SPREAD) return null
        return values.average()
    }

    /** [key] namespaces the debounce/trend state per caller: the debug page and the
     *  all-day RoomGuard evaluate with DIFFERENT scanners (different histories), so
     *  sharing hysteresis state between them would make the two fight. */
    fun evaluate(context: Context, scanner: BeaconScanner, pressure: PressureMonitor?, key: String = "ui"): Map<String, Status> {
        val now = System.currentTimeMillis()
        val statuses = LinkedHashMap<String, Status>()
        val dInByRoom = HashMap<String, Double>()
        val current = currentVector(context, scanner)

        for (room in RoomBeacons.rooms(context)) {
            val mac = RoomBeacons.beaconMac(context, room)
            val sk = "$key:$room"
            if (mac == null) {
                lastVerdict[sk] = Verdict.OUT; pendingSince.remove(sk)
                statuses[room] = Status(room, Verdict.OUT, false, false, null, null, null,
                    emptyList(), emptyList(), "No beacon assigned - run set-up.")
                continue
            }
            val b = scanner.beacon(mac)
            val age = b?.let { now - it.lastSeen }
            val kal = scanner.kalmanRssi(mac)
            if (!RoomBeacons.isCalibrated(context, room)) {
                lastVerdict[sk] = Verdict.OUT; pendingSince.remove(sk)
                statuses[room] = Status(room, Verdict.OUT, true, false, kal, b?.rssi, age,
                    emptyList(), emptyList(), "Beacon assigned - run set-up to calibrate.")
                continue
            }

            val was = lastVerdict[sk] ?: Verdict.OUT

            // Meters are DISPLAY plus one hard rule each: a beacon heard at a level
            // outside its amber band was never heard like that from inside → false.
            // Own-room beacons (both, in dual mode) are open-topped.
            val meters = RoomBeacons.assignedSensors(context, room).map { (beaconRoom, slot, m) ->
                val zone = RoomBeacons.zone(context, room, m)
                val cur = scanner.kalmanRssi(m)
                val openTop = beaconRoom == room
                val state = when {
                    zone == null -> 0
                    cur == null && !openTop -> 0
                    else -> zone.state(cur ?: RoomBeacons.SILENT_DBM, openTop)
                }
                MeterData(
                    "${beaconRoom.replaceFirstChar { it.uppercase() }} ${RoomBeacons.sensorLetter(slot)}",
                    m, cur, zone, state, openTop,
                )
            }

            val checks = mutableListOf<Check>()
            val heard = (b != null && age!! <= RoomBeacons.TIMEOUT_MS) ||
                meters.any { it.openTop && it.current != null }
            checks += Check("Beacon heard", if (heard) 1 else -1,
                if (age == null) "never" else "${age / 1000}s ago")

            // The pairwise core: how far is the current tuple from the nearest
            // recorded spot of each kind? (Worst per-beacon difference, in dB.)
            val samples = RoomBeacons.samples(context, room)
            val dInCore = samples.filter { it.inRoom && it.core }.minOfOrNull { distance(current, it) }
            val dInAny = samples.filter { it.inRoom }.minOfOrNull { distance(current, it) }
            val dOut = samples.filter { !it.inRoom }.minOfOrNull { distance(current, it) }

            // Recent-average booster: a good AND consistent last-10s average counts
            // as much as the instant value (so wobbles don't flap the answer).
            val hist = trend.getOrPut(sk) { ArrayDeque() }
            if (dInCore != null && dInAny != null) hist.addLast(Triple(now, dInCore, dInAny))
            while (hist.isNotEmpty() && hist.first().first < now - TREND_WINDOW_MS) hist.removeFirst()
            val avgCore = steadyAverage(hist.map { it.second })
            val avgAny = steadyAverage(hist.map { it.third })
            val effCore = listOfNotNull(dInCore, avgCore).minOrNull()
            val effAny = listOfNotNull(dInAny, avgAny).minOrNull()

            effAny?.let { dInByRoom[room] = it }
            val spotState = when {
                effAny == null -> 0
                dOut != null && dOut + OUT_MARGIN <= effAny -> -1
                effAny <= NEAR_DIST && (dOut == null || effAny + OUT_MARGIN <= dOut) -> 1
                else -> 0
            }
            checks += Check("Nearest known spot", spotState,
                "in ${fmt(dInAny)}${avgAny?.let { " (avg ${fmt(it)})" } ?: ""} · " +
                    "usage ${fmt(dInCore)}${avgCore?.let { " (avg ${fmt(it)})" } ?: ""} · " +
                    "out ${fmt(dOut)} dB")

            var floorOk = true
            if (pressure?.available == true) {
                val ago = pressure.shiftAgoMs()
                val recentShift = ago != null && ago < FLOOR_HOLD_MS
                floorOk = was == Verdict.IN || !recentShift
                checks += Check("No floor change", if (floorOk) 1 else -1,
                    when {
                        ago == null -> "steady"
                        recentShift -> "changed ${ago / 1000}s ago"
                        else -> "steady (${ago / 1000}s)"
                    })
            }

            // The ladder, all pairwise (instant OR steady recent average, whichever is
            // better): match a usage spot → true; anywhere inside → probs is; vaguely
            // close → probs not; match a tagged/outside spot better, match nothing,
            // or ANY beacon heard outside its learned band → false.
            var candidate = when {
                !heard || meters.any { it.state == -1 } -> Verdict.OUT
                effAny == null -> Verdict.OUT
                dOut != null && dOut + OUT_MARGIN <= effAny -> Verdict.OUT
                effCore != null && effCore <= TRUE_DIST -> Verdict.IN
                effAny <= NEAR_DIST * NEAR_TRUE_FRACTION -> Verdict.MAYBE_IN_TRUE
                effAny <= NEAR_DIST -> Verdict.MAYBE_IN
                effAny <= FAR_DIST -> Verdict.MAYBE_OUT
                else -> Verdict.OUT
            }
            if (!floorOk && (candidate == Verdict.IN || candidate == Verdict.MAYBE_IN_TRUE)) {
                candidate = Verdict.MAYBE_IN
            }

            // Debounce (silent): the change must persist before it's believed.
            val verdict: Verdict
            if (candidate == was) { pendingSince.remove(sk); verdict = was }
            else {
                val since = pendingSince.getOrPut(sk) { now }
                if (now - since >= FLIP_MS) { pendingSince.remove(sk); verdict = candidate }
                else verdict = was
            }
            lastVerdict[sk] = verdict
            statuses[room] = Status(room, verdict, true, true, kal, b?.rssi, age, meters, checks, "")
        }

        // Exclusivity: if several rooms are IN, the best (nearest) inside match keeps
        // IN and the rest drop to "maybe (probs is)".
        val claiming = statuses.values.filter {
            it.verdict == Verdict.IN || it.verdict == Verdict.MAYBE_IN_TRUE
        }
        if (claiming.size > 1) {
            val winner = claiming.minByOrNull { dInByRoom[it.room] ?: Double.MAX_VALUE }!!
            for (s in claiming) {
                if (s.room == winner.room) continue
                lastVerdict["$key:${s.room}"] = Verdict.MAYBE_IN; pendingSince.remove("$key:${s.room}")
                statuses[s.room] = s.copy(verdict = Verdict.MAYBE_IN)
            }
        }
        return statuses
    }

    private fun fmt(d: Double?): String = d?.let { "%.0f".format(it) } ?: "-"

    // What every assigned beacon sounds like right now (Kalman levels, silence = floor).
    private fun currentVector(context: Context, scanner: BeaconScanner): Map<String, Double> =
        RoomBeacons.allAssignedMacs(context)
            .associateWith { mac -> (scanner.kalmanRssi(mac) ?: RoomBeacons.SILENT_DBM).toDouble() }

    // Chebyshev distance: the WORST single-beacon difference between the live tuple
    // and a recorded spot. A spot only counts as matched when every beacon agrees.
    private fun distance(current: Map<String, Double>, spot: RoomBeacons.Sample): Double {
        var worst = 0.0
        for ((mac, cur) in current) {
            val d = Math.abs(cur - (spot.readings[mac] ?: RoomBeacons.SILENT_DBM))
            if (d > worst) worst = d
        }
        return worst
    }
}


// =====================================================================================
//  RoomGuard  -  the all-day enforcement watcher (strict mode + protected rooms).
// =====================================================================================
/**
 * Owned by the accessibility service. While the GATE is open - mode is strict or
 * stricter, at least one room fully set up, Bluetooth-scanning permissions granted -
 * it keeps a low-power BeaconScanner + PressureMonitor running and evaluates
 * RoomPresence every couple of seconds. [activeRoom] is the room the phone is
 * currently in; the service blocks every non-essential app while it is non-null.
 *
 * Engages on a verdict of IN (true), and once engaged it LATCHES through "maybe
 * (probs is)" - shifting around in bed must not flap the cover - releasing only when
 * the verdict drops to "maybe (probs not)" or false (i.e. you actually left).
 *
 * Fails OPEN, same doctrine as the night guard: no permission, Bluetooth off, no
 * calibration, no reading → no block. Locking someone out of their phone on a guess
 * is worse than missing one scroll. (Android also delivers no unfiltered scan
 * results while the screen is off - which is fine: blocking only matters with the
 * screen on, and readings resume within a second or two of waking.)
 */
object RoomGuard {

    private const val EVAL_MS = 2_000L     // presence evaluation cadence while armed
    private const val GATE_MS = 15_000L    // cadence of the should-this-run-at-all check

    /** The room the phone is in right now (presence - runs whenever a calibrated room
     *  + permissions exist, in ANY mode). Stamped onto block events. */
    @Volatile var presenceRoom: String? = null; private set

    /** The room currently being ENFORCED (strict/debug modes only), or null. */
    @Volatile var activeRoom: String? = null; private set

    /** True while the gate is open and the scanner is listening (for debug UIs). */
    @Volatile var armed: Boolean = false; private set

    private var scanner: BeaconScanner? = null
    private var pressure: PressureMonitor? = null
    private var handler: android.os.Handler? = null
    private var lastGateCheck = 0L
    private var gateOpen = false

    /** Idempotent. [onChange] fires on the main thread whenever [activeRoom] changes. */
    fun start(context: Context, onChange: (() -> Unit)? = null) {
        if (handler != null) return
        val app = context.applicationContext
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        handler = h
        h.post(object : Runnable {
            override fun run() {
                tick(app, onChange)
                h.postDelayed(this, if (gateOpen) EVAL_MS else GATE_MS)
            }
        })
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null); handler = null
        scanner?.stop(); scanner = null
        pressure?.stop(); pressure = null
        presenceRoom = null; activeRoom = null; armed = false; gateOpen = false; lastGateCheck = 0L
    }

    // Strict (or stricter) normally; the sensors page's debug toggle forces it on.
    private fun strictOrStricter(context: Context): Boolean =
        Mode.current(context) != Mode.RELAXED || RoomBeacons.debugGuard(context)

    private fun tick(app: Context, onChange: (() -> Unit)?) {
        val now = System.currentTimeMillis()
        // Presence runs in EVERY mode (block events want the room stamped on them);
        // only the BLOCKING below is gated on strict/debug.
        if (now - lastGateCheck >= GATE_MS || !gateOpen) {
            lastGateCheck = now
            gateOpen = RoomBeacons.hasPermissions(app) &&
                RoomBeacons.rooms(app).any { RoomBeacons.isCalibrated(app, it) }
        }
        if (!gateOpen) {
            scanner?.stop(); scanner = null
            pressure?.stop(); pressure = null
            armed = false
            presenceRoom = null
            setRoom(null, onChange)
            return
        }

        val sc = scanner ?: BeaconScanner(app).also { it.lowPower = true; scanner = it }
        val pr = pressure ?: PressureMonitor(app).also { pressure = it }
        pr.start()
        sc.expectedMacs = RoomBeacons.allAssignedMacs(app).toSet()
        sc.ensureScanning()
        armed = sc.isScanning

        val statuses = RoomPresence.evaluate(app, sc, pr, key = "guard")
        // Engage on true (IN, or the treated-as-true top slice of maybe); once engaged,
        // hold through plain maybe-probs-am so shifting in bed can't flap the cover.
        val inRoom = statuses.values.firstOrNull {
            it.verdict == RoomPresence.Verdict.IN || it.verdict == RoomPresence.Verdict.MAYBE_IN_TRUE
        }?.room
        val held = presenceRoom
        val heldVerdict = held?.let { statuses[it]?.verdict }
        val next = when {
            inRoom != null -> inRoom
            heldVerdict == RoomPresence.Verdict.MAYBE_IN ||
                heldVerdict == RoomPresence.Verdict.MAYBE_IN_TRUE -> held
            else -> null
        }
        presenceRoom = next
        setRoom(if (strictOrStricter(app)) next else null, onChange)
    }

    private fun setRoom(room: String?, onChange: (() -> Unit)?) {
        if (room == activeRoom) return
        activeRoom = room
        onChange?.invoke()
    }
}


// =====================================================================================
//  BeaconScanner  -  BLE listener with per-beacon history and a self-healing watchdog.
// =====================================================================================
// Passive listener only: never connects, never pairs. Call ensureScanning() from a UI
// tick: it starts the scan and also CYCLES it when it stops delivering - Android
// silently downgrades/kills long-running unfiltered scans, and a downgraded scan can
// keep trickling OTHER devices' adverts while the beacons vanish, so the watchdog
// also checks the beacons we EXPECT to hear (expectedMacs). Restarts respect a
// cool-down because Android hard-throttles >5 scan starts per 30 s.
class BeaconScanner(context: Context) {

    companion object {
        // 1D Kalman filter tuning. R = measurement noise variance (raw BLE RSSI
        // jitters with a std of roughly 5 dB → 25). Q = how much the TRUE signal is
        // allowed to drift per second (walking through a doorway moves it ~5-10 dB/s,
        // so the filter must not be so smooth that it lags a real room change).
        const val KALMAN_R = 25.0
        const val KALMAN_Q_PER_SEC = 4.0
    }

    /** Everything we know about one advertising device. */
    class Beacon(val mac: String) {
        var name: String? = null
        var rssi: Int = 0                 // latest raw reading, dBm
        var smoothedRssi: Double = 0.0    // EMA - display/sort only
        var lastSeen: Long = 0L
        var txPower: Int? = null
        var iBeaconUuid: String? = null
        var count: Long = 0
        // (timestamp, raw rssi) of recent adverts - feeds trimmed means + sequences.
        val history = ArrayDeque<Pair<Long, Int>>()
        // Kalman filter state: the level estimate, its uncertainty, and when it was
        // last updated (gaps grow the uncertainty so fresh data re-dominates).
        var kEstimate = 0.0
        var kVariance = 0.0
        var kInitialised = false
        var kUpdatedAt = 0L

        fun kalmanUpdate(measurement: Int, now: Long) {
            if (!kInitialised) {
                kEstimate = measurement.toDouble(); kVariance = KALMAN_R
                kInitialised = true; kUpdatedAt = now
                return
            }
            // Predict: uncertainty grows with time since the last advert (capped so a
            // long-lost beacon doesn't overflow; it just becomes "trust the new data").
            val dt = ((now - kUpdatedAt).coerceIn(0, 5_000)) / 1000.0
            kVariance += KALMAN_Q_PER_SEC * dt
            // Correct: blend the measurement in, weighted by the two uncertainties.
            val gain = kVariance / (kVariance + KALMAN_R)
            kEstimate += gain * (measurement - kEstimate)
            kVariance *= (1 - gain)
            kUpdatedAt = now
        }
    }

    private val adapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val beacons = LinkedHashMap<String, Beacon>()
    private var scanning = false
    private var scanStartedAt = 0L
    private var lastStartAttempt = 0L
    private var lastResultAt = 0L
    var lastScanError: Int? = null; private set
    var onUpdate: (() -> Unit)? = null
    private var lastEmit = 0L

    /** The beacons the caller expects to keep hearing; feeds the watchdog. */
    @Volatile var expectedMacs: Set<String> = emptySet()

    /** Balanced scan mode instead of low-latency: fewer readings (one every ~1-2 s is
     *  plenty for the all-day room guard) at a fraction of the battery cost. */
    @Volatile var lowPower: Boolean = false

    // Advert timestamps from the last few seconds - the UI's "is data flowing" proof.
    private val recent = ArrayDeque<Long>()
    val advertsPerSec: Double
        get() = synchronized(recent) {
            val cutoff = System.currentTimeMillis() - 5_000
            while (recent.isNotEmpty() && recent.first() < cutoff) recent.removeFirst()
            recent.size / 5.0
        }

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true
    val isScanning: Boolean get() = scanning

    fun all(): List<Beacon> = synchronized(beacons) { beacons.values.sortedByDescending { it.smoothedRssi } }

    fun beacon(mac: String): Beacon? = synchronized(beacons) { beacons[mac] }

    /** The Kalman-filtered current level - what all live decisions and meters use.
     *  Null when the beacon was never heard or has been silent past the timeout. */
    fun kalmanRssi(mac: String): Int? = synchronized(beacons) {
        val b = beacons[mac] ?: return@synchronized null
        if (!b.kInitialised) return@synchronized null
        if (System.currentTimeMillis() - b.lastSeen > RoomBeacons.TIMEOUT_MS) return@synchronized null
        Math.round(b.kEstimate).toInt()
    }

    /** Robust current level: trimmed mean (middle 60%) of the last windowMs. */
    fun robustRssi(mac: String, windowMs: Long = 6_000): Int? = synchronized(beacons) {
        val b = beacons[mac] ?: return@synchronized null
        val cutoff = System.currentTimeMillis() - windowMs
        val vals = b.history.filter { it.first >= cutoff }.map { it.second }.sorted()
        if (vals.isEmpty()) return@synchronized null
        val k = vals.size / 5
        Math.round(vals.subList(k, vals.size - k).average()).toInt()
    }

    /** The last `steps` seconds as one-second medians, oldest first; null = silent second. */
    fun recentSeq(mac: String, steps: Int, stepMs: Long = 1_000): List<Int?> = synchronized(beacons) {
        val b = beacons[mac] ?: return@synchronized List(steps) { null }
        val now = System.currentTimeMillis()
        (steps - 1 downTo 0).map { i ->
            val to = now - i * stepMs
            val vals = b.history.filter { it.first in (to - stepMs)..to }.map { it.second }.sorted()
            if (vals.isEmpty()) null else vals[vals.size / 2]
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device?.address ?: return
            val now = System.currentTimeMillis()
            lastResultAt = now
            synchronized(beacons) {
                val b = beacons.getOrPut(mac) { Beacon(mac).apply { smoothedRssi = result.rssi.toDouble() } }
                b.rssi = result.rssi
                b.smoothedRssi = 0.7 * b.smoothedRssi + 0.3 * result.rssi
                b.kalmanUpdate(result.rssi, now)
                b.lastSeen = now
                b.count++
                b.history.addLast(now to result.rssi)
                while (b.history.size > 256 || (b.history.isNotEmpty() && b.history.first().first < now - 15_000)) {
                    b.history.removeFirst()
                }
                try { result.device.name?.let { b.name = it } } catch (_: SecurityException) {}
                result.scanRecord?.deviceName?.let { b.name = it }
                parseIBeacon(result)?.let { (uuid, tx) -> b.iBeaconUuid = uuid; b.txPower = tx }
            }
            synchronized(recent) { recent.addLast(now); if (recent.size > 512) recent.removeFirst() }
            if (now - lastEmit >= 250) { lastEmit = now; onUpdate?.invoke() }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w("BeaconScanner", "scan failed: $errorCode")
            lastScanError = errorCode
            scanning = false
            onUpdate?.invoke()
        }
    }

    /** Call freely from a UI tick. Cycles the scan when: nothing at all heard for 8 s,
     *  the beacons we EXPECT are all silent for 15 s (downgraded scans keep trickling
     *  other devices), or the scan is older than 5 min. Cool-down protected. */
    fun ensureScanning() {
        val now = System.currentTimeMillis()
        if (scanning) {
            val globallySilent = lastResultAt > 0 && now - lastResultAt > 8_000
            val expectedSilent = expectedMacs.isNotEmpty() && now - scanStartedAt > 15_000 &&
                expectedMacs.all { mac -> (beacon(mac)?.lastSeen ?: 0L) < now - 15_000 }
            val old = now - scanStartedAt > 5 * 60_000
            if (!globallySilent && !expectedSilent && !old) return
            if (now - lastStartAttempt < 12_000) return
            stop()
        }
        if (now - lastStartAttempt < 12_000) return
        start()
    }

    /** Returns false if Bluetooth is off/absent or the OS refused (see lastScanError). */
    fun start(): Boolean {
        if (scanning) return true
        lastStartAttempt = System.currentTimeMillis()
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        return try {
            val settings = ScanSettings.Builder()
                .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
            scanner.startScan(null, settings, callback)
            scanning = true
            scanStartedAt = System.currentTimeMillis()
            lastScanError = null
            true
        } catch (t: SecurityException) {
            Log.w("BeaconScanner", "start refused: ${t.message}"); false
        }
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Throwable) {}
    }

    // iBeacon frame parse - optional garnish (identity comes from the MAC).
    private fun parseIBeacon(result: ScanResult): Pair<String, Int>? {
        val data = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return null
        if (data.size < 23 || data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return null
        val uuid = buildString {
            for (i in 2 until 18) {
                append(String.format("%02x", data[i]))
                if (i == 5 || i == 7 || i == 9 || i == 11) append('-')
            }
        }
        return uuid to data[22].toInt()
    }
}


// =====================================================================================
//  PressureMonitor  -  barometer as a floor-change detector.
// =====================================================================================
// Air pressure moves ~0.12 hPa per metre of height, so going up or down a floor is a
// fast ~0.3-0.4 hPa step. Absolute pressure is useless (weather swings are far
// bigger), but a step between "now" and "~20 s ago" is a reliable "you just changed
// floors" signal - used to briefly block a room from turning true right after one.
class PressureMonitor(context: Context) : SensorEventListener {

    companion object {
        /** hPa step between now and ~20 s ago that counts as a floor change (~2.5 m). */
        const val FLOOR_SHIFT_HPA = 0.30f
    }

    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
    val available: Boolean = sensor != null

    private val history = ArrayDeque<Pair<Long, Float>>()   // (timestamp, hPa)
    @Volatile private var lastShiftAt = 0L
    private var registered = false

    fun start() {
        if (registered || sensor == null) return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        registered = true
    }

    fun stop() {
        if (!registered) return
        sm.unregisterListener(this)
        registered = false
    }

    /** ms since the last detected floor change, or null if none seen this session. */
    fun shiftAgoMs(): Long? = if (lastShiftAt == 0L) null else System.currentTimeMillis() - lastShiftAt

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        synchronized(history) {
            history.addLast(now to e.values[0])
            while (history.isNotEmpty() && history.first().first < now - 40_000) history.removeFirst()
        }
        val recent = median(now - 3_000, now) ?: return
        val past = median(now - 25_000, now - 15_000) ?: return
        if (Math.abs(recent - past) >= FLOOR_SHIFT_HPA) lastShiftAt = now
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun median(from: Long, to: Long): Float? = synchronized(history) {
        val vals = history.filter { it.first in from..to }.map { it.second }.sorted()
        if (vals.isEmpty()) null else vals[vals.size / 2]
    }
}


// =====================================================================================
//  SignalMeterView  -  one beacon's red / amber / green scale for one room.
// =====================================================================================
// A horizontal dBm scale (-100 left … -35 right). Red everywhere except this room's
// learned bands: amber = seen in the room at all (outliers trimmed, ± slack), green =
// the core readings (temptation spots / at the beacon). The dark needle is the live
// robust level. Zones differ per room AND per beacon - that's the point.
class SignalMeterView(context: Context) : View(context) {

    private var current: Int? = null
    private var zone: RoomBeacons.Zone? = null
    private var openTop = false   // own beacon: closer than green is still green

    fun update(current: Int?, zone: RoomBeacons.Zone?, openTop: Boolean) {
        this.current = current; this.zone = zone; this.openTop = openTop
        invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val dp = resources.displayMetrics.density
        val minDbm = -100f; val maxDbm = -35f
        val x0 = 10 * dp; val x1 = width - 10 * dp
        val barTop = 3 * dp; val barBot = barTop + 16 * dp
        fun x(v: Float) = x0 + (x1 - x0) * ((v.coerceIn(minDbm, maxDbm) - minDbm) / (maxDbm - minDbm))

        paint.style = Paint.Style.FILL
        paint.color = 0x33C0392B   // red base: anywhere not proven in-room
        canvas.drawRoundRect(x0, barTop, x1, barBot, 8 * dp, 8 * dp, paint)

        val z = zone
        if (z != null) {
            // Own beacon (openTop): the bands run all the way to the loud end -
            // anything closer than the calibrated core is always super green.
            val amberHiX = if (openTop) x1 else x(z.amberHi.toFloat())
            val greenHiX = if (openTop) x1 else x(z.greenHi.toFloat())
            val superHiX = if (openTop) x1 else x(z.superHi.toFloat())
            paint.color = 0x55E0A800   // amber: the uncertain tails
            canvas.drawRect(x(z.amberLo.toFloat()), barTop, amberHiX, barBot, paint)
            paint.color = 0x4D2E9E44   // green: solid in-room readings
            canvas.drawRect(x(z.greenLo.toFloat()), barTop, greenHiX, barBot, paint)
            paint.color = 0xF21B5E20.toInt()   // SUPER green (dark): the usage-spot readings
            canvas.drawRect(x(z.superLo.toFloat()), barTop, superHiX, barBot, paint)
            text.textSize = 9 * dp; text.textAlign = Paint.Align.CENTER; text.color = 0xFF52606A.toInt()
            canvas.drawText("${z.superLo}", x(z.superLo.toFloat()), barBot + 12 * dp, text)
            if (!openTop) canvas.drawText("${z.superHi}", x(z.superHi.toFloat()), barBot + 12 * dp, text)
        }

        current?.let {
            paint.color = 0xFF1F2933.toInt()
            val cx = x(it.toFloat())
            canvas.drawRoundRect(cx - 1.5f * dp, barTop - 3 * dp, cx + 1.5f * dp, barBot + 3 * dp, 2 * dp, 2 * dp, paint)
        }

        text.textSize = 8 * dp; text.color = 0xFF9AA0A6.toInt()
        text.textAlign = Paint.Align.LEFT; canvas.drawText("-100", x0, barBot + 12 * dp, text)
        text.textAlign = Paint.Align.RIGHT; canvas.drawText("-35", x1, barBot + 12 * dp, text)
    }
}
