package com.example.webtrafficmonitor

import android.content.Context
import android.content.res.Resources
import androidx.core.os.ConfigurationCompat
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

// =====================================================================================
//  Units  —  the money and duration side of localisation.
// =====================================================================================
//
//  Strings are handled by res/values-<code>/ (see LocaleHelper). The numbers next to them
//  are NOT: "£13,000" and "1h 20m" are just as English as the sentences around them. This
//  object is the single place that turns a number into user-facing text, so every screen
//  says it the same way in every language.
//
//  MONEY. The amounts are the user's own (their salary, typed by them), so there is NO fx
//  conversion anywhere - we only ever change how the amount is WRITTEN:
//    * which currency: the DEVICE REGION's by default (a UK phone -> GBP, an Italian phone
//      -> EUR), overridable in Settings for anyone whose money and phone disagree.
//    * how it is written: the APP LANGUAGE's convention, so the symbol lands on the right
//      side with the right separators ("£13,000" vs "13.000 €").
//  Region and language are read separately on purpose: picking Italian in our language
//  picker must not silently re-denominate someone's British salary.
//
//  DURATIONS. The "h"/"m" suffixes live in strings.xml (unit_h, unit_m, ...) so a
//  translator can change them; the number itself is grouped/decimal-pointed per language.
object Units {

    private const val PREFS = "units"
    private const val KEY_CURRENCY = "currency_code"   // ISO-4217, "" = follow the device
    private const val FALLBACK = "GBP"

    // ── Currency ─────────────────────────────────────────────────────────────────────

    /**
     * Currencies offered in the picker, first entry = "follow the device region".
     * Not exhaustive by design: the common ones plus everywhere the app is likely to be
     * used. Anything else still works via the device region - this list is only the
     * manual override.
     */
    val SUPPORTED_CURRENCIES: List<String> = listOf(
        "", "GBP", "EUR", "USD", "CAD", "AUD", "NZD", "CHF",
        "SEK", "NOK", "DKK", "PLN", "CZK", "JPY", "INR", "BRL", "MXN", "ZAR",
    )

    /**
     * The locale to FORMAT with: the app's UI language, so "13,000" vs "13.000" matches the
     * text around it. (This one moves when the user picks a language in-app.)
     */
    fun uiLocale(c: Context): Locale =
        ConfigurationCompat.getLocales(c.resources.configuration)[0] ?: Locale.getDefault()

