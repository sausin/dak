package app.dak.classify.corpus

import app.dak.classify.ClassifierPipeline
import app.dak.classify.NaiveBayesModel
import app.dak.classify.SenderId
import app.dak.classify.SenderKind
import app.dak.classify.SenderRegion
import app.dak.classify.TemplateBundle
import app.dak.core.model.Category
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every case of [LabelledCorpus] must land in its labelled category on an Indian SIM, with the template prefilter
 * both on and off. Senders that are not DLT headers (so do not depend on India's route rules) are checked on a SIM of
 * unknown region too, except bank-alert-shaped text from private numbers, whose SPAM verdict rests on India's
 * "banks only send from DLT headers" rule.
 */
class LabelledCorpusTest {

    private val templates = TemplateBundle.loadDefault()
    private val model = NaiveBayesModel.loadDefault()

    private fun pipeline(region: SenderRegion, prefilter: Boolean = true) = ClassifierPipeline(
        templates = templates,
        model = model,
        contactLookup = { it in LabelledCorpus.CONTACTS },
        regionFor = { region },
        prefilter = prefilter,
    )

    private val india = pipeline(SenderRegion.INDIA)
    private val indiaPlain = pipeline(SenderRegion.INDIA, prefilter = false)
    private val unknownRegion = pipeline(SenderRegion.UNKNOWN)

    private fun run(p: ClassifierPipeline, case: LabelledCorpus.Case) = runBlocking { p.classify(case.address, case.body, 1) }

    @Test
    fun `every corpus message lands in its labelled category on an Indian SIM`() {
        val failures = ArrayList<String>()
        val report = StringBuilder()
        for ((group, cases) in LabelledCorpus.groups) {
            var ok = 0
            for (case in cases) {
                val got = run(india, case)
                val plain = run(indiaPlain, case)
                if (got != plain) failures += "[$group] prefilter changed the result for \"${case.body}\": $got vs $plain"
                if (got.category == case.expected) {
                    ok++
                } else {
                    failures += "[$group] expected ${case.expected}, got ${got.category} (${got.source}, ${got.confidence}, " +
                        "${got.labels}) from ${case.address}: \"${case.body}\""
                }
            }
            report.append("$group: $ok/${cases.size}\n")
        }
        println(report)
        if (failures.isNotEmpty()) fail("${failures.size} of ${LabelledCorpus.all.size} misclassified:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `non-DLT senders get the same category on a SIM of unknown region`() {
        // Outside India a phone number may be a business, so only contacts, short codes and names are compared, plus
        // scams and OTPs from unknown numbers (minus the India-only "no business sends from a mobile" verdicts).
        val failures = ArrayList<String>()
        for (case in LabelledCorpus.all) {
            val kind = SenderId.classify(case.address)
            if (kind == SenderKind.DLT_HEADER || case.body in LabelledCorpus.INDIA_ONLY) continue
            val person = kind == SenderKind.PHONE_NUMBER && case.address.count { it.isDigit() } >= 8
            if (person && case.address !in LabelledCorpus.CONTACTS && case.expected != Category.SPAM && case.expected != Category.OTP) continue
            val got = run(unknownRegion, case)
            if (got.category != case.expected) failures += "expected ${case.expected}, got ${got.category} from ${case.address}: \"${case.body}\""
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `scams carry the fraud-risk label or the unknown-sender-link label`() {
        for (case in LabelledCorpus.scams + LabelledCorpus.morePromotionsAndScams.filter { it.expected == Category.SPAM }) {
            val got = run(india, case)
            assertTrue(
                "fraud-risk" in got.labels || "unknown-sender-link" in got.labels,
                "no warning label on \"${case.body}\": ${got.labels}",
            )
        }
    }

    @Test
    fun `unknown senders with a scheme-less link get the unknown-sender-link label`() {
        for (body in listOf("Your parcel is held, pay customs fee at bit.ly/3xYzAb", "Claim here: prize-claim.xyz/uk now")) {
            assertTrue("unknown-sender-link" in run(india, LabelledCorpus.Case(Category.SPAM, "+919812345678", body)).labels, body)
        }
        // A saved contact sharing a link is not flagged.
        val contact = LabelledCorpus.Case(Category.PERSONAL, "+919876500001", "Check this out: youtube.com/watch?v=abc123")
        assertTrue("unknown-sender-link" !in run(india, contact).labels)
    }
}
