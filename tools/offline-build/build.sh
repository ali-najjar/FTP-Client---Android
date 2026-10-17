#!/usr/bin/env bash
# Builds Glass FTP into a signed APK *without* an Android SDK or Google Maven.
#
# Everything is fetched from Maven Central and npm (pinned + checksummed):
#   - android.jar (API 34), d8, apksigner  -> npm "@drxiaozhi/minapk"
#   - aapt2 (linux x64)                    -> npm "aaptjs3"
#   - Kotlin compiler + app libraries      -> Maven Central
#
# Usage: tools/offline-build/build.sh [output.apk]
# Requires: bash, curl, java (17+), python3, unzip, zip
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/tools/offline-build"
CACHE="${GLASSFTP_CACHE:-$HOME/.cache/glassftp-offline}"
OUT_APK="${1:-$ROOT/dist/GlassFTP.apk}"
BUILD="$ROOT/build/offline"

APP_ID="com.glassftp"
VERSION_CODE=1
VERSION_NAME="1.0.0"
MIN_SDK=26
TARGET_SDK=34

KOTLIN=2.0.21
COROUTINES=1.9.0
COMMONS_NET=3.11.1
JSCH=0.2.20

MAVEN="https://repo1.maven.org/maven2"
NPM="https://registry.npmjs.org"

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

fetch() { # url dest
  local url="$1" dest="$2"
  [ -s "$dest" ] && return 0
  mkdir -p "$(dirname "$dest")"
  for i in 1 2 3 4 5 6; do
    if curl -sSfL "$url" -o "$dest.part"; then mv "$dest.part" "$dest"; return 0; fi
    sleep $((i * 2))
  done
  echo "failed to download $url" >&2
  exit 1
}

maven() { # group:artifact:version -> echoes path
  local g a v
  IFS=: read -r g a v <<<"$1"
  local rel="${g//.//}/$a/$v/$a-$v.jar"
  fetch "$MAVEN/$rel" "$CACHE/m2/$rel"
  echo "$CACHE/m2/$rel"
}

check_sha() { # file sha256
  echo "$2  $1" | sha256sum -c --quiet - || { echo "checksum mismatch: $1" >&2; exit 1; }
}

