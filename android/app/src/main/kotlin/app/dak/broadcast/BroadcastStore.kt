package app.dak.broadcast

import android.content.Context
import app.dak.automations.broadcast.BroadcastCodec
import app.dak.automations.broadcast.BroadcastList
import app.dak.automations.broadcast.BroadcastRecipient
import app.dak.automations.broadcast.BroadcastRecord
import app.dak.automations.broadcast.BroadcastTerms
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** When (and which version of) the "Use broadcasts with care" terms the user accepted. */
data class BroadcastConsent(val version: Int, val acceptedAtMillis: Long)

/**
 * Broadcast lists, sent-broadcast records and the terms acceptance, as JSON in private SharedPreferences (no Room
 * table: the data is small and user-owned). Loaded once per process; every write updates the in-memory flows first.
 * Records are trimmed to the newest [MAX_RECORDS].
 */
@Singleton
class BroadcastStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableLists = MutableStateFlow(BroadcastCodec.decodeLists(prefs.getString(KEY_LISTS, null)))
    private val mutableRecords = MutableStateFlow(BroadcastCodec.decodeRecords(prefs.getString(KEY_RECORDS, null)))

    /** Lists, newest first. */
    val lists: StateFlow<List<BroadcastList>> = mutableLists.asStateFlow()

    /** Sent broadcasts, newest first. */
    val records: StateFlow<List<BroadcastRecord>> = mutableRecords.asStateFlow()

    fun list(id: String): BroadcastList? = mutableLists.value.firstOrNull { it.id == id }

    fun record(id: String): BroadcastRecord? = mutableRecords.value.firstOrNull { it.id == id }

    /** Inserts or replaces [list] (matched by id). */
    @Synchronized
    fun putList(list: BroadcastList) {
        val others = mutableLists.value.filterNot { it.id == list.id }
        val next = (listOf(list) + others).sortedByDescending { it.createdAt }
        mutableLists.value = next
        prefs.edit().putString(KEY_LISTS, BroadcastCodec.encodeLists(next)).apply()
    }

    /** Deletes a list. Its sent records stay (they are the history of what was sent) until trimmed. */
    @Synchronized
    fun deleteList(id: String) {
        val next = mutableLists.value.filterNot { it.id == id }
        mutableLists.value = next
        prefs.edit().putString(KEY_LISTS, BroadcastCodec.encodeLists(next)).apply()
    }

    @Synchronized
    fun putRecord(record: BroadcastRecord) {
        val next = (listOf(record) + mutableRecords.value.filterNot { it.id == record.id })
            .sortedByDescending { it.createdAt }
            .take(MAX_RECORDS)
        saveRecords(next)
    }

    /** Applies [change] to recipient [index] of record [recordId]; no-op when either is missing. */
    @Synchronized
    fun updateRecipient(recordId: String, index: Int, change: (BroadcastRecipient) -> BroadcastRecipient) {
        val current = mutableRecords.value
        val record = current.firstOrNull { it.id == recordId } ?: return
        if (index !in record.recipients.indices) return
        val recipients = record.recipients.toMutableList().also { it[index] = change(it[index]) }
        saveRecords(current.map { if (it.id == recordId) record.copy(recipients = recipients) else it })
    }

    @Synchronized
    fun deleteRecord(id: String) {
        saveRecords(mutableRecords.value.filterNot { it.id == id })
    }

    /** The accepted terms, or null when never accepted. */
    fun consent(): BroadcastConsent? {
        val version = prefs.getInt(KEY_CONSENT_VERSION, -1)
        if (version < 0) return null
        return BroadcastConsent(version, prefs.getLong(KEY_CONSENT_AT, 0L))
    }

    /** True when the terms must be (re-)accepted before using broadcasts. */
    fun needsConsent(): Boolean = BroadcastTerms.needsAcceptance(consent()?.version)

    fun acceptTerms(nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putInt(KEY_CONSENT_VERSION, BroadcastTerms.VERSION)
            .putLong(KEY_CONSENT_AT, nowMillis)
            .apply()
    }

    private fun saveRecords(next: List<BroadcastRecord>) {
        mutableRecords.value = next
        prefs.edit().putString(KEY_RECORDS, BroadcastCodec.encodeRecords(next)).apply()
    }

    private companion object {
        const val PREFS = "dak_broadcasts"
        const val KEY_LISTS = "lists"
        const val KEY_RECORDS = "records"
        const val KEY_CONSENT_VERSION = "terms_version"
        const val KEY_CONSENT_AT = "terms_accepted_at"
        const val MAX_RECORDS = 200
    }
}
