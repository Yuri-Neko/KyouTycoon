#!/usr/bin/env sh
# KyouTycoon bootstrap launcher pinned to Gradle 8.14.
set -e

GRADLE_VERSION="8.14"
if command -v gradle >/dev/null 2>&1; then
  INSTALLED_VERSION="$(gradle --version 2>/dev/null | awk '$1 == "Gradle" { print $2; exit }' || true)"
  if [ "$INSTALLED_VERSION" = "$GRADLE_VERSION" ]; then
    exec gradle "$@"
  fi
fi

USER_HOME="$GRADLE_USER_HOME"
if [ -z "$USER_HOME" ]; then USER_HOME="$HOME/.gradle"; fi
CACHE_ROOT="$USER_HOME/kyoutycoon"
INSTALL_ROOT="$CACHE_ROOT/gradle-$GRADLE_VERSION"
GRADLE_BIN="$INSTALL_ROOT/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$CACHE_ROOT"
  TEMP_ROOT="$(mktemp -d "/tmp/kyoutycoon-gradle.XXXXXX")"
  cleanup() { rm -rf "$TEMP_ROOT"; }
  trap cleanup EXIT HUP INT TERM

  URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  ARCHIVE="$TEMP_ROOT/gradle.zip"
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --retry 3 --silent --show-error "$URL" -o "$ARCHIVE"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ARCHIVE" "$URL"
  else
    echo "KyouTycoon requires curl or wget for the first Gradle bootstrap." >&2
    exit 1
  fi

  if ! command -v unzip >/dev/null 2>&1; then
    echo "KyouTycoon requires unzip for the first Gradle bootstrap." >&2
    exit 1
  fi
  unzip -q "$ARCHIVE" -d "$TEMP_ROOT"
  rm -rf "$INSTALL_ROOT"
  mv "$TEMP_ROOT/gradle-$GRADLE_VERSION" "$INSTALL_ROOT"
  trap - EXIT HUP INT TERM
  cleanup
fi

exec "$GRADLE_BIN" "$@"
