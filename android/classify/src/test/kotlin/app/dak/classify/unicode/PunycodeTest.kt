package app.dak.classify.unicode

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PunycodeTest {

    /** RFC 3492 §7.1 samples, plus IDN labels checked against Python's `punycode` codec. */
    private val vectors = listOf(
        "ليهمابتكلموشعربي؟" to "egbpdaj6bu4bxfgehfvwxn",
        "他们为什么不说中文" to "ihqwcrb4cv8a8dqg056pqjye",
        "3年B組金八先生" to "3B-ww4c5e180e575a65lsy2b",
        "安室奈美恵-with-SUPER-MONKEYS" to "-with-SUPER-MONKEYS-pc58ag80a8qai00g7n9n",
        "bücher" to "bcher-kva",
        "münchen" to "mnchen-3ya",
        "उदाहरण" to "p1b6ci4b4b3a",
        "pаypal" to "pypal-4ve", // Cyrillic а
    )

    @Test
    fun `encodes and decodes the reference vectors`() {
        for ((unicode, puny) in vectors) {
            assertEquals(puny, Punycode.encode(unicode), unicode)
            assertEquals(unicode, Punycode.decode(puny), puny)
        }
    }

    @Test
    fun `round trips random strings including astral code points`() {
        val random = Random(7)
        val pool = "abcxyz019-".map { it.code } + listOf(0xE9, 0x430, 0x928, 0x94D, 0x9A6, 0x627, 0x4E2D, 0x1F600, 0x1D400)
        repeat(2_000) {
            val s = buildString { repeat(random.nextInt(1, 20)) { appendCodePoint(pool[random.nextInt(pool.size)]) } }
            val encoded = Punycode.encode(s)!!
            assertEquals(s, Punycode.decode(encoded), s)
        }
    }

    @Test
    fun `rejects invalid input instead of throwing`() {
        assertNull(Punycode.decode("abé-x"), "non-basic code point in the basic part")
        assertNull(Punycode.decode("a-!"), "invalid digit")
        assertNull(Punycode.decode("99999999999"), "overflow")
        assertNull(Punycode.decode("a-9"), "truncated variable-length integer")
        assertNull(Punycode.encode("a\uD800b"), "unpaired surrogate")
        assertNull(Punycode.encode("a".repeat(Punycode.MAX_INPUT + 1)))
    }
}
