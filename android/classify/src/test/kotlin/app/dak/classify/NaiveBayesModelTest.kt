package app.dak.classify

import app.dak.core.model.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaiveBayesModelTest {

    private val model = NaiveBayesModel.loadDefault()

    @Test
    fun `scores sum to approximately one`() {
        val scores = model.predict("Your OTP is 123456, do not share")
        assertTrue(Math.abs(scores.values.sum() - 1.0f) < 0.01f)
    }

    @Test
    fun `classifies an otp message`() {
        val scores = model.predict("Use 774521 as your one time password, do not share with anyone")
        assertEquals(Category.OTP, scores.maxByOrNull { it.value }?.key)
    }

    @Test
    fun `classifies a transaction message`() {
        val scores = model.predict("Rs 500 debited from your account for a purchase, avl bal Rs 1000")
        assertEquals(Category.TRANSACTION, scores.maxByOrNull { it.value }?.key)
    }

    @Test
    fun `classifies a promotion message`() {
        val scores = model.predict("Flat 60 percent off sale ends tonight, shop now and save big")
        assertEquals(Category.PROMOTION, scores.maxByOrNull { it.value }?.key)
    }

    @Test
    fun `classifies a spam message`() {
        val scores = model.predict("Congratulations you have won a lottery, click here to claim your prize now")
        assertEquals(Category.SPAM, scores.maxByOrNull { it.value }?.key)
    }

    @Test
    fun `classifies a hinglish personal message`() {
        val scores = model.predict("Kya kar raha hai, chal movie dekhne chalte hai aaj raat")
        assertEquals(Category.PERSONAL, scores.maxByOrNull { it.value }?.key)
    }
}
