package app.dak.backup.engine

import app.dak.backup.format.DakExportWriter
import app.dak.backup.format.ManifestKind
import app.dak.backup.format.ManifestMeta
import app.dak.backup.format.MessageRecord
import app.dak.core.model.MessageKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Incremental-chain edge cases: broken links, self references, long chains, deletes and re-adds. */
class BackupChainTest {

    private fun record(key: String, body: String, date: Long = 1L) =
        MessageRecord(key = key, kind = MessageKind.SMS, threadId = 1, address = "+1", body = body, dateMillis = date)

    private fun tempDir(): File = createTempDirectory("dak-chain-test").toFile()

    private val none: (MessageKind, String, Long, String) -> Boolean = { _, _, _, _ -> false }

    private fun snapshot(id: String, parentId: String?, messages: List<MessageRecord>, deleted: List<String> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        DakExportWriter(out).use { w ->
            w.writeMessages(messages.asSequence())
            w.writeThreads(emptyList())
            w.writeSettings("{}")
            w.finish(
                ManifestMeta(
                    createdAt = 1, appVersion = "t", id = id, parentId = parentId,
                    kind = if (parentId == null) ManifestKind.FULL else ManifestKind.INCREMENTAL, deletedKeys = deleted,
                ),
            )
        }
        return out.toByteArray()
    }

    private fun latest(dir: File, id: String) = File(dir, "latest.json").writeText("""{"id":"$id","blobName":"$id.dakbackup"}""")

    @Test
    fun `a missing parent fails the restore and emits nothing`(): Unit = runBlocking {
        val dir = tempDir()
        File(dir, "child.dakbackup").writeBytes(snapshot("child", parentId = "gone", messages = listOf(record("sms:1", "x"))))
        latest(dir, "child")
        val emitted = mutableListOf<MessageRecord>()
        val e = assertFailsWith<IllegalStateException> {
            BackupEngine(LocalDirectoryTarget(dir)).restore(existingKeys = none).collect { emitted += it }
        }
        assertTrue("gone" in (e.message ?: ""), e.message)
        assertTrue(emitted.isEmpty(), "a partial chain must not be half-restored")
    }

    @Test
    fun `a snapshot that names itself as parent is refused`(): Unit = runBlocking {
        val dir = tempDir()
        File(dir, "a.dakbackup").writeBytes(snapshot("a", parentId = "a", messages = emptyList()))
        latest(dir, "a")
        assertFailsWith<IllegalStateException> { BackupEngine(LocalDirectoryTarget(dir)).restore(existingKeys = none).toList() }
    }

    @Test
    fun `no latest pointer and no snapshot is a clear error`(): Unit = runBlocking {
        val e = assertFailsWith<IllegalStateException> { BackupEngine(LocalDirectoryTarget(tempDir())).restore(existingKeys = none).toList() }
        assertTrue("No backups" in (e.message ?: ""))
    }

    @Test
    fun `a three-link chain applies changes and deletions oldest to newest, and a re-add wins`(): Unit = runBlocking {
        val dir = tempDir()
        File(dir, "s1.dakbackup").writeBytes(snapshot("s1", null, listOf(record("sms:1", "one"), record("sms:2", "two"), record("sms:3", "three"))))
        File(dir, "s2.dakbackup").writeBytes(snapshot("s2", "s1", listOf(record("sms:2", "two v2")), deleted = listOf("sms:3")))
        File(dir, "s3.dakbackup").writeBytes(snapshot("s3", "s2", listOf(record("sms:3", "three again", date = 9), record("sms:4", "four")), deleted = listOf("sms:1")))
        latest(dir, "s3")
        val restored = BackupEngine(LocalDirectoryTarget(dir)).restore(existingKeys = none).toList().associate { it.key to it.body }
        assertEquals(mapOf("sms:2" to "two v2", "sms:3" to "three again", "sms:4" to "four"), restored)
        // Restoring from the middle of the chain ignores newer links.
        val mid = BackupEngine(LocalDirectoryTarget(dir)).restore(existingKeys = none, fromBlobName = "s2.dakbackup").toList().associate { it.key to it.body }
        assertEquals(mapOf("sms:1" to "one", "sms:2" to "two v2"), mid)
    }

    @Test
    fun `engine-written chains round trip across several generations`(): Unit = runBlocking {
        val target = LocalDirectoryTarget(tempDir())
        val engine = BackupEngine(target)
        var live = (1..20).associate { "sms:$it" to record("sms:$it", "body $it", date = it.toLong()) }
        var result = engine.backup(live.values.asSequence(), appVersion = "1", now = 1, id = "g0")
        for (gen in 1..5) {
            live = live - "sms:$gen" + ("sms:${100 + gen}" to record("sms:${100 + gen}", "new $gen", date = 100L + gen)) +
                ("sms:${10 + gen}" to record("sms:${10 + gen}", "edited $gen", date = 10L + gen))
            result = engine.backup(
                live.values.asSequence(), appVersion = "1", previousManifest = result.manifest, previousDigest = result.digest,
                knownAttachmentHashes = result.knownAttachmentHashes, now = gen.toLong() + 1, id = "g$gen",
            )
            assertEquals(ManifestKind.INCREMENTAL, result.manifest.kind)
            assertEquals("g${gen - 1}", result.manifest.parentId)
            assertEquals(live.keys, result.digest.keys, "digest covers every live message after gen $gen")
        }
        val restored = engine.restore(existingKeys = none).toList()
        assertEquals(live.values.toSet(), restored.toSet())
    }

    @Test
    fun `a user-renamed snapshot name with path syntax is refused`(): Unit = runBlocking {
        val engine = BackupEngine(LocalDirectoryTarget(tempDir()))
        for (bad in listOf("../x.dakbackup", "a/b.dakbackup", "a\\b.dakbackup", ".hidden.dakbackup", "a\u0000.dakbackup")) {
            assertFailsWith<IllegalStateException>(bad) { engine.restore(existingKeys = none, fromBlobName = bad).toList() }
            assertFailsWith<IllegalStateException>(bad) { engine.readExtras(fromBlobName = bad) }
        }
    }
}
