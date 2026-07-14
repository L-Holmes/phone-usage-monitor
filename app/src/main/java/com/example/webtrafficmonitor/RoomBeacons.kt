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
import android.os.Build
import android.util.Log
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

// =====================================================================================
//  ROOM BEACONS  -  "which room is the phone in?" from BLE beacons (KKM K11).
// =====================================================================================
//
//  HOW IT WORKS: each room gets one beacon. The beacon just shouts its identity over
//  Bluetooth Low Energy a few times a second; the phone listens and measures how LOUD
//  the shout is (RSSI, in dBm - a negative number, closer to 0 = nearer). We never
//  connect to the beacon; we only overhear its advertisements. The K11's factory
//  settings are enough - neither we nor end users need KKM's own app.
//
//  There is no usable "distance" from BLE: distance estimates ARE just RSSI plus a
//  formula that's wrong in every real room (walls, bodies, furniture). So instead of
//  pretending, we calibrate in place:
//
//  CALIBRATION: the set-up wizard walks the user around the room - next to the beacon,
//  the centre, the far corners - and just outside it (doorway, next room). At each
//  spot it records the average smoothed RSSI of EVERY assigned beacon (a fingerprint,
//  not just the room's own beacon). From the room's own beacon this yields:
//      inMin  = the quietest reading taken INSIDE the room
//      outMax = the loudest reading taken OUTSIDE the room
//  The in/out boundary is the midpoint between them, with a hysteresis band so the
//  answer can't flap when the signal sits on the line:
//      enter  = midpoint + HYSTERESIS_DB   (must be at least this loud to become IN)
//      exit   = midpoint - HYSTERESIS_DB   (drops below this to become OUT)
//
//  DECISION (RoomPresence): the room's beacon must have been heard within TIMEOUT_MS.
//  Then, whenever BOTH beacons are assigned and calibrated, the primary evidence is
//  the FINGERPRINT VOTE (fingerprintVote below): the pattern across all beacons vs
//  the calibration spots. A lone beacon's loudness cannot tell "in the bedroom" from
//  "downstairs directly under it" - the pattern across two beacons can. The
//  single-beacon enter/exit gate is only the fallback when multi-beacon data is
//  missing. Flips are debounced (FLIP_MS), and if several rooms still claim IN at
//  once the best fingerprint match wins and the rest go false.
//
//  IDENTITY: beacons are identified by Bluetooth MAC address. Beacon hardware (unlike
//  phones) advertises with a fixed MAC, so it is a stable ID.
// =====================================================================================
object RoomBeacons {

    /** The rooms we track. Order = display order. */
    val ROOMS = listOf("bedroom", "bathroom")

    /** A beacon not heard for this long counts as absent (K11 default interval ~1 s). */
    const val TIMEOUT_MS = 10_000L

    /** How long each wizard sample (and the find-the-beacon scan) collects for. */
    const val SAMPLE_MS = 3_000L

    /** Half-width of the hysteresis band around the in/out midpoint. */
    const val HYSTERESIS_DB = 2

    /** No outside samples taken: guess the gate this far below the quietest in-room spot. */
    const val FALLBACK_ENTER_DB = 6
    const val FALLBACK_EXIT_DB = 10

    /** Treated as "silent" when a beacon in a fingerprint wasn't heard at all. */
    const val SILENT_DBM = -100

    /** One calibration spot: where the user stood, in or out, and what every assigned
     *  beacon sounded like from there (MAC -> averaged smoothed RSSI). */
    class Sample(val label: String, val inRoom: Boolean, val readings: Map<String, Int>)

    /** The armed decision boundary for a room, derived from its samples. */
    data class Thresholds(val enter: Int, val exit: Int, val inMin: Int, val outMax: Int?) {
        /** dB gap between quietest-inside and loudest-outside; small = unreliable room. */
        val separation: Int? get() = outMax?.let { inMin - it }
    }

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

