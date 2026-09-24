"""Tests for scripts/sms-pdu.py. Run: python3 -m pytest android/scripts/tests

The independent decoders (python-gsmmodem-new, smspdudecoder) are optional: install them with
`pip install python-gsmmodem-new smspdudecoder` (CI does); the tests that need them are skipped otherwise.
Neither knows the national language shift tables, so those PDUs are checked with the small decoder below.
"""

import datetime
import importlib.util
import pathlib
import random
import subprocess
import sys

import pytest

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "sms-pdu.py"
_spec = importlib.util.spec_from_file_location("sms_pdu", SCRIPT)
sms_pdu = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sms_pdu)

WHEN = datetime.datetime(2026, 9, 23, 10, 11, 12, tzinfo=datetime.timezone(datetime.timedelta(hours=5, minutes=30)))
SENDER = "+919876543210"
LONG = "Hello world " * 20  # 240 GSM characters: two parts


# --- a minimal SMS-DELIVER decoder, written from 3GPP TS 23.040 / 23.038 independently of the script --------

def _address_octets(tpdu, i):
    digits = tpdu[i]
    return 2 + (digits + 1) // 2


def parse_deliver(hex_pdu):
    raw = bytes.fromhex(hex_pdu)
    smsc_len = raw[0]
    tpdu = raw[1 + smsc_len:]
    first = tpdu[0]
    i = 1 + _address_octets(tpdu, 1)
    pid, dcs = tpdu[i], tpdu[i + 1]
    i += 2 + 7  # PID, DCS, SCTS
    udl = tpdu[i]
    ud = tpdu[i + 1:]
    elements, header_len = {}, 0
    if first & 0x40:
        header_len = ud[0] + 1
        j = 1
        while j < header_len:
            iei, length = ud[j], ud[j + 1]
            elements[iei] = bytes(ud[j + 2:j + 2 + length])
            j += 2 + length
    return {"first": first, "pid": pid, "dcs": dcs, "udl": udl, "ud": bytes(ud), "header_len": header_len,
            "elements": elements}


def unpack_septets(ud, udl, header_len):
    """Septets after the header (TP-UDL counts the header's septets, fill bits included)."""
    header_septets = (header_len * 8 + 6) // 7
    bits = int.from_bytes(ud, "little")
    return [(bits >> (7 * k)) & 0x7F for k in range(header_septets, udl)]


def decode_septets(septets, locking, single):
    out, escaped = [], False
    for s in septets:
        if escaped:
            out.append(single[s])
            escaped = False
        elif s == 0x1B:
            escaped = True
        else:
            out.append(locking[s])
    return "".join(out)


def default_single_shift():
    table = [" "] * 128
    for ch, septet in sms_pdu.GSM_EXT.items():
        table[septet] = ch
    return "".join(table)


def decode_text(hex_pdu):
    """Decodes a GSM-7 (with national tables from the UDH) or UCS-2 SMS-DELIVER to text."""
    p = parse_deliver(hex_pdu)
    if p["dcs"] & 0x0C == 0x08:
        return p["ud"][p["header_len"]:].decode("utf-16-be")
    locking, single = sms_pdu.GSM_BASIC, default_single_shift()
    if 0x25 in p["elements"]:
        locking = sms_pdu.NLS_LOCKING[_lang(p["elements"][0x25][0])]
    if 0x24 in p["elements"]:
        single = sms_pdu.NLS_SINGLE[_lang(p["elements"][0x24][0])]
    return decode_septets(unpack_septets(p["ud"], p["udl"], p["header_len"]), locking, single)


def _lang(identifier):
    return {v: k for k, v in sms_pdu.NLS_LANGUAGES.items()}[identifier]


def reassemble(pdus):
    parts = {}
    for pdu in pdus:
        p = parse_deliver(pdu)
        concat = p["elements"].get(0x00) or p["elements"].get(0x08)
        seq = concat[-1] if concat else 1
        parts[seq] = decode_text(pdu)
    return "".join(parts[k] for k in sorted(parts))


# --- defaults are unchanged -------------------------------------------------------------------------------------

def test_default_single_part_is_unchanged():
    # Captured from the script before the new flags were added (537393d).
    assert sms_pdu.build_pdus("VD-HDFCBK-T", "Your OTP is 482913", WHEN, ref=9) == [
        "000414D056620B49340E85CB161500006290320111212212D9775D0E7A52A1A0F41C44C3C972B119"
    ]
    assert sms_pdu.build_pdus(SENDER, "नमस्ते " * 12, WHEN, ref=9, flash=True) == [
        "00440C911989674523010018629032011121228C0500030902010928092E0938094D0924094700200928092E0938094D09240947"
        "00200928092E0938094D0924094700200928092E0938094D0924094700200928092E0938094D0924094700200928092E0938094D"
        "0924094700200928092E0938094D0924094700200928092E0938094D0924094700200928092E0938094D09240947002009280"
        "92E0938094D",
        "00440C91198967452301001862903201112122280500030902020924094700200928092E0938094D0924094700200928092E09"
        "38094D092409470020",
    ]


