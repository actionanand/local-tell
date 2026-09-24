#!/usr/bin/env bash
set -euo pipefail
GRADLE_CMD="${GRADLE_CMD:-gradle}"
rm -rf releases
mkdir -p releases
"$GRADLE_CMD" clean assembleRelease bundleRelease
VERSION_NAME=$(python3 -c "import json; print(json.load(open('android-version.json'))['versionName'])")
RELEASE_BASE=$(python3 -c "import json; print(json.load(open('app-config.json'))['releaseBaseName'])")
FILE_BASE="${RELEASE_BASE}-${VERSION_NAME//./-}"
APK=$(find app/build/outputs/apk/release -name '*-release-unsigned.apk' -type f | head -1)
AAB=$(find app/build/outputs/bundle/release -name '*-release.aab' -type f | head -1)
[[ -s "$APK" && -s "$AAB" ]]
cp "$APK" "releases/${FILE_BASE}-unsigned.apk"
cp "$AAB" "releases/${FILE_BASE}-unsigned.aab"
MAPPING="app/build/outputs/mapping/release/mapping.txt"
[[ -s "$MAPPING" ]] && cp "$MAPPING" "releases/${FILE_BASE}-mapping.txt"
echo "Unsigned release files collected in releases/"
