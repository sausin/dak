# :app

The Android application (package `app.dak`): Compose + Material 3 + Hilt + Navigation-Compose (string routes),
flavours `free` / `premium` (only `src/<flavour>/kotlin/app/dak/flavor/TierModule.kt` differs).

## Layout and ownership

| Path | Owner | What |
| --- | --- | --- |
| `DakApplication`, `MainActivity`, manifest, `res/` | shell | Hilt app + WorkManager `Configuration.Provider`, single activity, SENDTO/SEND/VIEW handlers, themes, icons, strings |
| `navigation/` | shell | `Routes` (pinned contract), `DakNavigator`, `DakNavHost`, `IntentRoutes`, `PendingShare` |
| `ui/theme/` | shell | `DakTheme`, `DakColors` tokens, palettes |
| `ui/common/` | shell | shared composables (below) |
| `ui/onboarding/` | shell | onboarding flow + `SmsRole`, `RuntimePermissions`, `BatteryOptimization`, `OemGuidance` |
| `ui/settings/`, `settings/` | shell | Settings UI; DataStore-backed `SettingsStore` |
| `ui/selftest/`, `notifications/` | shell | notification poster, channels, actions receiver, reliability checks, self-test |
| `di/` | shell | app bindings for :core-index seams, contacts, `IndexControl`, DataStore |
| `ui/inbox`, `ui/conversation`, `ui/search`, `ui/bin`, `ui/passbook`, `ui/automations`, `ui/backup`, `ui/blocked`, `automation/`, `backup/` | screens | currently placeholder stubs with the pinned signatures |

## Navigation (for screen authors)

Every screen is `@Composable fun XScreen(navigator: DakNavigator, modifier: Modifier = Modifier)` and gets its own
ViewModel via `hiltViewModel()`. Route arguments are read in the ViewModel from `SavedStateHandle` using the
`Routes.ARG_*` names (`conversationId`, `highlight`, `to`, `body`, `q`, `group`, `focus`, `accountId`); optional
args are `null` when absent. Arguments arrive URL-decoded.

```kotlin
navigator.navigate(Routes.conversation(conversationId, highlight = message.key.toString()))
navigator.navigate(Routes.search(q = "from:hdfc"))
navigator.openConversation(id)           // shorthand
navigator.openSetting(DakSettings.otpAutoDelete.key)  // long-press on a control -> its setting, highlighted
navigator.back()                         // never pops the last screen
navigator.toInbox(); navigator.replaceAll(route)
```

Conversation ids follow :core-index `ConversationIds` (`t:<threadId>`, `m:<mergeKey>`); notifications open
`Routes.conversation("t:<threadId>", highlight = "<kind>:<providerId>")`.

Media shared into Dak (ACTION_SEND with EXTRA_STREAM) cannot ride in the COMPOSE route: inject
`navigation.PendingShare` in the composer ViewModel and call `consume()` once to get the `Uri`s.
`Routes.COMPOSE` carries `to` (comma-separated recipients) and `body` from SENDTO/SEND/`sms:` links.

## Theme tokens

Wrap nothing yourself: `MainActivity` applies `DakTheme(prefs)` live from Settings. Never hard-code colours:

- `MaterialTheme.colorScheme.*` for standard roles (dynamic colour on 12+, designed teal palette below, high-contrast
  variants, AMOLED true-black surfaces).
- `DakTheme.colors` (`DakColors`): `bubbleIncoming/onBubbleIncoming`, `bubbleOutgoing/onBubbleOutgoing`,
  `bubbleFailed/onBubbleFailed`, `otpHighlight/onOtpHighlight`, `category(Category)`, `sim(slotIndex)`,
  `avatar(key)`, `financeCredit`, `financeDebit`, `warning`, `success`, `locked`, `focusHighlight`.
  Category/SIM/warning/success are `TonalColors(container, content, accent)`: `content` on `container` is AA text,
  `accent` is for small marks (dots, icons, chart strokes) on the plain surface.
