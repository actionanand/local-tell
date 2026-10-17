# Home and Easy announcements

Announcements are optional plain-text information. They never block GPS, locality lookup, navigation, or pack downloads.

## Visibility and placement

One root-scoped HomeAnnouncementViewModel owns Home and Easy announcement selection. Home displays a card after its pre-result refresh control (when present). Easy displays it immediately after the Find Location input/button section. Hidden cards leave no list item or spacer.

Any HomeStatus.Ready, including a completed result without a locality match, suppresses all announcements. Either Easy currentLocation or resolvedLocation does the same. The root observes both result streams across tabs. A synchronous render guard hides notices immediately; the retained session latch prevents subsequent refreshes, clearing results, higher priorities, or new revisions from restoring them. Existing results are respected on resume. No result suppression is persisted to disk.

A new Activity/ViewModel session after a normal new launch permits announcements again. Configuration changes retain the root ViewModel, including dismissals and result suppression. Background/foreground transitions refresh eligibility but deliberately do not reset dismissals or result suppression.

## Source, privacy, and refresh

The authoritative endpoint is configured by homeAnnouncementUrl in app-config.json, exposed as BuildConfig.HOME_ANNOUNCEMENT_URL:

https://raw.githubusercontent.com/actionanand/localtell-data/master/announcements/home.json

All clients request the same static HTTPS resource. Requests contain no coordinates, locality, SIM/carrier/cell information, location codes, or personal/device identifiers. There is no tracking, impression reporting, read receipt, targeting, or analytics.

The foreground lifecycle loads the last validated cache on an IO dispatcher, then requests fresh JSON asynchronously. Returning to the foreground refreshes with a one-minute minimum interval. Before results load, a five-minute refresh loop operates while STARTED; stopping the Activity cancels it. Once results load, network refreshes stop for that session. Local eligibility is reevaluated every 30 seconds for expiry and publication scheduling.

Requests use Cache-Control: no-cache, conditional ETag/Last-Modified headers when available, no redirects, ten-second connection/read timeouts, and a 64 KiB response limit. A 304 uses the validated cache. Malformed responses do not overwrite the document or its validators. Offline/network errors stay silent and preserve cached content. Cached documents still obey enabled, scheduling, version, repeat, and result rules.

Static GitHub/CDN delivery is near-real-time best effort, not instantaneous push: polling intervals and CDN propagation may delay publication. Immediate push would require FCM/backend infrastructure; this client does not use it. There are no announcement WorkManager jobs.

## Backward-compatible schema 1 contract

The root contains schemaVersion (exactly 1) and announcements (an array). At most one eligible notice is displayed.

| Field | Meaning/default |
| --- | --- |
| id | Required unique stable ID, 1–100 letters/digits/dot/underscore/hyphen |
| enabled | Required boolean; false disables the notice |
| type | general (default), feature, maintenance, downtime, migration, critical |
| revision | Positive integer, default 1 |
| repeatPolicy | every_launch (default) or once_per_revision |
| priority | Integer, default 0; range -1000000 to 1000000; highest wins |
| publishedAt | Optional ISO-8601 instant; hidden before this time |
| expiresAt | Optional ISO-8601 instant; hidden at/after this time; must follow publishedAt |
| minVersionCode / maxVersionCode | Optional nonnegative inclusive version bounds |
| title | Required localized plain-text map; nonblank en required, max 120 characters per language |
| message | Required localized plain-text map; nonblank en required, max 600 characters per language |
| action | none (default), offline_data, open_play_store |
| actionUrl | Optional validated HTTPS Google Play listing for open_play_store |

Supported text keys are en, ta, sa. Missing translations fall back to English. Unknown types/repeat policies use general/every_launch; unknown or unsafe actions become none. Remote HTML is not interpreted. The old Sri Lanka schema-1 document remains valid with the new defaults.

General, feature, maintenance, downtime, migration, and critical notices have localized labels and decorative icons. Downtime/critical use Material error-container colors; other badges use secondary-container colors. Badges do not animate continuously.

## Repeat, dismissal, and reannouncement

every_launch uses in-memory dismissal by ID and maximum dismissed revision. Closing revision 2 hides revision 2 and older for the retained session, including repeated polling and tab changes. A new launch may display it again.

