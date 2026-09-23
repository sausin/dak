# :mms-pdu

Clean-room, pure-Kotlin (JVM, no Android) codec for OMA MMS encapsulation PDUs (OMA-MMS-ENC 1.0–1.3) with WSP
header and multipart encoding (WAP-230). Package `app.dak.mms.pdu`. Used by `:core-telephony` for MMS send and
download.

## Decode

```kotlin
when (val r = MmsPduDecoder.decode(bytes)) {          // never throws
    is PduDecodeResult.Success -> when (val pdu = r.pdu) {
        is NotificationInd -> pdu.contentLocation      // + transactionId, from, subject, messageClass, messageSize, expiry
        is RetrieveConf -> pdu.parts                   // + from/to/cc, subject, date, messageId, retrieveStatus
        is SendConf -> pdu.isOk                        // responseStatus, messageId
        is DeliveryInd, is ReadOrigInd -> Unit         // reports (ReadOrigInd decoded tolerantly: all fields optional)
        else -> Unit                                   // SendReq / NotifyRespInd / AcknowledgeInd / ReadRecInd also decode
    }
    is PduDecodeResult.Failure -> r.error              // Empty | Truncated | Malformed | UnsupportedMessageType | MissingHeader
}
MmsPduDecoder.decodeAs<SendConf>(bytes)                // typed convenience, null on failure or other type
```

- Content types: well-known codes and text forms, with parameters `charset`, `type`, `start`, `start-info`,
  `name`, `filename` (typed and untyped forms, WSP 1.1–1.4 tokens); other parameters in `otherParameters`.
- Parts: `Content-ID`, `Content-Location`, `Content-Disposition` (+ filename), binary or textual header names;
  nested multiparts are flattened. `PduPart.text()` decodes text/SMIL with the part's MIBenum charset
  (UTF-8, US-ASCII, ISO-8859-x, UTF-16/UCS-2 with or without BOM, Shift_JIS, GBK, Big5, ...; unknown → UTF-8).
- Addresses are returned without the `/TYPE=PLMN` suffix.
- Unknown headers are skipped with the generic WSP value rules; application headers are ignored. Every length is
  bounds-checked before use, so malformed input yields `PduError`, never an exception or a huge allocation.

## Encode

```kotlin
val req: SendReq = MmsMessageBuilder.build(
    to = listOf("+15551234567"), text = "Hi", subject = null,
    attachments = listOf(MmsMessageBuilder.Attachment("image/jpeg", "photo.jpg", jpegBytes)),
)                                                      // SMIL root <smil> + parts, multipart.related; start=<smil>; type=application/smil
val bytes = MmsPduEncoder.encode(req)                  // From = insert-address-token, To = <number>/TYPE=PLMN
MmsPduEncoder.encode(NotifyRespInd(transactionId, MmsStatus.RETRIEVED))
MmsPduEncoder.encode(AcknowledgeInd(transactionId))
```

All modelled PDU types are encodable (useful for fixtures). `Smil.build(items)` generates a standard
Image-over-Text layout (root-layout 320×480), one `<par>` per media item, with the text in the first image/video
slide (its caption). `MmsClientTransactions.readReceipt(...)` builds the m-read-rec-ind owed for a read message
(personal senders only).

## SMIL on receive

`SmilPresentation.order(smil, parts)` returns the part indices in the sender's slide order (unreferenced parts
last, part order when the SMIL is missing or broken). It is a bounded hand-rolled tokenizer, not an XML parser: no
DTD / entity processing (no XXE), and `src` values are only compared with the message's own part headers. File names are sanitised to `[A-Za-z0-9._-]` and
de-duplicated; they become each part's Content-Location, name parameter and `<Content-ID>`.

## Safety (hostile input)

- `MmsLimits`: `MAX_PDU_BYTES` (16 MiB, larger input is rejected), `MAX_PARTS` (256 after flattening),
  `MAX_ADDRESSES` (100 To/Cc/Bcc kept), `MAX_HEADER_TEXT_CHARS` (subject / status texts truncated to 1024).
- `MmsSafety.isDownloadableContentLocation(url)`: true only for absolute http(s) URLs with a host, no userinfo,
  no whitespace/control/non-ASCII, not loopback, ≤ 1024 chars. Check it before handing a content location to the
  platform.
- `MmsSafety.safeFileName(raw)` / `PduPart.safeFileName`: a part name with path components, control/bidi/
  zero-width characters and leading dots removed, capped at 128 chars. Use it whenever a part name is stored as a
  file name, shown, or saved. The raw `contentLocation` stays available for SMIL matching.

## Constants

`MessageType` (octets = provider `m_type`), `MmsVersion`, `ResponseStatus` (+ `describe`, `isTransient`),
`RetrieveStatus`, `MmsStatus`, `ReadStatus`, `Priority`, `MessageClass`, `MmsCharset`, `MmsAddress`.

## Tests

Hand-built byte fixtures per PDU type, exact-byte encoder checks, round trips, charset cases, and truncation /
random-mutation fuzzing that asserts no internal error escapes, and `SecurityFuzzTest` (seeded structure-aware
mutation fuzzing, ≥100k iterations under a time cap, plus limit and sanitiser cases):
`DAK_JVM_HARNESS_DIR=<dir> GRADLE=... scripts/jvm-test.sh :mms-pdu:test`.