- `DakTheme.typography.otpCode` (bold monospace, scaled by the OTP display-size setting), `.amount`.
- `DakTheme.state` (`isDark`, `isAmoled`, `isHighContrast`, `isDynamic`).

## Common composables (`ui/common`)

`DakTopAppBar(title, onBack?, scrollBehavior?, actions)`, `SimChip(sim, compact)`, `CategoryChip(category)`,
`categoryLabel(category)` / `categoryLabelRes`, `LockChip()`, `TokenChip(label, colors)`,
`Avatar(name, key, size, photoUri, isBusiness)`, `initialsOf(name)`, `EmptyState(icon, title, body, actionLabel,
onAction)`, `WarningBanner(title, body, actionLabel, onAction)`, `ReliabilityBanner(navigator)` (put it at the top of
the inbox: it renders nothing unless notifications can be delayed), `RelativeTimeFormatter` /
`rememberRelativeTimeFormatter()` / `relativeTime(epochMillis)` (auto-refreshing "5 min", "14:32", "Yesterday"...).

## Reading and writing settings

Inject `app.dak.settings.SettingsStore` (singleton `AppSettingsStore` over DataStore) and use the registry records:

```kotlin
val autoDelete: String = settings.get(DakSettings.otpAutoDelete)          // synchronous, cached
settings.observe(DakSettings.quickActions).collect { ... }                 // Flow
settings.set(DakSettings.roamingWarnings, false)
settings.export() / settings.import(json)                                   // for backup; includes Appearance
```

Values are served from an in-memory snapshot (loaded once from DataStore) and persisted asynchronously in order.
The registry has no Appearance group yet, so the app declares `AppearanceSettings` (`appearance.themeMode`,
`.amoled`, `.contrast`, `.dynamicColor`) with the registry's `SettingDef` type; they are exported/imported with the
rest and shown as the first Settings section. Non-setting app state (onboarding done, banner dismissals) lives in
`AppStateStore`. Tier gating: `def.isLocked(entitlements)`; the Settings screen greys locked rows and opens
`UpgradeSheet(feature)`.

## Notifications

`notifications.MessageNotifier` is contributed to :core-telephony's `Set<IncomingMessageHandler>` (priority 0, before
indexing). It classifies with `:classify`'s `ClassifierPipeline` directly (`NotificationClassifier`), then posts:
OTP (large bold code in a custom view, Copy code / Delete now / Mark read, in-call warning, silent channel for
consumed OTPs, timeout at auto-delete), personal (MessagingStyle, inline RemoteInput reply, Mark read), others by
category channel. Lock-screen visibility follows `notifications.lockScreenPrivacy`.

- When a thread is opened/read, call `MessageNotifier.cancelForThread(threadId)` (inject `MessageNotifier`).
- Channel ids are constants on `NotificationChannels` (`PERSONAL`, `OTP`, `TRANSACTIONS`, `PROMOTIONS`, `OTHER`,
  `SPAM`, `OTP_CONSUMED`, `FAILURES`, `MMS`, `SELF_TEST`, `RELIABILITY`); other modules may post to `FAILURES`/`MMS`.
- `ReliabilityChecker.check()` reports default-SMS role, POST_NOTIFICATIONS, app/channel blocking, battery
  optimisation and background restriction; `Routes.SELF_TEST` fixes them and runs a send-to-self SMS round trip.

## Index and policies

`di/AppBindingsModule` binds :core-index's `BinPolicy` (`SettingsBinPolicy`), `OtpPolicy` (`SettingsOtpPolicy`),
`ContactLookup` (`AndroidContactLookup`, also usable app-wide for names/photos). `di/IndexControl` starts
`IndexSync` once Dak is the default SMS app (app start and right after the role is granted), applies the
onboarding/Settings index schedule and runs "Rebuild index now".
