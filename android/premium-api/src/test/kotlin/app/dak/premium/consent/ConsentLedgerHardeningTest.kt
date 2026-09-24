package app.dak.premium.consent

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Edge cases of the consent ledger: persisted format, fail-closed storage, trimming, and disclosure pinning. */
class ConsentLedgerHardeningTest {
    private var now = 1_000L
    private val clock = { now }

    /** Storage whose writes can be made to fail, like a full disk under AtomicFile. */
    private class FlakyStorage(var failWrites: Boolean = false, var failReads: Boolean = false) : ConsentStorage {
        var value: String? = null
        override fun read(): String? = if (failReads) throw IOException("read") else value
        override fun write(json: String) {
            if (failWrites) throw IOException("disk full")
            value = json
        }
    }

    @Test
    fun `record JSON on disk is pinned`() {
        val storage = InMemoryConsentStorage()
        now = 1_700_000_000_000
        val ledger = ConsentLedger(storage, clock)
        ledger.grant(DataFlow.WEBHOOKS, "settings")
        assertEquals(
            """[{"flow":"webhooks","granted":true,"atMillis":1700000000000,"disclosureVersion":1,""" +
                """"disclosureHash":"${Disclosures.webhooks.textHash}","source":"settings"}]""",
            storage.read(),
        )
        // And a file written by an older build (or with extra future fields) still reads.
        val legacy = """[{"flow":"ai_search","granted":true,"atMillis":5,"disclosureVersion":1,""" +
            """"disclosureHash":"${Disclosures.aiSearch.textHash}","source":"privacy","futureField":42}]"""
        assertTrue(ConsentLedger(InMemoryConsentStorage(legacy), clock).isGranted(DataFlow.AI_SEARCH))
    }

    @Test
    fun `an unreadable store fails closed`() {
        val storage = FlakyStorage(failReads = true)
        val ledger = ConsentLedger(storage, clock)
        DataFlow.entries.forEach { assertFalse(ledger.isGranted(it)) }
        // Valid JSON of the wrong shape is also "nothing granted", not a crash.
        for (raw in listOf("", "null", "{}", "[1,2]", """[{"flow":"webhooks"}]""", "[]")) {
            val l = ConsentLedger(InMemoryConsentStorage(raw), clock)
            assertTrue(l.granted().isEmpty(), raw)
        }
    }

    @Test
    fun `a record for an unknown flow id is ignored`() {
        val raw = """[{"flow":"telemetry","granted":true,"atMillis":1,"disclosureVersion":1,"disclosureHash":"x","source":"s"}]"""
        val ledger = ConsentLedger(InMemoryConsentStorage(raw), clock)
        assertTrue(ledger.granted().isEmpty())
        assertEquals(1, ledger.records.value.size, "kept as evidence")
    }

    @Test
    fun `a grant that cannot be saved does not take effect`() {
        val storage = FlakyStorage(failWrites = true)
        val ledger = ConsentLedger(storage, clock)
        assertFailsWith<IOException> { ledger.grant(DataFlow.WEBHOOKS, "settings") }
        assertFalse(ledger.isGranted(DataFlow.WEBHOOKS))
        assertTrue(ledger.records.value.isEmpty())
    }

    @Test
    fun `a withdrawal that cannot be saved still stops the flow at once`() {
        val storage = FlakyStorage()
        val ledger = ConsentLedger(storage, clock)
        ledger.grant(DataFlow.WEB_RELAY, "privacy")
        storage.failWrites = true
        assertFailsWith<IOException> { ledger.withdraw(DataFlow.WEB_RELAY, "privacy") }
        assertFalse(ledger.isGranted(DataFlow.WEB_RELAY), "withdrawal must fail closed")
    }

    @Test
    fun `clear that cannot be saved still forgets in memory`() {
        val storage = FlakyStorage()
        val ledger = ConsentLedger(storage, clock)
        ledger.grant(DataFlow.AI_SEARCH, "privacy")
        storage.failWrites = true
        assertFailsWith<IOException> { ledger.clear() }
        assertFalse(ledger.isGranted(DataFlow.AI_SEARCH))
    }

    @Test
    fun `decline after a grant turns the flow off`() {
        val ledger = ConsentLedger(InMemoryConsentStorage(), clock)
        ledger.grant(DataFlow.CLOUD_CLASSIFICATION, "settings")
        ledger.decline(DataFlow.CLOUD_CLASSIFICATION, "onboarding")
        assertFalse(ledger.isGranted(DataFlow.CLOUD_CLASSIFICATION))
    }

    @Test
    fun `regrant after withdrawal works and only the newest record decides`() {
        val ledger = ConsentLedger(InMemoryConsentStorage(), clock)
        ledger.grant(DataFlow.WEBHOOKS, "a")
        ledger.withdraw(DataFlow.WEBHOOKS, "b")
        ledger.grant(DataFlow.WEBHOOKS, "c")
        assertTrue(ledger.isGranted(DataFlow.WEBHOOKS))
        assertEquals(listOf(true, false, true), ledger.records.value.map { it.granted })
        assertEquals(setOf(DataFlow.WEBHOOKS), ledger.granted())
    }

    @Test
    fun `source is truncated so a caller cannot bloat the ledger`() {
        val ledger = ConsentLedger(InMemoryConsentStorage(), clock)
        val r = ledger.grant(DataFlow.WEBHOOKS, "x".repeat(1000))
        assertEquals(32, r.source.length)
    }