# ---------------------------------------------------------------- tools
log "Fetching build tools"
TOOLS="$CACHE/tools"
if [ ! -f "$TOOLS/.ok" ]; then
  rm -rf "$TOOLS" && mkdir -p "$TOOLS/x"
  fetch "$NPM/@drxiaozhi/minapk/-/minapk-0.4.0.tgz" "$CACHE/npm/minapk-0.4.0.tgz"
  fetch "$NPM/aaptjs3/-/aaptjs3-2.0.2.tgz" "$CACHE/npm/aaptjs3-2.0.2.tgz"
  tar xzf "$CACHE/npm/minapk-0.4.0.tgz" -C "$TOOLS/x" package/tools/android.jar package/tools/d8.jar package/tools/apksigner.jar
  tar xzf "$CACHE/npm/aaptjs3-2.0.2.tgz" -C "$TOOLS/x" package/bin/x64/linux/aapt2
  mv "$TOOLS"/x/package/tools/* "$TOOLS"/x/package/bin/x64/linux/aapt2 "$TOOLS/"
  rm -rf "$TOOLS/x"
  chmod +x "$TOOLS/aapt2"
  check_sha "$TOOLS/android.jar" 6cea1df3efb77103ac3e2beb9bf4718964b0e0869ab16d39d29d5cbae1c147ad
  check_sha "$TOOLS/d8.jar" d43c8a94c9b1f1da1a7cc49c32b81e8cee1708b37ee8b530a81a0688222b42c0
  check_sha "$TOOLS/apksigner.jar" eefdd6aed9db9fb849e4c98a50d8741e19d1b674ba6547220bcb9c3ed152123a
  touch "$TOOLS/.ok"
fi
ANDROID_JAR="$TOOLS/android.jar"
AAPT2="$TOOLS/aapt2"

KOTLINC_CP=""
for c in org.jetbrains.kotlin:kotlin-compiler-embeddable:$KOTLIN \
         org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN \
         org.jetbrains.kotlin:kotlin-script-runtime:$KOTLIN \
         org.jetbrains.kotlin:kotlin-reflect:1.6.10 \
         org.jetbrains.kotlin:kotlin-daemon-embeddable:$KOTLIN \
         org.jetbrains.intellij.deps:trove4j:1.0.20200330 \
         org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4 \
         org.jetbrains:annotations:13.0; do
  KOTLINC_CP="$KOTLINC_CP:$(maven "$c")"
done

LIBS=()
for c in org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN \
         org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$COROUTINES \
         commons-net:commons-net:$COMMONS_NET \
         com.github.mwiede:jsch:$JSCH; do
  LIBS+=("$(maven "$c")")
done
LIBS_CP="$(IFS=:; echo "${LIBS[*]}")"
ASM="$(maven org.ow2.asm:asm:9.7.1)"

# ---------------------------------------------------------------- resources
rm -rf "$BUILD" && mkdir -p "$BUILD"/{gen,classes,dex,libs}
SRC="$ROOT/app/src/main"

log "Compiling resources (aapt2)"
"$AAPT2" compile --dir "$SRC/res" -o "$BUILD/res.zip"

sed "s#<manifest #<manifest package=\"$APP_ID\" #" "$SRC/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"

log "Linking resources"
"$AAPT2" link -I "$ANDROID_JAR" \
  --manifest "$BUILD/AndroidManifest.xml" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name "$VERSION_NAME" \
  --java "$BUILD/gen" --auto-add-overlay \
  -o "$BUILD/base.apk" "$BUILD/res.zip"

# ---------------------------------------------------------------- code
log "Compiling R.java"
javac -nowarn --release 8 -d "$BUILD/classes" $(find "$BUILD/gen" -name '*.java') 2>&1 | grep -v '^Picked up' || true

log "Compiling Kotlin"
java -Xmx3g -cp "${KOTLINC_CP#:}" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-jdk -no-stdlib -no-reflect -jvm-target 1.8 -nowarn \
  -classpath "$ANDROID_JAR:$LIBS_CP:$BUILD/classes" \
  -d "$BUILD/classes" \
  $(find "$SRC/java" -name '*.kt') 2>&1 | grep -v '^Picked up' || true
[ -n "$(find "$BUILD/classes/${APP_ID//.//}" -name 'MainActivity.class')" ] || { echo "Kotlin compilation failed" >&2; exit 1; }

log "Dexing (d8)"
# Drop META-INF (multi-release duplicates) and MethodParameters attributes,
# which the bundled d8 cannot handle.
for j in "${LIBS[@]}"; do
  java -cp "$ASM" "$HERE/StripParams.java" "$j" "$BUILD/libs/$(basename "$j")" 2>&1 | grep -v '^Picked up' || true
done
(cd "$BUILD/classes" && zip -q -r "$BUILD/app-classes.jar" .)
java -Xmx2g -cp "$TOOLS/d8.jar" com.android.tools.r8.D8 --release --min-api $MIN_SDK \
  --lib "$ANDROID_JAR" --output "$BUILD/dex" \
  "$BUILD/app-classes.jar" "$BUILD"/libs/*.jar > "$BUILD/d8.log" 2>&1 || true
# (Kotlin-metadata warnings from this d8 version are harmless: no shrinking.)
[ -f "$BUILD/dex/classes.dex" ] || { cat "$BUILD/d8.log" >&2; echo "d8 failed" >&2; exit 1; }

# ---------------------------------------------------------------- package
log "Packaging"
cp "$BUILD/base.apk" "$BUILD/unaligned.apk"
(cd "$BUILD/dex" && zip -q "$BUILD/unaligned.apk" classes*.dex)
python3 -I "$HERE/zipalign.py" "$BUILD/unaligned.apk" "$BUILD/aligned.apk"

log "Signing"
mkdir -p "$(dirname "$OUT_APK")"
java -jar "$TOOLS/apksigner.jar" sign \
  --ks "$HERE/glassftp-debug.jks" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias glassftp --min-sdk-version $MIN_SDK --v4-signing-enabled false \
  --out "$OUT_APK" "$BUILD/aligned.apk" 2>&1 | grep -v '^Picked up' || true
java -jar "$TOOLS/apksigner.jar" verify -v "$OUT_APK" 2>&1 | grep -E 'Verified using v2 scheme \(APK Signature Scheme v2\): true' >/dev/null \
  || { echo "APK signature verification failed" >&2; exit 1; }

log "Done: $OUT_APK ($(du -h "$OUT_APK" | cut -f1))"
