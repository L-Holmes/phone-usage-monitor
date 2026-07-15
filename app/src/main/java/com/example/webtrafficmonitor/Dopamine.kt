package com.example.webtrafficmonitor

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// #####################################################################################
// #                                                                                   #
// #   THE DOPAMINE BASELINE ALGORITHM                                                 #
// #                                                                                   #
// #   This whole file is the algorithm. If you want to change how the score behaves,  #
// #   change DopamineTuning below - it is all plain numbers with plain explanations,   #
// #   and nothing else in the app hard-codes any of it.                               #
// #                                                                                   #
// #   WHAT THE SCORE MEANS                                                            #
// #   0 = a calm, unfried baseline. 100 = maximally overstimulated.                   #
// #   HIGHER IS WORSE. It is a measure of how hard you are hammering your reward       #
// #   system, not a measure of your worth, and the UI must never imply otherwise.      #
// #                                                                                   #
// #   HOW IT IS BUILT                                                                 #
// #   Each day we collect raw counters (see DopamineDay). Each counter is turned into  #
// #   points by one rule below. The points are added up and capped at 100.             #
// #                                                                                   #
// #     1. TIME SPENT, per content category - by far the biggest lever, so it carries  #
// #        the most points. Categories are ranked worst-first (adult content, then     #
// #        fast-paced short video, then fast social, ... down to long-form video).     #
// #     2. PHONE UNLOCKS per hour.                                                     #
// #     3. TIME OF DAY - late night is worst, then first thing on waking.              #
// #     4. URGENT OPENS - unlocked the phone and was in TikTok within seconds. This is #
// #        the purest compulsion signal we have, so it is weighted hard for how rare   #
// #        it is.                                                                      #
// #     5. APP CHECKING - opening the same app over and over in one hour.              #
// #     6. INTERACTION RATE - constant scrolling/tapping vs. reading something.        #
// #     7. POSTURE + LIGHT - using it lying down, or in the dark (we already have the  #
// #        sensors for this).                                                          #
// #                                                                                   #
// #   AND THE ANTI-RULES, which SUBTRACT points. They are deliberately weak and slow:  #
// #   you cannot undo a four-hour binge with a walk, and pretending otherwise would    #
// #   be a lie that makes the number useless.                                          #
// #                                                                                   #
// #   HONEST LIMITS - things asked for that we cannot currently measure:               #
// #     - "time since you woke up" / alarm detection. Android gives an accessibility   #
// #       service no reliable signal that an alarm fired or that you woke up. We use    #
// #       the first unlock after a long screen-off gap as a PROXY for "just woke up"   #
// #       (see WAKE_GAP_HOURS), which is decent but not the same thing.                #
// #     - "screen off AND no media playing". We can see screen-off; we cannot see      #
// #       whether audio is playing without the notification-listener permission, which  #
// #       this app does not take. So screen-off time counts, even if a podcast is on.   #
// #                                                                                   #
// #####################################################################################

object DopamineTuning {

    // ── 1. TIME SPENT ────────────────────────────────────────────────────────────────
    // Points earned for a FULL dose of each category (see doseMultiplier for what "full"
    // means). Worst-first. These are the headline dials: raise one and that category
    // starts dominating someone's score.
    val CATEGORY_POINTS: Map<DopamineCategory, Float> = mapOf(
        DopamineCategory.ADULT to 40f,           // what this app exists to block
        DopamineCategory.FAST_VIDEO to 34f,      // TikTok, Reels, Shorts - the worst legal one
        DopamineCategory.FAST_SOCIAL to 24f,     // Snapchat, the endless social loop
        DopamineCategory.GAMBLING to 32f,        // real money, real variable-ratio reward
        DopamineCategory.IMPULSE to 20f,         // Amazon, Temu, food delivery
        DopamineCategory.MOBILE_GAMING to 18f,   // built on streaks + unlocks
        DopamineCategory.LIVE to 16f,            // Twitch and friends
        DopamineCategory.FORUMS_NEWS to 14f,     // Reddit, forums, the news cycle
        DopamineCategory.LONG_VIDEO to 10f,      // YouTube proper - passive, but slow-paced
    )

    // The dose curve. At DOSE_MAX_HOURS or beyond you get the full points for a category;
    // below that it scales up as a slightly convex curve, so the 4th hour hurts more than
    // the 1st. (Exponent > 1 = convex = punishes the higher amounts harder, as asked.)
    const val DOSE_MAX_HOURS = 4f
    const val DOSE_CURVE = 1.3f

    fun doseMultiplier(hours: Float): Float {
        val h = hours.coerceIn(0f, DOSE_MAX_HOURS)
        return Math.pow((h / DOSE_MAX_HOURS).toDouble(), DOSE_CURVE.toDouble()).toFloat()
    }

