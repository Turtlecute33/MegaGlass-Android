# MegaGlass for Android

A MEGA cloud client for Android 15 and later, built on the official MEGA SDK (C++, BSD-2) with
Jetpack Compose and Material 3 Expressive. Chat and calls are not included.

## Build

Requirements: Android SDK with platform 37 and NDK 28, JDK 21, Homebrew packages
`cmake ninja swig autoconf automake autoconf-archive libtool pkg-config`.

```sh
scripts/bootstrap-sdk.sh      # fetches MEGA SDK v10.21.0 and vcpkg, applies patches/
scripts/build-sdk.sh          # builds libmega.so (arm64-v8a) and the Java bindings into :sdk
./gradlew :app:assembleDebug  # or assembleRelease after scripts/make-release-key.sh
```

The first SDK build compiles all native dependencies with vcpkg and takes a while. Later builds
reuse them.

## Native SDK changes

- `patches/mega-sdk-android-request-size.patch`: the SDK limits each transfer request to 2 MB on
  Android. The patch scales the limit with device RAM (up to 16 MB, the desktop value), which
  reduces per-request overhead on fast connections.
- `patches/mega-sdk-java-factory-ownership.patch`: marks allocating JNI factories and album
  getters as owning their native results so Java can release them safely.
- `scripts/ports/`: pinned native dependency updates, with upstream provenance and licenses.
  The native build records versions and a binary digest; Gradle rejects mismatched artifacts.
- The app sets 8 parallel connections per direction (SDK mobile default: 4 down, 3 up). This is
  the "Fast transfers" setting.
- Crypto++ and OpenSSL are built with the ARMv8 AES, PMULL and SHA instructions; they are
  selected at run time.

## Privacy

- No analytics, no ads, no telemetry, no Google Play Services.
- The session is encrypted with a key in the Android Keystore and is excluded from backups.
- Thumbnails, previews and offline files stay in app-private storage.
- The app passcode is stored only as a salted PBKDF2 hash.

## Layout

- `sdk/`: SDK bindings (`src/main/java` is generated, `src/main/glue` is hand-written).
- `app/src/main/kotlin/app/megaglass/android/core`: SDK wrapper, session, transfers, storage.
- `app/src/main/kotlin/app/megaglass/android/ui`: theme, shared components, navigation.
- `app/src/main/kotlin/app/megaglass/android/feature`: one package per feature.
- `scripts/fetch-icons.py`: regenerates the Material Symbols drawables.

## Validation

After building the native SDK, run JVM regressions, lint and both application builds:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :sdk:lintDebug \
  :app:assembleDebug :app:assembleRelease
```

With an Android 15 or later arm64 emulator connected, run
`./gradlew :app:connectedDebugAndroidTest`. These tests use isolated fixture data and do not
require a MEGA account. Authenticated workflows still require a dedicated test account.

See the [alpha audit](docs/ALPHA_AUDIT.md) for implemented fixes and remaining test gaps, and
the [validation record](docs/ALPHA_AUDIT_VALIDATION.md) for measured results and artifact hashes.
