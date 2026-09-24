"""Tests for scripts/check-store-metadata.py. Run: python3 -m pytest android/scripts/tests"""

import importlib.util
import pathlib
import struct
import zlib

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "check-store-metadata.py"
_spec = importlib.util.spec_from_file_location("check_store_metadata", SCRIPT)
check_store_metadata = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_store_metadata)

REPO_METADATA = SCRIPT.parent.parent.parent / "fastlane" / "metadata" / "android"


def _png(width, height):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IEND", b"")


def _listing(tmp_path, **overrides):
    files = {
        "title.txt": "Dak",
        "short_description.txt": "Short",
        "full_description.txt": "Long",
        "changelogs/1.txt": "First release.",
    }
    files.update(overrides)
    locale = tmp_path / "en-US"
    for rel, text in files.items():
        if text is None:
            continue
        p = locale / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
    (locale / "images").mkdir(parents=True, exist_ok=True)
    (locale / "images" / "icon.png").write_bytes(_png(512, 512))
    return locale


def test_repository_listing_is_valid():
    errors, _ = check_store_metadata.check(REPO_METADATA)
    assert errors == []


def test_minimal_listing_passes_with_screenshot_warning(tmp_path):
    _listing(tmp_path)
    errors, warnings = check_store_metadata.check(tmp_path)
    assert errors == []
    assert any("screenshots" in w for w in warnings)


def test_limits_are_enforced(tmp_path):
    _listing(tmp_path, **{"title.txt": "x" * 31, "short_description.txt": "x" * 81, "changelogs/2.txt": "x" * 501})
    errors, _ = check_store_metadata.check(tmp_path)
    assert len(errors) == 3


def test_missing_and_empty_files_fail(tmp_path):
    _listing(tmp_path, **{"full_description.txt": None, "short_description.txt": "  \n"})
    errors, _ = check_store_metadata.check(tmp_path)
    assert any("missing full_description.txt" in e for e in errors)
    assert any("short_description.txt: empty" in e for e in errors)


def test_changelog_must_be_named_by_version_code(tmp_path):
    _listing(tmp_path, **{"changelogs/v1.txt": "notes"})
    errors, _ = check_store_metadata.check(tmp_path)
    assert any("<versionCode>.txt" in e for e in errors)


def test_wrong_image_size_fails(tmp_path):
    locale = _listing(tmp_path)
    (locale / "images" / "featureGraphic.png").write_bytes(_png(1000, 500))
    (locale / "images" / "icon.png").write_bytes(b"not a png")
    errors, _ = check_store_metadata.check(tmp_path)
    assert len(errors) == 2


def test_default_locale_is_required(tmp_path):
    (tmp_path / "hi-IN").mkdir()
    errors, _ = check_store_metadata.check(tmp_path)
    assert errors and "en-US" in errors[0]