    // ── 2. UNLOCKS ───────────────────────────────────────────────────────────────────
    // Unlocks per waking hour. ~4/hr is ordinary; 12+/hr is a nervous tic.
    const val UNLOCKS_PER_HOUR_OK = 4f
    const val UNLOCKS_PER_HOUR_BAD = 12f
    const val UNLOCKS_MAX_POINTS = 10f

    // ── 3. TIME OF DAY ───────────────────────────────────────────────────────────────
    // Multiplier applied to time-spent points depending on WHEN it happened. Late night is
    // the worst (it costs you sleep as well as the hit); just-after-waking is next.
    const val LATE_NIGHT_FROM = 23     // 23:00
    const val LATE_NIGHT_TO = 5        // 05:00
    const val LATE_NIGHT_MULTIPLIER = 1.6f
    const val JUST_WOKE_MULTIPLIER = 1.4f
    // A screen-off gap this long means the next unlock is probably you waking up. A proxy -
    // see the honest-limits note at the top of the file.
    const val WAKE_GAP_HOURS = 5f
    const val JUST_WOKE_WINDOW_MIN = 30

    // ── 4. URGENT OPENS ──────────────────────────────────────────────────────────────
    // Unlock -> straight into a worst-tier app with no detour. Pure autopilot.
    const val URGENT_OPEN_SECONDS = 3      // within this many seconds of unlocking
    const val URGENT_OPEN_POINTS = 3f      // per occurrence
    const val URGENT_OPEN_MAX_POINTS = 12f

    // ── 5. APP CHECKING ──────────────────────────────────────────────────────────────
    // Opening the SAME app many times in one hour (the "50 Snapchat opens" case).
    const val CHECKS_PER_HOUR_OK = 6
    const val CHECKS_PER_HOUR_BAD = 30
    const val CHECKS_MAX_POINTS = 8f

    // ── 6. INTERACTION RATE ──────────────────────────────────────────────────────────
    // Scrolls + taps per minute of screen time. Reading a long article is a low rate;
    // thumbing a feed is a high one.
    const val INTERACTIONS_PER_MIN_OK = 8f
    const val INTERACTIONS_PER_MIN_BAD = 40f
    const val INTERACTIONS_MAX_POINTS = 8f

    // ── 7. POSTURE + LIGHT ───────────────────────────────────────────────────────────
    const val LYING_MAX_POINTS = 6f
    const val DARK_MAX_POINTS = 6f
    const val POSTURE_FULL_HOURS = 2f      // this many hours lying/dark = full points

    // ── ANTI-RULES (subtract) ────────────────────────────────────────────────────────
    // Weak and slow ON PURPOSE. A calm evening does not cancel a binge, and a score that
    // pretended it did would be worthless.
    const val SCREEN_OFF_FULL_HOURS = 6f   // this much waking screen-off time = full credit
    const val SCREEN_OFF_MAX_POINTS = 8f   // ...which is worth this little

    // ── BANDS ────────────────────────────────────────────────────────────────────────
    // What the number is called when we show it. Blunt, not cruel.
    fun band(score: Int): String = when {
        score >= 80 -> "Severely overstimulated"
        score >= 60 -> "Very poor"
        score >= 45 -> "Poor"
        score >= 30 -> "Middling"
        score >= 15 -> "Good"
        else -> "Calm"
    }

    fun bandColour(score: Int): Int = when {
        score >= 80 -> 0xFFB3261E.toInt()
        score >= 60 -> 0xFFD1462F.toInt()
        score >= 45 -> 0xFFE08A26.toInt()
        score >= 30 -> 0xFFCBA92B.toInt()
        score >= 15 -> 0xFF5C9E31.toInt()
        else -> 0xFF2E7D32.toInt()
    }

    const val WAKING_HOURS = 16f
}


// =====================================================================================
// Categories
// =====================================================================================
/**
 * Worst-first. The ORDER here is the ranking; the POINTS are in DopamineTuning.
 * OTHER means "we don't consider this a dopamine load" and scores nothing.
 */
enum class DopamineCategory(val label: String) {
    ADULT("Adult content"),
    FAST_VIDEO("Short-form video"),
    GAMBLING("Gambling"),
    FAST_SOCIAL("Fast social"),
    IMPULSE("Impulse shopping"),
    MOBILE_GAMING("Mobile gaming"),
    LIVE("Live streams"),
    FORUMS_NEWS("Forums & news"),
    LONG_VIDEO("Long-form video"),
    OTHER("Everything else"),
}

/**
 * Which category is this app/page?
 *
 * One function per category ON PURPOSE (as requested): each is an independent list you can
 * grow over time without touching the others. Each takes both the package and the current
 * web host, so a category can be spotted whether it's the native app or the website.
 *
 * A page rule beats an app rule: youtube.com/shorts inside the YouTube app is FAST_VIDEO,
 * not LONG_VIDEO. That is why the host checks run first in [categorise].
 */
object DopamineClassifier {

    fun isAdultContent(pkg: String?, host: String?, blocked: Boolean = false): Boolean {
        if (blocked) return true                      // our own blocker fired: definitionally adult
        return host != null && DomainBlocklist.isBlocked(host)
    }

