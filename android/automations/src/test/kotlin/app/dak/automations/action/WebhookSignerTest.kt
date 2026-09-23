package app.dak.automations.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookSignerTest {

    @Test
    fun `known vector matches an independently computed HMAC-SHA256`() {
        // Verified with: printf '1700000000.{"hello":"world"}' | openssl dgst -sha256 -hmac "whsec_test_secret"
        val body = """{"hello":"world"}"""
        val secret = "whsec_test_secret"
        val timestampMillis = 1_700_000_000_000L

        val header = WebhookSigner.sign(body, secret, timestampMillis)

        assertEquals(
            "t=1700000000,v1=86748dbec9cc87a9219f8a96632da703271bf5e85aa0afa2b310c37ba059d514",
            header,
        )
    }

    @Test
    fun `verify accepts its own signature and rejects a tampered body or wrong secret`() {
        val body = "hello"
        val secret = "s3cr3t"
        val header = WebhookSigner.sign(body, secret, 1_000_000L)

        assertTrue(WebhookSigner.verify(body, secret, header))
        assertFalse(WebhookSigner.verify("hello!", secret, header))
        assertFalse(WebhookSigner.verify(body, "wrong-secret", header))
        assertFalse(WebhookSigner.verify(body, secret, "garbage"))
    }
}
