package com.example.webtrafficmonitor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
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
//  CALIBRATION (the set-up wizard) records labelled fingerprints - what EVERY assigned
//  beacon sounds like - from many tagged places: static spots inside the room, the
//  "temptation spots" where the phone actually gets used, two real ENTRY WALKS (a
//  continuous trace of the approach → through the door → settling, so the data
//  contains what the signal looks like just before entering vs. standing somewhere
//  that merely sounds similar), and a tagged tour of the common OUTSIDE places
//  (doorway, neighbouring room, directly above/below, hallway).
//
//  DECISION (RoomPresence) is a set of independent rule CHECKS - one bar each in the
//  debug UI - and the room is IN only when every check is green:
//    1. Beacon heard      - the room's beacon was heard within TIMEOUT_MS.
//    2. Signal level      - 3-second MEDIAN of the beacon (a range of recent values,
//                           not one reading) is above the quietest calibrated in-room
//                           level minus slack.
//    3. Partner beacon    - the OTHER room's beacon currently sounds like it sounded
//                           from inside this room (range learned in calibration).
//                           This is what kills "downstairs directly under the bedroom":
//                           there the partner sounds wrong even when the own beacon
//                           sounds right.
//    4. Pattern match     - k-nearest-neighbours vote of the current all-beacon vector
//                           against every calibrated spot (inside AND outside labels).
//    5. No floor change   - barometer: air pressure moves ~0.12 hPa per metre, so
//                           going up/down a floor is a sharp step. Right after one,
//                           becoming IN is blocked briefly (rooms don't change floor -
//                           you did). Skipped on phones with no barometer.
//    6. Held steady       - the combined answer must persist FLIP_MS before flipping.
//
//  IDENTITY: beacons are identified by Bluetooth MAC (fixed on beacon hardware).
// =====================================================================================
object RoomBeacons {

    /** The rooms we track. Order = display order. */
    val ROOMS = listOf("bedroom", "bathroom")

    /** A beacon not heard for this long counts as absent (K11 default interval ~1 s). */
    const val TIMEOUT_MS = 10_000L

    /** How long each static sample / find-the-beacon scan collects for. */
    const val SAMPLE_MS = 3_000L

    /** Window for the live "recent values" median that all checks use. */
    const val MEDIAN_WINDOW_MS = 3_000L

    /** Treated as the reading when a beacon isn't heard at all. */
    const val SILENT_DBM = -100

    /** One calibration point: where the user was, in or out, and what every assigned
     *  beacon sounded like from there (MAC -> RSSI). Entry walks record one per second. */
    class Sample(val label: String, val inRoom: Boolean, val readings: Map<String, Int>)

    // ── Per-room config (SharedPreferences "room_beacons") ───────────────────────────
    private const val PREFS = "room_beacons"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun beaconMac(context: Context, room: String): String? =
        prefs(context).getString("$room.mac", null)

    fun setBeaconMac(context: Context, room: String, mac: String?) {
        val old = beaconMac(context, room)
        val e = prefs(context).edit()
        if (mac == null) e.remove("$room.mac").remove("$room.samples")
        else {
            e.putString("$room.mac", mac)
            // A different physical beacon invalidates the old calibration.
            if (mac != old) e.remove("$room.samples")
        }
        // Legacy keys from the first (two-far-ends) cut of this feature.
        e.remove("$room.farA").remove("$room.farB")
        e.apply()
    }

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
                )
            }
        } catch (t: Throwable) { Log.w("RoomBeacons", "bad samples for $room: ${t.message}"); emptyList() }
        synchronized(sampleCache) { sampleCache[room] = raw to parsed }
        return parsed
    }

    fun setSamples(context: Context, room: String, samples: List<Sample>) {
        val arr = JSONArray()
        for (s in samples) arr.put(
            JSONObject().put("label", s.label).put("in", s.inRoom)
                .put("r", JSONObject().apply { s.readings.forEach { (k, v) -> put(k, v) } }),
        )
        prefs(context).edit().putString("$room.samples", arr.toString()).apply()
    }

    /** Appends samples - used by the tagged outside tour. */
    fun addSample(context: Context, room: String, sample: Sample) =
        setSamples(context, room, samples(context, room) + sample)

    fun isCalibrated(context: Context, room: String): Boolean {
        val own = beaconMac(context, room) ?: return false
        return samples(context, room).any { it.inRoom && it.readings.containsKey(own) }
    }

    // ── Permissions ──────────────────────────────────────────────────────────────────
    // Android 12+ uses BLUETOOTH_SCAN, and because we infer location from beacons the
    // manifest deliberately does NOT set neverForLocation (setting it makes Android
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
//  RoomPresence  -  the rule engine: sensors in, per-check verdicts out.
// =====================================================================================
object RoomPresence {

