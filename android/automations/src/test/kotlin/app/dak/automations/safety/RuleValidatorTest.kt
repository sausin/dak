package app.dak.automations.safety

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Condition
import app.dak.automations.rule.RelayChannel
import app.dak.automations.rule.Rule
import app.dak.automations.rule.Trigger
import app.dak.premium.Feature
import app.dak.premium.FreeEntitlements
import app.dak.premium.StaticEntitlements
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuleValidatorTest {

    private fun rule(conditions: Condition = Condition.All(emptyList()), actions: List<ActionSpec>) = Rule(
        id = "r", name = "r", trigger = Trigger.MessageReceived(), conditions = conditions,
        actions = actions, createdAt = 0, updatedAt = 0,
    )

    @Test
    fun `flags an invalid regex in conditions and in trigger predicates`() {
        val r = rule(
            conditions = Condition.BodyMatches("(unclosed"),
            actions = listOf(ActionSpec.Archive),
        )
        val issues = RuleValidator.validate(r, FreeEntitlements)
        assertTrue(issues.any { it is ValidationIssue.InvalidRegex && it.location == "conditions" })

        val r2 = Rule(
            id = "r2", name = "r2",
            trigger = Trigger.MessageReceived(predicates = Condition.SenderMatches("[[[")),
            actions = listOf(ActionSpec.Archive), createdAt = 0, updatedAt = 0,
        )
        val issues2 = RuleValidator.validate(r2, FreeEntitlements)
        assertTrue(issues2.any { it is ValidationIssue.InvalidRegex && it.location == "trigger" })
    }

    @Test
    fun `flags a missing recipient`() {
        val r = rule(actions = listOf(ActionSpec.ForwardSms(to = "   ")))
        val issues = RuleValidator.validate(r, StaticEntitlements(setOf(Feature.RELAY_RULES)))
        assertTrue(issues.any { it is ValidationIssue.MissingRecipient })
    }

    @Test
    fun `flags a premium action on free entitlements, and not on premium`() {
        val r = rule(actions = listOf(ActionSpec.Webhook(url = "https://x", secretRef = "s")))
        val freeIssues = RuleValidator.validate(r, FreeEntitlements)
        assertIs<ValidationIssue.PremiumActionInFreeTier>(freeIssues.first { it is ValidationIssue.PremiumActionInFreeTier })

        val premiumIssues = RuleValidator.validate(r, StaticEntitlements(setOf(Feature.WEBHOOKS)))
        assertTrue(premiumIssues.none { it is ValidationIssue.PremiumActionInFreeTier })
    }

    @Test
    fun `flags forwarding to the user's own number as a loop`() {
        val r = rule(actions = listOf(ActionSpec.ForwardSms(to = "+91 98765 43210")))
        val issues = RuleValidator.validate(r, FreeEntitlements, ownAddresses = setOf("9876543210"))
        assertTrue(issues.any { it is ValidationIssue.ForwardingLoop })
    }

    @Test
    fun `relayRule over webhook channel has no phone-number recipient to validate`() {
        val r = rule(
            actions = listOf(ActionSpec.RelayRule(recipient = "https://hooks.example.com/x", channel = RelayChannel.WEBHOOK)),
        )
        val issues = RuleValidator.validate(r, StaticEntitlements(setOf(Feature.RELAY_RULES)))
        assertTrue(issues.none { it is ValidationIssue.MissingRecipient || it is ValidationIssue.ForwardingLoop })
    }

    @Test
    fun `no actions is flagged`() {
        val issues = RuleValidator.validate(rule(actions = emptyList()), FreeEntitlements)
        assertTrue(issues.contains(ValidationIssue.NoActions))
    }

    @Test
    fun `a clean free rule has no issues`() {
        val r = rule(actions = listOf(ActionSpec.Label("x"), ActionSpec.Archive))
        assertTrue(RuleValidator.validate(r, FreeEntitlements).isEmpty())
    }
}
