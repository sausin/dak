# Dak threat model

Dak is the default SMS app, so it parses more attacker-controlled input than almost anything else on the phone,
and it holds the SMS role and the Telephony provider. SMS/MMS parsers have a long history of remote bugs
(Stagefright, iMessage zero-click, MMS parser crashes, WAP push abuse, Unicode crash strings), so this document
lists every input surface, what protects it, and what is still open. It covers the wave-2a red-team pass and the
wave-2b app UI and IPC hardening.

## Security baseline: the free tier works offline

Free features must not depend on AI, cloud or network services while they run. CI enforces this with
`android/scripts/check-offline-baseline.sh`. The script fails the build if production code outside `src/premium`
references a network client (`HttpURLConnection`, OkHttp, Retrofit, Ktor, `java.net.http`, WebSocket), an AI or
Firebase SDK, or binds a `CloudClassifier`.

- **The only network path is the MMS download.** Dak calls the platform `SmsManager.downloadMultimediaMessage` /
  `sendMultimediaMessage`. The system MmsService runs the HTTP exchange over the carrier's MMS APN. Dak never
  fetches a URL itself, and never fetches message content over the ordinary internet.
- There is no WebView. Links in messages open in the user's browser, and only after the user taps them and the
  link-safety check runs.
- Free builds bind the cloud classifier, webhooks and the relay to no-ops (`premium-api`). Message text never
  leaves the device.
- **Open item:** `:core-telephony` still declares `INTERNET`, `ACCESS_NETWORK_STATE` and `CHANGE_NETWORK_STATE`.
  The platform MMS API does not need them from the caller, because MmsService owns the network. Dropping them
  would let the OS enforce the offline guarantee. Remove them after a device test on a Pixel and on one OEM phone
  confirms MMS still sends and downloads (see "Residual risks").

## Attackers

| Attacker | Capability |
| --- | --- |
| **Remote sender** | Sends SMS, concatenated SMS, flash (class-0) SMS, binary/port SMS, WAP push (a forged m-notification-ind), and MMS content served from any URL a notification points to. Can spoof alphanumeric sender IDs. Needs no interaction from the user. |
| **Malicious file** | A backup or export the user imports or restores (SMS Backup & Restore XML, Fossify JSON, SMS Organizer ZIP/JSON, a Dak archive). These files sit on shared storage (Drive, a SAF folder, Downloads), so anyone who can write there can tamper with them. |
| **Local app** | Any installed app with no special permissions. It can send intents to Dak's exported components (SENDTO/SEND/VIEW with extras, `EXTRA_STREAM` URIs) and can try to reach content URIs. |

Out of scope: a rooted device, a compromised system image or carrier, and the platform MmsService's own HTTP
stack.

## Surfaces and mitigations

### 1. MMS PDU decoder (`:mms-pdu`)

Both the WAP push body and the downloaded m-retrieve-conf are attacker-controlled.

| Threat | Mitigation |
| --- | --- |
| Out-of-bounds read, or a huge allocation from a length field (uintvar up to 2^32) | `WspReader` checks every length against the bytes that remain before it allocates or slices. A test with a 2 GiB declared length fails cleanly. |
| Oversized PDU | `MmsLimits.MAX_PDU_BYTES` (16 MiB) is checked before parsing. The downloader also checks the file length before it calls `readBytes`. |
| Thousands of parts, or nested multipart bombs | `MAX_PARTS` (256) counts all parts after nested multiparts are flattened. Nesting depth is 3; anything deeper stays one opaque part. |
| Thousands of To/Cc headers, which would become thread members and addr rows | `MAX_ADDRESSES` (100) per PDU. Restores apply the same cap. |
| Megabyte-long subjects in notifications | Subject, retrieve-text and response-text are truncated to 1024 characters. |
| Garbage in the charset or text | `MmsCharset.decode` never throws: unknown charsets fall back to UTF-8 and malformed bytes become U+FFFD. |
| An unexpected exception escaping into a receiver | `decode` never throws. It maps every internal failure to a `PduError`. |
| Path traversal or spoofing through part names (`../../x`, `/data/...`, NUL, RLO) | `MmsSafety.safeFileName` / `PduPart.safeFileName` remove path components, control, bidi and zero-width characters, and leading dots, and cap the length. `:core-telephony` stores only sanitised `name` / `fn` values and shows sanitised attachment names. `cl` / `cid` stay raw, because SMIL references them, and they are never used as paths. The provider picks part file paths itself. |

