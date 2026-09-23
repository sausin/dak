package app.dak.premium.consent

import app.dak.premium.PremiumGateway
import app.dak.premium.QueryUnderstanding
import app.dak.premium.WebhookRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentGatesTest {

    private class RecordingGateway : PremiumGateway {
        val webhooks = mutableListOf<WebhookRequest>()
        val relays = mutableListOf<String>()
        override val isAvailable: Boolean = true
        override suspend fun sendWebhook(request: WebhookRequest): Boolean { webhooks += request; return true }
        override suspend fun relayCiphertext(pairingId: String, ciphertext: ByteArray): Boolean { relays += pairingId; return true }
    }

    private val request = WebhookRequest("https://example.invalid/hook", "{}", ByteArray(1))

    @Test
    fun `nothing is sent without consent`() = runTest {
        val real = RecordingGateway()
        val ledger = ConsentLedger(InMemoryConsentStorage())
        val gated = ConsentGatedPremiumGateway(real, ledger::isGranted)
        assertFalse(gated.sendWebhook(request))
        assertFalse(gated.relayCiphertext("pair-1", ByteArray(4)))
        assertTrue(real.webhooks.isEmpty())
        assertTrue(real.relays.isEmpty())
    }

    @Test
    fun `each flow needs its own consent`() = runTest {
        val real = RecordingGateway()
        val ledger = ConsentLedger(InMemoryConsentStorage())
        val gated = ConsentGatedPremiumGateway(real, ledger::isGranted)
        ledger.grant(DataFlow.WEBHOOKS, "test")
        assertTrue(gated.sendWebhook(request))
        assertFalse(gated.relayCiphertext("pair-1", ByteArray(4)))
        ledger.grant(DataFlow.WEB_RELAY, "test")
        assertTrue(gated.relayCiphertext("pair-1", ByteArray(4)))
        assertEquals(1, real.webhooks.size)
        assertEquals(listOf("pair-1"), real.relays)
    }

    @Test
    fun `withdrawal stops the next send`() = runTest {
        val real = RecordingGateway()
        val ledger = ConsentLedger(InMemoryConsentStorage())
        val gated = ConsentGatedPremiumGateway(real, ledger::isGranted)
        ledger.grant(DataFlow.WEBHOOKS, "test")
        assertTrue(gated.sendWebhook(request))
        ledger.withdraw(DataFlow.WEBHOOKS, "test")
        assertFalse(gated.sendWebhook(request))
        assertEquals(1, real.webhooks.size)
    }

    @Test
    fun `ai search runs only with consent`() = runTest {
        var calls = 0
        val real = object : QueryUnderstanding {
            override val isAvailable = true
            override suspend fun toStructuredQuery(naturalLanguage: String): String { calls++; return "from:x" }
        }
        val ledger = ConsentLedger(InMemoryConsentStorage())
        val gated = ConsentGatedQueryUnderstanding(real, ledger::isGranted)
        assertFalse(gated.isAvailable)
        assertNull(gated.toStructuredQuery("spend on swiggy"))
        assertEquals(0, calls)
        ledger.grant(DataFlow.AI_SEARCH, "test")
        assertTrue(gated.isAvailable)
        assertEquals("from:x", gated.toStructuredQuery("spend on swiggy"))
    }
}