    fun isFastPacedVideo(pkg: String?, host: String?, url: String? = null): Boolean {
        val u = url?.lowercase().orEmpty()
        if (u.contains("/shorts") || u.contains("/reels") || u.contains("/reel/")) return true
        if (matchesHost(host, "tiktok.com")) return true
        return matchesPkg(pkg, FAST_VIDEO_APPS)
    }

    fun isFastSocial(pkg: String?, host: String?): Boolean =
        matchesPkg(pkg, FAST_SOCIAL_APPS) ||
            matchesAnyHost(host, listOf("snapchat.com", "instagram.com", "facebook.com", "x.com", "twitter.com"))

    fun isLiveStream(pkg: String?, host: String?): Boolean =
        matchesPkg(pkg, LIVE_APPS) || matchesAnyHost(host, listOf("twitch.tv", "kick.com"))

    fun isForumOrNews(pkg: String?, host: String?): Boolean =
        matchesPkg(pkg, FORUM_NEWS_APPS) ||
            matchesAnyHost(host, listOf(
                "reddit.com", "news.google.com", "cnn.com", "bbc.co.uk", "foxnews.com",
                "theguardian.com", "dailymail.co.uk", "news.ycombinator.com", "quora.com",
            ))

    fun isLongFormVideo(pkg: String?, host: String?): Boolean =
        matchesPkg(pkg, LONG_VIDEO_APPS) ||
            matchesAnyHost(host, listOf("youtube.com", "netflix.com", "primevideo.com", "hulu.com", "disneyplus.com"))

    fun isImpulseShopping(pkg: String?, host: String?): Boolean =
        matchesPkg(pkg, IMPULSE_APPS) ||
            matchesAnyHost(host, listOf(
                "amazon.com", "amazon.co.uk", "temu.com", "shein.com", "aliexpress.com",
                "ebay.com", "wish.com", "justeat.co.uk", "deliveroo.co.uk", "ubereats.com",
                "doordash.com",
            ))

    fun isGambling(pkg: String?, host: String?): Boolean =
        matchesPkg(pkg, GAMBLING_APPS) ||
            matchesAnyHost(host, listOf(
                "bet365.com", "skybet.com", "paddypower.com", "williamhill.com",
                "ladbrokes.com", "betfair.com", "draftkings.com", "stake.com",
            ))

    fun isMobileGaming(pkg: String?, host: String?): Boolean =
        matchesPkg(pkg, GAMING_APPS) ||
            matchesAnyHost(host, listOf("poki.com", "crazygames.com", "miniclip.com", "coolmathgames.com"))

    /** The single category for this moment. Worst wins; page beats app. */
    fun categorise(pkg: String?, host: String?, url: String? = null, blocked: Boolean = false): DopamineCategory = when {
        isAdultContent(pkg, host, blocked) -> DopamineCategory.ADULT
        isFastPacedVideo(pkg, host, url) -> DopamineCategory.FAST_VIDEO
        isGambling(pkg, host) -> DopamineCategory.GAMBLING
        isFastSocial(pkg, host) -> DopamineCategory.FAST_SOCIAL
        isImpulseShopping(pkg, host) -> DopamineCategory.IMPULSE
        isMobileGaming(pkg, host) -> DopamineCategory.MOBILE_GAMING
        isLiveStream(pkg, host) -> DopamineCategory.LIVE
        isForumOrNews(pkg, host) -> DopamineCategory.FORUMS_NEWS
        isLongFormVideo(pkg, host) -> DopamineCategory.LONG_VIDEO
        else -> DopamineCategory.OTHER
    }

    /** The apps that count as a "worst tier" autopilot open (see URGENT_OPEN_SECONDS). */
    fun isWorstTier(pkg: String?, host: String?): Boolean =
        isFastPacedVideo(pkg, host) || isFastSocial(pkg, host) ||
            isAdultContent(pkg, host) || isGambling(pkg, host)

    // ── the lists. Grow these freely; nothing else needs to change. ──
    private val FAST_VIDEO_APPS = setOf(
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go",
    )
    private val FAST_SOCIAL_APPS = setOf(
        "com.snapchat.android", "com.instagram.android", "com.instagram.lite",
        "com.facebook.katana", "com.facebook.lite", "com.twitter.android",
        "com.twitter.android.lite", "com.pinterest", "com.tumblr",
    )
    private val LIVE_APPS = setOf("tv.twitch.android.app")
    private val FORUM_NEWS_APPS = setOf("com.reddit.frontpage", "com.google.android.apps.magazines")
    private val LONG_VIDEO_APPS = setOf(
        "com.google.android.youtube", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient",
    )
    private val IMPULSE_APPS = setOf(
        "com.amazon.mShop.android.shopping", "com.einnovation.temu", "com.zzkko",
        "com.ebay.mobile", "com.alibaba.aliexpresshd", "com.justeat.app.uk",
        "com.deliveroo.orderapp", "com.ubercab.eats", "com.dd.doordash",
    )
    private val GAMBLING_APPS = setOf(
        "com.bet365.mobilesports", "uk.co.skybet.mobilesports", "com.paddypower.sportsbook",
    )
    private val GAMING_APPS = setOf("com.king.candycrushsaga", "com.supercell.clashofclans")

