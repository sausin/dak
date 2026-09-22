package app.dak.backup.engine

import app.dak.backup.format.MessageRecord
import app.dak.core.model.MessageKind
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupPlannerTest {
    private fun record(key: String, body: String) = MessageRecord(
        key = key, kind = MessageKind.SMS, threadId = 1, address = "123", body = body, dateMillis = 1L,
    )

    @Test
    fun `first backup marks everything as added`() {
        val current = sequenceOf(record("sms:1", "a"), record("sms:2", "b"))
        val plan = BackupPlanner.plan(emptyMap(), current)
        assertEquals(2, plan.added.size)
        assertEquals(0, plan.changed.size)
        assertEquals(0, plan.deletedKeys.size)
    }

    @Test
    fun `detects added, changed, unchanged and deleted`() {
        val previous = BackupPlanner.digestOf(sequenceOf(record("sms:1", "a"), record("sms:2", "b"), record("sms:3", "c")))
        val current = sequenceOf(
            record("sms:1", "a"), // unchanged
            record("sms:2", "b-changed"), // changed
            record("sms:4", "new"), // added
            // sms:3 is gone -> deleted
        )
        val plan = BackupPlanner.plan(previous, current)
        assertEquals(listOf("sms:4"), plan.added.map { it.key })
        assertEquals(listOf("sms:2"), plan.changed.map { it.key })
        assertEquals(listOf("sms:3"), plan.deletedKeys)
        assertEquals(1, plan.unchangedCount)
    }

    @Test
    fun `no changes yields an empty plan`() {
        val messages = sequenceOf(record("sms:1", "a"), record("sms:2", "b"))
        val digest = BackupPlanner.digestOf(messages)
        val plan = BackupPlanner.plan(digest, sequenceOf(record("sms:1", "a"), record("sms:2", "b")))
        assertEquals(true, plan.isEmpty)
    }

    @Test
    fun `content hash changes when enrichment changes, not just body`() {
        val base = record("sms:1", "a")
        val starred = base.copy(starred = true)
        assertEquals(false, base.contentHash() == starred.contentHash())
    }
}
