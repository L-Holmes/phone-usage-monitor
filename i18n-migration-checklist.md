# i18n migration checklist — moving inline UI strings into `strings.xml`

Goal: every **on-screen** string comes from `res/values/strings.xml` via `getString(R.string.…)`,
so the app is fully translatable. English master + fallback + picker + `checkTranslations` are
already in place (see `TRANSLATING.md`).

## What DOES and does NOT get migrated

**Migrate** (user sees it): `TextView`/`Button` `text =`, `titleText(...)`, `Toast`, notification
title/text, overlay/block-screen copy, dialog text, content descriptions, mode names/summaries.

**Do NOT migrate** (not user-facing — leave as plain Kotlin strings): log messages / `Log.x`
tags, `SharedPreferences` keys, package names, DB column names, regexes, the word-filter data
lists (`BannedWords`, `BannedPhrases`, `BannedWordExceptions`, `MedicalContext`, domain lists),
BuildConfig, debug-only `Sensor debug`/`Room beacon debug` raw dumps (optional, low value).

## Per-batch procedure (repeat for every unchecked item)

1. Read the file/screen; list only the **on-screen** literals.
2. Add keys to `res/values/strings.xml` under a `<!-- ── <area> ── -->` section header.
   - Key naming: `area_slug` (e.g. `overlay_block_title`, `mode_relaxed_name`, `stats_title`).
   - Escape `'` as `\'`; turn `"$x"` into `%1$s` / `%1$d` args passed to `getString(key, x)`.
3. Replace each literal with `getString(R.string.key)` (or `getString(R.string.key, arg)`).
4. `./gradlew :app:compileDebugKotlin` — must be clean.
5. `./gradlew checkTranslations` — must pass.
6. Tick the box here, note the key-prefix used, commit-sized and reviewable.

Reliability: small, self-contained files go whole; big files (**Main.kt**) go **one `showX()`
screen at a time** — never the whole file at once.

---

## Order (top = do first: small, self-contained, high-visibility → build the pattern)

### Phase 1 — small self-contained UI (fast wins, prove the pattern)
- [x] **Overlay.kt** — breathing-gate copy (4 keys, prefix `overlay_`). Block-cover reason
      comes from callers; static cover labels were already in strings.xml. ✅ compiles + validates
- [ ] **AppConfig.kt (mode specs only)** — `ModeSpec.displayName` + `summary` lines + the
      always-on rule lines. ⚠ DEFERRED: AppConfig is Context-free compile-time data used
      widely; needs a small refactor to resolve text via resources at DISPLAY time (in
      showModeRules, which has a Context) using `<string-array>` keyed by mode id. Do as its
      own batch. prefix `mode_` / `rule_`
- [x] **Greyscale.kt** — N/A, no on-screen text (literals were log/pref keys).
- [x] **UninstallGuard.kt** — N/A, no on-screen text.
- [x] **Sensors.kt** — N/A, no on-screen text.

### Phase 2 — services (block reasons + notifications the user sees)
- [x] **AccessibilityService.kt** — all block-cover reason strings (lockdown/waiting/blocked app/
      grey limit/night guard/room/adult site/search engine/blocked site/term/keyword/back-status/
      rapid block). prefix `br_`. ✅ compiles + validates.
- [x] **Views.kt** — breathing animator "Breathe in/out" → `overlay_breathe_in/out`. ✅

### Phase 3 — feature modules (ALL DONE / N/A)
- [x] **Dopamine.kt** — N/A (no UI text; screens live in Main.kt, done)
- [x] **RelapseLog.kt** — DONE: `analyze`/`encouragement`/`dayName` now take a Context and read
      resources (`relapse_fb_*`/`relapse_enc_*` + `relapse_days` array). Main caller updated. ✅
- [x] **BlockEventLog.kt** — N/A (0 UI-text sites)
- [x] **Blocking.kt** — N/A (0 UI-text sites)
- [x] **RoomBeacons.kt** — N/A (0 UI-text sites; beacon setup screens live in Main.kt dev region → English)
- [x] **Database.kt** — N/A (0 UI-text sites)

