#!/usr/bin/env python3
"""Bump the Android version by one patch release.

Reads `app/build.gradle.kts`, adds 1 to `versionCode` and `0.0.1` to `versionName`
(skipping any version whose `vX.Y.Z` tag already exists), writes the file back, and
reports the new values to the caller through `$GITHUB_OUTPUT` when it is set.

Usage: bump_version.py [repository root]   (defaults to the current directory)
"""

import os
import re
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
gradle_file = root / "app" / "build.gradle.kts"

text = gradle_file.read_text(encoding="utf-8")
code_match = re.search(r"versionCode\s*=\s*(\d+)", text)
name_match = re.search(r'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"', text)
if not code_match or not name_match:
    sys.exit(f"Could not find versionCode/versionName in {gradle_file}")

version_code = int(code_match.group(1))
major, minor, patch = (int(part) for part in name_match.groups())
previous_name = f"{major}.{minor}.{patch}"

# Tags are never moved: a hand-made release with the computed name would collide.
taken = set(
    subprocess.run(
        ["git", "tag", "--list"], cwd=root, capture_output=True, text=True, check=True
    ).stdout.split()
)
patch += 1
while f"v{major}.{minor}.{patch}" in taken:
    patch += 1
version_name = f"{major}.{minor}.{patch}"
version_code += 1

text = re.sub(r"versionCode\s*=\s*\d+", f"versionCode = {version_code}", text, count=1)
text = re.sub(
    r'versionName\s*=\s*"\d+\.\d+\.\d+"', f'versionName = "{version_name}"', text, count=1
)
gradle_file.write_text(text, encoding="utf-8")

asset_name = f"soundtrail-{version_name}-debug.apk"
print(f"versionCode {code_match.group(1)} -> {version_code}")
print(f"versionName {previous_name} -> {version_name}")

output = os.environ.get("GITHUB_OUTPUT")
if output:
    with open(output, "a", encoding="utf-8") as handle:
        handle.write(f"version={version_name}\n")
        handle.write(f"asset={asset_name}\n")
