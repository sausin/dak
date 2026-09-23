package app.dak.navigation

import app.dak.telephony.sms.RespondViaMessage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The composer ([SmsUriParser], SENDTO / VIEW intents) and the headless reply path ([RespondViaMessage],
 * RESPOND_VIA_MESSAGE from the dialer) parse the same RFC 5724 grammar. They must agree on recipients and body, or
 * the same link would text different people (or a different text) depending on how it was opened.
 */
class SmsUriParserConsistencyTest {

    private val corpus = listOf(
        "+15551234", "+15551234,+15559876", " +15551234 ; 5559876;;+15551234?body=hi", "//+15551234?body=x",
        "%2B15551234,5559876", "a%2Cb", "a%3Bb,c", "12%3F34?body=x", "+1?body=a%26b%25c", "+1?body=50%2525%20off",
        "+1?foo=1&body=x", "+1?BODY=x&foo=2", "+1?body=one&body=two", "+1?body=what?", "+1?body=a=b",
        "+1?%62ody=x", "+1?body=1+1%3D2", "+1?body=100%", "+1?body=%zz", "+1?body=%4", "+1?body=a%FFb",
        "+1?body=%E0%A4%A8%E0%A4%AE", "+1?body=नमस्ते", "?body=only", "", "+1#frag?body=x", "+1?body=x#y",
        "+1?body=%0Aline", "+1?body=%E2%80%AEevil", "+1?bodyx=y", "+1?&&body=z",
    )

    @Test
    fun bothParsersAgreeOnRecipients() {
        for (ssp in corpus) {
            assertEquals(RespondViaMessage.recipients(ssp), SmsUriParser.parse(ssp).recipients, "recipients of <$ssp>")
        }
    }

    @Test
    fun bothParsersAgreeOnTheBody() {
        for (ssp in corpus) {
            // SmsUriParser reports an empty body as none (the composer starts empty either way).
            val headless = RespondViaMessage.body(ssp)?.takeIf { it.isNotEmpty() }
            assertEquals(headless, SmsUriParser.parse(ssp).body, "body of <$ssp>")
        }
    }

    @Test
    fun percentDecodingIsIdenticalOnRandomInput() {
        val random = Random(5724)
        // Whole code points only: the two differ on a lone surrogate (not something a Uri can carry).
        val alphabet = "%0123456789abcdefABCDEFxz+&=?,;# ".map { it.toString() } + listOf("é", "😀", "%F0%9F", "%E2%80%AE", "%C3")
        repeat(5_000) {
            val s = buildString { repeat(random.nextInt(0, 24)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            assertEquals(RespondViaMessage.percentDecode(s), SmsUriParser.percentDecode(s), "<$s>")
        }
    }
}
