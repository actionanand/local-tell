# Adding a New Language to LocalTell

This document explains how to add a new UI language to the LocalTell Android app. The example below uses **Kannada (`kn`)**. The same process applies to future languages such as Hindi, Malayalam, Telugu, Bengali, Gujarati, Odia, Punjabi, and others.

> **Scope:** LocalTell localization applies to **app-owned UI text only**. Geographic data, carrier data, codes, coordinates, and other canonical/runtime data must not be translated.

## 1. Current localization architecture

LocalTell currently uses Android string resources together with a small application-level language manager.

The important files are:

```text
app/src/main/res/values/strings.xml
app/src/main/res/values-ta/strings.xml
app/src/main/res/values-sa/strings.xml
app/src/main/res/xml/locales_config.xml

app/src/main/java/com/actionanand/localtell/app/AppLanguage.kt
app/src/main/java/com/actionanand/localtell/app/MainActivity.kt
```

The relevant responsibilities are:

| File | Responsibility |
|---|---|
| `values/strings.xml` | English/default source strings |
| `values-<language>/strings.xml` | Translated UI strings |
| `locales_config.xml` | Languages exposed to Android as supported app locales |
| `AppLanguage.kt` | App language enum, persisted preference, Android locale application |
| `MainActivity.kt` | Language choices shown in LocalTell Settings |

LocalTell supports Android 13+ per-app locales through `LocaleManager`. For Android 12 and below, LocalTell applies the persisted locale through `AppLanguageManager.localizedContext(...)` and recreates the activity after the user changes language.

## 2. Kannada example

Kannada uses the Android/BCP-47 language code:

```text
kn
```

Its Android resource directory is:

```text
app/src/main/res/values-kn/
```

The translation file will therefore be:

```text
app/src/main/res/values-kn/strings.xml
```

The self-name shown in the language picker should be:

```text
ಕನ್ನಡ
```

## 3. Add Kannada to `AppLanguage`

Open:

```text
app/src/main/java/com/actionanand/localtell/app/AppLanguage.kt
```

Add Kannada to the enum:

```kotlin
enum class AppLanguage(val preferenceValue: String, val languageTag: String?) {
    SYSTEM("system", null),
    TAMIL("ta", "ta"),
    ENGLISH("en", "en"),
    SANSKRIT("sa", "sa"),
    KANNADA("kn", "kn"),
    ;
}
```

Also update `fromLanguageTag(...)`:

```kotlin
fun fromLanguageTag(value: String?): AppLanguage = when (
    value?.substringBefore('-')?.lowercase()
) {
    "en" -> ENGLISH
    "ta" -> TAMIL
    "sa" -> SANSKRIT
    "kn" -> KANNADA
    else -> SYSTEM
}
```

`KANNADA("kn", "kn")` contains two intentionally similar values:

- `preferenceValue = "kn"` — stored by LocalTell for the app preference path.
- `languageTag = "kn"` — supplied to Android locale APIs.

Do not use the visible label `ಕನ್ನಡ` as the preference value or language tag.

## 4. Add Kannada to Android locale configuration

Open:

```text
app/src/main/res/xml/locales_config.xml
```

Add:

```xml
<locale android:name="kn" />
```

Example:

```xml
<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
    <locale android:name="ta" />
    <locale android:name="sa" />
    <locale android:name="kn" />
</locale-config>
```

`AndroidManifest.xml` already references this file using:

```xml
android:localeConfig="@xml/locales_config"
```

Normally no additional manifest change is required.

## 5. Add the Kannada language-name resource

The Settings screen resolves language names through string resources. Add a new source key to:

```text
app/src/main/res/values/strings.xml
```

```xml
<string name="language_kannada">ಕನ್ನಡ</string>
```

For resource parity, add the same key to every supported localized resource file:

```text
values/strings.xml
values-ta/strings.xml
values-sa/strings.xml
values-kn/strings.xml
```

Use the language's **self-name** in the picker:

```text
Kannada  → ಕನ್ನಡ
Tamil    → தமிழ்
Sanskrit → संस्कृतम्
English  → English
```

Do not translate a language self-name merely because another UI language is selected unless LocalTell intentionally changes this policy.

## 6. Add Kannada to the Settings language list

The LocalTell Settings screen currently uses an explicit list of supported languages. In `MainActivity.kt`, add `AppLanguage.KANNADA` to that list.

Conceptually:

```kotlin
items(
    listOf(
        AppLanguage.SYSTEM,
        AppLanguage.TAMIL,
        AppLanguage.ENGLISH,
        AppLanguage.SANSKRIT,
        AppLanguage.KANNADA,
    ),
) { language ->
    ...
}
```

