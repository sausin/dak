package app.dak.classify.corpus

import app.dak.classify.ClassifierPipeline
import app.dak.classify.InvestmentLabels
import app.dak.classify.NaiveBayesModel
import app.dak.classify.SenderRegion
import app.dak.classify.TemplateBundle
import app.dak.core.model.Category
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Investment messages: fund / broker / depository confirmations are transactions (labelled [InvestmentLabels.UPDATE]),
 * security alerts with no money moving are transactions labelled [InvestmentLabels.ALERT] (routed to Alerts), NFO /
 * "invest now" offers are promotions, and OTPs for investment transactions stay OTPs. Keyed on vocabulary only, so
 * any (made-up here) fund, scheme or broker name and header gets the same result.
 */
class InvestmentClassificationTest {

    private val templates = TemplateBundle.loadDefault()
    private val india = ClassifierPipeline(templates, NaiveBayesModel.loadDefault(), regionFor = { SenderRegion.INDIA })
    private val indiaPlain = ClassifierPipeline(templates, NaiveBayesModel.loadDefault(), regionFor = { SenderRegion.INDIA }, prefilter = false)

    private data class Case(val address: String, val body: String, val category: Category, val label: String?)

    private val updates = listOf(
        "Dear Investor, your SIP instalment of Rs.5,000.00 in Peak Flexi Cap Fund - Direct Growth, Folio No. XXXX1234 has been processed. Units allotted: 45.678 at NAV Rs.109.4563 on 05-Sep-2026.",
        "Units allotted! Folio 12345678/90: 102.345 units of Orbit Liquid Fund - Regular Plan allotted @ Rs 2,445.1234 for your purchase of INR 2,50,250.00. Balance units: 512.001",
        "Dear Investor, 25.431 units of Silver Balanced Advantage Fund have been allotted to you at NAV of Rs 39.3200 for your lumpsum investment of Rs 1,000.",
        "PURCHASE CONFIRMED: FOLIO NO 44556677, NOVA INDEX FUND - DIRECT GROWTH, AMOUNT INR 7,500.00, NAV 21.5500, UNITS 348.028, TOTAL UNITS 1,002.500",
        "Redemption of 50.000 units from Orbit Liquid Fund, Folio 12345678/90 processed at NAV Rs 2,450.5000. Amount of Rs 1,22,525.00 will be credited to your bank a/c XX4321 in 1-2 working days.",
        "15.500 units redeemed from Silver Arbitrage Fund (Folio: 55443322) at NAV Rs 30.1200. Redemption amount Rs 466.86 credited to your registered bank account.",
        "Switch of 100.000 units from Zen Liquid Fund to Zen Small Cap Fund in folio 9988776 processed. Switch amount Rs 25,000.00.",
        "IDCW of Rs 1,250.00 declared under Peak Equity Income Fund for folio XXXX1234 has been paid to your bank a/c XX4321 on 20-Sep-2026.",
        "Dear Investor, the current value of your investments in Folio XXXX1234 as on 19-Sep-2026 is Rs 1,23,456.78. Units held: 1,127.890.",
        "Your Consolidated Account Statement (CAS) for Aug-2026 has been sent to your registered email. Total valuation as on 31-Aug-2026: INR 5,43,210.50 across 4 folios.",
        "NAV of Peak Flexi Cap Fund - Direct Growth as on 19-Sep-2026 is Rs 110.2345.",
        "Contract Note for 12-Sep-2026: Bought 10 ACME INDUSTRIES LTD @ 2,345.50. Net amount payable Rs 23,480.25 incl. charges. Client ID AB1234.",
        "Trade confirmation: BUY 25 GAMMA TECH @ 99.50, trade value Rs 2,487.50, charges Rs 12.40. BO ID 1234567800012345",
        "Your demat holdings value as on 19-Sep-2026 is Rs 8,76,543.21. BO ID XXXXXXXX00012345",
        "Congratulations! 14 shares of OMEGA FOODS allotted to your demat a/c XXXX5678 against your IPO application of Rs 14,980.",
    ).map { Case("VM-PEAKMF-S", it, Category.TRANSACTION, InvestmentLabels.UPDATE) }

    private val alerts = listOf(
        "10 shares of ACME LTD (ISIN INE123A01016) debited from your demat a/c XXXX5678 on 18-Sep-2026. If not done by you, contact your DP immediately.",
        "Pledge created for 50 shares of BETA POWER in your demat a/c XXXX5678 in favour of your broker. Value Rs 60,000.",
        "Margin pledge invoked: 20 qty of GAMMA TECH transferred from BO ID 1234567800012345.",
        "e-DIS request for 5 shares of DELTA CORP authorised. Securities will be debited from your demat account on T+1.",
        "Securities debited: ISIN INE987Z01012 Qty 15 from your demat. DP ID IN300123 Client ID 10001234",
        "Your holdings of 40 shares of ACME LTD have been re-pledged by your broker to the clearing member.",
    ).map { Case("JM-DEPOSI-S", it, Category.TRANSACTION, InvestmentLabels.ALERT) }

