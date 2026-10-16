#!/usr/bin/env python3
"""Record the rebuilt binary and its native dependency versions without runtime dependencies."""
import hashlib
import pathlib
import subprocess
import sys

root = pathlib.Path(sys.argv[1]).resolve()
binary = root / "sdk/src/main/jniLibs/arm64-v8a/libmega.so"
status = root / "Vendor/build-android-arm64/vcpkg_installed/vcpkg/status"
versions = {}
for paragraph in status.read_text().split("\n\n"):
    fields = dict(line.split(": ", 1) for line in paragraph.splitlines() if ": " in line)
    if fields.get("Architecture") == "arm64-android-megaglass" and "Version" in fields:
        versions[fields["Package"]] = fields["Version"]
for port in (root / "scripts/ports").glob("*/vcpkg.json"):
    import json
    expected = json.loads(port.read_text())
    if versions.get(expected["name"]) != expected["version"]:
        raise SystemExit(f"Native dependency {expected['name']} was not rebuilt to {expected['version']}")
versions["libSha256"] = hashlib.sha256(binary.read_bytes()).hexdigest()
# The SDK patches in this binary: build-sdk.sh checks that each one is applied.
patches = hashlib.sha256()
for patch in sorted(root.glob("patches/mega-sdk-*.patch")):
    patches.update(patch.read_bytes())
versions["patchesSha256"] = patches.hexdigest()
versions["sdkRevision"] = subprocess.check_output(
    ["git", "-C", str(root / "Vendor/mega-sdk"), "rev-parse", "HEAD"], text=True
).strip()
output = root / "sdk/src/main/assets/mega-build.properties"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text("".join(f"{key}={value}\n" for key, value in sorted(versions.items())))
