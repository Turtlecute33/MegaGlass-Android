#!/bin/zsh
# Builds libmega.so and the SWIG Java bindings for arm64-v8a and copies them into the :sdk module.
# Run scripts/bootstrap-sdk.sh first.
set -euo pipefail

ROOT="${0:A:h:h}"
SDK_DIR="$ROOT/Vendor/mega-sdk"
VCPKG_DIR="$ROOT/Vendor/vcpkg"
BUILD_DIR="$ROOT/Vendor/build-android-arm64"
MODULE_DIR="$ROOT/sdk/src/main"
ABI="arm64-v8a"
API=35

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_SDK/ndk/28.0.13004108}"
export JAVA_HOME="${JAVA_HOME:-$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home}"
export VCPKG_DISABLE_METRICS=1

[[ -f "$ANDROID_NDK_HOME/ndk-build" ]] || { print -u2 "NDK not found: $ANDROID_NDK_HOME"; exit 1; }
[[ -x "$VCPKG_DIR/vcpkg" ]] || { print -u2 "Run scripts/bootstrap-sdk.sh first"; exit 1; }

# Build only the pinned SDK, with every local patch applied.
source "$ROOT/scripts/sdk-version.sh"
[[ "$(git -C "$SDK_DIR" rev-parse HEAD)" == "$SDK_COMMIT" ]] || { print -u2 "Vendor/mega-sdk is not at the pinned commit. Run scripts/bootstrap-sdk.sh"; exit 1; }
for patch in "$ROOT"/patches/mega-sdk-*.patch(N); do
  git -C "$SDK_DIR" apply --reverse --check "$patch" 2>/dev/null || { print -u2 "Patch is not applied: ${patch:t}. Run scripts/bootstrap-sdk.sh"; exit 1; }
done

# The vcpkg dependencies are installed during the configure step. The overlay ports in
# scripts/ports come before the SDK's own overlay ports, so they replace them.
# ENABLE_CHAT only adds the SDK chat helper calls that the Java bindings reference (no MEGAchat).
# The prefix map keeps the checkout path out of __FILE__ strings in the library.
cmake -S "$SDK_DIR" --preset mega-android -B "$BUILD_DIR" -G Ninja \
  -DVCPKG_ROOT="$VCPKG_DIR" \
  -DVCPKG_OVERLAY_TRIPLETS="$ROOT/scripts/triplets" \
  -DVCPKG_OVERLAY_PORTS="$ROOT/scripts/ports" \
  -DVCPKG_TARGET_TRIPLET=arm64-android-megaglass \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="-ffile-prefix-map=$ROOT/=" \
  -DCMAKE_CXX_FLAGS="-ffile-prefix-map=$ROOT/=" \
  -DANDROID_PLATFORM=android-$API \
  -DANDROID_ABI=$ABI \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DENABLE_CHAT=ON \
  -DENABLE_SYNC=ON \
  -DUSE_LIBUV=ON \
  -DENABLE_MEDIA_FILE_METADATA=ON \
  -DENABLE_SDKLIB_EXAMPLES=OFF \
  -DENABLE_SDKLIB_TESTS=OFF

cmake --build "$BUILD_DIR" -j "$(sysctl -n hw.ncpu)"

mkdir -p "$MODULE_DIR/jniLibs/$ABI" "$MODULE_DIR/java/nz/mega/sdk"
"$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-unneeded \
  -o "$MODULE_DIR/jniLibs/$ABI/libmega.so" "$BUILD_DIR/bindings/java/libmega.so"

# Generated SWIG classes, then the hand-written Android bindings from the SDK.
rm -f "$MODULE_DIR"/java/nz/mega/sdk/*(N)
cp "$BUILD_DIR"/bindings/java/nz/mega/sdk/*.java "$MODULE_DIR/java/nz/mega/sdk/"
for f in "$SDK_DIR"/bindings/java/nz/mega/sdk/*.(java|kt); do
  [[ "${f:t}" == "MegaApiSwing.java" ]] || cp "$f" "$MODULE_DIR/java/nz/mega/sdk/"
done
cp "$SDK_DIR/LICENSE" "$ROOT/sdk/LICENSE-MEGA-SDK"

# Record exactly which native dependencies are in this binary. Gradle verifies the hash so an
# old libmega.so cannot accidentally be packaged after the overlay ports change.
python3 "$ROOT/scripts/native-provenance.py" "$ROOT"

print "SDK ready: $(du -h "$MODULE_DIR/jniLibs/$ABI/libmega.so" | cut -f1) libmega.so, $(ls "$MODULE_DIR/java/nz/mega/sdk" | wc -l | tr -d ' ') Java/Kotlin files"
