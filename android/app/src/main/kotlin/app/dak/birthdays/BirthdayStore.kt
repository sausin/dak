package app.dak.birthdays

import android.content.Context
import app.dak.automations.birthdays.ContactDate
import app.dak.automations.birthdays.OccasionKind
import app.dak.automations.birthdays.WishTemplates
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** "Ask me first" posts a notification with Send / Edit / Skip on the day; "Send automatically" just sends. */
enum class WishMode { ASK, AUTO }

/** Global birthday-wish settings. Nothing is ever sent while [enabled] is false (the default). */
@Serializable
data class BirthdaySettings(
    val enabled: Boolean = false,
    val includeAnniversaries: Boolean = false,
    /** Other dates saved on contacts ("Other" or a custom label such as "Graduation"). */
    val includeOtherDates: Boolean = false,
    val hour: Int = 9,
    val minute: Int = 0,
    val mode: String = WishMode.ASK.name,
    /** Sending SIM, or null for the default SMS SIM. */
    val subId: Int? = null,
    val birthdayTemplate: String = WishTemplates.DEFAULT_BIRTHDAY,
    val anniversaryTemplate: String = WishTemplates.DEFAULT_ANNIVERSARY,
    val otherTemplate: String = WishTemplates.DEFAULT_OTHER,
) {
    val wishMode: WishMode get() = WishMode.entries.firstOrNull { it.name == mode } ?: WishMode.ASK

    fun templateFor(kind: OccasionKind): String = when (kind) {
        OccasionKind.BIRTHDAY -> birthdayTemplate
        OccasionKind.ANNIVERSARY -> anniversaryTemplate
        OccasionKind.OTHER -> otherTemplate
    }

    /** Whether occasions of [kind] are shown and wished (birthdays always are). */
    fun includes(kind: OccasionKind): Boolean = when (kind) {
        OccasionKind.BIRTHDAY -> true
        OccasionKind.ANNIVERSARY -> includeAnniversaries
        OccasionKind.OTHER -> includeOtherDates
    }
}

/**
 * Per-contact choice for one occasion. [enabled] = "auto-send wish" for this contact (default off: the screen only
 * suggests). The contact snapshot (name, date) is refreshed from Contacts on every sync; the scheduled-send fields
 * track the one pending send for the next occurrence.
 */
@Serializable
data class OccasionConfig(
    val contactId: Long,
    val kind: String,
    val enabled: Boolean = false,
    val number: String? = null,
    /** Per-contact template, or null for the global one. */
    val template: String? = null,
    val name: String = "",
    val firstName: String? = null,
    val month: Int = 1,
    val day: Int = 1,
    val year: Int? = null,
    /** The date's own label from Contacts (other dates only), e.g. "Graduation". */
    val label: String? = null,
    val scheduledSendId: Long? = null,
    val scheduledAtMillis: Long? = null,
) {
    val occasionKind: OccasionKind get() = OccasionKind.entries.firstOrNull { it.name == kind } ?: OccasionKind.BIRTHDAY
    val key: String get() = keyOf(contactId, occasionKind)
    val date: ContactDate? get() = runCatching { ContactDate(month, day, year) }.getOrNull()

    companion object {
        fun keyOf(contactId: Long, kind: OccasionKind): String = "$contactId:${kind.name}"
    }
}

/** Everything the store holds, as one immutable snapshot for the UI. */
data class BirthdayState(
    val settings: BirthdaySettings = BirthdaySettings(),
    val configs: Map<String, OccasionConfig> = emptyMap(),
)

/**
 * Birthday-wish settings, per-contact choices and the "already wished" ledger (dedupe key contactId + kind + year —
 * for birthdays that is contactId + year), in private SharedPreferences. Synchronous and small; call off the main
 * thread where convenient (SharedPreferences caches after the first read).
 */
@Singleton
class BirthdayStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableState = MutableStateFlow(load())

    val state: StateFlow<BirthdayState> = mutableState.asStateFlow()

    val settings: BirthdaySettings get() = mutableState.value.settings

    fun config(contactId: Long, kind: OccasionKind): OccasionConfig? =
        mutableState.value.configs[OccasionConfig.keyOf(contactId, kind)]

    @Synchronized
    fun updateSettings(change: (BirthdaySettings) -> BirthdaySettings) {
        val next = change(mutableState.value.settings)
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(BirthdaySettings.serializer(), next)).apply()
        mutableState.value = mutableState.value.copy(settings = next)
    }

    /** Applies [change] to the config for ([contactId], [kind]), creating it from [initial] when missing. */
    @Synchronized
    fun updateConfig(contactId: Long, kind: OccasionKind, initial: () -> OccasionConfig, change: (OccasionConfig) -> OccasionConfig) {
        val key = OccasionConfig.keyOf(contactId, kind)
        val current = mutableState.value.configs[key] ?: initial()
        putConfigs(mutableState.value.configs + (key to change(current)))
    }

    @Synchronized
    fun replaceConfig(config: OccasionConfig) {
        putConfigs(mutableState.value.configs + (config.key to config))
    }

    fun isWished(dedupeKey: String): Boolean = prefs.getStringSet(KEY_WISHED, emptySet()).orEmpty().contains(dedupeKey)

    /** Years already wished (sent or skipped) for this contact and occasion. */
    fun wishedYears(contactId: Long, kind: OccasionKind): Set<Int> {
        val prefix = "$contactId:${kind.name}:"
        return prefs.getStringSet(KEY_WISHED, emptySet()).orEmpty()
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
            .toSet()
    }

    /** Records a wish as done for its year; keeps only the last few years so the set stays small. */
    @Synchronized
    fun markWished(dedupeKey: String, currentYear: Int) {
        val kept = prefs.getStringSet(KEY_WISHED, emptySet()).orEmpty()
            .filter { key -> key.substringAfterLast(':').toIntOrNull()?.let { it >= currentYear - 2 } ?: false }
            .toMutableSet()
        kept += dedupeKey
        prefs.edit().putStringSet(KEY_WISHED, kept).apply()
    }

    private fun putConfigs(configs: Map<String, OccasionConfig>) {
        prefs.edit().putString(KEY_CONFIGS, json.encodeToString(ListSerializer(OccasionConfig.serializer()), configs.values.toList())).apply()
        mutableState.value = mutableState.value.copy(configs = configs)
    }

    private fun load(): BirthdayState {
        val settings = prefs.getString(KEY_SETTINGS, null)
            ?.let { runCatching { json.decodeFromString(BirthdaySettings.serializer(), it) }.getOrNull() }
            ?: BirthdaySettings()
        val configs = prefs.getString(KEY_CONFIGS, null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(OccasionConfig.serializer()), it) }.getOrNull() }
            .orEmpty()
            .associateBy { it.key }
        return BirthdayState(settings, configs)
    }

    private companion object {
        const val PREFS = "dak_birthdays"
        const val KEY_SETTINGS = "settings"
        const val KEY_CONFIGS = "configs"
        const val KEY_WISHED = "wished"
    }
}
