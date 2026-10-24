#!/bin/sh
set -eu
POCKET_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cd "$POCKET_ROOT/android"
case "${1:-debug}" in
  debug) exec ./gradlew :app:assembleDebug --console=plain ;;
  release)
    ./gradlew :app:assembleRelease --console=plain
    cd "$POCKET_ROOT"
    if [ -n "${POCKET_NODE:-}" ]; then
      "$POCKET_NODE" scripts/collect-android.mjs
    elif command -v node >/dev/null 2>&1; then
      node scripts/collect-android.mjs
    else
      /bin/zsh -c 'source "$1" || exit 1; "$POCKET_NODE" "$2"' _ "$POCKET_ROOT/scripts/find-codex-node.sh" "$POCKET_ROOT/scripts/collect-android.mjs"
    fi
    ;;
  *) echo 'Usage: build-android.sh [debug|release]' >&2; exit 1 ;;
esac
