#!/usr/bin/env bash
# Build the Android app (APK).
#
# Cross-compiles translate for each ABI and places it in the app as
# jniLibs/<abi>/libtranslate.so (Android only lets apps execute files from their
# native library directory), then runs Gradle.
#
# Usage: scripts/build-android-app.sh [abi ...]      ABIs: arm64-v8a (default), x86_64
# Needs: the NDK (ANDROID_NDK), the Android SDK (ANDROID_HOME), JDK 17+, and the
# Rust targets (rustup target add aarch64-linux-android x86_64-linux-android).
# Environment: APP_VERSION (default: the version in Cargo.toml); a release key
# via ANDROID_KEYSTORE + ANDROID_KEYSTORE_PASSWORD (+ ANDROID_KEY_ALIAS), else the
# APK is signed with the debug key.
# Output: android/app/build/outputs/apk/release/app-release.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API=24

NDK="${ANDROID_NDK:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "Android NDK not found; set ANDROID_NDK to its directory" >&2
  exit 1
fi
case "$(uname -s)" in
  Linux) HOST=linux-x86_64 ;;
  Darwin) HOST=darwin-x86_64 ;; # the NDK ships universal binaries under this name
  *) echo "unsupported build host: $(uname -s) (use Linux or macOS)" >&2; exit 1 ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST/bin"

ABIS=("$@")
[ ${#ABIS[@]} -gt 0 ] || ABIS=(arm64-v8a)

JNI="$ROOT/android/app/src/main/jniLibs"
rm -rf "$JNI" # no leftovers of ABIs not asked for this time
for abi in "${ABIS[@]}"; do
  case "$abi" in
    arm64-v8a) target=aarch64-linux-android ;;
    x86_64) target=x86_64-linux-android ;;
    *) echo "unsupported ABI: $abi (use arm64-v8a or x86_64)" >&2; exit 2 ;;
  esac
  var="${target//-/_}"
  upper="$(printf '%s' "$var" | tr '[:lower:]' '[:upper:]')"
  export "CC_$var=$TC/$target$API-clang" "CXX_$var=$TC/$target$API-clang++" "AR_$var=$TC/llvm-ar"
  export "CARGO_TARGET_${upper}_LINKER=$TC/$target$API-clang"
  echo "==> translate for $abi ($target)"
  ANDROID_NDK="$NDK" ANDROID_API_LEVEL=$API \
    cargo build --release --target "$target" --manifest-path "$ROOT/Cargo.toml"
  mkdir -p "$JNI/$abi"
  "$TC/llvm-strip" -o "$JNI/$abi/libtranslate.so" "$ROOT/target/$target/release/translate"
done

echo "==> APK"
cd "$ROOT/android"
./gradlew --no-daemon assembleRelease ${APP_VERSION:+-PappVersion="$APP_VERSION"}
echo "APK: $ROOT/android/app/build/outputs/apk/release/app-release.apk"
