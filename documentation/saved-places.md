# Saved Places

Saved Places are manual, private bookmarks stored in `saved_places.db` on the device.
There is no account, synchronization, analytics, location upload, or new permission.
The app's existing exclusion from Android cloud backup is unchanged. Normal app
updates preserve this database; clearing app data or uninstalling removes it, so
export a backup first when moving devices.

## Save and reopen

Use the outlined bookmark on a Home result, Easy current location, Easy Find
Location/code result, or Journey history entry that has genuine coordinates.
It fills after the save succeeds and a brief confirmation appears. Tapping a filled
bookmark asks before removal. Cancel, Back and outside dismissal leave it saved.
Bookmark status is shared across these screens by one retained Activity ViewModel.

More → Saved Places lists the newest saves first and shows the count out of 30.
Tap a row to open it under Easy → Find Location; the result scrolls into view.
Stored metadata is shown even if the original geographic pack was removed.
Opening does not acquire GPS, access a pack, or contact a network service.
It provides both LocalTell codes, individual copy/share actions, the existing full
location share, Google Maps, and Uber pickup/destination options. External app
launch failures keep the existing graceful fallback behavior.

An unnamed coordinate is valid: its coordinates become its display name. Country
and geographic hierarchy are optional metadata, not prerequisites. Names remain
canonical data, never translated. When metadata is absent, it is not inferred or
fabricated from a cellular identity. No cellular identifiers are stored in bookmarks.

## Limit and duplicate policy

The limit is 30; reaching it never evicts an older place. Attempting to add a new
place at capacity opens a localized limit dialog with the 30/30 count, Cancel and
Manage Saved Places. An existing filled bookmark still offers confirmed removal,
not a limit dialog. Existing bookmarks win
on restore. Named results with the same normalized name and hierarchy within
5 km count as the same locality when hierarchy is known; distant namesakes remain
distinct. Without hierarchy, named results use the same 20 m threshold as unnamed
coordinates. Coordinate-only
results, or a named result compared with an unnamed result, match within 20 m,
including rounding-grid edges. These are duplicate heuristics, not positioning
accuracy guarantees. Separately named nearby landmarks remain distinct.

All writes are serialized on IO and use SQLite transactions. Restore checks IDs,
duplicates and capacity again inside its merge transaction. The list is keyed by
stable bookmark IDs. No rendering path opens a database or starts location work.

## Manage bookmarks

Use the row's remove action for individual deletion. Long-press a row to select;
tap rows to select/deselect, Select All, Cancel selection, or Delete Selected.
Deletion requires confirmation and captures exactly the requested IDs. Remove All
has its own confirmation with the affected count. Removing bookmarks never clears
Journey history, and clearing Journey history never removes bookmarks.

## Worldwide codes and accuracy

The unchanged LocalTell V1 codec accepts every finite coordinate in latitude
[-90, 90], longitude [-180, 180], without a country check or geographic pack.
The 19-digit numeric form (including leading zeros) and 15-character short form
represent the same six-decimal canonical coordinate and decode offline. Formatting
spaces/hyphens do not change the payload or checksum. Previously generated codes
remain compatible.

Bookmarks retain the original coordinate doubles separately from these canonical
codes, plus reported accuracy when supplied. Decimal precision is not GPS accuracy;
a code cannot make the original measurement more accurate. Never use this feature
for emergency response or safety-critical navigation.

## Export and restore

Manual Backup first prepares a private snapshot on IO, then asks **Save locally**
or **Upload to cloud**. Local saving uses Android's document picker with local-only
providers requested. Cloud options offer compatible document providers or a
read-only FileProvider sharing fallback for apps without document-provider support.
No cloud account or provider SDK is added to LocalTell.

Every prepared, exported and shared file has a `.bklt` extension, for example
`LocalTell-18-Oct-2026-08-30PM-<UUID>.bklt`. English month names and the timestamp
are human-readable; a UUID prevents collisions. No `.json` backup is generated.
The user/provider's actual display name must still end in `.bklt` before any bytes
are written, and is checked again after close. Only successful output-stream write,
flush and close plus that final name check produce **Backup saved**. A document
provider may synchronize later; this does not promise server-side durability.
Sharing reports only that its chooser opened, never delivery or a successful
save/upload by a receiving app (the user can also cancel the chooser).
Cancelling a picker does not report success. Private snapshot cache files may be
reclaimed by Android; files older than 24 hours are pruned on the next preparation.

