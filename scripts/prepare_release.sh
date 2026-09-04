#!/usr/bin/env bash

set -euo pipefail

readonly EXPECTED_CERT_SHA256="d151a261c0cb7386f9ff8e29915538c37f4c880e7f288e368b90fcd3dbeced59"
readonly BUILD_TOOLS_VERSION="35.0.0"

usage() {
  echo "Usage: $0 <signed-apk> <output-dir> [expected-tag]" >&2
}

resolve_sdk_root() {
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    printf '%s\n' "$ANDROID_HOME"
    return
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    printf '%s\n' "$ANDROID_SDK_ROOT"
    return
  fi
  if [[ -f local.properties ]]; then
    sed -n 's/^sdk\.dir=//p' local.properties | head -n 1
    return
  fi
  return 1
}

resolve_apkanalyzer() {
  local sdk_root="$1"
  local candidate
  if command -v apkanalyzer >/dev/null 2>&1; then
    command -v apkanalyzer
    return
  fi
  for candidate in \
    "$sdk_root/cmdline-tools/latest/bin/apkanalyzer" \
    "$sdk_root/cmdline-tools/latest-3/bin/apkanalyzer"; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  return 1
}

resolve_apksigner() {
  local sdk_root="$1"
  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return
  fi
  local candidate="$sdk_root/build-tools/$BUILD_TOOLS_VERSION/apksigner"
  [[ -x "$candidate" ]] || return 1
  printf '%s\n' "$candidate"
}

normalize_digest() {
  tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
  usage
  exit 64
fi

readonly APK_PATH="$1"
readonly OUTPUT_DIR="$2"
readonly EXPECTED_TAG="${3:-}"

if [[ ! -f "$APK_PATH" ]]; then
  echo "Signed APK not found: $APK_PATH" >&2
  exit 66
fi

SDK_ROOT="$(resolve_sdk_root)" || {
  echo "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
  exit 69
}
readonly SDK_ROOT
APKANALYZER="$(resolve_apkanalyzer "$SDK_ROOT")" || {
  echo "apkanalyzer not found in Android SDK." >&2
  exit 69
}
readonly APKANALYZER
APKSIGNER="$(resolve_apksigner "$SDK_ROOT")" || {
  echo "apksigner $BUILD_TOOLS_VERSION not found in Android SDK." >&2
  exit 69
}
readonly APKSIGNER

SIGNING_REPORT="$("$APKSIGNER" verify --verbose --print-certs "$APK_PATH")"
readonly SIGNING_REPORT
printf '%s\n' "$SIGNING_REPORT"

ACTUAL_CERT_SHA256="$(
  printf '%s\n' "$SIGNING_REPORT" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' |
    head -n 1 |
    normalize_digest
)"
readonly ACTUAL_CERT_SHA256
if [[ -z "$ACTUAL_CERT_SHA256" || "$ACTUAL_CERT_SHA256" != "$EXPECTED_CERT_SHA256" ]]; then
  echo "Release certificate fingerprint does not match the Void Music signing identity." >&2
  exit 65
fi

VERSION_NAME="$($APKANALYZER manifest version-name "$APK_PATH")"
VERSION_CODE="$($APKANALYZER manifest version-code "$APK_PATH")"
readonly VERSION_NAME VERSION_CODE
if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
  echo "Could not read APK version metadata." >&2
  exit 65
fi

if [[ -n "$EXPECTED_TAG" && "$EXPECTED_TAG" != "v$VERSION_NAME" ]]; then
  echo "Release tag $EXPECTED_TAG does not match APK version v$VERSION_NAME." >&2
  exit 65
fi

mkdir -p "$OUTPUT_DIR"
readonly APK_NAME="void-music-$VERSION_NAME-arm64-v8a.apk"
install -m 0644 "$APK_PATH" "$OUTPUT_DIR/$APK_NAME"

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$OUTPUT_DIR" && sha256sum "$APK_NAME" > SHA256SUMS)
else
  (cd "$OUTPUT_DIR" && shasum -a 256 "$APK_NAME" > SHA256SUMS)
fi

COMMIT_SHA="${GITHUB_SHA:-$(git rev-parse HEAD)}"
readonly COMMIT_SHA
{
  printf 'product=Void Music\n'
  printf 'channel=internal-test\n'
  printf 'versionName=%s\n' "$VERSION_NAME"
  printf 'versionCode=%s\n' "$VERSION_CODE"
  printf 'commit=%s\n' "$COMMIT_SHA"
  printf 'certificateSha256=%s\n' "$EXPECTED_CERT_SHA256"
  printf 'apk=%s\n' "$APK_NAME"
} > "$OUTPUT_DIR/BUILD-INFO.txt"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    printf 'version_name=%s\n' "$VERSION_NAME"
    printf 'version_code=%s\n' "$VERSION_CODE"
    printf 'apk_name=%s\n' "$APK_NAME"
  } >> "$GITHUB_OUTPUT"
fi

echo "Prepared Void Music v$VERSION_NAME (versionCode $VERSION_CODE)."
