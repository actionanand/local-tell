# LocalTell Agent Guide

This file defines the working rules for AI coding agents contributing to **LocalTell**.

## Project overview

LocalTell is a native Android application written in **Kotlin + Jetpack Compose**.

Current package:

```text
com.actionanand.localtell.app
```

Primary goals:

- resolve the user's current locality using offline State/UT geographic packs;
- work primarily offline after the required pack is installed;
- show useful cellular diagnostics for the active SIMs;
- provide an Easy location-sharing/code workflow;
- optionally track locality changes in Journey Mode;
- optionally collect Tower Survey observations when that feature flag is enabled.

Do not introduce a backend unless explicitly requested.

## Source of truth

Prefer, in this order:

1. current source code and tests;
2. `app-config.json`, `android-sdk.properties`, and `android-version.json`;
3. current GitHub workflow;
4. README/documentation.

Some older documentation may describe previous architecture. If documentation conflicts with the current implementation, do not silently rewrite working code to match old documentation.

## Current Android stack

- Kotlin
- Jetpack Compose / Material 3
- Android min SDK 26
- compile SDK 37
- target SDK 36
- Java/JVM 17
- AGP 9.3.1
- Kotlin / Compose compiler 2.4.20
- Gradle 9.5.0 in CI
- Google Play Services Location 21.3.0
- Android SQLite
- WorkManager
- Foreground services for Journey / Tower Survey

There is intentionally no committed Gradle wrapper. Do not install or add one unless explicitly requested.

## Important branches and CI

Primary Android development branch:

```text
main-android
```

Android GitHub Actions runs only for `main-android` or manual dispatch from that branch.

Validation performed by CI:

```bash
gradle lint testDebugUnitTest
```

The workflow then:

- increments `versionCode`;
- builds APK + AAB;
- signs when secrets are available;
- commits release artifacts under `releases/`.

Do not assume CI will run on feature branches.

Never force-push or rewrite branch history unless explicitly requested.

## Versioning

`android-version.json` is intentionally managed separately.

Rules:

- do not modify `android-version.json` unless the user explicitly asks for a version change;
- CI on `main-android` automatically increments `versionCode`;
- `versionName` is manually controlled;
- do not revert an intentional local/user version change just because it appears in a diff.

## Configuration

`app-config.json` is the main runtime/build configuration source.

Current important fields include:

```json
{
  "applicationId": "com.actionanand.localtell.app",
  "appName": "LocalTell",
  "releaseBaseName": "LocalTell",
  "dataManifestUrl": "https://github.com/actionanand/localtell-data/releases/latest/download/manifest.json",
  "enableTowerSurvey": false
}
```

Do not hardcode replacements for these values in Kotlin when an existing config field should be used.

## Main application structure

Important areas:

```text
app/src/main/java/com/actionanand/localtell/app/
├── MainActivity.kt
├── HomeViewModel.kt
├── PacksViewModel.kt
├── JourneyViewModel.kt
├── AppLanguage.kt
├── LocalTellApplication.kt
├── LocationEnablement.kt
├── data/
├── easy/
├── external/
├── journey/
├── location/
├── locationcode/
├── model/
├── survey/
├── telephony/
├── ui/
└── update/
```

Keep feature changes localized to the relevant layer. Avoid broad unrelated refactors.

## Navigation

Root tabs:

```text
HOME
EASY
MORE
```

More contains feature destinations such as:

- Journey
- Offline Data
- Tower Survey when enabled
- Settings

Settings is the central place for application preferences.

## Settings behavior

Settings currently owns:

- open Easy tab by default;
- remind user to turn off Android Location;
- theme;
- language.

Preference file:

```text
localtell_preferences
```

Important preferences:

```text
default_root_tab
remind_location_turn_off
theme_mode
app_language
```

### Default tab

Fresh installs / missing preference default to:

```text
EASY
```

Explicit stored values must remain respected:

```text
EASY
HOME
```

Do not silently overwrite an existing explicit `HOME` preference.

### Location turn-off reminder

Default:

```text
true
```

The reminder is shown only when LocalTell itself caused Android Location to transition from OFF to ON.

If Android Location was already ON before LocalTell used it, do not remind.

Turning the reminder setting OFF must clear pending reminder eligibility.

Turning it back ON while Location is already ON must not retroactively create eligibility.

## Location architecture

Primary acquisition is GNSS/GPS.

Important behavior:

- precise: approximately `<= 50 m`;
- approximate candidate: `> 50 m` and `<= 250 m`;
- weak approximate fixes may be retained while waiting for a better precise fix;
- assisted location is used only after explicit user choice;
- locality resolution remains offline after coordinates are obtained;
- do not add cellular-coordinate estimation as a fallback.