Tests: `SecurityFuzzTest` runs a deterministic seeded structure-aware mutation fuzzer (120k iterations under a
60 s cap, about 1 s locally) plus targeted cases, next to the existing truncation and random-garbage fuzzers.

### 2. Receivers and provider I/O (`:core-telephony`)

| Threat | Mitigation |
| --- | --- |
| Forged broadcasts | `SMS_DELIVER` requires `BROADCAST_SMS`. `WAP_PUSH_DELIVER` requires `BROADCAST_WAP_PUSH` and is filtered to `application/vnd.wap.mms-message`. `RESPOND_VIA_MESSAGE` requires `SEND_RESPOND_VIA_MESSAGE`. Result, boot and FileProvider components are not exported. The PendingIntents are explicit, with unique data URIs; they are mutable only where the platform must fill in result extras. |
| Malformed SMS_DELIVER (null PDUs, empty or null bodies) | `getMessagesFromIntent` is wrapped. An empty list is dropped, and null bodies become `""`. Work runs under `goAsync` with a 7 s budget, and each handler is isolated with try/catch and a timeout. |
| Very long concatenated SMS | The platform reassembles the parts. The body is stored verbatim. Classification looks at the first 4000 characters and link extraction at the first 20,000. |
| Class-0 (flash) SMS | Stored and notified like any other SMS. Dak never shows a full-screen or overlay dialog for it. |
| Data / port-addressed SMS | Not handled: Dak registers no `DATA_SMS_RECEIVED` receiver. MWI "do not store" messages are skipped. |
| Status reports | They arrive only on Dak's own explicit PendingIntents, never as `SMS_DELIVER`. |
| WAP push with other content types | The manifest filter only admits MMS. m-delivery-ind and m-read-orig-ind can only update the `st` / `read_status` of a sent message with the same Message-ID. Other PDU types are ignored. |
| **Forged m-notification-ind pointing at `file:`, `content:`, `javascript:`, a loopback address or a relative URL** | `MmsSafety.isDownloadableContentLocation` accepts only absolute `http`/`https` URLs with a host, no userinfo, no whitespace, control or non-ASCII characters, at most 1024 characters, and not loopback or unspecified addresses. Anything else is dropped before it is stored. The check runs again before every download attempt, which also covers provider rows written by other SMS apps. |
| Forged notification announcing a huge message | A declared `m_size` above 16 MiB is never auto-downloaded; the user has to tap. The downloaded file is size-checked before it is read. |
| Downloads while roaming | Auto-download is off while roaming unless the user allows it (`TelephonySettings`). An expired notification is never fetched. |
| Hostile names or addresses in restored MMS | Restores cap recipients at 100 and sanitise part names. |
| Sensitive data in logs | The code was checked with grep: logs carry only exception class names, result codes and PDU error offsets, never bodies, OTPs or full addresses. |

### 3. Regexes applied to message bodies (`:classify`, `:automations`, `:finance` parser, `:core-index`)

| Threat | Mitigation |
| --- | --- |
| Catastrophic backtracking in bundled template rules | The pipeline truncates bodies to `ClassifierPipeline.MAX_CLASSIFY_CHARS` (4000). A harness runs every bundled rule against about 20 pathological inputs. OTA bundles must pass signature verification. |
| `Masker` email regex was quadratic (23 s on a 50k-char `x.x.x…` body) | **Fixed:** repeats are now bounded to RFC lengths. **Fixed:** Masker also threw `IllegalArgumentException` on non-ASCII digits (Devanagari/Arabic-Indic); `\p{Nd}` now masks them. |
| **`LinkDetector` (`has:link`) threw StackOverflowError on `a.a.a.…`** (an Error, not an Exception, inside the indexer: a crash or a stuck reindex) | **Fixed:** replaced with a linear scanner. A regression test is in `core-index`. |
| User-written automation regexes (the user writes the pattern, the attacker writes the input) | `RegexSafety` rejects nested or ambiguous repetition (`(a+)+`, `(a\|aa)*`, `(\w+\s?)*`), backreferences, and patterns over 500 characters. The rule editor shows the problem, and the engine never runs an unsafe pattern. Input is capped at 4000 characters. Android's ICU regex cannot be interrupted, so the check is static. |
| Transaction parser, OTP extractor, link extractor | Harness tests on 50k-character pathological bodies, plus 1M characters for the OTP extractor: all finish in under a second. |
| WhatsApp relay URI built from message text (`&phone=` / `#` injection) | **Fixed:** both values are percent-encoded. |

