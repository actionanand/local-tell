# App-data backup — local files and Dropbox

## Configuration and installed data

`app-config.json` is the build-time source of truth:

```json
"maxSavedPlaces": 30,
"maxJourneyPoints": 100
```

Gradle requires integers in 1–200 and 1–1000 respectively, rejecting missing,
Boolean, string, fractional, zero, negative and out-of-range values with an
actionable error. BuildConfig exposes both to `AppLimits.current`/`DataLimits`.
Changing the JSON and rebuilding changes saving, counts/dialogs, merge capacity,
Journey retention and display. These are not editable user preferences. Absolute
safety ceilings are not business defaults; Gradle and DataLimits must stay aligned.

Lowering the Saved Places limit never removes existing bookmarks. The list explains
over-capacity status and blocks additions, while existing filled bookmarks remain
removable. All such existing bookmarks can still be backed up (up to the validated
absolute safety ceiling). Decoding is bounded by that ceiling, not a smaller
recipient's current setting, so preview can explain capacity overflow. File restore may
add nothing to an already-over-limit collection, but any new addition must fit the
configured maximum. Nothing is silently truncated to make a bookmark backup fit.

Journey retains/displays the newest configured N by `timestamp DESC, id DESC`.
Normal opening/insertion applies retention; backup inspection does not trim before
confirmation. Reducing the Journey limit applies the usual oldest-first eviction,
not a database reset. Increasing it allows future records up to the new maximum.

Installed database contracts are unchanged: `saved_places.db` schema 1 and
`journeys.db` schema 2. The existing additive Journey v1→v2 migration preserves old
records, with null latitude/longitude/accuracy/subdistrict. No GPS, service interval,
cell matching or location-code changes are involved.

## Settings UI and shared ownership

**More → Settings → Backup & Restore** owns the manual workflow and a separate
`AppBackupViewModel`/`AppBackupRepository`. Saved Places only manages bookmarks.
The screen shows both counts/limits, included datasets and an unencrypted-sensitive-
data notice. Backup is allowed for either nonempty dataset; empty/empty explains
there is nothing to back up. Restore remains available when empty.

Backup ready retains filename, privacy notice, Save locally, Share Backup and
Cancel. Save locally uses CreateDocument; Share opens the existing read-only
FileProvider Sharesheet directly. These file-sharing actions do not automatically
connect a cloud account or confirm delivery. The separate Dropbox integration
below uses the existing App Folder OAuth connection.

Restore Backup uses GET_CONTENT; Choose device file uses OPEN_DOCUMENT. External
VIEW/SEND opens this Settings destination, without briefly composing Home/Easy and
starting acquisition. All routes use the same transient `content://` URI validator,
provider `DISPLAY_NAME`, bounded read, decoder and combined preview. Missing grants,
inaccessible providers, file paths/URLs, missing names or unsupported extensions
fail safely. No persistent URI grant or new storage permission is requested.

The recommended folder for manually shared files is **AR Apps / LocalTell / Backups**. A receiving cloud app
may let the user choose an existing folder, but generic sharing cannot create or
enforce it. Sharesheet opening is not successful upload/delivery confirmation.
Provider availability differs; download the `.bklt` first if the cloud app cannot
return a readable document. Output success requires write, flush, close and final
filename validation; a failed provider write may leave an incomplete destination.

## New format only

Every file remains `LocalTell-18-Oct-2026-08-30PM-<UUID>.bklt`. Actual names must
have a `.bklt` extension regardless of MIME type. A `.json`, `.txt`, `.zip`, missing
or path-like filename is rejected even if content is valid. Renaming invalid data
to `.bklt` cannot bypass content validation. No user-visible JSON file is generated.

Envelope (JSON illustration; `payload` is an escaped JSON **string**):

```json
{
  "format": "localtell-backup",
  "schemaVersion": 1,
  "payload": "{...}",
  "sha256": "<lowercase SHA-256 of the exact UTF-8 payload string>"
}
```

Payload fields:

- `exportedAt`: ISO-8601 UTC instant.
- `savedPlaces`: complete bookmark objects: `id`, `name`, `localityName`,
  `subDistrict`, `district`, `state`, `countryCode`, `latitude`, `longitude`,
  `savedAt` (ISO instant), `source`, `accuracyMetres`.