### Phase 4 — Main.kt, screen by screen (the big one; one function per batch)
- [~] Setup / home / permissions screens — PERMISSION FLOW ✅ + `setupHomeScreen` ✅ (headings,
      week/month/year stat cards, legend, readouts, example tags, dev-tools link, About & privacy;
      prefixes `perm_`/`unprotected_`/`lock_`/`step_`/`home_`). Only the `›`/`🔧` glyphs left literal.
      NOTE: `monthNames` (Jan…Dec) axis labels left as-is — do later via SimpleDateFormat locale.
      `setupMainScreen` = the DEV "Developer tools" menu → stays English (dev-only). DONE.
- [x] Report screen + block-management (`showReportScreen`, `showManageRules`) — prefixes
      `report_`/`manage_` + `common_remove`. ✅ compiles + validates. (Block-offer popups —
      showUnprotectedPopup etc. — still pending, grouped with setup/permission popups.)
- [x] App/site blocking screens (`appSiteChooseKind/Site/App/Tier/Saved`, `tierNote`,
      `saveSiteRule`, `showBlockApps`/`blockAppRow`) — prefixes `appsite_`/`blockapps_` + `common_done`.
      ✅ compiles + checkTranslations + no leftover literals
- [x] Mode rules + adult settings (`showModeRules`, `showAdultSettings`) — FULLY DONE.
      `showAdultSettings` migrated; `showModeRules` chrome migrated; mode NAMES app-wide via
      `modeDisplayName(id)`; per-mode rule bullets → `<string-array>` `mode_<id>_rules` via
      `modeRules(id)`; `ALWAYS_ON_RULES` → `always_on_01..13` strings via `alwaysOnRules()`
      (3 lines take live values). **AppConfig prose deleted** — ModeSpec is behaviour-flags only;
      strings.xml is now the single source (maintenance comment updated). Also fixed the stale
      "DuckDuckGo is deliberately left usable" line → "Only Firefox is left usable".
      Prefixes `adult_`/`moderules_`/`mode_`/`always_on_`. ✅ compiles + validates + unit tests pass.
- [x] Stats hub + ALL sub-screens — `showStatsMenu`, `showContextStats`, `showProgress`,
      `showTemptationStats`, `showRelapseStats`, `showLoosenStats` (+ helpers lightWord/statBar/
      statBigCard/emptyStat). prefix `stats_`/`light_`. ✅ compiles + validates.
      NOTE: DOW_ORDER / HOUR_LABELS / cal[] axis labels deferred (with monthNames → SimpleDateFormat).
- [x] About-you / life-inputs (`showAboutYou`, `showLifeInputs` [done w/ dopamine], `showAboutPage`)
      — prefix `about_`. ✅ compiles + validates.
- [x] Dopamine screens (`showDopamine`, `showDopamineRanks`, `showDopamineMaths` [tuning-generated],
      `showDopamineGuidance`, `adviceFor`, `showLifeInputs`) — prefix `dop_`. ✅ compiles + validates.
      DEFERRED: LifeInputs.HABITS content (config, like TemptationSpec).
- [x] Temptations (`showTemptationsTab`, `showTemptation`, `blockSwitch`, `habitRide`,
      `habitRideDone`, `habitSlip`) — prefix `temp_`. ✅ compiles + validates.
      DEFERRED: `AppConfig.TemptationSpec` content (title/subtitle/covers/insteadOf) — same
      pattern as mode specs, move to resources keyed by spec id in its own batch.
- [x] Protocol (`showProtocol`, `showProtocolReplace`, `showProtocolTips`, `showProtocolApps`,
      `showProtocolHoliday`, `showProtocol7Day`) — all content + buttons + toasts. prefix `proto_`.
      ✅ compiles + validates. Only glyphs (›/💡/●) left literal.
- [x] Usage goal / productivity / scroll cost (`showUsageGoal`, `showScrollCost`, `showProductivity`)
      — all labels/stats/cards/opportunity-cost lines. prefixes `usage_`/`scroll_`/`prod_` (reused
      stats_prog_*/home_* where shared). ✅ compiles + validates.