    /**
     * The locale to take the REGION from: the system's, never the app's. Locale.getDefault()
     * is no good here - the per-app language override rewrites it to a bare language ("it")
     * with no country at all, which would both lose the region and throw below.
     */
    private fun deviceLocale(): Locale =
        ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0] ?: Locale.getDefault()

    /** The device region's currency code, or null if the region has none we can resolve. */
    private fun deviceCurrencyCode(): String? =
        runCatching { Currency.getInstance(deviceLocale()).currencyCode }.getOrNull()

    /** The user's manual override ("" when following the device). */
    fun currencyOverride(c: Context): String =
        prefs(c).getString(KEY_CURRENCY, "").orEmpty()

    /** Set the override; "" goes back to following the device region. */
    fun setCurrencyOverride(c: Context, code: String) =
        prefs(c).edit().putString(KEY_CURRENCY, code).apply()

    /** The ISO-4217 code actually in force: override, else device region, else sterling. */
    fun currencyCode(c: Context): String =
        currencyOverride(c).ifBlank { deviceCurrencyCode() ?: FALLBACK }

    private fun currencyOf(c: Context): Currency =
        runCatching { Currency.getInstance(currencyCode(c)) }.getOrNull()
            ?: Currency.getInstance(FALLBACK)

    /** Just the symbol ("£", "€", "$") - for hints and prose with no amount attached. */
    fun symbol(c: Context): String = narrowSymbol(c, currencyCode(c))

    /** A currency's name in the UI language, e.g. "British Pound" - for the picker. */
    fun currencyName(c: Context, code: String): String =
        runCatching { Currency.getInstance(code).getDisplayName(uiLocale(c)) }.getOrNull() ?: code

    /**
     * The most COMPACT symbol this language will accept for [code].
     *
     * CLDR gives some currencies no symbol in some languages - Italian writes the US dollar
     * "USD", Japanese yen "JPY". That is correct, but three letters in a stat cell wrap, and
     * a wrapped currency renders as "16.890 U" / "SD". "$" is understood in every language,
     * so we borrow the symbol from a locale that has one. Currencies with no symbol ANYWHERE
     * (CHF, SEK, NOK, ...) legitimately stay as their code.
     */
    fun narrowSymbol(c: Context, code: String): String {
        val cur = runCatching { Currency.getInstance(code) }.getOrNull() ?: return code
        val local = cur.getSymbol(uiLocale(c))
        if (local != code) return local
        for (l in listOf(Locale.US, Locale.ROOT)) {
            val s = cur.getSymbol(l)
            if (s != code && s.length < code.length) return s
        }
        return code
    }

    /**
     * A whole-money amount written the way this language writes money: "£13,000",
     * "13.000 $", "13,000 €". Never any pence - these are all estimates and projections.
     *
     * The gap before/after the symbol is tightened (see [tighten]): the default is a full-width
     * space, which both reads as though the symbol belongs to the next word and lets a narrow
     * cell wrap the value away from its own currency.
     */
    fun money(c: Context, amount: Number): String {
        val nf = NumberFormat.getCurrencyInstance(uiLocale(c))
        nf.maximumFractionDigits = 0
        if (nf is DecimalFormat) {
            // Order matters: setting the currency also resets the symbol, so ours goes second.
            nf.decimalFormatSymbols = nf.decimalFormatSymbols.apply {
                currency = currencyOf(c)
                currencySymbol = narrowSymbol(c, currencyCode(c))
            }
        } else {
            nf.currency = currencyOf(c)
        }
        return nf.format(amount).tighten()
    }

    /**
     * Bind a value to the unit beside it: a small gap that cannot be wrapped across.
     *
     * The obvious character, NARROW NO-BREAK SPACE, is no good in practice - plenty of vendor
     * fonts have no glyph for it and fall back to a full-width space, which is exactly the
     * "the € is miles from the number" look we are fixing (measured: no change at all on a
     * Samsung device). HAIR SPACE is honoured, so we use that and put the no-break back with
     * WORD JOINERs either side.
     */
    private fun String.tighten(): String =
        replace("\u00A0", TIGHT).replace(" ", TIGHT)

    /** WORD JOINER + HAIR SPACE + WORD JOINER: as narrow as fonts will actually render. */
    private const val TIGHT = "\u2060\u200A\u2060"

    // ── Durations ────────────────────────────────────────────────────────────────────

    /** A plain number with this language's grouping ("13,000" / "13.000"). */
    fun number(c: Context, v: Number): String = NumberFormat.getInstance(uiLocale(c)).format(v)

    /** A whole percentage from a 0..1 fraction, spaced the way this language spaces it. */
    fun percent(c: Context, fraction: Float): String =
        NumberFormat.getPercentInstance(uiLocale(c)).apply { maximumFractionDigits = 0 }
            .format(fraction).tighten()

    /** One decimal place, this language's decimal mark ("1.5" / "1,5"). */
    fun decimal1(c: Context, v: Float): String = String.format(uiLocale(c), "%.1f", v)

    // Each unit tightens its own gap, so translators write a NORMAL space ("%1$s min") and
    // never have to know about hair spaces. The COMPOUND forms just join two finished parts,
    // which keeps the space BETWEEN them a real, breakable one.
    fun hours(c: Context, v: String): String = c.getString(R.string.unit_h, v).tighten()
    fun hours(c: Context, v: Number): String = hours(c, number(c, v))
    fun mins(c: Context, v: String): String = c.getString(R.string.unit_m, v).tighten()
    fun mins(c: Context, v: Number): String = mins(c, number(c, v))
    fun secs(c: Context, v: Number): String = c.getString(R.string.unit_s, number(c, v)).tighten()
    fun days(c: Context, v: Number): String = c.getString(R.string.unit_d, number(c, v)).tighten()
    fun hoursMins(c: Context, h: Number, m: Number): String =
        c.getString(R.string.unit_h_m, hours(c, h), mins(c, m))
    fun daysHours(c: Context, d: Number, h: Number): String =
        c.getString(R.string.unit_d_h, days(c, d), hours(c, h))
    fun underAnHour(c: Context): String = c.getString(R.string.unit_under_1h).tighten()

    /** "3h" / "0h 45m" from a count of hours - the stats/graph house style. */
    fun fromHours(c: Context, hours: Float): String {
        val m = Math.round(hours * 60)
        return if (m % 60 == 0) hours(c, m / 60) else hoursMins(c, m / 60, m % 60)
    }

    /** "1h 20m" / "45m" from a count of seconds - the score-breakdown house style. */
    fun fromSeconds(c: Context, seconds: Long): String {
        val m = seconds / 60
        return if (m >= 60) hoursMins(c, m / 60, m % 60) else mins(c, m)
    }

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
