package app.dak.telephony.sms

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale

/**
 * Write-ahead journal for incoming SMS (pure JVM, file based, thread-safe).
 *
 * The platform deletes its raw copy of an SMS as soon as the SMS_DELIVER broadcast finishes, so a provider insert
 * that fails or is cut short would lose the message for good. The receiver therefore [write]s the raw PDUs here
 * (fsync'd, atomic rename) before doing anything else, removes the entry once the inbox row exists, and a replay job
 * retries whatever is left.
 *
 * Every byte comes from the network, so nothing here may wedge:
 * - entries are bounded ([MAX_PDUS] PDUs of at most [MAX_PDU_BYTES]); anything larger is not journaled (the
 *   receiver then processes it exactly as it did before the journal existed);
 * - at most [maxPending] entries are kept pending, so a flood that keeps failing cannot fill the disk;
 * - an entry that failed [maxAttempts] times, or that cannot be parsed at all, is moved to a bounded quarantine
 *   ([maxQuarantined], oldest dropped) instead of being retried forever, and is counted for the user to see;
 * - an unreadable or truncated file is quarantined, never thrown.
 *
 * The file name is a SHA-256 of the format and PDUs, so the same broadcast delivered twice (the platform re-delivers
 * raw PDUs after a crash) maps to the same entry; [Written.alreadyPresent] tells the caller to check the inbox for
 * a row written by the earlier attempt before inserting again.
 */
