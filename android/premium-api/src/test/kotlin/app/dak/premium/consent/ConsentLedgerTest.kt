package app.dak.premium.consent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentLedgerTest {
    private var now = 1_000L
    private val clock = { now }

    @Test
    fun `every flow is off by default`() {
        val ledger = ConsentLedger(InMemoryConsentStorage(), clock)
        DataFlow.entries.forEach { assertFalse(ledger.isGranted(it), "$it") }
        assertTrue(ledger.granted().isEmpty())
    }

    @Test
    fun `grant records timestamp, disclosure version and text hash`() {
        val ledger = ConsentLedger(InMemoryConsentStorage(), clock)
        now = 42L
        val record = ledger.grant(DataFlow.CLOUD_CLASSIFICATION, "settings")
        assertTrue(ledger.isGranted(DataFlow.CLOUD_CLASSIFICATION))
        assertFalse(ledger.isGranted(DataFlow.WEBHOOKS))
        assertEquals("cloud_classification", record.flow)
        assertEquals(42L, record.atMillis)
        assertEquals(Disclosures.cloudClassification.version, record.disclosureVersion)
        assertEquals(Disclosures.cloudClassification.textHash, record.disclosureHash)
        assertEquals(64, record.disclosureHash.length)
        assertEquals("settings", record.source)
    }

    @Test
    fun `withdrawal turns the flow off and is recorded, withdrawing again writes nothing`() {
        val ledger = ConsentLedger(InMemoryConsentStorage(), clock)
        ledger.grant(DataFlow.WEBHOOKS, "privacy")
        now = 2_000L
        val withdrawal = ledger.withdraw(DataFlow.WEBHOOKS, "privacy")
        assertFalse(ledger.isGranted(DataFlow.WEBHOOKS))
        assertEquals(false, withdrawal?.granted)
        assertEquals(2, ledger.records.value.size)
        assertNull(ledger.withdraw(DataFlow.WEBHOOKS, "privacy"))
        assertEquals(2, ledger.records.value.size)
    }

    @Test
    fun `decline keeps the flow off but leaves an audit record`() {
        val ledger = ConsentLedger(InMemoryConsentStorage(), clock)
        ledger.decline(DataFlow.AI_SEARCH, "privacy")
        assertFalse(ledger.isGranted(DataFlow.AI_SEARCH))
        assertEquals(1, ledger.records.value.size)
    }

    @Test
    fun `records survive a restart through storage`() {
        val storage = InMemoryConsentStorage()
        ConsentLedger(storage, clock).grant(DataFlow.WEB_RELAY, "privacy")
        val reopened = ConsentLedger(storage, clock)
        assertTrue(reopened.isGranted(DataFlow.WEB_RELAY))
        assertEquals(1, reopened.records.value.size)
    }

    @Test
    fun `a changed disclosure text invalidates an older consent`() {
        val storage = InMemoryConsentStorage()
        ConsentLedger(storage, clock).grant(DataFlow.CLOUD_CLASSIFICATION, "settings")
        val v2 = Disclosures.cloudClassification.copy(version = 2, retention = "Kept for 30 days.")
        val ledger = ConsentLedger(storage, clock, disclosures = { if (it == DataFlow.CLOUD_CLASSIFICATION) v2 else Disclosures.forFlow(it) })
        assertFalse(ledger.isGranted(DataFlow.CLOUD_CLASSIFICATION))
        ledger.grant(DataFlow.CLOUD_CLASSIFICATION, "settings")
        assertTrue(ledger.isGranted(DataFlow.CLOUD_CLASSIFICATION))
    }

    @Test
    fun `a text edit without a version bump also invalidates consent (hash check)`() {
        val storage = InMemoryConsentStorage()
        ConsentLedger(storage, clock).grant(DataFlow.WEBHOOKS, "settings")
        val edited = Disclosures.webhooks.copy(sentTo = "Somewhere else")
        assertNotEquals(Disclosures.webhooks.textHash, edited.textHash)
        val ledger = ConsentLedger(storage, clock, disclosures = { if (it == DataFlow.WEBHOOKS) edited else Disclosures.forFlow(it) })
        assertFalse(ledger.isGranted(DataFlow.WEBHOOKS))
    }

    @Test
    fun `corrupt storage fails closed`() {
        val ledger = ConsentLedger(InMemoryConsentStorage("{not json"), clock)
        DataFlow.entries.forEach { assertFalse(ledger.isGranted(it)) }
    }

    @Test
    fun `clear forgets every record`() {
        val storage = InMemoryConsentStorage()
        val ledger = ConsentLedger(storage, clock)
        ledger.grant(DataFlow.WEBHOOKS, "privacy")
        ledger.clear()
        assertFalse(ledger.isGranted(DataFlow.WEBHOOKS))
        assertFalse(ConsentLedger(storage, clock).isGranted(DataFlow.WEBHOOKS))
    }

    @Test
    fun `trimming keeps the deciding record of every flow`() {
        val ledger = ConsentLedger(InMemoryConsentStorage(), clock)
        ledger.grant(DataFlow.WEB_RELAY, "privacy")
        repeat(ConsentLedger.MAX_RECORDS + 50) { i ->
            now++
            if (i % 2 == 0) ledger.grant(DataFlow.WEBHOOKS, "privacy") else ledger.withdraw(DataFlow.WEBHOOKS, "privacy")
        }
        assertEquals(ConsentLedger.MAX_RECORDS, ledger.records.value.size)
        assertTrue(ledger.isGranted(DataFlow.WEB_RELAY))
    }

    @Test
    fun `export is readable JSON with every record`() {
        val ledger = ConsentLedger(InMemoryConsentStorage(), clock)
        ledger.grant(DataFlow.AI_SEARCH, "privacy")
        val json = ledger.exportJson()
        assertTrue("\"ai_search\"" in json)
        assertTrue("\"disclosureHash\"" in json)
    }

    @Test
    fun `flow ids are stable and unique`() {
        assertEquals(listOf("cloud_classification", "webhooks", "web_relay", "ai_search"), DataFlow.entries.map { it.id })
        DataFlow.entries.forEach { assertEquals(it, DataFlow.byId(it.id)) }
        assertEquals(DataFlow.entries.size, Disclosures.all.map { it.flow }.toSet().size)
    }

    @Test
    fun `every disclosure says what is sent, to whom, why and how to turn it off`() {
        Disclosures.all.forEach { d ->
            assertTrue(d.whatIsSent.isNotEmpty() && d.whatIsSent.all { it.isNotBlank() }, "${d.flow}")
            assertTrue(d.sentTo.isNotBlank() && d.why.isNotBlank() && d.howToTurnOff.isNotBlank() && d.retention.isNotBlank(), "${d.flow}")
            assertTrue("Settings" in d.howToTurnOff, "${d.flow} must point to where it is turned off")
        }
    }
}
