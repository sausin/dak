# Dak threat model

Dak is the default SMS app, so it parses more attacker-controlled input than almost anything else on the phone,
and it holds the SMS role and the Telephony provider. SMS/MMS parsers have a long history of remote bugs
(Stagefright, iMessage zero-click, MMS parser crashes, WAP push abuse, Unicode crash strings), so this document
lists every input surface, what protects it, and what is still open. It covers the wave-2a red-team pass, the
wave-2b app UI and IPC hardening, and the wave-3 zero-click pass (see "Zero-click hardening").

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

## Zero-click hardening (wave 3)

Goal: no specially crafted SMS, MMS or WAP push can take over the phone, crash Dak, or make it lose messages or
spend money. Everything below runs with no user interaction. Every byte of a PDU, and everything a PDU refers to,
is treated as hostile: the MMSC URL, the fetched m-retrieve-conf, part names, addresses, display names and links.

### What a crafted message reaches, in order

1. **SMS_DELIVER** (`SmsDeliverReceiver`, guarded by `BROADCAST_SMS`). The raw PDUs go into the `SmsJournal` first.
   Then the platform parses them, Dak inserts the row into the inbox, and the dispatcher runs the handlers:
   notification, index/classifiers, automations.
2. **WAP_PUSH_DELIVER** (`BROADCAST_WAP_PUSH`). `MmsPduDecoder` decodes the push. An m-notification-ind then passes
   the content-location check and the flood guard before a download is queued. The downloaded m-retrieve-conf goes
   through the decoder again, then into the provider mapping, and then to the same handlers.
3. **Rendering**: the notification (`NotificationText`), the conversation bubble (links through `LinkSafety`,
   attachments through `AttachmentOpener`), and Coil image decoding, which samples to the view size.

### Findings and fixes

