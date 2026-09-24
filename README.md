# LocalTell

**LocalTell** is a native Android app that resolves the current serving cellular identity to an **approximate locality using offline SQLite data packs**. The normal lookup path uses no web API and does not request GPS coordinates.

## Core idea

```
Serving cell (MCC + MNC + RAT + TAC/LAC + Cell ID/NCI)
                         ↓
                downloaded SQLite pack
                         ↓
               approximate locality name
```

Internet is used only to download/update offline data packs from a configurable GitHub Releases manifest.

## Android stack

- Kotlin
- Jetpack Compose
- `TelephonyManager` / `CellInfo`
- Android SQLite for read-only state packs
- SQLiteOpenHelper for local journey history
- WorkManager for weekly update checks
- Foreground service for optional journey tracking
- No backend required for runtime lookup

## Configuration

### `app-config.json`

Single source for app identity and data-manifest location:

```json
{
  "applicationId": "com.actionanand.localtell.app",
  "appName": "LocalTell",
  "releaseBaseName": "LocalTell",
  "dataManifestUrl": "https://github.com/actionanand/localtell-data/releases/latest/download/manifest.json"
}
```

Change `applicationId` only before the first Play Store publication.

### `android-version.json`

```json
{
  "versionCode": 1,
  "versionName": "1.0.0"
}
```

On `main-android`, GitHub Actions automatically increments and commits `versionCode`. `versionName` remains manually controlled. Locally:

```bash
python3 scripts/bump-android-version.py
python3 scripts/bump-android-version.py --patch
python3 scripts/bump-android-version.py --minor
python3 scripts/bump-android-version.py --major
```

### `android-sdk.properties`

```properties
MIN_SDK_VERSION=26
COMPILE_SDK_VERSION=37
TARGET_SDK_VERSION=36
BUILD_TOOLS_VERSION=36.0.0
```

Both Gradle and CI read these settings. `compileSdk` is 37 because current Compose 1.12 libraries require it; the requested Play target remains independently configurable at API 36.

## Branch and CI

Android CI runs only on **`main-android`** or manual dispatch from that branch.

The workflow:

1. validates configuration and runs lint/tests;
2. increments `versionCode` and commits it;
3. builds release APK + AAB;
4. decodes the signing keystore from GitHub Secrets;
5. signs and verifies APK/AAB when secrets are available;
6. otherwise leaves clearly named `-unsigned` artifacts;
7. commits `releases/` back to `main-android`;
8. uploads Actions artifacts for 30 days;
9. deletes decoded signing material even after failure.

## GitHub signing secrets

Set these at **Repository → Settings → Secrets and variables → Actions**:

| Secret | Meaning |
|---|---|
| `KEYSTORE_BASE64` | Full Base64-encoded keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias; generator default is `localtell` |
| `KEY_PASSWORD` | Private key password; for PKCS12 normally use the keystore password |

Generate a key once on a trusted Linux/WSL machine:

```bash
./scripts/generate-keystore.sh 'YOUR_PASSWORD'
base64 -w 0 release-keystore.jks > keystore.b64.txt
KEYSTORE_PASSWORD='YOUR_PASSWORD' ./scripts/detect-keystore-format.sh release-keystore.jks
```

Upload the **contents** of `keystore.b64.txt` as `KEYSTORE_BASE64`, then securely back up the `.jks`. Never commit the keystore, encoded text, or passwords.

## Permissions and privacy

`ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` are requested together because Android 12+ requires that pairing for a precise-location request; fine access is required because Android classifies Cell IDs as location-sensitive data. LocalTell itself does **not** call GPS/GNSS location APIs. Some devices also require the system-wide Android Location switch to be enabled before `TelephonyManager` returns cell identities.

`INTERNET` is used only for the offline-pack manifest/download. `FOREGROUND_SERVICE_LOCATION` is used only when the user explicitly starts Journey mode.

## Offline data pack format

A downloaded pack is a normal SQLite database with:

```sql
area(id, area_name, district, state)

cell_lookup(
  mcc, mnc, radio, area_code, cell_id,
  area_id, confidence, last_seen
)
```

Locality strings are normalized into `area`, so thousands of cells can reference the same area name without repeating the text. The app first performs an exact identity match. For LTE/5G only, it can fall back to the PLMN-scoped ECI/NCI without TAC if an exact TAC row is unavailable. GSM/WCDMA remain LAC-sensitive.

### Manifest format

`manifest.json` published as a GitHub Release asset:

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-09-24T00:00:00Z",
  "packs": [
    {
      "id": "TN",
      "name": "Tamil Nadu",
      "version": 1,
      "downloadUrl": "https://github.com/actionanand/localtell-data/releases/download/cell-data-2026.09.24/TN.db.gz",
      "sha256": "...",
      "compressedBytes": 123,
      "uncompressedBytes": 456
    }
  ]
}
```

Downloads are written to a temporary file, SHA-256 verified, gunzipped, SQLite `integrity_check` verified, schema checked, then atomically activated in app-private storage.

## Building an offline pack

`tools/db-builder/` contains the stable runtime schema and a builder. It expects an **already locality-enriched CSV** so the OpenCellID + OpenStreetMap enrichment pipeline can evolve independently of the Android app.

```bash
python3 tools/db-builder/build_pack.py \
  my-enriched.csv TN.db \
  --id TN --name 'Tamil Nadu' --version 1
```

This generates `TN.db` and `TN.db.gz`, printing the SHA-256 for the compressed release asset.

A tiny synthetic example is included only for validating the builder:

```bash
python3 tools/db-builder/build_pack.py \
  tools/db-builder/sample-enriched.csv /tmp/TN.db \
  --id TN --name 'Tamil Nadu' --version 1
```

**Do not treat the synthetic sample as real cell data.**

## Local build

The GitHub workflow installs Gradle 9.5.0 directly, so a Gradle wrapper binary is intentionally not committed in this starter ZIP.

In Android Studio, open the repository and use its configured Gradle JDK. From a machine with Gradle 9.5.0 and Android SDK installed:

```bash
gradle :app:assembleDebug
gradle :app:assembleRelease
gradle :app:bundleRelease
```

## What is already implemented

- serving-cell reading for GSM/WCDMA/TDSCDMA/LTE/5G NR;
- no GPS coordinate lookup;
- local multi-pack SQLite resolution;
- pack manifest retrieval, download progress, SHA-256 verification and DB validation;
- install/update/remove offline packs;
- weekly update check with WorkManager;
- journey foreground service that records locality transitions only;
- share current approximate area;
- automatic Android versionCode bump on `main-android`;
- Office-Orbit-style GitHub secret signing for APK/AAB;
- R8 mapping collection.

## Before first real device test

1. Create the GitHub repo and branch `main-android`.
2. Add the four signing secrets.
3. Create `actionanand/localtell-data` (or change `dataManifestUrl`).
4. Publish a real locality-enriched state pack and `manifest.json` as GitHub Release assets.
5. Install the generated signed APK and test cell visibility on the target SIM/device.

Cell coverage depends on the quality of the offline dataset. The UI deliberately says **approximate area** and shows `Unknown area` rather than inventing a location when a cell is absent.
