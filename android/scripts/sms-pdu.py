#!/usr/bin/env python3
"""
Builds SMS-DELIVER PDUs for the Android emulator console, so test messages can come from senders that
`adb emu sms send` refuses: Indian DLT headers (`VD-HDFCBK-T`), other alphanumeric senders, or numbers
with an explicit international type. It also builds the awkward cases real networks produce (16-bit
concatenation references, parts out of order or missing, port-addressed data SMS, national language
shift tables) and SMS-STATUS-REPORT PDUs for delivery-report tests.

    ./sms-pdu.py VD-HDFCBK-T "Rs.2,500.00 debited from A/c XX1234 on 23-09-26. UPI Ref 612345678901"
    ./sms-pdu.py --send VD-HDFCBK-T "Your OTP is 482913"          # runs adb itself
    ./sms-pdu.py --send -s emulator-5556 +919876543210 "नमस्ते"     # pick a device; Unicode -> UCS-2
    ./sms-pdu.py --flash +919876543210 "Shown at once"             # class 0 (flash) SMS
    ./sms-pdu.py --pid 41 +919876543210 "Balance: Rs 900"           # replace short message type 1

Concatenation (long texts become a multipart SMS with an 8-bit reference, IEI 0x00, by default):

    ./sms-pdu.py --ref16 +919876543210 "$(printf 'x%.0s' {1..400})"   # 16-bit reference (IEI 0x08)
    ./sms-pdu.py --ref 7 --shuffle +919876543210 "<long text>"        # parts in a random order (never 1,2,3...)
    ./sms-pdu.py --order 3,1,2 +919876543210 "<long text>"            # parts in exactly this order
    ./sms-pdu.py --drop 2 +919876543210 "<long text>"                 # part 2 never arrives (repeatable)

Port-addressed and binary SMS (IEI 0x05, 16-bit ports; --hex sends 8-bit data, DCS 0x04):

    ./sms-pdu.py --port 2948 --hex 0106 +919876543210                # data SMS to port 2948 (source 0)
    ./sms-pdu.py --port 16001:9200 +919876543210 "text on a port"    # destination 16001, source 9200
    ./sms-pdu.py --hex DEADBEEF +919876543210                        # 8-bit SMS without a port

National language shift tables (3GPP TS 23.038 A.2 / A.3; IEI 0x24 single shift, IEI 0x25 locking shift),
GSM 7-bit instead of UCS-2, so up to 152 Hindi characters fit in one SMS instead of 70:

    ./sms-pdu.py --nls hi +919876543210 "नमस्ते, आपका OTP 482913 है।"     # locking + single shift (Hindi)
    ./sms-pdu.py --nls bn --nls-tables locking +8801712345678 "আমি"     # Bengali, locking shift only

Delivery reports: an SMS-STATUS-REPORT PDU for the message with reference --mr, from the recipient given as
the first argument. The emulator console cannot inject status reports, so this prints only the hex PDU
(for unit tests and fixtures, e.g. SmsMessage.createFromPdu(pdu, "3gpp")):

    ./sms-pdu.py --status-report --mr 42 --st 00 +919876543210        # delivered
    ./sms-pdu.py --status-report --mr 42 --st 41 +919876543210        # permanent error (incompatible destination)

Prints one `adb emu sms pdu <hex>` line per part (long texts become a concatenated multipart SMS).
Run it on the machine where the emulator runs; needs only Python 3 (and adb on PATH for --send).
No dependencies, so it can be copied anywhere. Tests: android/scripts/tests/test_sms_pdu.py (pytest).
"""

import argparse
import datetime
import random
import subprocess
import sys

# GSM 03.38 default alphabet (index = septet value) and the escape-prefixed extension table.
GSM_BASIC = (
    "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\x1bÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
    "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
)
GSM_EXT = {"\f": 0x0A, "^": 0x14, "{": 0x28, "}": 0x29, "\\": 0x2F, "[": 0x3C, "~": 0x3D, "]": 0x3E, "|": 0x40, "€": 0x65}
ESC = 0x1B