| # | Sev. | Area | Proof of concept | Fix |
|---|---|---|---|---|
| 1 | High | MMS download | An m-notification-ind with `X-Mms-Content-Location: http://localhost./x`, `http://[::ffff:127.0.0.1]/`, `http://[0::1]/` or `http://2130706433/` passed the loopback check. `java.net.URI` accepts all four hosts. | `MmsSafety.isLocalHost` now handles a trailing dot, IPv4-mapped and IPv4-compatible IPv6, compressed IPv6 loopback, and non-canonical IPv4 (decimal, hex, octal, short forms). |
| 2 | High | Provider / MMS text | A retrieved MMS carrying a 3 MB `text/plain` part. The inline `part.text` insert goes over Binder (1 MB limit) and fails. The message is lost and downloaded again five times. Between about 1 and 2 MB, every later read of the thread risks `SQLiteBlobTooBigException` (CursorWindow is 2 MB). | Inline text is capped at 32k characters per part and 64k per message (`MmsLimits.MAX_INLINE_TEXT_CHARS` / `MAX_MESSAGE_TEXT_CHARS`, `PduPart.text(maxChars)` decodes only the bytes it needs). |
| 3 | High | Backup restore | An SMS Backup & Restore XML row `<sms address="+1900…" body="YES" type="6"/>` (type 6 is queued) was restored as QUEUED. `OutboxRecovery` re-enqueues QUEUED rows at boot, so the phone sent the text with no confirmation and no cost check. | `BoxMapping.restoredBox`: restored outbox and queued messages become FAILED ("tap to retry"). This applies to every restore path through `ProviderWriter.restore`. |
| 4 | Medium | Decoder | 5 kB `From`/`To`, `Message-ID`, `Transaction-ID`, part `Content-Location`/`Content-ID`, a 1 kB media type, or thousands of content-type parameters. All were stored verbatim in provider columns and thread addresses. | Addresses over 256 characters are dropped (From becomes "unknown"). Identifiers and part headers over 1024 characters are dropped. Media types over 255 characters become `application/octet-stream`. At most 16 unmodelled parameters are kept. |
| 5 | Medium | Decoder memory | A 16 MiB PDU with the payload three multiparts deep was copied once per level: about 4× the input size on the heap. | Nested multiparts are parsed in place (`WspReader.duplicate`). Only leaf parts are copied, so decoding allocates at most about 2× the input size. |
| 6 | Medium | WAP push flood | Thousands of forged notifications, each with a new URL, made thousands of inbox rows and fetches. Each fetch also acts as an IP beacon for the sender. | `NotificationFloodGuard`: 10 auto-downloads per sender and 30 per device per hour. Past that the user has to tap to download. Past 200 an hour, notifications are dropped. |
| 7 | Medium | Crash safety | A `StackOverflowError` (an Error, not an Exception) in any handler, or any exception escaping a fire-and-forget coroutine, killed the process. Doing that on every delivery is a remote denial of service against the SMS app. | `IncomingDispatcher` and `runAsync` also catch `StackOverflowError`. The application and telephony scopes have a logging `CoroutineExceptionHandler`. Out-of-memory still crashes. |
| 8 | Medium | Notifications | A 39k-character concatenated SMS, or a 32k-character MMS, went verbatim into MessagingStyle, the OTP RemoteViews and the repeat-collapse extras, so `notify()` could exceed the Binder limit. RLO/LRO/isolate controls in a body could visually reorder the rest of the notification. | `NotificationText.body`: at most 2000 characters, and explicit bidi embeddings, overrides and isolates are stripped. |
| 9 | Low | Links | `https://bank.example\@evil.example`. The link checker and the browser treat `\` as `/` and see host `bank.example`, but `android.net.Uri`, which decides which app handles the intent, sees `evil.example`. | `LinkSafety.normalizedWebLink` turns `\` into `/` before `Uri.parse`, and refuses links with whitespace or control characters or longer than 4096 characters. |
| 10 | Data loss (P0) | SMS receive | A provider insert that failed or overran the 7 s `goAsync` budget lost the SMS, because the platform had already deleted its raw copy. | `SmsJournal` stores the PDUs (fsync'd, atomic rename) before any other processing. The insert runs `NonCancellable`. The journal entry is removed only once the row exists. `SmsJournalReplayWorker` replays leftovers at the next receive, at boot and at app start. Replays are idempotent: an existing identical inbox row is reused. The journal is bounded (200 pending, PDUs ≤ 1 KiB, ≤ 255 parts). A poison entry is quarantined after 5 failures (at most 50 kept) and reported in a notification that carries no message content. |
| 11 | Safety (P0) | Sending | A text to an emergency number waited behind the shared 30-per-30-minutes limiter, for example during a broadcast. | `SendRateLimiter.reserveEmergency` never delays. `EmergencyDestinations` recognises 112/911 anywhere, plus libphonenumber emergency numbers for the home and serving regions. The classifier now recognises Australia's `000` (it looked like an international prefix). Reservations are persisted, because the platform's counter survives Dak's process. |

Already in place and re-verified in this pass: receivers guarded by `BROADCAST_SMS`, `BROADCAST_WAP_PUSH` and
`SEND_RESPOND_VIA_MESSAGE`; result, boot and notification-action receivers and both FileProviders not exported;
explicit PendingIntents; the route token on `MainActivity`; the `EXTRA_STREAM` confused-deputy filter; the
attachment MIME allow-list; http(s)-only links; safe part file names; backup `ArchiveLimits`; and logs that never
contain bodies, codes or addresses.

### Tests that guard these defences

- `mms-pdu` `HostileInputFuzzTest` (JVM harness). It uses a deterministic, grammar-aware random PDU generator (every
  header code and value encoding, lying lengths, nested multiparts, oversized strings) plus mutation of valid PDUs:
  60,000 inputs per generator under a 60 s cap. For every input it asserts that no Throwable escapes, that no input
  takes over 2 s, and that allocation stays within 2 × input + 4 MiB (measured per thread with `ThreadMXBean`), and it
  checks every `MmsLimits` bound on the outputs. Targeted regressions cover findings 1, 2, 4 and 5. It runs next
  to the existing `SecurityFuzzTest`, which runs 120,000 mutations.
- `mms-pdu` `NotificationFloodGuardTest` (finding 6).
- `core-telephony` (runs in CI): `SmsJournalTest` (round trip, re-delivery, quarantine, bounds, corrupt files,
  path-safe keys, a decoder fuzz loop), `EmergencySendTest`, `RestoreBoxTest`, and the added
  `MmsMappingTest.hostileTextPartsAreBoundedBeforeTheProvider`.
- `app` (runs in CI): `NotificationTextTest` and `LinkSafetyNormalizationTest`.

### Reported to other owners (not changed here)

- **Automations** (`app/automation`, being reworked in parallel):
  - The "schedule reply" action stores its send without a `ruleId`, so `ScheduledSendExecutor` skips the
    premium-rate check.
  - There is no per-sender cool-down on auto-replies, so two auto-repliers ping-pong forever at 30 texts per
    30 minutes. `ForwardLoopGuard` covers forwards only.
  - Unattended sends are spread out but never capped: N matching messages cause N forwards.
  - The Notify action posts `event.body` unbounded.

  Proposed patches are in the hand-off report.
- `:classify` / `:finance` were checked read-only with a timing harness: every receive-path processor (the pipeline,
  `TransactionParser.parse` and `parseBillReminder`, `FakeCreditDetector`, `OtpExtractor`, `LinkExtractor`,
  `EntityExtractor`, `MoneyParser`, `Masker`), run on about 630 repeated-token bodies of 4k, 39k and 64k
  characters. Nothing took more than 300 ms or threw. `TransactionParser.parse` is called on the whole body with
  no cap of its own; the 64k MMS body cap above now bounds it.

## Residual risks

- **Polynomial regex cost in user regexes** (`.*a.*b` on 4000 characters) is bounded but not zero, and Android's
  ICU matcher cannot be interrupted. The `RegexSafety` static check covers exponential cases only.
- **Forged MMS notifications are still fetched** when the URL is a plausible `http(s)` URL: every MMS client works
  this way, and it happens over the carrier APN through the platform MmsService. The sender learns that the
  device fetched the URL, which acts as a read receipt or IP beacon on the carrier network. WAP push floods are
  rate-limited (`NotificationFloodGuard`), but within the budget a DNS name that resolves to a private or loopback
  address is still fetched. Dak cannot resolve names itself, because MmsService does the fetch, often through the
  carrier's MMS proxy.
- **Very long bodies in the conversation view**: a 39k-character SMS (the platform maximum) or a 64k-character MMS
  body is laid out as one Compose text. This can be slow on low-end phones, but it is bounded. A "show more" fold
  would remove the cost.
- **Automatic MMS download policy**: MMS from anyone downloads automatically, as in every major client. It is limited
  to the carrier APN, to 16 MiB, to non-roaming, and by the flood guard. Users who want no attacker-triggered fetches
  can turn off auto-download.
- **SMS journal**: it holds message PDUs in plaintext in no-backup app storage, and only until the inbox write
  succeeds (quarantined entries at most 50). The provider database itself is not encrypted either. A
  replayed message whose first insert failed may be announced twice.
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
  JVM tests (`MmsMappingTest`, `LinkDetectorSecurityTest`) run there. The wave-3 journal, flood guard, emergency
  bypass, restore mapping and notification changes are in the same position. Their pure parts (`SmsJournal`,
  `SendRateLimiter`, `EmergencyDestinations`, `BoxMapping`, `MmsProviderMapping.partRows`, `NotificationText`) were
  compiled and tested on the JVM outside the Android build. The receiver, worker and Android wiring still need an
  emulator run: inject a failing provider insert and check the replay, and send text-to-112 while a broadcast is
  queued.