    /** A flip of the combined answer must hold this long before it's believed. */
    const val FLIP_MS = 1_500L

    /** Signal-level check: allowed this far below the quietest calibrated in-room level. */
    const val SIGNAL_SLACK_DB = 4

    /** Partner check: allowed this far outside the partner's calibrated in-room range. */
    const val PARTNER_SLACK_DB = 8

    /** After a barometer floor change, becoming IN is blocked for this long. */
    const val FLOOR_HOLD_MS = 8_000L

    /** One rule's verdict. state: 1 = green, 0 = grey (no data - doesn't block), -1 = red. */
    data class Check(val label: String, val state: Int, val value: String)

    data class Status(
        val room: String,
        val inRoom: Boolean,
        val assigned: Boolean,
        val calibrated: Boolean,
        val rssi: Int?,          // 3 s median of the room's own beacon
        val rawRssi: Int?,       // latest single reading
        val ageMs: Long?,        // since last heard
        val checks: List<Check>, // empty until calibrated
        val summary: String,     // only used while not set up
    )

    private val lastIn = HashMap<String, Boolean>()
    private val pendingSince = HashMap<String, Long>()
    fun reset() { lastIn.clear(); pendingSince.clear() }

    fun evaluate(context: Context, scanner: BeaconScanner, pressure: PressureMonitor?): Map<String, Status> {
        val now = System.currentTimeMillis()
        val statuses = LinkedHashMap<String, Status>()

        for (room in RoomBeacons.ROOMS) {
            val mac = RoomBeacons.beaconMac(context, room)
            if (mac == null) {
                lastIn[room] = false; pendingSince.remove(room)
                statuses[room] = Status(room, false, false, false, null, null, null, emptyList(),
                    "No beacon assigned - run set-up.")
                continue
            }
            val b = scanner.beacon(mac)
            val age = b?.let { now - it.lastSeen }
            val med = scanner.medianRssi(mac, RoomBeacons.MEDIAN_WINDOW_MS)
            val samples = RoomBeacons.samples(context, room)
            val inOwn = samples.filter { it.inRoom }.mapNotNull { it.readings[mac] }
            if (inOwn.isEmpty()) {
                lastIn[room] = false; pendingSince.remove(room)
                statuses[room] = Status(room, false, true, false, med, b?.rssi, age, emptyList(),
                    "Beacon assigned - run set-up to calibrate.")
                continue
            }

            val was = lastIn[room] ?: false
            val checks = mutableListOf<Check>()

            // 1. Beacon heard recently.
            val heard = b != null && age!! <= RoomBeacons.TIMEOUT_MS
            checks += Check("Beacon heard", if (heard) 1 else -1,
                if (age == null) "never" else "${age / 1000}s ago")

            // 2. Signal level: 3 s median vs the quietest calibrated in-room level.
            val floor = inOwn.minOrNull()!! - SIGNAL_SLACK_DB
            val level = med ?: RoomBeacons.SILENT_DBM
            checks += Check("Signal level", if (heard && level >= floor) 1 else -1,
                "$level dBm · room ≥ $floor")

            // 3. Partner beacon: from inside this room the OTHER beacon has a known
            //    loudness range; outside spots that fool check 2 (e.g. the floor
            //    below) put the partner outside that range.
            val otherMac = RoomBeacons.ROOMS.filter { it != room }
                .mapNotNull { RoomBeacons.beaconMac(context, it) }.firstOrNull()
            var partnerState = 0; var partnerValue = "no data yet"
            if (otherMac != null) {
                val inOther = samples.filter { it.inRoom }.mapNotNull { it.readings[otherMac] }
                if (inOther.isNotEmpty()) {
                    val lo = inOther.minOrNull()!! - PARTNER_SLACK_DB
                    val hi = inOther.maxOrNull()!! + PARTNER_SLACK_DB
                    val curO = scanner.medianRssi(otherMac, RoomBeacons.MEDIAN_WINDOW_MS) ?: RoomBeacons.SILENT_DBM
                    partnerState = if (curO in lo..hi) 1 else -1
                    partnerValue = "$curO dBm · room $lo..$hi"
                }
            }
            checks += Check("Partner beacon", partnerState, partnerValue)

            // 4. Pattern match: kNN vote against every calibrated spot.
            val vote = fingerprintVote(context, room, scanner)
            checks += Check("Pattern match",
                when { vote == null -> 0; vote.first -> 1; else -> -1 },
                vote?.second ?: "not enough data")

            // 5. Barometer: just changed floors → hold off on flipping IN (skipped
            //    while already IN, and on phones without the sensor).
            if (pressure?.available == true) {
                val ago = pressure.shiftAgoMs()
                val recentShift = ago != null && ago < FLOOR_HOLD_MS
                checks += Check("No floor change", if (was || !recentShift) 1 else -1,
                    when {
                        ago == null -> "steady"
                        recentShift -> "changed ${ago / 1000}s ago"
                        else -> "steady (${ago / 1000}s)"
                    })
            }

            // Only all-green makes the candidate IN...
            val candidate = checks.none { it.state == -1 }

            // 6. ...and the candidate must hold FLIP_MS before the answer flips.
            val inNow: Boolean
            val steady: Check
            if (candidate == was) {
                pendingSince.remove(room); inNow = was
                steady = Check("Held steady", 1, "stable")
            } else {
                val since = pendingSince.getOrPut(room) { now }
                if (now - since >= FLIP_MS) {
                    pendingSince.remove(room); inNow = candidate
                    steady = Check("Held steady", 1, "confirmed")
                } else {
                    inNow = was
                    steady = Check("Held steady", 0, "confirming ${(FLIP_MS - (now - since) + 999) / 1000}s…")
                }
            }
            checks += steady
            lastIn[room] = inNow
            statuses[room] = Status(room, inNow, true, true, med, b?.rssi, age, checks, "")
        }

        // Exclusivity: if more than one room is all-green, the best pattern match
        // keeps IN and the rest go false (their Pattern-match bar says why).
        val claiming = statuses.values.filter { it.inRoom }
        if (claiming.size > 1) {
            val dists = claiming.associate {
                it.room to (fingerprintDistance(context, it.room, scanner) ?: Double.MAX_VALUE)
            }
            val winner = claiming.minByOrNull { dists[it.room]!! }!!
            for (s in claiming) {
                if (s.room == winner.room) continue
                lastIn[s.room] = false; pendingSince.remove(s.room)
                statuses[s.room] = s.copy(
                    inRoom = false,
                    checks = s.checks.map {
                        if (it.label == "Pattern match") it.copy(state = -1, value = "fits ${winner.room} better") else it
                    },
                )
            }
        }
        return statuses
    }