### 4. Links (`:classify` LinkExtractor / LookalikeDomainChecker)

- Only `http://`, `https://` and `www.` are extracted. `javascript:`, `intent:`, `content:`, `file:`, `tel:`,
  `sms:`, `data:` and `market:` never become links.
- A link ends at a bidi override, an isolate or a zero-width character, so text reversed with RLO cannot pass as
  part of the URL.
- The host is parsed by hand. Userinfo is stripped, so `https://bank.com@evil.xyz` resolves to `evil.xyz`, and
  links that carry userinfo are flagged.
- Internationalised hosts used to parse to a null host and got no warning. They are now extracted with the
  Unicode form and a punycode `asciiHost`. Confusable letters (Cyrillic, Greek, Armenian, fullwidth) are folded
  to a Latin skeleton, so `hdfcbаnk.com` is flagged as imitating "HDFC Bank". Any other IDN host is flagged too.

### 5. Backup and import files (`:backup`)

| Threat | Mitigation |
| --- | --- |
| **Zip-slip: `attachments/../../databases/index.db`** reaching `File(tempDir, sha)` in the app | **Fixed:** attachment entries must be exactly 64 lower-case hex characters, and anything else is skipped. |
| Zip bombs | Each entry is capped while it inflates (attachments 64 MiB, metadata 16 MiB, message chunks 256 MiB, JSONL lines 8 MiB). There is also an 8 GiB total, a 200k entry count, and a 128 MiB cap on whole-file JSON imports, with the ZIP entry capped while it inflates. Declared sizes are never trusted. |
| JSON nesting bombs (StackOverflowError in a recursive parser) | `checkJsonDepth` (64 levels, string-aware and linear) runs before every parse of imported JSON. |
| Incremental-chain loops or path syntax in `parentId` / `latest.json` | Cycle detection, a maximum chain length of 10,000, and snapshot IDs restricted to `[A-Za-z0-9_-]{1,128}`. |
| XML entity expansion (billion laughs, XXE) | The tokenizer never expands DTD entities. It decodes only the five predefined entities and numeric references, and skips a DOCTYPE together with its internal subset. |
| Huge XML names, attributes or text | Names are capped at 256 characters, attributes at 256 per element, attribute values at 24M characters, and text runs at 1M characters. Unterminated comments are skipped without buffering. |
| Invalid surrogates, `&#0;` | Unpaired surrogates become U+FFFD; paired decimal surrogates, as SMS Backup & Restore writes emoji, still combine. `&#0;` is left literal. |
| Invalid base64 or oversized MMS in XML/JSON | Lenient MIME decoding. Garbage drops the part, not the import. At most 256 parts and 100 addresses per MMS. |
| **KDF DoS: a crafted header with 2^31 PBKDF2 iterations** | **Fixed:** iterations must be in `[100k, 5M]` on read and on write. This also sets a floor against weakly protected backups. |
| GCM segment length from the stream (was up to 64 MiB) | **Fixed:** a segment may be at most `SEGMENT_SIZE + 16` bytes. Salt, nonce-prefix and wrapped-key lengths are validated, so a malformed header raises a typed `MalformedHeaderException` rather than an index error. |

### 6. Index SQL (`:core-index`)

All SQL values are bound as arguments; column and table names are compile-time constants. FTS MATCH strings are
built only from `Highlighter.tokenize` output (letters, digits and marks, lower-cased). Quotes, `*`, `:`,
parentheses and `-` can never reach the MATCH grammar, and a bare `OR`/`NEAR` becomes `or*`. LIKE patterns go
through `escapeLike` with `ESCAPE '\'`. Message content is indexed data, never query text. No issues were found.

### 7. Manifest and IPC

Exported components: `MainActivity` (launcher; SENDTO/VIEW for sms/smsto/mms/mmsto; SEND/SEND_MULTIPLE for
text, vCard and media) and the three platform-guarded telephony components. Not exported: every result, boot
and notification-action receiver, both FileProviders (`cache/camera/` and `cache/dak_mms/` only), and the
startup provider. `allowBackup=false`. The mutable PendingIntents are all explicit.

## App UI and IPC hardening (wave 2b, all fixed)

