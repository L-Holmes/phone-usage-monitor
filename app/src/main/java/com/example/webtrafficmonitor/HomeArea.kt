package com.example.webtrafficmonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

// =====================================================================================
//  HOME AREA  -  "is the phone at the house, or out?" from GPS / network location.
// =====================================================================================
//
//  Same shape as RoomBeacons: a store (where home is), a rule engine (are we in it?),
//  a live monitor the debug page drives, and a watch the accessibility service runs all
//  day ([HomeAreaWatch] -> [HomeAreaContext]). Rooms answer "which room"; this answers
//  the coarser "at the house at all" - which is the question that decides whether the
//  phone is in doom-scrolling territory or out doing something.
//
//  The verdict is tracked WITH THE APP CLOSED (that is the whole point - see
//  HomeAreaWatch). What is still deliberately absent is ENFORCEMENT: nothing blocks an
//  app off the back of the verdict yet. Set-up is also still manual and developer-only:
//  stand in the house, press the button, and the current fix becomes home.
//
//  ON VPNs: a VPN cannot move this. Android's location comes from GPS satellites, plus
//  nearby Wi-Fi/cell fingerprints - none of which travel over the tunnel. A VPN changes
//  the IP a *website* geolocates, not what LocationManager reports. (What CAN lie to us
//  is a mock-location app in developer options - see [isMock].)
//
//  ACCURACY IS PART OF THE ANSWER, not a footnote. A fix carries a radius (68%
//  confidence); a 40 m reading with 60 m of error says nothing about a 50 m circle. So
//  the verdict is four-way, the same true / maybe / false honesty the room detection
//  uses: HOME only when the whole error circle is inside, AWAY only when it is wholly
//  outside, MAYBE when it straddles the edge, UNKNOWN when there is no usable fix.
// =====================================================================================
object HomeArea {

    /** "Close to the house" radius, metres. */
    const val RADIUS_M = 50f

    /** A fix older than this tells us where the phone WAS, not where it is. */
    const val MAX_FIX_AGE_MS = 2 * 60_000L

    /** Phones habitually report a hopeful 3-5 m; never trust a fix as tighter than this. */
    const val MIN_ACCURACY_M = 8f

    /** true / maybe / false / no idea. */
    enum class Verdict { HOME, MAYBE, AWAY, UNKNOWN }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("home_area", Context.MODE_PRIVATE)

    // ── Where home is ────────────────────────────────────────────────────────────────
    // One point, captured by hand from the dev page while standing in the house. Stored
    // with the accuracy and provider of the fix it came from, because a home point
    // captured off a 500 m network fix is worth knowing about before trusting anything
    // downstream of it.

    fun isSet(context: Context): Boolean = prefs(context).contains("lat")

    fun homeLat(context: Context): Double = prefs(context).getFloat("lat", 0f).toDouble()
    fun homeLon(context: Context): Double = prefs(context).getFloat("lon", 0f).toDouble()

    /** Accuracy (m) of the fix home was captured from, or -1 if unknown/unset. */
    fun homeAccuracy(context: Context): Float = prefs(context).getFloat("acc", -1f)

    /** Which provider gave the home fix ("gps" / "network"), or "" if unset. */
    fun homeProvider(context: Context): String = prefs(context).getString("provider", "") ?: ""

    /** When home was captured (epoch ms), or 0. */
    fun homeSetAt(context: Context): Long = prefs(context).getLong("set_at", 0L)

    /** Records [loc] as home. Deliberately overwrites - there is only ever one house. */
    fun setHome(context: Context, loc: Location) {
        // Float, not Double: ~7 significant figures still resolves to about a metre of
        // latitude, and SharedPreferences has no double.
        prefs(context).edit()
            .putFloat("lat", loc.latitude.toFloat())
            .putFloat("lon", loc.longitude.toFloat())
            .putFloat("acc", if (loc.hasAccuracy()) loc.accuracy else -1f)
            .putString("provider", loc.provider ?: "")
            .putLong("set_at", System.currentTimeMillis())
            .apply()
    }

    fun clearHome(context: Context) = prefs(context).edit().clear().apply()

    // ── The rule ─────────────────────────────────────────────────────────────────────

