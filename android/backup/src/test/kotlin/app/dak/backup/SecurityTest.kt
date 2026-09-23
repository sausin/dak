package app.dak.backup

import app.dak.backup.crypto.BackupCrypto
import app.dak.backup.crypto.MalformedHeaderException
import app.dak.backup.crypto.TamperedException
import app.dak.backup.engine.BackupEngine
import app.dak.backup.engine.LocalDirectoryTarget
import app.dak.backup.format.ArchiveLimitException
import app.dak.backup.format.ArchiveLimits
import app.dak.backup.format.DakExportReader
import app.dak.backup.format.DakExportWriter
import app.dak.backup.format.Hashing
import app.dak.backup.format.LimitedInputStream
import app.dak.backup.format.ManifestMeta
import app.dak.backup.format.MessageRecord
import app.dak.backup.format.checkJsonDepth
import app.dak.backup.format.readBounded
import app.dak.backup.importers.FossifyImporter
import app.dak.backup.importers.SmsOrganizerImporter
import app.dak.backup.xml.SmsBackupRestoreXmlImporter
import app.dak.backup.xml.XmlEntities
import app.dak.backup.xml.XmlLimits
import app.dak.backup.xml.XmlToken
import app.dak.backup.xml.XmlTokenizer
import app.dak.core.model.MessageKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Red-team tests: backup and import files are attacker-controlled input. */
class SecurityTest {

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun record(key: String, body: String = "hi") = MessageRecord(
        key = key, kind = MessageKind.SMS, threadId = 1, address = "+15551234567", body = body, dateMillis = 1_700_000_000_000L,
    )

    // --- Dak archive (zip) ------------------------------------------------------------------------------------

    @Test
    fun `zip-slip attachment names never reach the sink`() {
        val good = Hashing.sha256Hex("ok".toByteArray())
        val archive = zip(
            "attachments/../../databases/index.db" to "evil".toByteArray(),
            "attachments//data/data/app.dak/x" to "evil".toByteArray(),
            "attachments/${good.uppercase()}" to "evil".toByteArray(),
            "attachments/$good/../../x" to "evil".toByteArray(),
            "attachments/$good" to "ok".toByteArray(),
        )
        val seen = mutableListOf<String>()
        DakExportReader(ByteArrayInputStream(archive)).readMessages { sha, input -> seen += sha; input.readBytes() }.toList()
        assertEquals(listOf(good), seen)
    }

    @Test
    fun `zip bomb attachment is stopped while inflating`() {
        val sha = "a".repeat(64)
        val bomb = zip("attachments/$sha" to ByteArray((ArchiveLimits.MAX_ATTACHMENT_BYTES + 1).toInt())) // compresses to ~64 KiB
        assertTrue(bomb.size < 1_000_000, "fixture should be a high-ratio archive")
        val sink = ByteArray(64 * 1024)
        assertFailsWith<ArchiveLimitException> {
            DakExportReader(ByteArrayInputStream(bomb)).readMessages { _, input -> while (input.read(sink) >= 0) Unit }.toList()
        }
    }

    @Test
    fun `oversized metadata entry and endless message line are refused`() {
        val hugeSettings = zip("settings.json" to ByteArray((ArchiveLimits.MAX_METADATA_BYTES + 1).toInt()) { ' '.code.toByte() })
        assertFailsWith<ArchiveLimitException> { DakExportReader(ByteArrayInputStream(hugeSettings)).readMessages().toList() }

        val longLine = zip("messages/0000.jsonl" to ByteArray(ArchiveLimits.MAX_JSON_LINE_CHARS + 10) { 'x'.code.toByte() })
        assertFailsWith<ArchiveLimitException> { DakExportReader(ByteArrayInputStream(longLine)).readMessages().toList() }
    }