    fun samples(context: Context, room: String): List<Sample> {
        val raw = prefs(context).getString("$room.samples", null) ?: return emptyList()
        return try {
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
    }

    fun setSamples(context: Context, room: String, samples: List<Sample>) {
        val arr = JSONArray()
        for (s in samples) arr.put(
            JSONObject().put("label", s.label).put("in", s.inRoom)
                .put("r", JSONObject().apply { s.readings.forEach { (k, v) -> put(k, v) } }),
        )
        prefs(context).edit().putString("$room.samples", arr.toString()).apply()
    }

    /** Appends one sample - used by the "it's wrong here, fix it" corrective flow. */
    fun addSample(context: Context, room: String, sample: Sample) =
        setSamples(context, room, samples(context, room) + sample)

    /** The armed boundary, or null until the room has at least one in-room sample. */
    fun thresholds(context: Context, room: String): Thresholds? {
        val own = beaconMac(context, room) ?: return null
        val all = samples(context, room)
        val inVals = all.filter { it.inRoom }.mapNotNull { it.readings[own] }
        if (inVals.isEmpty()) return null
        val outVals = all.filter { !it.inRoom }.mapNotNull { it.readings[own] }
        val inMin = inVals.minOrNull()!!
        val outMax = outVals.maxOrNull()
        return when {
            // No outside data: guess a gate a bit below the quietest in-room spot.
            outMax == null -> Thresholds(inMin - FALLBACK_ENTER_DB, inMin - FALLBACK_EXIT_DB, inMin, null)
            // Healthy: outside is clearly quieter than inside - gate at the midpoint.
            outMax < inMin - 2 * HYSTERESIS_DB -> {
                val mid = (inMin + outMax) / 2
                Thresholds(mid + HYSTERESIS_DB, mid - HYSTERESIS_DB, inMin, outMax)
            }
            // Overlap: outside sounds as loud as inside. Best-effort split just under
            // the in-room floor; the UI warns that this room needs the beacon moved.
            else -> Thresholds(inMin - 1, inMin - 4, inMin, outMax)
        }
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
//  RoomPresence  -  turns scanner readings + calibration into per-room true/false.
// =====================================================================================
object RoomPresence {

    data class Status(
        val room: String,
        val inRoom: Boolean,
        val reason: String,            // human-readable "why", shown on the debug card
        val assigned: Boolean,
        val rssi: Double?,             // smoothed, null if never heard this session
        val rawRssi: Int?,
        val ageMs: Long?,              // since last heard
        val thresholds: RoomBeacons.Thresholds?,
    )

    /** A state flip must hold this long before it's believed (stops boundary flapping). */
    const val FLIP_MS = 1_500L

    // Hysteresis memory (was each room IN?) and pending-flip timestamps for debounce.
    private val lastIn = HashMap<String, Boolean>()
    private val pendingSince = HashMap<String, Long>()
    fun reset() { lastIn.clear(); pendingSince.clear() }

    fun evaluate(context: Context, scanner: BeaconScanner): Map<String, Status> {
        val now = System.currentTimeMillis()
        val statuses = LinkedHashMap<String, Status>()

        // Pass 1: each room independently. Primary evidence is the fingerprint vote
        // (the pattern across ALL beacons vs the calibration spots) - that's what can
        // tell "in the bedroom" from "directly below it on another floor", which a
        // single beacon's loudness fundamentally cannot. The single-beacon enter/exit
        // gate is the fallback when there isn't enough multi-beacon data.
        for (room in RoomBeacons.ROOMS) {
            val mac = RoomBeacons.beaconMac(context, room)
            val th = if (mac == null) null else RoomBeacons.thresholds(context, room)
            val b = if (mac == null) null else scanner.beacon(mac)
            val age = b?.let { now - it.lastSeen }

            fun out(reason: String) {
                lastIn[room] = false; pendingSince.remove(room)
                statuses[room] = Status(room, false, reason, mac != null, b?.smoothedRssi, b?.rssi, age, th)
            }

            when {
                mac == null -> out("No beacon assigned to this room yet.")
                th == null -> out("Beacon assigned - run set-up to calibrate before detection can arm.")
                b == null -> out("Beacon hasn't been heard since this page opened.")
                age!! > RoomBeacons.TIMEOUT_MS -> out("Beacon last heard ${age / 1000}s ago (>${RoomBeacons.TIMEOUT_MS / 1000}s) → treated as away.")
                else -> {
                    val was = lastIn[room] ?: false
                    val r = Math.round(b.smoothedRssi)
                    val vote = fingerprintVote(context, room, scanner)
                    val gate = if (was) b.smoothedRssi >= th.exit else b.smoothedRssi >= th.enter
                    val candidate = vote?.first ?: gate
                    // Debounce: a flip must persist FLIP_MS before it's accepted.
                    val inNow: Boolean
                    if (candidate == was) { pendingSince.remove(room); inNow = was }
                    else {
                        val since = pendingSince.getOrPut(room) { now }
                        inNow = if (now - since >= FLIP_MS) { pendingSince.remove(room); candidate } else was
                    }
                    lastIn[room] = inNow
                    val confirming = if (inNow != candidate) "  (confirming…)" else ""
                    val reason = when {
                        vote != null -> "Signal pattern across both beacons matches \"${vote.second}\" → ${if (vote.first) "IN" else "OUT"}.$confirming"
                        gate && was -> "Signal $r dBm is above the exit line (${th.exit}) - holding IN.$confirming"
                        gate -> "Signal $r dBm crossed the enter line (${th.enter}) → IN.$confirming"
                        was -> "Signal $r dBm fell below the exit line (${th.exit}) → OUT.$confirming"
                        else -> "Signal $r dBm is below the enter line (${th.enter}) - staying OUT.$confirming"
                    }
                    statuses[room] = Status(room, inNow, reason, true, b.smoothedRssi, b.rssi, age, th)
                }
            }
        }

        // Pass 2: exclusivity. If more than one room claims IN, the fingerprints
        // decide (how well does what ALL beacons sound like right now match each
        // room's calibration spots?); without multi-beacon data, the room that is
        // deeper above its own exit line wins.
        val claiming = statuses.values.filter { it.inRoom }
        if (claiming.size > 1) {
            val dists = claiming.associate { it.room to fingerprintDistance(context, it.room, scanner) }
            val winner = if (dists.values.all { it != null }) claiming.minByOrNull { dists[it.room]!! }!!
                         else claiming.maxByOrNull { it.rssi!! - it.thresholds!!.exit }!!
            for (s in claiming) {
                if (s.room == winner.room) continue
                lastIn[s.room] = false; pendingSince.remove(s.room)
                statuses[s.room] = s.copy(
                    inRoom = false,
                    reason = "Both rooms matched, but the signal pattern fits ${winner.room} better → OUT.",
                )
            }
        }
        return statuses
    }

    // The "triangulation": with 2+ beacons assigned and calibration spots that recorded
    // both, the decision is a k-nearest-neighbours vote. Compare what every beacon
    // sounds like RIGHT NOW against every calibration spot (inside and outside ones),
    // take the 3 closest spots, majority label wins (nearest breaks ties). This is what
    // catches "downstairs directly under the bedroom": from there the bedroom beacon is
    // loud but the bathroom beacon sounds wrong, so the nearest spot is an outside one.
    // Returns null when there isn't enough multi-beacon data (callers fall back to the
    // single-beacon gate). Second value = the nearest spot's label, for the debug UI.
    private fun fingerprintVote(context: Context, room: String, scanner: BeaconScanner): Pair<Boolean, String>? {
        val macs = RoomBeacons.ROOMS.mapNotNull { RoomBeacons.beaconMac(context, it) }.distinct()
        if (macs.size < 2) return null
        val spots = RoomBeacons.samples(context, room).filter { it.readings.size >= 2 }
        if (spots.none { it.inRoom } || spots.none { !it.inRoom }) return null
        val now = System.currentTimeMillis()
        val current = macs.associateWith { mac ->
            scanner.beacon(mac)?.takeIf { now - it.lastSeen <= RoomBeacons.TIMEOUT_MS }
                ?.smoothedRssi ?: RoomBeacons.SILENT_DBM.toDouble()
        }
        val ranked = spots.map { spot ->
            var sum = 0.0
            for (mac in macs) {
                val d = current[mac]!! - (spot.readings[mac] ?: RoomBeacons.SILENT_DBM)
                sum += d * d
            }
            spot to sqrt(sum / macs.size)
        }.sortedBy { it.second }
        val k = minOf(3, ranked.size)
        val inVotes = ranked.take(k).count { it.first.inRoom }
        val vote = if (2 * inVotes == k) ranked.first().first.inRoom else 2 * inVotes > k
        return vote to ranked.first().first.label
    }

    // Distance from "what every assigned beacon sounds like right now" to the nearest
    // of this room's in-room calibration spots. Needs 2+ assigned beacons and samples
    // that recorded more than one beacon; returns null otherwise. Smaller = better match.
    private fun fingerprintDistance(context: Context, room: String, scanner: BeaconScanner): Double? {
        val macs = RoomBeacons.ROOMS.mapNotNull { RoomBeacons.beaconMac(context, it) }.distinct()
        if (macs.size < 2) return null
        val inSpots = RoomBeacons.samples(context, room).filter { it.inRoom && it.readings.size >= 2 }
        if (inSpots.isEmpty()) return null
        val now = System.currentTimeMillis()
        val current = macs.associateWith { mac ->
            scanner.beacon(mac)?.takeIf { now - it.lastSeen <= RoomBeacons.TIMEOUT_MS }
                ?.smoothedRssi ?: RoomBeacons.SILENT_DBM.toDouble()
        }
        return inSpots.minOf { spot ->
            var sum = 0.0
            for (mac in macs) {
                val d = current[mac]!! - (spot.readings[mac] ?: RoomBeacons.SILENT_DBM)
                sum += d * d
            }
            sqrt(sum / macs.size)
        }
    }
}


// =====================================================================================
//  BeaconScanner  -  listens for BLE advertisements and keeps the latest per-beacon state.
// =====================================================================================
// Passive listener only: never connects, never pairs. start() begins scanning (caller
// must hold the permissions above and have Bluetooth on), stop() releases everything.
// If a scan dies (onScanFailed), ensureScanning() restarts it - but with a cool-down,
// because hammering startScan trips Android's 5-starts-per-30s throttle and makes the
// failure permanent. lastScanError / advertsPerSec exist so the UI can PROVE to the
// user that data is (or is not) flowing, rather than silently showing stale numbers.
class BeaconScanner(context: Context) {

    /** Everything we know about one advertising device, newest reading last. */
    class Beacon(val mac: String) {
        var name: String? = null
        var rssi: Int = 0                 // latest raw reading, dBm
        var smoothedRssi: Double = 0.0    // EMA over readings - what decisions use
        var lastSeen: Long = 0L
        var txPower: Int? = null          // from the iBeacon frame, if it sends one
        var iBeaconUuid: String? = null   // null until an iBeacon frame is parsed
        var count: Long = 0               // total adverts heard this session
    }

    private val adapter: BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val beacons = LinkedHashMap<String, Beacon>()
    private var scanning = false
    private var lastStartAttempt = 0L
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

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device?.address ?: return
            synchronized(beacons) {
                val b = beacons.getOrPut(mac) { Beacon(mac).apply { smoothedRssi = result.rssi.toDouble() } }
                b.rssi = result.rssi
                // EMA: heavy enough smoothing to stop flicker, light enough to follow a
                // walk between rooms within a few seconds.
                b.smoothedRssi = 0.7 * b.smoothedRssi + 0.3 * result.rssi
                b.lastSeen = System.currentTimeMillis()
                b.count++
                try { result.device.name?.let { b.name = it } } catch (_: SecurityException) {}
                result.scanRecord?.deviceName?.let { b.name = it }
                parseIBeacon(result)?.let { (uuid, tx) -> b.iBeaconUuid = uuid; b.txPower = tx }
            }
            synchronized(recent) { recent.addLast(System.currentTimeMillis()); if (recent.size > 512) recent.removeFirst() }
            val now = System.currentTimeMillis()
            if (now - lastEmit >= 250) { lastEmit = now; onUpdate?.invoke() }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w("BeaconScanner", "scan failed: $errorCode")
            lastScanError = errorCode
            scanning = false
            onUpdate?.invoke()
        }
    }

    /** Start if not already scanning, respecting the restart cool-down. Call freely
     *  from a UI tick - it is safe to spam. */
    fun ensureScanning() {
        if (scanning) return
        if (System.currentTimeMillis() - lastStartAttempt < 10_000) return
        start()
    }

    /** Returns false if Bluetooth is off/absent or the OS refused (see lastScanError). */
    fun start(): Boolean {
        if (scanning) return true
        lastStartAttempt = System.currentTimeMillis()
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        return try {
            // Low latency + aggressive matching = several readings a second, which
            // calibration and live debugging need. Fine for a foreground page; a
            // background version of this would want SCAN_MODE_BALANCED.
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
            scanner.startScan(null, settings, callback)
            scanning = true
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
    // 0x02 0x15 + 16-byte UUID + major + minor + calibrated tx power. Parsing it is
    // optional garnish (identity comes from the MAC); it just makes the debug page
    // more informative when the beacon is configured as an iBeacon.
    private fun parseIBeacon(result: ScanResult): Pair<String, Int>? {
        val data = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return null
        if (data.size < 23 || data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return null
        val uuid = buildString {
            for (i in 2 until 18) {
                append(String.format("%02x", data[i]))
                if (i == 5 || i == 7 || i == 9 || i == 11) append('-')
            }
        }
        return uuid to data[22].toInt()   // signed byte: tx power at 1 m, dBm
    }
}


// =====================================================================================
//  SignalMeterView  -  the room card's "where is the signal vs the boundaries" bar.
// =====================================================================================
// A horizontal dBm scale (-95 left … -35 right). Red zone = OUT (below exit), amber =
// hysteresis band, green = IN (above enter). Dots are the calibration spots (green =
// taken inside the room, grey = outside); the dark needle is the live smoothed RSSI.
class SignalMeterView(context: Context) : View(context) {

    private var current: Double? = null
    private var thresholds: RoomBeacons.Thresholds? = null
    private var inSamples: List<Int> = emptyList()
    private var outSamples: List<Int> = emptyList()

    fun update(current: Double?, thresholds: RoomBeacons.Thresholds?, inSamples: List<Int>, outSamples: List<Int>) {
        this.current = current; this.thresholds = thresholds
        this.inSamples = inSamples; this.outSamples = outSamples
        invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val dp = resources.displayMetrics.density
        val minDbm = -95f; val maxDbm = -35f
        val x0 = 10 * dp; val x1 = width - 10 * dp
        val barTop = 4 * dp; val barBot = barTop + 20 * dp
        fun x(v: Float) = x0 + (x1 - x0) * ((v.coerceIn(minDbm, maxDbm) - minDbm) / (maxDbm - minDbm))

        paint.style = Paint.Style.FILL
        paint.color = 0xFFE7EAED.toInt()
        canvas.drawRoundRect(x0, barTop, x1, barBot, 10 * dp, 10 * dp, paint)

        val th = thresholds
        if (th != null) {
            paint.color = 0x26B00020; canvas.drawRect(x0, barTop, x(th.exit.toFloat()), barBot, paint)
            paint.color = 0x33E0A800; canvas.drawRect(x(th.exit.toFloat()), barTop, x(th.enter.toFloat()), barBot, paint)
            paint.color = 0x332E9E44; canvas.drawRect(x(th.enter.toFloat()), barTop, x1, barBot, paint)
            // Threshold ticks with their dBm values underneath.
            text.textSize = 10 * dp; text.textAlign = Paint.Align.CENTER; text.color = 0xFF52606A.toInt()
            for (v in intArrayOf(th.exit, th.enter)) {
                paint.color = 0xFF52606A.toInt()
                canvas.drawRect(x(v.toFloat()) - dp, barTop - 2 * dp, x(v.toFloat()) + dp, barBot + 2 * dp, paint)
                canvas.drawText("$v", x(v.toFloat()), barBot + 14 * dp, text)
            }
        }

        // Calibration spots.
        val cy = (barTop + barBot) / 2
        for (v in outSamples) { paint.color = 0xFF9AA0A6.toInt(); canvas.drawCircle(x(v.toFloat()), cy, 3.5f * dp, paint) }
        for (v in inSamples) { paint.color = 0xFF2E7D32.toInt(); canvas.drawCircle(x(v.toFloat()), cy, 3.5f * dp, paint) }

        // Live needle.
        current?.let {
            paint.color = 0xFF1F2933.toInt()
            val cx = x(it.toFloat())
            canvas.drawRoundRect(cx - 1.5f * dp, barTop - 3 * dp, cx + 1.5f * dp, barBot + 3 * dp, 2 * dp, 2 * dp, paint)
        }

        // Scale ends.
        text.textSize = 9 * dp; text.color = 0xFF9AA0A6.toInt()
        text.textAlign = Paint.Align.LEFT; canvas.drawText("-95", x0, barBot + 14 * dp, text)
        text.textAlign = Paint.Align.RIGHT; canvas.drawText("-35 dBm", x1, barBot + 14 * dp, text)
    }
}
