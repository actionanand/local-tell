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

More → Saved Places lists the newest saves first and shows the count out of the configured Saved Places maximum (default 30).
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

The build-time `app-config.json` property `maxSavedPlaces` sets the limit (default 30);
reaching it never evicts an older place. Attempting to add a new
place at capacity opens a localized limit dialog with the current/configured count, Cancel and
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

All manual backup/restore controls now live under **More → Settings → Backup &
Restore**, not in Saved Places. A single version-1 `.bklt` file contains Saved
Places and retained Journey records. The former Saved Places-only backup formats
are intentionally unsupported; this does not alter either installed database.

See [App data backup](app-backup.md) for format, limits, coordinated merge, privacy,
snapshot ownership/30-day cleanup, local/shared/cloud document behavior and
validation requirements. Existing bookmarks remain available if a configured
maximum is lowered below the current count. Further additions are blocked until
the count is below the limit; no bookmark is automatically deleted.

## Existing Home and Journey records

New Home results and cache entries carry optional genuine coordinates. Old cache
JSON remains readable and has no bookmark action if coordinates are absent.
Journey database version 2 adds nullable latitude, longitude, accuracy and
sub-district columns via `ALTER TABLE`; existing history is retained, not rewritten
or cleared. Old entries without coordinates remain readable without a bookmark.
New entries persist the fix already acquired by Journey. Recording criteria,
newest-first retention uses `maxJourneyPoints` (default 100); foreground lifecycle
and the 20-second interval are unchanged. No extra acquisition is introduced.

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
python scripts/test_app_backup_storage.py
```

`BackupDocumentIOTest` exercises the same stream helper used by local and cloud
documents: Unicode round trips, extension checks before opening streams, provider
renaming after close, open/read/write/flush/close failures, cancellation cleanup,
malformed UTF-8 and size limits. It runs in the JVM suite, not in the host scripts.
Host contract checks cover the manifest, private URI grants, picker wiring and
translation parity; they do not simulate Android's document UI or a cloud server.
`BackupSnapshotsTest` covers the 30-day boundary, renewed handoff timestamp and
availability after store recreation, cancellation, active/picker protection,
coroutine pre-start cancellation, collision safety, and cleanup's directory/name/
symlink boundaries. Run these new JVM tests in Android CI.

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
- `.bklt` picker cancellation, Unicode roundtrip, malformed/oversized/unsupported
  imports, duplicates, conflicting IDs, overflow and document-provider failures.
- Google Maps/Uber installed and absent; numeric/short copy and sharing targets.
- English/Tamil/Sanskrit, light/dark, large fonts, screen-reader selection labels.
- Announcement session suppression and existing GPS cancellation/telephony/TTS
  regression suites remain green.

## Manual provider device matrix (must be verified on real devices)

Run the following on API 26 and a current Android version, in English, Tamil and
Sanskrit, light/dark mode and large font/TalkBack. CI does not verify cloud apps.

1. Save locally: create a bookmark with Unicode metadata, prepare a backup and
   check the timestamp/unique `.bklt` name. Select device storage, use/create
   `AR Apps / LocalTell / Backups`, and save. Reopen the actual document and verify
   the filename and valid contents. Cancel at every picker/dialog; no success/error
   should be reported for cancellation and no bookmark should change.
2. Google Drive: install/sign in, choose Share Backup and select Drive if offered.
   Pick an existing preferred folder in Drive if supported, then finish its upload
   UI. Verify the remote file in Drive. LocalTell reports only that sharing opened.
   Cancel both the chooser and Drive UI; never report completed upload. Delay the
   receiving-app read, rotate/navigate away and prepare another backup; the original
   shared snapshot must still exist. Test both Restore Backup and Choose device
   file. If Drive cannot supply a readable URI, download locally and restore.
   On a debug/test installation, also verify an unused cancelled snapshot is
   removed, recent shared snapshots survive, and snapshots older than 30 days are
   removed only when another backup is prepared. Never age or delete user documents.
3. Repeat sharing and both restore routes with Dropbox if actually supported on
   the device. Inspect both datasets' preview counts and Cancel, then confirm a merge.
   Existing IDs, coordinates and timestamps must remain unchanged. GET_CONTENT
   and OPEN_DOCUMENT may expose different choices; record what actually appears.
4. Repeat with OneDrive, Koofr, MEGA, Nextcloud/ownCloud or another provider
   actually installed. Record app/Android version and which of file selection,
   Open/Share receipt and upload is supported; do not assume all
   mechanisms exist for every provider. No-provider and no-share-target devices
   must have clear guidance; opening a chooser is never a delivery confirmation.
5. Test offline cloud files, signed-out/revoked access, full storage, read-only
   folders, provider process failure, renamed `.json` output and output-stream
   close failures. No failed operation may display a saved/uploaded success. A
   failed output can leave an incomplete document; bookmarks must remain intact.
6. Restore invalid extensions (even with valid content), renamed invalid content,
   invalid UTF-8, truncation, checksum corruption, unsupported schemas, duplicate
   IDs and over-capacity merges. No partial import. Check binary Open/Share URI
   grants in foreground and cold start, as well as `.bklt` files whose provider
   uses a different MIME type via both restore entry points. Return a file URI,
   missing authority, inaccessible content URI or missing DISPLAY_NAME and check
   safe rejection without a filesystem read. Cancel and retry after rotation,
   backgrounding or process death; if a prepared snapshot has been lost, prepare
   again rather than reporting success.
