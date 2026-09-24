package app.dak.ui.conversation

import app.dak.classify.LinkExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSafetyWarningTest {

    private val official = LinkExtractor.extract("see https://www.hdfcbank.com/ for details").single()
    private val plain = LinkExtractor.extract("see https://shop.example/offer").single()

    @Test
    fun officialLinkFromAnOrdinaryMessageOpensStraightAway() {
        assertNull(LinkSafety.warningFor(official, unknownSender = false))
        assertNull(LinkSafety.warningFor(official, unknownSender = true))
    }

    @Test
    fun anyLinkInAFlaggedFakeCreditNeedsASecondTap() {
        val warning = LinkSafety.warningFor(official, unknownSender = false, scamFlagged = true)
        assertNotNull(warning)
        checkNotNull(warning)
        assertTrue(warning.scamFlagged)
        assertTrue(warning.unknownSender) // shows the "never share your PIN / OTP" line too
        assertNotNull(LinkSafety.warningFor(plain, unknownSender = false, scamFlagged = true))
    }

    @Test
    fun unknownSenderRuleIsUnchanged() {
        assertNull(LinkSafety.warningFor(plain, unknownSender = false))
        assertEquals(false, LinkSafety.warningFor(plain, unknownSender = true)?.scamFlagged)
    }
}
