package app.dak.premium

/**
 * Seam for everything that needs our servers (relay, webhooks, web client, send API).
 * Server components only ever see ciphertext.
 */
interface PremiumGateway {
    val isAvailable: Boolean

    /** POST a signed webhook. Returns true on 2xx. */
    suspend fun sendWebhook(request: WebhookRequest): Boolean

    /** Push an already-encrypted blob to the paired web client relay. */
    suspend fun relayCiphertext(pairingId: String, ciphertext: ByteArray): Boolean
}

data class WebhookRequest(
    val url: String,
    val jsonBody: String,
    /** Secret used to HMAC-sign the body; never leaves the device except as a signature. */
    val signingSecret: ByteArray,
)

object NoOpPremiumGateway : PremiumGateway {
    override val isAvailable: Boolean = false
    override suspend fun sendWebhook(request: WebhookRequest): Boolean = false
    override suspend fun relayCiphertext(pairingId: String, ciphertext: ByteArray): Boolean = false
}
