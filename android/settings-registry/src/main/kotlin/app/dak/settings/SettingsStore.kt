package app.dak.settings

import app.dak.premium.Entitlements
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Abstraction over settings persistence. The Android side backs this with DataStore; a
 * [Map]-like adapter is all an implementation needs to provide (see [PersistenceAdapter]).
 * Values are always read/written through a [SettingDef] so callers get typed access, while the
 * underlying storage only ever sees strings.
 */
interface SettingsStore {
    /** Current value for [def], or its default if unset or unparsable. */
    fun <T> get(def: SettingDef<T>): T

    fun <T> set(def: SettingDef<T>, value: T)

    /** Emits the current value and every subsequent change. */
    fun <T> observe(def: SettingDef<T>): Flow<T>

    /** Resets every setting in [group] back to its default. */
    fun resetGroup(group: SettingsGroup)

    /** Serializes every non-default, non-Action setting to a JSON object string. */
    fun export(): String

    /**
     * Merges settings back in from [json] (as produced by [export]). Unknown keys are ignored;
     * a value that fails [SettingDef.deserialize] for its key's registered type is ignored too.
     */
    fun import(json: String)

    fun locked(def: SettingDef<*>, entitlements: Entitlements): Boolean = def.isLocked(entitlements)
}

/** The minimal persistence contract a platform adapter (e.g. DataStore) implements. */
interface PersistenceAdapter {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
    fun observe(key: String): Flow<String?>
    fun keys(): Set<String>
}

/** Backs [SettingsStore] with a plain in-memory [MutableMap]; used by [InMemorySettingsStore] and tests. */
private class MapPersistenceAdapter(initial: Map<String, String> = emptyMap()) : PersistenceAdapter {
    private val state = MutableStateFlow(initial)

    override fun get(key: String): String? = state.value[key]
    override fun set(key: String, value: String) {
        state.value = state.value + (key to value)
    }
    override fun remove(key: String) {
        state.value = state.value - key
    }
    override fun observe(key: String): Flow<String?> = state.map { it[key] }
    override fun keys(): Set<String> = state.value.keys
}

/**
 * A [SettingsStore] backed by [adapter]. This is the concrete implementation the registry ships;
 * the Android app supplies a [PersistenceAdapter] over DataStore instead of using
 * [InMemorySettingsStore].
 */
class RegistrySettingsStore(private val adapter: PersistenceAdapter) : SettingsStore {

    override fun <T> get(def: SettingDef<T>): T {
        val raw = adapter.get(def.key) ?: return def.default
        return def.deserialize(raw) ?: def.default
    }

    override fun <T> set(def: SettingDef<T>, value: T) {
        adapter.set(def.key, def.serialize(value))
    }

    override fun <T> observe(def: SettingDef<T>): Flow<T> =
        adapter.observe(def.key).map { raw -> raw?.let { def.deserialize(it) } ?: def.default }

    override fun resetGroup(group: SettingsGroup) {
        DakSettings.byGroup(group).forEach { adapter.remove(it.key) }
    }

    override fun export(): String {
        val entries = DakSettings.all
            .filter { it.control != ControlType.Action }
            .mapNotNull { def -> adapter.get(def.key)?.let { def.key to JsonPrimitive(it) } }
        return Json.encodeToString(JsonObject.serializer(), JsonObject(entries.toMap()))
    }

    override fun import(json: String) {
        val obj = try {
            Json.parseToJsonElement(json) as? JsonObject ?: return
        } catch (e: Exception) {
            return
        }
        for ((key, element) in obj) {
            val def = DakSettings.byKey(key) ?: continue
            val primitive = element as? kotlinx.serialization.json.JsonPrimitive ?: continue
            val raw = primitive.content
            if (def.deserialize(raw) == null) continue // type-check: only import values that parse
            adapter.set(key, raw)
        }
    }
}

/** In-memory [SettingsStore] for tests and previews; optionally seeded with raw string values. */
class InMemorySettingsStore(initial: Map<String, String> = emptyMap()) : SettingsStore by RegistrySettingsStore(MapPersistenceAdapter(initial))
