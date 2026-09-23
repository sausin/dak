package app.dak.backup.importers

import app.dak.backup.format.AttachmentRecord
import app.dak.backup.format.DakExportReader
import app.dak.backup.format.DakExportWriter
import app.dak.backup.format.Hashing
import app.dak.backup.format.ManifestMeta
import app.dak.backup.format.MessageRecord
import app.dak.backup.xml.SmsBackupRestoreXmlExporter
import app.dak.backup.xml.SmsBackupRestoreXmlImporter
import app.dak.core.model.MessageKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Seeded mutation fuzzing of every importer and of the Dak export reader. A hostile or corrupt file may be refused,
 * but only with an "input problem" exception (I/O incl. [app.dak.backup.format.ArchiveLimitException], or
 * [IllegalArgumentException] incl. JSON/number parse errors) — never a NullPointerException, ClassCastException,
 * IndexOutOfBounds, OutOfMemoryError or StackOverflowError that would crash the import screen.
 *
 * Fixed seeds keep this deterministic; the time budget per target is generous for slow CI machines.
 */
class ImporterFuzzTest {

    private val iterations = 1_000

    private fun mutate(seed: ByteArray, r: Random): ByteArray {
        val b = seed.toMutableList()
        repeat(1 + r.nextInt(8)) {
            if (b.isEmpty()) { b += r.nextInt(256).toByte(); return@repeat }
            val i = r.nextInt(b.size)
            when (r.nextInt(7)) {
                0 -> b[i] = r.nextInt(256).toByte() // random byte
                1 -> b.removeAt(i) // delete
                2 -> b.add(i, "<>&\"'{}[]:,\\\u0000".random(r).code.toByte()) // syntax byte
                3 -> repeat(r.nextInt(1, 40)) { b.add(i, b[i]) } // stutter
                4 -> { val j = r.nextInt(b.size); val t = b[i]; b[i] = b[j]; b[j] = t } // swap
                5 -> while (b.size > i) b.removeAt(b.size - 1) // truncate
                else -> b.addAll(i, listOf(0xC3, 0x28, 0xF0, 0x28, 0x8C, 0xBC, 0xED, 0xA0, 0x80).map { it.toByte() }) // invalid UTF-8
            }
        }
        return b.toByteArray()
    }

    private fun isInputProblem(t: Throwable): Boolean = t is IOException || t is IllegalArgumentException

    private fun fuzz(name: String, seeds: List<ByteArray>, seed: Int, run: (ByteArray) -> Unit) {
        val r = Random(seed)
        val start = System.nanoTime()
        repeat(iterations) { n ->
            val input = mutate(seeds[n % seeds.size], r)
            try {
                run(input)
            } catch (t: Throwable) {
                if (!isInputProblem(t)) fail("$name iteration $n: ${t::class.qualifiedName}: ${t.message}", t)
            }
        }
        val seconds = (System.nanoTime() - start) / 1e9
        assertTrue(seconds < 30, "$name took ${seconds}s")
        // Sanity: the originals themselves must import cleanly.
        seeds.forEach(run)
    }

    private val xmlSeed: ByteArray = ByteArrayOutputStream().also { out ->
        SmsBackupRestoreXmlExporter.write(
            out,
            sequenceOf(
                MessageRecord("sms:1", MessageKind.SMS, 1, "+15550100", "Hello <b>&amp; ₹500 😀", 1_700_000_000_000),
                MessageRecord("sms:2", MessageKind.SMS, 2, "VM-HDFCBK", "OTP 123456", 1_700_000_000_001),
            ),
            count = 2,
        )
    }.toByteArray()

    private val mmsXmlSeed = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<smses count="1"><mms date="1700000000000" msg_box="1" address="+1" read="1" m_type="132">
<parts><part seq="0" ct="text/plain" text="hi" /><part seq="1" ct="image/png" name="a.png" data="aGVsbG8=" /></parts>
<addrs><addr address="+1" type="137" /></addrs></mms></smses>""".toByteArray()

    private val fossifySeed = """[{"address":"123","body":"hi","date":1700000000000,"dateSent":1,"type":1,"subscriptionId":1,"read":true},
{"backupType":"mms","date":5,"type":2,"addresses":[{"address":"999","type":137}],
"parts":[{"contentType":"text/plain","text":"cap"},{"contentType":"image/png","name":"p.png","data":"aGVsbG8="}]}]""".toByteArray()

    private val organizerSeed = """{"messages":[{"sender":"foo","message":"bar","timestamp":1000000000000,"type":"sent"},
{"Address":"1","Body":"x","Date":1700000000,"Type":"inbox","sim":2}]}""".toByteArray()

    private val organizerZipSeed: ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("backup/messages.json"))
            zip.write(organizerSeed)
            zip.closeEntry()
        }
    }.toByteArray()

    private val dakSeed: ByteArray = ByteArrayOutputStream().also { out ->
        val w = DakExportWriter(out)
        val sha = Hashing.sha256Hex("img".toByteArray())
        w.writeAttachment(sha, "img".toByteArray())
        w.writeMessages(sequenceOf(MessageRecord("sms:1", MessageKind.SMS, 1, "+1", "a", 1, attachments = listOf(AttachmentRecord("image/png", sha)))))
        w.writeThreads(emptyList())
        w.writeSettings("{}")
        w.finish(ManifestMeta(createdAt = 1, appVersion = "t", id = "x"))
        w.close()
    }.toByteArray()

    @Test
    fun `sms backup and restore xml importer survives mutated input`() =
        fuzz("sbr-xml", listOf(xmlSeed, mmsXmlSeed), seed = 1) { bytes ->
            SmsBackupRestoreXmlImporter().import(ByteArrayInputStream(bytes)).forEach { m ->
                m.attachments.forEach { a -> a.bytes?.invoke() }
            }
        }

    @Test
    fun `fossify importer survives mutated input`() =
        fuzz("fossify", listOf(fossifySeed), seed = 2) { bytes ->
            FossifyImporter().import(ByteArrayInputStream(bytes)).forEach { m -> m.attachments.forEach { a -> a.bytes?.invoke() } }
        }

    @Test
    fun `sms organizer importer survives mutated json and zip input`() =
        fuzz("organizer", listOf(organizerSeed, organizerZipSeed), seed = 3) { bytes ->
            SmsOrganizerImporter().import(ByteArrayInputStream(bytes))
        }

    @Test
    fun `dak export reader survives mutated archives`() =
        fuzz("dak-export", listOf(dakSeed), seed = 4) { bytes ->
            val reader = DakExportReader(ByteArrayInputStream(bytes))
            reader.readMessages { _, input -> input.readBytes() }.toList()
        }

    @Test
    fun `import detector never throws on arbitrary headers`() {
        val r = Random(5)
        repeat(2_000) {
            val head = ByteArray(r.nextInt(0, 64)) { r.nextInt(256).toByte() }
            ImportDetector.detectOrFallback(head, if (r.nextBoolean()) null else "x${r.nextInt()}.xml")
        }
        for (s in listOf(xmlSeed, fossifySeed, organizerSeed, organizerZipSeed)) ImportDetector.detectOrFallback(s.copyOf(minOf(s.size, 512)))
    }
}