1. **Intent route injection: fixed.** `MainActivity` is exported, and it used to accept `EXTRA_ROUTE` from any
   caller, so another app could open any screen with any arguments. `IntentRoutes.routeFor(context, intent)` now
   honours `EXTRA_ROUTE` only together with a per-install random token (`navigation/RouteToken.kt`: 32 random
   bytes in private prefs, compared in constant time). Only `IntentRoutes.open()` adds the token, and only Dak's
   own notification and alarm PendingIntents use it; other apps cannot read their extras.
   - Launchers can read shortcut intents, so shortcuts never carry the token. Conversation shortcuts use
     `IntentRoutes.openConversationShortcut` (`dak://conversation/<id>`), which can only open that conversation.
   - The static "Report fraud" shortcut maps its own action (`app.dak.action.REPORT_FRAUD`) to fraud help with no
     arguments.
   - Without the token, other apps get only what the platform contracts allow: compose from SENDTO/VIEW/SEND,
     fraud help, and opening a conversation by id.
2. **Confused-deputy `EXTRA_STREAM`: fixed.** `IntentRoutes.sharedStreams(context, intent)` keeps only
   `content://` URIs. It rejects `file://`, Dak's own authorities (`<applicationId>` and `<applicationId>.*`) and
   the Telephony provider authorities (`mms`, `sms`, `mms-sms`, `telephony`, …), and accepts at most 10 URIs per
   share. `MmsMediaCompressor` reads at most the MMS budget (not `readBytes()`), refuses images over 200 MP before
   decoding, and handles provider exceptions.
3. **Link opening: fixed.** `LinkSafety.open` launches only parsed `http`/`https` URIs (`https://` is prefixed only
   to `www.` links), as `CATEGORY_BROWSABLE`. For IDN hosts and links with userinfo, the warning dialog shows the
   host that will actually open, in punycode (`asciiHost`).
4. **Attachments: fixed.** The sender-chosen MIME type no longer picks the handler.
   `ui/conversation/AttachmentOpener.kt` opens only image (not SVG), video and audio, `text/plain`, vCard and
   vCalendar with ACTION_VIEW. Anything else (APK, HTML, …) goes to a "Save or share" chooser as
   `application/octet-stream`.
5. **Restore temp files: fixed.** `BackupManager` checks that the attachment name is 64 lower-case hex characters
   before `File(tempDir, sha)`, in addition to the check in `:backup`.
6. **Automation "open" action: fixed.** `AutomationNotifications.postOpen` allows only `http`, `https`, `tel`,
   `geo`, `mailto`, `sms`, `smsto` and `whatsapp`.
7. There is no WebView, `Html.fromHtml` or `Linkify` in the app. `annotateMessage` builds links only from
   `LinkExtractor`, so `tel:` and `intent:` auto-linking cannot happen.

## Residual risks

- **Polynomial regex cost in user regexes** (`.*a.*b` on 4000 characters) is bounded but not zero, and Android's
  ICU matcher cannot be interrupted. The `RegexSafety` static check covers exponential cases only.
- **Forged MMS notifications are still fetched** when the URL is a plausible `http(s)` URL: every MMS client works
  this way, and it happens over the carrier APN through the platform MmsService. The sender learns that the
  device fetched the URL, which acts as a read receipt or IP beacon on the carrier network. There is no rate limit
  on WAP push floods yet: many forged notifications create many rows and download jobs, bounded by WorkManager
  uniqueness per content location.
- **Forged delivery/read reports:** a WAP push m-delivery-ind or m-read-orig-ind that names the Message-ID of a
  sent MMS can change its delivered/read status. This only affects status metadata, and Message-IDs are hard to
  guess, but it is not authenticated.
- **Platform components** (the MmsService HTTP stack, the SMS PDU parser in telephony, media codecs used to show
  attachments) are outside Dak's control. Keeping attachments in `content://mms/part` and handing them to system
  viewers limits Dak's own exposure.
- **The SMS Organizer importer** buffers up to 128 MiB and parses in memory. That is acceptable for a
  user-initiated import, but low-RAM devices may still hit OOM near the cap.
- **Network permissions** (see the baseline section) stay declared until the device test.
- **Not yet verified on a device:** the `:app`, `:core-telephony` and `:core-index` changes compile only in CI, and their
  JVM tests (`MmsMappingTest`, `LinkDetectorSecurityTest`) run there.
