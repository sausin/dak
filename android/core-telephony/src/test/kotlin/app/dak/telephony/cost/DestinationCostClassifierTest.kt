package app.dak.telephony.cost

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DestinationCostClassifierTest {
    private val c = DestinationCostClassifier()

    private fun kind(dest: String, sim: String? = "in", net: String? = "in", roaming: Boolean = false) =
        c.classify(dest, sim, net, roaming).kind

    @Test
    fun ordinaryDomesticNumbersAreNormal() {
        assertEquals(CostKind.NORMAL, kind("9812345678"))
        assertEquals(CostKind.NORMAL, kind("+919812345678"))
        assertEquals(CostKind.NORMAL, kind("098123 45678"))
        assertEquals(CostKind.NORMAL, kind("+14155552671", sim = "us", net = "us"))
    }

    @Test
    fun foreignNumbersAreInternational() {
        val v = c.classify("+971501234567", "in", "in", false)
        assertEquals(CostKind.INTERNATIONAL, v.kind)
        assertEquals("AE", v.destinationRegion)
        assertEquals(CostKind.INTERNATIONAL, kind("0044 7911 123456"))
        assertEquals(CostKind.INTERNATIONAL, kind("+919812345678", sim = "ae", net = "ae"))
    }

    @Test
    fun sharedCallingCodeIsNotInternational() {
        // US SIM texting a Canadian number: same +1 calling code.
        assertEquals(CostKind.NORMAL, kind("+16135550123", sim = "us", net = "us"))
    }

    @Test
    fun roamingAbroadIsFlaggedButDomesticRoamingIsNot() {
        val abroad = c.classify("+919812345678", "in", "ae", true)
        assertEquals(CostKind.ROAMING, abroad.kind)
        assertTrue(abroad.roaming)
        val national = c.classify("+919812345678", "in", "in", true)
        assertEquals(CostKind.NORMAL, national.kind)
        assertFalse(national.roaming)
        // Roaming flag with an unknown network country is treated as abroad (fail towards warning).
        assertEquals(CostKind.ROAMING, kind("9812345678", net = null, roaming = true))
    }

    @Test
    fun internationalWinsOverRoamingButKeepsTheFlag() {
        val v = c.classify("+971501234567", "in", "ae", true)
        assertEquals(CostKind.INTERNATIONAL, v.kind)
        assertTrue(v.roaming)
    }

    @Test
    fun emergencyNumbersAreNotedNotBlocked() {
        val v = c.classify("112", "in", "in", false)
        assertEquals(CostKind.EMERGENCY, v.kind)
        assertFalse(v.needsConfirmation)
        assertEquals(CostKind.EMERGENCY, kind("911", sim = "us", net = "us"))
        // Emergency number of the visited country while roaming.
        assertEquals(CostKind.EMERGENCY, kind("999", sim = "in", net = "gb", roaming = true))
    }

    @Test
    fun alphanumericSendersCannotBeRepliedTo() {
        val v = c.classify("VM-HDFCBK", "in", "in", false)
        assertEquals(CostKind.ALPHANUMERIC, v.kind)
        assertTrue(v.needsConfirmation)
        assertEquals(CostKind.ALPHANUMERIC, kind("AMAZON"))
    }

    @Test
    fun premiumRateNumbersAreStrong() {
        // UK 09xx premium-rate numbers, from a UK SIM.
        val v = c.classify("09098790000", "gb", "gb", false)
        assertEquals(CostKind.PREMIUM_RATE, v.kind)
        assertEquals(CostSeverity.STRONG, v.severity)
    }

    @Test
    fun shortCodesAreJudgedWithShortNumberMetadata() {
        // libphonenumber 8.13.x metadata: TRAI 1909 is toll-free; Indian commercial short codes have no tariff data
        // (India's short-number metadata lists no premium ranges), so they get the mild unknown-cost warning.
        assertEquals(CostKind.TOLL_FREE, kind("1909"))
        assertEquals(CostKind.UNKNOWN_SHORT_CODE, kind("56161"))
        assertEquals(CostKind.UNKNOWN_SHORT_CODE, kind("57575"))
        assertTrue(c.classify("56161", "in", "in", false).needsConfirmation)
        assertFalse(c.classify("1909", "in", "in", false).needsConfirmation)
        // US premium-rate short code, from a US SIM.
        assertEquals(CostKind.PREMIUM_RATE, kind("24280", sim = "us", net = "us"))
        // Short codes are judged in the SIM home region: the same digits from an Indian SIM are not US premium.
        assertEquals(CostKind.UNKNOWN_SHORT_CODE, kind("24280"))
    }

    @Test
    fun roamingDoesNotHidePremiumShortCodes() {
        val v = c.classify("24280", "us", "in", true)
        assertEquals(CostKind.PREMIUM_RATE, v.kind)
        assertTrue(v.roaming)
    }

    @Test
    fun unknownHomeCountryShortCodesAreMildlyWarned() {
        assertEquals(CostKind.UNKNOWN_SHORT_CODE, kind("56161", sim = null, net = null))
        assertEquals(CostKind.NORMAL, kind("+919812345678", sim = null, net = null))
    }

    @Test
    fun serviceCodesAndEmailAreLeftAlone() {
        assertEquals(CostKind.NORMAL, kind("*121#"))
        assertEquals(CostKind.NORMAL, kind("someone@example.com"))
        assertEquals(CostKind.NORMAL, kind(""))
    }

    @Test
    fun networkCountryIsUsedWhenSimCountryIsUnknown() {
        assertEquals(CostKind.INTERNATIONAL, kind("+971501234567", sim = null, net = "in"))
    }
}
