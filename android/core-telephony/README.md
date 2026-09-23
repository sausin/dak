# :core-telephony

The only code that touches the Telephony provider, SmsManager and the default-SMS-app broadcasts. Package
`app.dak.telephony`. Everything else codes against the contracts in `Contracts.kt`; Hilt binds them in
`di/TelephonyModule.kt` (`TelephonyBindingsModule`, `SingletonComponent`).

## Contracts and implementations

| Contract | Implementation | Notes |
| --- | --- | --- |
| `ProviderReader` | `TelephonyProviderReader` | SMS + MMS merged newest-first by walking both cursors (no SQL LIMIT needed); null projections and name lookups, so OEM schemas without `sub_id` work (falls back to `sim_id`/slot columns, else `NO_SUB_ID`). MMS body = text/plain parts; attachments use `content://mms/part/<id>` URIs. Filters MMS to `m_type` 128/130/132. Provider errors → empty results. |
| `ProviderWriter` | `TelephonyProviderWriter` | Verbatim inserts; retries without `sub_id` on OEM providers lacking it. `threadIdFor` returns -1 when the platform refuses. `restore` re-threads by address. Extra: `insertIncoming(IncomingSms)`, `insertOutgoing(..., deliveryReportRequested)`, `markSmsFailed`, `setSmsDeliveryStatus`. |
| `ProviderChanges` | `TelephonyProviderChanges` | One ContentObserver (`content://mms-sms/`, `sms`, `mms`) while collected, shared; bursts coalesced into one emission per 300 ms. `requestReconcile()` forces an emission (the index schedules its own periodic worker). |
| `SimRepository` | `TelephonySimRepository` | Active SIMs (slot order) + remembered removed SIMs (`isActive = false`, slot -1). Empty without READ_PHONE_STATE: call `refresh()` after grant. Number: API 33+ `getPhoneNumber` (READ_PHONE_NUMBERS), else `SubscriptionInfo.number`. Hot-swap via OnSubscriptionsChangedListener. `isRoaming` per subscription. |
| `MessageSender` | `TelephonyMessageSender` | See "Sending". `failureReason(key)` gives the text for the "tap to retry" bubble. |
| `MmsDownloads` | `TelephonyMmsDownloads` | Persistent `MmsDownloadState` per notification-ind id; unknown notification rows read as `Failed("Not downloaded yet", 0)`. `replacementFor(key)` maps a downloaded notification to its new retrieved-message key. |
| `BlockedNumbers` | `TelephonyBlockedNumbers` | `BlockedNumberContract`, guarded by `canCurrentUserBlockNumbers`. Receivers do not filter: the platform drops blocked senders before SMS_DELIVER (API 24+). |
| `NumberNormalizer` | `TelephonyNumberNormalizer` | Country: user override (`TelephonySettings`) → SIM `countryIso` → `simCountryIso` → locale. Rules in the pure `E164Normalizer`: never touches alphanumeric ids, e-mail, `*`/`#` codes or short codes (< 7 digits); only rewrites numbers libphonenumber validates. |
| `IncomingMessageHandler` (set) | contributed by other modules with `@IntoSet` | Declared with `@Multibinds` (empty set is valid). Run by `IncomingDispatcher` in priority order, each isolated (try/catch + 2.5 s timeout). A message whose provider write failed arrives with `providerId == UNPERSISTED_PROVIDER_ID` (notify it, don't index it). |

Contract additions (all with default bodies, so existing implementers/fakes still compile):
`ProviderChanges.requestReconcile()`, `MessageSender.failureReason(key)`, `MmsDownloads.replacementFor(key)`,
`UNPERSISTED_PROVIDER_ID`.

Other public helpers: `DefaultSmsRole.isDefault(context)` / `requestIntent(context)` (RoleManager on Q+,
ACTION_CHANGE_DEFAULT below), `SmsSegmentCounter.count(text)` → `SmsSegments`, `ExactAlarms.canSchedule(context)` /
`settingsIntent(context)`, `TelephonySettings` (normalise numbers, MMS auto-download incl. roaming, delivery
reports, m-notifyresp-ind, per-SIM home-country override; SharedPreferences so receivers can read it
synchronously), `SendRateLimiter`.

## SMS cost classification (`cost/`, pure JVM)

- `DestinationCostClassifier().classify(destination, simCountryIso, networkCountryIso, isRoaming): CostVerdict` —
  libphonenumber `ShortNumberInfo.getExpectedCostForRegion` for short codes (judged in the SIM **home** region) and
  `PhoneNumberUtil` number type / calling code for full numbers. `CostVerdict(destination, kind, destinationRegion,
  roaming)`; `kind: CostKind` in precedence order `EMERGENCY` (info, never blocked), `PREMIUM_RATE` (strong),
  `ALPHANUMERIC` (can't receive replies), `UNKNOWN_SHORT_CODE`, `INTERNATIONAL` (calling code ≠ home), `ROAMING`
  (abroad only: domestic roaming is not flagged), `STANDARD_SHORT_CODE`, `TOLL_FREE`, `NORMAL`; `severity`
  (`NONE`/`INFO`/`MILD`/`STRONG`) and `needsConfirmation` (MILD+).
- `CostPolicy.toConfirm(verdicts, subId, approvedKeys, warnRoaming)`, `CostPolicy.allowUnattended(verdict, subId,
  approvedKeys)` (premium-rate refused unless approved), `CostPolicy.approvalKey(destination, subId)`.
- Note: libphonenumber 8.13.x has no premium or tariff data for Indian commercial short codes (5xxxx), so from an
  Indian SIM they classify as `UNKNOWN_SHORT_CODE` (mild warning); `1909`, `121`, `198`, `199` are `TOLL_FREE`.
- The Android wrapper (SIM home/network country, settings, approvals) is `app.dak.safety.SendCostGuard` in :app.

## Receiving

- **SMS** (`SmsDeliverReceiver`): `goAsync()` + app-scope coroutine, 7 s budget. Parts assembled with
  `getMessagesFromIntent`; subscription from `android.telephony.extra.SUBSCRIPTION_INDEX` / `subscription` / OEM
  keys / slot mapping / default SMS sub. Written immediately to `Telephony.Sms.Inbox` (address, body, date,
  date_sent, sub_id, read=0, seen=0, protocol, service_center, reply_path_present), then handlers run.
  MWI "do not store" control messages are skipped.
- **MMS** (`WapPushDeliverReceiver`): m-notification-ind stored as an inbox row (m_type 130, ct_l, tr_id, exp,
  m_size, sub_id) + From addr, duplicates by ct_l/tr_id ignored, then `MmsDownloadWorker` is enqueued (unique per
  content location, expedited, exponential backoff from 30 s, 5 attempts). The worker calls
  `downloadMultimediaMessage` on the per-subscription SmsManager (the platform uses that SIM's MMS APN/MMSC)
  into a `MmsFileProvider` file and waits for `MmsDownloadedReceiver`, which parses the m-retrieve-conf, stores
  pdu + parts (`content://mms/<id>/part`, text inline, binary streamed) + addr rows in the right (group) thread,
  deletes the notification row and runs handlers. Failures keep the notification row with
  `MmsDownloadState.Failed(reason, attempts)`; handlers get the notification row once the download is given up
  (or when auto-download is off / roaming), so the user is always notified.
  No network constraint on purpose: the MMS APN works with mobile data off, where a "connected" constraint would
  block forever. m-delivery-ind / m-read-orig-ind update `st` / `read_status` of our sent message.
  m-notifyresp-ind is sent only if `TelephonySettings.sendMmsNotifyResponse` (default off; the platform download
  API does not send one and most MMSCs don't need it). m-acknowledge-ind is not sent (only for deferred retrieval).

## Sending

- **SMS**: per recipient, normalised (setting, default on) and written to the outbox (`status` PENDING when a
  delivery report is requested), then `divideMessage` + `sendTextMessage` / `sendMultipartTextMessage` with one
  sent and one delivery PendingIntent per part (explicit, unique data URI, `FLAG_MUTABLE` on API 31+ because the
  platform fills in `errorCode` / `pdu` / `format` extras). SmsManager: `createForSubscriptionId` on API 31+,
  `getSmsManagerForSubscriptionId` below. `SmsStatusReceiver` moves OUTBOX → SENT when all parts (or the last)
  reported OK; delivery sets `status` COMPLETE / PENDING / FAILED (3GPP and 3GPP2 decoding). Retryable failures
  (no service, radio off / DSDS, generic, rate limit, modem/network) → QUEUED + `SendRetryWorker` with 30 s … 30 min
  backoff, 5 attempts, then FAILED (`error_code` kept). All sends pass `SendRateLimiter` (30 per 30 min, booked
  slots, bulk spread deterministically).
- **MMS**: `MmsMessageBuilder` (SMIL + parts) → `MmsPduEncoder`, size checked against the carrier's
  `maxMessageSize` (the sender does not compress: the composer must scale images), stored in the MMS outbox
  (pdu + parts + addr), `sendMultimediaMessage` via FileProvider URI. `MmsSentReceiver` parses m-send-conf:
  SENT with `m_id`, or retry (transient) / `msg_box` FAILED (5) with a reason. Retries re-encode from the
  provider rows, so attachment files need not survive.
- `retry(key)` works for both; the boot/update receiver marks messages stuck in the outbox FAILED ("interrupted"),
  re-enqueues QUEUED SMS and pending MMS downloads.

## Manifest (merged into :app)

Permissions: RECEIVE_SMS, SEND_SMS, READ_SMS, RECEIVE_MMS, RECEIVE_WAP_PUSH, READ_PHONE_STATE, READ_PHONE_NUMBERS,
READ_CONTACTS, POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC,
RECEIVE_BOOT_COMPLETED, VIBRATE, WAKE_LOCK, INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE;
`android.hardware.telephony` not required.

| Component | Exported / guard | Purpose |
| --- | --- | --- |
| `sms.SmsDeliverReceiver` | exported, `BROADCAST_SMS` | `SMS_DELIVER` |
| `mms.WapPushDeliverReceiver` | exported, `BROADCAST_WAP_PUSH`, `application/vnd.wap.mms-message` | `WAP_PUSH_DELIVER` |
| `sms.HeadlessSmsSendService` | exported, `SEND_RESPOND_VIA_MESSAGE`, sms/smsto/mms/mmsto | `RESPOND_VIA_MESSAGE` |
| `sms.SmsStatusReceiver`, `mms.MmsSentReceiver`, `mms.MmsDownloadedReceiver` | not exported | our PendingIntent results |
| `boot.BootReceiver` | not exported | BOOT_COMPLETED, MY_PACKAGE_REPLACED → `OutboxRecoveryWorker` |
| `mms.MmsFileProvider` | not exported, grantUriPermissions, `${applicationId}.dak.mms` | PDU hand-off to MmsService |
| WorkManager `SystemForegroundService` | merged with `foregroundServiceType="dataSync"` | expedited MMS download below API 31 |

The **`ACTION_SENDTO` compose activity** (sms/smsto/mms/mmsto) that the default-SMS role also requires is owned by
`:app`, as are runtime permission requests (only after the role is granted) and notification channels for
messages.

## Wiring notes

- Receivers, the service and workers are created by the system, so they resolve dependencies through
  `TelephonyEntryPoint` (`EntryPointAccessors.fromApplication`) instead of `@AndroidEntryPoint`: this needs only
  `@HiltAndroidApp` in :app. Workers are plain `CoroutineWorker`s (not `@HiltWorker`), so they run with the
  default WorkManager factory or `HiltWorkerFactory` alike.
- Never log message bodies or full addresses from this module.

## Tests

Pure logic is JVM-tested (`src/test`): E.164 normalisation, SMS cost classification, rate limiter, retry backoff, SMS/MMS result codes,
3GPP/3GPP2 delivery status, multipart progress, RESPOND_VIA_MESSAGE parsing, PDU → provider row mapping and
thread-recipient rules, download-state and SIM codecs. Android classes are exercised on device (no Robolectric).