def test_default_multipart_uses_8bit_reference_and_153_septets():
    pdus = sms_pdu.build_pdus(SENDER, LONG, WHEN, ref=9)
    assert len(pdus) == 2
    first = parse_deliver(pdus[0])
    assert first["first"] == 0x44 and first["elements"] == {0x00: bytes([9, 2, 1])}
    assert first["udl"] == 7 + 153
    assert reassemble(pdus) == LONG


def test_short_text_has_no_header():
    p = parse_deliver(sms_pdu.build_pdus(SENDER, "Hi", WHEN)[0])
    assert p["first"] == 0x04 and p["elements"] == {}


# --- 16-bit reference, order, drop ------------------------------------------------------------------------------

def test_ref16_header_and_capacity():
    pdus = sms_pdu.build_pdus(SENDER, LONG, WHEN, ref=0x1234, ref16=True)
    assert len(pdus) == 2
    first = parse_deliver(pdus[0])
    assert first["elements"] == {0x08: bytes([0x12, 0x34, 2, 1])}
    assert first["udl"] == 8 + 152  # a 7-octet header is exactly 8 septets, no fill bits
    assert reassemble(pdus) == LONG


def test_ref16_ucs2_capacity():
    text = "आ" * 71  # one more than fits a single UCS-2 SMS
    pdus = sms_pdu.build_pdus(SENDER, text, WHEN, ref=1, ref16=True)
    assert [len(parse_deliver(p)["ud"]) for p in pdus] == [7 + 66 * 2, 7 + 5 * 2]
    assert reassemble(pdus) == text


def test_escapes_are_not_split_across_parts():
    text = "{[x]}" * 70  # 490 septets, many escape pairs at part boundaries
    for ref16 in (False, True):
        pdus = sms_pdu.build_pdus(SENDER, text, WHEN, ref=2, ref16=ref16)
        assert reassemble(pdus) == text
        assert all(parse_deliver(p)["udl"] <= 160 for p in pdus)


def test_ref_out_of_range():
    with pytest.raises(SystemExit):
        sms_pdu.build_pdus(SENDER, LONG, WHEN, ref=300)
    assert sms_pdu.build_pdus(SENDER, LONG, WHEN, ref=300, ref16=True)


def test_order_shuffle_and_drop():
    parts = sms_pdu.build_parts(SENDER, "abc " * 120, WHEN, ref=5)  # 480 septets: 4 parts
    assert [seq for seq, _ in parts] == [1, 2, 3, 4]
    pdus = dict(parts)
    assert sms_pdu.arrange(parts, order=[3, 1, 4, 2]) == [pdus[3], pdus[1], pdus[4], pdus[2]]
    for seed in range(20):
        shuffled = sms_pdu.arrange(parts, shuffle=True, rng=random.Random(seed))
        assert shuffled != [pdu for _, pdu in parts] and sorted(shuffled) == sorted(pdus.values())
    assert sms_pdu.arrange(parts, drop=[2]) == [pdus[1], pdus[3], pdus[4]]
    assert sms_pdu.arrange(parts, order=[4, 3, 2, 1], drop=[1, 3]) == [pdus[4], pdus[2]]
    assert reassemble(sms_pdu.arrange(parts, shuffle=True, rng=random.Random(1))) == "abc " * 120


@pytest.mark.parametrize("kwargs", [dict(order=[1, 2, 2, 4]), dict(order=[1, 2]), dict(drop=[5])])
def test_arrange_rejects_bad_input(kwargs):
    parts = sms_pdu.build_parts(SENDER, "abc " * 120, WHEN, ref=5)
    with pytest.raises(SystemExit):
        sms_pdu.arrange(parts, **kwargs)


def test_arrange_needs_multipart():
    with pytest.raises(SystemExit):
        sms_pdu.arrange(sms_pdu.build_parts(SENDER, "Hi", WHEN), drop=[1])


# --- port addressing and 8-bit data -----------------------------------------------------------------------------

def test_port_addressed_binary():
    p = parse_deliver(sms_pdu.build_pdus(SENDER, None, WHEN, port=(2948, 0), data=bytes.fromhex("0106FF"))[0])
    assert p["first"] == 0x44 and p["dcs"] == 0x04
    assert p["elements"] == {0x05: bytes([0x0B, 0x84, 0x00, 0x00])}
    assert p["ud"][p["header_len"]:] == bytes.fromhex("0106FF") and p["udl"] == 7 + 3


