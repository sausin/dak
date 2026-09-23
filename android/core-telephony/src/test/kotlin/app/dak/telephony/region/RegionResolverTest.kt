package app.dak.telephony.region

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionResolverTest {

    @Test
    fun simCountryWinsOverNetworkAndLocale() {
        val p = RegionResolver.resolve("gb", "fr", "IN")
        assertEquals("GB", p.countryIso)
        assertEquals(RegionSource.SIM, p.source)
    }

    @Test
    fun fallsBackToNetworkThenLocale() {
        assertEquals(RegionProfile("US", RegionSource.NETWORK), RegionResolver.resolve(null, "us", "DE"))
        assertEquals(RegionProfile("DE", RegionSource.LOCALE), RegionResolver.resolve("", " ", "de"))
    }

    @Test
    fun neverAssumesIndia() {
        val p = RegionResolver.resolve(null, null, "")
        assertEquals(RegionProfile.UNKNOWN, p)
        assertNull(p.countryIso)
        assertFalse(p.isIndia)
        assertFalse(p.dltSenderRules)
        assertNull(p.homeCurrency)
    }

    @Test
    fun rejectsMalformedAndNonCountryCodes() {
        assertNull(RegionResolver.normalize("ZZ"))
        assertNull(RegionResolver.normalize("I1"))
        assertNull(RegionResolver.normalize("IND"))
        assertEquals("IN", RegionResolver.normalize(" in "))
        // A garbage SIM country falls through to the network.
        assertEquals("AE", RegionResolver.resolve("xx", "ae", null).countryIso)
    }

    @Test
    fun indiaProfileEnablesIndiaOnlyRules() {
        val p = RegionResolver.resolve("in", null, null)
        assertTrue(p.isIndia)
        assertTrue(p.dltSenderRules)
        assertTrue(p.usesIndianGrouping)
        assertEquals("INR", p.homeCurrency)
    }

    @Test
    fun homeCurrencyFollowsTheRegion() {
        assertEquals("USD", RegionResolver.resolve("us", null, null).homeCurrency)
        assertEquals("CAD", RegionResolver.resolve("ca", null, null).homeCurrency)
        assertEquals("AUD", RegionResolver.resolve("au", null, null).homeCurrency)
        assertEquals("SGD", RegionResolver.resolve("sg", null, null).homeCurrency)
        assertEquals("GBP", RegionResolver.resolve("gb", null, null).homeCurrency)
        assertEquals("EUR", RegionResolver.resolve("de", null, null).homeCurrency)
        assertEquals("AED", RegionResolver.resolve("ae", null, null).homeCurrency)
        assertFalse(RegionResolver.resolve("us", null, null).usesIndianGrouping)
        assertFalse(RegionResolver.resolve("gb", null, null).dltSenderRules)
    }

    @Test
    fun emergencyNumbersComeFromLibphonenumberData() {
        assertEquals("911", EmergencyNumbers.forRegion("us").first())
        assertEquals("999", EmergencyNumbers.forRegion("GB").first())
        assertTrue("112" in EmergencyNumbers.forRegion("GB"))
        assertEquals(listOf("112"), EmergencyNumbers.forRegion("de"))
        assertEquals("000", EmergencyNumbers.forRegion("au").first())
        assertTrue("112" in EmergencyNumbers.forRegion("in"))
        assertEquals(listOf(EmergencyNumbers.GSM_EMERGENCY), EmergencyNumbers.forRegion(null))
    }
}
