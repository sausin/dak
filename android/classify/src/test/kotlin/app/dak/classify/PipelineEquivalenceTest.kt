package app.dak.classify

import app.dak.classify.bench.EnrichmentPath
import app.dak.classify.bench.SyntheticCorpus
import app.dak.core.model.Category
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Correctness proof for the indexing speed-ups: the template-hash cache ([TemplateCache]), the keyword prefilter
 * ([RulePrefilter]) and the precomputed Naive Bayes tables give exactly the results of the plain implementation,
 * over the 50k-message benchmark corpus and over digit-shuffled copies of it (which hit the cache with different
 * amounts, codes and accounts).
 */
class PipelineEquivalenceTest {

    private val templates = TemplateBundle.loadDefault()
    private val model = NaiveBayesModel.loadDefault()
    private val corpus = SyntheticCorpus.generate()

    private fun pipeline(cacheSize: Int, prefilter: Boolean, region: SenderRegion = SenderRegion.INDIA) = ClassifierPipeline(
        templates, model, contactLookup = EnrichmentPath.fakeContacts, regionFor = { region },
        cacheSize = cacheSize, prefilter = prefilter,
    )

    /** Same template, other digits: every ASCII digit replaced at random (lengths kept), plus in-vocabulary numbers. */
    private fun shuffledDigits(seed: Long): List<Triple<String, String, Int>> {
        val r = Random(seed)
        val vocabNumbers = listOf("500", "1000", "123456", "12", "2500", "4321", "10", "249")
        return corpus.map { m ->
            var body = String(CharArray(m.body.length) { i -> m.body[i].let { if (it in '0'..'9') '0' + r.nextInt(10) else it } })
            if (r.nextInt(8) == 0) body += " " + vocabNumbers[r.nextInt(vocabNumbers.size)]
            Triple(m.address, body, m.subId)
        }
    }

    @Test
    fun cachedAndPrefilteredResultsAreIdenticalToThePlainPipeline() = runBlocking {
        for (region in listOf(SenderRegion.INDIA, SenderRegion.UNKNOWN, SenderRegion.of("GB"))) {
            val plain = pipeline(cacheSize = 0, prefilter = false, region = region)
            val fast = pipeline(cacheSize = ClassifierPipeline.SUGGESTED_CACHE_SIZE, prefilter = true, region = region)
            // Full corpus plus a digit-shuffled copy of every other message for India; a slice of that for the other
            // regions (suite time: the whole classify suite should stay under a minute).
            val all = corpus.map { Triple(it.address, it.body, it.subId) } + shuffledDigits(1).filterIndexed { i, _ -> i % 2 == 0 }
            val inputs = if (region == SenderRegion.INDIA) all else all.filterIndexed { i, _ -> i % 8 == 0 }
            var differences = 0
            for ((address, body, subId) in inputs) {
                val expected = plain.classify(address, body, subId)
                val actual = fast.classify(address, body, subId)
                if (expected != actual) {
                    differences++
                    if (differences < 5) println("DIFF $address | $body\n  plain=$expected\n  fast =$actual")
                }
            }
            assertEquals(0, differences, "region $region")
            val (hits, misses) = fast.cacheCounters
            assertTrue(hits > 0, "cache should engage: hits=$hits misses=$misses")
            println("region $region: cache hits=$hits misses=$misses over ${inputs.size} classifications")
        }
    }

