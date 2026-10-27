# Native security updates

The curl, OpenSSL, libsodium, zlib, and SQLite overlay ports are copied from Microsoft vcpkg
commit `357b1a6f8343064ca982a20771e420fcecfe7efc` (MIT license).
They select curl 8.23.0, OpenSSL 3.6.5#1, libsodium 1.0.22#1, zlib 1.3.2#2, and SQLite
3.53.4#2, with the upstream download checksums and patches.
Two ports are changed. For Android, the OpenSSL `unix/portfile.cmake` gives fixed values for the
compiler command, the module and engine folders, and the build date that OpenSSL records in the
library (the block marked "MegaGlass"). Without them, the library holds paths of the build
machine, and each build gives a different binary. The curl `portfile.cmake` builds only HTTP(S)
and WebSockets (the block marked "MegaGlass"): the SDK uses only these, and many curl advisories
are in the other protocol handlers (FILE, FTP, GOPHER, TFTP and more). The curl configure log
(`buildtrees/curl/config-*-out.log` in Vendor/vcpkg) shows `Protocols: http https ws wss`.
The remaining dependencies and the MEGA SDK version stay pinned by the SDK manifest.

The SDK's original baseline selected curl 8.14.1 and OpenSSL 3.5.0, which have subsequently
published security advisories. The build script supplies these ports through
`VCPKG_OVERLAY_PORTS`; rebuilding the native library is required for the updates to take effect.
These ports come before the SDK's own overlay ports, so they replace them.

The libmediainfo port is the MEGA SDK v10.21.0 overlay port (BSD-2-Clause, see the SDK
`LICENSE`), changed to select MediaInfoLib 26.10 instead of 25.10. The SDK parses uploaded and
downloaded media files with this library. MediaInfoLib 26.05 fixed the heap overflows
CVE-2026-25104 and CVE-2026-28764 (LXF), CVE-2026-25713 (ID3v2) and CVE-2026-22554 (channel
splitting); 26.10 adds more fixes from a security audit. `dependencies.diff` and `features.diff`
are the MEGA patches, rebased onto 26.10. The MEGA AAC patch is not necessary because 26.10
contains the same fix. libzen stays at 0.4.41, the minimum version for 26.10.

Sources: [vcpkg revision](https://github.com/microsoft/vcpkg/tree/357b1a6f8343064ca982a20771e420fcecfe7efc/ports),
[curl advisories](https://curl.se/docs/vuln-8.14.1.html),
[OpenSSL advisories](https://www.openssl-library.org/news/vulnerabilities-3.5/index.html),
[libsodium releases](https://github.com/jedisct1/libsodium/releases),
[zlib releases](https://zlib.net/), [SQLite CVE applicability](https://sqlite.org/cves.html),
[MediaInfoLib changes](https://github.com/MediaArea/MediaInfoLib/blob/v26.10/History_DLL.txt), and
[Talos MediaInfoLib advisories](https://blog.talosintelligence.com/mediaarea-heap-based-buffer-overflow-vulnerabilities/).

When a native dependency changes, update its section in
`app/src/main/assets/third_party_notices.txt` from the new licence files (`share/<port>/copyright`
in the vcpkg install tree, or the source archive).

SQLite CVEs require an attacker to run SQL or supply a crafted database. The SDK owns its SQL
and stores databases privately; this audit did not establish such an attack path. Its update
also takes the intervening correctness fixes. This is not a claim that every upstream advisory
was reachable through MegaGlass.