# National language tables from 3GPP TS 23.038 Annex A, as the Android framework's GsmAlphabet has them (the
# decoder Dak's platform uses). Index = septet value. In locking shift tables a space other than 0x20 and U+FFFF
# (the escape position 0x1B) are unused; in single shift tables every space is unused.
NLS_LANGUAGES = {"bn": 4, "hi": 6}  # national language identifiers (TS 23.038 6.2.1.2.4)
NLS_LOCKING = {
    "bn": (
        "\u0981\u0982\u0983\u0985\u0986\u0987\u0988\u0989\u098a\u098b\x0a\u098c \x0d \u098f"
        "\u0990  \u0993\u0994\u0995\u0996\u0997\u0998\u0999\u099a\uffff\u099b\u099c\u099d\u099e"
        " !\u099f\u09a0\u09a1\u09a2\u09a3\u09a4)(\u09a5\u09a6,\u09a7.\u09a8"
        "0123456789:; \u09aa\u09ab?"
        "\u09ac\u09ad\u09ae\u09af\u09b0 \u09b2   \u09b6\u09b7\u09b8\u09b9\u09bc\u09bd"
        "\u09be\u09bf\u09c0\u09c1\u09c2\u09c3\u09c4  \u09c7\u09c8  \u09cb\u09cc\u09cd"
        "\u09ceabcdefghijklmno"
        "pqrstuvwxyz\u09d7\u09dc\u09dd\u09f0\u09f1"
    ),
    "hi": (
        "\u0901\u0902\u0903\u0905\u0906\u0907\u0908\u0909\u090a\u090b\x0a\u090c\u090d\x0d\u090e\u090f"
        "\u0910\u0911\u0912\u0913\u0914\u0915\u0916\u0917\u0918\u0919\u091a\uffff\u091b\u091c\u091d\u091e"
        " !\u091f\u0920\u0921\u0922\u0923\u0924)(\u0925\u0926,\u0927.\u0928"
        "0123456789:;\u0929\u092a\u092b?"
        "\u092c\u092d\u092e\u092f\u0930\u0931\u0932\u0933\u0934\u0935\u0936\u0937\u0938\u0939\u093c\u093d"
        "\u093e\u093f\u0940\u0941\u0942\u0943\u0944\u0945\u0946\u0947\u0948\u0949\u094a\u094b\u094c\u094d"
        "\u0950abcdefghijklmno"
        "pqrstuvwxyz\u0972\u097b\u097c\u097e\u097f"
    ),
}
NLS_SINGLE = {
    "bn": (
        "@\xa3$\xa5\xbf\"\xa4%&'\x0c*+ -/"
        "<=>\xa1^\xa1_#*\u09e6\u09e7 \u09e8\u09e9\u09ea\u09eb"
        "\u09ec\u09ed\u09ee\u09ef\u09df\u09e0\u09e1\u09e2{}\u09e3\u09f2\u09f3\u09f4\u09f5\\"
        "\u09f6\u09f7\u09f8\u09f9\u09fa       [~] "
        "|ABCDEFGHIJKLMNO"
        "PQRSTUVWXYZ     "
        "     \u20ac          "
        "                "
    ),
    "hi": (
        "@\xa3$\xa5\xbf\"\xa4%&'\x0c*+ -/"
        "<=>\xa1^\xa1_#*\u0964\u0965 \u0966\u0967\u0968\u0969"
        "\u096a\u096b\u096c\u096d\u096e\u096f\u0951\u0952{}\u0953\u0954\u0958\u0959\u095a\\"
        "\u095b\u095c\u095d\u095e\u095f\u0960\u0961\u0962\u0963\u0970\u0971 [~] "
        "|ABCDEFGHIJKLMNO"
        "PQRSTUVWXYZ     "
        "     \u20ac          "
        "                "
    ),
}

MAX_ALNUM_SENDER = 11  # TP-OA holds at most 10 octets = 11 packed septets
MAX_UD_OCTETS = 140  # TP-UD, header included
DCS_GSM, DCS_8BIT, DCS_UCS2 = 0x00, 0x04, 0x08