    // What every assigned beacon sounds like right now, as 3 s medians (a range of
    // recent values, not an instant), silence = SILENT_DBM.
    private fun currentVector(context: Context, scanner: BeaconScanner): Map<String, Double> {
        val macs = RoomBeacons.ROOMS.mapNotNull { RoomBeacons.beaconMac(context, it) }.distinct()
        return macs.associateWith { mac ->
            (scanner.medianRssi(mac, RoomBeacons.MEDIAN_WINDOW_MS) ?: RoomBeacons.SILENT_DBM).toDouble()
        }
    }

    // kNN vote: compare the current vector against every calibrated spot (inside and
    // outside - static spots, temptation spots, entry-walk trace points, tour tags),
    // take the 3 nearest, majority label wins (nearest breaks ties). Needs 2+ beacons
    // and both labels present; returns null otherwise (the check goes grey).
    // Second value = the nearest spot's label, shown on the bar.
    private fun fingerprintVote(context: Context, room: String, scanner: BeaconScanner): Pair<Boolean, String>? {
        val current = currentVector(context, scanner)
        if (current.size < 2) return null
        val spots = RoomBeacons.samples(context, room).filter { it.readings.size >= 2 }
        if (spots.none { it.inRoom } || spots.none { !it.inRoom }) return null
        val ranked = spots.map { spot -> spot to distance(current, spot) }.sortedBy { it.second }
        val k = minOf(3, ranked.size)
        val inVotes = ranked.take(k).count { it.first.inRoom }
        val vote = if (2 * inVotes == k) ranked.first().first.inRoom else 2 * inVotes > k
        return vote to ranked.first().first.label
    }

