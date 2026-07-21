# Translating the app

The app uses **Android's built-in string localization** — the standard, market-ready way to
ship one app in many languages. There is **one app**, not one per language: the OS picks the
right strings for the device's language and falls back to English for anything missing.

## The pieces

| Thing | Where | Role |
|---|---|---|
| **English master** | `app/src/main/res/values/strings.xml` | The source of truth. Every key lives here first. |
| A translation | `app/src/main/res/values-<code>/strings.xml` | Same keys, translated values. e.g. `values-es`, `values-fr`, `values-pt-rBR`. |
| Shipped-languages list | `app/src/main/res/xml/locales_config.xml` | Drives the in-app picker + the system per-app-language screen. |
| In-app picker | `LocaleHelper.kt` + **Developer tools → Language** | Lets the user override the language; defaults to system, then English. |
| Units (money + time) | `Units.kt` + **Developer tools → Currency** | The currency symbol and the `h`/`m` duration suffixes. See "Units" below. |
| Validation | `./gradlew checkTranslations` | **Fails on drift (EXTRA keys), warns on MISSING (with coverage %).** Runs automatically in every build (`preBuild`). |
| Shipped languages (build) | `app/build.gradle.kts` → `resourceConfigurations` | Declares which languages the bundle carries (currently `en`, `it`). Keep in step with the three rows above. |
| Filter words per language | `src/main/resources/filter/words/<code>/*.txt` | Adult-content word lists for that language (unioned with English). Optional; the scorer works English-only without it. |

## How this maps to a Google Play multi-market release (the rational setup)

- **One app, not one per country.** Ship a single **Android App Bundle**; Play generates
  per-device splits and delivers only the language resources that device needs. You never build
  separate APKs per country.
- **Target languages, not countries.** A country is not a language (Switzerland ≠ one language).
  Resource qualifiers are `values-<lang>` (optionally `-r<REGION>` for e.g. `pt-rBR`). Play store
  *listing* translations are managed separately in Play Console — that's marketing copy, not these
  strings.
- **English is the master + automatic fallback.** The OS resolves device-language → language
  (no region) → default `values/` (English). A key a locale hasn't translated shows English; a
  language you don't ship shows English. Nothing is ever blank.
