# Home and Easy announcements

LocalTell can display one compact remote announcement on the Home and Easy screens before a successful place result is available. Announcements are optional and best-effort: loading or parsing failures never block location features and do not produce user-facing errors.

## Remote source

The URL is configured by `homeAnnouncementUrl` in `app-config.json` and exposed to the Android app through `BuildConfig.HOME_ANNOUNCEMENT_URL`.

Current publishing location:

```text
https://raw.githubusercontent.com/actionanand/localtell-data/master/announcements/home.json
```

Publish the file in `actionanand/localtell-data` at:

```text
announcements/home.json
```

The request is an identical static HTTPS GET for every user. It contains no location, locality, cell, carrier, SIM, Easy code, user, or device information. There are no impressions, read receipts, analytics, or click tracking.

## Schema version 1

```json
{
  "schemaVersion": 1,
  "announcements": [
    {
      "id": "sri-lanka-support-2026-10",
      "enabled": true,
      "priority": 100,
      "publishedAt": "2026-10-15T00:00:00Z",
      "expiresAt": "2027-01-15T00:00:00Z",
      "minVersionCode": 31,
      "title": {
        "en": "Sri Lanka is now supported",
        "ta": "இலங்கை ஆதரவு இப்போது கிடைக்கிறது",
        "sa": "श्रीलङ्कायाः समर्थनम् अधुना उपलब्धम्"
      },
      "message": {
        "en": "Offline locality data is now available for all 9 Sri Lankan Provinces.",
        "ta": "இலங்கையின் 9 மாகாணங்களுக்கும் இணையமில்லா பகுதி தரவு இப்போது கிடைக்கிறது.",
        "sa": "श्रीलङ्कायाः सर्वेषां नवप्रान्तानां कृते अन्तर्जालरहित-स्थानीयस्थानदत्तांशः अधुना उपलब्धः अस्ति।"
      },
      "action": "offline_data"
    }
  ]
}
```

### Document fields

- `schemaVersion`: must be `1`.
- `announcements`: array of announcement objects. The app displays at most one eligible announcement.

### Announcement fields

- `id`: required, unique, stable identifier containing letters, digits, `.`, `_`, or `-`, up to 100 characters. Use a new ID for a new announcement.
- `enabled`: required boolean. Set to `false` to disable an announcement.
- `priority`: optional integer, default `0`. Higher values win.
- `publishedAt`: optional ISO-8601 instant. The announcement is hidden before this instant.
- `expiresAt`: optional ISO-8601 instant. The announcement is hidden at and after this instant.
- `minVersionCode`: optional nonnegative inclusive minimum.
- `maxVersionCode`: optional nonnegative inclusive maximum.
- `title`: localized plain-text map. A nonblank `en` value is required; `ta` and `sa` are optional.
- `message`: localized plain-text map with the same language rules as `title`.
- `action`: optional whitelisted action. Unknown values are treated as `none`.

Supported language keys:

- `en`: English and required fallback.
- `ta`: Tamil.
- `sa`: Sanskrit.

Supported actions:

- `none`: no action button.
- `offline_data`: shows **View Offline Data** and navigates inside the app to More → Offline Data.

Remote content is always rendered as plain text. HTML, arbitrary URLs, deep links, and executable actions are not supported.

## Selection

The app removes disabled, not-yet-published, expired, version-ineligible, and dismissed announcements. It then selects:

1. highest priority;
2. newest `publishedAt`;
3. lexicographically smallest ID as a deterministic final tie-breaker.

If the selected announcement was dismissed, the next eligible announcement is considered.

## Publishing a new announcement

1. Add an object with a new stable `id`.
2. Provide valid English title and message text.
3. Add reviewed Tamil and Sanskrit text when available; missing translations fall back to English.
4. Choose only a supported action.
5. Set publication, expiry, priority, and version bounds as needed.
6. Validate the JSON and ensure IDs are unique.
7. Publish it to `announcements/home.json` on the `master` branch of `actionanand/localtell-data`.

To stop an announcement, set `enabled` to `false`, remove it, or set an expired `expiresAt`. Reusing an old ID means users who dismissed that ID will continue not to see it.

## Dismissal, session hiding, and cache behavior

- Pressing the close button stores only the announcement ID in the `localtell_announcements` preferences and permanently hides that ID on both Home and Easy.
- Obtaining a successful Home locality or Easy result suppresses announcements only for the current app process. It is not persisted.
- The last valid JSON is cached. A malformed response or network failure leaves the valid cache intact.
- With no valid cache and no successful response, no card appears.
- A valid remote document with no eligible announcement clears the currently selected announcement.
- The app performs one remote refresh per root ViewModel/app process and does not use WorkManager for announcements.
