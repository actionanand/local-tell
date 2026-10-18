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

Manual Backup prepares a private snapshot on IO, then shows a compact **Backup
ready** dialog with its filename, unencrypted-coordinate warning, **Save locally**,
**Share Backup** and Cancel. Save locally uses ACTION_CREATE_DOCUMENT with local
storage requested. Share Backup immediately opens Android's Sharesheet using a
read-only FileProvider URI. There is no intermediate cloud destination dialog.
Only compatible receiving apps offered by Android can be chosen; LocalTell does
not assume Google Drive, Dropbox or any other provider is installed or available.

The recommended folder is **AR Apps / LocalTell / Backups**. In a receiving cloud
app, choose an existing destination folder when its UI supports this. Generic
sharing cannot enforce that path, create folders, or confirm an upload. LocalTell
adds no cloud account, provider SDK, OAuth, backend, background upload or sync.

Every prepared, exported and shared file has a `.bklt` extension, for example
`LocalTell-18-Oct-2026-08-30PM-<UUID>.bklt`. English month names and the timestamp
are human-readable; a UUID prevents collisions. No `.json` backup is generated.
The user/provider's actual display name must still end in `.bklt` before any bytes
are written, and is checked again after close. Only successful output-stream write,
flush and close plus that final name check produce the document-written message.
A document provider may synchronize later; this does not promise server-side durability.
Sharing reports only that its chooser opened, never delivery or a successful
save/upload by a receiving app (the user can also cancel the chooser).
Cancelling a picker does not report success or an error. A write, flush, close or
filename-query failure does not report success; it retains the prepared snapshot
and offers storage/share alternatives. An interrupted provider write can leave an
incomplete destination document, which the user may remove through the provider.
Cancellation is checked between stream operations, and opened streams are closed.
A blocking provider call cannot be interrupted immediately; it runs on IO and does
not block navigation. New snapshots stay in a dedicated private files directory,
not the disposable cache. Preparing another backup opportunistically deletes only
LocalTell-generated `.bklt` snapshots directly inside that directory when they are
**strictly older than 30 days**. Before sharing or exporting, the retention timestamp
is renewed on IO, before the URI/stream is handed off. Closing the chooser,
navigating away or preparing another backup does not delete a recent shared file.
This permits delayed reads while the recipient's temporary URI grant remains valid;
LocalTell does not extend that grant or know when a recipient finishes.

Explicitly cancelling a never-used prepared snapshot (including cancelling its save
picker) removes that private file on IO; clearing its Activity owner also releases
an unused snapshot. Once a share handoff or export write is attempted, its snapshot
is retained conservatively, even if the attempt fails or the receiving chooser is
cancelled. In-memory leases protect active operations and a currently prepared
snapshot, including an open destination picker, from age cleanup across Activity
owners in the same process. Lease release also
runs when an operation is cancelled before its coroutine starts. Cancel during the
pre-share IO step prevents launching Share and deletes the unused file after the
lease ends. Unknown/future
timestamps are retained. No scheduler or automatic cloud cleanup is added; if the
app does not prepare another backup, old snapshots remain until that opportunity
or clearing/uninstalling app data. Thirty days bounds age at cleanup, not the number
of backups a user can create during that period.

Cleanup never recurses, follows symlinks, touches other filenames, or deletes
user-exported local/cloud documents. The old cache FileProvider root remains
readable for earlier handoffs; it is not scanned by the new private-files cleanup,
and new backups are not written there.

Internal content remains versioned UTF-8 JSON. New schemaVersion 2 backups have
a SHA-256 checksum of the exact payload bytes for accidental corruption detection
(not authentication or encryption). Valid legacy schemaVersion 1 content remains
readable through `.bklt`, but legacy content has no integrity checksum. All other
schema versions are rejected. Content contains original coordinates, optional hierarchy/country,
saved timestamp, source and accuracy, but no cellular or Journey data.

**The backup is not encrypted.** Only save or share it with trusted destinations.
Local files work without internet. A cloud document provider selected by the user
may itself require network access; LocalTell does not upload backups independently.

**Restore Backup** uses AndroidX's GetContent contract (ACTION_GET_CONTENT with
CATEGORY_OPENABLE) to import once from an available file/content app. **Choose
device file** uses OpenDocument (ACTION_OPEN_DOCUMENT) as an explicit fallback.
Both immediately read and validate the transient content URI on IO, then keep
decoded records in memory for the confirmation preview. No persistent URI access
is requested. Provider availability differs between devices/apps; GET_CONTENT is
not a guarantee that any cloud provider appears. If a cloud app cannot return a
readable document, download the `.bklt` first and select that local copy.
This follows Android's [one-time import guidance](https://developer.android.com/guide/components/intents-common#GetFile).
Android Open/Share into LocalTell also supports content URIs advertised
as `application/octet-stream` or `application/vnd.localtell.backup`. The previous
`*/*` external filters are removed so LocalTell is no longer advertised for every
text/image/archive type. Binary files other than backups can still appear because
Android cannot filter opaque content URIs by their DISPLAY_NAME suffix. Some
providers advertise `.bklt` as a different MIME type; use Restore Backup for those
instead of relying on an external Open/Share entry. All sources
call the same validator. Only `content://` URIs with a nonempty provider authority
are accepted; `file://`, network URLs, malformed/unsupported URI types and missing
grants cannot be interpreted as local filesystem paths. It queries the provider's actual `DISPLAY_NAME` on IO;
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
   the device. Inspect all five preview counts and Cancel, then confirm a merge.
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
