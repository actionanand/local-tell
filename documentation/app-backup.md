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

### Cloud backup feature switch

```json
"enableCloudBackup": true
```

`enableCloudBackup` must be a JSON boolean; Gradle rejects strings such as
`"true"` and a missing value. It becomes `BuildConfig.ENABLE_CLOUD_BACKUP`, read
through `CloudBackupFeature.enabled`.

- `true`: the Dropbox card, connection, modes, Sync now, diagnostics and disconnect
  are available as described below.
- `false`: the whole Dropbox card is hidden and its ViewModels are not created.
  OAuth is never started or completed. Daily/Weekly work is not scheduled; at app
  start the unique scheduled, legacy periodic and manual Dropbox jobs are cancelled. A worker queued
  by an earlier build checks the switch at its entry point and stops before
  reading credentials or contacting Dropbox. Sync now and resolution requests are
  not enqueued.

Hiding the feature is **not** a disconnect. Credentials, account/session metadata,
ownership records, the chosen mode, Dropbox files and local data are all kept
untouched; nothing is deleted, renamed or moved. A later build with `true` resumes
through the normal stored-credential verification. Local backup, Save Backup,
Share Backup, Restore Backup, Choose device file, Saved Places and Journey history
are unaffected by the switch.

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
umbrella. The card shows only the real location and does not claim that destination.
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
- **Daily** and **Weekly** are anchored to the local calendar at a preferred
  2:00 AM (Weekly on a selectable weekday, Sunday by default). A unique periodic
  WorkManager "alarm" job has its next run overridden to the next occurrence and
  re-applies the override on every run (`UPDATE`, so no growing chain); its 1- or
  7-day period is only a fallback if an override was lost. The scheduler reads
  actual WorkManager state on each check, so a missing, cancelled or stale alarm is
  re-armed and repeated checks never duplicate it. Delayed, catch-up or manual
  backups never shift later occurrences. Timezone, clock and DST changes recompute
  the anchor. Android controls execution timing; 2:00 AM is preferred, not
  guaranteed. Sync now and the sync icons beside Daily and Weekly are available in
  every mode except Off and never change the schedule.
- A missed occurrence (device off, Doze, offline, deferred) is detected from
  local state when the alarm runs, the app starts or returns to the foreground,
  or after a timezone/clock change. One network-constrained backup is queued under
  its own unique name, so a pending manual sync can never swallow it; the worker
  mutex keeps all syncs serial. Before touching Dropbox, a scheduled run ends
  without work if the schedule (mode, weekday or session) changed since it was
  queued; otherwise it serves the occurrence due when it starts, so a Monday run
  delayed past Tuesday 2:00 AM backs up for Tuesday. A backup covers an occurrence
  only when its data was current at that time: if edits arrived during the upload,
  coverage is the snapshot time and the newer data is backed up again. When a
  scheduled run ends without a WorkManager retry and an occurrence that became due
  meanwhile is still unsatisfied and recoverable, a one-time re-check runs a minute
  later instead of waiting for the next 2:00 AM.
- If the alarm itself fails to schedule, it does not return a retry (its 2:00 AM
  override would take precedence over retry backoff); it queues a one-time
  recovery re-check about 15 minutes later and keeps its calendar anchor.
- Offline, temporary Dropbox errors and interrupted work recover automatically.
  A scheduled job retries up to three times with WorkManager backoff. When a run
  still ends with a recoverable failure (including one after the upload began,
  which is never retried blindly), a persisted throttle delays the next automatic
  attempt for that occurrence: 15 minutes, doubling per failure, capped at 6 hours.
  Job recreation, follow-ups and foreground events cannot reset it; a successful
  backup, a reconnection or the next occurrence does. The next attempt always lists
  and reads Dropbox first and resumes verification of an upload it had recorded.
  Authorization failures wait for reconnection; permanent failures (invalid data,
  quota, permissions, local storage) are not retried until the next occurrence.
  Conflicts and the Saved Places limit wait for the user.
- Only the selected Daily or Weekly option shows a "Next backup" time; Off,
  Manual and unselected rows show only their rule.
- Upgrades keep the selected mode, default Weekly to Sunday, cancel the old
  periodic job and start the anchor at upgrade time, so updating never triggers
  an immediate upload.

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
before receiving bytes and uses a bounded output stream.

Baseline downloads (`DOWNLOAD_REMOTE`) and post-upload verification
(`VERIFY_UPLOAD`) share `DropboxRevisionDownload`. The exact listed revision is
requested through the `path` argument as `rev:<revision>`, which Dropbox's
`/files/download` contract documents for a specific version. The deprecated
separate `rev` argument (SDK `withRev`) is not sent; the previous request combined
`id:<file id>` with it and was rejected on a device with HTTP 400 at
`DOWNLOAD_REMOTE`. A revision that does not match Dropbox's `[0-9a-f]{9,}` format
is rejected locally without a request. The returned file ID, revision and size
must match the listing before the body is read; the received byte count and the
Dropbox content hash (SHA-256 of per-4 MiB block digests) must match afterwards.
A mismatch means the file changed or the transfer was incomplete: the run aborts,
nothing is merged, recorded or deleted, and only a pre-upload attempt may retry. The full filename, UTF-8, strict JSON, checksum and record validation then
runs unchanged. Oversize or malformed data is a non-retryable validation/storage
failure; it cannot reach the merge transaction.

