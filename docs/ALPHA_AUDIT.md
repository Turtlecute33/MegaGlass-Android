# Alpha audit and stabilization round

Audit date: 2026-10-15. Reviewed and changed the current working tree, preserving the 49 files
already modified and the existing untracked `FolderSorts.kt`. No commits, publication, account
creation, or operations against a user's cloud data were performed.

This report records implemented fixes and their verification boundaries. It is not a claim
that every authenticated workflow has been exercised or that the application has no remaining
bugs. A dedicated MEGA test account was not available.

## Application architecture and scope

- Android application, arm64-v8a, minimum Android 15/API 35, target/compile API 37. Kotlin,
  Jetpack Compose, Material 3, Navigation 3, lifecycle ViewModels, coroutines/Flow, Preferences
  DataStore, WorkManager, Media3, Coil and the official MEGA C++/JNI SDK. Gradle, JDK 21, NDK 28;
  the repository's native bootstrap/build scripts target the macOS/Homebrew development setup.
- `MegaGlassApp` initializes the Android JNI filesystem adapter and one `AppGraph` per process.
  `Mega` owns an account SDK instance and a lazy public-folder instance. They outlive activities
  and background work. Native callbacks run on SDK threads; flows and transfer snapshots bridge
  them into the UI. Per-request suspend adapters propagate SDK errors.
- `Session` restores an encrypted session, logs in/MFA, fetches nodes, and controls Ready/Failed/
  Blocked/Closing states. Session ciphertext uses Android Keystore AES-GCM in no-backup storage.
  App backup/device transfer are disabled. Password inputs are not saveable UI state. The app
  lock uses the existing salted PBKDF2 hash, strong biometrics and persisted failure counts.
- `NodeRepository` covers enumeration, search, recents, versions and mutations. SDK nodes become
  immutable UI snapshots off the main thread. Transfers use the SDK's encryption, fingerprints,
  MAC verification, retry engine, upload/download APIs and collision policies. A user-initiated
  JobService keeps foreground transfers alive; camera uploads use constrained WorkManager jobs.
- Local content URIs are resolved or copied into private upload storage. Offline files have a
  staging/commit index. Image and preview copies are private. Android FileProvider grants access
  to selected files. Media streams through the SDK's restricted localhost HTTP server; PDFs use
  PdfRenderer; text edits upload through the normal transfer engine.
- Reviewed feature logic and screen bindings for authentication, drive/rubbish/backups, search,
  shared folders, contacts, account/quota, notifications, photos/albums/camera uploads, public
  links, offline files, device centre/two-way sync, settings/security and all viewers. Reviewed
  build scripts, manifests, storage rules, handwritten JNI glue, generated SDK signatures and
  relevant native implementations. No pre-existing application tests or issue tracker files
  were present. This was not a line-by-line audit of all third-party SDK/dependency code.

## Implemented fixes and hardening

Each row describes the underlying defect or reliability gap and the resulting behavior.
Priorities describe impact, not proof that an attack was exploitable in a deployed application.

