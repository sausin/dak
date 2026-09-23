# :automations

Pure-Kotlin JVM module (`app.dak.automations`) implementing Dak's rule-based automation engine: the
rule AST, its JSON codec, evaluation, action execution/gating, safety checks, the audit log, the send
rate limiter and scheduled-send recurrence math. No Android imports; `:app` supplies the real
`SmsForwarder`/`Notifier`/etc. and the coroutine scope that drives it.

Depends on `:core-model` (shared types: `Message`, `Category`, `ExtractedTransaction`, ...) and
`:premium-api` (`Entitlements`, `PremiumGateway`, `Feature`).

## Rule AST (`app.dak.automations.rule`)

```kotlin
data class Rule(
    val id: String, val name: String, val enabled: Boolean = true,
    val trigger: Trigger, val conditions: Condition = Condition.All(emptyList()),
    val actions: List<ActionSpec>, val createdAt: Long, val updatedAt: Long,
    val schemaVersion: Int = CURRENT_RULE_SCHEMA_VERSION,
)
```

- **Trigger**: `MessageReceived(predicates: Condition?)`, `Schedule(spec: ScheduleSpec)`,
  `Keyword(keyword, ignoreCase)`. `ScheduleSpec` is `OneShot(atMillis)` or
  `Recurring(recurrence: Recurrence)`; `Recurrence` is `Daily`/`Weekly`/`Monthly` (each carrying
  `hour`, `minute`, `zoneId`; `Weekly` adds ISO `dayOfWeek` 1..7; `Monthly` adds `dayOfMonth`, clamped
  to the month's length — see `nextOccurrence`).
- **Condition** tree: `All`/`Any`/`Not` plus leaves `SenderIs`, `SenderMatches`, `CategoryIs`, `SimIs`,
  `BodyContains`, `BodyMatches`, `AmountAtLeast`/`AmountAtMost`, `TimeWindow` (minute-of-day, UTC),
  `DirectionIs`, `HasOtp`, `ActiveBetween(startMillis, endMillis?)` (inclusive window on the message time;
  `null` end = "until I stop") and `SenderInGroups(mergeKeys, conversationIds, addresses)` (the message's merge
  group, display conversation `t:`/`m:` id, or raw sender — phone numbers compare on the last 10 digits).
- `Rule.meta: Map<String, String>` — free-form UI metadata, never evaluated (e.g. `kind = forwarding`). Additive:
  old JSON decodes to an empty map; `schemaVersion` stays 1.
- Validity helpers (`RuleValidity.kt`): `Rule.activeWindow()` (a top-level `ActiveBetween` conjunct),
  `Rule.isExpired(now)`, `Rule.isNotStartedYet(now)`. `:app` disables expired rules lazily.
- **ActionSpec** — free: `Label`, `Archive`, `Notify`, `ForwardSms`, `ScheduleReply`, `LaunchIntent`,
  `Delete`. Premium (gated in `ActionRegistry`): `Webhook`, `RelayToWebClient`, `RelayRule` (with
  `RelayChannel`: `SMS` / `WHATSAPP_ONE_TAP` / `WEBHOOK`).

Every polymorphic node carries an explicit `"type"` JSON discriminator (see `Serializers.kt`) and
decodes an unrecognised `type` as that node's `Unknown(type, raw: JsonObject)` variant instead of
failing — a rule using a trigger/condition/action type from a newer app build still loads (skipping
just the node it doesn't understand), and re-encodes byte-identical for that node. `RuleCodec`'s `Json`
also sets `ignoreUnknownKeys = true`, so an extra field on a *known* node is dropped, not fatal.

```kotlin
RuleCodec.encode(rules: List<Rule>): String
RuleCodec.decode(json: String): List<Rule>
```

## Evaluation (`RuleEngine`, `MessageEvent`, `PlannedAction`)

`MessageEvent` is the read-only slice of an indexed message the engine looks at (address, merge key,
body, `dateMillis`, `subId`/`slot`, `Category`, optional `OtpInfo`/`ExtractedTransaction`).

```kotlin
RuleEngine.evaluate(event: MessageEvent, rules: List<Rule>): List<PlannedAction>
```

Pure and deterministic: enabled rules only, in list order; `Trigger.Schedule` never matches a message
event (it is driven by a separate scheduler outside this module); regex conditions are guarded (bounded
input length via `RuleEngine.MAX_REGEX_INPUT_LENGTH`, `PatternSyntaxException` caught → non-match, never
thrown, and patterns failing `safety.RegexSafety` (nested/ambiguous repetition, backreferences, > 500 chars:
exponential backtracking on attacker-chosen bodies) never run; the validator reports them as `InvalidRegex`). Each matching rule contributes one `PlannedAction` per its `actions`, with
`requiresBiometricConfirmation` set when the action forwards/relays *and* `rule.conditions` can match an
OTP (see `conditionsCanMatchOtp` — true for a positive `HasOtp`, `CategoryIs(OTP)`, or no positive category
restriction at all; false when `excludesOtp`, i.e. the top-level conjuncts contain `otpExclusion()` =
`Not(HasOtp), Not(CategoryIs(OTP))`). Conditions under `Not` never count as positive restrictions.

Loop guard (`safety.ForwardLoopGuard`, applied inside `evaluate`): a `ForwardSms` (or SMS `RelayRule`) is dropped
when the message comes *from* its recipient, or when the body starts with the literal prefix of the action's
template (`"Fwd from"` for the forwarding default), so A→B→A chains stop. `safety.Addresses.same(a, b)` is the
shared address comparison (last 10 digits for phone numbers).

## Forwarding rules (`app.dak.automations.forwarding`)

```kotlin
data class ForwardingSpec(id, name, enabled, sources: List<ForwardingSource>, categories: Set<Category>, keyword,
    recipients: List<ForwardingRecipient>, subId: Int?, startMillis, endMillis: Long?, template = DEFAULT_TEMPLATE,
    includeOtp = false, createdAt) {
    val isComplete: Boolean
    fun status(nowMillis): ForwardingStatus          // ACTIVE / SCHEDULED / PAUSED / ENDED (ended wins)
    fun toRule(nowMillis, newId: () -> String): Rule?
    companion object { DEFAULT_TEMPLATE = "Fwd from {sender}: {body}"; fun isForwarding(rule); fun fromRule(rule): ForwardingSpec? }
}
data class ForwardingSource(conversationId, name, mergeKey?, addresses)   // @Serializable
data class ForwardingRecipient(number, name?) { val label }
```

The time-boxed "forward these channels to my CA until 31 Jul" model, stored as an ordinary rule
(`meta["kind"] = "forwarding"`): `MessageReceived` + `All(ActiveBetween, SenderInGroups, [Any(CategoryIs…)],
[BodyContains], [otpExclusion()])` + one `ForwardSms` per recipient. OTPs are excluded unless `includeOtp`
(then the rule needs the usual biometric confirmation). `RuleValidator` reports a recipient that is also a source
address as `ForwardingLoop`.

## Birthday wishes (`app.dak.automations.birthdays`)

- `BirthdayDates.parse(raw): ContactDate?` — robust parsing of contact event dates (`1990-05-17`, `--05-17`,
  `--0517`, `19900517`, ISO timestamps, `17/05/1990` / `05/17/1990` (day-first unless impossible), `17.05.1990`,
  `May 17, 1990`, `17 May`, epoch millis); placeholder years (0000, 1604) dropped; never throws.
- `ContactDate(month, day, year?)`: `inYear(y)` (Feb 29 → Feb 28 in non-leap years), `ageIn(y)`.
- `BirthdayDates.nextOccurrence(date, afterMillis, sendAt: LocalTime, zone, skipYears): ZonedDateTime?`,
  `daysUntil(date, today)`.
- `WishTemplates.render(template, name, firstName?, age?)` with `{firstName}`, `{name}`, `{age}`;
  `birthdayDefaults` / `anniversaryDefaults` (English + Hindi).
- `WishTag(ask, contactId, kind: OccasionKind, year)` — `encode()`/`decode()` of the scheduled send's `ruleId`
  (`birthday:<ask|auto>:<contactId>:<kind>:<year>`), `dedupeKey` for "never twice a year".

## Templates (`TemplateRenderer`)

`TemplateRenderer.render(template: String, event: MessageEvent, zoneId: String = "UTC"): String` fills
`{amount}` `{payer}` `{merchant}` `{time}` `{sender}` `{body}` `{otp}` `{sim}`; a missing value renders
as empty, an unrecognised `{placeholder}` passes through unchanged, and `"{body}"` (the default template
on every forwarding action) is the raw message.

## Executing actions (`app.dak.automations.action`)

```kotlin
interface ActionRegistry { suspend fun execute(planned: PlannedAction, event: MessageEvent, context: ActionContext): ActionResult }
class DefaultActionRegistry : ActionRegistry
```

`ActionResult` is `Success` / `Failed(reason)` / `Locked(feature: Feature)`. `DefaultActionRegistry`
checks `ActionContext.entitlements` for the three premium action types before running them (never
reaching the executor), delegates every action to one of the small executor interfaces `:app`
implements — `SmsForwarder`, `Notifier`, `Labeler`, `Archiver`, `Binner`, `IntentLauncher`,
`ReplyScheduler` — and, for `Webhook`, builds a `PremiumGateway.WebhookRequest` and calls
`premiumGateway.sendWebhook`. Every forwarding/relay action's outcome is recorded through
`ActionContext.auditSink` (see below).

`WebhookSigner.sign(body, secret, timestampMillis): String` produces a Stripe-style header value
`t=<seconds>,v1=<hex HMAC-SHA256 of "seconds.body">`; `WebhookSigner.verify(body, secret, header)` checks
one in constant time.

## Safety (`app.dak.automations.safety`)

- `RuleValidator.validate(rule, entitlements, ownAddresses = emptySet()): List<ValidationIssue>` — pure;
  flags `InvalidRegex`, `MissingRecipient`, `PremiumActionInFreeTier`, `ForwardingLoop` (forwarding to
  one of the user's own SIM numbers) and `NoActions`.
- `RecipientSnapshot` + `shouldPause(snapshot, currentNumber): Boolean` — pure decision for pausing a
  relay rule when its recipient's contact number has changed (or can no longer be resolved).

## Audit log (`app.dak.automations.audit`)

`AuditLogEntry(ruleId, ruleName, actionType, recipient, channel, messageKey, atMillis, outcome)` +
`interface AuditSink { suspend fun record(entry: AuditLogEntry) }`.

## Undo (`app.dak.automations.undo`)

`UndoToken(messageKey, actionType, description, expiresAtMillis)` — what `Archiver`/`Binner` return, for
the few-seconds "undo" affordance.

## Send rate limiting and scheduled sends (`app.dak.automations.ratelimit`)

```kotlin
class SendRateLimiter(maxSends: Int = 30, windowMillis: Long = 30 * 60 * 1000L) {
    fun tryAcquire(now: Long): Boolean
    companion object {
        fun planSends(count: Int, now: Long, history: List<Long> = emptyList(), maxSends: Int = 30, windowMillis: Long = 30 * 60 * 1000L): List<Long>
    }
}
```

`planSends` is the pure planner: spreads `count` new sends so no rolling `windowMillis` window (across
`history` + the new plan) ever holds more than `maxSends`. `tryAcquire` is the small stateful wrapper for
inline use.

```kotlin
data class ScheduledSend(id, addresses, body, subId, atMillis, recurrence: Recurrence? = null, state: ScheduledSendState = PENDING)
fun nextOccurrence(recurrence: Recurrence, after: Long): Long
```

`nextOccurrence` is pure `java.time` math: the next instant strictly after `after`, in the recurrence's
own `zoneId`, resolving DST gaps/overlaps the way `ZonedDateTime.of` always does, and clamping
`Monthly.dayOfMonth` to the shorter month's last day (31 → Feb 28/29, Apr/Jun/Sep/Nov 30).

## Presets (`app.dak.automations.presets`)

`Presets.all(now: Long): List<Rule>` — a few built-in example rules (`archiveOldPromotions`,
`labelAmazonDeliveries`, `otpBigNotification`), ordinary `Rule`s exportable/importable via `RuleCodec`
like anything else; `:app` can offer them as one-tap starting points. `archiveOldPromotions(now, zoneId =
ZoneId.systemDefault().id)` runs at 03:00 in the device's time zone.

## Known limitations / left for `:app` or a later pass

- `TemplateRenderer`'s `{amount}` formatting assumes a 2-decimal minor-unit exponent (no dependency on
  `:finance`'s `CurrencyTable`, per this module's allowed dependencies); a 0-decimal currency (JPY) would
  render with two decimal places anyway. `:finance`/`:app` can post-process if that matters before v1.
- The rule AST does not yet distinguish "payer" from "merchant" as separate extracted fields — both
  template placeholders currently read `ExtractedTransaction.merchant`.
- `Condition.TimeWindow` compares minute-of-day in UTC (this module has no ambient timezone); callers
  needing device-local time windows should convert the threshold before storing the rule, or this can
  grow a `zoneId` field in a later schema version (additive, so no `schemaVersion` bump needed if it
  defaults to UTC when absent).
- `RuleEngine` does not itself drive `Trigger.Schedule` rules or `ScheduledSend`s — that needs a
  Room-backed store plus `AlarmManager`/`WorkManager` wiring in `:app`, using `nextOccurrence` and
  `SendRateLimiter.planSends` from here.
