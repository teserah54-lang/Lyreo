#!/usr/bin/env bash
# ============================================================
# Lyreon — Android SDK bootstrap untuk GitHub Codespaces
# Menginstal: cmdline-tools, platform-tools, ANDROID platform 36,
# build-tools 36.0.0, lalu membangun Gradle wrapper jar.
# ============================================================
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-/workspaces/android-sdk}"
WORKDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> Lyreon Codespaces setup"
echo "    Workspace : $WORKDIR"
echo "    SDK root  : $SDK_ROOT"

mkdir -p "$SDK_ROOT/cmdline-tools"
cd /tmp

if [ ! -d "$SDK_ROOT/cmdline-tools/latest" ]; then
  echo "==> Mengunduh Android command line tools…"
  curl -fsSL -o cmdline-tools.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  rm -rf /tmp/cmdline-tools
  unzip -q cmdline-tools.zip -d /tmp
  mkdir -p "$SDK_ROOT/cmdline-tools/latest"
  cp -r /tmp/cmdline-tools/* "$SDK_ROOT/cmdline-tools/latest/"
  rm -rf /tmp/cmdline-tools cmdline-tools.zip
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$PATH:$SDK_ROOT/platform-tools:$SDK_ROOT/cmdline-tools/latest/bin"

echo "==> Menerima lisensi SDK…"
yes | sdkmanager --licenses >/dev/null 2>&1 || true

echo "==> Menginstal komponen SDK (platform 36, build-tools, platform-tools)…"
sdkmanager \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "cmdline-tools;latest" >/dev/null

cd "$WORKDIR"
echo "==> Menulis local.properties…"
echo "sdk.dir=$SDK_ROOT" > local.properties

GV=$(grep -oP 'gradle-\K[0-9]+\.[0-9]+(\.[0-9]+)?(?=-bin\.zip)' "$WORKDIR/gradle/wrapper/gradle-wrapper.properties")
echo "==> Menghasilkan gradle-wrapper.jar (Gradle $GV)…"
if command -v gradle >/dev/null 2>&1; then
  gradle wrapper --gradle-version "$GV" --distribution-type bin
else
  # Fallback: unduh distribusi Gradle langsung untuk bootstrap sekali
  curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GV}-bin.zip"
  rm -rf "/tmp/gradle-${GV}"
  unzip -q /tmp/gradle.zip -d /tmp
  "/tmp/gradle-${GV}/bin/gradle" wrapper --gradle-version "$GV" --distribution-type bin
  rm -rf /tmp/gradle.zip "/tmp/gradle-${GV}"
fi

echo ""
echo "============================================================"
echo "  SETUP SELESAI ✔"
echo "  Build APK debug   : ./gradlew :app:assembleDebug"
echo "  Output            : app/build/outputs/apk/debug/app-debug.apk"
echo "============================================================"