| ID | Priority | Problem and implemented change | Primary code |
|---|---|---|---|
| A01 | P0 | The SDK deletes `isSourceTemporary` upload sources on failure/cancellation too. Stop using that flag for private copies; retain failed sources for Retry and remove only successful copies inside approved private roots. The marker survives SDK transfer caching. | [TransferManager](../app/src/main/kotlin/app/megaglass/android/core/transfers/TransferManager.kt), [SafeFiles](../app/src/main/kotlin/app/megaglass/android/core/storage/SafeFiles.kt) |
| A02 | P0 | Captures used a second-resolution cache filename, allowing collision and eviction of the only original. Reserve unique files in persistent private `pending-uploads`; grant the camera only its FileProvider URI. | [Uploads](../app/src/main/kotlin/app/megaglass/android/feature/drive/Uploads.kt) |
| A03 | P0 | Offline availability was inferred from existing files, including unfinished folders after restart. Download to a separate staging tree and commit completion metadata only after SDK success. Preserve previous copies on replacement failure; label legacy copies unverified. | [OfflineStore](../app/src/main/kotlin/app/megaglass/android/core/storage/OfflineStore.kt), [OfflineFiles](../app/src/main/kotlin/app/megaglass/android/core/storage/OfflineFiles.kt) |
| A04 | P0 | In SDK 10.21 a batch folder's child transfers carry its appData; its unnamed batch container does not. Child completion cannot prove the whole folder finished. Single-node downloads now use the SDK single-node API with an explicit name; offline Retry starts a new tracked staging operation. | [TransferManager](../app/src/main/kotlin/app/megaglass/android/core/transfers/TransferManager.kt) |
| A05 | P0 | A cache file with the expected byte length was reused without proof the SDK verified completion. Persist verification metadata for successful private downloads, including zero-byte files; folder streaming checks length and commits a unique temporary file. | [SafeFiles](../app/src/main/kotlin/app/megaglass/android/core/storage/SafeFiles.kt), [ViewerCommon](../app/src/main/kotlin/app/megaglass/android/feature/viewer/ViewerCommon.kt) |
| A06 | P0 | Text edits became clean when queued, before upload success. Subscribe before dispatch, await SDK completion, keep failed edits dirty and retriable, and reject saves when the original node changed/moved. Cancel app-owned save waiters at session end. | [TextEditor](../app/src/main/kotlin/app/megaglass/android/feature/viewer/TextEditor.kt) |
| A07 | P0 | SAF overwrite deleted the destination before a fallible rename. Refuse non-atomic provider replacement instead of destroying the existing document. Reject NUL child names. | [FileWrapper](../sdk/src/main/glue/mega/privacy/android/data/filewrapper/FileWrapper.java) |
| A08 | P0 | Public folder/album names `.`/`..` could leave the download destination; shared names could contain separators/NUL. Centralize safe local components, validate constructed folder paths, preserve Unicode, and enumerate public trees iteratively. | [SafeFiles](../app/src/main/kotlin/app/megaglass/android/core/storage/SafeFiles.kt), [PublicLinkViewModel](../app/src/main/kotlin/app/megaglass/android/feature/links/PublicLinkViewModel.kt) |
| A09 | P0 | Generic uploads and SDK copies could overwrite same-name files, particularly with versioning off. Reserve distinct names for ordinary uploads/copies and link copies. Reject known conflicting moves/file-link imports; protect cross-share copy-delete into rubbish. Editor Save remains an explicit replacement. | [TransferManager](../app/src/main/kotlin/app/megaglass/android/core/transfers/TransferManager.kt), [NodeRepository](../app/src/main/kotlin/app/megaglass/android/core/mega/NodeRepository.kt) |
| A10 | P0 | Session rename failures were unchecked; IO/Keystore errors were treated as absent sessions, causing cleanup. Use AtomicFile, verify the committed ciphertext, distinguish recoverable read failures from malformed/authentication-failed ciphertext, and verify deletion. | [SessionStore](../app/src/main/kotlin/app/megaglass/android/core/storage/SessionStore.kt) |
| A11 | P1 | Startup/login/logout could race or leave indefinite loading. Serialize lifecycle changes, publish lazy jobs before starting them, add bounded authentication/logout waits, retry failed cleanup before login, and show authentication timeout errors. | [Session](../app/src/main/kotlin/app/megaglass/android/core/mega/Session.kt), [AuthViewModel](../app/src/main/kotlin/app/megaglass/android/feature/auth/AuthViewModel.kt) |
| A12 | P1 | Cancelled suspend requests retained listeners and could publish progress into stale views; broad `runCatching` swallowed cancellation. Remove SDK listeners on cancellation/dispatch failure, ignore late progress, and use a cancellation-preserving result helper throughout the app. | [Mega](../app/src/main/kotlin/app/megaglass/android/core/mega/Mega.kt), [Results](../app/src/main/kotlin/app/megaglass/android/core/util/Results.kt) |
| A13 | P1 | Several allocating SDK factories and album getters were generated as non-owning JNI wrappers, leaking native allocations. Add precise SWIG ownership annotations based on the native implementations; fence borrowed list items while their owning lists/requests are in use. | [SDK patch](../patches/mega-sdk-java-factory-ownership.patch), [PhotosData](../app/src/main/kotlin/app/megaglass/android/feature/photos/PhotosData.kt) |
| A14 | P1 | Folder cancellation by tag can deadlock during SDK enumeration. Retain cancellation tokens through completion and share parent tokens with child records. Pause operations now await request results and report errors, including the folder instance. | [TransferManager](../app/src/main/kotlin/app/megaglass/android/core/transfers/TransferManager.kt) |
| A15 | P1 | `tryEmit` could lose critical finish events, stranding offline/editor/share waiters. A single IO drain delivers completion events in order; session epochs discard obsolete results, clear records and prevent old snapshots returning after logout. | [TransferManager](../app/src/main/kotlin/app/megaglass/android/core/transfers/TransferManager.kt) |
| A16 | P1 | Navigation models, cached contacts, playback and prepared files could outlive an account. Scope navigation stores to the Ready navigation host and clear them on disposal; stop camera work/playback, clear caches and memory images, and reset contact/preparation state on logout. | [NavigationViewModels](../app/src/main/kotlin/app/megaglass/android/ui/nav/NavigationViewModels.kt), [MegaGlassApp](../app/src/main/kotlin/app/megaglass/android/MegaGlassApp.kt) |
| A17 | P1 | Public folder/album SDK sessions are shared across retained entries. Serialize session changes and protect cleanup with ownership IDs so an old screen cannot log out/close a newer preview. Cancel stale folder loads and report superseded folder links. | [PublicLinkViewModel](../app/src/main/kotlin/app/megaglass/android/feature/links/PublicLinkViewModel.kt) |
| A18 | P1 | Notification promotion was called on API 36.0 although introduced in 36.1, risking a runtime method error. Guard the full SDK version. | [TransferJobService](../app/src/main/kotlin/app/megaglass/android/core/transfers/TransferJobService.kt) |
| A19 | P1 | Original native dependencies had subsequent upstream security fixes. Pin official overlay ports and rebuild the native library. Record dependency provenance and binary SHA-256; reject stale/mismatched native artifacts in Gradle. | [Ports](../scripts/ports/README.md), [build script](../scripts/build-sdk.sh), [SDK build checks](../sdk/build.gradle.kts) |
| A20 | P1 | Sync pause/resume/remove did not await SDK results. Add managed listener overloads to the Java bridge, suppress duplicate actions and report errors before refreshing. | [MegaGlassApi](../sdk/src/main/glue/nz/mega/sdk/MegaGlassApi.java), [SyncViewModel](../app/src/main/kotlin/app/megaglass/android/feature/sync/SyncViewModel.kt) |
| A21 | P2 | Node mutations/downloads silently skipped vanished selections; bulk mutations created unbounded concurrent requests. Validate the whole selection and bound mutation batches. | [NodeRepository](../app/src/main/kotlin/app/megaglass/android/core/mega/NodeRepository.kt) |
| A22 | P2 | Search results and selected handles could belong to an earlier query; sort/hidden settings did not refresh searches. Collect distinct inputs with cancellation, include settings, and clear old selection/results immediately. Guard menu version counts against a newer selected node. | [SearchScreen](../app/src/main/kotlin/app/megaglass/android/feature/search/SearchScreen.kt), [NodeActions](../app/src/main/kotlin/app/megaglass/android/feature/drive/NodeActions.kt) |
| A23 | P2 | SDK node/user invalidations could be dropped when bounded flow buffers filled. A conflated overflow signal guarantees a subsequent full refresh without blocking SDK callbacks or starting unbounded refresh jobs. | [Invalidations](../app/src/main/kotlin/app/megaglass/android/core/util/Invalidations.kt) |
| A24 | P2 | Share failures left batch state behind; immediate download failures left preparing state; external launches could fail uncaught. Reset failed batches/preparation, reject duplicate preparation and handle missing external activities. | [FileOpener](../app/src/main/kotlin/app/megaglass/android/core/FileOpener.kt) |
| A25 | P2 | Hidden parent-folder sensitivity was not consistently applied to albums/outgoing shares/public links. Include inherited sensitivity in those enumerations. | [PhotosData](../app/src/main/kotlin/app/megaglass/android/feature/photos/PhotosData.kt), [SharedViewModel](../app/src/main/kotlin/app/megaglass/android/feature/shared/SharedViewModel.kt) |
| A26 | P2 | Camera retries appended an ever-growing dependent trigger chain; already queued media could be reported idle; stopping the setting could leave uploads running. Rearm after success only, keep busy media pending, stop disabled uploads, and report unreadable camera files without advancing past them. | [CameraUploadWorker](../app/src/main/kotlin/app/megaglass/android/core/camera/CameraUploadWorker.kt) |
| A27 | P2 | Malformed passcode hashes could crash verification; biometric callbacks outlived the lock screen; failed cleanup could remove the passcode anyway. Fail closed, cancel prompts on disposal/wipe, clear PBEKeySpec input, and retain the lock when cleanup fails. | [AppLock](../app/src/main/kotlin/app/megaglass/android/core/security/AppLock.kt), [LockScreen](../app/src/main/kotlin/app/megaglass/android/feature/lock/LockScreen.kt) |
| A28 | P2 | Avatar cache keys used collision-prone String hashes. Use full SHA-256 keys. Prevent pending image writes from repopulating a cleared cache; trim old image copies toward 256 MiB while protecting recent readers and `.part` writes. Include preview/link copies in cache metrics/clearing. | [MegaImages](../app/src/main/kotlin/app/megaglass/android/core/media/MegaImages.kt), [SettingsScreens](../app/src/main/kotlin/app/megaglass/android/feature/settings/SettingsScreens.kt) |
| A29 | P2 | Password-strength work ran on the UI thread; large selections performed quadratic membership checks; contact attribute requests were unbounded. Debounce native strength work on a serial background dispatcher, use handle sets, and limit concurrent contact loads. | [PasswordStrength](../app/src/main/kotlin/app/megaglass/android/ui/components/PasswordStrength.kt), [FolderViewModel](../app/src/main/kotlin/app/megaglass/android/feature/drive/FolderViewModel.kt) |
| A30 | P2 | Invalid media dates could crash timeline grouping; PDF constructor failures leaked descriptors; repeated PDF close/render could access a closed renderer; very large progress multiplication could overflow. Validate/fallback dates, clean renderer resources and clamp progress arithmetic safely. | [PhotosData](../app/src/main/kotlin/app/megaglass/android/feature/photos/PhotosData.kt), [PdfViewer](../app/src/main/kotlin/app/megaglass/android/feature/viewer/PdfViewer.kt), [Format](../app/src/main/kotlin/app/megaglass/android/core/util/Format.kt) |
| A31 | P2 | Link parsing accepted lookalike/unrelated origins and malformed paths. Validate scheme, host, credentials, port and route while preserving supported legacy/custom forms. Forward MEGA account recovery/confirmation links to an external chooser excluding this app. | [MegaLink](../app/src/main/kotlin/app/megaglass/android/feature/links/MegaLink.kt), [MainActivity](../app/src/main/kotlin/app/megaglass/android/MainActivity.kt) |
| A32 | P0 | Provider-supplied real paths could point folder uploads/sync at app-private data or its ancestors; unreadable sources were silently skipped. Centralize canonical credential/device-protected storage checks for every resolved path, reject forwarded internal provider URIs, report unreadable files and clean failed copies. | [LocalFiles](../app/src/main/kotlin/app/megaglass/android/core/storage/LocalFiles.kt), [Uploads](../app/src/main/kotlin/app/megaglass/android/feature/drive/Uploads.kt) |
| A33 | P2 | Resource/locale reads in Compose did not react to configuration changes. Use observable resources/configuration and enable strict lint in both modules. | [App build](../app/build.gradle.kts), [PhotoGrid](../app/src/main/kotlin/app/megaglass/android/feature/photos/PhotoGrid.kt) |
| A34 | P2 | Release builds silently fell back to the reproducible debug signing identity. Require the configured release identity for signing; otherwise produce an unsigned release artifact. Lower SDK logging to ERROR in every build. | [App build](../app/build.gradle.kts), [Mega](../app/src/main/kotlin/app/megaglass/android/core/mega/Mega.kt) |