The order is a product/UI decision. Do not accidentally reorder existing languages while adding a new one.

Also update `languageDisplayName(...)`:

```kotlin
@Composable
private fun languageDisplayName(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system_default)
    AppLanguage.TAMIL -> stringResource(R.string.language_tamil)
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
    AppLanguage.SANSKRIT -> stringResource(R.string.language_sanskrit)
    AppLanguage.KANNADA -> stringResource(R.string.language_kannada)
}
```

Because the `when` is exhaustive, adding the enum value without updating this function is an incomplete language implementation.

## 7. Create `values-kn/strings.xml`

Create:

```text
app/src/main/res/values-kn/strings.xml
```

Start from the **current** English/default file:

```text
app/src/main/res/values/strings.xml
```

Do not start from an old translated file. English/default is the source-key baseline.

Example skeleton:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="nav_home">ಮುಖಪುಟ</string>
    <string name="nav_easy">...</string>
    <string name="nav_more">...</string>

    ...

    <string name="language_kannada">ಕನ್ನಡ</string>
</resources>
```

The Kannada wording in this guide is structural only. Translation wording should be reviewed by a Kannada speaker before release.

## 8. What must be translated

Translate app-owned UI such as:

- navigation labels
- buttons
- headings
- dialogs
- errors
- descriptions
- Settings text
- Journey UI
- Offline Data UI
- Tower Survey UI
- permission explanations
- location-status messages
- signal-quality descriptions
- accessibility-facing resource strings

## 9. What must not be translated

Do **not** translate canonical or runtime data, including:

- locality names
- district names
- State names returned by the geographic database
- India region names coming from data
- offline pack names
- carrier/operator names
- PLMN/operator data
- Journey stored locality values
- coordinates
- latitude/longitude values
- LocalTell numeric codes
- LocalTell short codes
- cell/network identifiers

For example, if a pack is dynamically named `Karnataka`, do not mutate the data value itself. Translate only the UI around that value.

## 10. Preserve technical names and acronyms

Do not mechanically translate technical identifiers. Normally preserve terms such as:

```text
LocalTell
Android
Google Maps
Uber
Wi-Fi
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

The surrounding explanatory text can be translated.

Example source:

```text
GPS accuracy: %1$s
```

Translate `accuracy` appropriately, but keep `GPS` and `%1$s` intact.

## 11. Preserve format placeholders exactly

Android formatted strings are runtime contracts.

Common examples:

```text
%1$s
%2$s
%1$d
%2$d
%3$s
%%
```

A translation must preserve:

- placeholder index
- placeholder type
- required placeholder count

Example source:

```xml
<string name="easy_accuracy">Accuracy: ±%1$d m</string>
```

The translation must still contain `%1$d`. Do not accidentally change it to `%1$s`, `%d`, or `%2$d`.

Words may be reordered for Kannada grammar, but indexed placeholders must remain compatible.

Example:

```xml
<string name="offline_downloading_batch">
    Downloading %1$d of %2$d: %3$s
</string>
```

Kannada may reorder `%1$d`, `%2$d`, and `%3$s`, but all three must remain present with the same types.

## 12. Preserve escaped characters and XML rules

Pay attention to:

```text


'
&amp;
%%
```

### Newlines

Preserve intended `
` line breaks.

### Ampersand

A literal ampersand in XML content must be written as:

```xml
&amp;
```

### Percentage

Preserve `%%` when the source uses it for a literal percentage sign in a formatted string.

### Quotes/apostrophes

Keep Android/XML escaping compatible with the source resource style.

## 13. Do not hard-code localized strings in Kotlin/Compose

Correct Compose pattern:

```kotlin
Text(stringResource(R.string.more_settings))
```

Avoid:

```kotlin
Text("Settings")
```

Do not implement translation through Kotlin `when` branches containing visible UI text. Translation belongs in `res/values-<locale>/strings.xml`.

## 14. Use `stringResource(...)` inside Compose

Inside a `@Composable`, use:

```kotlin
stringResource(R.string.some_key)
```

Do not replace ordinary Compose resource access with:

```kotlin
LocalContext.current.getString(...)
```

LocalTell has lint expectations around Compose resource access, so new localization work should follow the existing `stringResource(...)` pattern.

## 15. Strings outside Compose

Services, notifications, or other non-Compose code may also need localized text.

For locale-sensitive lookup outside Compose, use the existing LocalTell locale mechanism, for example:

```kotlin
AppLanguageManager.getString(context, R.string.some_key)
```

or a context produced by:

```kotlin
AppLanguageManager.localizedContext(context)
```

This is particularly important for Android 12 and below.

Do not introduce a second independent locale-management mechanism.

## 16. Android-version behavior