Internal content remains versioned UTF-8 JSON. New schemaVersion 2 backups have
a SHA-256 checksum of the exact payload bytes for accidental corruption detection
(not authentication or encryption). Valid legacy schemaVersion 1 content remains
readable through `.bklt`, but legacy content has no integrity checksum. All other
schema versions are rejected. Content contains original coordinates, optional hierarchy/country,
saved timestamp, source and accuracy, but no cellular or Journey data.

**The backup is not encrypted.** Only save or share it with trusted destinations.
Local files work without internet. A cloud document provider selected by the user
may itself require network access; LocalTell does not upload backups independently.

Restore uses the document picker or Android Open/Share into LocalTell. All sources
call the same validator. It queries the provider's actual `DISPLAY_NAME` on IO;
missing names, `.json`, `.txt`, `.zip`, path-like names and other extensions are
rejected regardless of MIME type. Renaming arbitrary data to `.bklt` does not bypass
strict UTF-8, JSON syntax, duplicate-key, bounded-nesting or content checks.
Restore reads at most 256 KiB, validates format/version/checksum where present,
types, finite coordinate ranges, text lengths, timestamps, source values, IDs and
duplicate content. Unsupported or malformed files leave the database untouched.
Before any write it shows new count, duplicate count and final total. Default and
only behavior is merge, never replacement. Cancel changes nothing. A total above
30 blocks restoration without partial imports. No storage permission is needed.

## Existing Home and Journey records

New Home results and cache entries carry optional genuine coordinates. Old cache
JSON remains readable and has no bookmark action if coordinates are absent.
Journey database version 2 adds nullable latitude, longitude, accuracy and
sub-district columns via `ALTER TABLE`; existing history is retained, not rewritten
or cleared. Old entries without coordinates remain readable without a bookmark.
New entries persist the fix already acquired by Journey. Recording criteria,
100-entry history retention, foreground lifecycle and 20-second interval are
unchanged. No extra acquisition is introduced.

## Validation

JVM tests cover global reopen/code parsing, existing leading-zero golden behavior,
original precision, missing legacy coordinates, Maps/Uber builders, duplicate
heuristics, capacity and merge safety, invalid backups and Unicode round trips.
The existing V1 golden/checksum/invalid-character tests remain unchanged. Resource
tests check bookmark English/Tamil/Sanskrit coverage and placeholders.

Run in Android CI:

```sh
gradle lint testDebugUnitTest
gradle assembleDebug assembleRelease bundleRelease
```

Additional host-side SQL tests (not substitutes for Android tests):

```sh
python scripts/test_journey_retention.py
python scripts/test_saved_places_storage.py
python scripts/test_saved_places_backup_contract.py
```

Device release gate (not claimed as passed by source review):

- Single-tap Home/Easy/More navigation and smooth scrolling during acquisition,
  bookmark IO, network failures, export/restore and foreground transitions.
- Bookmark every eligible entry point, including code lookup and coordinate-only
  results; verify filled status across screens and all removal cancellation paths.
- Long-press/select/deselect/Select All/Cancel/Delete Selected/Remove All; check
  confirmation counts and verify Journey/bookmark deletion independence.
- Reopen under Easy after removing packs, while offline and with Location disabled;
  no location request, both codes and expected coordinates are available.
- Restart/rotate/background/update with existing bookmarks; test migration from
  Journey v1 and cache JSON without coordinate fields.
- JSON picker cancellation, Unicode roundtrip, malformed/oversized/unsupported
  imports, duplicates, conflicting IDs, overflow and document-provider failures.
- Google Maps/Uber installed and absent; numeric/short copy and sharing targets.
- English/Tamil/Sanskrit, light/dark, large fonts, screen-reader selection labels.
- Announcement session suppression and existing GPS cancellation/telephony/TTS
  regression suites remain green.
