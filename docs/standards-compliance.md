# Dak standards and platform compliance matrix

This matrix checks the Android client (`android/`) against the specifications, platform contracts and regulations
that apply to a default SMS/MMS app. It covers only the client side. Network-side behaviour (SMSC, MMSC, DLT
scrubbing) is out of scope except where the client has to cooperate with it.

- **Snapshot:** commit `80b0844` (branch `claude/sms-android-emulator-testing-9xxtq5`). All `file:line`
  references point at that commit. Other work is editing `android/finance`, `android/classify`, `android/mms-pdu`,
  `android/core-telephony`, `android/app` and `android/backup` at the same time, so line numbers may drift.
- **P1 telephony update:** the rows for class 0, replace PIDs, multipart retry, MMS-CTR answers, carrier config,
  RESPOND_VIA_MESSAGE and role loss were re-checked after those fixes; their references point at that later commit.
- **Status values:** **Compliant**; **Partial** (implemented but with a gap that matters); **Missing**; **N/A**
  (the Android framework, modem or another system component handles it, and the API is named).
- **Citations:** section numbers are given only where we are confident of them. Where a regulatory detail is
  uncertain (for example, TRAI amendment timelines) the row says "verify".
- **Priorities** in the backlog: **P0** means security, crash or data loss. **P1** means interoperability,
  regulatory or store-policy blockers. **P2** means nice-to-have.

## Summary

| # | Standard / rule set | Compliant | Partial | Missing | N/A | Rows |
|---|---|---|---|---|---|---|
| 1 | 3GPP TS 23.040 (SMS transfer layer) | 9 | 2 | 0 | 6 | 17 |
| 2 | 3GPP TS 23.038 (alphabets, DCS) + `scripts/sms-pdu.py` | 4 | 3 | 0 | 5 | 12 |
| 3 | OMA MMS-ENC 1.3 + WAP-230 WSP encoding | 14 | 0 | 0 | 0 | 14 |
| 4 | OMA MMS-CTR (client transactions) | 14 | 1 | 1 | 0 | 16 |
| 5 | OMA MMS-CONF, 3GPP TS 23.140 / 26.140 (media, SMIL) | 6 | 3 | 0 | 0 | 9 |
| 6 | WAP-251 Push / WSP push | 1 | 1 | 0 | 2 | 4 |
| 7 | Carrier config (CarrierConfigManager / SmsManager MMS config) | 7 | 1 | 2 | 2 | 12 |
| 8 | RFC 5724 `sms:`/`smsto:` (+ `mms:`/`mmsto:`), SENDTO/SEND intents | 6 | 1 | 0 | 0 | 7 |
| 9 | vCard 2.1/3.0/4.0 (RFC 6350), vCalendar | 2 | 3 | 0 | 1 | 6 |
| 10 | Android default-SMS-app requirements | 14 | 3 | 0 | 0 | 17 |
| 11 | Google Play SMS/Call Log policy, Data safety | 2 | 3 | 3 | 0 | 8 |
| 12 | India TRAI TCCCPR 2018 and amendments | 4 | 1 | 2 | 1 | 8 |
| 13 | DPDP Act 2023 (India), GDPR (EU) | 0 | 3 | 3 | 2 | 8 |
| 14 | Text safety: UAX #9, UTS #39, UTS #46 | 4 | 4 | 1 | 0 | 9 |
| 15 | OWASP MASVS v2 (high level) | 6 | 2 | 0 | 0 | 8 |
| | **Total** | **92** | **32** | **12** | **19** | **155** |