def test_binary_without_port_and_flash_class():
    p = parse_deliver(sms_pdu.build_pdus(SENDER, None, WHEN, data=b"\x00\x01", flash=True)[0])
    assert p["first"] == 0x04 and p["dcs"] == 0x14 and p["ud"] == b"\x00\x01"


def test_long_binary_is_split_with_port_on_every_part():
    data = bytes(range(256))
    pdus = sms_pdu.build_pdus(SENDER, None, WHEN, ref=3, port=(9200, 9201), data=data)
    parsed = [parse_deliver(pdu) for pdu in pdus]
    assert [len(p["ud"]) for p in parsed] == [140, 140]  # 12-octet header (concat + ports) + 128 data octets
    assert all(p["elements"][0x05] == bytes([0x23, 0xF0, 0x23, 0xF1]) for p in parsed)
    assert b"".join(p["ud"][p["header_len"]:] for p in parsed) == data


def test_port_addressed_text():
    pdus = sms_pdu.build_pdus(SENDER, LONG, WHEN, ref=4, port=(16001, 9200))
    assert all(parse_deliver(p)["elements"][0x05] == bytes([0x3E, 0x81, 0x23, 0xF0]) for p in pdus)
    assert reassemble(pdus) == LONG


# --- national language shift tables -----------------------------------------------------------------------------

def test_tables_match_3gpp_spot_checks():
    # Spot checks of the tables (3GPP TS 23.038 A.2.4 / A.2.6 / A.3.4 / A.3.6 as Android's GsmAlphabet has them).
    hi_lock, hi_single = sms_pdu.NLS_LOCKING["hi"], sms_pdu.NLS_SINGLE["hi"]
    bn_lock, bn_single = sms_pdu.NLS_LOCKING["bn"], sms_pdu.NLS_SINGLE["bn"]
    assert all(len(t) == 128 for t in (hi_lock, hi_single, bn_lock, bn_single))
    assert (hi_lock[0x00], hi_lock[0x03], hi_lock[0x0A], hi_lock[0x20], hi_lock[0x2F]) == ("ँ", "अ", "\n", " ", "न")
    assert (hi_lock[0x30], hi_lock[0x60], hi_lock[0x61], hi_lock[0x7F]) == ("0", "ॐ", "a", "ॿ")
    assert (hi_single[0x19], hi_single[0x1A], hi_single[0x1C], hi_single[0x41], hi_single[0x65]) == (
        "।", "॥", "०", "A", "€")
    assert (bn_lock[0x03], bn_lock[0x2F], bn_lock[0x60]) == ("অ", "ন", "ৎ")
    assert (bn_single[0x19], bn_single[0x1A], bn_single[0x41]) == ("০", "১", "A")


@pytest.mark.parametrize("lang,tables,text", [
    ("hi", "both", "नमस्ते, आपका OTP 482913 है। Rs. 500 {ok}"),
    # Locking shift only: the single shift is the default extension table, so no capitals or danda.
    ("hi", "locking", "नमस्ते, आपका otp 482913 है. {ok} [x] ~ €"),
    ("bn", "both", "আমি ভালো আছি, OTP ১২৩৪ / 1234"),
    ("bn", "locking", "আমি ভালো আছি, otp 1234 {ok}"),
])
def test_nls_round_trip(lang, tables, text):
    pdus = sms_pdu.build_pdus(SENDER, text, WHEN, nls=lang, nls_tables=tables)
    p = parse_deliver(pdus[0])
    assert p["dcs"] == 0x00 and p["first"] & 0x40
    ident = bytes([sms_pdu.NLS_LANGUAGES[lang]])
    assert p["elements"].get(0x25) == ident
    assert p["elements"].get(0x24) == (ident if tables == "both" else None)
    assert reassemble(pdus) == text


def test_nls_single_shift_only():
    text = "Price: ₹? no. Total १२३"  # Devanagari digits from the Hindi single shift table, basic Latin otherwise
    text = text.replace("₹", "Rs")
    pdus = sms_pdu.build_pdus(SENDER, text, WHEN, nls="hi", nls_tables="single")
    p = parse_deliver(pdus[0])
    assert p["elements"] == {0x24: bytes([6])}
    assert reassemble(pdus) == text