Do not change these thresholds or consent behavior unless explicitly requested.

Relevant files:

```text
location/OneShotGnssLocator.kt
location/AssistedLocationLocator.kt
location/LocationQuality.kt
location/GnssFixEligibility.kt
```

## Location reminder flow

Home, Easy, and Journey share the same centralized one-shot reminder eligibility in `MainActivity`.

Do not duplicate reminder-state logic independently in each screen.

## Offline geographic packs

Offline locality lookup uses State/UT packs distributed from:

```text
actionanand/localtell-data
```

Current geographic resolver:

```text
data/OfflineLocalityResolver.kt
```

Resolution order is intentionally:

```text
inside State/UT boundary
    ↓
locality polygon match
    ↓
nearest-place fallback
```

`sourceQuality` values such as:

```text
polygon
nearest-place
```

are resolver metadata, not pack names.

If presenting these to users, prefer friendly UI labels while preserving the internal values.

### Data boundary

Never translate, rename, or remap canonical geographic data for UI localization.

Keep unchanged:

- `India`;
- region names;
- State/UT names;
- `pack.name`;
- locality names;
- district/sub-district/state values;
- `manifestKey`;
- `scopeKey`;
- pack IDs;
- search source data.

Localization must stop at the UI boundary.

## Offline pack actions

Preserve:

- SHA-256 verification;
- database/schema validation;
- atomic activation;
- update safety;
- individual cancellation;
- region/India batch cancellation;
- region removal;
- State/UT removal.

Cancellation of an update must preserve the previously installed valid database.

Do not weaken these guarantees for UI changes.

## Cellular diagnostics

Cellular reading is implemented through Android telephony APIs.

Relevant file:

```text
telephony/CellReader.kt
```

Do not add new telephony permissions unless required and explicitly justified.

### SIM selector UI

Keep filter chips compact:

```text
All | SIM1 | SIM2
```

Localized equivalent for `All` is allowed.

Do not include carrier names in the filter chips.

Detailed cards may show:

```text
SIM1 · Jio True5G
SIM2 · airtel
```

Carrier names are dynamic data and must not be translated.

### Signal strength

Prefer:

```text
RSRP / SS-RSRP
```

with fallback to:

```text
dBm
```

Current visual thresholds:

```text
>= -80 dBm       Excellent
-81 .. -90       Good
-91 .. -100      Fair
-101 .. -110     Weak
< -110           Very weak
```

Progress normalization:

```text
((signalDbm + 120f) / 40f).coerceIn(0f, 1f)
```

Keep raw cellular engineering values visible alongside the visual indicator.

Do not combine RSRQ, SINR/RSSNR, or timing advance into the signal-strength bar.

## Easy feature

Easy supports:

- current coordinates;
- accuracy;
- Easy numeric code;
- short code;
- resolving entered code/coordinates;
- copy/share;
- Google Maps;
- Uber.

Do not alter code encoding/decoding formats without updating tests and preserving backward compatibility.

Relevant files:

```text
easy/EasyViewModel.kt
locationcode/LocalTellLocationCode.kt
locationcode/LocationInputParser.kt
external/LocationLinks.kt
```

## Uber integration

Uber is the only ride integration.

The user chooses whether the LocalTell location is:

- Pickup; or
- Destination.

Do not reintroduce Ola or Rapido unless explicitly requested.

Do not add Uber client credentials/product IDs unless explicitly requested.

## Journey Mode

Relevant files:

```text
JourneyViewModel.kt
journey/JourneyForegroundService.kt
journey/JourneyDbHelper.kt
journey/JourneyTrackingState.kt
```

Journey records an entry only when the resolved locality changes.

Preserve:

- foreground-service behavior;
- session/history persistence;
- one-shot location reminder behavior;
- dynamic locality values.

Do not translate stored locality/history data.

## Tower Survey

Feature flag:

```text
enableTowerSurvey
```

The feature is conditional and may be disabled in production configuration.

Relevant files:

```text
survey/TowerSurveyScreen.kt
survey/TowerSurveyForegroundService.kt
survey/TowerSurveyDbHelper.kt
survey/RadioBandResolver.kt
```

Do not modify Tower Survey DB/schema or observation semantics for unrelated UI work.

## Localization

Currently supported explicit languages:

```text
English   en
Tamil     ta
Sanskrit  sa
```

System default is also supported.

Resource directories:

```text
res/values/
res/values-ta/
res/values-sa/
```

Locale config:

```text
res/xml/locales_config.xml
```

Language model:

```text
AppLanguage.kt
```

### Localization rules

All app-owned UI must remain resource-backed.

Never translate:

