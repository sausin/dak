package app.dak.automations

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Condition
import app.dak.automations.rule.Rule
import app.dak.automations.rule.Trigger
import app.dak.core.model.Category
import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.InstrumentType
import app.dak.core.model.OtpInfo
import app.dak.core.model.TransactionDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleEngineTest {

    private fun event(
        category: Category = Category.OTP,
        subId: Int = 1,
        slot: Int? = 0,
        body: String = "Your OTP is 123456",
        address: String = "VM-HDFCBK",
        mergeKey: String? = "HDFC Bank",
        otp: OtpInfo? = OtpInfo(code = "123456"),
        transaction: ExtractedTransaction? = null,
        dateMillis: Long = 0L,
    ) = MessageEvent(
        messageKey = "sms:1",
        address = address,
        mergeKey = mergeKey,
        body = body,
        dateMillis = dateMillis,
        subId = subId,
        slot = slot,
        category = category,
        otp = otp,
        transaction = transaction,
    )

    private fun rule(
        trigger: Trigger = Trigger.MessageReceived(),
        conditions: Condition = Condition.All(emptyList()),
        actions: List<ActionSpec> = listOf(ActionSpec.Archive),
        enabled: Boolean = true,
    ) = Rule(
        id = "r", name = "r", enabled = enabled, trigger = trigger, conditions = conditions,
        actions = actions, createdAt = 0, updatedAt = 0,
    )

    @Test
    fun `disabled rule never fires`() {
        val r = rule(enabled = false)
        assertTrue(RuleEngine.evaluate(event(), listOf(r)).isEmpty())
    }

    @Test
    fun `schedule trigger never matches a message event`() {
        val r = rule(trigger = Trigger.Schedule(app.dak.automations.rule.ScheduleSpec.OneShot(1000L)))
        assertTrue(RuleEngine.evaluate(event(), listOf(r)).isEmpty())
    }

    @Test
    fun `keyword trigger matches body case-insensitively`() {
        val r = rule(trigger = Trigger.Keyword("otp", ignoreCase = true))
        assertEquals(1, RuleEngine.evaluate(event(body = "Your OTP is 5"), listOf(r)).size)
        val caseSensitive = rule(trigger = Trigger.Keyword("OTP", ignoreCase = false))
        assertTrue(RuleEngine.evaluate(event(body = "your otp is 5"), listOf(caseSensitive)).isEmpty())
    }

    @Test
    fun `messageReceived predicates gate the trigger before conditions`() {
        val r = rule(trigger = Trigger.MessageReceived(predicates = Condition.CategoryIs(Category.SPAM)))
        assertTrue(RuleEngine.evaluate(event(category = Category.OTP), listOf(r)).isEmpty())
        assertEquals(1, RuleEngine.evaluate(event(category = Category.SPAM), listOf(r)).size)
    }

    // --- SIM matrix ---

    @Test
    fun `SimIs matches by subId or by slot`() {
        val bySub = rule(conditions = Condition.SimIs(subId = 2))
        val bySlot = rule(conditions = Condition.SimIs(slot = 1))
        assertTrue(RuleEngine.evaluate(event(subId = 2, slot = 0), listOf(bySub)).isNotEmpty())
        assertTrue(RuleEngine.evaluate(event(subId = 1, slot = 1), listOf(bySlot)).isNotEmpty())
        assertTrue(RuleEngine.evaluate(event(subId = 1, slot = 0), listOf(bySub)).isEmpty())
        assertTrue(RuleEngine.evaluate(event(subId = 1, slot = 0), listOf(bySlot)).isEmpty())
    }

    // --- Category matrix ---

    @Test
    fun `CategoryIs matches exactly one category`() {
        for (category in Category.entries) {
            val r = rule(conditions = Condition.CategoryIs(category))
            for (candidate in Category.entries) {
                val result = RuleEngine.evaluate(event(category = candidate), listOf(r))
                assertEquals(candidate == category, result.isNotEmpty(), "category=$candidate rule=$category")
            }
        }
    }

    // --- Sender / regex ---

    @Test
    fun `SenderIs matches address or merge key case-insensitively`() {
        val byAddress = rule(conditions = Condition.SenderIs("vm-hdfcbk"))
        val byMerge = rule(conditions = Condition.SenderIs("hdfc bank"))
        assertTrue(RuleEngine.evaluate(event(), listOf(byAddress)).isNotEmpty())
        assertTrue(RuleEngine.evaluate(event(), listOf(byMerge)).isNotEmpty())
    }

    @Test
    fun `invalid regex never matches and never throws`() {
        val r = rule(conditions = Condition.BodyMatches("(unclosed"))
        assertTrue(RuleEngine.evaluate(event(), listOf(r)).isEmpty())
    }

    @Test
    fun `regex matching is bounded so a huge body cannot hang`() {
        val hugeBody = "x".repeat(RuleEngine.MAX_REGEX_INPUT_LENGTH * 5) + "NEEDLE"
        val r = rule(conditions = Condition.BodyMatches("NEEDLE"))
        // The needle is past the cap, so it must not be found (proves the input really was truncated).
        assertTrue(RuleEngine.evaluate(event(body = hugeBody), listOf(r)).isEmpty())
    }

    // --- Amount / direction ---

    @Test
    fun `AmountAtLeast and AmountAtMost respect currency and thresholds`() {
        val tx = ExtractedTransaction(
            direction = TransactionDirection.DEBIT,
            amountMinor = 50_00,
            currency = "INR",
            instrument = InstrumentType.BANK_ACCOUNT,
        )
        val e = event(transaction = tx)
        assertTrue(RuleEngine.evaluate(event(transaction = tx), listOf(rule(conditions = Condition.AmountAtLeast(40_00, "INR")))).isNotEmpty())
        assertTrue(RuleEngine.evaluate(e, listOf(rule(conditions = Condition.AmountAtLeast(60_00, "INR")))).isEmpty())
        assertTrue(RuleEngine.evaluate(e, listOf(rule(conditions = Condition.AmountAtMost(60_00, "INR")))).isNotEmpty())
        assertTrue(RuleEngine.evaluate(e, listOf(rule(conditions = Condition.AmountAtLeast(1, "AED")))).isEmpty())
        assertTrue(RuleEngine.evaluate(event(transaction = null), listOf(rule(conditions = Condition.AmountAtLeast(1)))).isEmpty())
    }

    @Test
    fun `DirectionIs requires a transaction`() {
        val tx = ExtractedTransaction(TransactionDirection.CREDIT, 100, "INR")
        assertTrue(RuleEngine.evaluate(event(transaction = tx), listOf(rule(conditions = Condition.DirectionIs(TransactionDirection.CREDIT)))).isNotEmpty())
        assertTrue(RuleEngine.evaluate(event(transaction = null), listOf(rule(conditions = Condition.DirectionIs(TransactionDirection.CREDIT)))).isEmpty())
    }

    // --- TimeWindow ---

    @Test
    fun `TimeWindow handles a normal and a midnight-wrapping window`() {
        val nineAm = 9 * 60 * 60 * 1000L
        val normal = Condition.TimeWindow(fromMinuteOfDay = 8 * 60, toMinuteOfDay = 10 * 60)
        assertTrue(RuleEngine.evaluate(event(dateMillis = nineAm), listOf(rule(conditions = normal))).isNotEmpty())

        val elevenPm = 23 * 60 * 60 * 1000L
        val wrapping = Condition.TimeWindow(fromMinuteOfDay = 22 * 60, toMinuteOfDay = 6 * 60)
        assertTrue(RuleEngine.evaluate(event(dateMillis = elevenPm), listOf(rule(conditions = wrapping))).isNotEmpty())
        assertTrue(RuleEngine.evaluate(event(dateMillis = nineAm), listOf(rule(conditions = wrapping))).isEmpty())
    }

    // --- HasOtp / All / Any / Not ---

    @Test
    fun `HasOtp, All, Any and Not compose`() {
        val e = event(otp = OtpInfo("1"), category = Category.OTP)
        val condition = Condition.All(
            listOf(
                Condition.HasOtp,
                Condition.Any(listOf(Condition.CategoryIs(Category.SPAM), Condition.CategoryIs(Category.OTP))),
                Condition.Not(Condition.CategoryIs(Category.PROMOTION)),
            ),
        )
        assertTrue(RuleEngine.evaluate(e, listOf(rule(conditions = condition))).isNotEmpty())
    }

    // --- Order and action fan-out ---

    @Test
    fun `each action of a matching rule becomes its own PlannedAction, in order`() {
        val r = rule(actions = listOf(ActionSpec.Label("a"), ActionSpec.Archive, ActionSpec.Notify()))
        val planned = RuleEngine.evaluate(event(), listOf(r))
        assertEquals(3, planned.size)
        assertEquals(listOf(ActionSpec.Label("a"), ActionSpec.Archive, ActionSpec.Notify()), planned.map { it.action })
    }

    // --- OTP forwarding safety flag ---

    @Test
    fun `forwarding action requires biometric confirmation when conditions can match an OTP`() {
        val unrestricted = rule(actions = listOf(ActionSpec.ForwardSms(to = "+1", template = "{body}")))
        assertTrue(RuleEngine.evaluate(event(), listOf(unrestricted)).all { it.requiresBiometricConfirmation })

        val explicitOtp = rule(
            conditions = Condition.CategoryIs(Category.OTP),
            actions = listOf(ActionSpec.ForwardSms(to = "+1")),
        )
        assertTrue(RuleEngine.evaluate(event(), listOf(explicitOtp)).all { it.requiresBiometricConfirmation })

        val hasOtpGuarded = rule(conditions = Condition.HasOtp, actions = listOf(ActionSpec.ForwardSms(to = "+1")))
        assertTrue(RuleEngine.evaluate(event(), listOf(hasOtpGuarded)).all { it.requiresBiometricConfirmation })

        val nonOtpOnly = rule(
            conditions = Condition.CategoryIs(Category.TRANSACTION),
            actions = listOf(ActionSpec.ForwardSms(to = "+1")),
        )
        val planned = RuleEngine.evaluate(event(category = Category.TRANSACTION), listOf(nonOtpOnly))
        assertFalse(planned.all { it.requiresBiometricConfirmation })
        assertTrue(planned.none { it.requiresBiometricConfirmation })
    }

    @Test
    fun `non-forwarding action never requires biometric confirmation even when conditions can match an OTP`() {
        val r = rule(actions = listOf(ActionSpec.Archive))
        assertTrue(RuleEngine.evaluate(event(), listOf(r)).none { it.requiresBiometricConfirmation })
    }
}