    /** Metres from home to [loc], or null if home isn't set. */
    fun distanceFrom(context: Context, loc: Location?): Float? {
        if (loc == null || !isSet(context)) return null
        val out = FloatArray(1)
        Location.distanceBetween(homeLat(context), homeLon(context), loc.latitude, loc.longitude, out)
        return out[0]
    }

    /** The error radius we'll actually work with: the phone's, floored (see MIN_ACCURACY_M). */
    fun usableAccuracy(loc: Location?): Float =
        if (loc != null && loc.hasAccuracy()) loc.accuracy.coerceAtLeast(MIN_ACCURACY_M) else Float.NaN

    fun ageMs(loc: Location?): Long =
        if (loc == null) Long.MAX_VALUE else System.currentTimeMillis() - loc.time

    /**
     * HOME / MAYBE / AWAY / UNKNOWN for [loc].
     *
     * The error circle decides: inside only when distance + accuracy still fits in the
     * radius, outside only when distance - accuracy clears it. Anything overlapping the
     * boundary is MAYBE and must not be treated as either - guessing "at home" off a
     * vague fix and locking someone's phone in a supermarket is the failure mode this
     * exists to prevent (same principle as SensorContext: unknown never blocks).
     */
    fun verdict(context: Context, loc: Location?): Verdict {
        if (!isSet(context) || loc == null) return Verdict.UNKNOWN
        if (ageMs(loc) > MAX_FIX_AGE_MS) return Verdict.UNKNOWN
        val d = distanceFrom(context, loc) ?: return Verdict.UNKNOWN
        val acc = usableAccuracy(loc)
        if (acc.isNaN()) return Verdict.UNKNOWN
        return when {
            d + acc <= RADIUS_M -> Verdict.HOME
            d - acc > RADIUS_M -> Verdict.AWAY
            else -> Verdict.MAYBE
        }
    }

    /** One line explaining the verdict, for the debug page. */
    fun explain(context: Context, loc: Location?): String {
        if (!isSet(context)) return "No home point saved yet - press \"I'm in my house\"."
        if (loc == null) return "Waiting for a location fix…"
        val age = ageMs(loc)
        if (age > MAX_FIX_AGE_MS) return "Last fix is ${age / 1000}s old - too stale to judge."
        val d = distanceFrom(context, loc) ?: return "No home point saved."
        val acc = usableAccuracy(loc)
        return when (verdict(context, loc)) {
            Verdict.HOME -> "${Math.round(d)} m from home, ±${Math.round(acc)} m - the whole error circle is inside the ${Math.round(RADIUS_M)} m radius."
            Verdict.AWAY -> "${Math.round(d)} m from home, ±${Math.round(acc)} m - wholly outside the ${Math.round(RADIUS_M)} m radius."
            Verdict.MAYBE -> "${Math.round(d)} m from home, ±${Math.round(acc)} m - the error circle straddles the ${Math.round(RADIUS_M)} m edge, so this decides nothing."
            Verdict.UNKNOWN -> "No usable fix."
        }
    }

    /** True when the fix came from a mock-location app rather than the hardware. */
    @Suppress("DEPRECATION")
    fun isMock(loc: Location?): Boolean = when {
        loc == null -> false
        Build.VERSION.SDK_INT >= 31 -> loc.isMock
        else -> loc.isFromMockProvider
    }

    // ── Permissions / system state ───────────────────────────────────────────────────
    //
    //  TWO SEPARATE GRANTS, and they must be asked for in that order:
    //
    //    1. fine + coarse  ("While using the app")  - requestPermissions(requiredPermissions())
    //    2. background     ("Allow all the time")   - a SECOND request, on its own.
    //
    //  From Android 10 (API 29) background location is its own permission. From Android
    //  11 (API 30) it must be a separate request - ask for it in the same call as fine
    //  location and the system ignores the lot and grants NOTHING. And on 11+ the "Allow
    //  all the time" option is not in the runtime dialog at all: the request either
    //  bounces the user to the app's location settings page or is refused outright,
    //  depending on version and OEM. So the UI always offers the settings deep link as
    //  well - see [backgroundState] and the dev page.
    //
    //  Below API 29 there is no such thing: fine location IS all-the-time location.