    /** The indexer enriches a batch on several threads: the whole path must give the same rows as one thread. */
    @Test
    fun parallelEnrichmentMatchesSequential() = runBlocking {
        val messages = corpus.take(10_000)
        val sequential = EnrichmentPath.create().let { path -> messages.map { path.enrich(it) } }
        val shared = EnrichmentPath.create()
        val parallel = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            messages.chunked(500).flatMap { batch ->
                batch.chunked(167).map { slice -> async { slice.map { shared.enrich(it) } } }.awaitAll().flatten()
            }
        }
        assertEquals(sequential, parallel)
    }

    @Test
    fun perMessagePartsAreNeverTakenFromTheCache() = runBlocking {
        val fast = pipeline(cacheSize = 64, prefilter = true)
        // 739184 / 285617 are not model tokens, so both bodies share one cache entry.
        val a = fast.classify("VM-AMAZON", "739184 is your OTP for login to Amazon. Do not share.", 1)
        val b = fast.classify("VM-AMAZON", "285617 is your OTP for login to Amazon. Do not share.", 1)
        assertEquals(1L to 1L, fast.cacheCounters)
        assertEquals(Category.OTP, b.category)
        assertEquals("739184", a.otp?.code)
        assertEquals("285617", b.otp?.code)
        // Same body from a saved contact and from an unknown number: the contact boost is per message.
        val body = "are you coming home for dinner at 9"
        val contact = "+919876543213".also { assertTrue(EnrichmentPath.fakeContacts(it)) }
        val stranger = "+14155550121".also { assertFalse(EnrichmentPath.fakeContacts(it)) }
        val c1 = fast.classify(contact, body, 1)
        val c2 = fast.classify(stranger, body, 1)
        val plain = pipeline(cacheSize = 0, prefilter = false)
        assertEquals(plain.classify(contact, body, 1), c1)
        assertEquals(plain.classify(stranger, body, 1), c2)
        assertNotEquals(c1.confidence, c2.confidence)
    }

    @Test
    fun cacheStaysOffWhenRulesCanTellDigitsApart() = runBlocking {
        val bundle = TemplateBundle.parseUnsigned(
            """{"version":1,"issuedAt":0,"rules":[{"id":"r","category":"OTP","pattern":"code 1\\d{3}","priority":1}]}""",
        )
        val p = ClassifierPipeline(bundle, model, regionFor = { SenderRegion.INDIA })
        assertEquals(Category.OTP, p.classify("VM-X", "code 1234", 1).category)
        assertEquals(Category.OTP, p.classify("VM-X", "code 1999", 1).category)
        assertTrue(p.classify("VM-X", "code 2234", 1).category != Category.OTP)
        assertEquals(0L to 0L, p.cacheCounters)
    }

    @Test
    fun digitBlindness() {
        assertTrue(TemplateCache.isDigitBlind(templates.rules.map { it.pattern }))
        assertTrue(TemplateCache.isDigitBlind(listOf("""\d{4,8}""", """\p{Nd}+""", """x{2,}""", """a{3}""")))
        for (p in listOf("otp 1", "[0-9]", """(\d)\1""", """(?<a>\d)\k<a>""", """\x30""", "\\u0030", "a{1,b}9")) {
            assertFalse(TemplateCache.isDigitBlind(listOf(p)), p)
        }
    }

    @Test
    fun longBodiesAreNotCached() {
        assertNull(TemplateCache.keyOf("", "x".repeat(TemplateCache.MAX_KEY_CHARS + 1)) { false })
        val k1 = TemplateCache.keyOf("c", "Rs 123 debited, 500 left") { it == "500" }
        val k2 = TemplateCache.keyOf("c", "Rs 987 debited, 500 left") { it == "500" }
        val k3 = TemplateCache.keyOf("c", "Rs 987 debited, 600 left") { it == "500" }
        assertEquals(k1, k2)
        assertNotEquals(k1, k3) // "500" is a model token; "600" is not, so the keys must differ
    }

    @Test
    fun ruleRegexesNeverMatchWhenThePrefilterSaysNo() {
        val prefilter = RulePrefilter(templates.rules)
        assertTrue(prefilter.gatedRuleCount >= templates.rules.size - 1, "gated ${prefilter.gatedRuleCount}")
        val regexes = templates.rules.associateWith { Regex(it.pattern, RegexOption.IGNORE_CASE) }
        var skipped = 0
        // Every other message: 25k bodies x every rule is plenty to catch a prefilter literal that is not required.
        val sample = corpus.filterIndexed { i, _ -> i % 2 == 0 }
        for (m in sample) {
            val hits = prefilter.scan(m.body)
            for ((rule, regex) in regexes) {
                if (!prefilter.mayMatch(rule, hits)) {
                    skipped++
                    assertFalse(regex.containsMatchIn(m.body), "${rule.id} skipped but matches: ${m.body}")
                }
            }
        }
        assertTrue(skipped > sample.size * 8, "prefilter should skip most rules, skipped $skipped")
    }

    /** The precomputed tables give bit-identical scores to the textbook loop they replaced. */
    @Test
    fun naiveBayesTablesAreBitIdentical() {
        val weights = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
            NaiveBayesWeights.serializer(),
            javaClass.getResourceAsStream("/app/dak/classify/model-weights.json")!!.bufferedReader().readText(),
        )
        for (m in corpus.take(20_000)) assertEquals(reference(weights, m.body), model.predict(m.body), m.body)
    }

    private fun reference(weights: NaiveBayesWeights, text: String): Map<Category, Float> {
        val totalDocs = weights.classes.values.sumOf { it.docCount }.coerceAtLeast(1)
        val tokens = Tokenizer.tokenize(text)
        val logScores = LinkedHashMap<Category, Double>()
        for (categoryName in weights.classes.keys) {
            val category = runCatching { Category.valueOf(categoryName) }.getOrNull() ?: continue
            val cw = weights.classes.getValue(categoryName)
            val prior = cw.docCount.toDouble() / totalDocs
            var score = ln(prior.coerceAtLeast(1e-9))
            val denom = (cw.totalTokens + weights.vocabSize).toDouble()
            for (token in tokens) score += ln(((cw.wordCounts[token] ?: 0) + 1).toDouble() / denom)
            logScores[category] = score
        }
        val max = logScores.values.max()
        val exps = logScores.mapValues { exp(it.value - max) }
        val sum = exps.values.sum().coerceAtLeast(1e-12)
        return exps.mapValues { (it.value / sum).toFloat() }
    }
}
