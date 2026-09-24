# LocalTell Android build guide

LocalTell is a native Kotlin/Compose Android application. Gradle remains the build system; the small `package.json` only exposes local keystore utilities and does not participate in APK or AAB builds.

## Build files

| File | Purpose |
| --- | --- |
| `app/build.gradle.kts` | Android application configuration and dependencies |
| `android-sdk.properties` | Minimum, compile, target SDK and build-tools versions |
| `android-version.json` | Source of truth for `versionCode` and `versionName` |
| `scripts/bump-android-version.py` | Safely increments the Android version code |
| `scripts/generate-keystore.mjs` | Creates the local PKCS12 signing keystore and Base64 secret value |
| `scripts/detect-keystore-format.mjs` | Verifies a keystore with Java `keytool` |
| `.github/workflows/android-build.yml` | Lints, tests, builds, signs, and publishes release artifacts |

The application ID is `com.actionanand.localtell.app`. Do not change it after publishing to Google Play.

## Local build

Install a JDK 21, Android SDK/build tools matching `android-sdk.properties`, and Gradle 9.5. Then run:

```bash
gradle lint testDebugUnitTest
gradle clean assembleRelease bundleRelease
```

The release build outputs an unsigned APK and AAB under `app/build/outputs/`. The CI workflow collects those files in `releases/`.

## Versioning and `main-android`

`android-version.json` supplies both the monotonically increasing `versionCode` and public `versionName`. Every Play upload requires a new, higher version code.

Pushes to `main-android` trigger the Android Actions workflow. It validates configuration, runs lint/unit tests, increments and commits `versionCode`, builds APK/AAB artifacts, and then signs them only when the required secrets are available. The workflow is intentionally unchanged by the local keystore utilities.

## Create a signing keystore

On a trusted development machine with Node.js and OpenSSL installed, run:

```bash
npm run generate-keystore -- --password 'KEYSTORE_PASSWORD'
base64 -w 0 release-keystore.jks > keystore.b64.txt
npm run keystore:type
```

The generator creates `release-keystore.jks` as a **PKCS12** keystore with alias **`localtell`**, and writes its complete single-line Base64 form to `keystore.b64.txt`. It refuses to overwrite either file.

Instead of `--password`, set `KEYSTORE_PASSWORD` in the environment. When neither is available, the command prompts without echoing the password. Avoid entering a real password in shell history, logs, or source-controlled files.

`npm run keystore:type` invokes `keytool`. It uses `KEYSTORE_PASSWORD` when set; otherwise `keytool` securely prompts for the store password.

## GitHub signing secrets

Set these under **Repository Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | Complete contents of `keystore.b64.txt` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | `localtell` |
| `KEY_PASSWORD` | The same password for this PKCS12 keystore |

The workflow decodes `KEYSTORE_BASE64`, identifies the keystore type, zipaligns and signs the APK, signs the AAB, and verifies both signatures. If signing secrets are unavailable, it preserves explicitly named unsigned artifacts instead.

## Keystore safety

Never commit `release-keystore.jks`, `keystore.b64.txt`, PEM keys, certificates, or passwords. These files are ignored by Git. Store encrypted, offline backups of both the keystore and its password in separate secure locations. Losing the release key can prevent future updates to an existing Google Play listing.
