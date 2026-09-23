package app.dak.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** RFC 5724 `sms:` / `smsto:` / `mms:` / `mmsto:` parsing ([SmsUriParser]); inputs are encoded scheme-specific parts. */
class SmsUriParserTest {

    private fun parse(ssp: String) = SmsUriParser.parse(ssp)

    @Test
    fun `body with an encoded ampersand keeps it`() {
        val parts = parse("+911234567890?body=a%26b")
        assertEquals(listOf("+911234567890"), parts.recipients)
        assertEquals("a&b", parts.body)
    }

    @Test
    fun `body is decoded exactly once`() {
        assertEquals("50% off", parse("+1?body=50%25%20off").body)
        // %2525 is an encoded "%25": one decode gives the literal text "%25", not "%".
        assertEquals("%25", parse("+1?body=%2525").body)
        assertEquals("a&b%c", parse("+1?body=a%26b%25c").body)
    }

    @Test
    fun `comma separated recipients`() {
        assertEquals(listOf("123", "456"), parse("123,456").recipients)
        assertEquals(listOf("123", "456"), parse("123;456").recipients)
        assertEquals(listOf("+1 555", "+2"), parse("+1%20555,%2B2").recipients)
        assertEquals(listOf("123"), parse("123,,123, ").recipients)
    }

    @Test
    fun `body without recipients`() {
        val parts = parse("?body=hi")
        assertEquals(emptyList(), parts.recipients)
        assertEquals("hi", parts.body)
    }

    @Test
    fun `no query gives no body`() {
        assertNull(parse("+911234567890").body)
        assertNull(parse("").body)
        assertNull(SmsUriParser.parse(null).body)
        assertNull(parse("+1?").body)
        assertNull(parse("+1?body=").body)
    }

    @Test
    fun `plus is a literal plus and percent-20 is a space`() {
        assertEquals("a+b c", parse("?body=a+b%20c").body)
        assertEquals(listOf("+919876543210"), parse("+919876543210").recipients)
    }

    @Test
    fun `other hfields are ignored and body matches case-insensitively`() {
        assertEquals("x", parse("+1?foo=1&body=x&bar=2").body)
        assertEquals("x", parse("+1?BODY=x").body)
        assertEquals("first", parse("+1?body=first&body=second").body)
        assertNull(parse("+1?foo=1&bodyx=2").body)
        // A field without '=' is not a body.
        assertNull(parse("+1?body").body)
    }

    @Test
    fun `invalid percent sequences are kept literally`() {
        assertEquals("100%", parse("?body=100%").body)
        assertEquals("%zz ok", parse("?body=%zz%20ok").body)
        assertEquals("a%2", parse("?body=a%2").body)
        assertEquals("%%41", parse("?body=%%2541").body)
    }

    @Test
    fun `utf-8 is decoded and broken utf-8 becomes a replacement character`() {
        assertEquals("नमस्ते", parse("?body=%E0%A4%A8%E0%A4%AE%E0%A4%B8%E0%A5%8D%E0%A4%A4%E0%A5%87").body)
        assertEquals("�", parse("?body=%E0%A4").body)
    }

    @Test
    fun `smsto double slash form`() {
        assertEquals(listOf("123"), parse("//123?body=x").recipients)
    }

    @Test
    fun `script-looking bodies are passed through as plain text`() {
        val body = parse("+1?body=%3Cscript%3Ealert(1)%3C%2Fscript%3E%26to%3D999").body
        assertEquals("<script>alert(1)</script>&to=999", body)
        // An injected "&to=" inside the encoded body does not add a recipient.
        assertEquals(listOf("+1"), parse("+1?body=%3Cscript%3Ealert(1)%3C%2Fscript%3E%26to%3D999").recipients)
    }

    @Test
    fun `an unencoded hash is data`() {
        assertEquals("a#b", parse("?body=a#b").body)
    }

    @Test
    fun `very long bodies are capped without splitting a surrogate pair`() {
        val long = "a".repeat(SmsUriParser.MAX_BODY_CHARS + 500)
        assertEquals(SmsUriParser.MAX_BODY_CHARS, parse("?body=$long").body!!.length)
        val emoji = "a".repeat(SmsUriParser.MAX_BODY_CHARS - 1) + "🎂" + "tail"
        val capped = SmsUriParser.capBody(emoji)
        assertEquals(SmsUriParser.MAX_BODY_CHARS - 1, capped.length)
        assertEquals('a', capped.last())
    }

    @Test
    fun `recipients are capped`() {
        val many = (1..200).joinToString(",") { "9$it" }
        assertEquals(SmsUriParser.MAX_RECIPIENTS, parse(many).recipients.size)
    }

    @Test
    fun `extras take precedence over the uri body`() {
        val r = SmsUriParser.resolve("+1?body=from-uri", smsBodyExtra = "from-extra", textExtra = "from-text")
        assertEquals("+1", r.to)
        assertEquals("from-extra", r.body)
        assertEquals("from-text", SmsUriParser.resolve("+1?body=from-uri", textExtra = "from-text").body)
        assertEquals("from-uri", SmsUriParser.resolve("+1?body=from-uri", smsBodyExtra = "").body)
    }

    @Test
    fun `address extra is the recipient fallback`() {
        assertEquals("123,456", SmsUriParser.resolve(null, addressExtra = "123; 456").to)
        assertEquals("+1", SmsUriParser.resolve("+1", addressExtra = "999").to)
        assertNull(SmsUriParser.resolve(null).to)
        assertNull(SmsUriParser.resolve("?body=x", addressExtra = " ").to)
    }

    @Test
    fun `extras are capped too`() {
        val r = SmsUriParser.resolve(null, textExtra = "x".repeat(SmsUriParser.MAX_BODY_CHARS * 2))
        assertEquals(SmsUriParser.MAX_BODY_CHARS, r.body!!.length)
    }

    @Test
    fun `percent decoding leaves text without escapes untouched`() {
        assertEquals("plain", SmsUriParser.percentDecode("plain"))
        assertEquals("a b", SmsUriParser.percentDecode("a%20b"))
        assertEquals("a%2Gb", SmsUriParser.percentDecode("a%2Gb"))
    }
}
