package app.dak.finance.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `:finance`'s copy of [AnalysisText] on its own (the transaction parser reads bodies through it). `:classify`'s
 * `AnalysisTextTest` also checks this copy agrees with the classifier's on every adversarial corpus body.
 */
class AnalysisTextTest {

    @Test
    fun `plain text is returned as is`() {
        val s = "Rs 500 credited to A/c XX1234. आपका खाता"
        assertSame(s, AnalysisText.of(s))
        assertSame(s, AnalysisText.capMarksKeepingOffsets(s))
    }

    @Test
    fun `right-to-left overrides are applied as displayed`() {
        assertEquals("Rs 50,000.00 credited", AnalysisText.of("Rs ‮00.000,05‬ credited"))
        assertEquals("OTP 123456", AnalysisText.of("OTP ‮654321"))
        assertEquals("a cba\nd", AnalysisText.of("a ‮abc\nd"), "an override ends at the line end")
        assertEquals("a cba\rd", AnalysisText.of("a ‮abc\rd"))
        assertEquals("x 😀y", AnalysisText.of("x ‮y😀⁩"), "surrogate pairs stay in order")
        assertEquals("", AnalysisText.of("‮"))
        assertEquals("ab", AnalysisText.of("‮b​a"), "invisible characters inside an override are dropped")
    }

    @Test
    fun `invisible characters and bidi controls are dropped`() {
        assertEquals("KYC update", AnalysisText.of("K​Y­C⁠ update﻿"))
        assertEquals("pay here", AnalysisText.of("⁦pay⁩ ‎here‏"))
    }

    @Test
    fun `combining mark floods are capped, real marks are kept`() {
        val zalgo = "4" + "̶".repeat(3000) + "82913"
        assertEquals("4" + "̶".repeat(AnalysisText.MAX_MARKS) + "82913", AnalysisText.of(zalgo))
        val kept = AnalysisText.capMarksKeepingOffsets(zalgo)
        assertEquals(zalgo.length, kept.length)
        assertEquals(AnalysisText.MAX_MARKS, kept.count { it == '̶' })
        for (s in listOf("क्षिं", "Việt Nam", "tiếng")) assertSame(s, AnalysisText.of(s))
        // A flood inside an override is capped too.
        val reversed = AnalysisText.of("‮" + "̶".repeat(40) + "5")
        assertEquals(1 + AnalysisText.MAX_MARKS, reversed.length)
    }

    @Test
    fun `analysis is linear on a combining flood`() {
        val zalgo = "Z" + "̶́҉".repeat(100_000)
        val start = System.nanoTime()
        AnalysisText.of(zalgo)
        AnalysisText.capMarksKeepingOffsets(zalgo)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 500)
    }

    @Test
    fun `the parser reads amounts hidden by a bidi override`() {
        val t = TransactionParserProbe.parse("VM-HDFCBK", "Rs ‮00.005,1‬ debited from A/c XX1234 on 01-01-26")
        assertEquals(150000L, t?.amountMinor)
    }
}

/** Keeps the parser dependency in one place for this test. */
private object TransactionParserProbe {
    fun parse(sender: String, body: String) = app.dak.finance.parser.TransactionParser.parse(sender, body)
}
