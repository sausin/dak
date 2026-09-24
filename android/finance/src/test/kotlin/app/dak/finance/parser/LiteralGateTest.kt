package app.dak.finance.parser

import app.dak.finance.money.CurrencyTable
import app.dak.finance.parser.corpus.InvestmentCorpus
import app.dak.finance.parser.corpus.SmsCorpus
import app.dak.finance.parser.corpus.SmsCorpusMore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The parser's literal gates ([GatedPattern]) only skip regexes that could not have matched: on every corpus body
 * (plus case and Unicode oddities), a pattern that matches always passes its gate, and the gated cue finder and
 * investment parser give exactly what the ungated ones give.
 */
class LiteralGateTest {

    private val bodies: List<String> = run {
        val base = SmsCorpus.all.values.flatten().map { it.body } +
            SmsCorpusMore.all.values.flatten().map { it.body } +
            InvestmentCorpus.all.values.flatten().map { it.body }
        base + base.map { it.uppercase() } + base.map { it.lowercase() } + listOf(
            "Rs 500 ſent to you", // long s: case-insensitively "sent"
            "INR 20 DEBITED Kindly note", // Kelvin sign
            "İNR 500 CREDİTED to A/c XX1234", // dotted capital I
            "Your OTP is 1234", "one-time  PASSWORD 4321", "Units allotted in XYZ Fund", "Folio 1234/56 NAV 12.3",
            "BOUGHT 10 ABC LTD @ 2,345.50", "Your CAS for Aug", "Mini-Statement: Rs 5", "request Rs 500 from you",
        )
    }

    @Test
    fun `a pattern that matches always passes its literal gate`() {
        val patterns = DirectionCues.gatedPatterns + TransactionParser.gatedPatterns + InvestmentParser.investmentVocabulary
        val misses = ArrayList<String>()
        for (body in bodies) {
            val folded = GatedPattern.fold(body)
            for (p in patterns) {
                if (p.regex.containsMatchIn(body) && !p.mayMatch(folded)) misses += "${p.regex.pattern.take(40)}… on \"$body\""
            }
        }
        if (misses.isNotEmpty()) fail(misses.joinToString("\n"))
    }

    @Test
    fun `bill reminder patterns always contain a bill word`() {
        for (body in bodies + listOf("Minimum amount DUE Rs 500", "Kindly PAY Rs 200", "Bill of Rs 5 generated", "Amt due Rs 5")) {
            val folded = GatedPattern.fold(body)
            if (TransactionParser.BILL_WORDS.none { folded.contains(it) }) {
                assertEquals(null, TransactionParser.parseBillReminder("VM-ABCDEF-S", body), body)
                assertTrue(!DirectionCues.due.containsMatchIn(body), body)
            }
        }
    }

    @Test
    fun `gated cue finding and investment parsing equal the ungated versions`() {
        val symbols = CurrencyTable.defaultSymbolToCurrency
        for (body in bodies) {
            val folded = GatedPattern.fold(body)
            assertEquals(DirectionCues.find(body, folded, gated = false), DirectionCues.find(body, folded), body)
            assertEquals(
                InvestmentParser.parse("VM-ABCDEF-S", body, symbols, folded, gated = false),
                InvestmentParser.parse("VM-ABCDEF-S", body, symbols, folded),
                body,
            )
        }
    }

    @Test
    fun `fold keeps the length`() {
        for (body in bodies) assertEquals(body.length, GatedPattern.fold(body).length)
        assertTrue(GatedPattern.fold("ſK").let { it == "sk" }, GatedPattern.fold("ſK"))
    }
}
