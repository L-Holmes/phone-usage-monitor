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
| Validation | `./gradlew checkTranslations` | Fails the build if any language's keys don't match the English master. Runs automatically in every build (`preBuild`). |

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

## How to add a NEW LANGUAGE (e.g. Spanish)

1. Copy the master: `res/values/strings.xml` → `res/values-es/strings.xml`.
2. Translate every **value**; keep every `name="…"` **identical**.
3. Add the locale to `res/xml/locales_config.xml`: `<locale android:name="es" />`.
4. Add it to `LocaleHelper.SUPPORTED`: `Lang("es", "Español")`.
5. Run `./gradlew checkTranslations` — it fails and lists any key that's missing/extra vs
   English, so a half-finished translation can't ship.

## Validation details

`checkTranslations` (in `app/build.gradle.kts`) compares the `<string name>` keys of every
`values-*/strings.xml` against the English master and, for each language, prints the keys that
are **MISSING** (in English, not yet translated) and **EXTRA** (present but not in English —
usually a typo or a key that was removed from the master). Any mismatch fails the build. It's
wired into `preBuild`, so `./deploy` / `assembleRelease` are gated by it; run it alone with
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
