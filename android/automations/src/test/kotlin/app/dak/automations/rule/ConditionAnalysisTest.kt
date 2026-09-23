package app.dak.automations.rule

import app.dak.core.model.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionAnalysisTest {

    @Test
    fun `exclusion clause makes a rule OTP safe`() {
        assertFalse(conditionsCanMatchOtp(Condition.All(otpExclusion())))
        assertFalse(conditionsCanMatchOtp(Condition.All(listOf(Condition.All(otpExclusion()), Condition.BodyContains("x")))))
    }

    @Test
    fun `negations never count as positive restrictions`() {
        assertTrue(conditionsCanMatchOtp(Condition.Not(Condition.CategoryIs(Category.PROMOTION))))
        assertTrue(conditionsCanMatchOtp(Condition.Not(Condition.HasOtp)))
        assertTrue(conditionsCanMatchOtp(Condition.Not(Condition.CategoryIs(Category.OTP))))
        assertFalse(
            conditionsCanMatchOtp(Condition.All(listOf(Condition.Not(Condition.HasOtp), Condition.CategoryIs(Category.TRANSACTION)))),
        )
    }

    @Test
    fun `positive OTP conditions still count`() {
        assertTrue(conditionsCanMatchOtp(Condition.HasOtp))
        assertTrue(conditionsCanMatchOtp(Condition.Any(listOf(Condition.CategoryIs(Category.OTP), Condition.CategoryIs(Category.TRANSACTION)))))
        assertFalse(conditionsCanMatchOtp(Condition.CategoryIs(Category.TRANSACTION)))
    }

    @Test
    fun `new condition types round trip through JSON and old rules still decode`() {
        val r = Rule(
            id = "r", name = "r", trigger = Trigger.MessageReceived(),
            conditions = Condition.All(
                listOf(Condition.ActiveBetween(1, null), Condition.SenderInGroups(listOf("HDFCBK"), listOf("m:HDFCBK"), listOf("VM-HDFCBK"))),
            ),
            actions = listOf(ActionSpec.Archive), createdAt = 0, updatedAt = 0, meta = mapOf("kind" to "forwarding"),
        )
        assertEquals(r, RuleCodec.decodeOne(RuleCodec.encode(r)))
        val old = """{"id":"a","name":"a","trigger":{"type":"messageReceived"},"actions":[{"type":"archive"}],"createdAt":1,"updatedAt":2,"schemaVersion":1}"""
        val decoded = RuleCodec.decodeOne(old)
        assertEquals(emptyMap(), decoded.meta)
        assertEquals(CURRENT_RULE_SCHEMA_VERSION, decoded.schemaVersion)
    }
}
