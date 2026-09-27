package app.dak.premium.consent

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
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
            """[{"flow":"webhooks","granted":true,"atMillis":1700000000000,"disclosureVersion":2,""" +
                """"disclosureHash":"${Disclosures.webhooks.textHash}","source":"settings","language":"en"}]""",
            storage.read(),
        )
        // And a file written by an older build (no language, the version-1 English hash, extra future fields) still
        // reads and still counts: that text says what version 2 says.
        val legacy = """[{"flow":"ai_search","granted":true,"atMillis":5,"disclosureVersion":1,""" +
            """"disclosureHash":"$LEGACY_AI_V1","source":"privacy","futureField":42}]"""
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
            DataFlow.CLOUD_CLASSIFICATION to (3 to GOLDEN_CLOUD),
            DataFlow.WEBHOOKS to (2 to GOLDEN_WEBHOOKS),
            DataFlow.WEB_RELAY to (2 to GOLDEN_RELAY),
            DataFlow.AI_SEARCH to (2 to GOLDEN_AI),
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
            base.copy(language = "hi"),
            // Moving an item between the lists must also change the hash.
            base.copy(whatIsSent = base.whatIsSent + base.whatIsNotSent, whatIsNotSent = emptyList()),
        )
        val hashes = edits.map { it.textHash }.toSet()
        assertEquals(edits.size, hashes.size)
        assertFalse(base.textHash in hashes)
    }

    /**
     * Pins every translation's version and hash the same way. A translation edit changes its hash, so users who
     * allowed it in that language are asked again: only change one with a reviewed text, then update the golden.
     */
    @Test
    fun `translated disclosure changes are pinned too`() {
        val golden = mapOf(
            "hi" to listOf(
                DataFlow.CLOUD_CLASSIFICATION to "c59a03d8e8ab1cf230bf24eff004f5b2427e722a76b0eb2935152ea8b27c5f64",
                DataFlow.WEBHOOKS to "927c34b270d72882f8796e19ecfbb580653cf5e775c9fbc8c4a7fe0cfa7d9cc4",
                DataFlow.WEB_RELAY to "f4df7d4f0dc795fbfeae85f6bcbc9b4d2c81822a4c3625dd7bdf3aa705e2b85d",
                DataFlow.AI_SEARCH to "9f53700bae5bf0f616ef210d480efe51f7bbeec4a551d236fb5646965b10fa75",
            ),
            "es" to listOf(
                DataFlow.CLOUD_CLASSIFICATION to "0078bdca9484beacbfee34d80e4085752db41a21a666ed5595bc3472c0fce533",
                DataFlow.WEBHOOKS to "92fba3470926040d8dae0cf54f4f779c8f8b989c4ad734a6f9f8b0fede95e929",
                DataFlow.WEB_RELAY to "bc037b61ec9de1cfb3d94d2a2b8aeb16d955435c6e77a351dd8982a35efd032c",
                DataFlow.AI_SEARCH to "99aac42660a644ad3df43f9e026a47025daee4f2e28d8779b3ba29852df75522",
            ),
            "fr" to listOf(
                DataFlow.CLOUD_CLASSIFICATION to "db204bd26ec9975c4c027117a8b5ac5ac0bb3d14ae78705f34015bafe2e50f9f",
                DataFlow.WEBHOOKS to "ab1207d9cf2d0e9682d795444b31264bdd5be42b498c2553c352da8dbbc95966",
                DataFlow.WEB_RELAY to "cf23b1de035a35a293b9a6ac8757e6dc6f0ad7241a296e88b2bba7ef6c5320c8",
                DataFlow.AI_SEARCH to "36f3bb189ed1879de7a16121a283f7ce67bf3e8d7eb294f07dab316bcf885ced",
            ),
        )
        assertEquals(Disclosures.TRANSLATED_LANGUAGES.toSet(), golden.keys)
        for ((lang, flows) in golden) {
            assertEquals(DataFlow.entries.toSet(), flows.map { it.first }.toSet(), lang)
            for ((flow, hash) in flows) {
                val d = Disclosures.forFlow(flow, lang)
                assertEquals(lang, d.language, "$lang $flow: translation missing or made for another version")
                assertEquals(Disclosures.forFlow(flow).version, d.version)
                assertEquals(hash, d.textHash, "$lang $flow: text changed? use a reviewed translation and update this golden")
            }
        }
    }

    /**
     * The English wording of the current versions is exactly the text of the versions before them: the bump only added
     * the language to the canonical text. This is what lets [Disclosures.EQUIVALENT_EARLIER] keep old consents valid.
     */
    @Test
    fun `the current English text is the earlier text, so earlier consents still count`() {
        for (earlier in Disclosures.EQUIVALENT_EARLIER) {
            val d = Disclosures.forFlow(earlier.flow)
            assertEquals(d.version, earlier.sameAsVersion, "${earlier.flow}: a new version retires the equivalence")
            assertEquals(earlier.hash, sha256(earlierCanonicalText(d.copy(version = earlier.version))), "${earlier.flow}")
        }
        assertEquals(LEGACY_AI_V1, Disclosures.EQUIVALENT_EARLIER.first { it.flow == DataFlow.AI_SEARCH }.hash)
        // A record of the earlier version whose hash is not the earlier text does not count.
        val forged = """[{"flow":"webhooks","granted":true,"atMillis":1,"disclosureVersion":1,"disclosureHash":"${"0".repeat(64)}","source":"s"}]"""
        assertFalse(ConsentLedger(InMemoryConsentStorage(forged), clock).isGranted(DataFlow.WEBHOOKS))
    }

    @Test
    fun `a consent given in one language stays valid when the app language changes`() {
        val storage = InMemoryConsentStorage()
        var language = "hi"
        val ledger = ConsentLedger(storage, clock, displayLanguage = { language })
        val record = ledger.grant(DataFlow.WEB_RELAY, "privacy")
        assertEquals("hi", record.language)
        assertEquals(Disclosures.forFlow(DataFlow.WEB_RELAY, "hi").textHash, record.disclosureHash)
        assertTrue(record.disclosureHash != Disclosures.webRelay.textHash, "the Hindi text was recorded, not the English")
        language = "fr"
        assertTrue(ledger.isGranted(DataFlow.WEB_RELAY))
        assertTrue(ConsentLedger(storage, clock).isGranted(DataFlow.WEB_RELAY), "and after a restart")
        // A region or an unknown language falls back sensibly.
        assertEquals("fr", ledger.disclosure(DataFlow.WEB_RELAY, "fr-CA").language)
        assertEquals("en", ledger.disclosure(DataFlow.WEB_RELAY, "de").language)
        assertEquals("en", ledger.grant(DataFlow.AI_SEARCH, "privacy", language = "de").language)
    }

    @Test
    fun `a translation made for an older version is not shown and does not validate a consent`() {
        val storage = InMemoryConsentStorage()
        ConsentLedger(storage, clock, displayLanguage = { "es" }).grant(DataFlow.WEBHOOKS, "privacy")
        val next = Disclosures.webhooks.copy(version = Disclosures.webhooks.version + 1)
        val ledger = ConsentLedger(storage, clock, disclosures = { if (it == DataFlow.WEBHOOKS) next else Disclosures.forFlow(it) })
        // The Spanish text is of the previous version: English is shown, and the Spanish consent no longer counts.
        assertEquals("en", ledger.disclosure(DataFlow.WEBHOOKS, "es").language)
        assertFalse(ledger.isGranted(DataFlow.WEBHOOKS))
    }

    @Test
    fun `a record naming a language whose text changed does not count`() {
        val hi = Disclosures.forFlow(DataFlow.AI_SEARCH, "hi")
        val raw = """[{"flow":"ai_search","granted":true,"atMillis":1,"disclosureVersion":${hi.version},""" +
            """"disclosureHash":"${hi.textHash}","source":"s","language":"hi"}]"""
        assertTrue(ConsentLedger(InMemoryConsentStorage(raw), clock).isGranted(DataFlow.AI_SEARCH))
        // The same hash claimed for English (or the English hash claimed for Hindi) is not the text that was shown.
        assertFalse(ConsentLedger(InMemoryConsentStorage(raw.replace("\"language\":\"hi\"", "\"language\":\"en\"")), clock).isGranted(DataFlow.AI_SEARCH))
        val englishHashAsHindi = raw.replace(hi.textHash, Disclosures.aiSearch.textHash)
        assertFalse(ConsentLedger(InMemoryConsentStorage(englishHashAsHindi), clock).isGranted(DataFlow.AI_SEARCH))
    }

    /** [Disclosure.canonicalText] as builds before translations wrote it (no language line). */
    private fun earlierCanonicalText(d: Disclosure): String = buildString {
        append(d.flow.id).append('\n').append(d.version).append('\n').append(d.title).append('\n')
        d.whatIsSent.forEach { append("sent: ").append(it).append('\n') }
        d.whatIsNotSent.forEach { append("not sent: ").append(it).append('\n') }
        append("to: ").append(d.sentTo).append('\n')
        append("why: ").append(d.why).append('\n')
        append("when: ").append(d.whenSent).append('\n')
        append("retention: ").append(d.retention).append('\n')
        append("turn off: ").append(d.howToTurnOff)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private companion object {
        const val GOLDEN_CLOUD = "d2caa21987ff90cca69b22d0092a09a3e1ec3d759caa788b2133d8d9b2fe0e78"
        const val GOLDEN_WEBHOOKS = "73209eccf146914dd0a8c98e88657e3193d3d036c6f04d9897031fbdcd15e04e"
        const val GOLDEN_RELAY = "30a9ff10e99a1abda9d3a9dcdb4e664c05d426abc02dd6ca38911c54750c8078"
        const val GOLDEN_AI = "595efe4f8fa83d97b2e226d6303766527e46b633f06e80f2b4dced3d1aa0d46e"

        /** The AI search disclosure hash (version 1) recorded by builds before translations. */
        const val LEGACY_AI_V1 = "5c0bf2f65a5afcb5a42080b2d3c93479a8709443fe3ca6eef8bcd0864dd0f3ad"
    }
}