- **Translations lag code — and that's fine.** `checkTranslations` therefore **fails only on
  drift** (a key not in English can never render, so it's a bug) and **warns on missing** with a
  per-locale coverage %, so CI tracks the gap without blocking releases. You do NOT need 100% of a
  language before the rest can ship.
- **Users can override the language in-app** (Android 13 per-app language, backported by AppCompat)
  via the picker; the choice persists. `resourceConfigurations` + `locales_config.xml` make the
  system/app language screen offer only the languages you actually translated.

**Example locale shipped:** Italian (`it`) — a partial translation demonstrating the whole flow.
Run `./gradlew checkTranslations` to see its coverage. Untranslated keys fall back to English.

## Units: money and durations

Strings alone are not a translation. `£13,000` and `1h 20m` are as English as the sentence
around them, so every number-with-a-unit goes through **`Units.kt`**.

**Money.** There is **no fx conversion anywhere**, on purpose: the amounts are the user's own
salary, typed by them, so converting would invent a number they never entered. Only the
*writing* changes:

- **Which currency** comes from the **device region** (a UK phone → GBP, an Italian phone → EUR),
  overridable in **Settings → Currency**. The region is read from `Resources.getSystem()`, *not*
  `Locale.getDefault()` — our per-app language override rewrites that to a bare language tag with
  no country at all.
- **How it is written** follows the **app language**, via `NumberFormat.getCurrencyInstance`:
  `£13,000` in English, `13.000 £` in Italian. Symbol side and separators come free.

So picking Italian does not silently re-denominate a British salary, and moving to Italy does
not force you to read your pay in English punctuation.

```kotlin
Units.money(this, 13000)   // "£13,000" / "13.000 €"
Units.symbol(this)         // "£" — for a hint, or a bare "years and £" sentence
```

Strings that mention money take a `%s` and are handed a formatted amount — never a bare `%d`
next to a currency symbol typed into the string.

**Durations.** The short suffixes are ordinary translatable strings, so a translator can change
them (Italian ships `1 h 20 min`, `3 g 4 h`):

| Key | English | Italian |
|---|---|---|
| `unit_h` / `unit_m` / `unit_s` | `%1$sh` / `%1$sm` / `%1$ss` | `%1$s h` / `%1$s min` / `%1$s s` |
| `unit_h_m` | `%1$sh %2$sm` | `%1$s h %2$s min` |
| `unit_d_h` | `%1$sd %2$sh` | `%1$s g %2$s h` |
| `unit_under_1h` | `<1h` | `<1 h` |

Keep them **short** — several land on a narrow chart axis. Use the helpers rather than
concatenating:

```kotlin
Units.fromHours(this, 1.33f)     // "1h 20m"   (stats / graph house style)
Units.fromSeconds(this, 4800)    // "1h 20m"   (score-breakdown house style)
Units.hours(this, 3)             // "3h"
Units.number(this, 13000)        // "13,000" / "13.000"
Units.decimal1(this, 2.5f)       // "2.5" / "2,5"
Units.percent(this, 0.42f)       // "42%"
```

**Rule of thumb:** never build a user-facing number by string concatenation (`"${h}h"`, `"£$n"`).
If you catch yourself typing a unit inside a Kotlin string, it belongs in `Units`.

## Fallback (why nothing is ever blank)

Android resolves each string against the device language, then the language without region,
then the default `values/` (English). So a missing key, or a language we don't ship, simply
shows the English text. **We focus on English now; other languages can be added any time
without touching code.**

## How to add a NEW STRING (do this as you migrate code, too)

1. Add it to the English master `res/values/strings.xml`:
   ```xml
   <string name="block_saved">Saved</string>
   ```
2. Use it in code (any `Context` / Activity):
   ```kotlin
   textView.text = getString(R.string.block_saved)
   // with arguments:
   toast(getString(R.string.toast_blocking, host))   // "Blocking: %1$s"
   ```
   - Escape apostrophes: `\'`. Wrap `%` args as `%1$s`, `%1$d`, …
   - Not user-facing? (log tags, package names, pref keys) → leave it a plain Kotlin string.

## How to add a NEW LANGUAGE (e.g. Spanish) — 4 required steps + translate

1. Create `res/values-es/strings.xml` (copy the master or start empty — untranslated keys fall
   back to English, so you can ship partial and fill in over time). Keep every `name="…"`
   **identical**; translate only the values.
2. Add the locale to `res/xml/locales_config.xml`: `<locale android:name="es" />`.
3. Add it to `LocaleHelper.SUPPORTED`: `Lang("es", "Español")`.
4. Add it to `resourceConfigurations` in `app/build.gradle.kts`: `listOf("en", "it", "es")`.
5. (optional) Add adult-content word files for the language:
   `src/main/resources/filter/words/es/*.txt` (unioned with English; see `filter-data-files.md`).
6. Run `./gradlew checkTranslations` — it prints the coverage % and warns which keys still fall
   back to English. It only **fails** if you left an EXTRA key that isn't in the English master.

See the shipped **Italian (`it`)** locale for a worked partial example.

## Validation details (the production policy)

`checkTranslations` (in `app/build.gradle.kts`) compares the `<string name>` keys of every
`values-*/strings.xml` against the English master and, per language:
- **EXTRA keys → FAIL.** A key not in English can never render — it's drift/a typo/a stale key.
- **MISSING keys → WARN**, with a coverage `%`. They fall back to English at runtime, so they're
  never a broken screen, and requiring 100% before shipping would block every code change that
  adds an English string. CI sees the gap; the release isn't gated on it.

It's wired into `preBuild`, so `./deploy` / `assembleRelease` run it; run it alone with
`./gradlew checkTranslations`.

---

## Status: migrating the existing UI

The screens are built in Kotlin with many inline English literals (`TextView().apply { text =
"…" }`). Making the whole app translatable means moving those literals into `strings.xml` and
referencing `getString(R.string.…)`. That's a large, purely mechanical migration, done file by
file. The **system, picker, fallback and validation above are already complete** — every new
or migrated string just drops into the master catalog. Migrate opportunistically (whenever you
touch a screen) or in dedicated batches; `checkTranslations` keeps the languages honest as you
go.
