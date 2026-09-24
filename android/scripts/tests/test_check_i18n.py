"""Tests for scripts/check-i18n.py. Run: python3 -m pytest android/scripts/tests"""

import importlib.util
import pathlib

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "check-i18n.py"
_spec = importlib.util.spec_from_file_location("check_i18n", SCRIPT)
check_i18n = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_i18n)

ANDROID_DIR = SCRIPT.parent.parent

CONFIG = """<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
{}
</locale-config>
"""


def _write(root, rel, text):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def _res(entries):
    return '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + "\n".join(entries) + "\n</resources>\n"


def _tree(tmp_path, locales=("en",), debug=("en", "en-XA", "ar-XB"), app=None, tel=None, translations=None):
    _write(tmp_path, "app/src/main/res/xml/locales_config.xml", CONFIG.format("\n".join(f'<locale android:name="{t}"/>' for t in locales)))
    _write(tmp_path, "app/src/debug/res/xml/locales_config.xml", CONFIG.format("\n".join(f'<locale android:name="{t}"/>' for t in debug)))
    _write(tmp_path, "app/src/main/res/values/strings.xml", _res(app or [
        '<string name="app_name" translatable="false">Dak</string>',
        '<!-- %1$s = sender -->',
        '<string name="from">From %1$s</string>',
        '<string name="fwd">Fwd from {sender}: {body}</string>',
        '<plurals name="n"><item quantity="one">%1$d message</item><item quantity="other">%1$d messages</item></plurals>',
    ]))
    _write(tmp_path, "core-telephony/src/main/res/values/strings.xml", _res(tel or ['<string name="sent">Sent</string>']))
    for rel, entries in (translations or {}).items():
        _write(tmp_path, rel, _res(entries))
    return tmp_path


HI_APP = [
    '<string name="from">%1$s से</string>',
    '<string name="fwd">आगे भेजा {sender}: {body}</string>',
    '<plurals name="n"><item quantity="one">एक संदेश</item><item quantity="other">%1$d संदेश</item></plurals>',
]
HI_TEL = ['<string name="sent">भेजा गया</string>']


def test_repository_passes():
    errors, _ = check_i18n.check(ANDROID_DIR)
    assert errors == []


def test_english_only_passes(tmp_path):
    assert check_i18n.check(_tree(tmp_path)) == ([], [])


def test_complete_shipped_language_passes(tmp_path):
    tree = _tree(tmp_path, locales=("en", "hi"), debug=("en", "hi", "en-XA"), translations={
        "app/src/main/res/values-hi/strings.xml": HI_APP,
        "core-telephony/src/main/res/values-hi/strings.xml": HI_TEL,
    })
    assert check_i18n.check(tree)[0] == []


def test_shipped_language_must_be_complete(tmp_path):
    tree = _tree(tmp_path, locales=("en", "hi"), debug=("en", "hi"), translations={
        "app/src/main/res/values-hi/strings.xml": HI_APP[:2],
    })
    errors = check_i18n.check(tree)[0]
    assert any("missing n" in e for e in errors)
    assert any("core-telephony values-hi: missing sent" in e for e in errors)


def test_listed_language_without_directory(tmp_path):
    errors = check_i18n.check(_tree(tmp_path, locales=("en", "ta"), debug=("en", "ta")))[0]
    assert any("values-ta/ does not exist" in e for e in errors)


def test_unlisted_partial_translation_is_only_a_warning(tmp_path):
    errors, warnings = check_i18n.check(_tree(tmp_path, translations={"app/src/main/res/values-hi/strings.xml": HI_APP[:1]}))
    assert errors == []
    assert any("values-hi/ exists but is not in locales_config.xml" in w for w in warnings)


def test_placeholders_must_match(tmp_path):
    errors = check_i18n.check(_tree(tmp_path, translations={"app/src/main/res/values-hi/strings.xml": [
        '<string name="from">%1$d से</string>',
        '<string name="fwd">आगे भेजा {प्रेषक}: {body}</string>',
        '<plurals name="n"><item quantity="other">%2$d संदेश</item></plurals>',
        '<string name="app_name">डाक</string>',
    ]}))[0]
    assert any("from placeholders" in e for e in errors)
    assert any("fwd placeholders" in e for e in errors)
    assert any("plural n" in e for e in errors)
    assert any('app_name is translatable="false"' in e for e in errors)


def test_several_placeholders_must_be_positional(tmp_path):
    errors = check_i18n.check(_tree(tmp_path, app=['<string name="x">%s of %d</string>', '<string name="ok">%1$s of %2$d (%3$d%%)</string>']))[0]
    assert errors == ["app/strings.xml: x has several placeholders; use %1$s, %2$d... so translators can reorder"]


def test_locale_config_rules(tmp_path):
    errors = check_i18n.check(_tree(tmp_path, locales=("hi", "en", "en-XA"), debug=("en",)))[0]
    assert any("first entry must be the default language" in e for e in errors)
    assert any("debug locales_config.xml lacks release languages" in e for e in errors)
    assert any("pseudo-locale en-XA" in e for e in errors)


def test_comment_warnings_are_opt_in(tmp_path):
    tree = _tree(tmp_path, app=['<string name="x">Hi %1$s</string>', "<!-- %1$s = name -->", '<string name="y">Hi %1$s</string>'])
    assert check_i18n.check(tree)[1] == []
    warnings = check_i18n.check(tree, comments=True)[1]
    assert warnings == ["app/strings.xml: x has placeholders but no translator comment"]


def test_tag_to_qualifier():
    assert check_i18n.tag_to_qualifier("hi") == "hi"
    assert check_i18n.tag_to_qualifier("pt-BR") == "pt-rBR"
    assert check_i18n.tag_to_qualifier("es-419") == "es-r419"
    assert check_i18n.tag_to_qualifier("zh-Hans") == "b+zh+Hans"
    assert check_i18n.tag_to_qualifier("en-XA") == "en-rXA"