- `journeys`: `timestamp` (ISO instant), `areaName`, `district`, `state`,
  `subDistrict`, `radio`, `plmn`, `cellId` (64-bit integer), `confidence` (0–100),
  `latitude`, `longitude`, `accuracyMetres`. SQLite autoincrement IDs are omitted.

Optional hierarchy/accuracy fields remain null, and older Journey coordinates
remain null; coordinates must be present as a pair when available. No coordinates
are fabricated. Real geographic, carrier and radio metadata is never translated.
No passwords, tokens, permissions, geographic packs, preferences or survey data
are included. This is a complete **Saved Places + Journey** backup, not an image
of all application storage.

The common read/encode/decode ceiling is **12 MiB**, including escaped envelope.
Absolute record ceilings (200 bookmarks/1000 Journey entries), bounded strings
(200 UTF-16 characters, IDs 100), finite numeric fields and strict UTF-8/JSON keep
memory/size bounded. Strict parsing also caps JSON values at 40,000 (valid full
backups at the absolute ceilings need fewer than 20,000), rejecting tiny-value
floods before org.json allocates their object graph. The ceiling allows for Unicode
separators represented as six-character JSON escapes and escaped again inside the
envelope (an 8 MiB ceiling is insufficient at the absolute record maxima). Tests
exercise this maximum-length escaping case and value floods.
Strict parsing rejects duplicate keys, invalid Unicode, trailing junk, unsupported
versions, bad types, metadata and checksum corruption. Unknown optional extra
fields may be ignored within a supported version; incompatible schema changes
need a new version. SHA-256 detects accidental corruption, not authenticity or
encryption. Backups contain sensitive coordinates, history and cellular metadata;
only save/share with trusted destinations.

The previous `localtell-saved-places` schemas 1/2 are deliberately rejected. No
legacy decode branch remains. Installed records are not deleted or rewritten
because an exported format changed. Export again with the current application.

## Coordinated snapshot and merge

All SQLite, provider IO, full validation, merge planning and encoding run on IO,
not Compose's/UI dispatcher. A dedicated connection attaches the existing Journey
database to the Saved Places database. One transaction reads committed rows from
both; encoding happens after that snapshot transaction, avoiding long write locks.
Journey is not stopped or asked for another fix. Normal SQLite locking may briefly
serialize an ongoing write; providers are never accessed inside the transaction.

The full file is validated before preview/writes. Preview shows each dataset's
input count, duplicates, genuinely new records, final count/configured maximum,
and Journey records excluded by retention. Confirmation replans against current
committed data, checks bookmark capacity before any inserts, inserts both datasets,
and trims Journey in the **same attached transaction**.