After uploading, LocalTell downloads and validates the new revision and compares
its data fingerprint. Only then can it record ownership and consider cleanup.
An unreadable ownership ledger or pending-upload receipt blocks uploads before
any remote write. A checksummed pending receipt stores the upload response,
fingerprint and pre-upload remote-state token. If verification, ownership
recording or cleanup fails, later attempts verify that same revision instead of
uploading another copy. Retention progress updates the receipt; successful
completion clears it. A process death between Dropbox accepting the upload and
persisting its response can still leave an unclaimed copy, which is preserved.
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

Automatic discovery lists only `/localtell-managed-backups`, relative to the
authorized App Folder. A missing folder is an empty initial state; it is created
when uploading. A concurrent creation is accepted only after confirming it is
a folder. Root-level manual and legacy files are never read by automatic sync.
Every valid `.bklt` file in the managed folder, including another device's
uploads, remains visible as sync data so cross-device changes become conflicts
instead of being silently ignored. Ownership controls only automatic deletion.
Files are read newest-first; an unreadable file without this installation's
ownership proof is skipped (and preserved) so it cannot block sync, while an
unreadable owned backup is reported as an invalid-backup error.
New file arrivals during a run still trigger a conservative conflict/race stop.
An interrupted upload whose file no longer exists remotely is forgotten and the
sync starts again from the current remote state.
Use the existing file picker and merge preview for explicit manual/legacy restore.
Changing a known file's revision remains visible as a conflict, but revokes its
eligibility for automatic deletion.

Local and remote data fingerprints are compared with the account's common
baseline:

- Identical data on both sides is already synchronized; no dialog is shown, even
  if an earlier conflict was recorded.
- An empty managed folder, or a remote backup with no records, uploads this
  device's data normally without a prompt. Uploads are append-only, so nothing
  in Dropbox can be lost.
- When this device has no Saved Places or Journey history and Dropbox holds a
  non-empty backup, a **Restore backup / Cancel** dialog is shown instead of a
  conflict. Restore is read-only toward Dropbox: it never uploads, records
  ownership or runs retention cleanup. Before writing it re-checks the confirmed
  path, content and remote file set, and writes only if the device is still empty
  inside the same transaction. Records added meanwhile produce a genuine conflict.
  If the remote file set changed, the confirmed path is no longer preferred: the
  newest valid backup is offered for fresh confirmation, and a vanished or
  entirely invalid backup set simply clears the offer.
  A completed restore adopts the snapshot as baseline and records a separate
  "last restored" time; the last-successful-backup time is not changed.
- When both sides hold different data, the explicit **Use this device's data**
  or **Merge Dropbox backup** choice is required.

Resolution also carries a token for the remote
file set. LocalTell checks that set immediately before merging/uploading and again
after verification, before each deletion and before successful completion. A
concurrent change to the tracked file set keeps a conflict and stops cleanup.

Dropbox does not provide an atomic compare-and-swap for an entire directory.
Another device can still upload after a final check. Immutable snapshots prevent
overwriting its records. A different installation's files have no device-local
ownership proof; they take part in conflict detection and the explicit merge
choice, but are never adopted for retention or deleted. This is installation-scoped snapshot backup,
not a real-time multi-device database replication protocol.

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

### Failure diagnostics

Only SDK `NetworkIOException` and interrupted transfer streams are treated as a
network outage. HTTP 400 (`BadRequestException`) is a rejected request, not a
connection failure, and never retries. Typed Dropbox
lookup/write tags distinguish paths, permissions and quota errors; permanent API
failures do not retry. Tagged Dropbox errors are inspected only after checking
their variant (for example `isPath` before `pathValue`); non-path variants and any
unexpected classification problem fall back to a generic category so the original
error is never hidden. Server/rate-limit, transport and changed-download errors may retry at most
three times before an upload starts. Upload and post-upload failures require a
later sync attempt, using the pending receipt where available, rather than
blindly retrying an ambiguous write. Daily, Weekly and Sync now share this logic.

Any unexpected exception is also classified, so a run always ends with a final
status. If the process dies mid-run, opening the backup screen while no backup
work is running changes a leftover **Syncing** status to **Backup interrupted**;
an attempt timestamp check prevents this from overwriting a run that just started.

The account-scoped diagnostic records stage, exception class, a fixed API tag,
sanitized request ID, retry decision and timestamp. For HTTP 400 only, it also
keeps a short **Detail** line from Dropbox's error text naming the rejected
argument, with paths, `id:`, `rev:` and `ns:` values redacted and length bounded.
Logcat and the optional **Copy diagnostic details** button expose only these
fields, never other exception messages, credentials, coordinates or backup
payloads. The card shows **Backup location: Apps / LocalTell /
localtell-managed-backups** and the short note that only backups created through
LocalTell are managed automatically; the former folder-help toggle and App Folder
paragraph were removed. The note does not change retention or file protection.

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
validity. `DropboxRevisionDownloadTest` drives the real SDK client through a fake
`HttpRequestor` to assert that both download and verification send only
`{"path":"rev:<revision>"}`, and covers ID/revision/size/hash mismatches, the
12 MiB limit and redacted HTTP 400 diagnostics. `CloudBackupFeatureTest` covers the
JSON boolean, screen sections, startup cancellation, scheduling and the worker
entry guard for both switch values. `DropboxBackupStorageTest` is an Android instrumentation suite using
isolated database files/preferences for overflow, explicit trimming, rollback,
pending changes and stale state updates. It requires an emulator or device; merely
building the Android test APK does not execute it. No local tests/builds were run
for these corrections; GitHub Actions and device verification are still required.
