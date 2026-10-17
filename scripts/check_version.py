#!/usr/bin/env python3
"""Validate one-source Android release version and the corresponding changelog entry."""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parents[1]

def check():
    file = root / "version.properties"
    lines = file.read_text(encoding="utf-8").splitlines()
    data = {}
    for item in lines:
        item = item.strip()
        if not item or item.startswith("#"):
            continue
        if "=" not in item:
            raise ValueError(f"Malformed version.properties line: {item}")
        key, value = item.split("=", 1)
        if key in data:
            raise ValueError(f"Duplicate key: {key}")
        data[key.strip()] = value.strip()
    if set(data) != {"versionName", "versionCode"}:
        raise ValueError("Expected exactly versionName and versionCode")
    name, raw_code = data["versionName"], data["versionCode"]
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)\.[1-9][0-9]*)?", name):
        raise ValueError(f"Invalid SemVer versionName: {name}")
    if not raw_code.isdecimal() or not 1 <= int(raw_code) <= 2100000000:
        raise ValueError("versionCode must be a positive integer within Google Play bounds")
    changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    first = re.search(r"^##\s+(\S+)", changelog, re.MULTILINE)
    if first is None or first.group(1) != name:
        raise ValueError(f"First CHANGELOG.md release must be {name}")
    print(f"Version OK: {name} (Android build {raw_code})")

if __name__ == "__main__":
    try:
        check()
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
