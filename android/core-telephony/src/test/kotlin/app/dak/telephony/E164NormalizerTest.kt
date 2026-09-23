package app.dak.telephony

import app.dak.telephony.number.E164Normalizer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class E164NormalizerTest {
    private val n = E164Normalizer()

    @Test
    fun nationalNumbersGetTheSimCountryCode() {
        assertEquals("+919812345678", n.normalize("9812345678", "in"))
        assertEquals("+919812345678", n.normalize("098123 45678", "IN"))
        assertEquals("+14155552671", n.normalize("(415) 555-2671", "us"))
        assertEquals("+447911123456", n.normalize("07911 123456", "gb"))
    }

    @Test
    fun internationalNumbersAreCanonicalisedRegardlessOfSimCountry() {
        assertEquals("+919812345678", n.normalize("+91 98123-45678", "us"))
        assertEquals("+919812345678", n.normalize("+919812345678", null))
        assertEquals("+919812345678", n.normalize("0091 9812345678", "gb"))
    }

    @Test
    fun theSimHomeCountryWinsNotTheNetwork() {
        // A saved Indian contact without prefix, while roaming in the UAE on an Indian SIM.
        assertEquals("+919812345678", n.normalize("9812345678", "in"))
        // The same digits on a UAE SIM are not a valid UAE number and stay untouched.
        assertEquals("9812345678", n.normalize("9812345678", "ae"))
    }

    @Test
    fun shortCodesAndAlphanumericSendersAreNeverTouched() {
        assertEquals("56161", n.normalize("56161", "in"))
        assertEquals("121", n.normalize("121", "in"))
        assertEquals("VM-HDFCBK", n.normalize("VM-HDFCBK", "in"))
        assertEquals("AMAZON", n.normalize("AMAZON", "in"))
        assertEquals("*123#", n.normalize("*123#", "in"))
        assertEquals("user@example.com", n.normalize("user@example.com", "in"))
    }

    @Test
    fun unknownCountryLeavesNationalNumbersAlone() {
        assertEquals("9812345678", n.normalize("9812345678", null))
        assertEquals("9812345678", n.normalize("9812345678", "zz1"))
    }

    @Test
    fun localOnlyNumbersAreNotMangled() {
        // A 7-digit US local number is only "possible locally": prefixing +1 would produce a wrong number.
        assertEquals("555 2671", n.normalize("555 2671", "us"))
    }

    @Test
    fun garbageIsReturnedUnchanged() {
        assertEquals("12+34567890", n.normalize("12+34567890", "in"))
        assertEquals("", n.normalize("", "in"))
        assertEquals("  ", n.normalize("  ", "in"))
    }

    @Test
    fun matchKeysUnifySpellings() {
        assertEquals(n.matchKey("+919812345678", "in"), n.matchKey("98123 45678", "in"))
        assertEquals("VM-HDFCBK", n.matchKey("vm-hdfcbk ", "in"))
        assertEquals("56161", n.matchKey("56-161", "in"))
        assertEquals("a@b.com", n.matchKey("A@B.com", "in"))
    }

    @Test
    fun normalisableHeuristic() {
        assertTrue(E164Normalizer.isNormalisable("+1 (415) 555-2671"))
        assertFalse(E164Normalizer.isNormalisable("123456"))
        assertFalse(E164Normalizer.isNormalisable("TX-ABCDEF"))
    }
}