def gsm_septets(text):
    """Text as GSM-7 septets, or None if any character is outside the default alphabet + extension."""
    out = []
    for ch in text:
        if ch in GSM_EXT:
            out += [ESC, GSM_EXT[ch]]
        elif ch in GSM_BASIC and ch != "\x1b":
            out.append(GSM_BASIC.index(ch))
        else:
            return None
    return out


def _reverse(table, space_at=None):
    """char -> septet for a 128-entry table; first occurrence wins, unused slots are skipped."""
    out = {}
    for i, ch in enumerate(table):
        if ch == "\uffff" or (ch == " " and i != space_at):
            continue
        out.setdefault(ch, i)
    return out


def nls_septets(text, lang, tables="both"):
    """Text as septets using the national locking and/or single shift table of `lang`, or None if it does not fit.

    tables: "both" (locking + single shift), "locking" (default extension table as the single shift), or
    "single" (default alphabet as the locking table).
    """
    locking = NLS_LOCKING[lang] if tables in ("both", "locking") else GSM_BASIC
    base = _reverse(locking, space_at=0x20)
    if tables in ("both", "single"):
        shift = _reverse(NLS_SINGLE[lang])
    else:
        shift = dict(GSM_EXT)
    out = []
    for ch in text:
        if ch in base and base[ch] != ESC:
            out.append(base[ch])
        elif ch in shift:
            out += [ESC, shift[ch]]
        else:
            return None
    return out


def pack_septets(septets, fill_bits=0):
    """Packs 7-bit values LSB-first, after `fill_bits` zero bits (used to align after a UDH)."""
    acc, nbits, out = 0, fill_bits, bytearray()
    for s in septets:
        acc |= s << nbits
        nbits += 7
        while nbits >= 8:
            out.append(acc & 0xFF)
            acc >>= 8
            nbits -= 8
    if nbits:
        out.append(acc & 0xFF)
    return bytes(out)


def swap_semi_octets(digits):
    if len(digits) % 2:
        digits += "F"
    return bytes(int(digits[i + 1] + digits[i], 16) for i in range(0, len(digits), 2))


def encode_originator(sender):
    """TP-OA (or TP-RA): length (in useful semi-octets), type of address, value."""
    plain = sender.lstrip("+")
    if plain.isdigit():
        toa = 0x91 if sender.startswith("+") else 0x81  # international / unknown, ISDN numbering plan
        return bytes([len(plain), toa]) + swap_semi_octets(plain)
    if len(sender) > MAX_ALNUM_SENDER:
        sys.exit(f"alphanumeric sender '{sender}' is longer than {MAX_ALNUM_SENDER} characters")
    septets = gsm_septets(sender)
    if septets is None or ESC in septets:
        sys.exit(f"alphanumeric sender '{sender}' must use plain GSM characters")
    packed = pack_septets(septets)
    semi_octets = (len(septets) * 7 + 3) // 4
    return bytes([semi_octets, 0xD0]) + packed  # 0xD0: alphanumeric, GSM 7-bit


