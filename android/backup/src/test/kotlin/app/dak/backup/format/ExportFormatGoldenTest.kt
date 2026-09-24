package app.dak.backup.format

import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * "Dak export format v1" is an open, documented format (FORMAT.md): other tools read it, and every Dak build must
 * read archives written by older ones. These goldens pin the JSON of each record type, the content hash the
 * incremental planner compares across backups, and a hand-built v1 archive the reader must accept.
 */
class ExportFormatGoldenTest {

    private val json = DakExportWriter.defaultJson

    private val record = MessageRecord(
        key = "sms:42", kind = MessageKind.SMS, threadId = 7, address = "+919876543210", body = "Rs.500 debited\nरु",
        dateMillis = 1_700_000_000_000, subId = 2, box = MessageBox.SENT, read = true, seen = true,
        attachments = listOf(AttachmentRecord("image/png", "a".repeat(64), "p.png", 10)),
        category = Category.TRANSACTION, labels = setOf("tax", "bank"), starred = true, archived = false,
    )

    @Test
    fun `format name and version are pinned`() {
        assertEquals("dak-export", DAK_EXPORT_FORMAT_NAME)
        assertEquals(1, DAK_EXPORT_FORMAT_VERSION)
        assertEquals(listOf("FULL", "INCREMENTAL"), ManifestKind.entries.map { it.name })
    }

    @Test
    fun `message line golden`() {
        val golden = """{"key":"sms:42","kind":"SMS","threadId":7,"address":"+919876543210","body":"Rs.500 debited\nरु",""" +
            """"dateMillis":1700000000000,"subId":2,"box":"SENT","read":true,"seen":true,""" +
            """"attachments":[{"mimeType":"image/png","sha256":"${"a".repeat(64)}","name":"p.png","sizeBytes":10}],""" +
            """"category":"TRANSACTION","labels":["tax","bank"],"starred":true,"archived":false}"""
        assertEquals(golden, json.encodeToString(record))
        assertEquals(record, json.decodeFromString<MessageRecord>(golden))
        // Minimal line from another tool: only the required fields.
        val minimal = json.decodeFromString<MessageRecord>("""{"key":"mms:1","kind":"MMS","threadId":1,"address":"a","body":"","dateMillis":0}""")
        assertEquals(MessageBox.INBOX, minimal.box)
        assertNull(minimal.category)
        assertEquals(-1, minimal.subId)
    }

    @Test
    fun `content hash is pinned so upgrades do not re-upload every message`() {
        // BackupPlanner compares this against the digest saved after the previous backup. If it changes, the next
        // incremental backup silently becomes a full one; change it only deliberately.
        assertEquals("79ab8b6186e491f545ef9e41b469474d3b36c79eafd87a70cf14b65538419ef3", record.contentHash())
        assertEquals(record.contentHash(), record.copy(key = "sms:999").contentHash(), "the key is not content")
        assertEquals(record.contentHash(), record.copy(labels = setOf("bank", "tax")).contentHash(), "label order is not content")
        for (changed in listOf(
            record.copy(body = record.body + " "), record.copy(read = false), record.copy(box = MessageBox.INBOX),
            record.copy(category = null), record.copy(archived = true), record.copy(attachments = emptyList()),
            record.copy(attachments = listOf(record.attachments[0].copy(mimeType = "image/jpeg"))),
        )) {
            assertEquals(false, changed.contentHash() == record.contentHash(), "$changed")
        }
        // Attachment display name and size are not content (the bytes are identified by sha256).
        assertEquals(record.contentHash(), record.copy(attachments = listOf(record.attachments[0].copy(name = "x", sizeBytes = 1))).contentHash())
    }

    @Test
    fun `content hash separates fields with a control character`() {
        val a = record.copy(address = "a\u0001b", body = "c")
        val b = record.copy(address = "a", body = "b\u0001c")
        // The separator is a control character that ordinary SMS text does not contain; document the limitation:
        // if both fields can contain it, two different records can collide.
        assertEquals(a.contentHash() == b.contentHash(), true)
        val c = record.copy(address = "ab", body = "c")
        val d = record.copy(address = "a", body = "bc")
        assertEquals(false, c.contentHash() == d.contentHash())
    }

    @Test
    fun `manifest and side records golden`() {
        val m = Manifest(
            createdAt = 5, appVersion = "1.2.3", device = "Pixel", counts = ManifestCounts(1, 2, 3, 4),
            parts = listOf(ManifestPart("threads.json", "b".repeat(64), 9)), id = "snap-2", parentId = "snap-1",
            kind = ManifestKind.INCREMENTAL, deletedKeys = listOf("sms:1"),
        )
        val golden = """{"format":"dak-export","version":1,"createdAt":5,"appVersion":"1.2.3","device":"Pixel",""" +
            """"counts":{"messages":1,"threads":2,"attachments":3,"automationRuns":4},""" +
            """"parts":[{"name":"threads.json","sha256":"${"b".repeat(64)}","sizeBytes":9}],"id":"snap-2","parentId":"snap-1",""" +
            """"kind":"INCREMENTAL","deletedKeys":["sms:1"]}"""
        assertEquals(golden, json.encodeToString(m))
        assertEquals(m, json.decodeFromString<Manifest>(golden))
        // A manifest written before automation runs and incrementals existed.
        val old = json.decodeFromString<Manifest>(
            """{"format":"dak-export","version":1,"createdAt":1,"appVersion":"0.9","counts":{"messages":0,"threads":0,"attachments":0},"parts":[],"id":"x"}""",
        )
        assertEquals(ManifestKind.FULL, old.kind)
        assertEquals(0, old.counts.automationRuns)
        assertNull(old.parentId)

        assertEquals(
            """{"threadId":3,"replySubId":null,"pinned":true,"muted":false,"archived":false,"bubbleColorArgb":-65536}""",
            json.encodeToString(ThreadPrefs(threadId = 3, pinned = true, bubbleColorArgb = -65536)),
        )
        assertEquals(
            """{"ruleId":"r","ruleName":"R","atMillis":1,"messageKey":null,"conversationId":null,"sourceLabel":null,""" +
                """"actionKind":"ForwardSms","destinationLabel":null,"destination":"+1","outcome":"SENT","reason":null,"textPreview":null}""",
            json.encodeToString(AutomationRunRecord(ruleId = "r", ruleName = "R", atMillis = 1, actionKind = "ForwardSms", destination = "+1", outcome = "SENT")),
        )
    }

