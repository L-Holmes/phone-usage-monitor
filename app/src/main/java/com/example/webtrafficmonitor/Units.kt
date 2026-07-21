package com.example.webtrafficmonitor

import android.content.Context
import android.content.res.Resources
import androidx.core.os.ConfigurationCompat
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
    fun symbol(c: Context): String = currencyOf(c).getSymbol(uiLocale(c))

    /** A currency's name in the UI language, e.g. "British Pound" - for the picker. */
    fun currencyName(c: Context, code: String): String =
        runCatching { Currency.getInstance(code).getDisplayName(uiLocale(c)) }.getOrNull() ?: code

    /** A currency's symbol in the UI language - for the picker. */
    fun currencySymbol(c: Context, code: String): String =
        runCatching { Currency.getInstance(code).getSymbol(uiLocale(c)) }.getOrNull() ?: code

    /**
     * A whole-money amount written the way this language writes money: "£13,000",
     * "13.000 €", "$13,000". Never any pence - these are all estimates and projections.
     */
    fun money(c: Context, amount: Number): String =
        NumberFormat.getCurrencyInstance(uiLocale(c)).apply {
            currency = currencyOf(c)
            maximumFractionDigits = 0
        }.format(amount)

    // ── Durations ────────────────────────────────────────────────────────────────────

    /** A plain number with this language's grouping ("13,000" / "13.000"). */
    fun number(c: Context, v: Number): String = NumberFormat.getInstance(uiLocale(c)).format(v)

    /** A whole percentage from a 0..1 fraction, spaced the way this language spaces it. */
    fun percent(c: Context, fraction: Float): String =
        NumberFormat.getPercentInstance(uiLocale(c)).apply { maximumFractionDigits = 0 }.format(fraction)

    /** One decimal place, this language's decimal mark ("1.5" / "1,5"). */
    fun decimal1(c: Context, v: Float): String = String.format(uiLocale(c), "%.1f", v)

    fun hours(c: Context, v: String): String = c.getString(R.string.unit_h, v)
    fun hours(c: Context, v: Number): String = hours(c, number(c, v))
    fun mins(c: Context, v: String): String = c.getString(R.string.unit_m, v)
    fun mins(c: Context, v: Number): String = mins(c, number(c, v))
    fun secs(c: Context, v: Number): String = c.getString(R.string.unit_s, number(c, v))
    fun hoursMins(c: Context, h: Number, m: Number): String =
        c.getString(R.string.unit_h_m, number(c, h), number(c, m))
    fun daysHours(c: Context, d: Number, h: Number): String =
        c.getString(R.string.unit_d_h, number(c, d), number(c, h))

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