    fun requiredPermissions(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** Fine location granted (coarse alone can't resolve a 50 m circle). */
    fun hasFine(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The separate "all the time" grant, to be requested ON ITS OWN and only after fine. */
    fun backgroundPermission(): Array<String> = arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Does this Android version even have a separate background grant? (Android 10+.) */
    fun backgroundIsSeparate(): Boolean = Build.VERSION.SDK_INT >= 29

    /** True when location works with the app closed. */
    fun hasBackground(context: Context): Boolean =
        if (!backgroundIsSeparate()) hasFine(context)
        else context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** ALWAYS / WHILE_USING / NONE - what the user has actually granted. */
    enum class Access { ALWAYS, WHILE_USING, NONE }

    fun access(context: Context): Access = when {
        !hasPermissions(context) -> Access.NONE
        hasBackground(context) -> Access.ALWAYS
        else -> Access.WHILE_USING
    }

    /**
     * True when asking for background through the runtime dialog is worth a try. On
     * Android 11+ the dialog usually won't grant it - the settings page is the real
     * route - but trying first costs one tap and works on 10.
     */
    fun backgroundRequestable(): Boolean = backgroundIsSeparate() && Build.VERSION.SDK_INT < 30

    /** Is the system location toggle on at all? */
    fun locationEnabled(context: Context): Boolean {
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Throwable) { false }
    }
}


// =====================================================================================
//  LocationMonitor  -  a live fix, at whatever cadence the caller can afford.
// =====================================================================================
/**
 * Listens to network + passive location (cheap) and optionally GPS (not cheap), keeping
 * the best current fix. Register with start(), release with stop() - and stop() genuinely
 * matters here: a forgotten GPS listener is the most expensive thing this app could leave
 * running. [setGps] engages and drops the GPS radio without disturbing the rest, which is
 * how [HomeAreaWatch] pays for precision only when it needs it.
 *
 * Mirrors [SensorMonitor]: onUpdate fires whenever the held fix changes.
 */
class LocationMonitor(context: Context) : LocationListener {

    private val app = context.applicationContext
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** The best fix we currently hold. */
    @Volatile var last: Location? = null; private set

    var onUpdate: (() -> Unit)? = null

    val hasGps: Boolean get() = try { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true } catch (_: Throwable) { false }
    val hasNetwork: Boolean get() = try { lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true } catch (_: Throwable) { false }

    /** True while the GPS radio is engaged (for the debug page's battery readout). */
    @Volatile var gpsEngaged = false; private set

    private var running = false
    private var intervalMs = 1_000L
    private var minDistanceM = 0f

    /**
     * @param intervalMs  fastest update rate to ask for (the system may give less).
     * @param minDistanceM  don't report until the phone has moved this far.
     * @param gps  engage the GPS radio straight away.
     */
    fun start(intervalMs: Long = 1_000L, minDistanceM: Float = 0f, gps: Boolean = true) {
        if (lm == null || !HomeArea.hasPermissions(app)) return
        this.intervalMs = intervalMs
        this.minDistanceM = minDistanceM
        if (running && gpsEngaged == gps) return
        running = true
        // Seed from whatever the system already has so nothing is blank while GPS warms
        // up (that first fix can take 30 s outdoors, longer indoors).
        for (p in ALL_PROVIDERS) {
            try { lm.getLastKnownLocation(p)?.let { accept(it) } } catch (_: SecurityException) { } catch (_: IllegalArgumentException) { }
        }
        register(gps)
        onUpdate?.invoke()
    }

    fun stop() {
        running = false; gpsEngaged = false
        try { lm?.removeUpdates(this) } catch (_: SecurityException) { }
    }

    /** Engage or drop GPS mid-flight. No-op if it's already in that state. */
    fun setGps(on: Boolean) {
        if (!running || on == gpsEngaged) return
        register(on)
    }

    private fun register(gps: Boolean) {
        val lm = lm ?: return
        try { lm.removeUpdates(this) } catch (_: SecurityException) { }
        gpsEngaged = gps
        val wanted = mutableListOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (gps) wanted.add(LocationManager.GPS_PROVIDER)
        for (p in wanted) {
            try {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, intervalMs, minDistanceM, this)
            } catch (_: SecurityException) { } catch (_: IllegalArgumentException) { }
        }
    }

    /**
     * Keep [candidate] if it beats what we hold. "Beats" = we hold nothing, what we hold
     * has gone stale, or the new fix is at least as tight. Without the accuracy test a
     * single sloppy network fix would repeatedly stamp on a good GPS one.
     */
    private fun accept(candidate: Location) {
        val current = last
        val better = when {
            current == null -> true
            candidate.time - current.time > STALE_MS -> true
            candidate.time < current.time -> false
            !current.hasAccuracy() -> true
            !candidate.hasAccuracy() -> false
            else -> candidate.accuracy <= current.accuracy
        }
        if (better) last = candidate
    }

    override fun onLocationChanged(location: Location) {
        accept(location)
        onUpdate?.invoke()
    }

    // Pre-30 devices have no default implementations for these, so they must be here or
    // the framework hits an AbstractMethodError the first time a provider changes state.
    @Deprecated("Required for API < 30")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) { onUpdate?.invoke() }
    override fun onProviderDisabled(provider: String) { onUpdate?.invoke() }

