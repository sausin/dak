package app.dak.classify.corpus

import app.dak.classify.ClassifierPipeline
import app.dak.classify.NaiveBayesModel
import app.dak.classify.SenderRegion
import app.dak.classify.TemplateBundle
import app.dak.classify.entities.Couriers
import app.dak.classify.scam.BankNames
import app.dak.core.model.Category
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Categorisation keys on the structure of a message, never on who sent it: template rules name no brand, bank or
 * courier, and the same courier / order / bank update is categorised identically whichever (real or brand-new) name
 * it carries and whichever header sends it.
 */
class GenericRulesTest {

    private val templates = TemplateBundle.loadDefault()
    private val india = ClassifierPipeline(templates, NaiveBayesModel.loadDefault(), regionFor = { SenderRegion.INDIA })

    private fun classify(address: String, body: String) = runBlocking { india.classify(address, body, 1) }

    /** Words of brand names that are ordinary vocabulary, so may appear in a rule. */
    private val genericWords = setOf("bank", "india", "first", "card", "post", "national", "small", "finance", "speed", "express", "mahindra")

    @Test
    fun `no template rule names a brand bank or courier`() {
        val brands = templates.rules.let { _ -> BankNames.families.map { it.displayName } } +
            Couriers.NAMES.values.flatten() +
            listOf("Paytm", "PhonePe", "Amazon", "Flipkart", "Swiggy", "Zomato", "Delhivery", "BlueDart", "Blue Dart", "IRCTC", "IndiGo",
                "Airtel", "Jio", "Xpressbees", "Shadowfax", "Ekart", "Ecom Express", "DTDC", "Myntra", "Nykaa", "Shiprocket", "Smartr")
        val words = brands.flatMap { it.lowercase().split(' ') }.filter { it.length >= 4 && it !in genericWords }.toSet() +
            setOf("dhl", "dtdc", "sbi", "ups", "jio")
        for (rule in templates.rules) {
            val pattern = rule.pattern.lowercase()
            for (word in words) {
                val hit = Regex("(?<![a-z])" + Regex.escape(word) + "(?![a-z])").containsMatchIn(pattern.replace(" ?", ""))
                assertTrue(!hit, "rule ${rule.id} names \"$word\": ${rule.pattern}")
            }
        }
    }