    private val promotions = listOf(
        "NFO alert! Peak Manufacturing Fund opens on 01-Oct-2026. Invest now with SIP starting Rs 500. Returns up to 18% in past schemes*.",
        "New Fund Offer: Orbit Defence Fund. Subscribe between 1-15 Oct. Minimum Rs 1,000.",
        "Start your SIP of Rs 1,000 today in folio XXXX1234 and grow your wealth!",
        "Invest in top mutual funds with SIP starting Rs 100 on our app.",
    ).map { Case("VM-PEAKMF-S", it, Category.PROMOTION, null) }

    private val otps = listOf(
        "123456 is your OTP to confirm redemption of 50 units in folio XXXX1234. Do not share.",
        "OTP for purchase of Rs 5,000 in Peak Flexi Cap Fund is 482913. Valid for 5 mins.",
        "Your OTP for e-DIS authorisation is 739201. Do not share it with anyone.",
        "Use OTP 551204 to confirm the pledge of 50 shares in your demat account.",
    ).map { Case("VM-PEAKMF-S", it, Category.OTP, null) }

    /** A bank's own SIP debit is an ordinary transaction alert (loud), not an investment update. */
    private val bankSide = listOf(
        "Rs.5,000.00 debited from A/c XX4321 on 05-09-26 towards ACH D- PEAK MF SIP. Avl Bal Rs.45,000.00",
        "Rs 1,22,525.00 credited to A/c XX4321 on 22-09-2026 by NEFT - PEAK MUTUAL FUND REDEMPTION. Avl Bal Rs 1,50,000.00",
        // Naming a folio does not make the bank's own debit a quiet fund update.
        "Your A/c XX4321 is debited for Rs 2,000.00 on 05-Sep-2026 towards SIP, Folio 12345678. Avl bal Rs 43,000.00",
        "Rs 2,000.00 debited from your A/c XX4321 towards NAV-based SIP purchase in folio 12345678.",
    ).map { Case("VM-NOVABK-S", it, Category.TRANSACTION, null) }

    @Test
    fun `investment messages land in their category with their label`() {
        val failures = ArrayList<String>()
        for (c in updates + alerts + promotions + otps + bankSide) {
            val got = runBlocking { india.classify(c.address, c.body, 1) }
            val plain = runBlocking { indiaPlain.classify(c.address, c.body, 1) }
            if (got != plain) failures += "prefilter changed the result for \"${c.body}\""
            val investmentLabels = got.labels.filter { it == InvestmentLabels.ALERT || it == InvestmentLabels.UPDATE }
            val wantLabels = listOfNotNull(c.label)
            if (got.category != c.category || investmentLabels != wantLabels) {
                failures += "expected ${c.category} $wantLabels, got ${got.category} ${got.labels} (${got.source}) from ${c.address}: \"${c.body}\""
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `no template rule names a fund house, registrar, broker, depository or exchange`() {
        // Names only ever appear here, in the test that keeps them out of the rules.
        val names = listOf(
            "cams", "kfin", "kfintech", "karvy", "nsdl", "cdsl", "zerodha", "groww", "upstox", "sharekhan", "angel", "dhan",
            "motilal", "nippon", "mirae", "parag", "quant", "tata", "uti", "franklin", "edelweiss", "sbimf", "hdfcmf",
            "icicipru", "axismf", "amfi", "mfcentral", "nse", "bse", "mcx", "coin", "kite", "smallcase",
        )
        for (rule in templates.rules) {
            val pattern = rule.pattern.lowercase()
            for (name in names) {
                val hit = Regex("(?<![a-z])" + Regex.escape(name) + "(?![a-z])").containsMatchIn(pattern)
                assertTrue(!hit, "rule ${rule.id} names \"$name\": ${rule.pattern}")
            }
        }
    }

    @Test
    fun `a fund or broker name never changes the result`() {
        val bodies = listOf(
            "Dear Investor, your SIP instalment of Rs.2,000.00 in %s Flexi Cap Fund, Folio No. XXXX1234 has been processed. Units allotted: 18.27 at NAV Rs.109.47.",
            "Contract Note: Bought 10 %s LTD @ 2,345.50. Net amount payable Rs 23,480.25. Client ID AB1234.",
            "10 shares of %s LTD debited from your demat a/c XXXX5678. If not done by you, contact your DP.",
        )
        val names = listOf("Peak", "Orbit", "Zen", "Nova", "Silverline", "Brand New", "Kestrel", "Umbra")
        val headers = listOf("VM-PEAKMF-S", "JD-ZENBRK-T", "AX-NEWAMC-S", "VM-DPALRT", "BZ-FUNDXX")
        val failures = ArrayList<String>()
        for (template in bodies) {
            val expected = runBlocking { india.classify(headers.first(), template.format(names.first()), 1) }
            assertTrue(expected.category == Category.TRANSACTION, "$expected: $template")
            for (name in names) for (header in headers) {
                val got = runBlocking { india.classify(header, template.format(name), 1) }
                val sameLabels = got.labels.filter { it.startsWith("investment-") } == expected.labels.filter { it.startsWith("investment-") }
                if (got.category != expected.category || !sameLabels) failures += "$header / $name: $got"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}