    private companion object {
        val ALL_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )

        /** A held fix older than this is superseded by anything newer, however sloppy. */
        const val STALE_MS = 30_000L
    }
}


// =====================================================================================
//  HomeAreaContext  -  the last known home/away answer, readable from anywhere.
// =====================================================================================
/**
 * [HomeAreaWatch] publishes here; everything else reads it instead of touching
 * LocationManager. Values are the LAST KNOWN answer, not a live one - exactly like
 * [SensorContext], and with the same rule: UNKNOWN means "we don't know", and a caller
 * must never turn that into a block.
 */
object HomeAreaContext {

    @Volatile var verdict: HomeArea.Verdict = HomeArea.Verdict.UNKNOWN; private set
    @Volatile var distanceM: Float = -1f; private set
    @Volatile var accuracyM: Float = -1f; private set
    /** When the underlying fix was taken, and when we last evaluated it. */
    @Volatile var fixTime: Long = 0L; private set
    @Volatile var updatedAt: Long = 0L; private set
    /** When the verdict last actually CHANGED, and what it changed from. */
    @Volatile var changedAt: Long = 0L; private set
    @Volatile var mock: Boolean = false; private set

    val known: Boolean get() = verdict != HomeArea.Verdict.UNKNOWN

    /** "home" / "away" / "maybe" / "unknown" - the shape block records use. */
    fun label(): String = when (verdict) {
        HomeArea.Verdict.HOME -> "home"
        HomeArea.Verdict.AWAY -> "away"
        HomeArea.Verdict.MAYBE -> "maybe"
        HomeArea.Verdict.UNKNOWN -> "unknown"
    }

    /** The last few transitions, newest first - this is how you check the watch kept
     *  working while the app was closed. In memory only (the service holds it). */
    class Change(val verdict: HomeArea.Verdict, val at: Long, val distanceM: Float)
    private val log = ArrayList<Change>()
    const val LOG_MAX = 12

    @Synchronized fun recent(): List<Change> = log.toList()

    @Synchronized internal fun publish(v: HomeArea.Verdict, loc: Location?, d: Float?, mockFix: Boolean) {
        val now = System.currentTimeMillis()
        updatedAt = now
        distanceM = d ?: -1f
        accuracyM = if (loc != null && loc.hasAccuracy()) loc.accuracy else -1f
        fixTime = loc?.time ?: 0L
        mock = mockFix
        if (v != verdict) {
            verdict = v
            changedAt = now
            log.add(0, Change(v, now, distanceM))
            while (log.size > LOG_MAX) log.removeAt(log.size - 1)
        }
    }

    @Synchronized internal fun clear() {
        verdict = HomeArea.Verdict.UNKNOWN
        distanceM = -1f; accuracyM = -1f; fixTime = 0L; updatedAt = 0L; mock = false
    }
}


// =====================================================================================
//  HomeAreaWatch  -  keeps the answer current WITH THE APP CLOSED.
// =====================================================================================
/**
 * Owned by the accessibility service (started in onServiceConnected, same as the sensor
 * watch and RoomGuard). That service is always running, which is what makes this work
 * with no activity on screen - and it is also why the "Allow all the time" location
 * grant is needed: without it Android hands the app nothing once it stops being visible.
 *
 * BATTERY. Continuous GPS would be indefensible for a question this coarse, so:
 *   - the standing subscription is network + passive only, at COARSE_INTERVAL_MS and
 *     COARSE_DISTANCE_M (Wi-Fi/cell fixes are typically 20-50 m, and passive fixes are
 *     free - they are other apps' GPS work, handed to us);
 *   - GPS is engaged only when the cheap fix can't answer (MAYBE / UNKNOWN / stale), for
 *     BURST_MS at a time, at most once per BURST_COOLDOWN_MS.
 *
 * The verdict must also HOLD for [HOLD_MS] before it is published - one stray fix at the
 * end of the drive should not flip the phone's whole rule set.
 */
