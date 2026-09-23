package app.dak.settings

import app.dak.ui.theme.AppearancePrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The app's [SettingsStore]: the registry's [RegistrySettingsStore] over DataStore, extended with the app-declared
 * [AppearanceSettings] rows (included in [export]/[import], reset with [resetAppearance]).
 * Bound as the app-wide [SettingsStore] singleton in `di/SettingsModule`.
 */
class AppSettingsStore(private val adapter: DataStorePersistenceAdapter) : SettingsStore {
    private val registry = RegistrySettingsStore(adapter)

    override fun <T> get(def: SettingDef<T>): T = registry.get(def)
    override fun <T> set(def: SettingDef<T>, value: T) = registry.set(def, value)
    override fun <T> observe(def: SettingDef<T>): Flow<T> = registry.observe(def)
    override fun resetGroup(group: SettingsGroup) = registry.resetGroup(group)

    /** Raw stored values keyed by setting key; emits on every change. Rows not present use their defaults. */
    val snapshot: Flow<Map<String, String>> = adapter.observeAll()

    /** Resets the Appearance section. */
    fun resetAppearance() {
        AppearanceSettings.all.forEach { adapter.remove(it.key) }
    }

    /** Keys whose stored value differs from the row's default (for search ranking and "changed" hints). */
    val changedKeys: Flow<Set<String>> = adapter.observeAll().map { raw ->
        raw.filter { (key, value) ->
            val def = DakSettings.byKey(key) ?: AppearanceSettings.byKey(key) ?: return@filter false
            value != serializedDefault(def)
        }.keys
    }

    /** Live theme preferences; MainActivity feeds these into `DakTheme` so changes apply without restart. */
    val appearance: Flow<AppearancePrefs> =
        adapter.observeAll().map { AppearanceSettings.prefsFrom(this) }.distinctUntilChanged()

    /** Current theme preferences (synchronous; loads the snapshot on first call). */
    fun appearanceNow(): AppearancePrefs = AppearanceSettings.prefsFrom(this)

    override fun export(): String {
        val base = runCatching { Json.parseToJsonElement(registry.export()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val extra = AppearanceSettings.all.mapNotNull { def -> adapter.get(def.key)?.let { def.key to JsonPrimitive(it) } }
        return Json.encodeToString(JsonObject.serializer(), JsonObject(base + extra))
    }

    override fun import(json: String) {
        registry.import(json)
        val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return
        for (def in AppearanceSettings.all) {
            val raw = (obj[def.key] as? JsonPrimitive)?.content ?: continue
            if (def.deserialize(raw) != null) adapter.set(def.key, raw)
        }
    }

    /** Raw stored string of every row, for display of current values without knowing each type. */
    fun rawValue(def: SettingDef<*>): String = adapter.get(def.key) ?: serializedDefault(def)

    /** Writes a raw string value after validating it against the row's type. */
    fun setRaw(def: SettingDef<*>, raw: String): Boolean {
        if (def.deserialize(raw) == null) return false
        adapter.set(def.key, raw)
        return true
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun serializedDefault(def: SettingDef<*>): String = (def as SettingDef<Any?>).let { it.serialize(it.default) }
    }
}