- [x] Loosen flow — DONE: core sequence + `loosenStop`, `loosenBlockedScreen`, `showBypassOffer`,
      `openPanic` (grounding + lock buttons + toasts), `lockPhoneNow`, `panicButton`. `onLookAnyway`
      is a no-string delegator. prefixes `loosen_`/`bypass_`/`panic_`. ✅ compiles + validates.
      DEFERRED: NEG/POS feeling word arrays (data, like Opts.FEELINGS).
- [x] Relapse report flow (`renderRelapseStep` steps, `noteStep`, `renderRelapseFeedback`) — prefix
      `relapse_`. Fixed the label-as-value comparisons to use the resource strings. ✅ compiles + validates.
      DEFERRED: ACTIVITIES/FEELINGS/DEFAULT_ROOMS picker data (like Opts.*). RelapseLog.analyze feedback lives in RelapseLog.kt.
- [x] Recent blocks / log page (`showRecentBlocks`, `showLogPage`) + bottom nav (Overview/
      Productivity/Temptations) + shared option pickers (`pickWithCustomScreen`/`pickMultiWithCustomScreen`/
      `promptCustom`/`addOwnRow`, "Select all that apply"/"Add your own"/"Type it"/"Add") + temptation
      groups screen chrome + dev-console TITLE. prefixes `recent_`/`log_`/`nav_`/`picker_`/`temp_groups_`.
      ✅ compiles + validates.
      NOTE: raw DIAGNOSTIC dumps left English (dev-only): `showDevConsole` rows, `showBanList`,
      `showEntryDetails`. TGroup enum content deferred (config).
- [x] Dev tools screens — DONE BY DESIGN: dev-only diagnostics stay ENGLISH (user confirmed).
      dev-console rows, ban list, entry details, sensor/room-beacon debug, dev-menu card labels =
      intentionally not translated. No further work.
- [x] "I feel temptation" ride-wave flow (`waveWalk/Move/Physical/Stuck/Peak/Success`, `waveBreatheScreen`,
      `waveActionScreen`, `attachWaveTimer`, `temptationUrgeScreen`) — prefix `ride_`. ✅