    private fun matchesPkg(pkg: String?, set: Set<String>) =
        pkg != null && pkg.lowercase() in set

    private fun matchesHost(host: String?, domain: String): Boolean {
        val h = host?.lowercase()?.removePrefix("www.") ?: return false
        return h == domain || h.endsWith(".$domain")
    }

    private fun matchesAnyHost(host: String?, domains: List<String>) =
        domains.any { matchesHost(host, it) }
}


// =====================================================================================
// DopamineDay  (one day's raw counters - the INPUT to the score)
// =====================================================================================
data class DopamineDay(
    val date: String,
    /** Seconds spent in each category today. */
    val seconds: MutableMap<DopamineCategory, Long> = mutableMapOf(),
    /** Seconds spent in a scoring category during late night / just after waking. */
    var lateNightSeconds: Long = 0,
    var justWokeSeconds: Long = 0,
    var unlocks: Int = 0,
    var urgentOpens: Int = 0,
    /** Highest number of opens of any single app within one hour today. */
    var maxChecksInHour: Int = 0,
    var interactions: Long = 0,        // scrolls + taps
    var screenOnSeconds: Long = 0,
    var screenOffSeconds: Long = 0,
    var lyingSeconds: Long = 0,
    var darkSeconds: Long = 0,
) {
    fun loadSeconds(): Long =
        seconds.entries.filter { it.key != DopamineCategory.OTHER }.sumOf { it.value }
}


// =====================================================================================
// DopamineScore  (the raw counters -> a number out of 100, plus WHY)
// =====================================================================================
data class ScoreLine(val label: String, val points: Int, val detail: String)

data class DopamineResult(
    val score: Int,
    val band: String,
    val colour: Int,
    val contributors: List<ScoreLine>,   // things that PUSHED IT UP, biggest first
    val credits: List<ScoreLine>,        // the anti-rules, which pulled it down
    val hasData: Boolean,
)

object DopamineScore {

    fun of(day: DopamineDay): DopamineResult {
        val t = DopamineTuning
        val up = mutableListOf<ScoreLine>()
        val down = mutableListOf<ScoreLine>()
        var total = 0f

        // 1 + 3. Time spent, weighted by WHEN it happened.
        val loadSecs = day.loadSeconds()
        val timeOfDayMult = timeOfDayMultiplier(day)
        DopamineCategory.values().forEach { cat ->
            if (cat == DopamineCategory.OTHER) return@forEach
            val secs = day.seconds[cat] ?: 0L
            if (secs <= 0) return@forEach
            val hours = secs / 3600f
            val base = (t.CATEGORY_POINTS[cat] ?: 0f) * t.doseMultiplier(hours)
            val pts = base * timeOfDayMult
            if (pts >= 0.5f) {
                total += pts
                up.add(ScoreLine(cat.label, Math.round(pts), "${fmtDuration(secs)} today"))
            }
        }
        if (timeOfDayMult > 1f && loadSecs > 0) {
            up.add(ScoreLine(
                "Late night / just-woken use",
                0,
                "×${String.format("%.2f", timeOfDayMult)} applied to the time above",
            ))
        }

        // 2. Unlocks.
        val unlocksPerHour = day.unlocks / t.WAKING_HOURS
        ramp(unlocksPerHour, t.UNLOCKS_PER_HOUR_OK, t.UNLOCKS_PER_HOUR_BAD, t.UNLOCKS_MAX_POINTS)
            .takeIf { it >= 0.5f }?.let {
                total += it
                up.add(ScoreLine("Phone unlocks", Math.round(it),
                    "${day.unlocks} today (~${String.format("%.1f", unlocksPerHour)}/hr)"))
            }

        // 4. Urgent opens.
        if (day.urgentOpens > 0) {
            val pts = (day.urgentOpens * t.URGENT_OPEN_POINTS).coerceAtMost(t.URGENT_OPEN_MAX_POINTS)
            total += pts
            up.add(ScoreLine("Straight-in opens", Math.round(pts),
                "${day.urgentOpens}× you unlocked and were in a feed within ${t.URGENT_OPEN_SECONDS}s"))
        }

        // 5. Checking the same app over and over.
        ramp(day.maxChecksInHour.toFloat(), t.CHECKS_PER_HOUR_OK.toFloat(),
            t.CHECKS_PER_HOUR_BAD.toFloat(), t.CHECKS_MAX_POINTS)
            .takeIf { it >= 0.5f }?.let {
                total += it
                up.add(ScoreLine("Compulsive checking", Math.round(it),
                    "${day.maxChecksInHour} opens of one app in a single hour"))
            }

        // 6. Interaction rate.
        val minutesOn = day.screenOnSeconds / 60f
        if (minutesOn >= 5f) {
            val rate = day.interactions / minutesOn
            ramp(rate, t.INTERACTIONS_PER_MIN_OK, t.INTERACTIONS_PER_MIN_BAD, t.INTERACTIONS_MAX_POINTS)
                .takeIf { it >= 0.5f }?.let {
                    total += it
                    up.add(ScoreLine("Constant scrolling / tapping", Math.round(it),
                        "~${Math.round(rate)} interactions a minute"))
                }
        }

        // 7. Posture + light.
        fraction(day.lyingSeconds, t.POSTURE_FULL_HOURS).let { f ->
            val pts = f * t.LYING_MAX_POINTS
            if (pts >= 0.5f) {
                total += pts
                up.add(ScoreLine("Using it lying down", Math.round(pts), fmtDuration(day.lyingSeconds)))
            }
        }
        fraction(day.darkSeconds, t.POSTURE_FULL_HOURS).let { f ->
            val pts = f * t.DARK_MAX_POINTS
            if (pts >= 0.5f) {
                total += pts
                up.add(ScoreLine("Using it in the dark", Math.round(pts), fmtDuration(day.darkSeconds)))
            }
        }

        // ANTI-RULES.
        fraction(day.screenOffSeconds, t.SCREEN_OFF_FULL_HOURS).let { f ->
            val pts = f * t.SCREEN_OFF_MAX_POINTS
            if (pts >= 0.5f) {
                total -= pts
                down.add(ScoreLine("Time with the screen off", -Math.round(pts),
                    "${fmtDuration(day.screenOffSeconds)} awake and off the phone"))
            }
        }

        val score = total.coerceIn(0f, 100f).let { Math.round(it) }
        return DopamineResult(
            score = score,
            band = t.band(score),
            colour = t.bandColour(score),
            contributors = up.sortedByDescending { it.points },
            credits = down,
            hasData = day.screenOnSeconds > 60 || day.unlocks > 0,
        )
    }