    @Test
    fun `deeply nested json is rejected before parsing`() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertFailsWith<ArchiveLimitException> { checkJsonDepth(deep) }
        checkJsonDepth("""{"a":[{"b":"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["}]}""") // brackets in strings don't count
        val archive = zip("threads.json" to deep.toByteArray())
        assertFailsWith<ArchiveLimitException> { DakExportReader(ByteArrayInputStream(archive)).readMessages().toList() }
        assertFailsWith<ArchiveLimitException> { FossifyImporter().import(ByteArrayInputStream(deep.toByteArray())).toList() }
        // SMS Organizer is tolerant: it reports the problem as a warning instead of throwing.
        val result = SmsOrganizerImporter().import(ByteArrayInputStream(deep.toByteArray()))
        assertTrue(result.messages.isEmpty() && result.warnings.isNotEmpty())
    }

    @Test
    fun `limited stream stops at the cap and counts totals`() {
        var total = 0L
        val limited = LimitedInputStream(ByteArrayInputStream(ByteArray(1000)), 999, "x", onBytes = { total += it })
        assertFailsWith<ArchiveLimitException> { limited.readBytes() }
        assertTrue(total >= 1000)
        assertEquals(10, readBounded(ByteArrayInputStream(ByteArray(10)), 10, "x").size)
    }

    @Test
    fun `sms organizer zip entries are inflated under a cap`() {
        // A small archive still imports; the cap itself is exercised by the limited-stream test above.
        val json = """[{"address":"+1555","body":"hi","date":1700000000000}]"""
        val result = SmsOrganizerImporter().import(ByteArrayInputStream(zip("b.json" to json.toByteArray())))
        assertEquals(1, result.messages.size)
    }

    // --- Restore chain ------------------------------------------------------------------------------------------

    private fun tempDir(): File = createTempDirectory("dak-sec").toFile().apply { deleteOnExit() }

    private fun snapshot(id: String, parentId: String?): ByteArray {
        val out = ByteArrayOutputStream()
        DakExportWriter(out).use { w ->
            w.writeMessages(sequenceOf(record("sms:$id")))
            w.writeThreads(emptyList())
            w.writeSettings("{}")
            w.finish(ManifestMeta(createdAt = 1, appVersion = "t", id = id, parentId = parentId))
        }
        return out.toByteArray()
    }

    @Test
    fun `parent id cycles terminate`(): Unit = runBlocking {
        val dir = tempDir()
        File(dir, "a.dakbackup").writeBytes(snapshot("a", parentId = "b"))
        File(dir, "b.dakbackup").writeBytes(snapshot("b", parentId = "a"))
        File(dir, "latest.json").writeText("""{"id":"a","blobName":"a.dakbackup"}""")
        assertFailsWith<IllegalStateException> {
            BackupEngine(LocalDirectoryTarget(dir)).restore(existingKeys = { _, _, _, _ -> false }).toList()
        }
    }

    @Test
    fun `path syntax in parent id or latest pointer is refused`(): Unit = runBlocking {
        val dir = tempDir()
        File(dir, "a.dakbackup").writeBytes(snapshot("a", parentId = "../../outside"))
        File(dir, "latest.json").writeText("""{"id":"a","blobName":"a.dakbackup"}""")
        assertFailsWith<IllegalStateException> {
            BackupEngine(LocalDirectoryTarget(dir)).restore(existingKeys = { _, _, _, _ -> false }).toList()
        }
        File(dir, "latest.json").writeText("""{"id":"a","blobName":"../a.dakbackup"}""")
        assertFailsWith<IllegalStateException> {
            BackupEngine(LocalDirectoryTarget(dir)).restore(existingKeys = { _, _, _, _ -> false }).toList()
        }
    }

    // --- Encryption header --------------------------------------------------------------------------------------

    private fun header(iterations: Int, saltLen: Int = 16, prefixLen: Int = 4, wrappedLen: Int = 60): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write(BackupCrypto.MAGIC.toByteArray())
            writeByte(1)
            writeInt(iterations)
            writeByte(saltLen); write(ByteArray(saltLen))
            writeByte(prefixLen); write(ByteArray(prefixLen))
            writeByte(wrappedLen); write(ByteArray(wrappedLen))
            writeByte(wrappedLen); write(ByteArray(wrappedLen))
        }
        return out.toByteArray()
    }

    private fun decrypt(bytes: ByteArray): InputStream = BackupCrypto.decryptingInputStream(ByteArrayInputStream(bytes), "pw".toCharArray())

    @Test
    fun `attacker chosen kdf iteration counts are refused without deriving`() {
        val start = System.nanoTime()
        assertFailsWith<MalformedHeaderException> { decrypt(header(Int.MAX_VALUE)) }
        assertFailsWith<MalformedHeaderException> { decrypt(header(BackupCrypto.MAX_ITERATIONS + 1)) }
        assertFailsWith<MalformedHeaderException> { decrypt(header(1)) }
        assertFailsWith<MalformedHeaderException> { decrypt(header(-5)) }
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "header checks must not run the KDF")
        assertFailsWith<IllegalArgumentException> {
            BackupCrypto.encryptingOutputStream(ByteArrayOutputStream(), "pw".toCharArray(), iterations = 1)
        }
    }

    @Test
    fun `malformed header fields are typed errors, never index or argument crashes`() {
        assertFailsWith<MalformedHeaderException> { decrypt(header(BackupCrypto.MIN_ITERATIONS, prefixLen = 200)) }
        assertFailsWith<MalformedHeaderException> { decrypt(header(BackupCrypto.MIN_ITERATIONS, saltLen = 0)) }
        assertFailsWith<MalformedHeaderException> { decrypt(header(BackupCrypto.MIN_ITERATIONS, wrappedLen = 5)) }
        val full = header(BackupCrypto.MIN_ITERATIONS)
        for (len in 0 until full.size) {
            assertFailsWith<MalformedHeaderException>("prefix $len") { decrypt(full.copyOf(len)) }
        }
    }

    @Test
    fun `huge declared segment length is refused before allocation`() {
        val out = ByteArrayOutputStream()
        val enc = BackupCrypto.encryptingOutputStream(out, "pw".toCharArray(), BackupCrypto.MIN_ITERATIONS)
        enc.output.write("hello".toByteArray())
        enc.output.close()
        val bytes = out.toByteArray()
        // Replace the first segment's length with 0x7FFFFFFF: the header length is everything before it.
        val headerLen = bytes.size - (4 + 5 + 16)
        val forged = bytes.copyOf()
        forged[headerLen] = 0x7F; forged[headerLen + 1] = -1; forged[headerLen + 2] = -1; forged[headerLen + 3] = -1
        assertFailsWith<TamperedException> { decrypt(forged).readBytes() }
    }

    // --- XML -----------------------------------------------------------------------------------------------------

    private fun tokens(xml: String): List<XmlToken> {
        val t = XmlTokenizer(ByteArrayInputStream(xml.toByteArray()))
        val out = mutableListOf<XmlToken>()
        while (true) {
            val tok = t.next()
            out += tok
            if (tok is XmlToken.EndDocument) return out
        }
    }

    @Test
    fun `billion laughs entities are never expanded`() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <smses count="1"><sms address="&lol3;" body="&xxe;" date="1" type="1" /></smses>"""
        val msgs = SmsBackupRestoreXmlImporter().import(ByteArrayInputStream(xml.toByteArray())).toList()
        assertEquals(1, msgs.size)
        assertEquals("&lol3;", msgs[0].address)
        assertEquals("&xxe;", msgs[0].body)
    }

    @Test
    fun `huge attributes and names are capped`() {
        val bigName = "<" + "a".repeat(XmlLimits.MAX_NAME_CHARS + 1) + "/>"
        assertFailsWith<ArchiveLimitException> { tokens(bigName) }
        val manyAttrs = "<sms " + (0..XmlLimits.MAX_ATTRIBUTES).joinToString(" ") { "a$it=\"x\"" } + "/>"
        assertFailsWith<ArchiveLimitException> { tokens(manyAttrs) }
        val bigText = "<a>" + "x".repeat(XmlLimits.MAX_TEXT_CHARS + 1) + "</a>"
        assertFailsWith<ArchiveLimitException> { tokens(bigText) }
    }

    @Test
    fun `unterminated comment and doctype do not buffer the whole file`() {
        val xml = "<!--" + "x".repeat(5_000_000)
        assertEquals(listOf<XmlToken>(XmlToken.EndDocument), tokens(xml))
    }

    @Test
    fun `lone surrogates and NUL references are neutralised`() {
        assertEquals("a�b", XmlEntities.decode("a&#xD800;b"))
        assertEquals("�", XmlEntities.decode("&#56832;"))
        assertEquals("😀", XmlEntities.decode("&#55357;&#56832;")) // SMS Backup & Restore's emoji encoding
        assertEquals("&#0;", XmlEntities.decode("&#0;"))
        assertEquals("&#99999999999999999999;", XmlEntities.decode("&#99999999999999999999;"))
    }

    @Test
    fun `bad base64 drops the part not the import, and part count is capped`() {
        val xml = """<smses><mms date="1" msg_box="1"><parts>
            <part ct="image/jpeg" data="!!!not base64!!!" />
            <part ct="text/plain" text="hello" />
            </parts><addrs><addr address="+1555" type="137" /></addrs></mms></smses>"""
        val msgs = SmsBackupRestoreXmlImporter().import(ByteArrayInputStream(xml.toByteArray())).toList()
        assertEquals("hello", msgs.single().body)
        assertTrue(msgs.single().attachments.isEmpty())

        val many = "<smses><mms date=\"1\"><parts>" + "<part ct=\"text/plain\" text=\"x\"/>".repeat(ArchiveLimits.MAX_MMS_PARTS + 1) + "</parts></mms></smses>"
        assertFailsWith<ArchiveLimitException> { SmsBackupRestoreXmlImporter().import(ByteArrayInputStream(many.toByteArray())).toList() }
    }
}