- [x] Misc sweep — grepped Main.kt: only 2 non-dev stragglers left ("No data yet.", "Drag across the
      graph...") + loosen countdown labels + urge-scale hi/lo. All migrated. `setupMainScreen` = the
      DEV "Developer tools" menu (stays English). **Main.kt user-facing strings essentially COMPLETE**
      (remaining literals are dev screens + deferred config data only).

### Phase 5 — sweep
- [ ] `grep` for remaining on-screen literals across all files; migrate stragglers.
- [ ] Final `./gradlew checkTranslations` + a manual pass of each screen.

---

## Log (append per batch: date · file/screen · key prefix · # keys)
- (started)
- 2026-07-20 · Overlay.kt (breathing gate) · `overlay_` · 4 keys · compiles + checkTranslations OK
- 2026-07-20 · Greyscale/UninstallGuard/Sensors · N/A (no on-screen text)
- 2026-07-20 · (note) AppConfig mode specs deferred — needs Context-resolution refactor
- 2026-07-20 · SIDE TASK started: data lists → assets/filter/ (see filter-data-files.md); domain lists done
- 2026-07-20 · data lists: apps_safe / apps_greylist / browsers_allowed / browsers_blocked → assets; AppConfig + Whitelist/AppBlocklist now getters. Next: word tiers → words/en/.
- 2026-07-20 · data lists: ALL word tiers/phrases/exceptions/families/medical → src/main/resources/filter/words/en/ (classpath, not assets, so the JVM scorer test still loads them). TextFilter objects now FilterData-backed. Unit tests PASS (behaviour unchanged).
- 2026-07-20 · i18n Main.kt batch 1: app/site blocking flow + Whitelisted-apps screen · `appsite_`/`blockapps_` · ~30 keys · compiles + validates
- 2026-07-20 · i18n Main.kt batch 2: Report screen + Manage blocks · `report_`/`manage_`/`common_remove` · ~13 keys · compiles + validates
- 2026-07-20 · i18n Main.kt batch 3: Adult settings + Mode rules chrome + mode names (modeDisplayName helper) · `adult_`/`moderules_`/`mode_` · ~28 keys · compiles + validates. Mode summaries + ALWAYS_ON_RULES deferred.
- 2026-07-20 · i18n Main.kt batch 5: permission/setup flow (popups + step prompts + lock prompt) · `perm_`/`unprotected_`/`lock_`/`step_`/`common_` · ~22 keys · compiles + validates
- 2026-07-20 · i18n Main.kt batch 6: home/overview page (setupHomeScreen) · `home_` · ~21 keys · compiles + validates. monthNames axis labels deferred.
- 2026-07-20 · i18n Main.kt batch 7: Statistics hub menu (showStatsMenu) · `stats_` · 6 keys · compiles + validates. Stats sub-screens still TODO.
- 2026-07-20 · i18n batch 8: AccessibilityService ALL block-cover reasons (`br_`, ~19 keys) + Views.kt breathe in/out. compiles + validates. Phase 2 (services) DONE.
- 2026-07-20 · i18n batch 9: ALL stats sub-screens (context/progress/temptation/relapse/loosen + helpers) · `stats_`/`light_` · ~55 keys · compiles + validates.
- 2026-07-20 · i18n batch 10: Temptations tab + shared category page + ride/slip flow · `temp_` · ~32 keys · compiles + validates. TemptationSpec content deferred.
- 2026-07-20 · i18n batch 11: ALL protocol screens (main + replace + tips + apps + holiday + 7day) · `proto_` · ~60 keys · compiles + validates.
- 2026-07-20 · i18n batch 12: usage goal + scroll cost + productivity page · `usage_`/`scroll_`/`prod_` · ~45 keys · compiles + validates.
- 2026-07-20 · i18n batch 13: ALL dopamine screens (main/ranks/maths/guidance/advice/lifeinputs) · `dop_` · ~140 keys · compiles + validates.
- 2026-07-20 · i18n batch 14: core loosen/supervised-unlock flow · `loosen_` · ~75 keys · compiles + validates. Panic/bypass/feeling-arrays deferred.
- 2026-07-20 · i18n batch 15: loosen remainder — stop/blocked/bypass-offer/panic screen · `loosen_`/`bypass_`/`panic_` · ~25 keys · compiles + validates. Loosen flow now complete (bar feeling arrays).
- 2026-07-20 · i18n batch 16: relapse report flow (`relapse_`) + about-you/about pages (`about_`) · ~26 keys · compiles + validates. Fixed relapse label-as-value comparisons.
- 2026-07-20 · i18n batch 17: recent blocks + log page + bottom nav + shared pickers + temptation-groups chrome + dev-console title · `recent_`/`log_`/`nav_`/`picker_`/`temp_groups_`/`dev_console_` · ~21 keys · compiles + validates. Dev diagnostic dumps left English.
- 2026-07-20 · i18n batch 18: "I feel temptation" ride-wave flow (walk/move/physical/stuck/peak/success + waveBreathe/waveAction/attachWaveTimer milestones) · `ride_`/`temp_urge_` · ~24 keys · compiles + validates. Dev diagnostics confirmed English-only by user.
- 2026-07-20 · i18n batch 18b: misc straggler sweep (No data yet / drag graph / loosen countdown / urge hi-lo). MAIN.KT USER-FACING STRINGS NOW COMPLETE (only dev screens + deferred config data remain).
- 2026-07-20 · i18n batch 19: RelapseLog.analyze generated feedback → resources (Context threaded through; `relapse_fb_*`/`relapse_enc_*`/`relapse_days`). Scanned all other .kt files: NONE build UI (0 sites). ALL non-Main files now DONE/N/A. Remaining: dev screens (English by design) + deferred config-data lists only.
- 2026-07-20 · data-files: GenderedTerms → words/en/gendered_*.txt (FilterData). ALL filter word lists now in files. Unit tests pass. Filter data-file extraction COMPLETE.
- 2026-07-20 · batch 25 (cleanup): night-guard/lockdown substrings → assets/filter/ (getters); removed dead DEFAULT_ROOMS; SHORT_FORM_PATTERNS/SEARCH_ENGINES left in code (rationale). compiles + validates + tests pass. ALL actionable remaining items closed.
- 2026-07-20 · batch 24: SimpleDateFormat/axis-label pass — DOW_ORDER/cal/monthNames/weekDays now from DateFormatSymbols(Locale.getDefault()) (DOW_ORDER + dowName + cal derive from the SAME locale so matching holds); display date formatters (weekdayFmt/niceDateFmt/niceDoyFmt) → Locale.getDefault(); numeric/storage formats stay Locale.US. compiles + validates.
  · LEFT BY DESIGN: HOUR_LABELS ("12a/6a/…") compact English am/p markers — minor, low value.
  · Currency £ NOT localized: the amounts are actual GBP (VALUE_PER_HOUR_GBP, "£13,000/year"),
    so £ is correct regardless of UI language — localizing the symbol would misrepresent the currency.
- 2026-07-20 · i18n batch 23: FeelingFaceView key/label split (stable values + localized displayLabels; feel_neg/feel_pos) + Views.kt chart annotations (chart_*). compiles + validates. Picker-data now fully done bar DEFAULT_ROOMS (dev).
- 2026-07-20 · i18n batch 22: PICKER-DATA key/label refactor — Choice(value=stable English key, label=localized); optLabel via opt_<cat> arrays; pickers store English + display localized; stats hBars localized; urge scale + examples; TGroup enum content (tgroup_*). No data migration. compiles + validates + tests pass. Deferred: NEG/POS face feelings + DEFAULT_ROOMS.
- 2026-07-20 · i18n batch 21: LifeInputs.HABITS → resources (`habit_<key>_label/_hint/_options`). SAFE because options are stored by INDEX not label. Dopamine.kt Habit/Option keep only key + credits; Main resolves labels/hints/options via habitLabel/habitHint/habitOption helpers. compiles + validates + unit tests pass.
- 2026-07-20 · i18n batch 20: config-data move — AppConfig.TemptationSpec display text (title/subtitle/covers/insteadOf) → `temptspec_<id>_*` resources + arrays, resolved via temptTitle/etc helpers (resources.getIdentifier by id). AppConfig keeps only id/blockPatterns/greyApps. ~35 strings + 7 arrays. compiles + validates.

## Picker-data key/label refactor — DONE (batch 22)
Solved the store-vs-display problem WITHOUT a data migration: the stored value stays the stable
ENGLISH string (backward-compatible; comparisons/icons/grouping unchanged), and a separate
localized LABEL is shown. Implemented: `Choice` is now a data class with `value` (stable key) +
`label` (display); `optLabel(category, value)` maps a stored English value → localized label via
index into `opt_<cat>` arrays (falls back to the value for custom entries). Pickers display
`choice.label` but `onPick` still returns `choice.value`. Stats hBars localize each grouped key
via `optLabel`. Urge scale + examples localized (`opt_urge`/`opt_urge_examples`). `TGroup` enum
(temptation-groups screen) content → `tgroup_<name>_*` (display-only, no stored-value risk).
✅ compiles + validates + unit tests pass.
NEG/POS face feelings — DONE (batch 23): FeelingFaceView now takes stable `labels` (values) +
`displayLabels` (localized); draws display, `nearestLabel()` returns the stable value. `feel_neg`/
`feel_pos` arrays. Also migrated the user-facing chart annotations in Views.kt (`chart_*`: past the
peak / tap where / more pull / free / one-off→back up / keep going→free / of your waking life).
STILL DEFERRED: DEFAULT_ROOMS (room-beacon setup, dev-only).
- 2026-07-20 · i18n Main.kt batch 4: mode CONTENT — per-mode rule string-arrays + always_on_01..13 (modeRules/alwaysOnRules helpers). AppConfig prose DELETED (ModeSpec = flags only). Stale DDG line fixed. compiles + validates + unit tests pass. Mode-rules screen fully done.
