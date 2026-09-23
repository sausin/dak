package app.dak.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinHasherTest {
    private val hasher = PinHasher()

    @Test
    fun `pin policy accepts 4 to 8 ascii digits only`() {
        assertTrue(PinPolicy.isValid("1234".toCharArray()))
        assertTrue(PinPolicy.isValid("12345678".toCharArray()))
        assertFalse(PinPolicy.isValid("123".toCharArray()))
        assertFalse(PinPolicy.isValid("123456789".toCharArray()))
        assertFalse(PinPolicy.isValid("12a4".toCharArray()))
        assertFalse(PinPolicy.isValid("12 34".toCharArray()))
        // Non-ASCII digits (Devanagari) are rejected so the same PIN is typed the same way everywhere.
        assertFalse(PinPolicy.isValid("१२३४".toCharArray()))
    }

    @Test
    fun `correct pin verifies and wrong pin does not`() {
        val record = hasher.hash("4821".toCharArray())
        assertTrue(hasher.verify("4821".toCharArray(), record))
        assertFalse(hasher.verify("4822".toCharArray(), record))
        assertFalse(hasher.verify("48210".toCharArray(), record))
        assertFalse(hasher.verify("".toCharArray(), record))
    }

    @Test
    fun `same pin gets a different salt and hash each time`() {
        val a = hasher.hash("000000".toCharArray())
        val b = hasher.hash("000000".toCharArray())
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.hash.contentEquals(b.hash))
        assertEquals(16, a.salt.size)
        assertEquals(32, a.hash.size)
    }

    @Test
    fun `record round trips through its encoding and never contains the pin`() {
        val record = hasher.hash("13572468".toCharArray())
        val encoded = record.encode()
        assertFalse(encoded.contains("13572468"))
        assertTrue(encoded.startsWith("v1:${PinHasher.DEFAULT_ITERATIONS}:"))
        val decoded = assertNotNull(PinRecord.decode(encoded))
        assertEquals(record.iterations, decoded.iterations)
        assertTrue(hasher.verify("13572468".toCharArray(), decoded))
        assertFalse(record.toString().contains(encoded.substringAfterLast(':')))
    }

    @Test
    fun `iterations are at least 100k and stored cost is honoured`() {
        assertTrue(PinHasher.DEFAULT_ITERATIONS >= 100_000)
        assertFailsWith<IllegalArgumentException> { PinHasher(iterations = 99_999) }
        val strong = PinHasher(iterations = 150_000)
        val record = strong.hash("2468".toCharArray())
        assertEquals(150_000, record.iterations)
        // A default hasher still verifies it, because the cost comes from the record.
        assertTrue(hasher.verify("2468".toCharArray(), record))
    }

    @Test
    fun `malformed encodings decode to null`() {
        assertNull(PinRecord.decode(null))
        assertNull(PinRecord.decode(""))
        assertNull(PinRecord.decode("v2:120000:AAAA:AAAA"))
        assertNull(PinRecord.decode("v1:0:AAAA:AAAA"))
        assertNull(PinRecord.decode("v1:999999999:AAAA:AAAA"))
        assertNull(PinRecord.decode("v1:120000:not base64!:AAAA"))
        assertNull(PinRecord.decode("v1:120000::AAAA"))
        assertNull(PinRecord.decode("v1:120000:AAAA"))
    }

    @Test
    fun `hashing an invalid pin is refused`() {
        assertFailsWith<IllegalArgumentException> { hasher.hash("12".toCharArray()) }
    }

    @Test
    fun `constant time comparison`() {
        assertTrue(PinHasher.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(PinHasher.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(PinHasher.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertTrue(PinHasher.constantTimeEquals(byteArrayOf(), byteArrayOf()))
    }
}
