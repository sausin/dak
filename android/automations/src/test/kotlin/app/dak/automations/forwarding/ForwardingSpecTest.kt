package app.dak.automations.forwarding

import app.dak.automations.MessageEvent
import app.dak.automations.RuleEngine
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Condition
import app.dak.automations.rule.Rule
import app.dak.automations.rule.RuleCodec
import app.dak.automations.rule.Trigger
import app.dak.automations.rule.activeWindow
import app.dak.automations.rule.conditionsCanMatchOtp
import app.dak.automations.rule.isExpired
import app.dak.automations.rule.isNotStartedYet
import app.dak.automations.safety.RuleValidator
import app.dak.automations.safety.ValidationIssue
import app.dak.core.model.Category
import app.dak.core.model.OtpInfo
import app.dak.premium.FreeEntitlements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForwardingSpecTest {

    private val start = 1_000_000L
    private val end = 2_000_000L

    private val hdfc = ForwardingSource("m:HDFCBK", "HDFC Bank", mergeKey = "HDFCBK", addresses = listOf("VM-HDFCBK", "JD-HDFCBK"))
    private val itd = ForwardingSource("m:ITDEPT", "Income Tax Dept", mergeKey = "ITDEPT")
    private val ca = ForwardingRecipient("+919876543210", "Sharma CA")

    private fun spec(
        includeOtp: Boolean = false,
        categories: Set<Category> = emptySet(),
        keyword: String = "",
        endMillis: Long? = end,
    ) = ForwardingSpec(
        name = "Tax docs for CA",
        sources = listOf(hdfc, itd),
        recipients = listOf(ca),
        subId = 2,
        startMillis = start,
        endMillis = endMillis,
        categories = categories,
        keyword = keyword,
        includeOtp = includeOtp,
    )

    private fun event(
        address: String = "VM-HDFCBK",
        mergeKey: String? = "HDFCBK",
        conversationId: String? = "m:HDFCBK",
        body: String = "Rs 5,000 debited from a/c XX1234",
        category: Category = Category.TRANSACTION,
        otp: OtpInfo? = null,
        dateMillis: Long = 1_500_000L,
    ) = MessageEvent(
        messageKey = "sms:1", address = address, mergeKey = mergeKey, body = body, dateMillis = dateMillis,
        subId = 1, category = category, otp = otp, conversationId = conversationId,
    )

    private fun rule(s: ForwardingSpec = spec()): Rule = s.toRule(10L) { "id-1" }!!

    @Test
    fun `builds a forwarding rule that the engine runs within its window`() {
        val r = rule()
        assertEquals("id-1", r.id)
        assertEquals("Tax docs for CA", r.name)
        assertEquals(listOf(ActionSpec.ForwardSms("+919876543210", 2, ForwardingSpec.DEFAULT_TEMPLATE)), r.actions)
        assertTrue(ForwardingSpec.isForwarding(r))
        val planned = RuleEngine.evaluate(event(), listOf(r))
        assertEquals(1, planned.size)
        assertFalse(planned.single().requiresBiometricConfirmation)
    }

    @Test
    fun `window is inclusive at both ends and open ended until stopped`() {
        val r = rule()
        assertEquals(1, RuleEngine.evaluate(event(dateMillis = start), listOf(r)).size)
        assertEquals(1, RuleEngine.evaluate(event(dateMillis = end), listOf(r)).size)
        assertEquals(0, RuleEngine.evaluate(event(dateMillis = start - 1), listOf(r)).size)
        assertEquals(0, RuleEngine.evaluate(event(dateMillis = end + 1), listOf(r)).size)
        val forever = rule(spec(endMillis = null))
        assertEquals(1, RuleEngine.evaluate(event(dateMillis = Long.MAX_VALUE / 2), listOf(forever)).size)
        assertFalse(forever.isExpired(Long.MAX_VALUE / 2))
    }

    @Test
    fun `expiry helpers read the top level window`() {
        val r = rule()
        assertEquals(Condition.ActiveBetween(start, end), r.activeWindow())
        assertTrue(r.isNotStartedYet(start - 1))
        assertFalse(r.isExpired(end))
        assertTrue(r.isExpired(end + 1))
    }

    @Test
    fun `only chosen channels are forwarded`() {
        val r = rule()
        assertEquals(0, RuleEngine.evaluate(event(address = "AX-ZERODH", mergeKey = "ZERODHA", conversationId = "m:ZERODHA"), listOf(r)).size)
        // A raw address of the group matches even if the merge key changed.
        assertEquals(1, RuleEngine.evaluate(event(address = "JD-HDFCBK", mergeKey = null, conversationId = null), listOf(r)).size)
        assertEquals(1, RuleEngine.evaluate(event(address = "AD-ITDEPT", mergeKey = "ITDEPT", conversationId = "m:ITDEPT"), listOf(r)).size)
    }

    @Test
    fun `OTPs are excluded by default and need confirmation when included`() {
        val r = rule()
        assertFalse(conditionsCanMatchOtp(r.conditions))
        assertEquals(0, RuleEngine.evaluate(event(category = Category.OTP, otp = OtpInfo("123456")), listOf(r)).size)
        assertEquals(0, RuleEngine.evaluate(event(category = Category.TRANSACTION, otp = OtpInfo("123456")), listOf(r)).size)

        val withOtp = rule(spec(includeOtp = true))
        assertTrue(conditionsCanMatchOtp(withOtp.conditions))
        val planned = RuleEngine.evaluate(event(category = Category.OTP, otp = OtpInfo("123456")), listOf(withOtp))
        assertTrue(planned.single().requiresBiometricConfirmation)
    }

    @Test
    fun `including OTPs with a category filter adds the OTP category`() {
        val r = rule(spec(includeOtp = true, categories = setOf(Category.TRANSACTION)))
        assertTrue(conditionsCanMatchOtp(r.conditions))
        assertEquals(1, RuleEngine.evaluate(event(category = Category.OTP, otp = OtpInfo("1")), listOf(r)).size)
        assertEquals(setOf(Category.TRANSACTION), ForwardingSpec.fromRule(r)!!.categories)
    }

    @Test
    fun `category and keyword filters apply`() {
        val r = rule(spec(categories = setOf(Category.TRANSACTION), keyword = "debited"))
        assertEquals(1, RuleEngine.evaluate(event(), listOf(r)).size)
        assertEquals(0, RuleEngine.evaluate(event(body = "Your statement is ready"), listOf(r)).size)
        assertEquals(0, RuleEngine.evaluate(event(category = Category.PROMOTION), listOf(r)).size)
    }

    @Test
    fun `round trips through the rule and its JSON`() {
        val original = spec(categories = setOf(Category.TRANSACTION), keyword = "debited")
        val r = rule(original)
        val decoded = RuleCodec.decodeOne(RuleCodec.encode(r))
        assertEquals(r, decoded)
        val back = ForwardingSpec.fromRule(decoded)
        assertNotNull(back)
        assertEquals(original.copy(id = "id-1", createdAt = 10L), back)
    }

    @Test
    fun `non forwarding rules are not editable here`() {
        val plain = Rule("x", "x", trigger = Trigger.MessageReceived(), actions = listOf(ActionSpec.Archive), createdAt = 0, updatedAt = 0)
        assertNull(ForwardingSpec.fromRule(plain))
    }

    @Test
    fun `status reflects the window and the enabled flag`() {
        val s = spec()
        assertEquals(ForwardingStatus.SCHEDULED, s.status(start - 1))
        assertEquals(ForwardingStatus.ACTIVE, s.status(start))
        assertEquals(ForwardingStatus.ENDED, s.status(end + 1))
        assertEquals(ForwardingStatus.PAUSED, s.copy(enabled = false).status(start + 1))
        assertEquals(ForwardingStatus.ENDED, s.copy(enabled = false).status(end + 1))
    }

    @Test
    fun `incomplete specs do not build`() {
        assertNull(spec().copy(sources = emptyList()).toRule(0) { "x" })
        assertNull(spec().copy(recipients = listOf(ForwardingRecipient(" "))).toRule(0) { "x" })
        assertNull(spec().copy(endMillis = start - 1).toRule(0) { "x" })
    }

    @Test
    fun `derived name lists sources and recipients`() {
        assertEquals("HDFC Bank, Income Tax Dept → Sharma CA", spec().copy(name = "").toRule(0) { "x" }!!.name)
    }

    @Test
    fun `forwarding to a source is a loop`() {
        val loop = spec().copy(recipients = listOf(ForwardingRecipient("VM-HDFCBK"))).toRule(0) { "x" }!!
        val issues = RuleValidator.validate(loop, FreeEntitlements)
        assertTrue(issues.any { it is ValidationIssue.ForwardingLoop })
        assertTrue(RuleValidator.validate(rule(), FreeEntitlements).isEmpty())
    }

    @Test
    fun `messages from the recipient are never forwarded back`() {
        val person = ForwardingSource("t:7", "Priya", addresses = listOf("+91 98765 43210", "+919812345678"))
        val r = spec().copy(sources = listOf(person), recipients = listOf(ForwardingRecipient("09812345678"))).toRule(0) { "x" }!!
        val fromRecipient = event(address = "+919812345678", mergeKey = null, conversationId = "t:7", category = Category.PERSONAL)
        assertTrue(RuleEngine.evaluate(fromRecipient, listOf(r)).isEmpty())
        val fromOther = fromRecipient.copy(address = "+919876543210")
        assertEquals(1, RuleEngine.evaluate(fromOther, listOf(r)).size)
    }

    @Test
    fun `already forwarded messages are not forwarded again`() {
        val r = rule()
        val bounced = event(body = "Fwd from HDFCBK: Rs 5,000 debited")
        assertTrue(RuleEngine.evaluate(bounced, listOf(r)).isEmpty())
    }
}
