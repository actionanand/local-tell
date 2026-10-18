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
or **Upload to cloud** in **Backup ready**, with the actual filename and Cancel.
Local saving uses Android's document picker with local-only
providers requested. Cloud options offer compatible document providers or a
read-only FileProvider sharing fallback for apps without document-provider support.
No cloud account or provider SDK is added to LocalTell.

The preferred folder convention is **AR Apps / LocalTell / Backups** for local
or cloud storage. Users navigate to or create these folders using the provider's
picker controls where supported. LocalTell never creates cloud folders itself or
claims that a provider has created them. The cloud action opens **Choose cloud
destination**, explaining that Android's storage picker can show both local and
cloud destinations; there is no reliable cloud-only filter. **Open storage picker**
uses ACTION_CREATE_DOCUMENT with local-only disabled. Only the providers exposed
by Android appear; an installed cloud app does not necessarily implement a document
provider. **Share Backup** is the alternative when the app can receive files but
does not expose a document provider. If neither mechanism is available, enable a
compatible storage/sharing app or use another destination. No provider inventory,
OAuth, account registration, provider SDK, background upload or sync is added.

Every prepared, exported and shared file has a `.bklt` extension, for example
`LocalTell-18-Oct-2026-08-30PM-<UUID>.bklt`. English month names and the timestamp
are human-readable; a UUID prevents collisions. No `.json` backup is generated.
The user/provider's actual display name must still end in `.bklt` before any bytes
are written, and is checked again after close. Only successful output-stream write,
flush and close plus that final name check produce the document-written message.
A document
provider may synchronize later; this does not promise server-side durability.
Sharing reports only that its chooser opened, never delivery or a successful
save/upload by a receiving app (the user can also cancel the chooser).
Cancelling a picker does not report success or an error. A write, flush, close or
filename-query failure does not report success; it retains the prepared snapshot
and offers storage/share alternatives. An interrupted provider write can leave an
incomplete destination document, which the user may remove through the provider.
Cancellation is checked between stream operations, and opened streams are closed.
A blocking provider call cannot be interrupted immediately; it runs on IO and does
not block navigation. Private snapshot cache files may be
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

**Restore Backup** uses the document picker, including compatible cloud providers.
If an app cannot expose the file there, download its `.bklt` first and choose the
local copy. Android Open/Share into LocalTell also supports content URIs advertised
as `application/octet-stream` or `application/vnd.localtell.backup`. The previous
`*/*` external filters are removed so LocalTell is no longer advertised for every
text/image/archive type. Binary files other than backups can still appear because
Android cannot filter opaque content URIs by their DISPLAY_NAME suffix. Some
providers advertise `.bklt` as a different MIME type; use Restore Backup for those
instead of relying on an external Open/Share entry. All sources
call the same validator. It queries the provider's actual `DISPLAY_NAME` on IO;
missing names, `.json`, `.txt`, `.zip`, path-like names and other extensions are
rejected regardless of MIME type. Renaming arbitrary data to `.bklt` does not bypass
strict UTF-8, JSON syntax, duplicate-key, bounded-nesting or content checks.
Restore reads at most 256 KiB, validates format/version/checksum where present,
types, finite coordinate ranges, text lengths, timestamps, source values, IDs and
duplicate content. Unsupported or malformed files leave the database untouched.
Before any write it shows places in the backup, already-saved total, new count,
duplicates skipped and final total. Default and
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

`BackupDocumentIOTest` exercises the same stream helper used by local and cloud
documents: Unicode round trips, extension checks before opening streams, provider
renaming after close, open/read/write/flush/close failures, cancellation cleanup,
malformed UTF-8 and size limits. It runs in the JVM suite, not in the host scripts.
Host contract checks cover the manifest, private URI grants, picker wiring and
translation parity; they do not simulate Android's document UI or a cloud server.

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
2. Google Drive: install/sign in, open Upload to cloud, and look for Drive in the
   system picker. If available, select the preferred folder and save. Verify the
   remote file in Drive after synchronization, not only the LocalTell message.
   Select it for restore, inspect all five preview counts and Cancel, then confirm
   a merge. Existing IDs, coordinates and timestamps must remain unchanged.
3. Dropbox: test whether this device/app version exposes a document provider.
   If it does, follow the same picker test. Otherwise choose Share Backup, select
   Dropbox if offered, and finish its own upload UI. LocalTell reports only that
   sharing opened. Cancel the chooser and receiving-app flow; never claim upload
   success. Retrieve/download the `.bklt` and restore it with the file picker.
4. Repeat with OneDrive, Koofr, MEGA, Nextcloud/ownCloud or another provider
   actually installed. Record app/Android version and which of document creation,
   file selection, Open/Share receipt and upload is supported; do not assume all
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
   uses a different MIME type via Restore Backup. Cancel and retry after rotation,
   backgrounding or process death; if a prepared snapshot has been lost, prepare
   again rather than reporting success.
