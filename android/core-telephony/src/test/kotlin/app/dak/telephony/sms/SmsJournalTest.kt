package app.dak.telephony.sms

import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SmsJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun journal(maxPending: Int = 200, maxAttempts: Int = 5, maxQuarantined: Int = 50) =
        SmsJournal(tmp.root, maxPending, maxAttempts, maxQuarantined)

    private val pdus = listOf(byteArrayOf(0, 4, 11, -111, 81, 85, 0, 0, 0, 1), byteArrayOf(0, 4, 11, -111, 2, 3))

    @Test
    fun writeReadRemoveRoundTrip() {
        val j = journal()
        val written = assertNotNull(j.write("3gpp", pdus, subId = 3, receivedAtMillis = 1_000))
        assertFalse(written.alreadyPresent)
        val entry = j.pending().single()
        assertEquals(written.key, entry.key)
        assertEquals("3gpp", entry.format)
        assertEquals(3, entry.subId)
        assertEquals(1_000, entry.receivedAtMillis)
        assertEquals(0, entry.attempts)
        assertEquals(pdus.map { it.toList() }, entry.pdus.map { it.toList() })
        assertTrue(j.hasPending())
        j.remove(written.key)
        assertFalse(j.hasPending())
        assertTrue(j.pending().isEmpty())
    }

    @Test
    fun survivesANewInstance() {
        val key = journal().write(null, pdus, -1, 5)!!.key
        // A new process sees the same entry (the file is the source of truth).
        assertEquals(key, journal().pending().single().key)
    }

    @Test
    fun redeliveryOfTheSamePdusIsRecognised() {
        val j = journal()
        val first = j.write("3gpp", pdus, 1, 10)!!
        val again = j.write("3gpp", pdus, 1, 99)!!
        assertEquals(first.key, again.key)
        assertTrue(again.alreadyPresent)
        // The first receive time is kept, so a replay matches the row the first attempt may have written.
        assertEquals(10, j.pending().single().receivedAtMillis)
    }

    @Test
    fun failingEntryIsQuarantinedAfterMaxAttempts() {
        val j = journal(maxAttempts = 3)
        val key = j.write("3gpp", pdus, 1, 10)!!.key
        assertTrue(j.recordFailure(key))
        assertTrue(j.recordFailure(key))
        assertEquals(2, j.pending().single().attempts)
        assertFalse(j.recordFailure(key)) // third failure: given up on
        assertFalse(j.hasPending())
        assertEquals(1, j.quarantinedCount())
    }

    @Test
    fun outOfBoundsInputIsNotJournaled() {
        val j = journal()
        assertNull(j.write("3gpp", emptyList(), 1, 1))
        assertNull(j.write("3gpp", listOf(ByteArray(0)), 1, 1))
        assertNull(j.write("3gpp", listOf(ByteArray(SmsJournal.MAX_PDU_BYTES + 1)), 1, 1))
        assertNull(j.write("3gpp", List(SmsJournal.MAX_PDUS + 1) { byteArrayOf(1) }, 1, 1))
        assertNull(j.write("x".repeat(100), pdus, 1, 1))
        assertFalse(j.hasPending())
    }

    @Test
    fun pendingIsBoundedSoAFloodCannotFillTheDisk() {
        val j = journal(maxPending = 5)
        val written = (0 until 20).mapNotNull { i -> j.write("3gpp", listOf(byteArrayOf(i.toByte(), 1, 2)), 1, i.toLong()) }
        assertEquals(5, written.size)
        assertEquals(5, j.pending().size)
    }

    @Test
    fun quarantineIsBounded() {
        val j = journal(maxAttempts = 1, maxQuarantined = 3)
        repeat(10) { i ->
            val key = j.write("3gpp", listOf(byteArrayOf(i.toByte(), 9)), 1, i.toLong())!!.key
            j.recordFailure(key)
        }
        assertTrue(j.quarantinedCount() <= 3)
    }

    @Test
    fun corruptOrForeignFilesAreQuarantinedNotThrown() {
        val j = journal()
        val key = j.write("3gpp", pdus, 1, 10)!!.key
        val pendingDir = File(tmp.root, "pending")
        val file = File(pendingDir, "$key.sms")
        // Truncated entry, random garbage under a valid-looking name, and a name that does not match the content.
        file.writeBytes(file.readBytes().copyOf(12))
        File(pendingDir, "0".repeat(40) + ".sms").writeBytes(Random(1).nextBytes(300))
        val other = journal().write("3gpp", listOf(byteArrayOf(7, 7)), 1, 11)!!.key
        File(pendingDir, "$other.sms").copyTo(File(pendingDir, "f".repeat(40) + ".sms"))
        val pending = j.pending()
        assertEquals(listOf(other), pending.map { it.key })
        assertEquals(3, j.quarantinedCount())
    }

    @Test
    fun decoderRejectsHostileBytes() {
        val random = Random(42)
        repeat(5_000) { assertNull(SmsJournal.decode(random.nextBytes(random.nextInt(0, 200)))) }
        val valid = SmsJournal.encode(SmsJournal.Entry(SmsJournal.keyOf("3gpp", pdus), "3gpp", pdus, 1, 2, 0))
        assertNotNull(SmsJournal.decode(valid))
        // Every truncation and every single-byte corruption is rejected or still consistent, never thrown.
        for (n in valid.indices) assertNull(SmsJournal.decode(valid.copyOf(n)))
        for (i in valid.indices) {
            val corrupted = valid.copyOf().also { it[i] = (it[i].toInt() xor 0x5A).toByte() }
            SmsJournal.decode(corrupted)?.let { assertEquals(SmsJournal.keyOf(it.format, it.pdus), it.key) }
        }
    }

    @Test
    fun keysArePathSafe() {
        val j = journal()
        j.remove("../../etc/passwd")
        j.quarantine("../x")
        assertFalse(j.recordFailure("/data/data"))
        val key = SmsJournal.keyOf("3gpp", pdus)
        assertEquals(40, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
