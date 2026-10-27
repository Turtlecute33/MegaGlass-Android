# Final audit validation (snapshot of 2026-10-15)

Recorded 2026-10-15T02:33:43+00:00.

This is a dated snapshot of one run. It does not show later changes: more SDK patches,
MediaInfoLib 26.10 and new native build flags. Each native build writes its own versions,
patch digest, tool versions and binary digest to `sdk/src/main/assets/mega-build.properties`.

The final combined build completed successfully with strict lint enabled.

| Check | Result |
|---|---|
| JVM regression tests | 35 passed; 0 failures/errors/skips |
| Android 15 emulator tests | 8 passed; 0 failures/errors/skips |
| app Android lint | 0 errors; 77 warnings; 3 hints |
| sdk Android lint | 0 errors; 9 warnings; 0 hints |
| Debug APK | Built successfully |
| Optimized release APK | Built successfully |
| Native SDK | Rebuilt successfully with pinned dependency overlays and JNI ownership patch |
| Packaged native library and provenance | Both APKs match the recorded binary digest and provenance |
| Native digest guard negative test | Altered provenance digest rejected; restored digest accepted |
| SDK patch application | Both patches pass reverse-application checks |
| Shell/Python syntax | Passed |
| Whitespace | Staged diff check passed excluding verbatim upstream dependency ports; those retain upstream patch whitespace and line endings |
| Clean-device startup | Android 15 cold launch succeeded in 900 ms; welcome actions verified |

Commands run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_SERIAL=emulator-5560 ./gradlew \
  :app:testDebugUnitTest :app:connectedDebugAndroidTest \
  :app:assembleDebug :app:lintDebug :sdk:lintDebug :app:assembleRelease
```

Artifacts from this run (local builds, not the published release assets; check a release APK by
its signing certificate, see the README):

| Artifact | SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `711165c6bcd1607c313ce48baff1ff4e91bcf3c83e06c05013dee056a93f9d41` |
| `app/build/outputs/apk/release/app-release.apk` | `9bd02b9486f5d803b823828153edd49a5b8196cecc8666534e41b73dfa872d62` |
| `sdk/src/main/jniLibs/arm64-v8a/libmega.so` | `56646cf89fc2c125812c8e4f4e527dc972631fce246dc8036f0a1377d08d08ed` |

Generated native provenance:

```properties
cryptopp=8.9.0
curl=8.23.0
icu=74.2
libSha256=56646cf89fc2c125812c8e4f4e527dc972631fce246dc8036f0a1377d08d08ed
libmediainfo=25.10
libsodium=1.0.22
libuv=1.51.0
libzen=0.4.41
openssl=3.6.5
sdkRevision=7e4519cc1d8c42312444482766b761c899894e6f
sqlite3=3.53.4
tinyxml2=11.0.0
zlib=1.3.2
zstd=1.5.7
```

The Android tests use a clean, isolated Android 15 arm64 emulator and placeholder fixture data. They do not use a MEGA account or mutate remote cloud data. Account-dependent test coverage and behavioral limits are listed in [the audit report](ALPHA_AUDIT.md).

Source edits after the build only clarified comments in `LocalFiles.kt` and updated documentation.
