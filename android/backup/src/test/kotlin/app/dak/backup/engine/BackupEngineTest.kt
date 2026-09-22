package app.dak.backup.engine

import app.dak.backup.format.Hashing
import app.dak.backup.format.MessageRecord
import app.dak.core.model.MessageKind
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupEngineTest {

    private fun record(key: String, body: String, address: String = "123", date: Long = 1L) = MessageRecord(
        key = key, kind = MessageKind.SMS, threadId = 1, address = address, body = body, dateMillis = date,
    )

    private fun tempDir(): File = createTempDirectory("dak-backup-engine-test").toFile()

    @Test
    fun `full backup then restore yields every message when nothing exists yet`() = runBlocking {
        val target = LocalDirectoryTarget(tempDir())
        val engine = BackupEngine(target)
        val messages = listOf(record("sms:1", "hello"), record("sms:2", "world"))

        val result = engine.backup(messages.asSequence(), appVersion = "1.0")
        assertEquals(2, result.manifest.counts.messages)

        val restored = engine.restore(existingKeys = { _, _, _, _ -> false }).toList()
        assertEquals(messages.toSet(), restored.toSet())
    }

    @Test
    fun `restore never re-emits messages already present`() = runBlocking {
        val target = LocalDirectoryTarget(tempDir())
        val engine = BackupEngine(target)
        val messages = listOf(record("sms:1", "hello"), record("sms:2", "world"))
        engine.backup(messages.asSequence(), appVersion = "1.0")

        val helloBodyHash = Hashing.sha256Hex("hello")
        val restored = engine.restore(existingKeys = { kind, address, date, bodyHash ->
            kind == MessageKind.SMS && address == "123" && date == 1L && bodyHash == helloBodyHash
        }).toList()

        assertEquals(listOf("world"), restored.map { it.body })
    }

    @Test
    fun `incremental backup writes only the delta and restore merges the chain`() = runBlocking {
        val target = LocalDirectoryTarget(tempDir())
        val engine = BackupEngine(target)

        val gen1 = listOf(record("sms:1", "a"), record("sms:2", "b"), record("sms:3", "c"))
        val full = engine.backup(gen1.asSequence(), appVersion = "1.0")
        assertEquals(3, full.manifest.counts.messages)

        // sms:2 changes, sms:3 is deleted, sms:4 is new.
        val gen2 = listOf(record("sms:1", "a"), record("sms:2", "b-changed"), record("sms:4", "d"))
        val incremental = engine.backup(
            gen2.asSequence(),
            appVersion = "1.0",
            previousManifest = full.manifest,
            previousDigest = full.digest,
        )
        assertEquals(2, incremental.manifest.counts.messages) // only changed + added
        assertEquals(listOf("sms:3"), incremental.manifest.deletedKeys)
        assertEquals(full.manifest.id, incremental.manifest.parentId)

        val restored = engine.restore(existingKeys = { _, _, _, _ -> false }).toList().associateBy { it.key }
        assertEquals(setOf("sms:1", "sms:2", "sms:4"), restored.keys)
        assertEquals("b-changed", restored.getValue("sms:2").body)
    }

    @Test
    fun `attachments are only written once across full and incremental snapshots`() = runBlocking {
        val target = LocalDirectoryTarget(tempDir())
        val engine = BackupEngine(target)
        val sha = Hashing.sha256Hex("photo bytes")
        val withAttachment = record("sms:1", "see attached").copy(
            attachments = listOf(app.dak.backup.format.AttachmentRecord(mimeType = "image/jpeg", sha256 = sha)),
        )
        var fetchCount = 0
        val source: suspend (String) -> java.io.InputStream? = { hash -> fetchCount++; "photo bytes".byteInputStream() }

        val full = engine.backup(sequenceOf(withAttachment), appVersion = "1.0", attachmentSource = source)
        assertEquals(1, fetchCount)

        engine.backup(
            sequenceOf(withAttachment, record("sms:2", "another")),
            appVersion = "1.0",
            previousManifest = full.manifest,
            previousDigest = full.digest,
            knownAttachmentHashes = full.knownAttachmentHashes,
            attachmentSource = source,
        )
        assertEquals(1, fetchCount) // unchanged message + already-known attachment: not re-fetched
    }

    @Test
    fun `encrypted backups round trip with the passphrase`() = runBlocking {
        val target = LocalDirectoryTarget(tempDir())
        val engine = BackupEngine(target, BackupEncryption("correct horse".toCharArray(), iterations = 100))
        val messages = listOf(record("sms:1", "secret message"))
        val result = engine.backup(messages.asSequence(), appVersion = "1.0")
        assertTrue(result.recoveryCode != null)

        val restored = engine.restore(
            key = RestoreKey.Passphrase("correct horse".toCharArray()),
            existingKeys = { _, _, _, _ -> false },
        ).toList()
        assertEquals(listOf("secret message"), restored.map { it.body })
    }

    @Test
    fun `encrypted backup restore with recovery code works and wrong passphrase fails`(): Unit = runBlocking {
        val target = LocalDirectoryTarget(tempDir())
        val engine = BackupEngine(target, BackupEncryption("correct horse".toCharArray(), iterations = 100))
        val messages = listOf(record("sms:1", "secret message"))
        val result = engine.backup(messages.asSequence(), appVersion = "1.0")
        val recoveryCode = requireNotNull(result.recoveryCode)

        val viaRecovery = engine.restore(
            key = RestoreKey.RecoveryCode(recoveryCode),
            existingKeys = { _, _, _, _ -> false },
        ).toList()
        assertEquals(listOf("secret message"), viaRecovery.map { it.body })

        assertFailsWith<app.dak.backup.crypto.WrongPassphraseException> {
            engine.restore(
                key = RestoreKey.Passphrase("wrong passphrase".toCharArray()),
                existingKeys = { _, _, _, _ -> false },
            ).toList()
        }
    }
}
