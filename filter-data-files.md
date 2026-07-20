# Filter data files — lists loaded from `assets/filter/`, not hardcoded

The lists that decide what to block are being moved out of Kotlin into plain, easy-to-edit text
files under `app/src/main/assets/filter/`. One entry per line; `#` starts a comment. Loaded by
`FilterData` (see `FilterData.kt`), read lazily and cached; `FilterData.init(context)` runs at
startup (MainActivity + the service).

## Language strategy (the key decision)

- **Language-NEUTRAL** — apps (package names) and domains (hosts). A package name / URL is the
  same in every language, so **one shared file each**.
    `assets/filter/domains_*.txt`, `apps_*.txt`, `browsers_*.txt`  → `FilterData.set("…")`
- **Language-SPECIFIC** — keywords, phrases, and innocent-context exceptions (medical etc.).
  These are words, so they differ per language. **Per-language folder, English is the master:**
    `assets/filter/words/en/…`, later `assets/filter/words/es/…`
  `FilterData.langSet("…")` reads English ALWAYS and UNIONs the device language's file if we
  ship one (so adult content is caught whatever language the page is in). No file for a
  language → just English. Nothing ever breaks.

## Format conventions

- Simple list: one token/host/package per line.
- Apps that need a friendly name (the whitelist picker shows it): `Friendly Name = com.pkg`.
- Exceptions (per-word innocent neighbours): `word: neighbour1, neighbour2, …` per line.

## Status

### Done
- [x] `FilterData` loader (neutral `set()`/`lines()` + language `langSet()`/`langLines()`, init, cache).
- [x] `domains_search_engines.txt` → `SearchEngineBlocklist`
- [x] `domains_strict_only.txt` → `StrictOnlyBlocklist`
- [x] `domains_greylist.txt` → `DomainGreylist`

### Next — language-neutral (low risk)
- [x] `apps_safe.txt` (`Name = pkg`) ← `AppConfig.SAFE_APPS_BY_NAME` (names kept for the picker); `map()` loader
- [x] `apps_greylist.txt` ← `AppConfig.GREYLIST_APPS_BY_NAME`
- [x] `browsers_allowed.txt` ← `AppConfig.ALLOWED_BROWSERS`
- [x] `browsers_blocked.txt` ← `AppConfig.BLOCKED_BROWSERS`
      (NOTE: AppConfig fields + Whitelist/AppBlocklist captures are now `get()`, not `val`,
       so they read after FilterData.init rather than capturing an empty set at object-init.)
- [ ] `apps_night_guard.txt` / `apps_lockdown.txt` (essentials substrings) — optional
- [ ] `mode_keywords_strict.txt` / `mode_keywords_superhardcore.txt` ← `ModeKeywords`
      (these ARE language-specific in spirit, but "reddit"/site-slugs are mostly neutral —
      decide per entry; safest is words/en/ + union)

### Done — language-specific word lists (scoring hot path)
Moved into **`src/main/resources/filter/words/en/`** — NOT assets. Why resources: the scorer
has a pure-JVM unit test (`BorderlineScorerTest`, no Android Context); classpath resources load
there AND on-device, assets don't. `FilterData.langSet/langLines` read them via the classloader
(English master ∪ device language). Verified by `./gradlew testDebugUnitTest` (all pass →
behaviour unchanged).
- [x] `words_core/mixed/support/combo/extra_explicit/subtle/dual/ambiguous/person.txt`
- [x] `variant_explicit.txt`, `variant_dual.txt`
- [x] `phrases_loud.txt`, `phrases_soft.txt`
- [x] `medical_context.txt`
- [x] `exceptions.txt` (`word: n1, n2, …`) → `BannedWordExceptions` parser
- [x] `family_groups.txt` (`head, infl1, …`) → `BannedWords.famOf` parser
- [x] `BannedWords` / `BannedWordExceptions` / `MedicalContext` / `BannedPhrases` now read via
      `FilterData` (getters + cached parsers). Scorer logic untouched.

### Done — GenderedTerms
- [x] `GenderedTerms` (SOFT_FEMALE / SOFT_MALE / PHRASES_FEMALE / PHRASES_MALE) →
      `words/en/gendered_female.txt` / `gendered_male.txt` / `gendered_phrases_female.txt` /
      `gendered_phrases_male.txt`, loaded via `FilterData.langSet`. Getters, not vals.
      Unit-test verified (attraction-switch tests still pass).

### Done — night-guard / lockdown essentials
- [x] `NIGHT_GUARD_ALLOWED_SUBSTRINGS` → `assets/filter/apps_night_guard.txt`
- [x] `LOCKDOWN_ALLOWED_SUBSTRINGS` → `assets/filter/apps_lockdown.txt`
      (getters; the eager captures in AccessibilityService/Lockdown made getters too.)

### Intentionally left in code
- `SHORT_FORM_PATTERNS` — entangled with `AppConfig.TemptationSpec.blockPatterns` (init ordering);
  moving it is risk for zero benefit. Stays a compile-time constant.
- `SEARCH_ENGINES` (the `Search` query-param specs) — structured data, not a flat list; not user-facing.
- Also removed dead code: `DEFAULT_ROOMS` (unused).

## ✅ Filter data-file extraction COMPLETE for all the meaningful lists
Every word tier / phrase / exception / medical-context / family / gendered list is in
`src/main/resources/filter/words/en/`; every domain/app/browser list is in `assets/filter/`.
Only the optional low-value neutral specs above remain in code.

### Shared with the Firefox plugin
Because these are now plain text files, the extension can eventually read the SAME files
(bundled into it) instead of duplicating the lists — the cleanest possible version of the
"keep the two in sync" goal. Track under guide-keep-word-monitoring-consistent.txt.

## After every file moved
`./gradlew :app:compileDebugKotlin` + `./gradlew checkTranslations` + sanity-run the block flow.