Both on-disk databases must use DELETE/TRUNCATE/PERSIST rollback journals and the
connection sets synchronous FULL before starting. WAL/OFF/MEMORY fails closed;
the app does not silently change existing database journal modes. This deliberately
uses SQLite's attached-database commit mechanism rather than pretending two
independent commits are atomic. See [SQLite ATTACH transaction guarantees](https://www.sqlite.org/lang_attach.html).
Any pre-commit capacity, constraint, IO or cancellation failure rolls back both.
Cancel/dismiss before confirmation performs no merge or trimming. After a
confirmed transaction commits, lifecycle cancellation cannot undo the commit;
change notifications are sent before returning across the dispatcher boundary.
Android SQLite integration/power-loss validation is still a device release gate.

Bookmark matching retains the existing identity/distance rules. Existing names,
coordinates, timestamps and other metadata win; conflicting IDs or duplicate
entries within the bookmark file are rejected. File restore's new records must all fit; no partial
bookmark import, eviction, replacement or silent subset import occurs.

Journey identity is all stored fields except local ID (signed numeric zero is
normalized). Duplicate records within the backup or already stored are skipped.
Merge considers newest timestamps first, with existing rows winning a tie at the
retention cutoff. New equal-timestamp records use a deterministic metadata order;
only new candidates surviving retention are inserted, in ascending timestamp/key
order, to obtain consistent local IDs. Final database order remains timestamp DESC,
ID DESC. This prevents repeated oversized/equal-timestamp restores from repeatedly
displacing their own survivors. Preview's retention count includes ignored older
incoming records and evicted existing records; not all reported new records need
survive the configured retention window. No separate duplicate ledger is stored.

Bookmark and Journey change signals refresh their respective screens after commit.
Removing bookmarks and clearing Journey still affect only their own datasets.

## Snapshot lifecycle (unchanged)

Generated files stay in the dedicated private `files/saved-place-backups/` directory
for delayed recipients, not disposable cache. The existing cancellation-safe
`prepareForPublication` helper owns a file until UI publication, including the IO
result-delivery race. Unpublished/explicitly cancelled unused snapshots are released
and discarded on non-cancellable IO; active leases prevent deletion while needed.

Subsequent preparation cleans only generated direct-child `.bklt` files strictly
older than **30 days**, without traversal, symlink following or scanning other
directories. Before handoff/export the retention timestamp is renewed on IO.
Recent shared/attempted-export snapshots survive cancellation, chooser dismissal,
navigation and owner recreation; temporary recipient URI grants are not extended.
Active/prepared leases protect files from cleanup. Unknown/future timestamps are
retained. No cleanup scheduler is introduced: files can remain longer than 30 days
until the next safe preparation. This is an age cleanup policy, not a count cap.
The old cache FileProvider root remains available for previous handoffs but is not
scanned. User-exported local documents are never deleted by this cleanup. Dropbox has a
separate, verified-ownership retention policy described below.

## Dropbox backup management — Feature 4B

The existing PKCE OAuth connection retains **App Folder** access, not Full Dropbox
access. Uploads use the API-relative directory `/localtell-managed-backups`, shown
as **Apps / LocalTell / localtell-managed-backups**. Dropbox controls the app-folder
root; this permission cannot create the proposed **Apps / AR Apps / LocalTell**
umbrella. The UI explains this restriction rather than claiming that destination.
Dropbox may localize or rename the app-folder root; the API-relative directory is
unchanged. Existing root-level `.bklt` files remain readable for conflict handling
and compatibility, but are not automatically adopted as owned files.

The `.bklt` format and its sensitive contents remain **unencrypted**, including
when uploaded directly to Dropbox. The existing privacy disclosure stays visible.
Encrypted local OAuth credential storage is not backup-content encryption.
Credentials and upload ownership/session metadata are not included in `.bklt`.

### Modes and connection lifecycle

- **Off** is the default and blocks manual and scheduled backup.
- **Manual only** runs only after tapping **Sync now**.
- **Daily** and **Weekly** use unique WorkManager periodic work with a connected
  network constraint and exponential retry backoff. Android controls execution
  timing; exact times are not promised. Sync now is also available in these modes.

Manual and scheduled workers share a mutex. Each queued request contains the
verified account hash and a persisted session generation. Starting a disconnect
or a new authorization invalidates that session before asynchronous credential
work. Workers verify the account and session before network mutations, during
transfer reads/writes, before a merge commits, and before publishing results.
Old requests cannot switch to a newly connected account. Backups already accepted
by Dropbox cannot be undone by cancellation; an in-flight request may leave an
extra snapshot, which is preserved rather than guessed to be safe to delete.

Disconnect requires confirmation, cancels work, clears encrypted credentials and
attempts token revocation. It never deletes backups or local app data. A failed
revocation is disclosed independently of local disconnection.

### Verified upload and ownership-only retention

Uploads create new uniquely named files without overwriting any existing file.
Every remote read checks metadata size against `AppBackupCodec.MAX_BYTES` (12 MiB)
before receiving bytes, pins the Dropbox revision, and uses a bounded output
stream. The full filename, UTF-8, strict JSON, checksum and record validation then
runs unchanged. Oversize or malformed data is a non-retryable validation/storage
failure; it cannot reach the merge transaction.

After uploading, LocalTell downloads and validates the new revision and compares
its data fingerprint. Only then can it record ownership and consider cleanup.
The private, checksummed, account-scoped ownership ledger records the exact upload response:
Dropbox file ID, revision, path, size and content hash. Cleanup requires a matching
record and matching current metadata; deletion uses file ID plus a revision
condition, not the path alone. Neither filename, extension nor directory membership
proves ownership.

The latest three verified, positively owned backups are retained, including the
new upload. Server modification time orders files; persisted upload order breaks
timestamp ties. No folders are deleted or traversed recursively. Manual uploads,
legacy files, unrelated files, modified/replaced files, and records with missing
or inconsistent ownership proof are preserved.

**Safety limit:** ownership proof is local to the installation that uploaded the
file. Reinstallation, cleared storage, ledger corruption, or another device's
uploads can therefore leave more than three files in Dropbox. Such files are not
retroactively claimed or deleted. This intentionally prioritizes preservation
over a global three-file cap. A cleanup failure surfaces as a backup error rather
than silently pretending cleanup succeeded.

### Conflicts, pending edits and Saved Places overflow

Local and remote data fingerprints are compared with the account's common
baseline. Different remote data requires the explicit **Use this device's data**
or **Merge Dropbox backup** choice. Resolution also carries a token for the remote
file set. LocalTell checks that set immediately before merging/uploading and again
after verification, before each deletion and before successful completion. A
concurrent change keeps a conflict and stops cleanup. A competing remote snapshot
is not hidden by the worker's own newer upload.

Dropbox does not provide an atomic compare-and-swap for an entire directory.
Another device can still upload after a final check. Immutable snapshots prevent
overwriting its records; the next synchronization detects differing content.
This is snapshot backup with explicit conflict resolution, not a real-time
multi-device database replication protocol.

A Dropbox merge keeps unique Saved Places up to the absolute 200-record safety
bound, including temporary overflow of the configured limit. File restore keeps
its existing stricter capacity check. The existing attached SQLite transaction
rolls back inserts and Journey trimming together on validation, constraints,
cancellation or a session invalidation before commit.

While Saved Places exceeds its configured limit, new saves and Dropbox syncing
are paused. The prompt offers to remove the oldest extra places or let the user
delete manually. Deletion is never implicit. Explicit trimming replans inside a
write transaction and removes no more than the count the user approved. Once the
count is within the limit, syncing becomes available again. The adopted remote
baseline prevents trimming from creating the same conflict repeatedly.

Success publishes the uploaded fingerprint as baseline while comparing it with a
fresh transactional local snapshot. Edits during an upload remain pending.
Account/session checks, cancelled superseded refresh jobs and revision-checked
status updates prevent old asynchronous snapshots from clearing newer state.
Failures do not replace the last successful timestamp or discard conflict details.

## Validation

Host checks (SQL contracts, not Android or Kotlin execution):

```sh
python scripts/test_journey_retention.py
python scripts/test_saved_places_storage.py
python scripts/test_saved_places_backup_contract.py
python scripts/test_app_backup_storage.py
git diff --check
```

CI must run `gradle lint testDebugUnitTest` and debug/release/R8 APK/AAB builds.
`AppBackupTest` covers configurable limits, full metadata, optional coordinates,
both/single datasets, checksum/schema/type rejection, actual malformed decoder
input, previews, overflow, repeated/equal-timestamp restores and max-size metadata.
Existing document IO and snapshot ownership/cancellation/retention suites remain
applicable to the shared flow. Resource tests include all three locales.

Real device gates: upgrade with both existing databases; restore rollback under
storage/constraint failure; concurrent Journey writes; repeats and lowered limits;
empty datasets; cold/warm external open/share; rotation/background/cancel during
prepare/read/write/confirm; delayed Sharesheet recipient reads; large fonts,
TalkBack/light/dark and all languages; Drive/Dropbox/provider-specific behavior
only when actually available. No cloud interoperability success is assumed from
host or source tests. These corrections do not change the app version, signing,
location codes or acquisition.

Dropbox unit coverage includes fourth/fifth upload retention, unknown/manual file
protection, invalid ownership records, failed uploads/verification, download
limits, account/session binding, concurrent remote changes and localized resource
validity. `DropboxBackupStorageTest` is an Android instrumentation suite using
isolated database files/preferences for overflow, explicit trimming, rollback,
pending changes and stale state updates. It requires an emulator or device; merely
building the Android test APK does not execute it. No local tests/builds were run
for these corrections; GitHub Actions and device verification are still required.