def encode_timestamp(when):
    """TP-SCTS: YYMMDDhhmmss + zone in quarter hours, each pair semi-octet swapped."""
    fields = when.strftime("%y%m%d%H%M%S")
    stamp = swap_semi_octets(fields)
    quarters = int(when.utcoffset().total_seconds() // 900)
    tz = swap_semi_octets(f"{abs(quarters):02d}")[0]
    if quarters < 0:
        tz |= 0x08  # sign bit sits in the (swapped) tens digit
    return stamp + bytes([tz])


def split_gsm(septets, size):
    """Splits on septet boundaries without separating an escape from the character it prefixes."""
    parts, i = [], 0
    while i < len(septets):
        end = min(i + size, len(septets))
        if end < len(septets) and septets[end - 1] == ESC:
            end -= 1
        parts.append(septets[i:end])
        i = end
    return parts


def split_ucs2(text, size):
    units = text.encode("utf-16-be") if isinstance(text, str) else text
    parts, i = [], 0
    while i < len(units):
        end = min(i + size * 2, len(units))
        if end < len(units) and 0xD8 <= units[end - 2] <= 0xDB:  # don't split a surrogate pair
            end -= 2
        parts.append(units[i:end])
        i = end
    return parts


def udh_bytes(elements):
    """The user data header (UDHL + information elements), or b"" when there are none."""
    body = b"".join(elements)
    return bytes([len(body)]) + body if body else b""


def capacity(dcs, elements):
    """Characters (septets / UTF-16 units / octets) one TP-UD holds next to a header made of `elements`."""
    udh = len(udh_bytes(elements))
    if dcs == DCS_GSM:
        return (MAX_UD_OCTETS - udh) * 8 // 7  # 160 without a header, 153 with an 8-bit concat header
    if dcs == DCS_UCS2:
        return (MAX_UD_OCTETS - udh) // 2  # 70 / 67
    return MAX_UD_OCTETS - udh


def encode_body(text, data=None, nls=None, nls_tables="both"):
    """(dcs, units) for the message: GSM-7 septets, UTF-16-BE bytes, or raw octets for --hex."""
    if data is not None:
        return DCS_8BIT, bytes(data)
    if nls:
        septets = nls_septets(text, nls, nls_tables)
        if septets is None:
            sys.exit(f"text does not fit the {nls} national language tables ({nls_tables}); drop --nls for UCS-2")
        return DCS_GSM, septets
    septets = gsm_septets(text)
    if septets is not None:
        return DCS_GSM, septets
    return DCS_UCS2, text.encode("utf-16-be")


def split_units(dcs, units, size):
    if dcs == DCS_GSM:
        return split_gsm(units, size)
    if dcs == DCS_UCS2:
        return split_ucs2(units, size)
    return [units[i:i + size] for i in range(0, len(units), size)]


def build_parts(sender, text, when, ref=None, pid=0x00, flash=False, ref16=False, port=None, data=None,
                nls=None, nls_tables="both"):
    """All parts in order, as (sequence number, hex PDU)."""
    oa = encode_originator(sender)
    scts = encode_timestamp(when)
    dcs, units = encode_body(text, data, nls, nls_tables)

    base = []  # information elements every part carries
    if port is not None:
        dest, src = port
        base.append(bytes([0x05, 0x04]) + dest.to_bytes(2, "big") + src.to_bytes(2, "big"))
    if nls:
        lang = NLS_LANGUAGES[nls]
        if nls_tables in ("both", "single"):
            base.append(bytes([0x24, 0x01, lang]))
        if nls_tables in ("both", "locking"):
            base.append(bytes([0x25, 0x01, lang]))

    concat_len = 6 if ref16 else 5  # IEI, IEDL, reference (1 or 2 octets), total, sequence
    length = len(units) // 2 if dcs == DCS_UCS2 else len(units)  # UTF-16 code units for UCS-2
    if length <= capacity(dcs, base):
        chunks = [units]
    else:
        chunks = split_units(dcs, units, capacity(dcs, [bytes(concat_len)] + base))
    multipart = len(chunks) > 1
    if multipart and len(chunks) > 255:
        sys.exit("message too long (more than 255 parts)")
    if ref is None:
        ref = random.randint(0, 0xFFFF if ref16 else 0xFF)
    elif not 0 <= ref <= (0xFFFF if ref16 else 0xFF):
        sys.exit("--ref must fit in " + ("16 bits (0..65535)" if ref16 else "8 bits (0..255); use --ref16"))

    parts = []
    for seq, chunk in enumerate(chunks, start=1):
        elements = list(base)
        if multipart:
            if ref16:
                elements.insert(0, bytes([0x08, 0x04]) + ref.to_bytes(2, "big") + bytes([len(chunks), seq]))
            else:
                elements.insert(0, bytes([0x00, 0x03, ref, len(chunks), seq]))
        udh = udh_bytes(elements)
        if dcs == DCS_GSM:
            fill = (7 - (len(udh) * 8) % 7) % 7 if udh else 0
            ud = udh + pack_septets(chunk, fill)
            udl = (len(udh) * 8 + fill) // 7 + len(chunk)  # counted in septets, header included
        else:
            ud = udh + bytes(chunk)
            udl = len(ud)
        first = 0x04 | (0x40 if udh else 0)  # SMS-DELIVER, no more messages, UDHI when there is a header
        # DCS general coding group with a message class: bit 4 set, class in bits 1..0 (class 0 = flash).
        coding = (dcs | 0x10) if flash else dcs
        tpdu = bytes([first]) + oa + bytes([pid, coding]) + scts + bytes([udl]) + ud
        parts.append((seq, "00" + tpdu.hex().upper()))  # 00: no SMSC address, the emulator's modem fills none in
    return parts


def build_pdus(sender, text, when, ref=None, pid=0x00, flash=False, **options):
    """Hex PDUs of every part, in order."""
    return [pdu for _, pdu in build_parts(sender, text, when, ref=ref, pid=pid, flash=flash, **options)]


def arrange(parts, order=None, shuffle=False, drop=(), rng=random):
    """Reorders and drops parts (1-based sequence numbers) to simulate a network that delivers them badly."""
    count = len(parts)
    if (order or shuffle or drop) and count < 2:
        sys.exit("--order / --shuffle / --drop need a multipart message (make the text longer)")
    by_seq = dict(parts)
    if order:
        if sorted(order) != list(range(1, count + 1)):
            sys.exit(f"--order must list each part 1..{count} exactly once")
        seqs = list(order)
    else:
        seqs = list(range(1, count + 1))
        if shuffle:
            while seqs == sorted(seqs):  # a shuffle that leaves the order intact tests nothing
                rng.shuffle(seqs)
    for n in drop:
        if not 1 <= n <= count:
            sys.exit(f"--drop {n}: the message has parts 1..{count}")
    return [by_seq[s] for s in seqs if s not in set(drop)]


def build_status_report(recipient, when, mr, status, delivered_after=datetime.timedelta(seconds=5)):
    """SMS-STATUS-REPORT (3GPP TS 23.040 9.2.2.3) for the submitted message with reference `mr`, as hex."""
    first = 0x06  # TP-MTI = 10 (status report), TP-MMS = 1 (no more messages), TP-SRQ = 0 (SMS-SUBMIT result)
    tpdu = (bytes([first, mr]) + encode_originator(recipient) + encode_timestamp(when)
            + encode_timestamp(when + delivered_after) + bytes([status]))
    return "00" + tpdu.hex().upper()


def parse_port(value):
    dest, _, src = value.partition(":")
    try:
        ports = int(dest), int(src) if src else 0
    except ValueError:
        raise argparse.ArgumentTypeError("expected DEST or DEST:SRC (decimal port numbers)")
    if not all(0 <= p <= 0xFFFF for p in ports):
        raise argparse.ArgumentTypeError("ports are 16-bit (0..65535)")
    return ports


def parse_hex(value):
    try:
        return bytes.fromhex(value.replace(" ", ""))
    except ValueError:
        raise argparse.ArgumentTypeError("expected hex octets, e.g. 0106FF")


def parse_order(value):
    try:
        return [int(v) for v in value.split(",")]
    except ValueError:
        raise argparse.ArgumentTypeError("expected part numbers, e.g. 3,1,2")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sender", help="DLT header (VD-HDFCBK-T), other alphanumeric id, or number (+919876543210); "
                                   "the recipient for --status-report")
    ap.add_argument("text", nargs="?", help="message body; Unicode (₹, Hindi) switches to UCS-2")
    ap.add_argument("--send", action="store_true", help="run `adb emu sms pdu` for each part instead of printing")
    ap.add_argument("-s", "--serial", help="adb device serial, e.g. emulator-5554 (when several are running)")
    ap.add_argument("--minutes-ago", type=int, default=0, help="backdate the service-centre timestamp")
    ap.add_argument("--pid", type=lambda v: int(v, 16), default=0x00,
                    help="TP-PID in hex: 40 = type 0 (silent), 41..47 = replace short message type 1..7")
    ap.add_argument("--flash", action="store_true", help="message class 0 (flash SMS): DCS 0x10 / 0x18 for UCS-2")
    concat = ap.add_argument_group("concatenation")
    concat.add_argument("--ref16", action="store_true", help="16-bit concatenation reference (IEI 0x08)")
    concat.add_argument("--ref", type=int, help="concatenation reference (default: random)")
    concat.add_argument("--order", type=parse_order, help="send parts in this order, e.g. 3,1,2")
    concat.add_argument("--shuffle", action="store_true", help="send parts in a random order (never in sequence)")
    concat.add_argument("--seed", type=int, help="random seed for --shuffle (reproducible order)")
    concat.add_argument("--drop", type=int, action="append", default=[], metavar="N",
                        help="leave out part N (1-based; repeatable)")
    data = ap.add_argument_group("port addressing and binary data")
    data.add_argument("--port", type=parse_port, metavar="DEST[:SRC]",
                      help="application port addressing, 16-bit ports (IEI 0x05); source defaults to 0")
    data.add_argument("--hex", type=parse_hex, metavar="HEX", help="8-bit binary payload (DCS 0x04) instead of text")
    nls = ap.add_argument_group("national language shift tables (3GPP TS 23.038)")
    nls.add_argument("--nls", choices=sorted(NLS_LANGUAGES), help="encode with the language's shift tables")
    nls.add_argument("--nls-tables", choices=["both", "locking", "single"], default="both",
                     help="which tables: locking (IEI 0x25) and/or single shift (IEI 0x24); default both")
    report = ap.add_argument_group("delivery reports")
    report.add_argument("--status-report", action="store_true",
                        help="print an SMS-STATUS-REPORT PDU (hex only) instead of an SMS-DELIVER")
    report.add_argument("--mr", type=int, default=0, help="TP-MR of the reported message (0..255)")
    report.add_argument("--st", type=lambda v: int(v, 16), default=0x00,
                        help="TP-Status in hex: 00 delivered, 20..3F still trying, 40..7F failed")
    args = ap.parse_args()
    if not 0 <= args.pid <= 0xFF:
        sys.exit("--pid must be one octet (00..FF)")

    when = datetime.datetime.now().astimezone() - datetime.timedelta(minutes=args.minutes_ago)
    if args.status_report:
        if args.send:
            sys.exit("the emulator console cannot inject status reports; --status-report prints the PDU only")
        if not 0 <= args.mr <= 0xFF or not 0 <= args.st <= 0xFF:
            sys.exit("--mr and --st must be one octet")
        print(build_status_report(args.sender, when, args.mr, args.st))
        return
    if (args.text is None) == (args.hex is None):
        sys.exit("give either a text or --hex")
    if args.hex is not None and args.nls:
        sys.exit("--nls applies to text, not to --hex data")
    if args.order and args.shuffle:
        sys.exit("use either --order or --shuffle")

    parts = build_parts(args.sender, args.text, when, ref=args.ref, pid=args.pid, flash=args.flash,
                        ref16=args.ref16, port=args.port, data=args.hex, nls=args.nls, nls_tables=args.nls_tables)
    pdus = arrange(parts, order=args.order, shuffle=args.shuffle, drop=args.drop, rng=random.Random(args.seed))
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    for pdu in pdus:
        if args.send:
            result = subprocess.run(adb + ["emu", "sms", "pdu", pdu], capture_output=True, text=True)
            reply = (result.stdout + result.stderr).strip()
            if result.returncode != 0 or "KO" in reply:
                sys.exit(f"adb rejected the PDU: {reply or result.returncode}")
        else:
            print(" ".join(adb + ["emu", "sms", "pdu", pdu]))


if __name__ == "__main__":
    main()
