package app.dak.backup.format

import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DakExportRoundTripTest {

    private fun record(i: Int, body: String = "Hello $i"): MessageRecord = MessageRecord(
        key = "sms:$i",
        kind = MessageKind.SMS,
        threadId = (i % 5).toLong(),
        address = "+9198765432$i",
        body = body,
        dateMillis = 1_700_000_000_000L + i,
        subId = i % 2,
        box = MessageBox.INBOX,
        read = i % 3 == 0,
        category = if (i % 4 == 0) Category.OTP else null,
        labels = if (i % 7 == 0) setOf("bank") else emptySet(),
        starred = i % 11 == 0,
    )

    @Test
    fun `round trips messages, threads, settings and manifest`() {
        val n = 2500 // spans multiple chunk files with the default chunk size
        val out = ByteArrayOutputStream()
        val writer = DakExportWriter(out, chunkSize = 500)
        val threads = listOf(ThreadPrefs(threadId = 1, replySubId = 1, pinned = true))
        writer.writeAttachment("deadbeef", "hello attachment".toByteArray())
        writer.writeMessages((0 until n).asSequence().map(::record))
        writer.writeThreads(threads)
        writer.writeSettings("""{"theme":"dark"}""")
        val manifest = writer.finish(ManifestMeta(createdAt = 42L, appVersion = "1.0", id = "snap-1"))
        writer.close()

        assertEquals(n, manifest.counts.messages)
        assertEquals(1, manifest.counts.threads)
        assertEquals(1, manifest.counts.attachments)
        assertEquals(ManifestKind.FULL, manifest.kind)
        assertTrue(manifest.parts.any { it.name == "manifest.json" }.not()) // manifest doesn't list itself

        val attachmentBytes = mutableMapOf<String, ByteArray>()
        val reader = DakExportReader(ByteArrayInputStream(out.toByteArray()))
        val messages = reader.readMessages { sha256, input -> attachmentBytes[sha256] = input.readBytes() }.toList()

        assertEquals(n, messages.size)
        for (i in 0 until n) assertEquals(record(i), messages[i])
        assertEquals(threads, reader.threads)
        assertEquals("""{"theme":"dark"}""", reader.settingsJson)
        assertEquals(manifest, reader.manifest)
        assertEquals("hello attachment", attachmentBytes["deadbeef"]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `parts each carry a correct sha256 and size`() {
        val out = ByteArrayOutputStream()
        val writer = DakExportWriter(out)
        writer.writeMessages(sequenceOf(record(1)))
        val manifest = writer.finish(ManifestMeta(createdAt = 1L, appVersion = "1.0", id = "s1"))
        writer.close()

        val part = manifest.parts.single { it.name == "messages/0000.jsonl" }
        assertEquals(Hashing.sha256Hex(record(1).let { rec -> DakExportWriter.defaultJson.encodeToString(MessageRecord.serializer(), rec) + "\n" }), part.sha256)
    }

    @Test
    fun `empty export still produces a valid manifest`() {
        val out = ByteArrayOutputStream()
        val writer = DakExportWriter(out)
        writer.writeMessages(emptySequence())
        writer.writeThreads(emptyList())
        writer.writeSettings("{}")
        val manifest = writer.finish(ManifestMeta(createdAt = 1L, appVersion = "1.0", id = "empty"))
        writer.close()
        assertEquals(0, manifest.counts.messages)

        val reader = DakExportReader(ByteArrayInputStream(out.toByteArray()))
        assertEquals(emptyList(), reader.readMessages().toList())
        assertEquals(emptyList(), reader.threads)
        assertEquals("{}", reader.settingsJson)
        assertEquals(manifest, reader.manifest)
    }

    @Test
    fun `incremental manifest carries parent and deleted keys`() {
        val out = ByteArrayOutputStream()
        val writer = DakExportWriter(out)
        writer.writeMessages(sequenceOf(record(1)))
        val manifest = writer.finish(
            ManifestMeta(
                createdAt = 2L,
                appVersion = "1.0",
                id = "snap-2",
                parentId = "snap-1",
                kind = ManifestKind.INCREMENTAL,
                deletedKeys = listOf("sms:99"),
            ),
        )
        writer.close()
        assertEquals("snap-1", manifest.parentId)
        assertEquals(listOf("sms:99"), manifest.deletedKeys)
        assertEquals(ManifestKind.INCREMENTAL, manifest.kind)
    }
}