    /** Time-of-day multiplier for the whole day's load, weighted by how much fell in each window. */
    private fun timeOfDayMultiplier(day: DopamineDay): Float {
        val load = day.loadSeconds()
        if (load <= 0) return 1f
        val lateFrac = (day.lateNightSeconds.toFloat() / load).coerceIn(0f, 1f)
        val wokeFrac = (day.justWokeSeconds.toFloat() / load).coerceIn(0f, 1f)
        val normalFrac = (1f - lateFrac - wokeFrac).coerceAtLeast(0f)
        return normalFrac +
            lateFrac * DopamineTuning.LATE_NIGHT_MULTIPLIER +
            wokeFrac * DopamineTuning.JUST_WOKE_MULTIPLIER
    }

    /** 0 points at or below [ok], [maxPoints] at or above [bad], straight line between. */
    private fun ramp(value: Float, ok: Float, bad: Float, maxPoints: Float): Float {
        if (value <= ok) return 0f
        if (value >= bad) return maxPoints
        return maxPoints * (value - ok) / (bad - ok)
    }

    private fun fraction(seconds: Long, fullHours: Float): Float =
        ((seconds / 3600f) / fullHours).coerceIn(0f, 1f)

    fun fmtDuration(seconds: Long): String {
        val m = seconds / 60
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
}


// =====================================================================================
// DopamineLog  (collects the counters; one row per day)
// =====================================================================================
/**
 * Written from the accessibility service as things happen, read by the UI. Deliberately
 * SharedPreferences and not Room: it is a handful of counters per day, and it must survive
 * being written to constantly without a DB write on every scroll event.
 */
object DopamineLog {

    private const val PREFS = "dopamine"
    private const val KEY_DAYS = "days"          // CSV of dates we have data for
    private const val MAX_DAYS = 120
    private const val SEP = "\u001F"

    @Synchronized
    fun today(context: Context): DopamineDay = load(context, todayKey())

    @Synchronized
    fun load(context: Context, date: String): DopamineDay {
        val raw = prefs(context).getString("day:$date", null) ?: return DopamineDay(date)
        return parse(date, raw)
    }

    /** The last [days] days, oldest first. Missing days come back empty (not skipped). */
    @Synchronized
    fun history(context: Context, days: Int): List<DopamineDay> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        return (0 until days).map {
            val key = fmt.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            load(context, key)
        }
    }

    @Synchronized
    fun update(context: Context, mutate: (DopamineDay) -> Unit) {
        val date = todayKey()
        val day = load(context, date)
        mutate(day)
        save(context, day)
    }

