package app.dak.automations.broadcast

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * JSON for broadcast lists and records (the app keeps them in private storage). Lenient on read: unknown keys are
 * ignored and a corrupt blob decodes to an empty list rather than throwing.
 */
public object BroadcastCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listsSerializer = ListSerializer(BroadcastList.serializer())
    private val recordsSerializer = ListSerializer(BroadcastRecord.serializer())

    public fun encodeLists(lists: List<BroadcastList>): String = json.encodeToString(listsSerializer, lists)

    public fun decodeLists(text: String?): List<BroadcastList> =
        text?.let { runCatching { json.decodeFromString(listsSerializer, it) }.getOrNull() }.orEmpty()

    public fun encodeRecords(records: List<BroadcastRecord>): String = json.encodeToString(recordsSerializer, records)

    public fun decodeRecords(text: String?): List<BroadcastRecord> =
        text?.let { runCatching { json.decodeFromString(recordsSerializer, it) }.getOrNull() }.orEmpty()
}

/** Pure editing helpers for a list's members. */
public object BroadcastLists {
    /**
     * Adds [additions] to [members], skipping numbers already present (see [PhoneKey.same]) and stopping at
     * [max] members. Returns the new member list and how many additions were dropped for being over the cap.
     */
    public fun addMembers(
        members: List<Member>,
        additions: List<Member>,
        max: Int = BroadcastLimits.HARD_MAX_RECIPIENTS,
    ): Pair<List<Member>, Int> {
        val out = members.toMutableList()
        var overCap = 0
        for (m in additions) {
            if (m.address.isBlank() || out.any { PhoneKey.same(it.address, m.address) }) continue
            if (out.size >= max) {
                overCap++
                continue
            }
            out += m.copy(address = m.address.trim())
        }
        return out to overCap
    }

    /** Splits typed text ("98xxxx, +91 97xxxx; 080...") into raw-number members. */
    public fun parseNumbers(text: String): List<Member> =
        text.split(',', ';', '\n')
            .map { it.trim() }
            .filter { part -> part.count { it.isDigit() } >= 3 }
            .map { Member(contactId = null, displayName = "", address = it) }
}
