package app.dak.classify

import app.dak.classify.scam.FakeCreditDetector
import app.dak.classify.scam.ScamLevel
import app.dak.core.model.Category
import app.dak.finance.parser.TransactionParser
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keyword and header matching must not depend on the phone's language: in Turkish, `"i".uppercase(Locale("tr"))` is
 * "İ" and `"I".lowercase(Locale("tr"))` is "ı", which would break every comparison against ASCII keywords. Kotlin's
 * `uppercase()` / `lowercase()` (used throughout :classify and :finance) are locale-invariant; this pins that the
 * whole analysis gives the same answers under a Turkish, Azerbaijani or Lithuanian default locale.
 */
class LocaleIndependenceTest {

    private val cases = listOf(
        "vm-hdfcbk-s" to "Rs.2,450.00 debited from A/c **1234 on 23-09-26 to VPA swiggy@icici (UPI Ref No 626512345678).",
        "+919876512301" to "INR 25,000.00 CREDITED to your A/c XX4321 by IMPS. Avl Bal INR 25,340.50 -SBI",
        "VM-ICICIT-S" to "OTP IS 482913 FOR LOGIN. DO NOT SHARE IT. VISIT HTTPS://ICICIBANK.COM",
        "+919876512331" to "Your KYC is pending, account will be BLOCKED today. Update: HTTPS://SBI-KYC-INFO.XYZ/I",
    )

    private fun snapshot(): List<String> {
        val templates = TemplateBundle.loadDefault()
        val pipeline = ClassifierPipeline(templates, NaiveBayesModel.loadDefault(), regionFor = { SenderRegion.INDIA })
        val detector = FakeCreditDetector(templates)
        return cases.map { (address, body) ->
            val c = runBlocking { pipeline.classify(address, body, 1) }
            listOf(
                SenderId.classify(address), SenderId.mergeKey(address), c.category, c.otp?.code, c.labels.sorted(),
                detector.evaluate(address, body).level, TransactionParser.parse(address, body)?.amountMinor,
                LinkExtractor.extract(body).map { it.host }, OtpExtractor.extract(body)?.code,
            ).toString()
        }
    }

    @Test
    fun `results are the same under Turkish, Azerbaijani and Lithuanian default locales`() {
        val original = Locale.getDefault()
        val expected = snapshot()
        try {
            for (tag in listOf("tr-TR", "az-AZ", "lt-LT")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(expected, snapshot(), tag)
            }
        } finally {
            Locale.setDefault(original)
        }
        assertEquals(true, expected[0].contains(Category.TRANSACTION.name), expected[0])
        assertEquals(true, expected[1].contains(ScamLevel.LIKELY_SCAM.name), expected[1])
    }
}
