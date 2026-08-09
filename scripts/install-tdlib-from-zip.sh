#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'Usage: %s /path/to/tdlib.zip EXPECTED_SHA256\n' "$0" >&2
  exit 2
fi

ZIP_PATH=$1
EXPECTED_SHA256=$2
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
PROJECT_ROOT=${TDLIB_DEST_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd -P)}
OVERWRITE=${TDLIB_OVERWRITE:-0}

if [[ ! -f "$ZIP_PATH" ]]; then
  printf 'Error: TDLib archive not found: %s\n' "$ZIP_PATH" >&2
  exit 1
fi

if [[ ! "$EXPECTED_SHA256" =~ ^[0-9A-Fa-f]{64}$ ]]; then
  printf 'Error: EXPECTED_SHA256 must be exactly 64 hexadecimal characters.\n' >&2
  exit 1
fi

for command_name in unzip install mktemp od tr cp mv rm mkdir dirname; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf 'Error: required command not found: %s\n' "$command_name" >&2
    exit 1
  fi
done

EXPECTED_SHA256=$(printf '%s' "$EXPECTED_SHA256" | tr '[:upper:]' '[:lower:]')

if command -v sha256sum >/dev/null 2>&1; then
  read -r ACTUAL_SHA256 _ < <(sha256sum "$ZIP_PATH")
elif command -v shasum >/dev/null 2>&1; then
  read -r ACTUAL_SHA256 _ < <(shasum -a 256 "$ZIP_PATH")
else
  printf 'Error: sha256sum or shasum is required.\n' >&2
  exit 1
fi
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
  printf 'Error: TDLib archive SHA-256 does not match the trusted expected digest.\n' >&2
  exit 1
fi

MAIN_PARENT="$PROJECT_ROOT/tdlib/src"
MAIN_DIR="$MAIN_PARENT/main"
CLIENT_REL="java/org/drinkless/tdlib/Client.java"
API_REL="java/org/drinkless/tdlib/TdApi.java"
JNI_REL="jniLibs/arm64-v8a/libtdjni.so"

if [[ "$OVERWRITE" != "1" ]] && [[ -e "$MAIN_DIR/$CLIENT_REL" || -e "$MAIN_DIR/$API_REL" || -e "$MAIN_DIR/$JNI_REL" ]]; then
  printf 'Error: TDLib artifacts already exist. Set TDLIB_OVERWRITE=1 to replace them.\n' >&2
  exit 1
fi

mkdir -p "$MAIN_PARENT"
EXTRACT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/floating-voice-tdlib.XXXXXX")
TRANSACTION_DIR=$(mktemp -d "$MAIN_PARENT/.tdlib-install.XXXXXX")
STAGED_MAIN="$TRANSACTION_DIR/main"
PREVIOUS_MAIN="$TRANSACTION_DIR/previous-main"
mkdir -p "$STAGED_MAIN"

HAD_PREVIOUS=0
COMMITTED=0
cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$status" -ne 0 && "$HAD_PREVIOUS" -eq 1 && "$COMMITTED" -eq 0 && -d "$PREVIOUS_MAIN" ]]; then
    rm -rf "$MAIN_DIR"
    mv "$PREVIOUS_MAIN" "$MAIN_DIR" || true
  fi
  rm -rf "$EXTRACT_DIR" "$TRANSACTION_DIR"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

unzip -p "$ZIP_PATH" tdlib/java/org/drinkless/tdlib/Client.java > "$EXTRACT_DIR/Client.java"
unzip -p "$ZIP_PATH" tdlib/java/org/drinkless/tdlib/TdApi.java > "$EXTRACT_DIR/TdApi.java"
unzip -p "$ZIP_PATH" tdlib/libs/arm64-v8a/libtdjni.so > "$EXTRACT_DIR/libtdjni.so"

for artifact in "$EXTRACT_DIR/Client.java" "$EXTRACT_DIR/TdApi.java" "$EXTRACT_DIR/libtdjni.so"; do
  if [[ ! -s "$artifact" ]]; then
    printf 'Error: required artifact is missing or empty in %s\n' "$ZIP_PATH" >&2
    exit 1
  fi
done

ELF_HEADER=$(LC_ALL=C od -An -tx1 -N20 "$EXTRACT_DIR/libtdjni.so" | tr -d ' \n')
if [[ "${ELF_HEADER:0:12}" != "7f454c460201" || "${ELF_HEADER:36:4}" != "b700" ]]; then
  printf 'Error: libtdjni.so is not a 64-bit little-endian AArch64 ELF.\n' >&2
  exit 1
fi

if [[ -d "$MAIN_DIR" ]]; then
  cp -R "$MAIN_DIR/." "$STAGED_MAIN/"
fi
mkdir -p "$STAGED_MAIN/$(dirname "$CLIENT_REL")" "$STAGED_MAIN/$(dirname "$JNI_REL")"
install -m 0644 "$EXTRACT_DIR/Client.java" "$STAGED_MAIN/$CLIENT_REL"
install -m 0644 "$EXTRACT_DIR/TdApi.java" "$STAGED_MAIN/$API_REL"
install -m 0644 "$EXTRACT_DIR/libtdjni.so" "$STAGED_MAIN/$JNI_REL"

if [[ -d "$MAIN_DIR" ]]; then
  HAD_PREVIOUS=1
  mv "$MAIN_DIR" "$PREVIOUS_MAIN"
fi
mv "$STAGED_MAIN" "$MAIN_DIR"
COMMITTED=1

printf 'Installed verified TDLib Java/JNI artifacts under %s/tdlib/src/main\n' "$PROJECT_ROOT"
printf 'Verified TDLib archive SHA-256: %s\n' "$ACTUAL_SHA256"
