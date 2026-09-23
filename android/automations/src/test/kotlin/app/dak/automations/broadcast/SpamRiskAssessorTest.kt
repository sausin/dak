package app.dak.automations.broadcast

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpamRiskAssessorTest {

    private fun level(text: String, n: Int = 5) = SpamRiskAssessor.assess(text, n).level

    @Test
    fun personalMessagesAreLowRisk() {
        assertEquals(SpamRiskLevel.LOW, level("Happy Diwali to you and the family!"))
        assertEquals(SpamRiskLevel.LOW, level("Are you free on Sunday for lunch? Many happy returns, Anita"))
        assertEquals(SpamRiskLevel.LOW, level("दीवाली की हार्दिक शुभकामनाएँ"))
        assertEquals(SpamRiskLevel.LOW, level("Kal milte hain, 7 baje"))
        assertFalse(SpamRiskAssessor.assess("Hello", 5).requiresExtraConfirmation)
    }

    @Test
    fun linkWithOfferIsHigh() {
        val r = SpamRiskAssessor.assess("Flat 50% off this weekend only! Shop now at bit.ly/xyz", 5)
        assertEquals(SpamRiskLevel.HIGH, r.level)
        assertTrue(SpamSignal.LINK in r.signals)
        assertTrue(SpamSignal.PROMO_WORDS in r.signals)
        assertTrue(SpamSignal.MONEY_BAIT in r.signals)
    }

    @Test
    fun hindiAndHinglishPromotionsAreFlagged() {
        assertEquals(SpamRiskLevel.HIGH, level("दिवाली सेल! 40% छूट, अभी खरीदें"))
        assertEquals(SpamRiskLevel.HIGH, level("Diwali par bumper chhoot, jaldi karein! www.example.in"))
        assertTrue(SpamRiskAssessor.assess("Paisa kamao ghar baithe, inaam jeeto", 3).looksPromotional)
    }

    @Test
    fun singleSignalIsElevated() {
        assertEquals(SpamRiskLevel.ELEVATED, level("Photos from the trip: https://photos.example.com/abc"))
        assertEquals(SpamRiskLevel.ELEVATED, level("Hi all", 20))
        assertTrue(SpamRiskAssessor.assess("Hi all", 20).requiresExtraConfirmation)
    }

    @Test
    fun largeListPlusPromoIsHigh() {
        assertEquals(SpamRiskLevel.HIGH, level("Limited time sale at my boutique", 25))
    }

    @Test
    fun shoutingIsASignal() {
        val r = SpamRiskAssessor.assess("URGENT CALL NOW TO CLAIM", 2)
        assertTrue(SpamSignal.ALL_CAPS in r.signals)
        assertTrue(SpamSignal.CALL_TO_ACTION in r.signals)
        assertEquals(SpamRiskLevel.HIGH, r.level)
    }
}
