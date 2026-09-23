package app.dak.classify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaskerTest {

    @Test
    fun `never leaks a digit`() {
        val samples = listOf(
            "Rs 4500.00 debited from A/c XX1234 on 12-03-24. Avl bal Rs 12,340.00",
            "123456 is your OTP for login, do not share with anyone",
            "Card 4111 1111 1111 1234 charged USD 42.10",
            "Dear Rohit Sharma, your order #998877 has shipped, contact rohit@example.com",
        )
        for (s in samples) {
            val masked = Masker.mask(s)
            assertFalse(masked.any { it.isDigit() }, "leaked a digit in: $masked")
        }
    }

    @Test
    fun `masks urls and emails`() {
        val masked = Masker.mask("Visit https://example.com/offer or email support@example.com for help")
        assertTrue(masked.contains("<URL>"))
        assertTrue(masked.contains("<EMAIL>"))
        assertFalse(masked.contains("example.com"))
    }

    @Test
    fun `masks amounts and generic numbers`() {
        val masked = Masker.mask("Rs 2,500 debited, ref 1029384756")
        assertTrue(masked.contains("<AMT>") || masked.contains("<NUM>"))
    }

    @Test
    fun `masks a name after a salutation`() {
        val masked = Masker.mask("Dear Rohit Sharma, your KYC is pending")
        assertTrue(masked.contains("Dear <NAME>"))
        assertFalse(masked.contains("Rohit"))
    }

    @Test
    fun `masks card numbers as NUM not AMT`() {
        val masked = Masker.mask("Card 4111111111111234 was charged")
        assertTrue(masked.contains("<NUM>"))
    }
}