- geographic/database data;
- carrier names;
- locality names;
- pack names;
- IDs;
- coordinates;
- Easy/Short codes;
- technical measurements.

Keep technical acronyms unchanged where appropriate:

```text
GPS
GNSS
SIM
LTE
NR
MCC
MNC
PLMN
TAC
LAC
NCI
PCI
EARFCN
NRARFCN
RSRP
RSRQ
RSSNR
SINR
dBm
dB
CSV
```

Brands also remain unchanged:

```text
LocalTell
Google Maps
Uber
```

### Compose resource access

Inside `@Composable` code, use:

```kotlin
stringResource(R.string.some_key)
```

Do not query UI resource values through:

```kotlin
LocalContext.current.getString(...)
```

inside composition.

This causes `LocalContextGetResourceValueCall` lint failures and can return stale strings after configuration/language changes.

Resolve strings during composition and capture the resulting `String` inside callbacks.

### Non-Compose resource access

ViewModels and foreground services should use the locale-aware helper:

```kotlin
AppLanguageManager.getString(...)
```

especially for API 26-32, where running services/application contexts can otherwise retain stale resources after an in-app language change.

### Resource parity

Whenever adding/changing UI strings, keep all languages aligned:

```text
English
Tamil
Sanskrit
```

Verify:

- no duplicate resource names;
- no missing keys;
- no extra keys;
- format-placeholder parity;
- `%s` / `%d` types remain compatible.

### Sanskrit

Use proper Sanskrit in Devanagari.

Language self-name:

```text
संस्कृतम्
```

Be careful with:

- visarga `ः`;
- anusvāra `ं`;
- virāma `्`;
- vowel length;
- case endings;
- number/gender agreement;
- imperative forms;
- Sanskrit vocabulary rather than Hindi written in Devanagari.

Do not add visarga mechanically; grammar determines its use.

Keep brands, technical acronyms, and canonical data unchanged.

## Theme

Theme modes:

```text
SYSTEM
LIGHT
DARK
```

Settings owns theme selection.

Reuse the existing `theme_mode` preference.

Do not reintroduce the previous Home-header theme-cycle control unless explicitly requested.

## Compose UI guidance

Use Material 3 and existing project patterns.

Prefer:

- `LazyColumn` with `contentPadding`;
- resource-backed labels;
- accessible full-row click targets where appropriate;
- theme colors from `MaterialTheme.colorScheme`;
- restrained visual hierarchy.

Avoid:

- hardcoded colors unless required by semantic visualization;
- new dependencies for simple UI;
- decorative `contentDescription` values;
- oversized nested cards;
- UI strings embedded directly in Kotlin.

The app already uses 32dp bottom list content padding so final items remain visible above bottom navigation. Preserve this behavior.

## Tests

Current JVM tests include:

```text
AppLanguageTest
LocalityArchitectureTest
PackCatalogTest
LocationLinksTest
JourneyTrackingTest
GnssFixEligibilityTest
LocationQualityTest
LocalTellLocationCodeTest
LocationInputParserTest
RadioBandResolverTest
SignalVisualTest
```

Add/update pure JVM tests when changing deterministic logic.

Avoid Android/instrumentation tests unless the behavior truly requires Android runtime APIs.

## Validation before finishing a coding task

Always run:

```bash
git diff --check
git status
```

If Gradle 9.5.0 and the Android SDK are already available:

```bash
gradle lint testDebugUnitTest
```

Do not install a random system Gradle version merely to run validation.

Do not add lint suppressions/baselines just to make CI pass unless explicitly approved.

For Compose lint failures, fix the underlying configuration-aware code.

## Git discipline

Before editing:

```bash
git status
git branch --show-current
```

Do not commit or push unless explicitly requested.

When new files are present, do not use only:

```bash
git commit -am
```

because untracked files will be skipped.

Prefer explicit staging.

Do not overwrite unrelated user changes.

Do not reset `android-version.json` just because it differs.

## Scope discipline

When asked for a focused fix:

- inspect the current implementation first;
- change the minimum required files;
- preserve unrelated behavior;
- do not broad-refactor architecture;
- do not change data semantics;
- do not modify permissions, CI, or versioning unless required by the task.

At the end, report:

- files changed;
- behavior changed;
- behavior intentionally preserved;
- validation run;
- anything that could not be validated locally.

## Privacy and product intent

LocalTell is designed to keep locality resolution private and offline wherever possible.

Do not add:

- analytics;
- location upload;
- remote geocoding;
- silent background network location lookup;
- storage/upload of precise coordinates;

unless explicitly requested and the privacy implications are clearly explained.

The app may use network access for explicit supported workflows such as offline-pack downloads and user-approved assisted location, but locality resolution itself should remain offline.
