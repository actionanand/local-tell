#!/usr/bin/env bash
set -euo pipefail
FILE="${1:-release-keystore.jks}"
[[ -f "$FILE" ]] || { echo "Keystore not found: $FILE" >&2; exit 1; }
if [[ -n "${KEYSTORE_PASSWORD:-}" ]]; then
  keytool -list -keystore "$FILE" -storepass:env KEYSTORE_PASSWORD
else
  keytool -list -keystore "$FILE"
fi