    /** Distance from the live vector to the room's nearest INSIDE spot (for tie-breaks). */
    private fun fingerprintDistance(context: Context, room: String, scanner: BeaconScanner): Double? {
        val current = currentVector(context, scanner)
        if (current.size < 2) return null
        val inSpots = RoomBeacons.samples(context, room).filter { it.inRoom && it.readings.size >= 2 }
        if (inSpots.isEmpty()) return null
        return inSpots.minOf { distance(current, it) }
    }

    private fun distance(current: Map<String, Double>, spot: RoomBeacons.Sample): Double {
        var sum = 0.0
        for ((mac, cur) in current) {
            val d = cur - (spot.readings[mac] ?: RoomBeacons.SILENT_DBM)
            sum += d * d
        }
        return sqrt(sum / current.size)
    }
}


// =====================================================================================
//  BeaconScanner  -  listens for BLE advertisements and keeps per-beacon state + history.
// =====================================================================================
// Passive listener only: never connects, never pairs. ensureScanning() is the one
// entry point to call from a UI tick: it starts the scan, and also CYCLES it when it
// has gone quiet or old - Android silently downgrades/kills long-running unfiltered
// scans (~30 min cap on many stacks), which shows up as "heard Ns ago" climbing
// forever. Restarts respect a cool-down because Android hard-throttles apps that
// start scans more than 5 times in 30 s. lastScanError / advertsPerSec exist so the
// UI can PROVE data is (or is not) flowing.
class BeaconScanner(context: Context) {

    /** Everything we know about one advertising device. */
    class Beacon(val mac: String) {
        var name: String? = null
        var rssi: Int = 0                 // latest raw reading, dBm
        var smoothedRssi: Double = 0.0    // EMA - display only; decisions use medians
        var lastSeen: Long = 0L
        var txPower: Int? = null
        var iBeaconUuid: String? = null
        var count: Long = 0
        // (timestamp, raw rssi) of recent adverts - feeds the windowed medians.
        val history = ArrayDeque<Pair<Long, Int>>()
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

    /** Snapshot of all beacons heard this session, strongest first. */
    fun all(): List<Beacon> = synchronized(beacons) { beacons.values.sortedByDescending { it.smoothedRssi } }

    fun beacon(mac: String): Beacon? = synchronized(beacons) { beacons[mac] }

    /** Median raw RSSI over the last windowMs - "a range of recent values, not one". */
    fun medianRssi(mac: String, windowMs: Long): Int? = synchronized(beacons) {
        val b = beacons[mac] ?: return@synchronized null
        val cutoff = System.currentTimeMillis() - windowMs
        val vals = b.history.filter { it.first >= cutoff }.map { it.second }.sorted()
        if (vals.isEmpty()) null else vals[vals.size / 2]
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

    /** Call freely from a UI tick: starts the scan if needed, and cycles a scan that
     *  has gone silent (>8 s with nothing at all) or old (>10 min), because Android
     *  quietly stops delivering to long-lived scans. Cool-down protected. */
    fun ensureScanning() {
        val now = System.currentTimeMillis()
        if (scanning) {
            val silent = lastResultAt > 0 && now - lastResultAt > 8_000
            val old = now - scanStartedAt > 10 * 60_000
            if (!silent && !old) return
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
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
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

    // The K11 can broadcast Apple's iBeacon format: manufacturer 0x004C, payload
    // 0x02 0x15 + 16-byte UUID + major + minor + calibrated tx power. Optional
    // garnish (identity comes from the MAC).
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
// floors" signal - used to briefly block a room from turning IN right after one.
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

    /** Estimated height change between ~20 s ago and now, metres (+ = up). Null = no data. */
    val heightDeltaM: Float?
        get() {
            val now = System.currentTimeMillis()
            val recent = median(now - 3_000, now) ?: return null
            val past = median(now - 25_000, now - 15_000) ?: return null
            return (past - recent) * 8.4f   // pressure falls as you rise: ~8.4 m per hPa
        }

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
