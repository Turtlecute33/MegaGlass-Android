#!/bin/zsh
# Fetches the MEGA SDK and vcpkg at pinned commits and applies the local patches.
set -euo pipefail

SDK_REPO="https://github.com/meganz/sdk.git"
SDK_COMMIT="7e4519cc1d8c42312444482766b761c899894e6f" # v10.21.0

ROOT="${0:A:h:h}"
VENDOR="$ROOT/Vendor"
SDK_DIR="$VENDOR/mega-sdk"
VCPKG_DIR="$VENDOR/vcpkg"

mkdir -p "$VENDOR"

fetch_commit() { # repo dir commit
  if [[ ! -d "$2/.git" ]]; then
    git init -q "$2"
    git -C "$2" remote add origin "$1"
  fi
  if [[ "$(git -C "$2" rev-parse -q --verify HEAD 2>/dev/null || true)" != "$3" ]]; then
    print "Fetching ${1:t} ${3:0:10}"
    git -C "$2" fetch -q --depth 1 origin "$3"
    git -C "$2" checkout -q FETCH_HEAD
  fi
}

fetch_commit "$SDK_REPO" "$SDK_DIR" "$SDK_COMMIT"

# Local patches (see patches/). Each one applies once.
for patch in "$ROOT"/patches/mega-sdk-*.patch(N); do
  if git -C "$SDK_DIR" apply --check "$patch" 2>/dev/null; then
    git -C "$SDK_DIR" apply "$patch"
  elif ! git -C "$SDK_DIR" apply --reverse --check "$patch" 2>/dev/null; then
    print -u2 "Patch does not apply: $patch"
    exit 1
  fi
done

# vcpkg at the baseline that the SDK manifest pins.
BASELINE=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['builtin-baseline'])" "$SDK_DIR/vcpkg.json")
fetch_commit https://github.com/microsoft/vcpkg.git "$VCPKG_DIR" "$BASELINE"
if [[ ! -x "$VCPKG_DIR/vcpkg" ]]; then
  VCPKG_DISABLE_METRICS=1 "$VCPKG_DIR/bootstrap-vcpkg.sh" -disableMetrics
fi

print "Vendor ready: MEGA SDK ${SDK_COMMIT:0:10}, vcpkg ${BASELINE:0:10}"