## Native dependency evidence

The completed native rebuild selects OpenSSL **3.6.5#1**, curl **8.23.0**, libsodium **1.0.22#1**,
zlib **1.3.2#2** and SQLite **3.53.4#2**. The MEGA SDK remains pinned to
`7e4519cc1d8c42312444482766b761c899894e6f` / v10.21.0. Overlay source, patch files, download
checksums and licensing come from Microsoft vcpkg revision
`357b1a6f8343064ca982a20771e420fcecfe7efc`.

The original OpenSSL 3.5.0 and curl 8.14.1 have subsequent documented security fixes:
[OpenSSL advisories](https://www.openssl-library.org/news/vulnerabilities-3.5/index.html),
[curl version advisories](https://curl.se/docs/vuln-8.14.1.html).
The libsodium update includes upstream validation/security corrections:
[official releases](https://github.com/jedisct1/libsodium/releases).
SQLite's own guidance explains that its CVEs require SQL control or a crafted database; neither
attack path was established here. Its update also incorporates maintenance fixes:
[SQLite applicability](https://sqlite.org/cves.html), [zlib releases](https://zlib.net/).
These updates are not proof that every upstream CVE was reachable through this application.

The generated `sdk/src/main/assets/mega-build.properties` records installed native versions and
the exact binary digest. It is generated/ignored with the binary, packaged as a readable asset,
and checked before builds. A fresh checkout must run both SDK scripts as documented.

## Verification

- JVM regression suite: **35 tests**, covering cancellation/overflow recovery, navigation model
  cleanup, safe path components, Unicode, collision names, zero-byte and unverified cache files,
  failed/atomic replacements, interrupted offline staging/restart, legacy-copy preservation,
  original-file and symlink protection during upload cleanup, avatar hash collisions, cache
  eviction, extreme media dates, progress arithmetic and supported/rejected MEGA URL forms.
- Android 15 / arm64 isolated emulator suite: **8 tests**. Real Android Keystore/GCM round trips,
  ciphertext tamper/truncation rejection, IO-vs-absent-session distinction, failed deletion
  reporting, actual SDK listener registration/removal plus late-callback suppression, SDK error
  propagation, native owned-allocation release, and failed-upload source retention with an
  invalid destination while logged out. No authenticated server mutations are performed.
- Build/check commands: `:app:testDebugUnitTest`, `:app:connectedDebugAndroidTest`,
  `:app:assembleDebug`, `:app:assembleRelease`, `:app:lintDebug`, `:sdk:lintDebug`.
  Exact latest outcomes are recorded in `ALPHA_AUDIT_VALIDATION.md` after the final run.
- Clean-device startup smoke: welcome screen rendered on Android 15; native SDK loaded without
  linkage failure. The emulator uses separate temporary data. No existing AVD user data or
  real device was used for these tests.
- Staged whitespace checks passed outside verbatim upstream dependency ports, which preserve
  upstream patch whitespace and line endings. Shell syntax checks passed; targeted file scan for private-key,
  AWS-access-key and GitHub-token patterns and tracked credential files. No matches found.
  This narrow scan is not a complete secret/history scanner or full dependency SCA certification.

## Behavioral limits and account validation still required

- Multi-item SDK operations are not server transactions. Known local conflicts are checked and
  copies preserve names, but another client can change a destination after a preflight check.
  Cancellation stops observation as documented; it cannot undo an already authorized remote
  operation. Partial failures are surfaced rather than reported as complete success.
- Offline `.pending` copies are never advertised as verified after process death. Older copies
  without metadata are labelled unverified and can be downloaded again. A replacement moves
  the existing copy to `.previous-*` until the new copy is in place, then deletes it; startup
  restores a copy that an interrupted commit left there. When the node tree is current, the
  app adopts SDK-resumed single-file downloads (their staging records the node) and downloads
  interrupted folders again, because resumed child files cannot prove a folder complete.
  This needs live account/process-kill tests. Local offline clearing/logout intentionally
  removes offline data.
- Failed captured originals persist under private `files/pending-uploads`, outside cache
  eviction. A logout moves them to Pictures/MegaGlass. Leftovers that no upload or capture
  of the current process uses move there when the app next comes to the foreground. With
  the app lock, both moves wait for an unlock. The passcode wipe deletes them first. Failed
  copied-upload sources remain retriable while present; copies that no queued upload reads
  are removed when the node tree of the next run is current, and by Clear cache. Transfer
  history is bounded and not a persistent recovery catalogue for every pending original;
  app-data deletion removes originals.
- Completion delivery uses an in-process FIFO mailbox; it is not a durable event log. Under a
  prolonged stalled observer its backlog can grow. Disk-image trimming has a recent-reader
  grace period and is a soft budget. Viewer, open-with and link copies have the same kind of
  budget (512 MB each, oldest first), applied at start and after each viewer download.
- Only one public folder SDK session can be active. Superseded entries report that the link must
  be reopened. Native two-way sync runs while the process lives; a continuous Android sync
  service is not implemented. Chat/calls are intentionally excluded. Navigation remains based
  on the existing in-memory back stacks; full process-death navigation/draft restoration is not
  established by this round. `Mega.APP_KEY` remains empty as in the alpha and must be checked
  with service acceptance during real-account validation.
- Accepted risk (MEGAGLASS-SEC-002): downloads to shared storage, folder upload and two-way
  sync need all-files access, because the SDK uses real paths for them. With this access, a
  fault in the native SDK can reach all of shared storage. The bundled FileWrapper can read SAF
  tree URIs, but a SAF sync is not tested for speed or for files that it does not see, and a
  two-way sync deletes such a file from the cloud. The README Security section records this.
- The tests do not establish production readiness for successful login/MFA/session restoration,
  account switching with active authenticated operations, quota exhaustion, multi-gigabyte or
  recursive transfers, real network loss, real disk exhaustion, shared-folder permissions,
  public-link import/download, long/pending camera videos, or sync conflict resolution. API 36.0/
  36.1/37 runtime notification behavior was checked statically, not on corresponding emulators.

Run the live-account matrix below with disposable data and keep original files outside the app.

| Workflow | Required live validation |
|---|---|
| Authentication | Valid/invalid passwords, MFA required/wrong/correct, airplane-mode startup, expiry/revocation, account block, logout without network, account switch during fetch/transfer/editor save. |
| Files and integrity | 0 B, small, multi-GB, Unicode, long names, duplicate names, deep trees, same-content/different-content collisions, invalid/read-only destinations and low disk. Compare downloaded originals independently. |
| Transfers | Pause/resume/cancel during enumeration and chunk transfer, quota recovery, retried failed sources, folder failure propagation, rapid completion bursts, background/close/process kill and cached-transfer restoration. |
| Mutations and sharing | Move/copy/rename collisions, trash/restore/undo, permanent deletion confirmations, read-only/full-access shares, removed destinations and concurrent changes from another client. |
| Camera and sync | Selected-photo permissions, pending/late-published videos, Wi-Fi/charging changes, disable/cancel, duplicate worker launches, existing queued media, marker recovery, two-way sync conflicts/permission loss. |
| Public links and viewers | Protected/wrong/missing keys, taken-down nodes, concurrent folder/album links, import collisions, invalid paths, cancelled preparation, failed PDF rendering and playback across lifecycle changes. |
