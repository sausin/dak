package app.dak.classify.text

import app.dak.classify.adversarial.AdversarialCorpus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [AnalysisText]: what the regex analysis reads, and that `:finance`'s copy reads the same. */
class AnalysisTextTest {

    @Test
    fun `plain text is returned as is`() {
        val s = "Rs 500 credited to A/c XX1234. आपका खाता"
        assertSame(s, AnalysisText.of(s))
        assertSame(s, AnalysisText.capMarksKeepingOffsets(s))
    }

    @Test
    fun `right-to-left overrides are applied as displayed`() {
        assertEquals("Rs 50,000.00 credited", AnalysisText.of("Rs \u202E00.000,05\u202C credited"))
        assertEquals("OTP 123456", AnalysisText.of("OTP \u202E654321"))
        assertEquals("a cba\nd", AnalysisText.of("a \u202Eabc\nd"), "an override ends at the line end")
        assertEquals("x 😀y", AnalysisText.of("x \u202Ey😀\u2069"), "surrogate pairs stay in order")
    }

    @Test
    fun `invisible characters and bidi controls are dropped`() {
        assertEquals("KYC update", AnalysisText.of("K\u200BY\u00ADC\u2060 update\uFEFF"))
        assertEquals("pay here", AnalysisText.of("\u2066pay\u2069 \u200Ehere\u200F"))
    }

    @Test
    fun `combining mark floods are capped, real marks are kept`() {
        val zalgo = "4" + "̶".repeat(3000) + "82913"
        assertEquals("4" + "̶".repeat(AnalysisText.MAX_MARKS) + "82913", AnalysisText.of(zalgo))
        val kept = AnalysisText.capMarksKeepingOffsets(zalgo)
        assertEquals(zalgo.length, kept.length)
        assertEquals(AnalysisText.MAX_MARKS, kept.count { it == '̶' })
        // Hindi and Vietnamese keep all their marks.
        for (s in listOf("क्षिं", "Việt Nam", "tiếng")) assertSame(s, AnalysisText.of(s))
    }

    @Test
    fun `analysis is linear on a combining flood`() {
        val zalgo = "Z" + "̶́҉".repeat(100_000)
        val start = System.nanoTime()
        AnalysisText.of(zalgo)
        AnalysisText.capMarksKeepingOffsets(zalgo)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 500)
    }

    @Test
    fun `finance's copy agrees on every corpus body`() {
        val bodies = AdversarialCorpus.load().entries.map { it.body.take(20_000) } + listOf(
            "Rs \u202E00.000,05\u202C credited", "4" + "̶".repeat(50) + "8", "x \u202Ey😀\u2069", "\u202E",
        )
        for (b in bodies) {
            assertEquals(AnalysisText.of(b), app.dak.finance.money.AnalysisText.of(b), b.take(80))
            assertEquals(AnalysisText.capMarksKeepingOffsets(b), app.dak.finance.money.AnalysisText.capMarksKeepingOffsets(b), b.take(80))
        }
    }
}
