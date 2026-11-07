#!/usr/bin/env bash
# Installs the build and analysis tools at the versions pinned in .claude/toolchain.json.
# Every download is checked against a SHA-256 (Google's published hash for the command-line
# tools, the GitHub release asset digest for the rest). Safe to re-run.
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/android-sdk}"
TOOLS="${HEVAL_TOOLS:-/opt/heval-tools}"
DL="$TOOLS/downloads"

sudo mkdir -p "$SDK" "$TOOLS/bin" "$DL"
sudo chown -R "$(id -u):$(id -g)" "$SDK" "$TOOLS"
command -v unzip >/dev/null || { sudo apt-get update -q && sudo apt-get install -yq unzip; }

# fetch <url> <sha256> <file name>: download into $DL unless a verified copy is already there.
fetch() {
  local url="$1" sha="$2" out="$DL/$3"
  if [ -f "$out" ] && echo "$sha  $out" | sha256sum -c --quiet - 2>/dev/null; then return; fi
  echo "Downloading $3"
  curl -fsSL --retry 3 -o "$out.part" "$url"
  echo "$sha  $out.part" | sha256sum -c --quiet -
  mv "$out.part" "$out"
}

# jar_tool <command name> <jar>: a small wrapper so the jar runs like a normal command.
jar_tool() {
  printf '#!/bin/sh\nexec java -jar "%s" "$@"\n' "$2" > "$TOOLS/bin/$1"
  chmod +x "$TOOLS/bin/$1"
}

# Android SDK: command-line tools 15859902 (apkanalyzer, sdkmanager), build-tools 36.0.0
# (apksigner, zipalign, aapt2, d8) and the platforms the extensions compile against.
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  fetch https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip \
    4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583 cmdline-tools.zip
  rm -rf "$DL/cmdline-tools" && unzip -q "$DL/cmdline-tools.zip" -d "$DL"
  mkdir -p "$SDK/cmdline-tools" && rm -rf "$SDK/cmdline-tools/latest"
  mv "$DL/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
# The Android SDK licences, as accepted on the owner's own machine.
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK" "build-tools;36.0.0" "platforms;android-35" "platforms;android-36" >/dev/null

# jadx 1.5.6
fetch https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip \
  545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974 jadx-1.5.6.zip
if [ ! -x "$TOOLS/jadx-1.5.6/bin/jadx" ]; then
  rm -rf "$TOOLS/jadx-1.5.6" && unzip -q "$DL/jadx-1.5.6.zip" -d "$TOOLS/jadx-1.5.6"
  chmod +x "$TOOLS/jadx-1.5.6/bin/"*
fi
ln -sf "$TOOLS/jadx-1.5.6/bin/jadx" "$TOOLS/bin/jadx"

# apktool 3.0.3, bundletool 1.18.3, APKEditor 1.4.9, Morphe CLI 1.19.0 (patcher 2.0.0)
fetch https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar \
  dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423 apktool-3.0.3.jar
fetch https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar \
  a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29 bundletool-1.18.3.jar
fetch https://github.com/REAndroid/APKEditor/releases/download/V1.4.9/APKEditor-1.4.9.jar \
  a9cd40df818845456be6d696de6110c89edf4b0a0580cb83438ed6b25a366e67 APKEditor-1.4.9.jar
fetch https://github.com/MorpheApp/morphe-desktop/releases/download/v1.19.0/morphe-desktop-1.19.0-all.jar \
  deb5d931f3899b20a119637473d338c52585f0c561b5d8b2856b916fe90163b5 morphe-cli-1.19.0.jar
jar_tool apktool "$DL/apktool-3.0.3.jar"
jar_tool bundletool "$DL/bundletool-1.18.3.jar"
jar_tool apkeditor "$DL/APKEditor-1.4.9.jar"
jar_tool morphe-cli "$DL/morphe-cli-1.19.0.jar"

# remoteEnv's PATH only reaches the editor's terminals, not `gh codespace ssh` sessions, so link
# every command into /usr/local/bin as well.
sudo ln -sf "$TOOLS/bin/"* "$SDK/cmdline-tools/latest/bin/apkanalyzer"   "$SDK/build-tools/36.0.0/apksigner" "$SDK/build-tools/36.0.0/zipalign"   "$SDK/build-tools/36.0.0/aapt2" /usr/local/bin/

# Morphe registry credentials. The app.morphe.patches plugin needs a user and a token: the
# gpr.user / gpr.key Gradle properties, else GITHUB_ACTOR / GITHUB_TOKEN. A codespace sets
# GITHUB_TOKEN but not GITHUB_ACTOR, so gpr.user is always written (GitHub Packages accepts any
# user name with a valid token). gpr.key is written only when the MORPHE_PACKAGES_TOKEN
# Codespaces secret exists (a read:packages-only token, see README.md); otherwise the codespace's
# own GITHUB_TOKEN is used. The file lives in the container's ~/.gradle, never in the repo.
GRADLE_PROPS="$HOME/.gradle/gradle.properties"
mkdir -p "$HOME/.gradle" && touch "$GRADLE_PROPS" && chmod 600 "$GRADLE_PROPS"
sed -i '/^gpr\.user=/d;/^gpr\.key=/d' "$GRADLE_PROPS"
echo "gpr.user=${GITHUB_USER:-codespace}" >> "$GRADLE_PROPS"
if [ -n "${MORPHE_PACKAGES_TOKEN:-}" ]; then
  echo "gpr.key=$MORPHE_PACKAGES_TOKEN" >> "$GRADLE_PROPS"
  echo "Morphe registry: using the MORPHE_PACKAGES_TOKEN secret"
else
  echo "Morphe registry: using the codespace GITHUB_TOKEN"
fi

# Gradle wrapper distribution.
./gradlew --version -q >/dev/null

echo
echo "Tools ready: java $(java -version 2>&1 | head -n1 | cut -d'"' -f2), jadx, apktool, bundletool, apkeditor, morphe-cli,"
echo "apkanalyzer, apksigner, zipalign, aapt2. Next steps: .devcontainer/README.md"