    /** A v1 archive assembled by hand, as FORMAT.md describes it, not by [DakExportWriter]. */
    private fun handMadeArchive(extraEntries: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("attachments/${Hashing.sha256Hex("img".toByteArray())}", "img".toByteArray())
            put("messages/0000.jsonl", ("""{"key":"sms:1","kind":"SMS","threadId":1,"address":"+1","body":"one","dateMillis":1}""" + "\r\n\n" +
                """{"key":"sms:2","kind":"SMS","threadId":1,"address":"+1","body":"two","dateMillis":2,"futureField":{"x":1}}""").toByteArray())
            put("messages/0001.jsonl", """{"key":"mms:3","kind":"MMS","threadId":2,"address":"+2","body":"three","dateMillis":3}""".toByteArray())
            for ((n, b) in extraEntries) put(n, b)
            put("threads.json", """[{"threadId":1,"pinned":true}]""".toByteArray())
            put("settings.json", """{"a":1}""".toByteArray())
            put("automation_runs.jsonl", """{"ruleId":"r","ruleName":"R","atMillis":1,"actionKind":"ForwardSms","outcome":"SENT"}""".toByteArray())
            put("manifest.json", """{"format":"dak-export","version":1,"createdAt":1,"appVersion":"x","counts":{"messages":3,"threads":1,"attachments":1},"parts":[],"id":"hand"}""".toByteArray())
        }
        return out.toByteArray()
    }

    @Test
    fun `a hand-made v1 archive reads fully`() {
        val attachments = mutableMapOf<String, ByteArray>()
        val reader = DakExportReader(ByteArrayInputStream(handMadeArchive()))
        val messages = reader.readMessages { sha, input -> attachments[sha] = input.readBytes() }.toList()
        assertEquals(listOf("sms:1", "sms:2", "mms:3"), messages.map { it.key })
        assertEquals(listOf("one", "two", "three"), messages.map { it.body })
        assertContentEquals("img".toByteArray(), attachments.values.single())
        assertEquals(1, reader.threads.single().threadId)
        assertEquals("""{"a":1}""", reader.settingsJson)
        assertEquals("hand", reader.manifest?.id)
        assertEquals("R", reader.automationRuns.single().ruleName)
    }

    @Test
    fun `entries this version does not know are skipped, unsafe attachment names are ignored`() {
        val reader = DakExportReader(
            ByteArrayInputStream(
                handMadeArchive(
                    listOf(
                        "future/part.bin" to ByteArray(10),
                        "attachments/../../evil" to "x".toByteArray(),
                        "attachments/ABC" to "x".toByteArray(),
                        "messages/readme.txt" to "not json".toByteArray(),
                    ),
                ),
            ),
        )
        val sunk = mutableListOf<String>()
        assertEquals(3, reader.readMessages { sha, _ -> sunk += sha }.count())
        assertEquals(listOf(Hashing.sha256Hex("img".toByteArray())), sunk)
    }

    @Test
    fun `a message line with a value this version cannot read fails the read loudly`() {
        // Documented behaviour: message lines are not best effort (unlike the run history), so a restore never
        // silently drops messages. Seen by users as "this backup was made by a newer Dak".
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("messages/0000.jsonl"))
            zip.write("""{"key":"sms:1","kind":"RCS","threadId":1,"address":"+1","body":"x","dateMillis":1}""".toByteArray())
            zip.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> { DakExportReader(ByteArrayInputStream(out.toByteArray())).readMessages().toList() }
    }

    @Test
    fun `hashing helpers agree with each other and reject bad hex`() {
        val bytes = "abc".toByteArray()
        val hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(hex, Hashing.sha256Hex(bytes))
        assertEquals(hex, Hashing.sha256Hex("abc"))
        assertEquals(hex, Hashing.sha256Hex(ByteArrayInputStream(bytes)))
        assertContentEquals(Hashing.sha256(bytes), Hashing.hexToBytes(hex))
        assertContentEquals(Hashing.sha256(bytes), Hashing.hexToBytes(hex.uppercase()))
        assertContentEquals(ByteArray(0), Hashing.hexToBytes(""))
        assertFailsWith<IllegalArgumentException> { Hashing.hexToBytes("abc") }
        assertFailsWith<IllegalArgumentException> { Hashing.hexToBytes("zz") }
        val big = ByteArray(100_000) { it.toByte() }
        assertEquals(Hashing.sha256Hex(big), Hashing.sha256Hex(ByteArrayInputStream(big)))
    }
}