    @Synchronized
    private fun save(context: Context, day: DopamineDay) {
        val p = prefs(context)
        val dates = (p.getString(KEY_DAYS, "").orEmpty().split(",").filter { it.isNotBlank() } + day.date)
            .distinct().sorted().takeLast(MAX_DAYS)
        p.edit()
            .putString("day:${day.date}", serialise(day))
            .putString(KEY_DAYS, dates.joinToString(","))
            .apply()
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    // ── (de)serialisation: one line, field order is fixed ──
    private fun serialise(d: DopamineDay): String {
        val cats = DopamineCategory.values().joinToString(",") { "${it.name}=${d.seconds[it] ?: 0L}" }
        return listOf(
            cats, d.lateNightSeconds, d.justWokeSeconds, d.unlocks, d.urgentOpens,
            d.maxChecksInHour, d.interactions, d.screenOnSeconds, d.screenOffSeconds,
            d.lyingSeconds, d.darkSeconds,
        ).joinToString(SEP)
    }

    private fun parse(date: String, raw: String): DopamineDay {
        val p = raw.split(SEP)
        val day = DopamineDay(date)
        p.getOrNull(0)?.split(",")?.forEach { pair ->
            val k = pair.substringBefore('=');
            val v = pair.substringAfter('=', "0").toLongOrNull() ?: 0L
            runCatching { DopamineCategory.valueOf(k) }.getOrNull()?.let { day.seconds[it] = v }
        }
        day.lateNightSeconds = p.getOrNull(1)?.toLongOrNull() ?: 0L
        day.justWokeSeconds = p.getOrNull(2)?.toLongOrNull() ?: 0L
        day.unlocks = p.getOrNull(3)?.toIntOrNull() ?: 0
        day.urgentOpens = p.getOrNull(4)?.toIntOrNull() ?: 0
        day.maxChecksInHour = p.getOrNull(5)?.toIntOrNull() ?: 0
        day.interactions = p.getOrNull(6)?.toLongOrNull() ?: 0L
        day.screenOnSeconds = p.getOrNull(7)?.toLongOrNull() ?: 0L
        day.screenOffSeconds = p.getOrNull(8)?.toLongOrNull() ?: 0L
        day.lyingSeconds = p.getOrNull(9)?.toLongOrNull() ?: 0L
        day.darkSeconds = p.getOrNull(10)?.toLongOrNull() ?: 0L
        return day
    }

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun todayKey(): String = fmt.format(Date())
    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


// =====================================================================================
// LifeInputs  (the OPTIONAL, self-reported habits - a SEPARATE estimate)
// =====================================================================================
/**
 * These do NOT touch the measured score. They cannot: they're self-reported, unverifiable,
 * and mixing them in would let someone tick "meditated" and watch the number they came here
 * to face go down. They produce their own clearly-labelled estimate instead.
 */
object LifeInputs {

    private const val PREFS = "life_inputs"

    /**
     * One option on a habit's scale. [credit] is how much of that habit's full value this
     * option is worth, 0..1 - so the scales can be different lengths and still add up fairly.
     */
    data class Option(val label: String, val credit: Float)

    /**
     * A habit, and the answer scale that actually suits it.
     *
     * TYPICAL DAY, not "days last week". The scales are per-habit on purpose: eight hours of
     * sleep is a good day and eight hours of meditation is not a thing, so they cannot share
     * a scale. Full credit is set at a level that is GOOD, not heroic - the point is to
     * describe an ordinary decent day, not to make everyone score 20%.
     */
    data class Habit(val key: String, val label: String, val hint: String, val options: List<Option>)

    private fun mins(vararg pairs: Pair<String, Float>) = pairs.map { Option(it.first, it.second) }

    val HABITS: List<Habit> = listOf(
        Habit("deep_rest", "Sleep", "Actual sleep, on a typical night. Not naps.",
            mins(
                "under 5h" to 0f, "5-6h" to 0.25f, "6-7h" to 0.6f,
                "7-8h" to 1f, "8h+" to 1f,
            )),
        Habit("offline_focus", "Deep offline focus",
            "A physical book, studying, screen-free work.",
            mins(
                "none" to 0f, "<15 min" to 0.2f, "~30 min" to 0.45f, "~45 min" to 0.65f,
                "~1 hour" to 0.85f, "2 hours+" to 1f,
            )),
        Habit("training", "Intense physical training",
            "Weights, running, sport - the hard stuff.",
            mins(
                "none" to 0f, "<15 min" to 0.25f, "~30 min" to 0.6f,
                "~45 min" to 0.85f, "1 hour+" to 1f,
            )),
        Habit("building", "High-leverage building",
            "Business, career, planning, learning - work on your future.",
            mins(
                "none" to 0f, "<30 min" to 0.2f, "~1 hour" to 0.5f,
                "~2 hours" to 0.8f, "3 hours+" to 1f,
            )),
        Habit("creation", "Active creation",
            "Writing, music, cooking, making things.",
            mins(
                "none" to 0f, "<15 min" to 0.2f, "~30 min" to 0.5f,
                "~1 hour" to 0.8f, "2 hours+" to 1f,
            )),
        Habit("in_person", "In-person time, phones away",
            "Real time with people, not messaging them.",
            mins(
                "none" to 0f, "<30 min" to 0.25f, "~1 hour" to 0.55f,
                "~2 hours" to 0.85f, "3 hours+" to 1f,
            )),
        Habit("reflection", "Screen-free reflection",
            "Walking alone, meditation, sitting doing nothing. Boredom counts.",
            mins(
                "none" to 0f, "<15 min" to 0.35f, "~30 min" to 0.7f,
                "~45 min" to 0.9f, "1 hour+" to 1f,
            )),
        Habit("light_exercise", "Light movement",
            "A walk. Anything gentle. Being on your feet.",
            mins(
                "none" to 0f, "<15 min" to 0.2f, "~30 min" to 0.5f,
                "~1 hour" to 0.8f, "2 hours+" to 1f,
            )),
        Habit("healthy_eating", "Eating",
            "Not a duration - just how the day went.",
            listOf(
                Option("Binged / junk", 0f), Option("Mixed", 0.4f),
                Option("Mostly good", 0.75f), Option("Clean", 1f),
            )),
        Habit("caffeine", "Caffeine",
            "After about midday it costs you sleep, which costs you tomorrow.",
            listOf(
                Option("None", 1f), Option("1 cup, early", 0.9f), Option("2-3 cups", 0.6f),
                Option("Lots / late in the day", 0.15f),
            )),
        Habit("alcohol", "Alcohol",
            "Wrecks deep sleep even when it helps you drop off.",
            listOf(
                Option("None", 1f), Option("1 drink", 0.7f),
                Option("2-3 drinks", 0.35f), Option("More", 0f),
            )),
    )

    /** The stored option INDEX for a habit (defaults to the worst/none end of the scale). */
    fun get(c: Context, key: String): Int = prefs(c).getInt(key, defaultIndex(key))
    fun set(c: Context, key: String, index: Int) {
        val h = habit(key) ?: return
        prefs(c).edit().putInt(key, index.coerceIn(0, h.options.lastIndex)).apply()
    }

    fun habit(key: String): Habit? = HABITS.firstOrNull { it.key == key }
    fun optionOf(key: String, index: Int): Option? =
        habit(key)?.options?.getOrNull(index)

    fun anySet(c: Context) = HABITS.any { get(c, it.key) != defaultIndex(it.key) }

    // Caffeine and alcohol default to "none", which is the GOOD end - so someone who never
    // opens this page isn't quietly assumed to be drinking. Everything else defaults to 0.
    private fun defaultIndex(key: String) = 0

    /**
     * A 0-100 "restorative habits" estimate. Higher = better, which is the OPPOSITE direction
     * to the dopamine score - deliberately, so the two can never be confused for each other.
     */
    fun estimate(c: Context): Int {
        if (HABITS.isEmpty()) return 0
        val total = HABITS.sumOf { h ->
            (optionOf(h.key, get(c, h.key))?.credit ?: 0f).toDouble()
        }
        return Math.round(total / HABITS.size * 100).toInt()
    }

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


// =====================================================================================
// AboutYou  (optional personal numbers, used ONLY to make the cost concrete)
// =====================================================================================
object AboutYou {
    private const val PREFS = "about_you"
    private const val KEY_WAGE = "hourly_wage"
    private const val KEY_SIDE = "side_hourly"
    private const val KEY_WAGE_YR = "annual_wage"
    private const val KEY_SIDE_YR = "annual_side"

    /** UK median-ish. Used when the user hasn't told us theirs. */
    const val DEFAULT_HOURLY_GBP = 12

    /** Full-time working hours in a year (~37.5 h × 52) - the annual↔hourly bridge. */
    const val HOURS_PER_YEAR = 1950

    // The UI asks PER YEAR now ("£28,000" is how people actually know their income).
    // The old per-hour keys are kept as a read-only fallback for anyone who already
    // entered them, converted through HOURS_PER_YEAR.
    fun annualWage(c: Context): Int = prefs(c).getInt(KEY_WAGE_YR, 0)
        .takeIf { it > 0 } ?: (prefs(c).getInt(KEY_WAGE, 0) * HOURS_PER_YEAR)
    fun setAnnualWage(c: Context, v: Int) =
        prefs(c).edit().putInt(KEY_WAGE_YR, v.coerceIn(0, 10_000_000)).apply()

    fun annualSide(c: Context): Int = prefs(c).getInt(KEY_SIDE_YR, 0)
        .takeIf { it > 0 } ?: (prefs(c).getInt(KEY_SIDE, 0) * HOURS_PER_YEAR)
    fun setAnnualSide(c: Context, v: Int) =
        prefs(c).edit().putInt(KEY_SIDE_YR, v.coerceIn(0, 10_000_000)).apply()

    fun hasData(c: Context) = annualWage(c) > 0 || annualSide(c) > 0

    /** What we value a year of their time at: theirs if given, else the default rate. */
    fun effectiveAnnual(c: Context): Int =
        maxOf(annualWage(c), annualSide(c)).takeIf { it > 0 }
            ?: (DEFAULT_HOURLY_GBP * HOURS_PER_YEAR)

    /** The per-hour rate every projection uses, derived from the annual figure. */
    fun effectiveHourly(c: Context): Int =
        Math.round(effectiveAnnual(c).toFloat() / HOURS_PER_YEAR).coerceAtLeast(1)

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


// =====================================================================================
// DopamineRank  (the belt system: hold a low baseline over time to rank up)
// =====================================================================================
/**
 * A prestige ladder over the daily dopamine score, CoD-prestige / dojo-belt style.
 * The top ranks are EARNED BY TIME: not "a good day", but a low baseline HELD for
 * weeks. The bottom ranks come straight from the recent average, so a rough week
 * demotes you quickly but climbing back is a long game - that asymmetry is the point.
 *
 * Rank rules (checked top-down, first match wins):
 *   Monk     - 60+ day streak of scores ≤ 25
 *   Guru     - 30+ day streak of scores ≤ 30
 *   Sensei   - 14+ day streak of scores ≤ 35
 *   Disciple -  7+ day streak of scores ≤ 40
 *   Apprentice - 7-day average < 40
 *   Drifter    - 7-day average < 50
 *   Twitchy    - 7-day average < 60
 *   Doomscroller - 7-day average < 75
 *   Fried      - 7-day average ≥ 75
 * A "streak" walks back from today; days with no data are skipped (they neither
 * count nor break it), a data day above the threshold breaks it.
 */
data class DopamineRankResult(
    val title: String,
    val longTitle: String,    // the flavour name ("Adenosine Apprentice")
    val colour: Int,
    val detail: String,       // one line: what earned it / what's next
    val hasData: Boolean,
)

object DopamineRank {

    private data class Belt(val title: String, val longTitle: String, val streakDays: Int, val maxScore: Int, val colour: Int)

    // Earned belts, best first. Colours darken as you climb.
    private val BELTS = listOf(
        Belt("Monk", "Melatonin Monk", 60, 25, 0xFF1B5E20.toInt()),
        Belt("Guru", "Grounded Guru", 30, 30, 0xFF2E7D32.toInt()),
        Belt("Sensei", "Serotonin Sensei", 14, 35, 0xFF2E9E8F.toInt()),
        Belt("Disciple", "Dopamine Disciple", 7, 40, 0xFF3E7CB1.toInt()),
    )

    fun of(context: Context): DopamineRankResult {
        val history = DopamineLog.history(context, 90)
        val scored = history.map { d -> DopamineScore.of(d).let { if (it.hasData) it.score else -1 } }
        // Day one is already a rank: installing the thing that watches the habit IS the
        // first step of the journey, and it should read that way, not as "unranked".
        if (scored.none { it >= 0 }) return DopamineRankResult(
            "Initiate", "The Journey Begins", 0xFF2E9E8F.toInt(),
            "Step one - noticing - is done. A day of normal phone use unlocks your real rank.", false)

        // Streak per threshold: newest-first walk; no-data days are skipped.
        fun streak(maxScore: Int): Int {
            var n = 0
            for (s in scored.asReversed()) {
                if (s < 0) continue
                if (s > maxScore) break
                n++
            }
            return n
        }

        for ((i, belt) in BELTS.withIndex()) {
            if (streak(belt.maxScore) >= belt.streakDays) {
                val next = BELTS.getOrNull(i - 1)
                val detail = if (next == null) "The top of the mountain. Keep sitting on it."
                else "${belt.streakDays}+ days with a baseline ≤ ${belt.maxScore}. " +
                    "Next: ${next.title} - ${next.streakDays} days ≤ ${next.maxScore}."
                return DopamineRankResult(belt.title, belt.longTitle, belt.colour, detail, true)
            }
        }

        // No belt yet: rank straight off the recent average, and show the path in.
        val recent = scored.takeLast(7).filter { it >= 0 }
        val avg = if (recent.isEmpty()) scored.last { it >= 0 } else recent.average().toInt()
        val entry = BELTS.last()
        val path = "Next: ${entry.title} - ${entry.streakDays} days ≤ ${entry.maxScore} (streak so far: ${streak(entry.maxScore)})."
        return when {
            avg < 40 -> DopamineRankResult("Apprentice", "Adenosine Apprentice", 0xFF52796F.toInt(), "7-day average $avg. $path", true)
            avg < 50 -> DopamineRankResult("Drifter", "Dopamine Drifter", 0xFF9A7B00.toInt(), "7-day average $avg. $path", true)
            avg < 60 -> DopamineRankResult("Twitchy", "The Twitchy Thumb", 0xFFB07800.toInt(), "7-day average $avg. $path", true)
            avg < 75 -> DopamineRankResult("Doomscroller", "Certified Doomscroller", 0xFFC0392B.toInt(), "7-day average $avg. $path", true)
            else -> DopamineRankResult("Fried", "Fully Fried", 0xFF8E1600.toInt(), "7-day average $avg. It only goes up from here. $path", true)
        }
    }
}
