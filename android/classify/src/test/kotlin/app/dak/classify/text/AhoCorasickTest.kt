package app.dak.classify.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AhoCorasickTest {

    private fun found(ac: AhoCorasick, text: String): Set<Int> = ac.matches(text).stream().toArray().toSet()

    @Test
    fun findsOverlappingAndNestedPatterns() {
        val ac = AhoCorasick(listOf("he", "she", "his", "hers", "e"))
        assertEquals(setOf(0, 1, 3, 4), found(ac, "ushers"))
        assertEquals(setOf(2), found(ac, "this"))
        assertEquals(emptySet(), found(ac, "xyz"))
        assertTrue(ac.containsAny("ushers"))
        assertFalse(ac.containsAny("xyz"))
    }

    @Test
    fun foldsCaseLikeAUnicodeCaseInsensitiveRegex() {
        val ac = AhoCorasick(listOf("otp", "kyc", "sale"))
        assertEquals(setOf(0), found(ac, "Your OTP is"))
        assertEquals(setOf(1), found(ac, "update KYC")) // Kelvin sign folds to k, as Regex(IGNORE_CASE) matches it
        assertEquals(setOf(2), found(ac, "big ſALE")) // long s
        assertTrue(Regex("kyc", RegexOption.IGNORE_CASE).containsMatchIn("KYC"))
    }

    @Test
    fun matchesNonAsciiPatternsExactly() {
        val ac = AhoCorasick(listOf("ओटीपी", "₹"))
        assertEquals(setOf(0), found(ac, "आपका ओटीपी 1234 है"))
        assertEquals(setOf(1), found(ac, "paid ₹500"))
        assertEquals(emptySet(), found(ac, "ओटी पी"))
    }

    @Test
    fun honoursTheLimit() {
        val ac = AhoCorasick(listOf("end"))
        assertTrue(ac.containsAny("the end", limit = 7))
        assertFalse(ac.containsAny("the end", limit = 6))
    }

    @Test
    fun agreesWithNaiveSearchOnRandomInput() {
        val r = Random(7)
        val alphabet = "abcAB ₹क"
        repeat(300) {
            val patterns = List(1 + r.nextInt(8)) { String(CharArray(1 + r.nextInt(4)) { alphabet[r.nextInt(alphabet.length)] }) }
            val ac = AhoCorasick(patterns)
            repeat(20) {
                val text = String(CharArray(r.nextInt(40)) { alphabet[r.nextInt(alphabet.length)] })
                val folded = text.map(AhoCorasick::fold).joinToString("")
                val expected = patterns.indices.filter { folded.contains(patterns[it].map(AhoCorasick::fold).joinToString("")) }.toSet()
                assertEquals(expected, found(ac, text), "patterns=$patterns text=$text")
                assertEquals(expected.isNotEmpty(), ac.containsAny(text))
            }
        }
    }
}