object HomeAreaWatch {

    private const val EVAL_MS = 20_000L          // how often we re-read the held fix
    private const val GATE_MS = 60_000L          // how often we re-check "should this run"
    private const val COARSE_INTERVAL_MS = 60_000L
    private const val COARSE_DISTANCE_M = 20f
    private const val BURST_MS = 45_000L         // GPS engaged for this long
    private const val BURST_COOLDOWN_MS = 5 * 60_000L
    /** A new verdict must survive this long before it's believed. */
    private const val HOLD_MS = 60_000L

    /** True while the watch is actually subscribed to location (for debug UIs). */
    @Volatile var armed: Boolean = false; private set
    /** True while GPS is engaged for a burst (for debug UIs). */
    @Volatile var bursting: Boolean = false; private set

    private var monitor: LocationMonitor? = null
    private var handler: Handler? = null
    private var gateOpen = false
    private var lastGateCheck = 0L
    private var burstStarted = 0L
    private var lastBurstEnded = 0L
    private var candidate: HomeArea.Verdict? = null
    private var candidateSince = 0L

    /** Idempotent. [onChange] fires on the main thread when the published verdict changes. */
    fun start(context: Context, onChange: (() -> Unit)? = null) {
        if (handler != null) return
        val app = context.applicationContext
        val h = Handler(Looper.getMainLooper())
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
        monitor?.stop(); monitor = null
        armed = false; bursting = false; gateOpen = false; lastGateCheck = 0L
        candidate = null; candidateSince = 0L
        HomeAreaContext.clear()
    }

    private fun tick(app: Context, onChange: (() -> Unit)?) {
        val now = System.currentTimeMillis()

        // Gate: a home point, foreground permission, and location switched on. Background
        // permission is NOT part of the gate - without it this still works whenever the
        // app is visible, which is better than nothing while the user is being nagged for
        // the "all the time" grant.
        if (now - lastGateCheck >= GATE_MS || !gateOpen) {
            lastGateCheck = now
            gateOpen = HomeArea.isSet(app) && HomeArea.hasPermissions(app) && HomeArea.locationEnabled(app)
        }
        if (!gateOpen) {
            monitor?.stop(); monitor = null
            if (armed) HomeAreaContext.clear()
            armed = false; bursting = false
            return
        }

        val m = monitor ?: LocationMonitor(app).also {
            monitor = it
            it.start(COARSE_INTERVAL_MS, COARSE_DISTANCE_M, gps = false)
        }
        armed = true

        val loc = m.last
        val raw = HomeArea.verdict(app, loc)

        // Precision on demand: the cheap providers can't settle a 50 m question when the
        // fix is vague or missing, so buy a GPS burst - rarely, and briefly.
        val needsGps = raw == HomeArea.Verdict.MAYBE || raw == HomeArea.Verdict.UNKNOWN
        if (bursting) {
            if (!needsGps || now - burstStarted >= BURST_MS) {
                m.setGps(false); bursting = false; lastBurstEnded = now
            }
        } else if (needsGps && now - lastBurstEnded >= BURST_COOLDOWN_MS && m.hasGps) {
            m.setGps(true); bursting = true; burstStarted = now
        }

        // Debounce: a change has to hold before anyone hears about it.
        val settled = HomeAreaContext.verdict
        if (raw != settled) {
            if (candidate != raw) { candidate = raw; candidateSince = now }
            if (now - candidateSince < HOLD_MS) {
                // Not yet - refresh the readout's numbers, leave the verdict alone.
                HomeAreaContext.publish(settled, loc, HomeArea.distanceFrom(app, loc), HomeArea.isMock(loc))
                return
            }
        } else {
            candidate = null
        }

        val before = HomeAreaContext.verdict
        HomeAreaContext.publish(raw, loc, HomeArea.distanceFrom(app, loc), HomeArea.isMock(loc))
        if (raw != before) onChange?.invoke()
    }
}
