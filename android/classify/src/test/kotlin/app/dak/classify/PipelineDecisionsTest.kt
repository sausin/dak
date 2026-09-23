package app.dak.classify

import app.dak.core.model.Category
import app.dak.core.model.ClassifierSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Each decision of [ClassifierPipeline] in isolation, with a tiny unsigned bundle and a fixed model, so a failure
 * names the rule that changed rather than a shift in the bundled model's weights.
 */
class PipelineDecisionsTest {

    private val bundle = TemplateBundle.parseUnsigned(
        """
        {"version":1,"issuedAt":0,
         "senders":[{"header":"NEWBNK","brand":"New Bank","regions":["IN"]}],
         "rules":[
          {"id":"spam-kyc","category":"SPAM","pattern":"update your kyc","priority":90,"labels":["fraud-risk"]},
          {"id":"otp","category":"OTP","pattern":"\\botp\\b","priority":80},
          {"id":"txn","category":"TRANSACTION","pattern":"\\bdebited\\b","priority":50},
          {"id":"biz-paid","category":"TRANSACTION","pattern":"\\bi paid\\b","priority":40,"senderScope":"BUSINESS"},
          {"id":"private-alert","category":"SPAM","pattern":"\\bcredited to your a/c\\b","priority":95,"senderScope":"PRIVATE_NUMBER"}
         ]}
        """.trimIndent(),
    )

    /** A model that always says [category] with [p] and splits the rest evenly. */
    private fun model(category: Category, p: Float) = MessageModel {
        val rest = (1f - p) / (Category.entries.size - 1)
        Category.entries.associateWith { if (it == category) p else rest }
    }

    private fun pipeline(
        model: MessageModel = model(Category.PERSONAL, 0.9f),
        region: SenderRegion = SenderRegion.INDIA,
        contacts: Set<String> = emptySet(),
        cloud: CloudClassifier = NoCloudClassifier,
        threshold: Float = 0.55f,
    ) = ClassifierPipeline(bundle, model, cloud = cloud, contactLookup = { it in contacts }, threshold = threshold, regionFor = { region })

    private fun ClassifierPipeline.run(address: String, body: String) = runBlocking { classify(address, body, 1) }

    // --- DLT routes bound a template's spam verdict

    @Test
    fun `a spam rule on a registered route is the brand's own notice unless a link is risky`() {
        val p = pipeline()
        val onService = p.run("VM-NEWBNK-S", "Please update your KYC at the branch")
        assertEquals(Category.TRANSACTION, onService.category)
        assertFalse("fraud-risk" in onService.labels)
        assertTrue("dlt-service" in onService.labels)

        assertEquals(Category.PROMOTION, p.run("VM-NEWBNK-P", "Please update your KYC at the branch").category)

        // A look-alike / suspicious-TLD link keeps the spam verdict whatever the route.
        val risky = p.run("VM-NEWBNK-S", "Please update your KYC at http://newbank-kyc.top/verify")
        assertEquals(Category.SPAM, risky.category)
        assertTrue("fraud-risk" in risky.labels)

        // Without a DLT header (outside India, or an unknown number) the rule stands.
        assertEquals(Category.SPAM, pipeline(region = SenderRegion.of("GB")).run("VM-NEWBNK-S", "Please update your KYC").category)
    }

    @Test
    fun `a transaction rule on the promotional route is a promotion`() {
        assertEquals(Category.PROMOTION, pipeline().run("VM-NEWBNK-P", "Rs 500 debited, get 5% cashback").category)
        assertEquals(Category.TRANSACTION, pipeline().run("VM-NEWBNK-T", "Rs 500 debited").category)
    }

    @Test
    fun `with no rule the route alone is a weak hint`() {
        val p = pipeline(model = model(Category.PERSONAL, 0.3f))
        val promo = p.run("VM-NEWBNK-P", "zzqx nothing matches")
        assertEquals(Category.PROMOTION, promo.category)
        assertEquals(0.6f, promo.confidence)
        assertEquals(ClassifierSource.TEMPLATE, promo.source)
        assertEquals(setOf("dlt-promotional"), promo.labels)
        assertEquals(Category.TRANSACTION, p.run("VM-NEWBNK-G", "zzqx nothing matches").category)
        assertEquals(Category.TRANSACTION, p.run("VM-NEWBNK-T", "zzqx nothing matches").category)
    }

    // --- Contacts and private numbers

    @Test
    fun `a saved contact's unflagged message is personal whatever the model says`() {
        val p = pipeline(model = model(Category.PROMOTION, 0.95f), contacts = setOf("+919812345678"))
        val c = p.run("+919812345678", "Flat 50% sale, come shop with me")
        assertEquals(Category.PERSONAL, c.category)
        assertEquals(0.8f, c.confidence)
        assertEquals(ClassifierSource.MODEL, c.source)
        // Rules still win for contacts (a forwarded OTP is still an OTP).
        assertEquals(Category.OTP, p.run("+919812345678", "Your OTP is 482913").category)
        // ...but business-only rules are skipped: people write "I paid" too.
        assertEquals(Category.PERSONAL, p.run("+919812345678", "I paid the rent").category)
    }

