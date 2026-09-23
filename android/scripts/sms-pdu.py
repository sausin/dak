#!/usr/bin/env python3
"""
Builds SMS-DELIVER PDUs for the Android emulator console, so test messages can come from senders that
`adb emu sms send` refuses: Indian DLT headers (`VD-HDFCBK-T`), other alphanumeric senders, or numbers
with an explicit international type.

    ./sms-pdu.py VD-HDFCBK-T "Rs.2,500.00 debited from A/c XX1234 on 23-09-26. UPI Ref 612345678901"
    ./sms-pdu.py --send VD-HDFCBK-T "Your OTP is 482913"          # runs adb itself
    ./sms-pdu.py --send -s emulator-5556 +919876543210 "नमस्ते"     # pick a device; Unicode -> UCS-2
    ./sms-pdu.py --flash +919876543210 "Shown at once"             # class 0 (flash) SMS
    ./sms-pdu.py --pid 41 +919876543210 "Balance: Rs 900"           # replace short message type 1

Prints one `adb emu sms pdu <hex>` line per part (long texts become a concatenated multipart SMS).
Run it on the machine where the emulator runs; needs only Python 3 (and adb on PATH for --send).
No dependencies, so it can be copied anywhere.
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

MAX_ALNUM_SENDER = 11  # TP-OA holds at most 10 octets = 11 packed septets
SINGLE_GSM, PART_GSM = 160, 153  # septets; a part loses 7 to the concatenation header
SINGLE_UCS2, PART_UCS2 = 70, 67  # UTF-16 code units


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
    """TP-OA: length (in useful semi-octets), type of address, value."""
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
    units = text.encode("utf-16-be")
    parts, i = [], 0
    while i < len(units):
        end = min(i + size * 2, len(units))
        if end < len(units) and 0xD8 <= units[end - 2] <= 0xDB:  # don't split a surrogate pair
            end -= 2
        parts.append(units[i:end])
        i = end
    return parts


def build_pdus(sender, text, when, ref=None, pid=0x00, flash=False):
    oa = encode_originator(sender)
    scts = encode_timestamp(when)
    septets = gsm_septets(text)
    if septets is not None:
        dcs = 0x00
        chunks = [septets] if len(septets) <= SINGLE_GSM else split_gsm(septets, PART_GSM)
    else:
        dcs = 0x08
        if len(text.encode("utf-16-be")) <= SINGLE_UCS2 * 2:
            chunks = [text.encode("utf-16-be")]
        else:
            chunks = split_ucs2(text, PART_UCS2)

    multipart = len(chunks) > 1
    if multipart and len(chunks) > 255:
        sys.exit("message too long (more than 255 parts)")
    ref = random.randint(0, 255) if ref is None else ref
    pdus = []
    for seq, chunk in enumerate(chunks, start=1):
        udh = bytes([0x05, 0x00, 0x03, ref, len(chunks), seq]) if multipart else b""
        if dcs == 0x00:
            fill = (7 - (len(udh) * 8) % 7) % 7 if udh else 0
            ud = udh + pack_septets(chunk, fill)
            udl = (len(udh) * 8 + fill) // 7 + len(chunk)  # counted in septets, header included
        else:
            ud = udh + chunk
            udl = len(ud)
        first = 0x04 | (0x40 if multipart else 0)  # SMS-DELIVER, no more messages, UDHI when split
        # DCS general coding group with a message class: bit 4 set, class in bits 1..0 (class 0 = flash).
        coding = (dcs | 0x10) if flash else dcs
        tpdu = bytes([first]) + oa + bytes([pid, coding]) + scts + bytes([udl]) + ud
        pdus.append("00" + tpdu.hex().upper())  # 00: no SMSC address, the emulator's modem fills none in
    return pdus


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sender", help="DLT header (VD-HDFCBK-T), other alphanumeric id, or number (+919876543210)")
    ap.add_argument("text", help="message body; Unicode (₹, Hindi) switches to UCS-2")
    ap.add_argument("--send", action="store_true", help="run `adb emu sms pdu` for each part instead of printing")
    ap.add_argument("-s", "--serial", help="adb device serial, e.g. emulator-5554 (when several are running)")
    ap.add_argument("--minutes-ago", type=int, default=0, help="backdate the service-centre timestamp")
    ap.add_argument("--pid", type=lambda v: int(v, 16), default=0x00,
                    help="TP-PID in hex: 40 = type 0 (silent), 41..47 = replace short message type 1..7")
    ap.add_argument("--flash", action="store_true", help="message class 0 (flash SMS): DCS 0x10 / 0x18 for UCS-2")
    args = ap.parse_args()
    if not 0 <= args.pid <= 0xFF:
        sys.exit("--pid must be one octet (00..FF)")

    when = datetime.datetime.now().astimezone() - datetime.timedelta(minutes=args.minutes_ago)
    pdus = build_pdus(args.sender, args.text, when, pid=args.pid, flash=args.flash)
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
