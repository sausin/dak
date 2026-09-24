package app.dak.premium.consent

import app.dak.premium.PremiumGateway
import app.dak.premium.QueryUnderstanding
import app.dak.premium.WebhookRequest

/**
 * Wraps the real [PremiumGateway] so nothing leaves the phone without a current consent, whatever the caller (a rule
 * created before consent, a restored rule, a future screen that forgot to ask). Webhooks need [DataFlow.WEBHOOKS];
 * everything through the relay (web client, relay-rule webhook channel) needs [DataFlow.WEB_RELAY]. A blocked call
 * returns false, which the automation engine records as a failed run, so the user can see it in the run history.
 */
class ConsentGatedPremiumGateway(
    private val delegate: PremiumGateway,
    private val isGranted: (DataFlow) -> Boolean,
) : PremiumGateway {
    override val isAvailable: Boolean get() = delegate.isAvailable

    override suspend fun sendWebhook(request: WebhookRequest): Boolean =
        isGranted(DataFlow.WEBHOOKS) && delegate.sendWebhook(request)

    override suspend fun relayCiphertext(pairingId: String, ciphertext: ByteArray): Boolean =
        isGranted(DataFlow.WEB_RELAY) && delegate.relayCiphertext(pairingId, ciphertext)
}

/** AI-assisted search only runs with [DataFlow.AI_SEARCH] consent; otherwise search stays keyword-only on the phone. */
class ConsentGatedQueryUnderstanding(
    private val delegate: QueryUnderstanding,
    private val isGranted: (DataFlow) -> Boolean,
) : QueryUnderstanding {
    override val isAvailable: Boolean get() = delegate.isAvailable && isGranted(DataFlow.AI_SEARCH)

    override suspend fun toStructuredQuery(naturalLanguage: String): String? =
        if (isGranted(DataFlow.AI_SEARCH)) delegate.toStructuredQuery(naturalLanguage) else null
}
