# LocalTell

**LocalTell** is a native Android app that resolves the current locality using on-device GNSS and offline SQLite geographic packs. India and Sri Lanka are currently configured data sources; locality lookup runs locally after a pack is installed.

## Core idea

```
On-device location
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
- Android SQLite for read-only geographic packs
- SQLiteOpenHelper for local journey history
- WorkManager for weekly update checks
- Foreground service for optional journey tracking
- No backend required for runtime lookup

## Configuration

### `app-config.json`

Single source for app identity and per-country data-manifest sources:

```json
{
  "applicationId": "com.actionanand.localtell.app",
  "appName": "LocalTell",
  "releaseBaseName": "LocalTell",
  "homeAnnouncementUrl": "https://raw.githubusercontent.com/actionanand/localtell-data/master/announcements/home.json",
  "dataManifestSources": [
    {
      "countryCode": "IN",
      "countryName": "India",
      "packType": "state_ut",
      "layout": "regions",
      "manifestUrl": "https://github.com/actionanand/localtell-data/releases/download/cell-data-v3/manifest.json"
    },
    {
      "countryCode": "LK",
      "countryName": "Sri Lanka",
      "packType": "province",
      "layout": "flat",
      "manifestUrl": "https://github.com/actionanand/localtell-data/releases/download/country-lk-v1/manifest-LK.json"
    }
  ]
}
```

Change `applicationId` only before the first Play Store publication.

India retains its regions and State/UT packs. Sri Lanka displays nine Province packs directly under its country heading. India is pinned first in the UI; remaining countries follow configured `sourceOrder`, and `layout` controls regional versus flat presentation. To add a future flat country, add its manifest source with a unique country code and globally unique pack IDs; only countries with published manifests should be advertised.

Manifests use explicit release tags rather than repository-wide latest-release URLs. Legacy India manifests inherit country metadata from configuration. Country manifests can declare `countryCode`, `countryName`, and `packType`; conflicting codes/types are rejected. Failed sources produce localized refresh feedback without hiding successful sources or removing installed packs.

Canonical country, region, first-level subdivision, district and locality names remain untranslated. Localized labels surround those values. The generic schema-v3 resolver uses the same `state`/`state_code` columns for Sri Lankan provinces, with no country-specific lookup path or installed-pack migration.

Home and Easy may show one static remote announcement from the configured `homeAnnouncementUrl`. The request contains no location or user data, and failures never block location features. Explicit dismissal is stored by announcement ID; a successful Home/Easy place result suppresses the card only for the current app process. Remote actions are limited to app-defined whitelist values. See [Home and Easy announcements](documentation/home-announcements.md) for the publishing schema and cache behavior.

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

Current geographic packs use schema-v3 (`pack_meta`, `place`, `place_geometry`, `place_geometry_rtree`), with a level-4 pack boundary, locality polygon lookup, and nearest-place fallback. Existing India DB filenames and `offline_packs` persistence remain compatible. The following schema-v2 cell/tower format is retained for legacy pack compatibility:

```sql
tower_site(
  id, source_site_id, latitude, longitude,
  area_name, district, state, address, source
)

cell_lookup(
  mcc, mnc, radio, area_code, cell_id,
  tower_site_id, pci, arfcn, confidence, last_seen
)
```

Each cell references a physical `tower_site`; PCI and ARFCN are supporting diagnostics and do not participate in the primary match. The app first performs an exact identity match. For LTE/5G only, it can fall back to the PLMN-scoped ECI/NCI without TAC if an exact TAC row is unavailable. GSM/WCDMA remain LAC-sensitive. Existing schema-v1 packs remain readable during the pre-production transition.

### Manifest format

`manifest.json` published as a GitHub Release asset:

```json
{
  "schemaVersion": 2,
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

`tools/db-builder/` contains the stable runtime schema and a builder. It accepts a cell-to-tower CSV and a tower-site CSV, so enrichment can evolve independently of the Android app.

```bash
python3 tools/db-builder/build_pack.py \
  my-cells.csv TN.db --towers my-towers.csv \
  --id TN --name 'Tamil Nadu' --version 1
```

This generates `TN.db` and `TN.db.gz`, printing the SHA-256 for the compressed release asset.

A tiny synthetic example is included only for validating the builder:

```bash
python3 tools/db-builder/build_pack.py \
  tools/db-builder/sample-enriched.csv /tmp/TN.db \
  --towers tools/db-builder/sample-towers.csv \
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
3. Verify the configured sources in `dataManifestSources` against the published releases in `actionanand/localtell-data`.
4. Publish a real locality-enriched state pack and `manifest.json` as GitHub Release assets.
5. Install the generated signed APK and test cell visibility on the target SIM/device.

Cell coverage depends on the quality of the offline dataset. The UI deliberately says **approximate area** and shows `Unknown area` rather than inventing a location when a cell is absent.
