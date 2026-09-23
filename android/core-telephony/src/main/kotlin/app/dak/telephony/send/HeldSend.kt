package app.dak.telephony.send

/**
 * A text that was due to go out while Dak was not the default SMS app (a scheduled send, a broadcast copy): kept
 * here, not sent and not lost, until the role comes back ([HeldSendStore], [app.dak.telephony.role.SmsRoleMonitor]).
 * Messages already in the provider (QUEUED SMS, outbox MMS) are held by key instead and need no copy.
 *
 * Pure Kotlin with a self-describing, length-prefixed [encode] form (bodies may contain any character).
 */
data class HeldSend(
    val id: String,
    val addresses: List<String>,
    val body: String,
    val subId: Int,
    val threadId: Long?,
    val requestDeliveryReport: Boolean,
    val heldAtMillis: Long,
) {
    fun encode(): String = buildString {
        field(VERSION)
        field(id)
        field(addresses.size.toString())
        addresses.forEach { field(it) }
        field(body)
        field(subId.toString())
        field(threadId?.toString().orEmpty())
        field(if (requestDeliveryReport) "1" else "0")
        field(heldAtMillis.toString())
    }

    companion object {
        private const val VERSION = "1"

        /** Recipients one held entry may carry (a hostile or corrupt value must not allocate unbounded lists). */
        const val MAX_ADDRESSES = 1_000

        private fun StringBuilder.field(value: String) {
            append(value.length).append(':').append(value)
        }

        /** Decodes [encode]'s output; null for anything malformed (never throws). */
        fun decode(value: String?): HeldSend? {
            if (value == null) return null
            val fields = Fields(value)
            return try {
                if (fields.next() != VERSION) return null
                val id = fields.next() ?: return null
                val count = fields.next()?.toIntOrNull()?.takeIf { it in 0..MAX_ADDRESSES } ?: return null
                val addresses = List(count) { fields.next() ?: return null }
                val body = fields.next() ?: return null
                val subId = fields.next()?.toIntOrNull() ?: return null
                val thread = fields.next() ?: return null
                val report = fields.next() ?: return null
                val heldAt = fields.next()?.toLongOrNull() ?: return null
                if (!fields.atEnd()) return null
                HeldSend(id, addresses, body, subId, thread.toLongOrNull(), report == "1", heldAt)
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    /** Reader for `<length>:<chars>` fields. */
    private class Fields(private val s: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos == s.length

        fun next(): String? {
            val colon = s.indexOf(':', pos)
            if (colon < 0 || colon - pos > 10) return null
            val length = s.substring(pos, colon).toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val end = colon + 1 + length
            if (end > s.length) return null
            pos = end
            return s.substring(colon + 1, end)
        }
    }
}
