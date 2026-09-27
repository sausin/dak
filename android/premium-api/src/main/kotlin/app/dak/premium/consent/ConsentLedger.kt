package app.dak.premium.consent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One consent decision, kept on the phone as evidence of what the user agreed to and when (DPDP Act 2023 s.6(10):
 * the Data Fiduciary must be able to prove notice and consent; GDPR Art. 7(1)).
 *
 * @param flow [DataFlow.id].
 * @param granted true for "Allow", false for a decline or a withdrawal.
 * @param disclosureVersion / [disclosureHash] identify the exact disclosure text shown ([Disclosure.textHash]).
 * @param source where it happened, e.g. `settings`, `privacy`, `onboarding`, `restore`.
 * @param language language of the disclosure text shown ([Disclosure.language]); null in records written before
 *   disclosures were translated, which were all English.
 */
@Serializable
data class ConsentRecord(
    val flow: String,
    val granted: Boolean,
    val atMillis: Long,
    val disclosureVersion: Int,
    val disclosureHash: String,
    val source: String,
    val language: String? = null,
)

/** Where the ledger keeps its JSON. The app backs it with a file in no-backup storage; tests use memory. */
interface ConsentStorage {
    fun read(): String?
    fun write(json: String)
}

class InMemoryConsentStorage(private var value: String? = null) : ConsentStorage {
    override fun read(): String? = value
    override fun write(json: String) { value = json }
}

/**
 * Append-only consent records per [DataFlow], and the single answer to "may this flow run now?".
 *
 * A flow is allowed only when its newest record is a grant **for the current disclosure version**: a withdrawal, a
 * decline, or a disclosure whose text has changed since the user agreed all read as "not allowed". Defaults are
 * therefore off, and a corrupt or missing store also reads as "not allowed" (fail closed).
 *
 * A consent is to one version of a disclosure in the language it was shown in: it stays valid when the app language
 * changes later (every translation of a version says the same thing), and the record keeps the language and the hash
 * of the exact text shown. Consents given to an earlier text with the same meaning
 * ([Disclosures.EQUIVALENT_EARLIER]) also count.
 *
 * Thread-safe; writes are synchronous and small (at most [MAX_RECORDS] records).
 *
 * @param disclosures the current disclosure of each flow in the source language; it sets the current version.
 * @param translations the translation of the current disclosure into a language, if any (see [Disclosures.translation]).
 * @param displayLanguage the language the app shows disclosures in now (a BCP 47 tag); used when a caller of
 *   [grant] or [decline] does not name one.
 */
class ConsentLedger(
    private val storage: ConsentStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val disclosures: (DataFlow) -> Disclosure = Disclosures::forFlow,
    private val translations: (DataFlow, String) -> Disclosure? = Disclosures::translation,
    private val displayLanguage: () -> String = { Disclosures.SOURCE_LANGUAGE },
) {
    private val lock = Any()
    private val state = MutableStateFlow(load())

    /** Every record, oldest first. */
    val records: StateFlow<List<ConsentRecord>> = state.asStateFlow()

    fun isGranted(flow: DataFlow): Boolean = isGranted(flow, state.value)

    /** Flows currently allowed. */
    fun granted(): Set<DataFlow> = DataFlow.entries.filterTo(LinkedHashSet()) { isGranted(it) }

    /**
     * The disclosure to show for [flow] to someone using [language]: the translation of the current version when there
     * is one, else the source (English) text. [grant] records exactly this text.
     */
    fun disclosure(flow: DataFlow, language: String = displayLanguage()): Disclosure {
        val source = disclosures(flow)
        if (Disclosures.normalizeLanguage(language) == source.language) return source
        return translations(flow, language)?.takeIf { it.flow == flow && it.version == source.version } ?: source
    }

    /** Records an explicit "Allow" of the current disclosure for [flow], as shown in [language]. */
    fun grant(flow: DataFlow, source: String, language: String = displayLanguage()): ConsentRecord =
        append(flow, granted = true, source = source, language = language)

    /** Records "Not now" on a disclosure; only an audit entry, the flow stays off. */
    fun decline(flow: DataFlow, source: String, language: String = displayLanguage()): ConsentRecord =
        append(flow, granted = false, source = source, language = language)

    /** Withdraws consent; returns null (and writes nothing) when the flow was not allowed anyway. */
    fun withdraw(flow: DataFlow, source: String): ConsentRecord? = synchronized(lock) {
        if (!isGranted(flow)) null else append(flow, granted = false, source = source, language = displayLanguage())
    }

    /** Forgets everything (used by "Delete my Dak data"). */
    fun clear() = synchronized(lock) {
        // Forget in memory first: even if the write fails (and throws), nothing stays allowed for this process.
        state.value = emptyList()
        storage.write(encode(emptyList()))
    }

    /** The records as pretty JSON, for the data export. */
    fun exportJson(): String = prettyJson.encodeToString(ListSerializer(ConsentRecord.serializer()), state.value)

    private fun append(flow: DataFlow, granted: Boolean, source: String, language: String): ConsentRecord = synchronized(lock) {
        val disclosure = disclosure(flow, language)
        val record = ConsentRecord(
            flow = flow.id,
            granted = granted,
            atMillis = clock(),
            disclosureVersion = disclosure.version,
            disclosureHash = disclosure.textHash,
            source = source.take(MAX_SOURCE_CHARS),
            language = disclosure.language,
        )
        val next = trimmed(state.value + record)
        if (granted) {
            // A grant only counts once it is on disk: a failed write throws and leaves the flow off.
            storage.write(encode(next))
            state.value = next
        } else {
            // A withdrawal or decline takes effect at once, even if the write then fails (and throws): fail closed.
            state.value = next
            storage.write(encode(next))
        }
        record
    }

    private fun isGranted(flow: DataFlow, all: List<ConsentRecord>): Boolean {
        val latest = all.lastOrNull { it.flow == flow.id } ?: return false
        if (!latest.granted) return false
        val current = disclosures(flow)
        if (latest.disclosureVersion == current.version) {
            // The text of this version in the language it was shown in (records without one predate translations).
            val shown = disclosure(flow, latest.language ?: Disclosures.SOURCE_LANGUAGE)
            return latest.disclosureHash == shown.textHash
        }
        return Disclosures.isEquivalentEarlier(flow, latest.disclosureVersion, latest.disclosureHash, current.version)
    }

    private fun load(): List<ConsentRecord> {
        val raw = runCatching { storage.read() }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(ConsentRecord.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    private fun encode(list: List<ConsentRecord>): String = json.encodeToString(ListSerializer(ConsentRecord.serializer()), list)

    /**
     * Keeps the newest [MAX_RECORDS], but never drops the newest record of a flow (it decides whether the flow is
     * allowed) so a burst of toggling one flow cannot silently turn another off or on.
     */
    private fun trimmed(list: List<ConsentRecord>): List<ConsentRecord> {
        if (list.size <= MAX_RECORDS) return list
        val keep = HashSet<Int>()
        DataFlow.entries.forEach { flow ->
            val i = list.indexOfLast { it.flow == flow.id }
            if (i >= 0) keep += i
        }
        val budget = MAX_RECORDS - keep.size
        var taken = 0
        for (i in list.indices.reversed()) {
            if (i in keep) continue
            if (taken >= budget) break
            keep += i
            taken++
        }
        return list.filterIndexed { i, _ -> i in keep }
    }

    companion object {
        const val MAX_RECORDS: Int = 500
        private const val MAX_SOURCE_CHARS = 32
        private val json = Json { ignoreUnknownKeys = true }
        private val prettyJson = Json { prettyPrint = true }
    }
}
