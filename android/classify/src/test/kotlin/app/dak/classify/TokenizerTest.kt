package app.dak.classify

import kotlin.test.Test
import kotlin.test.assertEquals

class TokenizerTest {

    @Test
    fun `splits latin words and numbers`() {
        assertEquals(listOf("your", "otp", "is", "123456"), Tokenizer.tokenize("Your OTP is 123456!"))
    }

    @Test
    fun `lowercases tokens`() {
        assertEquals(listOf("hdfc", "bank"), Tokenizer.tokenize("HDFC Bank"))
    }

    @Test
    fun `handles devanagari script`() {
        assertEquals(listOf("आपका", "ओटीपी", "665544"), Tokenizer.tokenize("आपका ओटीपी 665544"))
    }

    @Test
    fun `handles hinglish mixed text`() {
        assertEquals(listOf("aapka", "otp", "990011", "hai"), Tokenizer.tokenize("aapka otp 990011 hai"))
    }

    @Test
    fun `drops punctuation`() {
        assertEquals(listOf("rs", "4500", "00", "debited"), Tokenizer.tokenize("Rs.4500.00 debited!!"))
    }
}