def test_nls_fits_more_than_ucs2():
    text = "नमस्ते " * 21  # 147 characters: one GSM-7 part with both tables, three UCS-2 parts without
    assert len(sms_pdu.build_pdus(SENDER, text, WHEN, nls="hi")) == 1
    assert len(sms_pdu.build_pdus(SENDER, text, WHEN)) == 3
    long_text = text * 3
    pdus = sms_pdu.build_pdus(SENDER, long_text, WHEN, ref=8, nls="hi")
    assert all(set(parse_deliver(p)["elements"]) == {0x00, 0x24, 0x25} for p in pdus)
    assert reassemble(pdus) == long_text


def test_nls_rejects_characters_outside_the_tables():
    with pytest.raises(SystemExit):
        sms_pdu.build_pdus(SENDER, "नमस्ते ₹", WHEN, nls="hi")


# --- status reports ---------------------------------------------------------------------------------------------

def test_status_report_layout():
    raw = bytes.fromhex(sms_pdu.build_status_report(SENDER, WHEN, 42, 0x41))
    assert raw[0] == 0x00  # no SMSC
    tpdu = raw[1:]
    assert tpdu[0] & 0x03 == 0x02 and tpdu[1] == 42
    i = 2 + _address_octets(tpdu, 2)
    assert tpdu[i:i + 7] == sms_pdu.encode_timestamp(WHEN)
    assert tpdu[i + 7:i + 14] == sms_pdu.encode_timestamp(WHEN + datetime.timedelta(seconds=5))
    assert tpdu[i + 14] == 0x41 and len(tpdu) == i + 15


# --- independent decoders ---------------------------------------------------------------------------------------

def test_gsmmodem_decodes_ports_data_and_status_reports():
    pdu = pytest.importorskip("gsmmodem.pdu")
    d = pdu.decodeSmsPdu(sms_pdu.build_pdus(SENDER, None, WHEN, port=(2948, 7), data=b"\x01\x06")[0])
    assert d["type"] == "SMS-DELIVER" and d["number"] == SENDER
    (port,) = d["udh"]
    assert (port.id, port.destination, port.source) == (0x05, 2948, 7)
    assert d["text"] == "\x01\x06"
    ref16 = pdu.decodeSmsPdu(sms_pdu.build_pdus(SENDER, LONG, WHEN, ref=0xBEEF, ref16=True)[1])
    (concat,) = ref16["udh"]
    assert (concat.id, concat.reference, concat.parts, concat.number) == (0x08, 0xBEEF, 2, 2)
    report = pdu.decodeSmsPdu(sms_pdu.build_status_report(SENDER, WHEN, 42, 0x20))
    assert report["type"] == "SMS-STATUS-REPORT" and report["reference"] == 42 and report["status"] == 0x20
    assert report["number"] == SENDER
    assert report["discharge"] - report["time"] == datetime.timedelta(seconds=5)


@pytest.mark.parametrize("ref16", [False, True])
# Escape-heavy texts are left to the local decoder: smspdudecoder ignores TP-UDL and reads 7 spare trailing bits
# as an extra "@", which the spec leaves to TP-UDL to rule out.
@pytest.mark.parametrize("text", [LONG, "₹ debited " * 20, "Rs.2,500.00 debited from A/c XX1234 " * 6])
def test_smspdudecoder_reassembles(ref16, text):
    easy = pytest.importorskip("smspdudecoder.easy")
    pdus = sms_pdu.build_pdus("VD-HDFCBK-T", text, WHEN, ref=200, ref16=ref16)
    decoded = [easy.read_incoming_sms(p) for p in pdus]
    assert all(d["sender"] == "VD-HDFCBK-T" for d in decoded)
    assert [d["partial"]["part_number"] for d in decoded] == list(range(1, len(pdus) + 1))
    assert "".join(d["content"] for d in decoded) == text


# --- command line -----------------------------------------------------------------------------------------------

def run_cli(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True)


def test_cli_prints_adb_lines_in_the_requested_order():
    out = run_cli("--ref", "3", "--order", "2,1", "--drop", "1", "-s", "emulator-5556", SENDER, LONG)
    assert out.returncode == 0, out.stderr
    lines = out.stdout.splitlines()
    assert len(lines) == 1 and lines[0].startswith("adb -s emulator-5556 emu sms pdu 00")
    assert parse_deliver(lines[0].split()[-1])["elements"][0x00] == bytes([3, 2, 2])


def test_cli_status_report_prints_hex_only_and_refuses_send():
    out = run_cli("--status-report", "--mr", "7", "--st", "41", SENDER)
    assert out.returncode == 0 and bytes.fromhex(out.stdout.strip())[2] == 7
    assert run_cli("--status-report", "--send", SENDER).returncode != 0


def test_cli_needs_text_or_hex():
    assert run_cli(SENDER).returncode != 0
    assert run_cli("--hex", "01", SENDER, "text").returncode != 0
    assert run_cli("--port", "2948", "--hex", "0106", SENDER).returncode == 0
