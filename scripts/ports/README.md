# Native security updates

The curl, OpenSSL, libsodium, zlib, and SQLite overlay ports are copied without modification from Microsoft vcpkg
commit `357b1a6f8343064ca982a20771e420fcecfe7efc` (MIT license).
They select curl 8.23.0, OpenSSL 3.6.5#1, libsodium 1.0.22#1, zlib 1.3.2#2, and SQLite
3.53.4#2, with the upstream download checksums and patches.
The remaining dependencies and the MEGA SDK version stay pinned by the SDK manifest.

The SDK's original baseline selected curl 8.14.1 and OpenSSL 3.5.0, which have subsequently
published security advisories. The build script supplies these ports through
`VCPKG_OVERLAY_PORTS`; rebuilding the native library is required for the updates to take effect.

Sources: [vcpkg revision](https://github.com/microsoft/vcpkg/tree/357b1a6f8343064ca982a20771e420fcecfe7efc/ports),
[curl advisories](https://curl.se/docs/vuln-8.14.1.html),
[OpenSSL advisories](https://www.openssl-library.org/news/vulnerabilities-3.5/index.html),
[libsodium releases](https://github.com/jedisct1/libsodium/releases),
[zlib releases](https://zlib.net/), and [SQLite CVE applicability](https://sqlite.org/cves.html).

SQLite CVEs require an attacker to run SQL or supply a crafted database. The SDK owns its SQL
and stores databases privately; this audit did not establish such an attack path. Its update
also takes the intervening correctness fixes. This is not a claim that every upstream advisory
was reachable through MegaGlass.
