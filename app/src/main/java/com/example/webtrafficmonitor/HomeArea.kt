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
import org.json.JSONArray
import org.json.JSONObject

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
//  HomeAreaWatch). What it is USED for lives in [HomeRule]: in Super hardcore, being at
//  the house shortens the word-detection ladder to one. Set-up is still one manual step -
//  stand in the house, press the button, and the current fix becomes home - but it is no
//  longer developer-only: the house row on the dashboard opens it, and Strict and above
//  ask for it (see HomeRule.shouldAsk).
//
//  THERE CAN BE MORE THAN ONE HOUSE, and none of them can be edited: you add where you
//  are, and in Strict and above removing one takes a month's wait nobody is told the end
//  of. See [HouseLock] for why, and [HomeArea.houses] for the list.
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

    // ── Where home is ──────────────────────────────────────────────────────────
    //
    //  A LIST of points, oldest first, captured by hand while standing in each one. It was
    //  a single point until [HouseLock] made moving it expensive: once you cannot simply
    //  edit the house, the honest answer for someone with two real homes - or one home and
    //  a parents' house every weekend - has to be a SECOND HOUSE rather than a rewrite of
    //  the first. Adding one is free and instant, and safely so: at home only ever makes
    //  the rules STRICTER (see [HomeRule]), so another house can only ever cost the user
    //  more, never less. A saved house cannot be moved at all - removing one is the only
    //  way back out, and that is the direction [HouseLock] slows down.
    //
    //  Every question this file answers - distance, verdict - is asked of the NEAREST
    //  house, so nothing downstream has to know there is more than one.
    //
    //  Each point keeps the accuracy and provider of the fix it came from, because a house
    //  captured off a 500 m network fix is worth knowing about before trusting anything
    //  built on top of it.

    /** How many houses one person may have. Four is already a generous reading of "home". */
    const val MAX_HOUSES = 4

    /**
     * One saved house.
     *
     * [deleteRequestedAt] is non-zero once removal has been ASKED FOR and the month has
     * started running (see [HouseLock.DELETE_DELAY_MS]). A house waiting to go still counts
     * for everything in the meantime: it is home right up until the day it isn't.
     */
    data class House(
        val id: String,
        val lat: Double,
        val lon: Double,
        val accuracy: Float,
        val provider: String,
        val setAt: Long,
        val deleteRequestedAt: Long = 0L,
    )

    private const val KEY_HOUSES = "houses"

    /**
     * The saved houses, oldest first - and the one place a matured deletion actually
     * happens (see [HouseLock.deleteIsDue]). Reaping on read rather than on a timer is
     * deliberate: there is no alarm to miss, no notification to fire, and the removal
     * simply IS the case the next time anything asks. Which is the point - the user is
     * never told when the month is up.
     */
    fun houses(context: Context): List<House> {
        val p = prefs(context)
        val raw = p.getString(KEY_HOUSES, null)
        val stored = when {
            raw != null -> parseHouses(raw)
            // Installs from before the list existed: one point, in the old flat keys.
            p.contains("lat") -> listOf(
                House(
                    id = "h0",
                    lat = p.getFloat("lat", 0f).toDouble(),
                    lon = p.getFloat("lon", 0f).toDouble(),
                    accuracy = p.getFloat("acc", -1f),
                    provider = p.getString("provider", "") ?: "",
                    setAt = p.getLong("set_at", 0L),
                )
            ).also { writeHouses(context, it) }
            else -> emptyList()
        }
        val due = stored.filter { HouseLock.deleteIsDue(it) }
        if (due.isEmpty()) return stored
        val kept = stored.filter { h -> due.none { it.id == h.id } }
        writeHouses(context, kept)
        return kept
    }

    private fun parseHouses(raw: String): List<House> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            House(
                id = o.optString("id", "h$i"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                accuracy = o.optDouble("acc", -1.0).toFloat(),
                provider = o.optString("provider", ""),
                setAt = o.optLong("set_at", 0L),
                deleteRequestedAt = o.optLong("delete_at", 0L),
            )
        }
    } catch (t: Throwable) { emptyList() }

    private fun writeHouses(context: Context, list: List<House>) {
        // Doubles here, unlike the old flat keys - JSON has no float/double distinction to
        // lose, so the ~1 m rounding that came of squeezing a latitude into a Float goes.
        val arr = JSONArray()
        for (h in list) arr.put(
            JSONObject()
                .put("id", h.id)
                .put("lat", h.lat)
                .put("lon", h.lon)
                .put("acc", h.accuracy.toDouble())
                .put("provider", h.provider)
                .put("set_at", h.setAt)
                .put("delete_at", h.deleteRequestedAt)
        )
        prefs(context).edit()
            .putString(KEY_HOUSES, arr.toString())
            // The pre-list keys, gone for good the moment the list exists.
            .remove("lat").remove("lon").remove("acc").remove("provider").remove("set_at")
            .apply()
    }

    private fun newId(existing: List<House>): String {
        var n = existing.size
        while (existing.any { it.id == "h$n" }) n++
        return "h$n"
    }

    fun isSet(context: Context): Boolean = houses(context).isNotEmpty()

    /** How many houses are saved. */
    fun count(context: Context): Int = houses(context).size

    /**
     * Saves [loc] as a house, returning it - or null when the list is already full. Always
     * allowed, in every mode: see the note at the top of this section. This is the ONLY way
     * a house is written; there is no editing one, by design.
     */
    fun addHouse(context: Context, loc: Location): House? {
        val list = houses(context)
        if (list.size >= MAX_HOUSES) return null
        val h = House(
            id = newId(list),
            lat = loc.latitude,
            lon = loc.longitude,
            accuracy = if (loc.hasAccuracy()) loc.accuracy else -1f,
            provider = loc.provider ?: "",
            setAt = System.currentTimeMillis(),
        )
        writeHouses(context, list + h)
        return h
    }

    /** Removes a house NOW. Only for the modes with no lock - see [HouseLock]. */
    fun removeHouse(context: Context, id: String) =
        writeHouses(context, houses(context).filterNot { it.id == id })

    /**
     * Asks for a house to be removed, starting the wait. Idempotent on purpose: asking
     * again does not restart the month, so hammering the button changes nothing.
     */
    fun requestDelete(context: Context, id: String) {
        val list = houses(context)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0 || list[i].deleteRequestedAt > 0L) return
        val updated = list.toMutableList()
        updated[i] = list[i].copy(deleteRequestedAt = System.currentTimeMillis())
        writeHouses(context, updated)
    }

    /** Calls the removal off. Always allowed - keeping a house is never the risky direction. */
    fun cancelDelete(context: Context, id: String) {
        val list = houses(context)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0 || list[i].deleteRequestedAt == 0L) return
        val updated = list.toMutableList()
        updated[i] = list[i].copy(deleteRequestedAt = 0L)
        writeHouses(context, updated)
    }

    // ── The rule ─────────────────────────────────────────────────────────────────────

    /** Metres from [house] to [loc]. */
    fun distanceTo(house: House, loc: Location): Float {
        val out = FloatArray(1)
        Location.distanceBetween(house.lat, house.lon, loc.latitude, loc.longitude, out)
        return out[0]
    }

    /**
     * The closest saved house to [loc] and how far away it is, or null with no fix / no
     * houses. NEAREST WINS, everywhere: with more than one house the phone is at home if it
     * is at any of them, and every readout below describes the one it is nearest to.
     */
    fun nearest(context: Context, loc: Location?): Pair<House, Float>? {
        if (loc == null) return null
        var bestHouse: House? = null
        var bestDist = Float.MAX_VALUE
        for (h in houses(context)) {
            val d = distanceTo(h, loc)
            if (d < bestDist) { bestDist = d; bestHouse = h }
        }
        return bestHouse?.let { it to bestDist }
    }

    /** Metres to the nearest house from [loc], or null if no house is set. */
    fun distanceFrom(context: Context, loc: Location?): Float? = nearest(context, loc)?.second

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
//  HouseLock  -  in Strict and above, the house is not a setting you can edit.
// =====================================================================================
/**
 * WHY A LOCK AT ALL. Every rule in this app that keys off "at home" is only as good as the
 * point it is measured from, and that point used to be one button press away from being
 * somewhere else. Someone who wants an evening off does not have to argue with the mode
 * picker, the uninstall lock or the ratchet - they could stand in the garden, press save,
 * and be "out" for the rest of the night in a house they never left. A rule with a one-tap
 * escape hatch is not a rule, it is a suggestion.
 *
 * So a saved house is never edited. The two things that CAN be done split like this:
 *
 *   ADDING a house    FREE, INSTANTLY, always, in every mode. This is the pressure valve,
 *                     and it is what lets the rest be strict: everyone whose life genuinely
 *                     has two homes in it gets a real answer instead of a refusal. It is
 *                     safe because being at home only ever TIGHTENS the rules (see
 *                     [HomeRule]) - another house can cost the user more, never less.
 *
 *   REMOVING a house  In Strict and above: A MONTH, AND NOBODY IS TOLD WHEN. This is the
 *                     only direction that loosens anything, so it is the only one that
 *                     waits. The wait is ASKED FOR and then unmarked: the app never counts
 *                     down, never notifies, and the house simply stops being there some
 *                     time after the month is up, the next time anything looks (see
 *                     [HomeArea.houses]). A visible countdown is a date to hold out for,
 *                     and holding out for a date is exactly the behaviour this is trying
 *                     not to reward.
 *
 * In Off and Relaxed a house is removed on the spot - those modes promise nothing, and
 * making them wait would only teach people not to set the house up in the first place.
 */