Findings to act on first (details are in the [backlog](#prioritised-remediation-backlog)):

- **P0: incoming SMS durability.** The inbox write runs inside a cancellable 7 s `goAsync` budget, and no retry
  journal exists. If the write times out or fails, the platform has already removed the raw PDU, so the message is
  lost.
- **P0: emergency texts can be queued.** All sends, including broadcasts, share one process-wide rate limiter. A
  text to an emergency number can wait behind a bulk send.
- **P1 SMS gaps (fixed):** class 0 (flash) SMS are shown at once and stored only on "Save"; "Replace short
  message" (TP-PID 0x41–0x47) replaces in place; a partial multipart failure is no longer re-sent automatically.
- **P1 MMS gaps (fixed):** m-notifyresp-ind (Retrieved / Deferred / Unrecognised) and m-acknowledge-ind are sent by
  default; carrier MMS config (group MMS, SMS→MMS thresholds, size, recipient, subject and text limits) is read per
  SIM through `CarrierConfigManager`.
- **P1 role loss (fixed):** losing the default-SMS role holds queued and scheduled sends (never emergency texts),
  stops MMS downloads and makes the composer read-only with a "Make Dak your default SMS app" action.
- **P1 URI bug (fixed):** `sms:` intents are parsed per RFC 5724 from the encoded URI (`navigation/SmsUriParser.kt`)
  and RESPOND_VIA_MESSAGE likewise (`sms/RespondViaMessage.kt`): decoded once, `&` kept.

---

## 1. 3GPP TS 23.040: SMS transfer layer

Android's telephony stack (`InboundSmsHandler`, `GsmInboundSmsHandler`, `SMSDispatcher`, `SmsMessage`) does most
of the TP-layer work. The default SMS app receives already-assembled messages through
`Telephony.Sms.Intents.getMessagesFromIntent`. The app decides what to store, how to display special classes and
how to report status.

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| SMS-DELIVER fields relevant to the app: originator (TP-OA, including alphanumeric and e-mail-gateway originators), SC timestamp (TP-SCTS), TP-PID, service centre, reply-path flag, user data | `core-telephony/.../sms/IncomingSmsProcessor.kt:48-64` reads `displayOriginatingAddress`, `timestampMillis`, `protocolIdentifier`, `serviceCenterAddress`, `isReplyPathPresent`, `pseudoSubject`. `provider/TelephonyProviderWriter.kt:62-79` writes `address`, `date_sent`, `protocol`, `service_center`, `reply_path_present`, `subject`, `sub_id` | Compliant | None | `sms-pdu.py VD-HDFCBK-T …` and `+9198…` via `adb emu sms pdu`. Assert provider columns match the PDU |
| Concatenated SMS (UDH IEI 0x00 with 8-bit ref and IEI 0x08 with 16-bit ref, §9.2.3.24.1 / §9.2.3.24.8), reordering, duplicate segments | Framework: `InboundSmsHandler` stores segments in the `raw` table and delivers one SMS_DELIVER with every PDU in sequence order. Dak joins bodies at `IncomingSmsProcessor.kt:49` | N/A (framework `InboundSmsHandler`) | None | Send parts in reverse order and with a 16-bit ref from an extended `sms-pdu.py` (see §2). Assert one inbox row with the correct text |
| Missing segments or segment timeout | Framework keeps partial segments in `raw` and expires them. The behaviour differs between Android versions, and Dak never sees partial messages | N/A (framework) | Document it. Optionally show a "message may be incomplete" hint where an OEM delivers partials | Inject 2 of 3 parts. Confirm nothing reaches Dak and nothing crashes |
| Application port addressing (IEI 0x04/0x05, §9.2.3.24.3/4) and data SMS | Framework routes port-addressed SMS to `DATA_SMS_RECEIVED` or the WAP push path. Dak registers no data-SMS receiver (`core-telephony/src/main/AndroidManifest.xml:39-57`) | N/A (framework `InboundSmsHandler` port dispatch) | None. Do not add a `DATA_SMS_RECEIVED` receiver without a use case | Port-addressed PDU: assert no inbox row and no crash |
| 8-bit data DCS **without** a port (binary SMS) | `messageBody` is null for 8-bit data. `IncomingSmsProcessor.kt:49` stores `""`, so the user sees an empty bubble | Partial | Detect `SmsMessage.getUserData()` when the body is null. Store a placeholder such as "[binary message, N bytes]" and keep the hex in a side table | PDU with DCS 0x04 and no UDH: assert a readable placeholder |
| TP-PID type 0 ("silent" SMS, §9.2.3.9): acknowledge, do not display or store | Framework: `GsmInboundSmsHandler` acknowledges and drops type-0 messages before SMS_DELIVER. Dak drops PID 0x40 again as a second line for OEM builds that do not (`sms/IncomingSmsPolicy.kt:48-51`, applied at `IncomingSmsProcessor.kt:85-91` and in the journal replay at `:166-170`) | N/A (framework `GsmInboundSmsHandler`) | None | PDU with PID 0x40 (`sms-pdu.py --pid 40`): assert no row and no notification. Unit: `IncomingSmsPolicyTest` |
| TP-PID "Replace Short Message Type 1–7" (0x41–0x47, §9.2.3.9): replace the earlier message from the same originator with the same PID | `IncomingSmsPolicy` classifies 0x41–0x47 as REPLACE (`sms/IncomingSmsPolicy.kt:48-56`, 3GPP format only). `IncomingSmsProcessor.storeNew` (`:197-200`) calls `TelephonyProviderWriter.replaceIncoming` (`provider/TelephonyProviderWriter.kt:87-107`), which overwrites the newest inbox row with the same `address` and `protocol` (body, dates, service centre, SIM; `read=0`, `seen=0`) and keeps its id and thread. With no earlier row the message is inserted. Journal replays stay idempotent (the replaced row matches by body and date) | Compliant | None | Two PDUs from the same sender with `sms-pdu.py --pid 41`: one row with the second body. Unit: `IncomingSmsPolicyTest.replaceTypesOneToSeven` |
| TP-DCS message class 0 (flash, from the 23.038 DCS): display immediately; the ME need not store it | Decision: follow stock Android (AOSP ClassZeroActivity): show at once, store only when the user taps **Save**. `IncomingSmsProcessor.kt:93-96` hands class 0 to `sms/FlashMessages.kt:52-89`: a high-importance (heads-up) notification on its own channel with the full text and Save / Dismiss actions; tapping it opens `FlashMessageActivity` (`:204`), a framework AlertDialog (plain text, no clickable links). Save inserts the message as read (`:92-96`) and does not re-announce it. One notification per sender (a newer flash replaces the older). Full-screen intents and background activity starts are not used (restricted on Android 14+ / 10+). If notifications are off for Dak or the channel, the message is stored as a normal SMS so it is never lost unseen. Class 0 wins over the replace PIDs; PID 0x40 wins over class 0 | Compliant | None | `sms-pdu.py --flash`: heads-up shown, no inbox row until Save; Save → one read row; Dismiss → nothing stored. Unit: `IncomingSmsPolicyTest.classZeroIsFlashAndWinsOverReplace` |
| Class 2 (SIM-specific) storage, SIM data download (PID 0x7F) | Modem or framework: `UsimDataDownloadHandler` handles SIM data download, and class-2 storage happens in the modem/RIL | N/A (modem, `UsimDataDownloadHandler`) | Optional P2: a "SIM messages" viewer (the platform API for this is restricted) | Not testable on the emulator |
| Message-waiting indication (DCS groups 1100–1110, UDH special-SMS IEI 0x01, CPHS) | The framework updates the voicemail indicator. Dak drops store=false MWI (`IncomingSmsProcessor.kt:45`) and stores store=true ones | Compliant | None | MWI "discard" DCS 0xC8: assert no row |
| TP-SRR (status report request, §9.2.3.5) on submit | The delivery `PendingIntent` is passed only when the user enables reports: `send/TelephonyMessageSender.kt:67,165-173` | Compliant | None | Enable delivery reports and send. The emulator returns a status report. Assert `status` becomes COMPLETE |
| SMS-STATUS-REPORT handling, TP-Status (TP-ST, §9.2.3.15) ranges: completed, temporary (still trying), permanent/temporary-final errors | `sms/SmsStatusProcessor.kt:69-94`. `sms/DeliveryStatus.kt:29-34` maps 0x00–0x1F to delivered, 0x20–0x3F to pending and ≥0x40 to failed, and handles 3GPP2 error classes (lines 19-27) | Compliant | None | Unit: `DeliveryStatus.outcome` for 0x00, 0x20, 0x41, 0x60 and a CDMA class 3 |
| Status reports for multipart messages (one report per segment) | Marked delivered only when every part is delivered (`SmsStatusProcessor.kt:83-86`, `send/SendStores.kt:45-48`) | Compliant | None | 3-part send with the 2nd report failed: assert FAILED |
| Validity period (TP-VP, §9.2.3.12) | Public `SmsManager` has no validity-period parameter, so the SMSC default or carrier config applies | N/A (framework / SMSC) | None. If a hidden-API alternative ever appears, avoid it | – |
| Reply path (TP-RP, Annex D): a reply should go through the originating SC | Flag stored (`TelephonyProviderWriter.kt:73`), but replies always use the default SC (`TelephonyMessageSender.kt:171-173` pass `scAddress = null`) | Partial | When replying to a message with `reply_path_present=1`, pass its `service_center` as `scAddress`. Gate this behind a setting, because some carriers reject foreign SCs | Unit: the reply builder picks the SC when the flag is set |
| SMS-SUBMIT segmentation and concatenated submit | `divideMessage` + `sendMultipartTextMessage` on the per-subscription `SmsManager` (`TelephonyMessageSender.kt:156-174`). TP-MR and UDH are built by the framework | Compliant | None | Send 400 GSM characters. Assert 3 parts and 1 provider row |
| Retry after a failed segment (avoid duplicates at the recipient) | Per-part results are recorded for the attempt (`sms/PartProgress.kt:61-73`, `send/SendStores.kt:32-43`) and nothing is decided until every part (or the last part) has reported (`sms/SmsStatusProcessor.kt:49-58`). No part sent → normal retry policy (a whole resend cannot duplicate anything). Some parts sent → FAILED with "Only k of n parts were sent…" and **no** automatic resend (`SmsStatusProcessor.markPartlySent`, `:69-73`); late delivery reports cannot flip it to delivered. Trade-off: SMS cannot resend only the missing parts (a resend is a new concatenation with a new reference), so the user's retry resends the whole message knowingly. On OEMs that fire only the last part's intent, a failed last part with silently sent earlier parts is indistinguishable from "nothing sent" and is still retried | Compliant | None | Unit: `MultipartOutcomeTest` (part 2 of 3 fails → PARTIAL, not retried; all fail → retry). Instrumented with a fake SmsManager still to do |

## 2. 3GPP TS 23.038: alphabets, data coding scheme, segment counting

Encoding choices are the framework's job (`GsmAlphabet`, `SmsMessage.calculateLength`, `SmsManager.divideMessage`).
Dak must make its counter agree with what will actually be sent. The emulator tool must produce valid PDUs.

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| GSM 7-bit default alphabet (§6.2.1) and extension table (§6.2.1.1) on send, including the escape (0x1B) counting as 2 septets | Framework `GsmAlphabet` via `divideMessage` / `sendMultipartTextMessage` (`TelephonyMessageSender.kt:158,170-174`) | N/A (framework `GsmAlphabet`) | None | – |
| UCS-2 fallback when a character is outside GSM 7-bit (70 / 67 code units per segment) | Framework `SmsMessage.calculateLength` / `fragmentText` | N/A (framework) | None | – |
| National language single-shift / locking-shift tables (Annex A), including the Indian tables: Hindi, Bengali, Tamil, Telugu and others | Framework: tables are enabled per device/carrier through resource overlays (`config_sms_enabled_single_shift_tables` / `..._locking_shift_tables`). Whether the Indian tables are on is up to the OEM build, and carriers rarely support them, so Indic text normally goes as UCS-2 | N/A (framework resource config) | Do not hard-code "Hindi = 70 characters" anywhere. Keep using the platform counter (next row) | Device matrix: compare the counter with the part count actually sent for Hindi text on 2–3 OEMs |
| Composer segment counter matches what the sender will produce | `MessageSendController.segments` uses `SmsMessage.calculateLength(text, false)` (`app/.../conversation/MessageSendController.kt:63-67`). `SmsSegmentCounter` does the same (`core-telephony/.../PlatformHelpers.kt:49-59`). Counter shown above 3 segments or while roaming (`ComposerDelegate.kt:78`) | Compliant | None | Unit/instrumented: `"€"`×80 → 2 segments (escape counted); 71 × `"अ"` → 2 segments |
| Counter per subscription (shift tables and 7-bit handling can differ per carrier on dual-SIM phones) | `calculateLength` is static and uses the default subscription, while sending uses `SmsManagers.forSubscription` (`internal/AndroidSupport.kt:30-37`) | Partial | For the chosen SIM, compute the count with `SmsManager.forSubscription(subId).divideMessage(text).size` (debounced), or document the difference | Dual-SIM device with different carriers: compare the counter and the parts sent |
| Surrogate pairs (emoji) never split across segments | Framework `SmsMessage.fragmentText` | N/A (framework) | None | – |
| Receive decoding of every DCS, including shift tables and UCS-2 | Framework `SmsMessage` | N/A (framework) | None | – |
| `scripts/sms-pdu.py`: default alphabet and extension table are correct | `android/scripts/sms-pdu.py:23-27` (index = septet value; the extension table maps `\f ^ { } \ [ ~ ] \| €`) | Compliant | None | `python3 sms-pdu.py +91… "{[€]}"`, then decode with `SmsMessage.createFromPdu` in a JVM test |
| `sms-pdu.py`: septet packing with fill bits after the UDH, UDL counted in septets including the UDH | `sms-pdu.py:48-60,140-147` | Compliant | None | Multipart GSM text: round-trip through `SmsMessage` |
| `sms-pdu.py`: UCS-2 handling of non-BMP characters | `sms-pdu.py:108-117` emits UTF-16 (surrogate pairs kept together, never split). Strictly, 23.038 UCS-2 has no surrogates. Android and most handsets accept UTF-16, so this matches real traffic | Partial | Document that it matches Android's behaviour. Add a `--strict-ucs2` flag that rejects non-BMP characters so strict-decoder paths can be tested | Emoji text: 70-unit boundary does not split a pair |
| `sms-pdu.py`: fixtures for the rest of 23.040/23.038 (16-bit concat refs, out-of-order or missing parts, shift-table IEIs 0x24/0x25, class 0, PID 0x40/0x41–0x47, MWI, 8-bit ports, status reports) | SMS-DELIVER with 8-bit refs, DCS 0x00/0x08; `--pid HEX` (type 0, replace types) and `--flash` (class 0: DCS 0x10 / 0x18) added (`sms-pdu.py:120-160,170-176`) | Partial | Still to add: `--ref16`, `--order 3,1,2`, `--drop N`, `--dcs HEX`, `--port N`, `--nls hi`, MWI and status reports | Each flag gets a JVM round-trip test through `SmsMessage.createFromPdu` |
| Alphanumeric TP-OA: at most 11 characters, GSM 7-bit packed, TON/NPI 0xD0, length in semi-octets | `sms-pdu.py:69-82` (`(septets*7+3)//4` useful semi-octets) | Compliant | None | `VD-HDFCBK-T` (11 characters) decodes to the same string |

## 3. OMA MMS-ENC 1.3 and WAP-230 WSP header encoding

Dak has its own codec in `android/mms-pdu`. It sends m-send-req, m-notifyresp-ind and m-acknowledge-ind (the last
is encodable but unused, see §4). It receives m-notification-ind, m-retrieve-conf, m-send-conf, m-delivery-ind
and m-read-orig-ind.

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| Model and codec for every PDU type a client sends or receives | `mms-pdu/.../MmsPdu.kt:33-150`. Decoder `MmsPduDecoder.kt:169-259`, encoder `MmsPduEncoder.kt:12-50` | Compliant | Add m-read-rec-ind when §4's read-report row is done | Existing golden round-trip tests. Add AOSP-generated fixtures |
| Header order: X-Mms-Message-Type, X-Mms-Transaction-ID, X-Mms-MMS-Version first; Content-Type last, followed by the body | `MmsPduEncoder.kt:100-104` (preamble), `:165-173` (Content-Type then body). The decoder stops at Content-Type (`MmsPduDecoder.kt:91-92`) | Compliant | None | Byte-level assertion on the first 3 headers |
| m-send-req mandatory headers: type, TID, version, From, To/Cc/Bcc (at least one), Content-Type; From as Insert-address-token | `MmsPduEncoder.kt:52-66`, `fromField` `:131-141` (0x81 insert token when `from == null`) | Compliant | None | Decode an encoded send-req with AOSP `PduParser` in an instrumented test |
| Address encoding: `/TYPE=PLMN` for numbers, e-mail addresses unchanged | `MmsAddress.kt:21-26` (separators stripped), `fromWire` `:11-15` | Compliant | None | Unit: `+91 98-765` → `+9198765/TYPE=PLMN`; `a@b.c` unchanged |
| Encoded-String-Value: charset-tagged when not ASCII (Subject, To, From) | `WspWriter.kt:89-99` writes UTF-8 (MIBenum 106) with Value-length when any character is outside 0x20–0x7E. `WspReader.kt:166-170` decodes the charset | Compliant | None | Hindi subject round-trip; decode a UTF-16 subject fixture |
| Content-Type `application/vnd.wap.multipart.related` with `start` and `type` parameters, SMIL root | `MmsMessageBuilder.kt:62` (`start=<smil>`, `type=application/smil`). `ContentTypeCodec.kt:157-199` writes `Start` (0x0A) and `Type` (0x09). These are the WSP 1.2 tokens AOSP also emits. The decoder accepts both the 1.2 and 1.4 tokens (`ContentTypeCodec.kt:14-20,115-116`) | Compliant | None | Hex fixture compared against an AOSP-encoded send-req |
| Part headers: Content-ID as quoted `<id>`, Content-Location, optional Content-Disposition | `MultipartCodec.kt:88-122`. The builder sets no disposition (`MmsMessageBuilder.kt:31-37`), which suits carriers without disposition support | Compliant | If needed, honour `MMS_CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION` | Round-trip test with disposition on and off |
| Text part charset parameter; decode default when no charset is given | Builder sets UTF-8 (`MmsMessageBuilder.kt:41-48`). The decoder falls back to UTF-8, a tolerant superset of the us-ascii default (`PduPart.kt:34-37`) | Compliant | None | Part without charset and Latin-1 bytes: no crash |
| X-Mms-MMS-Version: send the right version and handle a major version we do not support | Always sends 1.2 (`PduConstants.kt:30`). A received notification of another major version (e.g. 2.0) is answered m-notifyresp-ind Unrecognised and not stored or fetched (`mms-pdu/.../MmsClientTransactions.kt` `forUnsupportedVersion`; `core-telephony/.../mms/MmsDownloadManager.kt:107-112`) | Compliant | None | `ClientTransactionsTest.unsupportedMajorVersionIsUnrecognised` (version 0x20 fixture) |
| Unknown well-known and application headers are skipped without failing | `MmsPduDecoder.kt:94-98,114-117` | Compliant | None | Fuzz corpus (already partly in tests) |
| Robust decoding of hostile input (size, part count, nesting, addresses; never throws) | `MmsSafety.kt:10-22` (16 MiB, 256 parts, 100 addresses, 1024-character texts). `MmsPduDecoder.kt:12-36` turns every failure into `PduDecodeResult.Failure` | Compliant | Owned by the security work stream | Fuzz with Jazzer or a random-mutation JVM test |
| Transaction-ID unique and printable | `MmsMessageBuilder.kt:72` (`T` + hex time + random 64-bit) | Compliant | None | Unit: 10k IDs are unique and ASCII |
| Date / Expiry / Delivery-Time encoding (absolute and relative tokens) | `MmsPduEncoder.kt:149-163`, `MmsPduDecoder.kt:138-145`, `MmsPdu.kt:7-18` | Compliant | None | Relative-expiry fixture resolves against the receive time |
| Nested multipart (for example multipart/alternative inside related) | Flattened with a depth and part limit (`MultipartCodec.kt:14-40`) | Compliant | None | Nested fixture: every leaf part appears |

## 4. OMA MMS-CTR: client transactions

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| Receive m-notification-ind (WAP push) and hand it to the download pipeline on the right SIM | `mms/WapPushProcessor.kt:28-38`, `MmsDownloadManager.kt:79-107`, subscription from `internal/SubscriptionExtras.kt:27-41` | Compliant | None | Emulator: `adb emu` cannot inject WAP push, so use an instrumented test that broadcasts to `WapPushDeliverReceiver` with a fixture |
| Content-Location sanity (absolute http(s), no loopback) and de-duplication of repeated notifications | `MmsSafety.kt:41-62`; `MmsDownloadManager.kt:82-86,159-163,256`; de-dup by location or TID at `:86`; unique work per location at `:141` | Compliant | None | Same notification twice: one row and one download |
| Immediate retrieval (automatic download) through the platform MMS service | `MmsDownloadManager.kt:93-106,254-272` (`downloadMultimediaMessage` per subscription) | Compliant | None | Instrumented test with a fake MMSC (MockWebServer over loopback is refused by design, so use a test-only override) |
| After immediate retrieval, send **m-notifyresp-ind** with X-Mms-Status = Retrieved (the platform download API does not send it) | On by default (`TelephonySettings.kt:41-50`, kept as a switch). After the retrieved message is stored, `MmsClientTransactions.afterRetrieval` picks the answer (`MmsDownloadManager.kt:288-292`), sent by `MmsSendManager.sendClientPdu` (`:126-150`) to the MMSC, or to the notification's Content-Location when the carrier sets `enabledNotifyWapMMSC` (the key's AOSP meaning: where to send, not whether) | Compliant | None | `ClientTransactionsTest.immediateRetrievalIsAnsweredRetrieved` (exact bytes) |
| Deferred retrieval (auto-download off, roaming, too large, notification flood): send m-notifyresp-ind with X-Mms-Status = **Deferred** | When a new notification is left for a tap, `MmsClientTransactions.deferred` is sent and the row is marked deferred (`MmsDownloadManager.kt:130-133`, `MmsDownloadStateStore.markDeferred`). Flood-throttled notifications are answered too; dropped ones (past the hourly hard cap) are not | Compliant | None | `ClientTransactionsTest.deferredThenRetrievedIsAcknowledged` |
| After a later user-initiated retrieval of a deferred message: send **m-acknowledge-ind** (not notifyresp) | `store()` sends `AcknowledgeInd` when the row was answered Deferred, NotifyRespInd(Retrieved) otherwise, then clears the flag (`MmsDownloadManager.kt:288-292`) | Compliant | None | `ClientTransactionsTest.deferredThenRetrievedIsAcknowledged` (exact bytes) |
| m-retrieve-conf X-Mms-Retrieve-Status handling (transient vs permanent, error text shown) | `MmsDownloadManager.kt:234-240` | Compliant | None | Fixture with status 0xC1 (transient) → retry; 0xE0 (permanent) → no retry |
| Expiry: do not fetch after X-Mms-Expiry | `MmsDownloadManager.kt:154-158`; expiry stored at `MmsProviderMapping.kt:54` | Compliant | None | Notification with past absolute expiry → "Expired" state and no fetch |
| Retry policy for download and send (bounded, backoff) | Download: WorkManager exponential backoff from 30 s, 5 attempts (`MmsDownloadManager.kt:137,280-281`). Send: `RetryPolicy` 4 MMS attempts (`send/RetryPolicy.kt:6`, `MmsSendManager.kt:165-174`) | Compliant | None | Fake failures: attempt counts and delays |
| m-send-conf X-Mms-Response-Status (OK, transient, permanent) and Message-ID kept for reports | `MmsSendManager.kt:100-114`; Message-ID persisted through `persister.markSent` | Compliant | None | Fixtures 0x80, 0xC0, 0xE1 |
| m-delivery-ind matched to the sent message by Message-ID, status per recipient | `WapPushProcessor.kt:35` → `MmsPersister.applyDeliveryReport` | Compliant | None | Fixture delivery-ind: sent row shows delivered |
| m-read-orig-ind (read report received for our message) | `WapPushProcessor.kt:36` | Compliant | None | Fixture read-orig-ind |
| Send **m-read-rec-ind** when a received message has X-Mms-Read-Report = Yes and the user agrees | Not implemented. `read_report` is stored (`MmsProviderMapping.kt:83`) but never acted on | **Missing** | Add a user setting "Send read receipts for MMS" (default off, for privacy). When on, send m-read-rec-ind on first open. Needs the PDU type in `mms-pdu`. Also honour `MMS_CONFIG_MMS_READ_REPORT_ENABLED` | Unit + fixture |
| X-Mms-Report-Allowed in notifyresp and acknowledge (whether the user allows delivery reports to the sender) | Field is supported (`MmsPduEncoder.kt:19,23`) but always null (`MmsSendManager.kt:122`) | Partial | Fill it from a privacy setting (default Yes, which matches AOSP) | Encoder test with the field present |
| Unsupported or unrecognised notification: respond with X-Mms-Status = Unrecognised or Rejected | A WAP push the decoder rejects is passed to `MmsDownloadManager.onUndecodable` (`WapPushProcessor.kt:31-36`, `MmsDownloadManager.kt:302-306`): `MmsPduDecoder.peekPreamble` salvages type and TID, and a notification (or unknown type) with a TID gets NotifyRespInd(Unrecognised). Damaged delivery / read reports get no answer. Answers share the notification flood budget | Compliant | None | `ClientTransactionsTest.undecodableNotificationIsAnsweredUnrecognised`, `…unknownMessageType…`, `…damagedReports…` |
| Download policy for roaming and size (user control, no silent roaming data) | `TelephonySettings.kt:21-29`; `MmsDownloadManager.kt:91-104` | Compliant | None | Roaming flag on: no auto-download |

## 5. OMA MMS-CONF, 3GPP TS 23.140 / TS 26.140: content, media and SMIL

MMS-CONF defines content classes (Text, Image Basic/Rich, Video Basic/Rich, Megapixel, Content Basic/Rich) with
size caps in the 30 KB–600 KB range, depending on class and version. It also defines creation modes (restricted,
warning, free). Carriers publish the effective limit through carrier config. Exact per-class numbers should be
checked against the MMS-CONF v1.3 tables before relying on them.

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| Stay within the carrier's message size limit (300 KB default and floor) | `MmsSendManager.kt:71-74,184` reads `maxMessageSize` through `carrier/CarrierConfigRepository.kt` (CarrierConfigManager first); the composer budgets parts against `min(compressor limit, carrier limit)` (`app/.../conversation/MessageSendController.kt:199-203`) | Compliant | None | Carrier config 100 KB: 2 MB photo fits and send succeeds |
| Image adaptation to a widely supported format and resolution (JPEG, EXIF orientation applied, metadata dropped) | `MmsMediaCompressor.kt:43-50` and the compress loop: JPEG, longest edge ≤1600 px (`:127`), quality ladder, 200 MP decode guard. Re-encoding drops EXIF, so GPS is not leaked | Compliant | Take the max width and height from carrier config (§7) | HEIC/WebP/PNG input → JPEG ≤ limit, correct orientation |
| Video and audio adaptation (3GPP/MP4 H.263/H.264, AMR-NB/AAC per TS 26.140 / 26.234 codecs) | Video and audio that fit are sent unchanged. Over the budget they are transcoded with platform codecs only, because Media3 is not a dependency: `app/.../conversation/MmsMediaCompressor.kt:53-67` calls `MmsMediaTranscoder.kt:49`. Video goes MediaExtractor → MediaCodec decoder → SurfaceTexture/GLES scale → H.264 encoder (Baseline requested) → MediaMuxer MP4, with AAC-LC audio. Audio-only input becomes AAC-LC in MP4. `MmsTranscodePlan.kt` derives resolution (176–640 px), frame rate (15/24), bitrate and container overhead from the budget, retries at 0.7× when the encoder overshoots, and refuses clips that cannot fit at ≥32 kbit/s video (about 15–25 s at 300 KB). Any codec, GL or timeout failure (120 s) falls back to the old refusal, and the snackbar suggests trimming or sharing from the source app. The limit still comes from `MmsMediaCompressor.messageLimitBytes` (TODO hook for carrier config, §7) | Compliant | Verify on devices (not yet run on hardware). Later: AMR-NB for voice notes; audio part file name uses the source extension (`MessageSendController.fileNameFor`, telephony stream) | 20 MB, 10 s phone video → MP4 under the limit, plays on a stock Messages client; 2-minute video → "too large" snackbar, no crash |
| SMIL root part referenced by `start`, one presentation per message | `mms-pdu/.../Smil.kt:33-52`, `MmsMessageBuilder.kt:50-62` | Compliant | None | Parse the generated SMIL with an XML parser; every `src` resolves to a part Content-Location |
| SMIL layout: image and text on the **same** slide (the usual MMS-CONF image+text content), root-layout size | Each item gets its own `<par>`, with text last (`Smil.kt:39-49`). `<root-layout/>` has no width or height (`:35`) | Partial | Pair the first image or video with the text in one `<par>`. Emit `root-layout width/height` (for example 320×480). Keep one `<par>` per extra media item | Golden SMIL string; render on AOSP Messaging and iOS: caption shows under the photo |
| Receive-side presentation: follow the SMIL order and timing where present | SMIL is ignored. Parts render in PDU order and text parts are concatenated (`core-telephony/.../mms/MmsProviderMapping.kt:170-191`) | Partial | Parse the SMIL `<par>` order to sort attachments and pair captions. Skip timing | Fixture where the SMIL order differs from the PDU order |
| Receive and render the core media types (JPEG/GIF/PNG/WBMP images, 3GP/MP4 video, AMR/AAC audio, text) and open unknown types safely | Images inline (`MessageBubble.kt:310-319`). Others go to system viewers through `AttachmentOpener.kt:20-29`, which sends dangerous types to a "Save or share" chooser as octet-stream | Compliant | None | Fixture with an APK and an SVG part: opens the chooser, not the installer or browser |
| Text encoding of text parts (UTF-8 / us-ascii) | `MmsMessageBuilder.kt:41-48` (UTF-8 charset parameter) | Compliant | None | – |
| Creation mode (restricted, warning, free): warn when content falls outside the content classes | Only the size limit is enforced. Arbitrary MIME types (PDF, APK, …) can be attached | Partial | Implement the "warning" creation mode: warn when a part is not in the core types or would need a higher content class | Attach a PDF: warning shown, send allowed |

## 6. WAP-251 Push message format / WSP push

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| Parse the WSP push PDU (TID, PDU type, push headers, content type) and pass the MMS body on | Framework `WapPushOverSms` parses the push and delivers `WAP_PUSH_DELIVER` with the stripped PDU in the `data` extra. Dak reads only `data` (`WapPushProcessor.kt:29,42-43`) | N/A (framework `WapPushOverSms`) | None | – |
| Accept only the MMS content type, only from the system | `core-telephony/src/main/AndroidManifest.xml:49-57` (`BROADCAST_WAP_PUSH`, mime `application/vnd.wap.mms-message`) | Compliant | None | `adb shell am broadcast` without the permission is refused |
| Other push content types (SI/SL service indication and loading, provisioning) | Not registered, which is safer: SI/SL are phishing vectors | N/A (not a default-SMS-app duty) | Keep it that way | – |
| MMS notifications from blocked senders | Relies on the framework (AOSP `WapPushOverSms` checks the block list for MMS notifications; OEM builds vary). No defensive check in `WapPushProcessor.kt:34` | Partial | Before inserting, call `BlockedNumbers.isBlocked(n.from)` and drop blocked notifications (no download). Skip the check while the platform suppresses blocking after an emergency call | Instrumented: blocked `from` fixture → no row |

## 7. Carrier config (CarrierConfigManager / SmsManager MMS config), UA/UAProf

`SmsManager.getCarrierConfigValues()` is used and suppressed as deprecated (`MmsSendManager.kt:177`). The
supported source is `CarrierConfigManager.getConfigForSubId(subId)` with the `KEY_MMS_*` keys.

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| Max message size per SIM | `carrier/CarrierConfigRepository.kt` (per subscription: `CarrierConfigManager.getConfigForSubId`, then the deprecated `SmsManager.getCarrierConfigValues`, then defaults; cached 5 min) → `CarrierMessagingConfig.maxMessageSizeBytes`, used by `MmsSendManager.kt:184` and `MessageSendController.kt:199-203` | Compliant | None | `CarrierMessagingConfigTest` |
| User-Agent / UAProf (`x-wap-profile`) headers on MMSC HTTP | Platform `MmsService` adds them from carrier config, because Dak passes `configOverrides = null` (`MmsSendManager.kt:155`, `MmsDownloadManager.kt:267`) | N/A (framework `MmsService`) | None | – |
| MMS APN, MMSC URL, proxy, HTTP params | Platform `MmsService` | N/A (framework) | None | – |
| SMS→MMS conversion threshold (`MMS_CONFIG_SMS_TO_MMS_TEXT_THRESHOLD` / `..._LENGTH_THRESHOLD`) | `carrier/SendModePolicy.kt` uses the carrier's segment threshold (`smsToMmsTextThreshold`) and length threshold (`smsToMmsTextLengthThreshold`) when > 0, else 10 segments; `MessageSendController.plan` (`:101-109`) feeds the composer's MMS chip and the send path | Compliant | None | `SendModePolicyTest.carrierThresholdsReplaceTheDefault` |
| MMS enabled and group MMS enabled (`MMS_CONFIG_MMS_ENABLED`, `MMS_CONFIG_GROUP_MMS_ENABLED`) | `SendModePolicy.plan`: group MMS only when `enableGroupMms`; otherwise text goes as individual SMS (one provider row per recipient) and media as one MMS per recipient (`MessageSendController.kt:176-183`), and the composer says so (`CarrierNotice.GROUP_AS_INDIVIDUAL`). With `enabledMMS` false, text stays SMS and media is refused with a reason | Compliant | None | `SendModePolicyTest.groupGoesAsGroupMmsOnlyWhenTheCarrierAllowsIt`, `…mmsDisabled…` |
| Recipient limit (`MMS_CONFIG_RECIPIENT_LIMIT`) | `SendModePolicy.plan` blocks a group MMS above `recipientLimit` (`SendBlock.TOO_MANY_RECIPIENTS`); the composer shows the limit and send fails with the reason | Compliant | Optionally offer to split into several groups | `SendModePolicyTest.recipientAndTextLimits` |
| Subject and text limits (`MMS_CONFIG_SUBJECT_MAX_LENGTH`, `MMS_CONFIG_TEXT_MAX_SIZE`) | Subject cut to `maxSubjectLength` code points before encoding (`MmsSendManager.kt:69`, `SendModePolicy.subject`); MMS text over `maxMessageTextSize` is refused with a reason (`SendBlock.TEXT_TOO_LONG`) | Compliant | Warn in the UI when a subject is cut (Dak has no subject field today) | `SendModePolicyTest.recipientAndTextLimits`, `…subjectIsCut…` |
| Max image dimensions (`MMS_CONFIG_MAX_IMAGE_WIDTH/HEIGHT`) | Read into `CarrierMessagingConfig.maxImageWidth/Height` (AOSP defaults 640×480), but the compressor still uses a fixed 1600 px (`MmsMediaCompressor.kt:127`) | Partial | In `MmsMediaCompressor`, use `min(1600, carrier)` from `CarrierConfigRepository` | Unit |
| Report enablement (`MMS_CONFIG_SMS_DELIVERY_REPORT_ENABLED`, `..._MMS_DELIVERY_REPORT_ENABLED`, `..._MMS_READ_REPORT_ENABLED`) | Only user settings (`TelephonySettings.kt:31-39`) | **Missing** | AND the user setting with the carrier flag, and hide toggles the carrier disables | Unit |
| m-notifyresp-ind policy (`MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED`) | In AOSP this key decides **where** notifyresp / acknowledge go (the notification's Content-Location instead of the MMSC), not whether they are sent. `MmsSendManager.sendClientPdu` passes the (validated) Content-Location as `locationUrl` when it is set (`:126-133`); the answers themselves are on by default (§4) | Compliant | None | Unit on `CarrierMessagingConfig.notifyWapMmsc` |
| Multipart SMS as separate messages (`MMS_CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES`) | When set, each `divideMessage` part goes with `sendTextMessage`, still tracked per part (`TelephonyMessageSender.kt:269-273`) | Compliant | None | Unit with a fake SmsManager (to do) |
| E-mail recipients over SMS (`MMS_CONFIG_EMAIL_GATEWAY_NUMBER`, `MMS_CONFIG_ALIAS_ENABLED`) | Not read. E-mail recipients can only go by MMS | **Missing** | If a gateway number exists, send `"<email> <text>"` to it by SMS | Unit |

## 8. RFC 5724 `sms:`/`smsto:` (and `mms:`/`mmsto:`), SENDTO/SEND intents

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| Handle `SENDTO` and `VIEW` for `sms`, `smsto`, `mms`, `mmsto` (also required for the SMS role) | `app/src/main/AndroidManifest.xml:74-91` | Compliant | None | `adb shell am start -a android.intent.action.SENDTO -d "smsto:+911234"` |
| Recipient list: comma-separated (RFC 5724); `;` accepted for compatibility | `navigation/SmsUriParser.kt:39-60`: the encoded list is split on `,`/`;`, then each recipient is percent-decoded once. `+` stays literal. Duplicates are dropped, with a cap of 50 | Compliant | None | Unit `SmsUriParserTest`; `sms:+1,+2` → 2 recipients |
| `body` hfield: percent-decoded exactly once; `&` and `%` inside the body kept | `IntentRoutes.kt:120-137` passes `uri.encodedSchemeSpecificPart` (plus any unencoded `#…`) to the pure `SmsUriParser.parse` (`SmsUriParser.kt:39`). It splits on `?` and `&` first, then decodes each value once as UTF-8. `body` is matched case-insensitively and the first one wins. Invalid `%` escapes are kept literally. Bodies are capped at 10,000 chars without splitting a surrogate pair. The second decode in the composer was also removed (`NewConversationViewModel.kt:106`: Navigation already decodes query arguments) | Compliant | None | Unit `SmsUriParserTest`: `sms:+911234567890?body=a%26b` → `a&b`; `?body=50%25%20off` → `50% off` |
| `sms_body` / `EXTRA_TEXT` extras (de facto Android contract) take precedence over the URI body | `SmsUriParser.resolve` (`SmsUriParser.kt:74-90`): `sms_body`, then `EXTRA_TEXT`, then the URI `body`. Recipients come from the URI, else the `address` extra | Compliant | None | Unit `SmsUriParserTest`; intent with both an extra and a URI body |
| Unknown hfields ignored | `SmsUriParser.kt:50-57` | Compliant | None | `sms:+1?foo=1&body=x` → body `x` |
| `SEND` / `SEND_MULTIPLE` share of text, media and vCard; confused-deputy protection on shared URIs | `AndroidManifest.xml:94-108`; `IntentRoutes.kt:73-75,84-114` (content:// from other authorities only, at most 10). A `SEND` carrying an `sms:`/`smsto:` data URI takes its recipients from it. `EXTRA_STREAM` on `SENDTO` (sent by some gallery and camera apps) is accepted under the same guard | Compliant | None | Share `content://mms/part/1` → rejected; `am start -a android.intent.action.SENDTO -d smsto:123 --eu android.intent.extra.STREAM content://…` attaches |
| `RESPOND_VIA_MESSAGE` URI and body parsing | `sms/HeadlessSmsSendService.kt:30-35`; `sms/RespondViaMessage.kt:9-22` splits the **decoded** SSP on `&`, so a body containing `&` is cut. Telecom normally puts the text in `EXTRA_TEXT`, which is used first | Partial | Share the fixed parser from the row above | Unit on `RespondViaMessage.body` |

## 9. vCard (2.1 / 3.0 / 4.0, RFC 6350) and vCalendar

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| Send a contact as a vCard media object (`text/x-vcard`, `.vcf`, `<ref>` in SMIL) | `app/.../conversation/Composer.kt:326-328,415-425` (`ContactsContract.Contacts.CONTENT_VCARD_URI`); `Smil.kt` maps it to `ref` | Compliant | None | Pick a contact → the MMS part is `text/x-vcard` and parses with the Contacts importer |
| vCard version produced (2.1 vs 3.0) and RFC 6350 4.0 recipients | Decided by the device Contacts provider (usually 2.1 or 3.0) | N/A (Contacts provider) | None. Receiving apps must handle 2.1 and 3.0 | – |
| Received vCards: show the name and offer "Add contact" (parsing 2.1/3.0/4.0) | Shows a chip labelled with the file name (`MessageBubble.kt:321-333`) and opens `ACTION_VIEW` so the contacts app parses it (`AttachmentOpener.kt:20`) | Partial | Parse `FN`/`N`/`TEL` (2.1 quoted-printable, 3.0/4.0 escaping, `CHARSET=`) to show the name and number inline, with "Add contact" going to `ACTION_INSERT` | Fixtures for vCard 2.1 QP-encoded Hindi, 3.0 and 4.0 |
| Share-in accepts `text/vcard` (RFC 6350 type), `text/x-vcalendar`, `text/calendar` | Manifest lists only `text/x-vcard` (`AndroidManifest.xml:98`) | Partial | Add `text/vcard`, `text/x-vcalendar` and `text/calendar` to the SEND filter and send them unchanged | `am start -a SEND -t text/vcard` resolves to Dak |
| Received vCalendar opens in a calendar app | `AttachmentOpener.kt:20` (`text/x-vcalendar`, `text/calendar` are viewable) | Compliant | None | Fixture `.vcs` opens the calendar |
| Bound vCard size (embedded photos can be several MB) | `Composer.kt:422` reads the whole stream. It is caught later by the MMS size check with a generic "too large" | Partial | Read with a cap. If too large, retry with `CONTENT_VCARD_URI` plus `Contacts.QUERY_PARAMETER_VCARD_NO_PHOTO=true` | Contact with a 5 MB photo → card sent without the photo |

## 10. Android default-SMS-app requirements

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| Request the role: `RoleManager.ROLE_SMS` (Android 10+), `ACTION_CHANGE_DEFAULT` below | `core-telephony/.../PlatformHelpers.kt:18-37`; onboarding `ui/onboarding/OnboardingScreen.kt:96`, `OnboardingViewModel.kt:83-85` | Compliant | None | Fresh install: role dialog, then permissions |
| `SMS_DELIVER` receiver guarded by `BROADCAST_SMS` | `core-telephony/src/main/AndroidManifest.xml:39-46`; `sms/SmsDeliverReceiver.kt:13-17` | Compliant | None | Receiving works; a broadcast without the permission is refused |
| `WAP_PUSH_DELIVER` receiver guarded by `BROADCAST_WAP_PUSH`, mime `application/vnd.wap.mms-message` | `core-telephony/src/main/AndroidManifest.xml:49-57` | Compliant | None | – |
| `RESPOND_VIA_MESSAGE` service guarded by `SEND_RESPOND_VIA_MESSAGE`, all four schemes | `core-telephony/src/main/AndroidManifest.xml:60-72`; `HeadlessSmsSendService.kt:25-48` (sends on the call's subscription) | Compliant | See the §8 parsing row | Incoming call → quick reply is sent from the right SIM |
| `SENDTO` activity for `sms`/`smsto`/`mms`/`mmsto` | `app/src/main/AndroidManifest.xml:74-82` | Compliant | None | – |
| Request SMS runtime permissions only after holding the role | `core-telephony/src/main/AndroidManifest.xml:11-15` comment; `app/src/main/AndroidManifest.xml:5-6`; onboarding order `OnboardingViewModel.kt:112-130` | Compliant | None | Decline the role: no SMS permission prompt |
| Provider writes (incoming): insert immediately with `read=0`, `seen=0`, `date`, `date_sent`, `sub_id` | `TelephonyProviderWriter.kt:62-79` | Compliant | None | – |
| Provider writes (outgoing): outbox → sent / failed (+`error_code`), queued, delivery `status`, thread ids through `Telephony.Threads.getOrCreateThreadId` | `TelephonyProviderWriter.kt:85-161,203-212`; MMS boxes at `:127-139` | Compliant | Note that `date_sent` is overwritten with the delivery time on DELIVERED (`:116-122`), which departs from the column's usual meaning. Keep it only if interop with the other app that will read these rows has been checked | Switch the default app to Google Messages: sent, failed and delivered states display correctly |
| `seen` vs `read` semantics (seen when the user has been notified or viewed the list; read when opened) | `markThreadRead` / `markRead` set both (`TelephonyProviderWriter.kt:163-187`) | Compliant | Optionally set `seen=1` when the inbox list is shown, so other apps' badges clear | – |
| Incoming SMS must survive slow starts and provider errors (the framework deletes the raw PDU once SMS_DELIVER completes) | `IncomingSmsProcessor.kt:36-78` runs inside `runAsync` with a **cancellable** 7 s timeout (`internal/ReceiverSupport.kt:17,33`). If the insert fails (`key == null`, line 66), the message is only logged and notified, never stored | Partial | Write the PDUs and extras to an app-private journal (no-backup dir) as the first step. Run the insert in `NonCancellable`. If the insert fails, schedule WorkManager replay from the journal and delete the journal entry only after the insert succeeds | Instrumented: fake writer that returns null once, then succeeds → message present after replay; kill the process mid-receive |
| Losing the role: listen for `ACTION_DEFAULT_SMS_PACKAGE_CHANGED` (`EXTRA_IS_DEFAULT_SMS_APP`), switch to read-only, disable compose, offer to restore | `role/SmsRoleMonitor.kt` + `DefaultSmsChangedReceiver` (manifest, exported for the protected broadcast; the role is re-read, the extra is not trusted). Also re-checked on app resume (`ui/common/ReliabilityBanner.kt:57`, composer `ON_RESUME`), before each send attempt and at boot (`OutboxRecovery`). While not default nothing is dropped: due QUEUED / outbox rows and new sends (scheduled sends, broadcasts) are **held** (`send/HeldSendStore.kt`; `TelephonyMessageSender.kt:84,120-135,180-205`) and MMS downloads stop with rows left Pending (`MmsDownloadManager.kt:179-185`); on regain held sends go out through the rate limiter and downloads resume (`TelephonyMessageSender.resumeHeld`, `:214`). Texts to emergency numbers are never held: sent at once, with or without a provider row (`dispatchUnpersisted`, `:288`), and the composer stays usable for emergency-only recipients. The composer is read-only with a "Make Dak your default SMS app" banner (`ui/conversation/Composer.kt:183,237-262`, `ComposerDelegate.kt:79-91`); the inbox banner requests the role directly | Compliant | If SEND_SMS is revoked together with the role on a given Android version, an emergency text fails with a visible reason: consider handing it to the new default app via `ACTION_SENDTO` | Switch the default to another app: composer read-only with "Make Dak default"; a scheduled send comes due and waits; switch back: it goes out. Unit: `HeldSendTest` |
| Android 8–15 background limits: short receivers, WorkManager for long work, FGS type on 14+ | `ReceiverSupport.kt:17-42` (`goAsync`); expedited MMS work `MmsDownloadManager.kt:138`; `SystemForegroundService` `dataSync` type (`core-telephony/src/main/AndroidManifest.xml:107-110`) | Compliant | Watch the Android 15 `dataSync` 6 h cap for bulk broadcasts | – |
| Multi-SIM: subscription id from delivery intents, per-SIM SmsManager, per-SIM MMS config, reply on the receiving SIM | `SubscriptionExtras.kt:19-41` (AOSP and OEM keys, slot fallback); `AndroidSupport.kt:30-37`; `MmsSendManager.kt:178-181` | Compliant | When no extra is present the SIM falls back to the default SMS SIM (`SubscriptionExtras.kt:40`). Label such rows "SIM unknown" rather than guessing | Dual-SIM emulator (`-prop` two SIMs) |
| Emergency: never delay or block texts to emergency numbers | Emergency numbers are classified (`cost/DestinationCostClassifier.kt:55`), but every send goes through the **process-wide** `SendRateLimiter` (`TelephonyMessageSender.kt:74`, singleton at `di/TelephonyModule.kt:95`). Broadcasts share it (`BroadcastService.kt:79`), so a text to 112/911 can be queued behind a bulk send | Partial | Skip the limiter, queue and cost dialog for `CostKind.EMERGENCY` and send at once. Never auto-block an emergency short code | Unit: fill the limiter, send to 112 → dispatched with delay 0 |
| Cell Broadcast / Wireless Emergency Alerts (ETWS/CMAS): do not interfere | No `SMS_CB_RECEIVED` / `SMS_EMERGENCY_CB_RECEIVED` receivers. The CellBroadcastReceiver module owns alerts. Dak's quiet-hours and automation rules act only on SMS and MMS | Compliant | Keep it that way. Do not add "silence all" features that touch alert channels | – |
| Blocked numbers: use the shared `BlockedNumberContract` and rely on platform filtering before SMS_DELIVER | `blocked/TelephonyBlockedNumbers.kt:26-58` (`canCurrentUserBlockNumbers` check, graceful fallback); `IncomingSmsProcessor.kt:26-28` comment | Compliant | See the §6 row on MMS notifications | Block in the Phone app → the SMS never arrives in Dak |
| Direct boot (FBE): behaviour for SMS arriving before first unlock | No component is `directBootAware`. The framework holds SMS for non-aware default apps and delivers after unlock (AOSP `InboundSmsHandler` may post a generic "new message" notification). No loss, but no Dak notification until unlock | Partial | Optional: make `SmsDeliverReceiver` direct-boot aware and journal to device-protected storage with a content-free notification. Insert into the provider after unlock | Reboot and send SMS before unlock: delivered after unlock with the correct date |

## 11. Google Play: SMS and Call Log permissions policy, Data safety

The policy text changes often. Check it against the current Play Console Help Center before submission (the repo
already notes this at `docs/build-plan.md:299`).

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| SMS permissions only for the default SMS handler as core functionality, requested only after the role | See §10 row "Request SMS runtime permissions only after holding the role" | Compliant | None | Pre-launch report: no SMS permission prompt without the role |
| Permissions Declaration Form (Play Console) | Process item, unchecked (`docs/build-plan.md:302`) | **Missing** | File it with "Default SMS handler" as the core use, with a video of the role flow | – |
| Prominent in-app disclosure and affirmative consent before any message content leaves the device (Jev cloud classification, webhooks/relay, forwarding, hosted backup) | Jev is opt-in (`settings-registry/.../DakSettings.kt:238-243`). High-risk OTP forwarding needs a biometric confirmation (`app/.../automation/OtpForwardConfirmations.kt:16-30`). No single disclosure screen covering each data flow was found. `docs/build-plan.md:303` is unchecked | Partial | Before each flow is first enabled, show a full-screen disclosure (what data, to whom, why) with Accept/Decline, separate from the privacy policy. Log consent with a timestamp | UI test: enabling Jev without Accept keeps it off |
| Data safety form | Process item (`docs/build-plan.md:304`). Free tier: nothing collected. Premium and webhooks: user-directed transfer, which still needs a declaration | **Missing** | Prepare per-flavour answers. Webhook and relay destinations are user-chosen, but premium relay traffic crosses Dak's servers (ciphertext), so declare it as encrypted in transit | – |
| Privacy policy linked in-app and on the listing | No privacy-policy string or link in `app/src/main/res/values/` | **Missing** | Add a Settings → About link to a hosted policy | – |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (a Play-restricted permission) | Declared at `app/src/main/AndroidManifest.xml:13`. The default SMS app is already woken for SMS_DELIVER and WAP push | Partial | Either remove it and use the Settings deep link (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), or prepare a justification (scheduled sends, MMS downloads). Play accepts only listed use cases | – |
| Package visibility: `<queries>` for all launcher apps and https browsers | `app/src/main/AndroidManifest.xml:23-33` (SMS Retriever hash and WebOTP detection) | Partial | Document the justification. Consider narrowing to specific packages or intents when possible. This is not the restricted `QUERY_ALL_PACKAGES`, but it is broad | – |
| No sale, advertising or analytics use of SMS data | No ads, analytics or crash SDKs in the Gradle dependencies (`app/build.gradle.kts`); log policy at `core-telephony/.../internal/AndroidSupport.kt:15` | Compliant | Keep a CI check for new SDKs (`check-offline-baseline.sh` exists) | – |

## 12. India: TRAI TCCCPR 2018 and amendments

TCCCPR 2018 set up DLT-based header and template registration and UCC complaints through 1909. Later amendments
(notably the February 2025 amendment) added the header suffixes **-P** (promotional), **-S** (service),
**-T** (transactional) and **-G** (government), changed complaint handling and assigned the **140-series**
(promotional) and **160-series** (service/transactional) number ranges to voice calls. Exact windows and formats
below should be checked against the gazetted text.

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| Recognise DLT headers `XY-HEADER[-P/S/T/G]` (XY is commonly described as access provider + service-area code) | `classify/.../SenderId.kt:63-81`. It **rejects all-digit entity headers** (`:73`). Promotional headers have historically been 6-digit numeric (for example `VM-612345`), so they fall through to SHORT_CODE / ALPHANUMERIC. The KDoc at `:32-35` calls the prefix a telemarketer id | Partial | Accept numeric entity headers when a prefix is present (optionally only with `-P` or length 6). Fix the prefix description. (`classify` is being edited by another stream, so coordinate) | Unit: `VM-612345`, `VM-612345-P` → DLT_HEADER, PROMOTIONAL |
| Use the traffic-type suffix (P/S/T/G) in classification | `SenderId.kt:15-25,74-79` | Compliant | None | Existing tests |
| 1909 UCC complaint SMS in the prescribed "`<message>,<sender>,<dd/mm/yy>`" shape, reviewed by the user before sending | `app/.../safety/FraudReport.kt:18,28-43` (template overridable per region, flattened text, 10-digit sender); `ConversationViewModel.kt:267-276` | Compliant | None | Unit: date in IST, `+91` stripped |
| Send the complaint from the SIM that received the UCC (the complaint is tied to the complainant's number) | `ui/fraud/FraudHelpViewModel.kt:143-156` picks the SIM with `ComplaintSim.choose` (`ui/fraud/ComplaintSim.kt`): the message's SIM if it is still active, else the default SMS SIM, else the only active SIM. It passes the SIM as the new `sub` argument of the compose route (`navigation/Routes.kt` `COMPOSE`), and the composer preselects that SIM if it is active (`NewConversationViewModel.kt:166`). The Report card says which SIM will send (`FraudHelpScreen.kt:297,353`). The conversation "Report spam" actions pass the message's SIM too | Compliant | Optional: warn if the user switches SIM in the composer | Unit `ComplaintSimTest`; dual-SIM: report a message from SIM 2 → card says "Sends from SIM 2", composer preselects SIM 2 |
| Complaint window (days since receipt; 3 days under the 2018 regulations, reportedly extended to 7 in 2025, verify) | Not checked | **Missing** | Show "Reports older than N days may be rejected" using a region-profile constant | Unit on the date check |
| DND / preference registration (1909 keyword SMS, TRAI DND app) | Not offered | **Missing** | Optional P2: a "Manage DND" entry that opens a prefilled 1909 SMS (keywords to verify with TRAI) or the TRAI DND app | – |
| 140-/160-series numbers for calls | Dak is not a dialer | N/A (dialer/Telecom) | Optional: treat 1600xxxxxx numbers quoted in bank SMS as legitimate call-backs in scam scoring | – |
| Bulk sending from personal SIMs must not become unregistered commercial communication | Broadcast cap of 100/day (`automations/.../broadcast/BroadcastLimits.kt:23`), terms sheet (`ui/broadcast/BroadcastTermsSheet.kt:72`), `docs/terms-acceptable-use.md` | Compliant | None | – |

## 13. DPDP Act 2023 (India) and GDPR (EU)

These apply only where Dak (as Data Fiduciary / controller) processes personal data off-device. That covers the
premium relay, Jev cloud classification, hosted backup and any support or telemetry. The DPDP Rules, 2025 bring
obligations in over a phased timeline. Check the current commencement dates.

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| Free tier: all processing on-device | Index encrypted locally (`core-index/.../crypto/IndexDatabaseFactory.kt:51`); `NoOpPremiumGateway` (`premium-api/.../PremiumGateway.kt:24-28`) | N/A (no off-device processing) | Keep the offline baseline check | `scripts/check-offline-baseline.sh` |
| Notice (itemised purpose, rights, grievance contact) and free, specific, informed, unambiguous consent before server processing; withdrawal as easy as giving | Jev opt-in toggle only (`DakSettings.kt:238-243`); relay "ciphertext only" by design (`PremiumGateway.kt:3-5`) | Partial | Consent screen per purpose (shared with the §11 disclosure). Withdrawal from the same setting, with server-side deletion. Keep consent records | UI + backend test: withdrawing stops calls and triggers deletion |
| Security safeguards and breach intimation (to the Data Protection Board and affected users under DPDP; within 72 h to the authority under GDPR Art. 33) | Masking before cloud (`classify/.../CloudClassifier.kt:9-10`); E2E ciphertext relay; no incident-response doc | Partial | Write an incident-response runbook with the notification templates and timelines | Tabletop exercise |
| Data principal / data subject rights: access, correction, erasure, grievance redressal (DPDP), access, erasure and portability (GDPR Art. 15-20) | Not present (premium not launched) | **Missing** | Account screen for export and delete. Publish grievance officer / DPO contact details | – |
| Retention and erasure once the purpose is served | Not defined for server data | **Missing** | Retention schedule (for example Jev payloads deleted immediately after scoring; relay blobs TTL ≤ 7 days) | Backend TTL test |
| Children's data: verifiable parental consent for users under 18 (DPDP); age thresholds under GDPR Art. 8 | No age gate for premium | **Missing** | Age confirmation for premium or cloud features. Do not use Jev for under-18s | – |
| Cross-border transfer (DPDP allows transfer except to notified countries; GDPR Chapter V) | No servers chosen yet | N/A (pending) | Choose the region. For EU users use SCCs or an adequacy decision | – |
| GDPR specifics: lawful basis (consent, Art. 6(1)(a)), processor contracts (Art. 28), DPIA for message content (Art. 35), pseudonymised text is still personal data | Masked text + sender header still identifies relationships (for example a bank header) | Partial | DPIA for Jev and relay. Treat masked payloads as personal data | – |

## 14. Text safety: UAX #9, UTS #39, UTS #46

| Requirement | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| UAX #9: isolate untrusted text (sender names, snippets) so it cannot reorder surrounding UI text; isolates (FSI/PDI) preferred | `ui/common/text/BidiText.kt:44-47` uses `android.text.BidiFormatter.unicodeWrap`, used at 26 call sites (inbox, conversation, notifications, search). The KDoc says FSI…PDI, but per its documentation `BidiFormatter.unicodeWrap` emits **embeddings** (LRE/RLE…PDF) plus LRM/RLM marks, not isolates | Partial | Wrap explicitly with U+2068 FSI … U+2069 PDI (or `BidiFormatter` with isolates if a target API offers it). Fix the KDoc | Unit: Arabic name followed by "₹500" shows the amount in LTR order; snapshot test |
| Strip bidi overrides and invisible characters from display names | `BidiText.kt:55-72` (keeps ZWJ/ZWNJ between letters for Indic scripts) | Compliant | None | Unit: `"‮gnp.exe"` → `"gnp.exe"` |
| Bidi controls inside message bodies and extracted links or amounts (for example an RLO that visually reverses a URL or amount) | No sanitisation of bodies found. The link warning prints `verdict.link.raw` (`LinkSafety.kt:104`) | Partial | Strip or visualise U+202A–U+202E and U+2066–U+2069 inside extracted URLs, phone numbers and amounts, and isolate each entity span | Body containing `https://evil.com/‮gpj.exe`: the warning shows the real order |
| Attachment file names sanitised (paths, controls, bidi, zero-width) | `mms-pdu/.../MmsSafety.kt:71-93` | Compliant | None | Existing unit tests |
| UTS #39 confusables for link hosts (skeleton, brand look-alikes) | `classify/.../LookalikeDomainChecker.kt:49-56,159-173`: a hand-picked Cyrillic/Greek/Armenian map plus NFKC, and every IDN host is flagged anyway | Partial | Generate the map from Unicode `confusables.txt` (MA table) at build time, or use ICU4J `SpoofChecker.getSkeleton` in the JVM module | `hdfcbаnk.com` (Cyrillic а) and `xn--` variants; fullwidth `ｈｄｆｃ` |
| UTS #39 mixed-script / restriction-level checks for sender names (MMS `From` encoded strings, contact names, e-mail-gateway originators) | None: no `SpoofChecker` or script-mixing test | **Missing** | Flag names that are "Highly Restrictive"-level violations (for example Latin + Cyrillic) in the scam banner. Alphanumeric TP-OA is GSM-7 only, so focus on MMS and e-mail senders | Unit: `"НDFC Bank"` (Cyrillic Н) flagged |
| UTS #46 / IDNA2008 processing for the punycode shown to users | `classify/.../LinkExtractor.kt:66` uses `java.net.IDN.toASCII`, which is **IDNA2003** (RFC 3490). It differs from UTS #46 for deviation characters (ß, ς, ZWJ/ZWNJ) and newer Unicode | Partial | Use ICU4J `IDNA.getUTS46Instance(NONTRANSITIONAL_TO_ASCII | CHECK_BIDI | CHECK_CONTEXTJ)`, or Android's `android.icu.text.IDNA` in the app layer | `faß.de` → `xn--fa-hia.de` (UTS #46 nontransitional) |
| Show the real (ASCII/punycode) host for IDN and userinfo links before opening | `LinkSafety.kt:107-109` | Compliant | None | – |
| Only http(s) links open from message text | `LinkSafety.kt:59-85` | Compliant | None | `intent:` / `javascript:` links are inert |

## 15. OWASP MASVS v2: high-level pointers

The security work stream owns detailed findings (`docs/security/threat-model.md`). This table only maps
categories to where Dak addresses them.

| Category | Dak implementation (file:line) | Status | Remediation | Test |
|---|---|---|---|---|
| MASVS-STORAGE | `allowBackup="false"` (`app/src/main/AndroidManifest.xml:37`); SQLCipher index with a Keystore-wrapped passphrase in `noBackupFilesDir` (`core-index/.../crypto/IndexPassphraseStore.kt:18,38`); encrypted backups (`backup/.../crypto/BackupCrypto.kt:39-56`). The Telephony provider itself is platform-managed plaintext | Compliant | Add `dataExtractionRules` (Android 12+) to state explicitly that nothing is extracted | `adb backup` / D2D transfer contains no Dak data |
| MASVS-CRYPTO | AES-GCM STREAM segments with per-file nonce prefix and final flag (`BackupCrypto.kt:39-56,240-241`); Keystore for keys | Compliant | Cross-review by the security stream (KDF parameters, recovery code entropy) | Known-answer and truncation tests |
| MASVS-AUTH | App lock with PIN or biometric, `FLAG_SECURE` (`app/.../MainActivity.kt:125-127`, `security/AppLockPolicy.kt:33,94`) | Compliant | None | – |
| MASVS-NETWORK | `targetSdk 35` (cleartext off by default, user CAs not trusted); MMS goes through the platform `MmsService`; `MmsSafety.isDownloadableContentLocation` (`MmsSafety.kt:41-55`). No network security config and no pinning for future premium endpoints | Partial | Add `network_security_config.xml` with `cleartextTrafficPermitted=false` and a pin-set (with backup pins) for Dak servers when they exist | Proxy with a user CA: premium calls fail |
| MASVS-PLATFORM | Permission-guarded exported components (§10); route token for deep links (`navigation/IntentRoutes.kt:54-61`); shared-URI guard (`:109-116`); explicit mutable PendingIntents (`internal/AndroidSupport.kt:40-49`); safe attachment opening (`AttachmentOpener.kt:10-29`) | Compliant | None | Drozer / `am start` fuzzing of MainActivity extras |
| MASVS-CODE | Hostile-input limits in the PDU decoder (`MmsSafety.kt:10-22`); `minSdk 26`; log policy (`AndroidSupport.kt:15`) | Compliant | Add dependency vulnerability scanning in CI | – |
| MASVS-RESILIENCE | Release build has `isMinifyEnabled = false` (`app/build.gradle.kts:45`) | Partial | Enable R8 (shrinking and obfuscation) with keep rules for Hilt, Room and serialization. Consider Play Integrity for premium APIs | Release build smoke test |
| MASVS-PRIVACY | EXIF dropped on image re-encode (§5); masked text for Jev; no analytics SDKs; lock-screen privacy options | Compliant | None | – |

---

## Prioritised remediation backlog

### P0: security, crash, data loss

1. **Make incoming SMS storage durable.** `core-telephony/.../sms/IncomingSmsProcessor.kt:36-78`,
   `internal/ReceiverSupport.kt:17,33`. First journal the PDUs, format and subscription id to no-backup storage.
   Then insert under `NonCancellable`. On failure or timeout, replay through WorkManager and delete the journal
   entry only after the insert succeeds. Today a timeout or a null insert (`:66`) loses the message, because the
   platform has already deleted its raw copy.
2. **Emergency texts must bypass the shared rate limiter and queue.** `send/TelephonyMessageSender.kt:74`,
   `di/TelephonyModule.kt:95`, `cost/DestinationCostClassifier.kt:55`. Send `CostKind.EMERGENCY` destinations at
   once with no limiter slot and no cost dialog, even while a broadcast is in progress.

### P1: interoperability, regulatory, store policy

3. **Done: Class 0 (flash) SMS** shown at once (heads-up + dialog), stored only on "Save" (`sms/FlashMessages.kt`,
   `sms/IncomingSmsPolicy.kt`, `IncomingSmsProcessor.kt`). See §1.
4. **Done: Replace-short-message PIDs 0x41–0x47** replace in place (`TelephonyProviderWriter.replaceIncoming`).
5. **Done: No automatic whole-message retry after a partial multipart send.** Per-part outcome per attempt
   (`sms/PartProgress.kt`, `SmsStatusProcessor.markPartlySent`); partial sends are FAILED with an accurate reason.
6. **Done: MMS-CTR acknowledgements** (`mms-pdu/.../MmsClientTransactions.kt`, `MmsDownloadManager.kt`): Retrieved by
   default, Deferred when a download waits, m-acknowledge-ind after a deferred retrieval, Unrecognised for undecodable
   or unsupported-version notifications. `enabledNotifyWapMMSC` picks the destination URL.
7. **Done: Carrier MMS config** (`carrier/CarrierMessagingConfig.kt`, `CarrierConfigRepository.kt`,
   `SendModePolicy.kt`): MMS / group MMS enablement (individual messages otherwise), SMS→MMS thresholds, message
   size, recipient, subject and text limits, separate-parts SMS. Image dimensions are read but not yet applied by
   `MmsMediaCompressor` (§7).
8. **Done: `sms:` / `smsto:` body parsing** in both entry points: intents (`navigation/SmsUriParser.kt`) and
   RESPOND_VIA_MESSAGE (`sms/RespondViaMessage.kt`, takes the encoded SSP).
9. **Done: Losing the SMS role** (`role/SmsRoleMonitor.kt`, `send/HeldSendStore.kt`): sends held (never emergency
   texts), downloads paused, composer read-only with "Make Dak your default SMS app"; everything resumes with the role.
10. **Video and audio adaptation for MMS** (Media3 Transformer → H.264/AAC MP4 or 3GP; AMR/AAC audio).
    `app/.../conversation/MmsMediaCompressor.kt:43-50`.
11. **DLT numeric promotional headers** (`VM-612345[-P]`) and the prefix description. `classify/.../SenderId.kt:32-35,73`.
    Coordinate with the classify work stream.
12. ~~**1909 complaint from the receiving SIM.**~~ Done (`ui/fraud/ComplaintSim.kt`, compose route `sub` argument).
13. **Play submission blockers:**
    - Permissions Declaration Form, Data safety form and privacy-policy link (`docs/build-plan.md:302-304`).
    - A per-flow prominent disclosure before any content leaves the device (Jev, webhooks, relay, hosted backup).
    - Justify or remove `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`app/src/main/AndroidManifest.xml:13`).
14. **DPDP/GDPR before premium or cloud launch:**
    - Consent records and withdrawal.
    - Rights (export and delete) and grievance / DPO contact.
    - Retention schedule, breach runbook and DPIA.
    - Age gate.

### P2: nice-to-have and hardening

15. **UAX #9:** use real FSI/PDI isolates (`ui/common/text/BidiText.kt:44-47`). Isolate entity spans and strip
    bidi controls inside extracted links and amounts (`ui/conversation/LinkSafety.kt:104`).
16. **UTS #46** instead of IDNA2003 (`classify/.../LinkExtractor.kt:66`). Generate the UTS #39 confusables from
    `confusables.txt` (`LookalikeDomainChecker.kt:159-173`). Add a mixed-script check for MMS and e-mail sender
    names.
17. **More MMS-CTR:**
    - m-read-rec-ind behind an opt-in setting.
    - X-Mms-Report-Allowed.
18. **SMIL:**
    - Put image and caption on one slide, with root-layout dimensions (`mms-pdu/.../Smil.kt:35-49`).
    - Follow SMIL order on receive (`mms/MmsProviderMapping.kt:170-191`).
    - Add a "warning" creation mode for non-core media.
19. **Remaining carrier config keys:** image dimensions in the compressor, report enablement, e-mail gateway (§7).
20. **Defensive block-list check** for MMS notifications (`WapPushProcessor.kt:34`).
21. **Other SMS details:**
    - Placeholder for binary (8-bit, no port) SMS (`IncomingSmsProcessor.kt:49`).
    - Reply-path SC honouring (`TelephonyMessageSender.kt:171-173`).
    - Per-SIM segment counter (`MessageSendController.kt:63-67`).
22. **vCard and vCalendar:**
    - Inline vCard parsing and "Add contact" (`MessageBubble.kt:321-333`).
    - Accept `text/vcard`, `text/calendar` and `text/x-vcalendar` shares (`AndroidManifest.xml:98`).
    - Cap vCard size with a no-photo fallback (`Composer.kt:422`).
23. **`scripts/sms-pdu.py` fixture flags:** `--pid` and `--flash` exist; still to add `--ref16`, `--order`, `--drop`,
    `--dcs`, `--port`, `--nls`, `--strict-ucs2`.
24. **Direct-boot-aware receive** with a content-free pre-unlock notification.
25. **TRAI:** complaint-window hint and a DND management entry.
26. **MASVS hardening:**
    - Enable R8 in release (`app/build.gradle.kts:45`).
    - Network security config with pins for future premium endpoints.
    - `dataExtractionRules`.
27. **Verify a rate-limiter assumption.** The `SendRateLimiter` KDoc (`send/SendRateLimiter.kt:3-10`) says the
    platform's per-app SMS counter resets when Dak's process dies. That counter lives in the phone process, so the
    claim is likely wrong. Check whether `SmsUsageMonitor` limits apply to the default SMS app on target Android
    versions, and fix the comment.
