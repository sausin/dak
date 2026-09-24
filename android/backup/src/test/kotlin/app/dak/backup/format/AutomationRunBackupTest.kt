package app.dak.backup.format

import app.dak.backup.crypto.BackupCrypto
import app.dak.backup.engine.BackupEncryption
import app.dak.backup.engine.BackupEngine
import app.dak.backup.engine.LocalDirectoryTarget
import app.dak.backup.engine.RestoreKey
import app.dak.core.model.MessageKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutomationRunBackupTest {

    private fun run(i: Int, at: Long = 1_700_000_000_000L + i, outcome: String = "SENT") = AutomationRunRecord(
        ruleId = "rule-${i % 3}",
        ruleName = "Forward bank OTPs",
        atMillis = at,
        messageKey = "sms:$i",
        conversationId = "c:$i",
        sourceLabel = "VM-HDFCBK",
        actionKind = "ForwardSms",
        destinationLabel = "Asha",
        destination = "+919800000000",
        outcome = outcome,
        reason = if (outcome == "SKIPPED") "cooldown" else null,
        textPreview = "OTP ******",
    )

    private fun message(i: Int) = MessageRecord(
        key = "sms:$i", kind = MessageKind.SMS, threadId = 1, address = "VM-HDFCBK", body = "b$i", dateMillis = i.toLong(),
    )

    @Test
    fun `runs round trip through the export format and are counted in the manifest`() {
        val out = ByteArrayOutputStream()
        val runs = (0 until 250).map { run(it) }
        DakExportWriter(out).use { w ->
            w.writeMessages(sequenceOf(message(1)))
            w.writeThreads(emptyList())
            w.writeSettings("{}")
            w.writeAutomationRuns(runs.asSequence())
            val manifest = w.finish(ManifestMeta(createdAt = 1, appVersion = "t", id = "s1"))
            assertEquals(250, manifest.counts.automationRuns)
            assertTrue(manifest.parts.any { it.name == "automation_runs.jsonl" })
        }
        val reader = DakExportReader(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, reader.readMessages().toList().size)
        assertEquals(runs, reader.automationRuns)
        assertEquals(250, reader.manifest?.counts?.automationRuns)
    }

    @Test
    fun `archives without run history still read, with zero runs`() {
        val out = ByteArrayOutputStream()
        DakExportWriter(out).use { w ->
            w.writeMessages(sequenceOf(message(1)))
            w.writeThreads(emptyList())
            w.writeSettings("{}")
            w.finish(ManifestMeta(createdAt = 1, appVersion = "t", id = "s1"))
        }
        val reader = DakExportReader(ByteArrayInputStream(out.toByteArray()))
        reader.readMessages().toList()
        assertTrue(reader.automationRuns.isEmpty())
        assertEquals(0, reader.manifest?.counts?.automationRuns)
    }

    @Test
    fun `a malformed history line is skipped and never stops the message restore`() {
        val bytes = zipOf(
            "messages/0000.jsonl" to (DakExportWriter.defaultJson.encodeToString(MessageRecord.serializer(), message(7)) + "\n"),
            "automation_runs.jsonl" to (
                "{not json\n" +
                    """{"ruleId":"r","ruleName":"n","atMillis":5,"actionKind":"Webhook","outcome":"FAILED","future":"field"}""" + "\n" +
                    """{"ruleName":"missing required fields"}""" + "\n"
                ),
        )
        val reader = DakExportReader(ByteArrayInputStream(bytes))
        assertEquals(listOf("sms:7"), reader.readMessages().map { it.key }.toList())
        assertEquals(1, reader.automationRuns.size)
        assertEquals("Webhook", reader.automationRuns.single().actionKind)
    }

    @Test
    fun `an overlong history line is refused like any oversized part`() {
        val longLine = "{\"ruleId\":\"" + "x".repeat(ArchiveLimits.MAX_AUTOMATION_RUN_LINE_CHARS + 10) + "\"}\n"
        val bytes = zipOf("automation_runs.jsonl" to longLine)
        assertFailsWith<ArchiveLimitException> { DakExportReader(ByteArrayInputStream(bytes)).readMessages().toList() }
    }

    @Test
    fun `restored string fields are cut to the field limit`() {
        val big = "y".repeat(ArchiveLimits.MAX_AUTOMATION_RUN_FIELD_CHARS + 500)
        val line = DakExportWriter.defaultJson.encodeToString(AutomationRunRecord.serializer(), run(1).copy(textPreview = big, ruleName = big))
        val reader = DakExportReader(ByteArrayInputStream(zipOf("automation_runs.jsonl" to line + "\n")))
        reader.readMessages().toList()
        val restored = reader.automationRuns.single()
        assertEquals(ArchiveLimits.MAX_AUTOMATION_RUN_FIELD_CHARS, restored.textPreview?.length)
        assertEquals(ArchiveLimits.MAX_AUTOMATION_RUN_FIELD_CHARS, restored.ruleName.length)
    }

    @Test
    fun `encrypted backup carries the run history and readExtras returns it with the settings`() = runBlocking {
        val target = LocalDirectoryTarget(createTempDirectory("dak-runs").toFile())
        val passphrase = "correct horse battery".toCharArray()
        val engine = BackupEngine(target, BackupEncryption(passphrase.copyOf(), iterations = BackupCrypto.MIN_ITERATIONS))
        val runs = (0 until 20).map { run(it) }
        val full = engine.backup(
            messages = sequenceOf(message(1)),
            settingsJson = """{"a":"b"}""",
            automationRuns = runs.asSequence(),
            appVersion = "t",
        )
        assertEquals(20, full.manifest.counts.automationRuns)
        // The history is a full copy in incremental snapshots too; the newest snapshot has everything.
        val more = runs + run(99)
        engine.backup(
            messages = sequenceOf(message(1), message(2)),
            settingsJson = """{"a":"c"}""",
            automationRuns = more.asSequence(),
            appVersion = "t",
            previousManifest = full.manifest,
            previousDigest = full.digest,
        )
        val extras = BackupEngine(target).readExtras(RestoreKey.Passphrase(passphrase.copyOf()))
        assertEquals("""{"a":"c"}""", extras.settingsJson)
        assertEquals(more, extras.automationRuns)
        // Messages restore exactly as before.
        val restored = BackupEngine(target).restore(RestoreKey.Passphrase(passphrase.copyOf()), existingKeys = { _, _, _, _ -> false }).toList()
        assertEquals(setOf("sms:1", "sms:2"), restored.map { it.key }.toSet())
    }

    @Test
    fun `no history means no entry in the snapshot`() = runBlocking {
        val target = LocalDirectoryTarget(createTempDirectory("dak-runs-empty").toFile())
        val result = BackupEngine(target).backup(sequenceOf(message(1)), appVersion = "t")
        assertTrue(result.manifest.parts.none { it.name == "automation_runs.jsonl" })
        assertTrue(BackupEngine(target).readExtras().automationRuns.isEmpty())
    }

    @Test
    fun `restore plan skips rows already present and restoring twice adds nothing`() {
        val now = 1_800_000_000_000L
        val incoming = (0 until 10).map { run(it) }
        val first = AutomationRunRestore.plan(incoming, existingKeys = emptySet(), nowMillis = now)
        assertEquals(10, first.size)
        val again = AutomationRunRestore.plan(incoming, existingKeys = first.map { it.dedupeKey() }.toSet(), nowMillis = now)
        assertTrue(again.isEmpty())
        val partial = AutomationRunRestore.plan(incoming, existingKeys = setOf(run(3).dedupeKey()), nowMillis = now)
        assertEquals(9, partial.size)
    }

    @Test
    fun `restore plan drops invalid and future-dated rows, dedupes within the file, orders oldest first`() {
        val now = 1_800_000_000_000L
        val incoming = listOf(
            run(5, at = 500),
            run(1, at = 100),
            run(1, at = 100), // duplicate inside the file
            run(2, at = -1),
            run(3, at = now + ArchiveLimits.MAX_AUTOMATION_RUN_CLOCK_SKEW_MILLIS + 1),
            run(4, at = 200).copy(ruleId = " "),
            run(6, at = 300).copy(outcome = ""),
        )
        val plan = AutomationRunRestore.plan(incoming, emptySet(), now)
        assertEquals(listOf(100L, 500L), plan.map { it.atMillis })
    }

    @Test
    fun `restore plan keeps the newest rows when over the cap`() {
        val max = ArchiveLimits.MAX_AUTOMATION_RUNS
        val incoming = (0 until max + 5).map { i -> run(i, at = i.toLong()).copy(ruleId = "r$i") }
        val plan = AutomationRunRestore.plan(incoming, emptySet(), nowMillis = Long.MAX_VALUE / 2)
        assertEquals(max, plan.size)
        assertEquals(5L, plan.first().atMillis)
        assertEquals((max + 4).toLong(), plan.last().atMillis)
    }

    @Test
    fun `restore plan only produces log rows (the record type has no rule definition to recreate)`() {
        // Structural guard: a run record carries no rule JSON, trigger or enabled flag, so a restore cannot recreate
        // or re-enable a rule, and the plan is data only (nothing is executed).
        val fields = AutomationRunRecord.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }
        assertTrue(fields.none { it in setOf("json", "enabled", "trigger", "conditions", "actions") }, "$fields")
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