object HouseLock {

    /** How long a removal sits waiting before it quietly happens. */
    const val DELETE_DELAY_MS = 30L * 24 * 60 * 60 * 1000

    /** True in the modes that hold the user to the house they saved. */
    fun applies(ctx: Context): Boolean = Mode.isStrict(ctx) || Mode.isSuperHardcore(ctx)

    /** Is there room for another house? */
    fun canAdd(ctx: Context): Boolean = HomeArea.count(ctx) < HomeArea.MAX_HOUSES

    /** True when removing a house has to be asked for and waited out rather than just done. */
    fun deleteNeedsWait(ctx: Context): Boolean = applies(ctx)

    /**
     * Has this house's month run out? Deliberately not exposed as a countdown anywhere -
     * [HomeArea.houses] is the only caller, and it acts on it silently.
     */
    fun deleteIsDue(house: HomeArea.House): Boolean =
        house.deleteRequestedAt > 0L &&
            System.currentTimeMillis() - house.deleteRequestedAt >= DELETE_DELAY_MS
}


// =====================================================================================
//  HomeRule  -  what the home/away answer is actually FOR.
// =====================================================================================
/**
 * THE HOUSE IS WHERE IT HAPPENS. Nobody's problem is the bus into work; it is the sofa,
 * the bedroom, the two hours after everyone else has gone to bed - the place where there
 * is no one to walk in, nothing to be late for, and every excuse already used up. A rule
 * that knows where the phone is can be strict in the one place strictness is worth having
 * and stay out of the way everywhere else, which is a far better trade than being equally
 * strict everywhere and equally resented.
 *
 * So, in SUPER HARDCORE ONLY, at the house: [RepeatGate]'s ladder collapses to one. The
 * first word detected in an app closes it, with no second look and no waiting to see
 * whether it comes back. That is the bargain super hardcore already makes everywhere else
 * (every open interrupted, the night guard, no daily pass) applied to the word filter -
 * and it is the mode you have to deliberately choose, with the uninstall lock already on.
 *
 * IT NEVER GUESSES. Only a settled HOME verdict counts: MAYBE and UNKNOWN leave the
 * ordinary ladder exactly as it is, the same rule SensorContext and RoomGuard follow.
 * Being wrong in the strict direction here means someone loses an app in a supermarket
 * because the fix was vague, and one of those costs more trust than the rule earns.
 *
 * Strict is deliberately NOT included. Strict is the mode you can live in indefinitely,
 * and one word taking an app away is not something to live with indefinitely - it is what
 * you choose when you have decided the ordinary rules are not holding. Strict is asked to
 * SET THE HOUSE UP (see [shouldAsk]) so that the rule is there the day it is wanted.
 */
