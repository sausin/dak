package app.dak.automations

import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.OtpInfo
import app.dak.core.model.TransactionDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateRendererTest {

    private val baseEvent = MessageEvent(
        messageKey = "sms:1",
        address = "VM-HDFCBK",
        mergeKey = "HDFC Bank",
        body = "Rs.500 debited",
        dateMillis = 0L,
        subId = 5,
        slot = 1,
        category = app.dak.core.model.Category.TRANSACTION,
        otp = OtpInfo(code = "998877"),
        transaction = ExtractedTransaction(
            direction = TransactionDirection.DEBIT,
            amountMinor = 50_000,
            currency = "INR",
            merchant = "Swiggy",
        ),
    )

    @Test
    fun `default template is the raw message`() {
        assertEquals(baseEvent.body, TemplateRenderer.render("{body}", baseEvent))
    }

    @Test
    fun `renders every documented placeholder`() {
        val rendered = TemplateRenderer.render(
            "amount={amount} payer={payer} merchant={merchant} sender={sender} otp={otp} sim={sim} body={body}",
            baseEvent,
        )
        assertEquals(
            "amount=INR 500.00 payer=Swiggy merchant=Swiggy sender=HDFC Bank otp=998877 sim=SIM 2 body=Rs.500 debited",
            rendered,
        )
    }

    @Test
    fun `falls back to address when there is no merge key`() {
        val e = baseEvent.copy(mergeKey = null)
        assertEquals("VM-HDFCBK", TemplateRenderer.render("{sender}", e))
    }

    @Test
    fun `missing values render as empty string, unknown placeholders pass through`() {
        val e = baseEvent.copy(transaction = null, otp = null)
        assertEquals("amount=[] otp=[]", TemplateRenderer.render("amount=[{amount}] otp=[{otp}]", e))
        assertEquals("literal {notAPlaceholder} text", TemplateRenderer.render("literal {notAPlaceholder} text", e))
    }

    @Test
    fun `sim falls back to sub id when slot is unknown`() {
        val e = baseEvent.copy(slot = null, subId = 3)
        assertEquals("sub 3", TemplateRenderer.render("{sim}", e))
    }
}
