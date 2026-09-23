package app.dak.classify

import app.dak.core.model.Category
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassifierPipelineTest {

    private val pipeline = ClassifierPipeline(
        templates = TemplateBundle.loadDefault(),
        model = NaiveBayesModel.loadDefault(),
    )

    private fun classify(address: String, body: String, subId: Int = 1) =
        runBlocking { pipeline.classify(address, body, subId) }

    @Test
    fun `HDFC debit message`() {
        val c = classify("VM-HDFCBK", "Rs 4500.00 debited from A/c XX1234 on 12-03-24 at AMAZON. Avl bal Rs 12,340.00")
        assertEquals(Category.TRANSACTION, c.category)
        assertEquals("HDFC Bank", c.canonicalSender)
    }

    @Test
    fun `ICICI credit card spend`() {
        val c = classify("AX-ICICIT", "Rs.850 spent on your ICICI Bank Credit Card XX9012 at SWIGGY on 05-Aug. Avl limit Rs 50,000")
        assertEquals(Category.TRANSACTION, c.category)
    }

    @Test
    fun `SBI UPI credit`() {
        val c = classify("JD-SBIINB", "Your a/c XX7788 credited Rs 1200 via UPI ref 234567891023. Avl Bal Rs 45,780.")
        assertEquals(Category.TRANSACTION, c.category)
        assertEquals("State Bank of India", c.canonicalSender)
    }

    @Test
    fun `Paytm wallet top-up`() {
        val c = classify("VK-PAYTM", "Paytm: Rs 500 added to your wallet successfully. Ref 998877665544.")
        assertEquals(Category.TRANSACTION, c.category)
        assertEquals("Paytm", c.canonicalSender)
    }

    @Test
    fun `Amazon OTP`() {
        val c = classify("VM-AMAZON", "Your Amazon OTP is 221100. Do not share this OTP with anyone, including Amazon associates.")
        assertEquals(Category.OTP, c.category)
        assertNotNull(c.otp)
        assertEquals("221100", c.otp?.code)
    }

    @Test
    fun `IRCTC booking confirmation`() {
        val c = classify("VK-IRCTC", "IRCTC: Your PNR 4455667788 ticket booking confirmed for train 12345 on 15-Sep-25.")
        assertEquals(Category.TRANSACTION, c.category)
    }

    @Test
    fun `Jio recharge promo`() {
        val c = classify("BP-JIO-P", "Jio: Recharge now with Rs 399 plan and get double data offer, valid till Sunday.")
        assertEquals(Category.PROMOTION, c.category)
    }

    @Test
    fun `fake KYC spam with link`() {
        val c = classify("9876501234", "URGENT: Your bank account will be suspended. Verify your KYC now at http://secure-bank-kyc.tk")
        assertEquals(Category.SPAM, c.category)
        assertTrue("unknown-sender-link" in c.labels)
    }

    @Test
    fun `personal hinglish chat from a saved contact`() {
        val pipelineWithContact = ClassifierPipeline(
            templates = TemplateBundle.loadDefault(),
            model = NaiveBayesModel.loadDefault(),
            contactLookup = { it == "9876543210" },
        )
        val c = runBlocking {
            pipelineWithContact.classify("9876543210", "Kal party mein aa raha hai na? Sabko bata diya hai time 8 baje", 1)
        }
        assertEquals(Category.PERSONAL, c.category)
    }

    @Test
    fun `unknown numeric sender with link is flagged even without a matching template rule`() {
        val c = classify("9123456780", "Hey check this out https://randomoffers.xyz/win")
        assertTrue("unknown-sender-link" in c.labels)
    }

    @Test
    fun `below-threshold result with no cloud classifier is UNKNOWN`() {
        // A model that is never sure: without a cloud stage the pipeline must say UNKNOWN rather than guess.
        val unsure = ClassifierPipeline(
            templates = TemplateBundle.loadDefault(),
            model = { Category.entries.associateWith { 1f / Category.entries.size } },
        )
        val c = runBlocking { unsure.classify("XY-UNKNOWNCO", "asdkj qwioeu zxcv random text with nothing recognisable", 1) }
        assertEquals(Category.UNKNOWN, c.category)
        assertTrue(c.confidence < 0.55f, c.toString())
    }

    @Test
    fun `low confidence escalates to cloud classifier and respects its verdict`() {
        val cloud = CloudClassifier { _, _ -> CloudVerdict(Category.TRANSACTION, 0.8f) }
        val pipelineWithCloud = ClassifierPipeline(
            templates = TemplateBundle.loadDefault(),
            model = object : MessageModel {
                override fun predict(text: String) = mapOf(
                    Category.PERSONAL to 0.3f, Category.TRANSACTION to 0.3f, Category.OTP to 0.1f,
                    Category.PROMOTION to 0.1f, Category.SPAM to 0.1f, Category.UNKNOWN to 0.1f,
                )
            },
            cloud = cloud,
            threshold = 0.5f,
        )
        val c = runBlocking { pipelineWithCloud.classify("XY-RANDOM", "some ambiguous text here", 1) }
        assertEquals(Category.TRANSACTION, c.category)
        assertEquals(0.8f, c.confidence)
    }

    @Test
    fun `every message of the sample corpus is classified correctly`() {
        val samples = listOf(
            Triple("VM-HDFCBK", "Rs 4500.00 debited from A/c XX1234 on 12-03-24 at AMAZON. Avl bal Rs 12,340.00", Category.TRANSACTION),
            Triple("AX-ICICIT", "Rs.850 spent on your ICICI Bank Credit Card XX9012 at SWIGGY on 05-Aug. Avl limit Rs 50,000", Category.TRANSACTION),
            Triple("JD-SBIINB", "Your a/c XX7788 credited Rs 1200 via UPI ref 234567891023. Avl Bal Rs 45,780.", Category.TRANSACTION),
            Triple("VK-PAYTM", "Paytm: Rs 500 added to your wallet successfully. Ref 998877665544.", Category.TRANSACTION),
            Triple("VM-AMAZON", "Your Amazon OTP is 221100. Do not share this OTP with anyone, including Amazon associates.", Category.OTP),
            Triple("VK-IRCTC", "IRCTC: Your PNR 4455667788 ticket booking confirmed for train 12345 on 15-Sep-25.", Category.TRANSACTION),
            Triple("BP-JIO-P", "Jio: Recharge now with Rs 399 plan and get double data offer, valid till Sunday.", Category.PROMOTION),
            Triple("9876501234", "URGENT: Your bank account will be suspended. Verify your KYC now at http://secure-bank-kyc.tk", Category.SPAM),
            Triple("9988776655", "Kal party mein aa raha hai na? Sabko bata diya hai time 8 baje", Category.PERSONAL),
            Triple("VM-HDFCBK", "553221 is the OTP for your Paytm login. Valid for 3 minutes.", Category.OTP),
        )
        val contactPipeline = ClassifierPipeline(
            templates = TemplateBundle.loadDefault(),
            model = NaiveBayesModel.loadDefault(),
            contactLookup = { it == "9988776655" },
        )
        // Every sample, not "90% of ten": one silent misclassification here is a regression.
        val wrong = samples.mapNotNull { (address, body, expected) ->
            val result = runBlocking { contactPipeline.classify(address, body, 1) }
            if (result.category != expected) "$address \"$body\": expected $expected got ${result.category}" else null
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }
}
