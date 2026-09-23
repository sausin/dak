package app.dak.classify

import app.dak.core.model.Category
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dak launches India-first, but must work anywhere: OTPs, region-gated DLT rules, region-tagged templates. */
class WorldwideTest {

    private val templates = TemplateBundle.loadDefault()
    private val model = NaiveBayesModel.loadDefault()

    private fun pipeline(region: SenderRegion, contacts: Set<String> = emptySet()) = ClassifierPipeline(
        templates = templates,
        model = model,
        contactLookup = { it in contacts },
        regionFor = { region },
    )

    private fun classify(address: String, body: String, region: SenderRegion) =
        runBlocking { pipeline(region).classify(address, body, 1) }

    // ------------------------------------------------------------------ OTPs from anywhere

    private val otps = listOf(
        // US
        Triple("262966", "Your Chase verification code is 123456. Don't share it with anyone.", "123456"),
        Triple("22000", "G-482913 is your Google verification code.", "482913"),
        // UK
        Triple("HSBC", "Your HSBC one-time passcode is 739104. Never share this code, HSBC will never ask for it.", "739104"),
        // EU
        Triple("Revolut", "Your Revolut verification code is 551 203", null),
        Triple("N26", "N26: Your security code is 90817263. Do not share it.", "90817263"),
        // UAE (English)
        Triple("ADCB", "Your OTP for ADCB online banking is 348201. Valid for 5 minutes.", "348201"),
        // Singapore
        Triple("DBS", "DBS: Your One-Time Password is 612093 for iBanking login. Do not share.", "612093"),
    )

    @Test
    fun `english OTPs from US UK EU UAE and SG senders are extracted`() {
        for ((_, body, code) in otps) {
            if (code == null) continue
            assertEquals(code, OtpExtractor.extract(body)?.code, body)
        }
    }

    @Test
    fun `english OTPs are classified as OTP outside India`() {
        val region = SenderRegion.of("US")
        for ((sender, body, code) in otps) {
            if (code == null) continue
            val c = classify(sender, body, region)
            assertEquals(Category.OTP, c.category, body)
            assertEquals(code, c.otp?.code, body)
        }
    }

    @Test
    fun `arabic OTP with arabic-indic digits gives an ASCII code`() {
        // "Your verification code is ٤٨٢٩١٣" (UAE / Saudi style).
        val body = "رمز التحقق الخاص بك هو ٤٨٢٩١٣. لا تشاركه مع أحد."
        assertEquals("482913", OtpExtractor.extract(body)?.code)
        // Arabic text, "OTP" label, Arabic-Indic digits.
        assertEquals("739104", OtpExtractor.extract("OTP: ٧٣٩١٠٤ رمز لمرة واحدة")?.code)
    }

    // ------------------------------------------------------------------ DLT only for India

    @Test
    fun `DLT traffic labels only on Indian SIMs`() {
        val body = "Jio: Recharge now with Rs 399 plan and get double data offer, valid till Sunday."
        assertTrue("dlt-promotional" in classify("BP-JIO-P", body, SenderRegion.INDIA).labels)
        assertFalse(classify("BP-JIO-P", body, SenderRegion.of("GB")).labels.any { it.startsWith("dlt-") })
        assertFalse(classify("BP-JIO-P", body, SenderRegion.UNKNOWN).labels.any { it.startsWith("dlt-") })
    }

    @Test
    fun `Indian sender table entries are not applied to other regions`() {
        val body = "Your parcel from our store is on its way."
        assertEquals("HDFC Bank", classify("VM-HDFCBK", "Rs 4500.00 debited from A/c XX1234. Avl bal Rs 12,340.00", SenderRegion.INDIA).canonicalSender)
        assertNull(classify("AMAZON", body, SenderRegion.of("GB")).canonicalSender)
        // Unknown region: nothing is filtered.
        assertEquals("Amazon", classify("AMAZON", body, SenderRegion.UNKNOWN).canonicalSender)
    }

    @Test
    fun `short codes are normal for banks outside India`() {
        val body = "Chase: Review your recent activity at https://chase.com/alerts"
        assertFalse("unknown-sender-link" in classify("24273", body, SenderRegion.of("US")).labels)
        assertTrue("unknown-sender-link" in classify("56070", body, SenderRegion.INDIA).labels)
        // A full phone number with a link is flagged everywhere.
        assertTrue("unknown-sender-link" in classify("+14155552671", body, SenderRegion.of("US")).labels)
    }

    @Test
    fun `personal bias uses local mobile shapes`() {
        assertTrue(SenderRegion.INDIA.isLocalMobile("+919812345678"))
        assertFalse(SenderRegion.INDIA.isLocalMobile("+447911123456"))
        assertTrue(SenderRegion.of("GB").isLocalMobile("+447911123456"))
        assertTrue(SenderRegion.of("US").isLocalMobile("4155552671"))
        assertFalse(SenderRegion.of("US").isLocalMobile("24273"))
        assertFalse(SenderRegion.of("US").isLocalMobile("CHASE"))
    }

    // ------------------------------------------------------------------ region-tagged templates

    @Test
    fun `region-tagged rules apply only in their regions and win ties`() {
        val bundle = TemplateBundle.parseUnsigned(
            """
            {"version": 1, "issuedAt": 0,
             "senders": [{"header": "BANKX", "brand": "Bank X", "regions": ["IN"]}, {"header": "GLOBAL", "brand": "Global"}],
             "rules": [
               {"id": "generic", "category": "TRANSACTION", "pattern": "paid", "priority": 50},
               {"id": "india", "category": "TRANSACTION", "pattern": "paid", "priority": 50, "regions": ["IN"]},
               {"id": "uk", "category": "TRANSACTION", "pattern": "paid", "priority": 50, "regions": ["gb"]}
             ]}
            """.trimIndent(),
        )
        assertEquals(listOf("india", "generic"), bundle.rulesFor("X", SenderRegion.INDIA).map { it.id })
        assertEquals(listOf("uk", "generic"), bundle.rulesFor("X", SenderRegion.of("GB")).map { it.id })
        assertEquals(listOf("generic"), bundle.rulesFor("X", SenderRegion.of("US")).map { it.id })
        assertEquals(3, bundle.rulesFor("X", SenderRegion.UNKNOWN).size)
        assertEquals("Bank X", bundle.sender("BANKX", SenderRegion.INDIA)?.brand)
        assertNull(bundle.sender("BANKX", SenderRegion.of("US")))
        assertEquals("Global", bundle.sender("GLOBAL", SenderRegion.of("US"))?.brand)
    }

    @Test
    fun `bundled Indian senders are tagged IN and generic rules stay global`() {
        val all = templates.rules
        assertTrue(all.any { it.id == "otp-english" && it.regions.isEmpty() })
        assertEquals(listOf("IN"), templates.sender("HDFCBK")?.regions)
        assertTrue(templates.rulesFor(null, SenderRegion.of("US")).none { it.id == "txn-upi" })
    }

    @Test
    fun `region codes are normalised`() {
        assertEquals(SenderRegion.INDIA, SenderRegion.of(" in "))
        assertEquals(SenderRegion.UNKNOWN, SenderRegion.of(""))
        assertEquals(SenderRegion.UNKNOWN, SenderRegion.of("IND"))
        assertEquals("GB", SenderRegion.of("gb").countryIso)
    }
}
