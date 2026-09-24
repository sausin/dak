#!/usr/bin/env python3
"""Checks the store listing in fastlane/metadata/android against F-Droid's and Google Play's limits.

Usage: scripts/check-store-metadata.py [metadata-dir]   (default: ../fastlane/metadata/android)

Both stores read the same files (docs/fdroid-submission.md, docs/play-submission.md), so the stricter limit wins:
title <= 30 characters (Play), short_description <= 80, full_description <= 4000, each changelog <= 500; no empty
file; images/icon.png is a 512x512 PNG and images/featureGraphic.png, when present, a 1024x500 PNG. Errors exit 1.
A locale without phone screenshots is only a warning (Play needs at least two before the first submission).

Standard library only; scripts/tests/test_check_store_metadata.py covers it (CI runs those tests).
"""

import pathlib
import struct
import sys

LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}
CHANGELOG_LIMIT = 500
IMAGES = {"icon.png": (512, 512), "featureGraphic.png": (1024, 500)}
REQUIRED = ("title.txt", "short_description.txt", "full_description.txt", "images/icon.png")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def png_size(path):
    head = path.read_bytes()[:24]
    if len(head) < 24 or not head.startswith(PNG_SIGNATURE) or head[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", head[16:24])


def check_locale(locale):
    errors, warnings = [], []
    for rel in REQUIRED:
        if not (locale / rel).is_file():
            errors.append(f"{locale.name}: missing {rel}")
    texts = [(locale / name, limit) for name, limit in LIMITS.items()]
    texts += [(p, CHANGELOG_LIMIT) for p in sorted((locale / "changelogs").glob("*.txt"))]
    for path, limit in texts:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8").strip()
        rel = path.relative_to(locale.parent)
        if not text:
            errors.append(f"{rel}: empty")
        elif len(text) > limit:
            errors.append(f"{rel}: {len(text)} characters, limit {limit}")
    for p in sorted((locale / "changelogs").glob("*")):
        if p.suffix != ".txt" or not p.stem.isdigit():
            errors.append(f"{p.relative_to(locale.parent)}: changelogs are named <versionCode>.txt")
    for name, expected in IMAGES.items():
        path = locale / "images" / name
        if path.is_file():
            size = png_size(path)
            if size != expected:
                errors.append(f"{path.relative_to(locale.parent)}: expected a {expected[0]}x{expected[1]} PNG, got {size}")
    shots = [p for p in (locale / "images" / "phoneScreenshots").glob("*") if p.suffix in (".png", ".jpg", ".jpeg")]
    if len(shots) < 2:
        warnings.append(f"{locale.name}: {len(shots)} phone screenshots (Play needs at least 2)")
    return errors, warnings


def check(metadata_dir):
    locales = sorted(p for p in pathlib.Path(metadata_dir).iterdir() if p.is_dir())
    if not any(p.name == "en-US" for p in locales):
        return [f"{metadata_dir}: no en-US locale (the default listing)"], []
    errors, warnings = [], []
    for locale in locales:
        e, w = check_locale(locale)
        errors += e
        warnings += w
    return errors, warnings


def main(argv):
    default = pathlib.Path(__file__).resolve().parent.parent.parent / "fastlane" / "metadata" / "android"
    metadata_dir = pathlib.Path(argv[1]) if len(argv) > 1 else default
    errors, warnings = check(metadata_dir)
    for w in warnings:
        print(f"warning: {w}")
    for e in errors:
        print(f"error: {e}")
    if errors:
        return 1
    print(f"Store metadata OK ({metadata_dir}).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
