package app.dak.classify

import app.dak.core.model.Category
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-device misclassifications (India): courier / order / invoice updates landing in Promotions or Spam because
 * of their "rate our service" / "click for feedback" links, and a carrier "now available to take calls" alert
 * landing in Spam. Negative controls keep genuine promos and parcel-fee scams where they belong.
 */
class LogisticsAndCallAlertTest {

    private val india = ClassifierPipeline(
        templates = TemplateBundle.loadDefault(),
        model = NaiveBayesModel.loadDefault(),
        regionFor = { SenderRegion.INDIA },
    )

    private val generic = ClassifierPipeline(
        templates = TemplateBundle.loadDefault(),
        model = NaiveBayesModel.loadDefault(),
    )

    private fun classify(address: String, body: String, pipeline: ClassifierPipeline = india) =
        runBlocking { pipeline.classify(address, body, 1) }

    private fun assertCategory(expected: Category, address: String, body: String) {
        for (pipeline in listOf(india, generic)) {
            val c = classify(address, body, pipeline)
            assertEquals(expected, c.category, "\"$body\" from $address: $c")
        }
    }

    // --- Reported cases

    @Test
    fun `Blue Dart delivered with a rate-our-service link is a transaction`() {
        val body = "Your order with Blue Dart AWB# 77053102755 was delivered to KARTI . Please Rate our Service on " +
            "https://acl.cc/BLUDRT/lqZvGvJb"
        for (address in listOf("JD-BLUDRT-S", "VM-BLUDRT-T", "BLUDRT")) assertCategory(Category.TRANSACTION, address, body)
    }

    @Test
    fun `Shift Logistics arriving notice with a tracking link is a transaction`() {
        val body = "ARRIVING: Bluedart 500g (SURFACE) will deliver your shipment, Track " +
            "https://c.shift.in/s?r=ls6ZQJYLVf3RrjrMr11xdQ Regards, Shift Logistics, India."
        for (address in listOf("VM-SHIFTL-S", "AD-SHIFTO")) assertCategory(Category.TRANSACTION, address, body)
    }

    @Test
    fun `Xpressbees delivered with a feedback link is a transaction, not spam`() {
        val body = "We have delivered your M&S order:14385854046 on 2026-05-18 to Kraxx, For feedback pls click " +
            "fb.xbees.in/ffb63 Xpressbees"
        for (address in listOf("VM-XPRESS-S", "AX-XBEES-T")) assertCategory(Category.TRANSACTION, address, body)
    }

    @Test
    fun `carrier now-available-to-take-calls alert is personal, not spam`() {
        val body = "Dear Customer, +919999999999 is now available to take calls."
        for (address in listOf("JM-JIOSVC-S", "AD-AIRTEL", "VK-VIMOBI-S", "121", "+919812345678")) {
            assertCategory(Category.PERSONAL, address, body)
        }
        assertCategory(Category.PERSONAL, "JM-JIOSVC-S", "Dear Customer, 98XXXXX210 is now reachable. Call now.")
        assertCategory(Category.PERSONAL, "AD-AIRTEL", "Missed call alert: 9812345678 called you 2 times, last at 10:42.")
    }

    // --- Nearby wording the fix should generalise to

    @Test
    fun `order invoice and courier updates are transactions`() {
        assertCategory(Category.TRANSACTION, "JD-AMAZON-S", "Your invoice for order 402-1234567 is ready: https://amzn.in/i/abc")
        assertCategory(Category.TRANSACTION, "VM-MYNTRA-S", "Your Myntra order #OD123456 has been shipped. Rate your experience: https://myntr.it/x")
        assertCategory(Category.TRANSACTION, "VM-DELHVR-S", "Delhivery: We were unable to deliver your shipment today, we will reattempt tomorrow.")
        assertCategory(Category.TRANSACTION, "AX-ECOMEX-S", "Ecom Express: consignment 12345678 is in transit, expected delivery Friday.")
        assertCategory(Category.TRANSACTION, "VM-NYKAA-T", "Tax invoice NYK/24/5566 for Rs 1,299 is attached. Thank you for shopping with us.")
        assertCategory(
            Category.TRANSACTION,
            "VM-DELHVR-S",
            "Your package was delivered. Click here to rate your delivery https://dlv.in/r/abc",
        )
    }

    // --- Negative controls

    @Test
    fun `order-now promotions stay promotions`() {
        assertCategory(Category.PROMOTION, "VK-MYNTRA-P", "Order now! 50% off on all shoes, today only.")
        assertCategory(Category.PROMOTION, "BP-SWIGGY-P", "Order no later than 9 PM and get flat 40% off, free delivery on your order!")
        assertCategory(Category.PROMOTION, "VK-CROMA-P", "iPhone 17 is now available at Croma, get 10% instant discount. Order now!")
    }

    @Test
    fun `parcel customs-fee and address-update scams stay spam`() {
        assertCategory(Category.SPAM, "+919812345678", "Your parcel is held, pay customs fee at bit.ly/3xYzAb")
        assertCategory(
            Category.SPAM,
            "+919812345678",
            "India Post: Your package could not be delivered due to incomplete address. Please update your address " +
                "within 12 hours: https://indiapost-in.top/track",
        )
        assertCategory(
            Category.SPAM,
            "VM-BLUDRT-S", // even a courier-looking header: the fee demand is the scam signal
            "Blue Dart: your shipment AWB 88776655 is on hold, pay redelivery fee of Rs 25 at https://bd-pay.xyz",
        )
        assertCategory(
            Category.SPAM,
            "+15551234567",
            "USPS: Your package is waiting for delivery. Please confirm your delivery address at https://usps-redeliver.top",
        )
        assertTrue("fraud-risk" in classify("+919812345678", "Your parcel is held, pay customs fee at bit.ly/3xYzAb").labels)
        // LinkExtractor only sees http(s):// and www. links, so the sender-link label needs the scheme.
        val c = classify("+919812345678", "Your parcel is held, pay customs fee at https://bit.ly/3xYzAb")
        assertTrue("fraud-risk" in c.labels && "unknown-sender-link" in c.labels, c.labels.toString())
    }

    @Test
    fun `feedback wording does not exempt a real click-here scam`() {
        assertCategory(Category.SPAM, "+919812345678", "Your account is locked, click here now https://sbi-help.xyz")
    }

    // --- DLT route bias in the model stage

    @Test
    fun `transactional DLT route damps promotion and spam model scores`() {
        val pipeline = ClassifierPipeline(
            templates = TemplateBundle.loadDefault(),
            model = { mapOf(Category.PROMOTION to 0.45f, Category.SPAM to 0.15f, Category.TRANSACTION to 0.4f) },
            regionFor = { SenderRegion.INDIA },
            threshold = 0.3f,
        )
        val text = "zzqx unmatched wording"
        assertEquals(Category.TRANSACTION, classify("VM-SHOPCO-T", text, pipeline).category)
        assertEquals(Category.PROMOTION, classify("VM-SHOPCO-P", text, pipeline).category)
        // Service-implicit templates may carry consented promotions: only spam is damped there.
        assertEquals(Category.PROMOTION, classify("VM-SHOPCO-S", text, pipeline).category)
    }
}