object HomeRule {

    private const val PREFS = "home_rule"
    private const val KEY_ASKED_FOR = "asked_for_mode"

    /** True only when the settled verdict actually says the phone is at the house. */
    fun atHome(): Boolean = HomeAreaContext.verdict == HomeArea.Verdict.HOME

    /** Super hardcore, at the house, home point set: one detection is the whole ladder. */
    fun oneDetectionIsEnough(ctx: Context): Boolean =
        Mode.isSuperHardcore(ctx) && HomeArea.isSet(ctx) && atHome()

    /**
     * Should we ask the user to set the house up, now that they have chosen [mode]?
     *
     * Once per mode, and never twice for the same one: an offer that reappears every time
     * the picker is touched is nagging, and nagging is how a good idea gets refused on
     * reflex. Strict asks because the rule should be in place before it is needed; Super
     * hardcore asks again because there it is not a preference, it is the mode's own rule
     * sitting switched off.
     */
    fun shouldAsk(ctx: Context, mode: String): Boolean {
        if (HomeArea.isSet(ctx)) return false
        if (mode != Mode.STRICT && mode != Mode.SUPERHARDCORE) return false
        return prefs(ctx).getString(KEY_ASKED_FOR, null) != mode
    }

    fun markAsked(ctx: Context, mode: String) =
        prefs(ctx).edit().putString(KEY_ASKED_FOR, mode).apply()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
        for (p in listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, intervalMs, minDistanceM, this)
            } catch (_: SecurityException) { } catch (_: IllegalArgumentException) { }
        }
        // ⚠️ GPS IS NOT SUBSCRIBED AT THE CALLER'S CADENCE, and that is not an oversight.
        //
        // GPS is only ever switched on to ANSWER something (see HomeAreaWatch's bursts): the
        // cheap providers couldn't tell, so we are paying for the radio for a few seconds to
        // settle it. Handing that subscription the standing minute-long interval - or worse,
        // a minimum distance - means a phone sitting still on a sofa gets NO callbacks at
        // all, so the burst costs its battery and answers nothing, and the verdict stays
        // UNKNOWN for as long as you don't move. Which is the sofa. Which is the entire
        // point of the feature. A burst runs flat out for as long as it is on.
        if (gps) {
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_BURST_INTERVAL_MS, 0f, this)
                }
            } catch (_: SecurityException) { } catch (_: IllegalArgumentException) { }
        }
    }

    /**
     * Hand the monitor a fix somebody else already paid for. Same acceptance test as a fix
     * that arrived through our own subscription - it is kept only if it beats what we hold.
     *
     * This is how the foreground house page (which runs GPS while the user is looking at
     * it) tops up the background watch, instead of the two of them holding different
     * answers and the dashboard disagreeing with the page the dashboard links to.
     */
    fun offer(candidate: Location) {
        val before = last
        accept(candidate)
        if (last !== before) onUpdate?.invoke()
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

        /** What a GPS burst asks for while it is engaged. See [register]. */
        const val GPS_BURST_INTERVAL_MS = 1_000L
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
    /**
     * ⚠️ ZERO, AND IT MUST STAY ZERO. This used to be 20 m, on the reasoning that a phone
     * that hasn't moved has nothing new to say. It does: LocationManager's minimum-distance
     * filter suppresses the CALLBACK, not just the news, so a phone lying still delivered
     * nothing at all, the held fix aged past MAX_FIX_AGE_MS, and the verdict fell to UNKNOWN
     * and stayed there. Sitting still at home is the exact state this feature exists to
     * recognise, and it was the one state it could not see. Battery is bought with the
     * interval instead - a minute-cadence network fix is a Wi-Fi lookup, not a radio.
     */
    private const val COARSE_DISTANCE_M = 0f
    private const val BURST_MS = 45_000L         // GPS engaged for this long
    private const val BURST_COOLDOWN_MS = 5 * 60_000L
    /** A new verdict must survive this long before it's believed. */
    private const val HOLD_MS = 60_000L
    /**
     * ...except when we currently know NOTHING. UNKNOWN is not a verdict anything acts on
     * (nothing ever gets stricter because we can't tell), so there is no answer to protect
     * from flapping and no reason to make the user watch a dashboard say "can't tell right
     * now" for a minute after the fix has plainly settled.
     */
    private const val FIRST_HOLD_MS = 10_000L

    /** True while the watch is actually subscribed to location (for debug UIs). */
    @Volatile var armed: Boolean = false; private set
    /** True while GPS is engaged for a burst (for debug UIs). */
    @Volatile var bursting: Boolean = false; private set

    private var monitor: LocationMonitor? = null
    private var handler: Handler? = null
    private var onChange: (() -> Unit)? = null
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
        this.onChange = onChange
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
        onChange = null
        armed = false; bursting = false; gateOpen = false; lastGateCheck = 0L
        candidate = null; candidateSince = 0L
        HomeAreaContext.clear()
    }

    /**
     * Take a fix the FOREGROUND app has already paid for and re-evaluate straight away.
     *
     * The house page runs its own monitor with GPS engaged the whole time it is open, so
     * while the user is looking at it there is a far better fix in the building than
     * anything this watch's cheap subscription will see. Without this, the page could read
     * "AT HOME" off a 6 m GPS fix while the dashboard behind it still said "can't tell right
     * now" off a stale network one - two screens, one phone, two answers, and no way for the
     * user to work out which of them to believe. Costs nothing: the fix already exists.
     */
    fun offer(context: Context, loc: Location?) {
        if (loc == null || handler == null) return
        // Straight into a tick rather than at the monitor: the gate is re-checked in there,
        // and "the home point has only just been saved" is precisely the case where the
        // gate is still shut and the offered fix would otherwise have nowhere to land.
        tick(context.applicationContext, onChange, extraFix = loc)
    }

    private fun tick(app: Context, onChange: (() -> Unit)?, extraFix: Location? = null) {
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
        if (extraFix != null) m.offer(extraFix)

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

        // Debounce: a change has to hold before anyone hears about it - except when there
        // is no answer yet to protect (see FIRST_HOLD_MS).
        val settled = HomeAreaContext.verdict
        if (raw != settled) {
            if (candidate != raw) { candidate = raw; candidateSince = now }
            val hold = if (settled == HomeArea.Verdict.UNKNOWN) FIRST_HOLD_MS else HOLD_MS
            if (now - candidateSince < hold) {
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