class SmsJournal(
    private val root: File,
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val maxQuarantined: Int = DEFAULT_MAX_QUARANTINED,
) {
    /** One journaled SMS broadcast. */
    class Entry(
        val key: String,
        val format: String?,
        val pdus: List<ByteArray>,
        val subId: Int,
        val receivedAtMillis: Long,
        /** Failed insert attempts so far (0 while the receiver is still working on it). */
        val attempts: Int,
    )

    /** Result of [write]: the entry's [key], and whether it already existed (a re-delivery of the same PDUs). */
    data class Written(val key: String, val alreadyPresent: Boolean)

    private val pendingDir get() = File(root, "pending")
    private val quarantineDir get() = File(root, "quarantine")

    /**
     * Durably records a broadcast; null when it was not journaled (out of bounds, journal full, or an I/O error), in
     * which case the caller carries on without a journal entry.
     */
    @Synchronized
    fun write(format: String?, pdus: List<ByteArray>, subId: Int, receivedAtMillis: Long): Written? {
        if (!withinBounds(format, pdus)) return null
        val key = keyOf(format, pdus)
        val file = pendingFile(key)
        if (file.exists()) {
            // A re-delivery keeps the first entry (and its receive time and attempt count).
            if (read(file) != null) return Written(key, alreadyPresent = true)
            file.delete()
        }
        val dir = pendingDir
        if (!dir.isDirectory && !dir.mkdirs()) return null
        if ((dir.list()?.count { it.endsWith(SUFFIX) } ?: 0) >= maxPending) return null
        return if (writeAtomically(file, encode(Entry(key, format, pdus, subId, receivedAtMillis, 0)))) Written(key, false) else null
    }

    /** Pending entries, oldest first. Unreadable files are quarantined on the way. */
    @Synchronized
    fun pending(): List<Entry> {
        val files = pendingDir.listFiles { f -> f.name.endsWith(SUFFIX) } ?: return emptyList()
        val out = ArrayList<Entry>(files.size)
        for (f in files) {
            val entry = read(f)
            if (entry == null) moveToQuarantine(f) else out += entry
        }
        return out.sortedBy { it.receivedAtMillis }
    }

    /** True when there is anything to replay (cheap: one directory listing). */
    @Synchronized
    fun hasPending(): Boolean = pendingDir.list()?.any { it.endsWith(SUFFIX) } == true

    /** Removes the entry once the inbox row exists (or the message needs no storing). */
    @Synchronized
    fun remove(key: String) {
        if (isKey(key)) pendingFile(key).delete()
    }

    /**
     * Records one more failed attempt. Returns true when the entry stays pending for another replay, false when it
     * reached [maxAttempts] and was quarantined (or no longer exists).
     */
    @Synchronized
    fun recordFailure(key: String): Boolean {
        if (!isKey(key)) return false
        val file = pendingFile(key)
        val entry = read(file) ?: run {
            if (file.exists()) moveToQuarantine(file)
            return false
        }
        val attempts = entry.attempts + 1
        if (attempts >= maxAttempts) {
            moveToQuarantine(file)
            return false
        }
        val updated = Entry(entry.key, entry.format, entry.pdus, entry.subId, entry.receivedAtMillis, attempts)
        return writeAtomically(file, encode(updated))
    }

    /** Moves an entry that can never be stored (unparseable PDUs) out of the replay queue. */
    @Synchronized
    fun quarantine(key: String) {
        if (!isKey(key)) return
        val file = pendingFile(key)
        if (file.exists()) moveToQuarantine(file)
    }

    /** Messages that could not be saved and were given up on (shown to the user). */
    @Synchronized
    fun quarantinedCount(): Int = quarantineDir.list()?.size ?: 0

    // --- Internals --------------------------------------------------------------------------------------------

    private fun pendingFile(key: String) = File(pendingDir, key + SUFFIX)

    private fun moveToQuarantine(file: File) {
        val dir = quarantineDir
        if (!dir.isDirectory && !dir.mkdirs()) {
            file.delete()
            return
        }
        val existing = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
        existing.take((existing.size - maxQuarantined + 1).coerceAtLeast(0)).forEach { it.delete() }
        val target = File(dir, file.name)
        if (!file.renameTo(target)) file.delete()
    }

    private fun read(file: File): Entry? {
        val length = file.length()
        if (length <= 0 || length > MAX_ENTRY_BYTES) return null
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return null
        }
        val entry = decode(bytes) ?: return null
        return entry.takeIf { file.name == entry.key + SUFFIX }
    }

    private fun writeAtomically(file: File, bytes: ByteArray): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return false
            }
            syncDirectory(file.parentFile)
            true
        } catch (e: IOException) {
            tmp.delete()
            false
        } catch (e: SecurityException) {
            tmp.delete()
            false
        }
    }

    /** Makes the rename itself durable (best effort: not every file system lets a directory be opened). */
    private fun syncDirectory(dir: File?) {
        dir ?: return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // Some platforms refuse to open directories; the file itself is already synced.
        }
    }

    companion object {
        /** A concatenated SMS has at most 255 parts. */
        const val MAX_PDUS: Int = 255

        /** A GSM PDU is at most 176 octets with the SMSC address; 3GPP2 PDUs are larger, but never near this. */
        const val MAX_PDU_BYTES: Int = 1024

        const val MAX_FORMAT_CHARS: Int = 16
        const val DEFAULT_MAX_PENDING: Int = 200
        const val DEFAULT_MAX_ATTEMPTS: Int = 5
        const val DEFAULT_MAX_QUARANTINED: Int = 50

        private const val MAGIC = 0x44414B4A // "DAKJ"
        private const val VERSION = 1
        private const val SUFFIX = ".sms"
        private const val KEY_CHARS = 40
        private const val MAX_ENTRY_BYTES: Long = 64L + MAX_FORMAT_CHARS * 3 + MAX_PDUS.toLong() * (4 + MAX_PDU_BYTES)

        fun withinBounds(format: String?, pdus: List<ByteArray>): Boolean =
            pdus.isNotEmpty() && pdus.size <= MAX_PDUS && pdus.all { it.size in 1..MAX_PDU_BYTES } &&
                (format == null || format.length <= MAX_FORMAT_CHARS)

        /** Stable key for a broadcast: SHA-256 over the format and the length-prefixed PDUs, hex, 40 characters. */
        fun keyOf(format: String?, pdus: List<ByteArray>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update((format ?: "").toByteArray(Charsets.UTF_8))
            digest.update(0)
            for (pdu in pdus) {
                digest.update(byteArrayOf((pdu.size shr 8).toByte(), pdu.size.toByte()))
                digest.update(pdu)
            }
            return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }.take(KEY_CHARS)
        }

        private fun isKey(key: String): Boolean = key.length == KEY_CHARS && key.all { it in '0'..'9' || it in 'a'..'f' }

        internal fun encode(entry: Entry): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeByte(VERSION)
                out.writeUTF(entry.key)
                out.writeBoolean(entry.format != null)
                entry.format?.let(out::writeUTF)
                out.writeInt(entry.subId)
                out.writeLong(entry.receivedAtMillis)
                out.writeInt(entry.attempts)
                out.writeInt(entry.pdus.size)
                for (pdu in entry.pdus) {
                    out.writeInt(pdu.size)
                    out.write(pdu)
                }
            }
            return bytes.toByteArray()
        }

        /** Decodes an entry, or null for anything malformed or out of bounds (never throws). */
        internal fun decode(bytes: ByteArray): Entry? = try {
            DataInputStream(bytes.inputStream()).use { input ->
                if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) return null
                val key = input.readUTF()
                if (!isKey(key)) return null
                val format = if (input.readBoolean()) input.readUTF() else null
                if (format != null && format.length > MAX_FORMAT_CHARS) return null
                val subId = input.readInt()
                val receivedAt = input.readLong()
                val attempts = input.readInt()
                val count = input.readInt()
                if (count !in 1..MAX_PDUS || attempts < 0) return null
                val pdus = ArrayList<ByteArray>(count)
                repeat(count) {
                    val size = input.readInt()
                    if (size !in 1..MAX_PDU_BYTES) return null
                    val pdu = ByteArray(size)
                    input.readFully(pdu)
                    pdus += pdu
                }
                if (input.read() != -1) return null // trailing bytes
                if (keyOf(format, pdus) != key) return null
                Entry(key, format, pdus, subId, receivedAt, attempts)
            }
        } catch (e: IOException) {
            null
        }
    }
}
