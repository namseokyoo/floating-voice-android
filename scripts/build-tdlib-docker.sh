#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd -P)
readonly TDLIB_COMMIT=022d60202e446ad1287b9fb68e687c8a0760788b
readonly ANDROID_NDK_VERSION=28.2.13676358
readonly OPENSSL_VERSION=OpenSSL_1_1_1w
DOCKER_COMMAND=${DOCKER_COMMAND:-docker}

for command_name in git "$DOCKER_COMMAND" unzip mktemp python3; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf 'Error: required command not found: %s\n' "$command_name" >&2
    exit 1
  fi
done

if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
  printf 'Error: sha256sum or shasum is required.\n' >&2
  exit 1
fi

if ! "$DOCKER_COMMAND" info >/dev/null 2>&1; then
  printf 'Error: Docker is not running or is not accessible.\n' >&2
  exit 1
fi

mkdir -p "$PROJECT_ROOT/tdlib-dist"
WORK_DIR=$(mktemp -d "$PROJECT_ROOT/tdlib-dist/build.XXXXXX")
SOURCE_DIR="$WORK_DIR/source"
OUTPUT_DIR="$WORK_DIR/output"
mkdir -p "$OUTPUT_DIR"

on_error() {
  printf 'TDLib build failed. The retained work directory is: %s\n' "$WORK_DIR" >&2
}
trap on_error ERR

printf 'Fetching TDLib source revision %s...\n' "$TDLIB_COMMIT"
git init -q "$SOURCE_DIR"
git -C "$SOURCE_DIR" remote add origin https://github.com/tdlib/td.git
git -C "$SOURCE_DIR" fetch -q --depth 1 origin "$TDLIB_COMMIT"
git -C "$SOURCE_DIR" checkout -q --detach FETCH_HEAD
ACTUAL_COMMIT=$(git -C "$SOURCE_DIR" rev-parse HEAD)
if [[ "$ACTUAL_COMMIT" != "$TDLIB_COMMIT" ]]; then
  printf 'Error: expected TDLib %s but checked out %s\n' "$TDLIB_COMMIT" "$ACTUAL_COMMIT" >&2
  exit 1
fi

DOCKERFILE="$SOURCE_DIR/example/android/Dockerfile"
python3 - "$DOCKERFILE" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
needle = "ARG TDLIB_INTERFACE=Java\n"
if text.count(needle) != 1:
    raise SystemExit("Error: pinned TDLib Dockerfile layout changed; refusing to patch compiler flags.")
prefix_flags = (
    "-ffile-prefix-map=/home/td=/usr/src/tdlib "
    "-fdebug-prefix-map=/home/td=/usr/src/tdlib "
    "-fmacro-prefix-map=/home/td=/usr/src/tdlib"
)
replacement = needle + f'ENV CFLAGS="{prefix_flags}"\n' + f'ENV CXXFLAGS="{prefix_flags}"\n'
path.write_text(text.replace(needle, replacement))
PY

printf 'Building TDLib with the pinned upstream Dockerfile and sanitized source paths...\n'
"$DOCKER_COMMAND" build \
  --build-arg "COMMIT_HASH=$TDLIB_COMMIT" \
  --build-arg "ANDROID_NDK_VERSION=$ANDROID_NDK_VERSION" \
  --build-arg "OPENSSL_VERSION=$OPENSSL_VERSION" \
  --build-arg "TDLIB_INTERFACE=Java" \
  --build-arg "ANDROID_STL=c++_static" \
  --output "type=local,dest=$OUTPUT_DIR" \
  "$SOURCE_DIR/example/android"

TDLIB_ZIP="$OUTPUT_DIR/tdlib.zip"
if [[ ! -s "$TDLIB_ZIP" ]]; then
  printf 'Error: the Docker build did not produce %s\n' "$TDLIB_ZIP" >&2
  exit 1
fi

python3 - "$TDLIB_ZIP" <<'PY'
from pathlib import Path
import sys
import zipfile

archive = Path(sys.argv[1])
forbidden = (b"/Users/", b"/Volumes/", b"/home/td/")
with zipfile.ZipFile(archive) as bundle:
    libraries = [name for name in bundle.namelist() if name.endswith("/libtdjni.so")]
    if not libraries:
        raise SystemExit("Error: TDLib archive has no libtdjni.so to scan.")
    for name in libraries:
        payload = bundle.read(name)
        if any(marker in payload for marker in forbidden):
            raise SystemExit(
                "Error: TDLib native artifact contains an unsanitized private/build source path."
            )
print(f"TDLib native path scan passed for {len(libraries)} ABI artifact(s).")
PY

if command -v sha256sum >/dev/null 2>&1; then
  read -r ARCHIVE_SHA _ < <(sha256sum "$TDLIB_ZIP")
else
  read -r ARCHIVE_SHA _ < <(shasum -a 256 "$TDLIB_ZIP")
fi

PROVENANCE_FILE="$OUTPUT_DIR/floating-voice-tdlib-provenance.txt"
{
  printf 'tdlib_commit=%s\n' "$ACTUAL_COMMIT"
  printf 'android_ndk_version=%s\n' "$ANDROID_NDK_VERSION"
  printf 'openssl_version=%s\n' "$OPENSSL_VERSION"
  printf 'tdlib_interface=Java\n'
  printf 'android_stl=c++_static\n'
  printf 'compiler_prefix_map=/home/td=/usr/src/tdlib\n'
  printf 'native_private_path_scan=passed\n'
  printf 'tdlib_zip_sha256=%s\n' "$ARCHIVE_SHA"
} > "$PROVENANCE_FILE"

TDLIB_OVERWRITE=${TDLIB_OVERWRITE:-0} \
  "$SCRIPT_DIR/install-tdlib-from-zip.sh" "$TDLIB_ZIP" "$ARCHIVE_SHA"

"$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" :tdlib:verifyTdlibArtifacts

trap - ERR
printf 'TDLib source commit: %s\n' "$ACTUAL_COMMIT"
printf 'TDLib archive: %s\n' "$TDLIB_ZIP"
printf 'TDLib archive SHA-256: %s\n' "$ARCHIVE_SHA"
printf 'TDLib provenance: %s\n' "$PROVENANCE_FILE"
printf 'Work directory retained for audit: %s\n' "$WORK_DIR"
