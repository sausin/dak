package app.dak.telephony.send

import android.content.Context
import app.dak.core.model.MessageKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends held while Dak is not the default SMS app (see [app.dak.telephony.role.SmsRoleMonitor]): provider rows
 * (QUEUED SMS, outbox MMS) by key, and texts that never reached the provider as [HeldSend] copies. SharedPreferences
 * with `commit()`: callers are off the main thread and a hold must survive the process dying right after.
 */
@Singleton
class HeldSendStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("dak_held_sends", Context.MODE_PRIVATE)

    @Synchronized
    fun holdRow(key: MessageKey) {
        prefs.edit().putBoolean(ROW_PREFIX + key, true).commit()
    }

    /** Keeps a copy of a send that could not be written to the provider; false when the store is full. */
    @Synchronized
    fun holdNew(addresses: List<String>, body: String, subId: Int, threadId: Long?, requestDeliveryReport: Boolean, nowMillis: Long): Boolean {
        if (prefs.all.keys.count { it.startsWith(NEW_PREFIX) } >= MAX_NEW) return false
        val entry = HeldSend(UUID.randomUUID().toString(), addresses, body, subId, threadId, requestDeliveryReport, nowMillis)
        return prefs.edit().putString(NEW_PREFIX + entry.id, entry.encode()).commit()
    }

    @Synchronized
    fun rows(): List<MessageKey> =
        prefs.all.keys.filter { it.startsWith(ROW_PREFIX) }.mapNotNull { MessageKey.parse(it.removePrefix(ROW_PREFIX)) }

    /** Held copies, oldest first. Unreadable entries are dropped. */
    @Synchronized
    fun news(): List<HeldSend> {
        val out = ArrayList<HeldSend>()
        val broken = ArrayList<String>()
        for ((k, v) in prefs.all) {
            if (!k.startsWith(NEW_PREFIX)) continue
            HeldSend.decode(v as? String)?.let { out += it } ?: broken.add(k)
        }
        if (broken.isNotEmpty()) prefs.edit().apply { broken.forEach { remove(it) } }.commit()
        return out.sortedBy { it.heldAtMillis }
    }

    @Synchronized
    fun removeRow(key: MessageKey) {
        prefs.edit().remove(ROW_PREFIX + key).commit()
    }

    @Synchronized
    fun removeNew(id: String) {
        prefs.edit().remove(NEW_PREFIX + id).commit()
    }

    fun isEmpty(): Boolean = prefs.all.isEmpty()

    private companion object {
        const val ROW_PREFIX = "row:"
        const val NEW_PREFIX = "new:"

        /** Far above any real backlog (scheduled sends are capped per day); keeps a corrupt loop from filling disk. */
        const val MAX_NEW = 2_000
    }
}
