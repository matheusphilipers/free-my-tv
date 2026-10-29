#!/usr/bin/env bash
# Runs app/test (the logic that needs no TV) against the classes ./build.sh just compiled. tools/check.sh runs it.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/platforms" ]; then SDK="$d"; break; fi
  done
fi
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -f "${AJ:-}" ] || { echo "Android platform not found (see build.sh)"; exit 1; }
[ -d out/classes ] || { echo "Build first: ./build.sh"; exit 1; }
CPSEP=:; case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) CPSEP=";" ;; esac
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"; JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
CP="out/classes$CPSEP$AJ"
for j in out/libs/*.jar; do [ -f "$j" ] && CP="$CP$CPSEP$j"; done
rm -rf out/test && mkdir -p out/test
"$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp "$CP" -d out/test app/test/io/freemytv/*.java
"$JAVA" -cp "out/test$CPSEP$CP" io.freemytv.Tests
