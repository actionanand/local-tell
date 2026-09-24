#!/usr/bin/env bash
set -euo pipefail
OUTPUT="release-keystore.jks"
ALIAS="localtell"
PASSWORD="${KEYSTORE_PASSWORD:-${1:-}}"
if [[ -z "$PASSWORD" ]]; then read -rsp "Enter keystore password: " PASSWORD; echo; fi
[[ -n "$PASSWORD" ]] || { echo "Password cannot be empty" >&2; exit 1; }
[[ ! -e "$OUTPUT" ]] || { echo "$OUTPUT already exists; refusing to overwrite signing identity" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }
KEY=$(mktemp); CERT=$(mktemp); trap 'rm -f "$KEY" "$CERT"' EXIT
openssl genrsa -out "$KEY" 2048 >/dev/null 2>&1
openssl req -new -x509 -key "$KEY" -out "$CERT" -days 36500 -subj '/CN=LocalTell/OU=Mobile/O=LocalTell/C=IN' >/dev/null 2>&1
OPENSSL_PASS="$PASSWORD" openssl pkcs12 -export -in "$CERT" -inkey "$KEY" -out "$OUTPUT" -name "$ALIAS" -passout env:OPENSSL_PASS
printf 'Created %s\nAlias: %s\nFormat: PKCS12\n' "$OUTPUT" "$ALIAS"
echo "Encode for GitHub: base64 -w 0 $OUTPUT > keystore.b64.txt"