    @Test
    fun `a stored grant with the right hash but another version does not count`() {
        val d = Disclosures.webhooks
        val raw = """[{"flow":"webhooks","granted":true,"atMillis":1,"disclosureVersion":${d.version - 1},""" +
            """"disclosureHash":"${d.textHash}","source":"s"}]"""
        assertFalse(ConsentLedger(InMemoryConsentStorage(raw), clock).isGranted(DataFlow.WEBHOOKS))
        val ok = raw.replace("\"disclosureVersion\":${d.version - 1}", "\"disclosureVersion\":${d.version}")
        assertTrue(ConsentLedger(InMemoryConsentStorage(ok), clock).isGranted(DataFlow.WEBHOOKS))
    }

    @Test
    fun `trimming keeps the deciding record of every flow across a restart`() {
        val storage = InMemoryConsentStorage()
        val ledger = ConsentLedger(storage, clock)
        ledger.grant(DataFlow.WEB_RELAY, "first")
        ledger.decline(DataFlow.AI_SEARCH, "first")
        repeat(ConsentLedger.MAX_RECORDS * 2) { i ->
            now++
            if (i % 2 == 0) ledger.grant(DataFlow.WEBHOOKS, "loop") else ledger.withdraw(DataFlow.WEBHOOKS, "loop")
        }
        val reopened = ConsentLedger(storage, clock)
        assertEquals(ConsentLedger.MAX_RECORDS, reopened.records.value.size)
        assertTrue(reopened.isGranted(DataFlow.WEB_RELAY))
        assertFalse(reopened.isGranted(DataFlow.WEBHOOKS), "the loop ended with a withdrawal")
        assertTrue(reopened.records.value.any { it.flow == "ai_search" }, "the decline is still evidence")
        // Chronological order is preserved after trimming.
        val times = reopened.records.value.map { it.atMillis }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `concurrent grants lose no records`() {
        val ledger = ConsentLedger(InMemoryConsentStorage(), clock)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val flows = DataFlow.entries
        repeat(200) { i ->
            pool.execute {
                start.await()
                ledger.grant(flows[i % flows.size], "t$i")
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(200, ledger.records.value.size)
        assertEquals(flows.toSet(), ledger.granted())
    }

    @Test
    fun `export round-trips to the same records`() {
        val ledger = ConsentLedger(InMemoryConsentStorage(), clock)
        ledger.grant(DataFlow.AI_SEARCH, "privacy")
        ledger.withdraw(DataFlow.AI_SEARCH, "privacy")
        val back = Json.decodeFromString(ListSerializer(ConsentRecord.serializer()), ledger.exportJson())
        assertEquals(ledger.records.value, back)
    }

    /**
     * Pins every disclosure's version together with its text hash. If you edited a disclosure, this fails on
     * purpose: bump [Disclosure.version] (users must see and accept the new text), then update the golden here.
     */
    @Test
    fun `disclosure text changes must come with a version bump`() {
        val golden = mapOf(
            DataFlow.CLOUD_CLASSIFICATION to (2 to GOLDEN_CLOUD),
            DataFlow.WEBHOOKS to (1 to GOLDEN_WEBHOOKS),
            DataFlow.WEB_RELAY to (1 to GOLDEN_RELAY),
            DataFlow.AI_SEARCH to (1 to GOLDEN_AI),
        )
        assertEquals(DataFlow.entries.toSet(), golden.keys, "every flow needs a pinned disclosure")
        val actual = DataFlow.entries.joinToString("\n") { "$it ${Disclosures.forFlow(it).version} ${Disclosures.forFlow(it).textHash}" }
        if (golden.any { (f, p) -> Disclosures.forFlow(f).let { it.version to it.textHash } != p }) error("disclosures changed (bump the version of each edited one, then update the goldens):\n$actual")
        for ((flow, pair) in golden) {
            val d = Disclosures.forFlow(flow)
            assertEquals(pair.first to pair.second, d.version to d.textHash, "$flow: text changed? bump the version and update this golden")
            assertEquals(64, d.textHash.length)
            assertTrue(d.textHash.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun `canonical text covers every field so any edit changes the hash`() {
        val base = Disclosures.webRelay
        val edits = listOf(
            base.copy(title = base.title + "!"),
            base.copy(whatIsSent = base.whatIsSent + "more"),
            base.copy(whatIsNotSent = emptyList()),
            base.copy(why = "x"),
            base.copy(whenSent = "x"),
            base.copy(retention = "x"),
            base.copy(howToTurnOff = "x"),
            base.copy(version = 99),
            base.copy(flow = DataFlow.WEBHOOKS),
            // Moving an item between the lists must also change the hash.
            base.copy(whatIsSent = base.whatIsSent + base.whatIsNotSent, whatIsNotSent = emptyList()),
        )
        val hashes = edits.map { it.textHash }.toSet()
        assertEquals(edits.size, hashes.size)
        assertFalse(base.textHash in hashes)
    }

    private companion object {
        const val GOLDEN_CLOUD = "2e31a1655dbe17dedc9fd51006e00d4184d5bc64bfbcfb1df7d1074567c9cf23"
        const val GOLDEN_WEBHOOKS = "79631b8a562a8b42babad5e3b0980ff666932a16d7ca75c41188bfe4ac2afc35"
        const val GOLDEN_RELAY = "bbeac96d43b8e5dfae0ef34473ddad3813f505605dfdd9e6dad4c502b1d74b6d"
        const val GOLDEN_AI = "5c0bf2f65a5afcb5a42080b2d3c93479a8709443fe3ca6eef8bcd0864dd0f3ad"
    }
}
