# :settings-registry

Pure-Kotlin (JVM) module implementing the declarative Settings registry described in the build
plan's "Settings design" section: one record per setting drives the UI, the search index, deep
links, per-group reset and export/import. No Android dependency; depends on `:core-model` and
`:premium-api`.

## Public API

### `SettingDef<T>` / supporting types (`SettingDef.kt`)

```kotlin
data class SettingDef<T>(
    val key: String,
    val group: SettingsGroup,
    val title: String,
    val summary: String,
    val control: ControlType,
    val default: T,
    val keywords: List<String> = emptyList(),
    val tier: SettingTier = SettingTier.Free,
    val advanced: Boolean = false,
    val visible: (DeviceContext) -> Boolean = { true },
    val serialize: (T) -> String,
    val deserialize: (String) -> T?,
) {
    fun isLocked(entitlements: Entitlements): Boolean
}
```

- `SettingsGroup` — the 7 groups, in spec table order: `NOTIFICATIONS`, `CATEGORIES_SPAM`,
  `FINANCE`, `SIMS_SENDING`, `BACKUP_DATA`, `AUTOMATIONS`, `TRANSLATION`.
- `SettingTier` — `Free` or `Premium(feature: app.dak.premium.Feature)`.
- `ControlType` — `Toggle`, `SingleChoice(options: List<ChoiceOption>)`,
  `Slider(range: IntRange, step: Int)`, `Text`, `Action` (navigation/action row with no value).
- `DeviceContext(simCount, hasMmsData, apiLevel, hasBiometric)` — read by `visible` to hide rows
  that cannot apply (e.g. SIM 2 rows when `simCount == 1`, exact-alarm permission below API 31).

### `DakSettings` (object)

Every setting from the spec's "Proposed groups" table plus the feature sections it references
(Notifications, OTP lifecycle and recycle bin, Finance ledger, Roaming and language, Relay rules,
Translation), as individually-named `val`s (e.g. `DakSettings.otpAutoDelete`,
`DakSettings.otpBinRetention`) plus:

```kotlin
val all: List<SettingDef<*>>
fun byKey(key: String): SettingDef<*>?
fun byGroup(group: SettingsGroup): List<SettingDef<*>>
```

The one premium seam called out explicitly in "Free vs. premium" — adjustable OTP bin retention —
is `otpBinRetention`, tiered `Premium(Feature.ADJUSTABLE_OTP_BIN_RETENTION)`; free keeps the fixed
1-day default. Automation `webhooks`/`sendApiKeys`/`auditLog` and all `translation.*` rows (except
`freeTasterPack`) are `Premium`-tiered against `Feature.WEBHOOKS` / `Feature.SEND_API` /
`Feature.TRANSLATION`.

`SwipeActions` holds the value constants (`ARCHIVE`, `DELETE`, `MARK_READ`, `PIN`, `NONE`) and choice options of the
inbox swipe rows `swipeRight` / `swipeLeft`; `inboxOtpCopy` and `enterToSend` are plain toggles.

### `SettingsSearch` (object)

```kotlin
fun search(
    query: String,
    deviceContext: DeviceContext,
    entitlements: Entitlements,
    changedKeys: Set<String> = emptySet(),
    settings: List<SettingDef<*>> = DakSettings.all,
    text: SettingsText = SettingsText.English,
): List<SettingsSearchResult>

data class SettingsSearchResult(val def: SettingDef<*>, val group: SettingsGroup, val key: String, val locked: Boolean)
```

Tolerant prefix + token-based fuzzy (Damerau-Levenshtein, distance ≤ 2) matching over title,
summary and `keywords` (so "one time password" finds the OTP rows). Rows a caller marks as
user-changed (`changedKeys`) rank above equally-scored unchanged rows. Rows hidden for the given
`DeviceContext` are excluded entirely; premium rows are still returned with `locked = true` when
`entitlements` does not grant their `Feature`, so they keep acting as the free tier's sales page.
`text` is what rows are shown with (the app passes its string-resource labels): search matches it *and* the
English title, summary and keywords, so English words work in every app language.

### Labels: `SettingsStringKeys` / `SettingsText` (`SettingsStringKeys.kt`)

Titles, summaries, option labels and group names are English literals here; the app shows Android string resources
whose names `SettingsStringKeys` derives from the setting key (`setting_<key>_title`, `..._summary`,
`..._opt_<value>`, `settings_group_<group>`, or a `ChoiceOption.labelKey` for shared option lists).
`SettingsStringResourcesTest` checks `app/src/main/res/values/strings_settings.xml` has every name with the same
English text. Details: [`docs/i18n.md`](../../docs/i18n.md).

### `SettingsStore` / `InMemorySettingsStore` (`SettingsStore.kt`)

```kotlin
interface SettingsStore {
    fun <T> get(def: SettingDef<T>): T
    fun <T> set(def: SettingDef<T>, value: T)
    fun <T> observe(def: SettingDef<T>): Flow<T>
    fun resetGroup(group: SettingsGroup)
    fun export(): String   // JSON
    fun import(json: String)
    fun locked(def: SettingDef<*>, entitlements: Entitlements): Boolean
}

interface PersistenceAdapter { // implement this over DataStore on Android
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
    fun observe(key: String): Flow<String?>
    fun keys(): Set<String>
}

class RegistrySettingsStore(adapter: PersistenceAdapter) : SettingsStore
class InMemorySettingsStore(initial: Map<String, String> = emptyMap()) : SettingsStore
```

`export()` emits a JSON object of every *overridden, non-Action* setting (`{"key": "value"}`), for
inclusion in a backup. `import(json)` merges values back in: unknown keys are ignored, and a value
that fails its setting's `deserialize` (wrong type/shape) is ignored rather than corrupting the
store. `InMemorySettingsStore` is for tests/previews; the Android app implements
`PersistenceAdapter` over DataStore and constructs `RegistrySettingsStore(adapter)`.

## Notes / follow-ups for other modules

- The Android app supplies the real `PersistenceAdapter` (DataStore-backed) and the real
  `DeviceContext` (from `SubscriptionManager`, `Build.VERSION.SDK_INT`, `BiometricManager`, ...).
- `Entitlements`/`Feature` come from `:premium-api` and are not redefined here.
- `SettingDef.control` is UI-only metadata (this module renders nothing); the Compose settings
  screen reads `control` to pick a composable per row.