### Android 13+ / API 33+

`AppLanguageManager.apply(...)` uses Android's `LocaleManager.applicationLocales`. The new language must therefore exist in `locales_config.xml`.

### Android 12 and below / API 26–32

LocalTell stores the selected language and applies it through `AppLanguageManager.localizedContext(...)`. `MainActivity.attachBaseContext(...)` uses that localized context, and the activity is recreated after a manual language change.

Test both paths if possible.

## 17. System Default behavior

`AppLanguage.SYSTEM` is not a real language locale. It is deliberately represented by:

```kotlin
SYSTEM("system", null)
```

When selected, the device/system locale determines which supported Android resource is used.

After Kannada is supported, a phone using Kannada can resolve `values-kn` when LocalTell is on **System Default**.

Do not create a fake locale tag for `SYSTEM`.

## 18. Adding new source strings later

After Kannada support is introduced, every new translatable English resource must be reviewed for every supported language.

Example:

```xml
<string name="new_feature_title">New feature</string>
```

Before release, review/add the same key in:

```text
values-ta/strings.xml
values-sa/strings.xml
values-kn/strings.xml
```

Android can fall back to English for missing translations, which can hide localization gaps during development. For LocalTell, resource parity should be treated as a release-quality requirement.

## 19. Translation quality requirements

Valid XML is not enough. Review each translation for:

- natural wording
- grammar
- singular/plural forms
- context
- consistent terminology
- technical meaning
- unnecessary English transliteration
- awkward machine-generated wording
- ambiguous terms

Prefer terminology consistency over translating the same concept differently on different screens.

If a technical term has no clear native-language equivalent, keeping the accepted technical term may be better than inventing an unclear translation.

## 20. Maintain a terminology glossary

For every new language, keep an approved terminology list for important product concepts.

Example:

```markdown
| English | Kannada |
|---|---|
| Theme | ... |
| Signal strength | ... |
| Tower Survey | ... |
| Offline Data | ... |
| Easy number | ... |
| State / Union Territory | ... |
| Mobile data | ... |
```

Confirm uncertain terms with a fluent speaker before propagating them throughout the app.

## 21. Text length and responsive UI

Translations can be significantly longer than English. After adding Kannada, check at least:

- bottom navigation
- Settings cards
- language picker
- dialogs
- Offline Data region actions
- Download / Remove actions
- Journey controls
- Tower Survey controls
- filter chips
- error/info cards

Do not solve overflow by incorrectly shortening the translation. Prefer responsive Compose layout behavior where required.

## 22. Font and script rendering

Prefer Android/system fonts unless there is a demonstrated rendering problem.

For Kannada, verify:

- Kannada glyph coverage
- combining marks and vowel signs
- line height
- clipping
- bold rendering
- button labels
- notification text

Do not add custom font files merely because a new language is being introduced.

## 23. Text-to-Speech considerations

The Easy screen includes read-aloud functionality. Adding Kannada UI resources does **not** guarantee that every device has Kannada TTS support.

If language-dependent prose is spoken in the future:

- detect TTS language support
- retain unavailable/error handling
- do not make general Kannada UI support depend on TTS availability

## 24. Accessibility

Translated resource review should include strings used by accessibility-facing controls, not only visible headings and buttons.

Check resources used for:

- icons
- search actions
- clear actions
- navigation
- dialogs
- status text

If an icon intentionally uses `contentDescription = null` because nearby text already supplies the semantic label, do not add a new description only for localization.

## 25. Kannada testing checklist

Test at least:

- System Default
- English
- Tamil
- Sanskrit
- Kannada
- English → Kannada
- Kannada → Tamil
- Kannada → System Default
- app restart after Kannada selection
- Android 13+ per-app locale behavior
- Android 12 or lower recreation behavior, if available
- Home
- Easy
- More
- Settings
- Offline Data
- Journey
- Tower Survey when enabled
- dialogs
- notifications where practical

Also test a device whose system language is Kannada while LocalTell is set to **System Default**.

## 26. Resource validation

Before committing a language addition, validate all resource sets.

At minimum:

1. English and Kannada contain the same required string keys.
2. No duplicate string names exist.
3. No accidental extra/misspelled keys exist.
4. Format placeholders match.
5. XML is valid.
6. Kannada does not contain accidental untranslated English UI text.
7. Approved technical/brand terms remain unchanged.
8. `git diff --check` passes.

Once Kannada is added, compare all supported sets together:

```text
EN
TA
SA
KN
```

## 27. Useful manual checks

Search for suspicious untranslated English remnants in Kannada:

```bash
grep -nE 'Easy|Theme|State|UT|Signal|Cellular|Download|Remove|Settings'   app/src/main/res/values-kn/strings.xml
```