    @Test
    fun `a courier or merchant name never changes the category`() {
        val templatesOf = listOf(
            "Your order with %s AWB# 77053102755 was delivered to KARTI. Please rate our service on https://x.example/r/1",
            "We have delivered your order:14385854046 on 2026-05-18 to Meera. For feedback pls click fb.example.in/ffb63 %s",
            "%s: We were unable to deliver your shipment today, we will reattempt tomorrow.",
            "%s: consignment 12345678 is in transit, expected delivery Friday.",
            "ARRIVING: %s 500g (SURFACE) will deliver your shipment. Track https://c.example.in/s?r=abc",
            "Your %s order #OD123456 has been shipped. Rate your experience: https://x.example/y",
            "Tax invoice INV/24/5566 for Rs 1,299 from %s is attached. Thank you for shopping with us.",
            "%s: your parcel is out for delivery today. Our agent will call before arriving.",
        )
        val names = listOf("Blue Dart", "Delhivery", "Xpressbees", "Ecom Express", "Shadowfax", "Ekart", "DTDC", "India Post", "Kargo Express",
            "Zyntra", "Parsly Logistics", "Quickship", "Nova Couriers", "Amazon", "Myntra", "Brand New Store")
        val headers = listOf("VM-KARGOX-S", "JD-ZYNTRA-T", "AX-NEWCOX-S", "VM-BLUDRT-S", "VK-DELHVR", "BZ-QCKSHP")
        val failures = ArrayList<String>()
        for (template in templatesOf) {
            for (name in names) {
                for (header in headers) {
                    val body = template.format(name)
                    val got = classify(header, body).category
                    if (got != Category.TRANSACTION) failures += "$got from $header: \"$body\""
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `a courier fee scam is spam whichever courier it names`() {
        for (name in listOf("Blue Dart", "India Post", "FedEx", "Zyntra", "Nova Couriers")) {
            assertEquals(
                Category.SPAM,
                classify("+919812345678", "$name: your parcel is on hold due to incomplete address. Pay Rs 25 redelivery fee at bit.ly/x9").category,
                name,
            )
        }
    }

    /** Unseen phrasings (written after the rules were settled), as a check against over-fitting the corpus. */
    @Test
    fun `holdout phrasings`() {
        val cases = listOf(
            Triple(Category.TRANSACTION, "VM-ORBITB-T", "Txn of INR 1,249.00 on Orbit Bank card ending 7781 at FUELSTN on 18-09-26. Avl limit INR 42,000."),
            Triple(Category.TRANSACTION, "JD-POSTXX-S", "Your consignment has reached the delivery hub near you and will be delivered tomorrow."),
            Triple(Category.TRANSACTION, "VM-MEDIXX-S", "Hi Rahul, your lab report for booking LB7788 is ready. Download: mdx.in/r/7788"),
            Triple(Category.TRANSACTION, "AX-HOMEFX-S", "Your service appointment for AC repair is scheduled for 19-Sep between 2-4 PM. Technician: Ajay."),
            Triple(Category.TRANSACTION, "VM-NIMBUS-S", "Your credit card payment of Rs 5,000 is due tomorrow. Ignore if already paid."),
            Triple(Category.TRANSACTION, "JM-TELCOX-S", "Your pack has expired. Recharge to continue using data and calls."),
            Triple(Category.TRANSACTION, "VM-RAILBK-T", "PNR 5566778899: Train 12001 is running late by 40 mins. Expected arrival at NDLS 10:45."),
            Triple(Category.OTP, "VM-ORBITB-T", "Your one time password for adding a payee is 448812. Valid for 3 minutes. Do not share."),
            Triple(Category.OTP, "AD-SHOPIX-S", "448812 is your login code for Shopix. It expires in 10 minutes."),
            Triple(Category.PROMOTION, "VK-ORBITB-P", "Pre-approved car loan up to Rs 10 lakh at special rates for you. Apply now: orb.in/cl"),
            Triple(Category.PROMOTION, "BP-FRESHO-P", "Weekend special: buy 2 get 1 free on fruits and vegetables. Order now!"),
            Triple(Category.PROMOTION, "VK-FASHNX-P", "Hey! Your favourite sneakers are back in stock. Grab them now before they sell out."),
            Triple(Category.SPAM, "+919812300101", "Dear customer, your account will be suspended within 2 hours. Complete your KYC: nb-kyc-verify.xyz"),
            Triple(Category.SPAM, "9812300102", "Congratulations! You have won Rs 50,000 cash prize in the festive lucky draw. Call now to claim."),
            Triple(Category.SPAM, "+919812300103", "Earn Rs 800 per task daily from home, just rate hotels online. Contact HR on WhatsApp."),
            Triple(Category.SPAM, "9812300104", "Rs 12,500 has been credited to your a/c XX2201. Ref IMPS 667788. Kindly check and confirm."),
            Triple(Category.PERSONAL, "9812300105", "Hey, are we still meeting for lunch tomorrow? Let me know"),
            Triple(Category.PERSONAL, "+919812300106", "Main kal nahi aa paunga, kuch kaam aa gaya hai. Sorry yaar"),
            Triple(Category.PERSONAL, "JM-TELCOX-S", "You have 2 missed calls from 9812300107. Last call at 21:05."),
        )
        val failures = cases.mapNotNull { (expected, address, body) ->
            val got = classify(address, body)
            if (got.category == expected) null else "expected $expected, got ${got.category} (${got.source}) from $address: \"$body\""
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}
