package app.dak.premium

import app.dak.premium.consent.ConsentGatedPremiumGateway
import app.dak.premium.consent.ConsentGatedQueryUnderstanding
import app.dak.premium.consent.DataFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntitlementsTest {

    @Test
    fun `free tier grants nothing`() = runTest {
        Feature.entries.forEach { assertFalse(FreeEntitlements.has(it), "$it") }
        assertEquals(emptySet(), FreeEntitlements.granted.first())
    }

    @Test
    fun `static entitlements update has and the flow together`() = runTest {
        val e = StaticEntitlements(setOf(Feature.WEBHOOKS))
        assertTrue(e.has(Feature.WEBHOOKS))
        assertFalse(e.has(Feature.TRANSLATION))
        e.set(setOf(Feature.TRANSLATION, Feature.AI_SEARCH))
        assertFalse(e.has(Feature.WEBHOOKS))
        assertTrue(e.has(Feature.TRANSLATION))
        assertEquals(setOf(Feature.TRANSLATION, Feature.AI_SEARCH), e.granted.first())
    }

    @Test
    fun `every feature has a user-facing summary`() {
        Feature.entries.forEach { assertTrue(it.summary.isNotBlank() && it.summary.first().isUpperCase(), "$it") }
        assertEquals(Feature.entries.size, Feature.entries.map { it.summary }.toSet().size)
    }

    @Test
    fun `no-op implementations are unavailable and never succeed`() = runTest {
        assertFalse(NoOpPremiumGateway.isAvailable)
        assertFalse(NoOpPremiumGateway.sendWebhook(WebhookRequest("https://x.invalid", "{}", ByteArray(0))))
        assertFalse(NoOpPremiumGateway.relayCiphertext("p", ByteArray(1)))
        assertFalse(NoOpTranslator.isAvailable)
        assertNull(NoOpTranslator.detectLanguage("hola"))
        assertNull(NoOpTranslator.translate("hola", "en"))
        assertFalse(NoOpQueryUnderstanding.isAvailable)
        assertNull(NoOpQueryUnderstanding.toStructuredQuery("x"))
    }

    @Test
    fun `gated wrappers never report more availability than the delegate`() = runTest {
        val allGranted: (DataFlow) -> Boolean = { true }
        assertFalse(ConsentGatedPremiumGateway(NoOpPremiumGateway, allGranted).isAvailable)
        assertFalse(ConsentGatedQueryUnderstanding(NoOpQueryUnderstanding, allGranted).isAvailable)
        // With consent, a failing delegate still reports failure (not a fake success).
        assertFalse(ConsentGatedPremiumGateway(NoOpPremiumGateway, allGranted).sendWebhook(WebhookRequest("u", "{}", ByteArray(0))))
    }

    @Test
    fun `gates consult only their own flow`() = runTest {
        val asked = mutableListOf<DataFlow>()
        val gate: (DataFlow) -> Boolean = { asked += it; false }
        val gw = ConsentGatedPremiumGateway(NoOpPremiumGateway, gate)
        gw.sendWebhook(WebhookRequest("u", "{}", ByteArray(0)))
        gw.relayCiphertext("p", ByteArray(0))
        ConsentGatedQueryUnderstanding(NoOpQueryUnderstanding, gate).toStructuredQuery("q")
        assertEquals(listOf(DataFlow.WEBHOOKS, DataFlow.WEB_RELAY, DataFlow.AI_SEARCH), asked)
    }
}