Review every hit. Some Latin-script terms may be intentionally preserved technical names or brands, so do not blindly replace them.

Then inspect repository changes:

```bash
git status --short
git diff --check
git --no-pager diff
```

A successful build alone does not prove translation quality.

## 28. Recommended automated parity check

A small validation script can compare `<string name="...">` keys across:

```text
values/strings.xml
values-ta/strings.xml
values-sa/strings.xml
values-kn/strings.xml
```

Desired result:

```text
Missing Kannada keys: 0
Extra Kannada keys: 0
Duplicate Kannada keys: 0
Placeholder mismatches: 0
```

Do not permanently hard-code an old string count because the resource set will grow as LocalTell evolves. Always treat the **current English/default resource file** as the key baseline.

## 29. Files normally expected to change

A normal Kannada addition should primarily change/create:

```text
app/src/main/java/com/actionanand/localtell/app/AppLanguage.kt
app/src/main/java/com/actionanand/localtell/app/MainActivity.kt
app/src/main/res/xml/locales_config.xml
app/src/main/res/values/strings.xml
app/src/main/res/values-ta/strings.xml
app/src/main/res/values-sa/strings.xml
app/src/main/res/values-kn/strings.xml
```

Existing language files may change because `language_kannada` is added for resource parity.

Business logic should not change merely to add a UI language.

## 30. Files that normally should not change

Adding Kannada alone should not require modifications to:

```text
android-version.json
location/GNSS logic
offline database schema
geographic pack data
pack manifest data
Easy-code encoding
Journey data models
Tower Survey collection logic
cellular identifiers
map/ride coordinates
```

If a language-only change touches those areas, review the scope carefully.

## 31. Recommended implementation sequence

1. Confirm the locale code (`kn`).
2. Confirm important Kannada terminology with a fluent speaker.
3. Add `KANNADA` to `AppLanguage`.
4. Add `"kn" -> KANNADA` to `fromLanguageTag(...)`.
5. Add `<locale android:name="kn" />` to `locales_config.xml`.
6. Add `language_kannada` to resource sets.
7. Add Kannada to the Settings language list.
8. Add Kannada to `languageDisplayName(...)`.
9. Create `values-kn/strings.xml` from the latest English keys.
10. Translate app-owned UI only.
11. Verify placeholders and escaping.
12. Verify canonical/runtime data remains untranslated.
13. Validate key parity.
14. Review UI for overflow and wrapping.
15. Test switching and persistence.
16. Run repository checks.
17. Obtain human language review.
18. Commit the localization change.

## 32. Kannada implementation summary

### `AppLanguage.kt`

```kotlin
KANNADA("kn", "kn")
```

and:

```kotlin
"kn" -> KANNADA
```

### `locales_config.xml`

```xml
<locale android:name="kn" />
```

### Language-name resource

```xml
<string name="language_kannada">ಕನ್ನಡ</string>
```

### `MainActivity.kt`

Add:

```kotlin
AppLanguage.KANNADA
```

to the Settings language list and:

```kotlin
AppLanguage.KANNADA -> stringResource(R.string.language_kannada)
```

to `languageDisplayName(...)`.

### Kannada resources

Create:

```text
app/src/main/res/values-kn/strings.xml
```

with the complete translated UI resource set.

## 33. Final review checklist

- [ ] Correct BCP-47/Android language tag selected
- [ ] New `AppLanguage` enum entry added
- [ ] `fromLanguageTag(...)` updated
- [ ] `locales_config.xml` updated
- [ ] Language self-name resource added
- [ ] Settings language list updated
- [ ] `languageDisplayName(...)` updated
- [ ] `values-<locale>/strings.xml` created
- [ ] All current English keys reviewed
- [ ] No missing translation keys
- [ ] No duplicate keys
- [ ] No placeholder mismatches
- [ ] XML escaping verified
- [ ] `\n` and `%%` preserved
- [ ] Dynamic geographic data not translated
- [ ] Carrier/operator names not translated
- [ ] Technical acronyms reviewed instead of blindly translated
- [ ] Compose uses `stringResource(...)`
- [ ] Non-Compose locale-sensitive strings use the LocalTell locale mechanism
- [ ] Language switching tested
- [ ] App restart persistence tested
- [ ] System Default tested
- [ ] Long-text/responsive UI tested
- [ ] Human translation review completed
- [ ] `git diff --check` passes
- [ ] Only intended localization files changed

## Principle to remember

> **A LocalTell language pack translates the application interface, not the underlying location/network data.**

Keep resource keys synchronized, preserve runtime formatting contracts, use one approved terminology set per language, and review translations in the actual UI before release.