    @Test
    fun `business-only rules apply to unknown numbers only outside India`() {
        val stranger = "+919812345678"
        assertEquals(Category.TRANSACTION, pipeline(region = SenderRegion.of("GB")).run(stranger, "I paid the rent").category)
        assertEquals(Category.PERSONAL, pipeline().run(stranger, "I paid the rent").category)
        assertEquals(Category.TRANSACTION, pipeline().run("VM-NEWBNK-S", "I paid the rent").category)
    }

    @Test
    fun `a bank-style alert from a private number is spam in India only`() {
        val body = "Rs 5000 credited to your A/c XX1234"
        assertEquals(Category.SPAM, pipeline().run("+919812345678", body).category)
        assertEquals(Category.PERSONAL, pipeline(region = SenderRegion.of("GB")).run("+447700900123", body).category)
        // A saved contact is never a PRIVATE_NUMBER sender.
        assertEquals(Category.PERSONAL, pipeline(contacts = setOf("+919812345678")).run("+919812345678", body).category)
    }

    @Test
    fun `an unknown number with a risky link is spam, a known business is not`() {
        val body = "hello see https://newbank-login.top/x"
        val stranger = pipeline().run("+919812345678", body)
        assertEquals(Category.SPAM, stranger.category)
        assertTrue("fraud-risk" in stranger.labels && "unknown-sender-link" in stranger.labels, stranger.labels.toString())
        val business = pipeline().run("VM-NEWBNK-S", body)
        assertFalse("unknown-sender-link" in business.labels)
    }

    // --- OTP needs a code

    @Test
    fun `an OTP rule without any code falls through to the next rule or the model`() {
        val p = pipeline(model = model(Category.PERSONAL, 0.9f))
        val noCode = p.run("VM-NEWBNK-S", "Never share your OTP with anyone. Rs 500 debited.")
        assertEquals(Category.TRANSACTION, noCode.category)
        assertNull(noCode.otp)
        val withCode = p.run("VM-NEWBNK-S", "Your OTP is 482913")
        assertEquals(Category.OTP, withCode.category)
        assertEquals("482913", withCode.otp?.code)
    }

    @Test
    fun `the model cannot call a message without a code an OTP`() {
        val c = pipeline(model = model(Category.OTP, 0.7f), region = SenderRegion.of("GB")).run("ACME", "zzqx welcome aboard")
        assertTrue(c.category != Category.OTP, c.toString())
    }

    // --- Threshold and the cloud stage

    @Test
    fun `below the threshold with no cloud the answer is UNKNOWN, keeping the score`() {
        val c = pipeline(model = model(Category.PERSONAL, 0.3f), region = SenderRegion.of("GB")).run("ACME", "zzqx")
        assertEquals(Category.UNKNOWN, c.category)
        assertTrue(c.confidence < 0.55f)
    }

    @Test
    fun `the cloud only ever sees masked text and the sender header`() {
        var seen: Pair<String?, String>? = null
        val cloud = CloudClassifier { header, masked ->
            seen = header to masked
            CloudVerdict(Category.TRANSACTION, 0.9f)
        }
        val c = pipeline(model = model(Category.PERSONAL, 0.3f), cloud = cloud)
            .run("AX-SHOPCO-S", "Dear Rohit, order 482913 for Rs 1,299 at https://shop.example/o/1")
        assertEquals(Category.TRANSACTION, c.category)
        assertEquals(ClassifierSource.CLOUD, c.source)
        val (header, masked) = seen!!
        assertEquals("AX-SHOPCO-S", header)
        assertFalse(masked.any { it.isDigit() }, masked)
        assertFalse("Rohit" in masked || "shop.example" in masked, masked)
    }

    @Test
    fun `a cloud verdict below the threshold is still UNKNOWN, and a confident local result never reaches the cloud`() {
        var calls = 0
        val weak = CloudClassifier { _, _ -> calls++; CloudVerdict(Category.SPAM, 0.4f) }
        assertEquals(Category.UNKNOWN, pipeline(model = model(Category.PERSONAL, 0.3f), cloud = weak, region = SenderRegion.of("GB")).run("ACME", "zzqx").category)
        assertEquals(1, calls)
        assertEquals(Category.TRANSACTION, pipeline(cloud = weak).run("VM-NEWBNK-S", "Rs 500 debited").category)
        assertEquals(1, calls)
    }

    @Test
    fun `canonical sender comes from the bundle only for its region`() {
        assertEquals("New Bank", pipeline().run("VM-NEWBNK-S", "Rs 500 debited").canonicalSender)
        assertNull(pipeline(region = SenderRegion.of("GB")).run("VM-NEWBNK-S", "Rs 500 debited").canonicalSender)
    }

    @Test
    fun `only the first MAX_CLASSIFY_CHARS characters are classified`() {
        val tail = " ".repeat(ClassifierPipeline.MAX_CLASSIFY_CHARS) + "Rs 500 debited"
        val c = pipeline(model = model(Category.PERSONAL, 0.9f), region = SenderRegion.of("GB")).run("ACME", "hello$tail")
        assertEquals(Category.PERSONAL, c.category)
    }

    @Test
    fun `a failing region lookup falls back to generic rules instead of crashing`() {
        val p = ClassifierPipeline(bundle, model(Category.PERSONAL, 0.9f), regionFor = { error("no SIM") })
        assertEquals(Category.TRANSACTION, p.run("VM-NEWBNK-S", "Rs 500 debited").category)
    }
}
