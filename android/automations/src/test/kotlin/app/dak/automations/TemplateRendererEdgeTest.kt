package app.dak.automations

import app.dak.core.model.Category
import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.OtpInfo
import app.dak.core.model.TransactionDirection
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateRendererEdgeTest {

    private fun event(
        body: String = "hi",
        address: String = "+15550100",
        mergeKey: String? = null,
        otp: OtpInfo? = null,
        tx: ExtractedTransaction? = null,
        dateMillis: Long = 0,
        slot: Int? = null,
        subId: Int = 3,
    ) = MessageEvent("sms:1", address, mergeKey, body, dateMillis, subId, slot, Category.PERSONAL, otp, tx)

    @Test
    fun `values are never re-expanded, so a sender cannot smuggle in the OTP or other fields`() {
        // An attacker texts a body containing placeholders; the forward must carry them literally.
        val e = event(body = "win! {otp} {amount} {sender}", otp = OtpInfo("123456"), address = "{body}")
        assertEquals("win! {otp} {amount} {sender} / {body}", TemplateRenderer.render("{body} / {sender}", e))
        // Doubled braces are not an escape and never trigger a second pass: the key "{otp" is unknown, so verbatim.
        assertEquals("{{otp}}", TemplateRenderer.render("{{otp}}", e))
        // An OTP whose own text looks like a placeholder stays literal too.
        assertEquals("{body}", TemplateRenderer.render("{otp}", event(body = "secret", otp = OtpInfo("{body}"))))
    }

    @Test
    fun `malformed templates pass through without crashing`() {
        val e = event(otp = OtpInfo("42"))
        assertEquals("", TemplateRenderer.render("", e))
        assertEquals("{", TemplateRenderer.render("{", e))
        assertEquals("}", TemplateRenderer.render("}", e))
        assertEquals("a {otp", TemplateRenderer.render("a {otp", e))
        assertEquals("{}", TemplateRenderer.render("{}", e))
        assertEquals("x 42 {", TemplateRenderer.render("x {otp} {", e))
        assertEquals("{OTP}", TemplateRenderer.render("{OTP}", e), "placeholders are case-sensitive")
        assertEquals("{ otp }", TemplateRenderer.render("{ otp }", e))
        // "{a{otp}" — the first '{' opens a key "a{otp" that is unknown, so it passes through verbatim.
        assertEquals("{a{otp}", TemplateRenderer.render("{a{otp}", e))
    }

    @Test
    fun `otp renders empty when the message has none`() {
        assertEquals("code: ", TemplateRenderer.render("code: {otp}", event()))
    }

    @Test
    fun `amount formatting is locale independent and exact for large and negative values`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // would print 1.234,56 if the renderer used the default locale
            val tx = ExtractedTransaction(TransactionDirection.CREDIT, 123_456, "EUR")
            assertEquals("EUR 1234.56", TemplateRenderer.render("{amount}", event(tx = tx)))
            assertEquals("INR 0.05", TemplateRenderer.render("{amount}", event(tx = tx.copy(amountMinor = 5, currency = "INR"))))
            assertEquals("INR 0.00", TemplateRenderer.render("{amount}", event(tx = tx.copy(amountMinor = 0, currency = "INR"))))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `time uses the given zone, follows DST, and falls back to UTC for a bad zone`() {
        fun at(zone: String, y: Int, mo: Int, d: Int, h: Int, mi: Int) =
            LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
        val winter = at("Europe/London", 2026, 1, 15, 9, 5)
        val summer = at("Europe/London", 2026, 7, 15, 9, 5)
        assertEquals("15 Jan, 09:05", TemplateRenderer.render("{time}", event(dateMillis = winter), "Europe/London"))
        assertEquals("15 Jul, 09:05", TemplateRenderer.render("{time}", event(dateMillis = summer), "Europe/London"))
        assertEquals("15 Jul, 08:05", TemplateRenderer.render("{time}", event(dateMillis = summer), "UTC"))
        assertEquals("15 Jul, 13:35", TemplateRenderer.render("{time}", event(dateMillis = summer), "Asia/Kolkata"))
        assertEquals("15 Jul, 08:05", TemplateRenderer.render("{time}", event(dateMillis = summer), "Not/AZone"))
        assertEquals("15 Jul, 08:05", TemplateRenderer.render("{time}", event(dateMillis = summer), ""))
    }

    @Test
    fun `sender prefers the merge key and sim prefers the slot`() {
        assertEquals("HDFC Bank|SIM 2", TemplateRenderer.render("{sender}|{sim}", event(mergeKey = "HDFC Bank", slot = 1)))
        assertEquals("+15550100|sub 3", TemplateRenderer.render("{sender}|{sim}", event()))
    }

    @Test
    fun `payer and merchant both come from the extracted counterparty`() {
        val tx = ExtractedTransaction(TransactionDirection.DEBIT, 100, "INR", merchant = "Swiggy")
        assertEquals("Swiggy/Swiggy", TemplateRenderer.render("{payer}/{merchant}", event(tx = tx)))
        assertEquals("/", TemplateRenderer.render("{payer}/{merchant}", event()))
    }

    @Test
    fun `a huge body renders in linear time`() {
        val body = "{".repeat(50_000)
        val start = System.nanoTime()
        val out = TemplateRenderer.render("{body}" + "{x".repeat(10_000), event(body = body))
        assertEquals(body.length + 20_000, out.length)
        assertEquals(true, System.nanoTime() - start < 2_000_000_000L)
    }
}
