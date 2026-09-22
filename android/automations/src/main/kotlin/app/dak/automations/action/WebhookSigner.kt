package app.dak.automations.action

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs outbound webhook bodies (the premium [app.dak.automations.rule.ActionSpec.Webhook] action),
 * Stripe-style: HMAC-SHA256 over `"<timestamp>.<body>"`, sent as a header value the receiving endpoint
 * can verify without seeing the raw secret.
 */
public object WebhookSigner {

    private const val ALGORITHM = "HmacSHA256"

    /** @return a header value of the form `t=<timestampSeconds>,v1=<hex hmac>`. */
    public fun sign(body: String, secret: String, timestampMillis: Long): String {
        val timestampSeconds = timestampMillis / 1000
        val signedPayload = "$timestampSeconds.$body"
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), ALGORITHM))
        val digest = mac.doFinal(signedPayload.toByteArray(StandardCharsets.UTF_8))
        return "t=$timestampSeconds,v1=${digest.toHex()}"
    }

    /** Verifies a header value produced by [sign], for tests and for a receiving endpoint written in Kotlin. */
    public fun verify(body: String, secret: String, header: String): Boolean {
        val parts = header.split(',').associate {
            val (k, v) = it.split('=', limit = 2).takeIf { kv -> kv.size == 2 } ?: return false
            k to v
        }
        val t = parts["t"]?.toLongOrNull() ?: return false
        val v1 = parts["v1"] ?: return false
        val expected = sign(body, secret, t * 1000)
        val expectedV1 = expected.substringAfter("v1=")
        return constantTimeEquals(v1, expectedV1)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
