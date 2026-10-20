#!/usr/bin/env sh
# Lightweight Gradle bootstrap for this source archive (no machine-specific paths).
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=8.2
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/game-stability-gradle/gradle-$GRADLE_VERSION"
if [ ! -x "$GRADLE_HOME_DIR/bin/gradle" ]; then
  command -v curl >/dev/null 2>&1 || { echo "Need curl to download Gradle $GRADLE_VERSION." >&2; exit 1; }
  command -v unzip >/dev/null 2>&1 || { echo "Need unzip to install Gradle." >&2; exit 1; }
  BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/game-stability-gradle"
  mkdir -p "$BASE"
  ARCHIVE="$BASE/gradle-$GRADLE_VERSION-bin.zip"
  curl -fL --retry 2 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ARCHIVE"
  unzip -qo "$ARCHIVE" -d "$BASE"
fi
exec "$GRADLE_HOME_DIR/bin/gradle" -p "$APP_HOME" "$@"
command -v curl >/dev/null 2>&1 || { echo "Need Gradle $GRADLE_VERSION or curl to download it." >&2; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo "Need unzip to install Gradle." >&2; exit 1; }
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/game-stability-gradle"
mkdir -p "$BASE"
ARCHIVE="$BASE/gradle-$GRADLE_VERSION-bin.zip"
echo "Downloading Gradle $GRADLE_VERSION..."
curl -fL --retry 2 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ARCHIVE"
unzip -q "$ARCHIVE" -d "$BASE"
exec "$GRADLE_HOME_DIR/bin/gradle" -p "$APP_HOME" "$@"