once_per_revision additionally persists the maximum dismissed revision by ID. A newer revision becomes eligible; an older revision cannot lower that watermark. Presentation without pressing close is not recorded permanently. Dismissal considers the next eligible ID immediately.

Legacy dismissed_ids preferences migrate to revision-1 watermarks and are removed. They affect once_per_revision only and cannot permanently suppress every_launch notices. No every-launch dismissal is written permanently.

Selection excludes disabled, future, expired, version-ineligible and dismissed notices, then sorts by descending priority, descending publication time, and ID. A new ID or increased revision can appear during foreground refresh before results load. Repeated identical JSON cannot re-show a dismissed revision.

There is no independent force switch: publish a higher revision or a new ID to reannounce. No remote setting, critical type, or priority can override results-loaded suppression.

## Safe actions

none renders no button. offline_data opens More → Offline Data.

open_play_store requires actionUrl exactly shaped as:
https://play.google.com/store/apps/details?id=com.example.application

The client accepts only HTTPS, exact play.google.com authority, /store/apps/details, one id query with a valid dotted package identifier, and no fragment, user information, port, extra parameters, or custom schemes. Invalid destinations disable the action. The client tries the Google Play app with the validated HTTPS listing, then a browser. If neither exists it leaves the app usable. No arbitrary remote intents or executable content are accepted.

## Complete example for localtell-data

This is example content, not an instruction to publish a real outage or migration. Review translations and real product links before publication.

```json
{
  "schemaVersion": 1,
  "announcements": [
    {
      "id": "planned-maintenance-example",
      "enabled": true,
      "type": "downtime",
      "revision": 2,
      "repeatPolicy": "every_launch",
      "priority": 300,
      "publishedAt": "2026-10-20T00:00:00Z",
      "expiresAt": "2026-10-21T00:00:00Z",
      "title": {
        "en": "Pack downloads may be unavailable",
        "ta": "தொகுப்புப் பதிவிறக்கங்கள் கிடைக்காமல் போகலாம்",
        "sa": "सञ्चयावरोहणम् अनुपलब्धं भवेत्"
      },
      "message": {
        "en": "Planned maintenance may affect downloads. Installed offline packs remain usable.",
        "ta": "திட்டமிட்ட பராமரிப்பால் பதிவிறக்கங்கள் பாதிக்கப்படலாம். நிறுவப்பட்ட இணையமில்லா தொகுப்புகளைத் தொடர்ந்து பயன்படுத்தலாம்.",
        "sa": "नियोजितानुरक्षणेन अवरोहणं बाधितं भवेत्। संस्थापिताः अन्तर्जालरहितसञ्चयाः उपयोक्तुं शक्यन्ते।"
      },
      "action": "none"
    },
    {
      "id": "upcoming-feature-example",
      "enabled": true,
      "type": "feature",
      "revision": 1,
      "repeatPolicy": "once_per_revision",
      "priority": 50,
      "title": { "en": "An update is planned" },
      "message": { "en": "More offline data options are planned for a future update." },
      "action": "offline_data"
    },
    {
      "id": "migration-example",
      "enabled": false,
      "type": "migration",
      "revision": 1,
      "repeatPolicy": "once_per_revision",
      "priority": 200,
      "title": { "en": "A new application is available" },
      "message": { "en": "Read the application listing before choosing to move." },
      "action": "open_play_store",
      "actionUrl": "https://play.google.com/store/apps/details?id=com.example.replacement"
    }
  ]
}
```

Use type maintenance for planned maintenance, downtime for an interruption, critical for high-importance information, and general for ordinary messages. Each remains non-blocking.

## Publication and cancellation

1. Edit announcements/home.json on the master branch of actionanand/localtell-data.
2. Choose a new ID for a new subject, or increment revision to reannounce the same subject.
3. Supply English text and reviewed optional Tamil/Sanskrit text. Configure type, repeat policy, priority, action, dates and version bounds.
4. Validate JSON, unique IDs, text lengths, positive revisions, and Play listing links.
5. Commit/publish the data file using the data repository's normal workflow. No new Android release is needed for supported content/settings.
6. Allow for foreground polling and CDN propagation.

To cancel, set enabled false, remove the object, or set an expiry in the past. A valid fetched document replaces the active selection. Offline clients may retain the previous document until a successful refresh, so time-sensitive notices should have expiresAt.

This client task does not modify or publish the data repository.
