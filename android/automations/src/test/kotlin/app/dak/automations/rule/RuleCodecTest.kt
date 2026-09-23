package app.dak.automations.rule

import app.dak.core.model.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuleCodecTest {

    private fun sampleRule(): Rule = Rule(
        id = "r1",
        name = "Test rule",
        trigger = Trigger.MessageReceived(predicates = Condition.CategoryIs(Category.OTP)),
        conditions = Condition.All(
            listOf(
                Condition.SenderMatches("(?i)hdfc"),
                Condition.Any(listOf(Condition.HasOtp, Condition.BodyContains("otp"))),
                Condition.Not(Condition.DirectionIs(app.dak.core.model.TransactionDirection.DEBIT)),
            ),
        ),
        actions = listOf(
            ActionSpec.Label("Bank"),
            ActionSpec.ForwardSms(to = "+911234567890", subId = 1, template = "OTP {otp} from {sender}"),
            ActionSpec.Webhook(url = "https://example.com/hook", secretRef = "secret-ref-1"),
        ),
        createdAt = 1000L,
        updatedAt = 2000L,
    )

    @Test
    fun `round-trips a full rule through JSON`() {
        val rule = sampleRule()
        val json = RuleCodec.encode(listOf(rule))
        val decoded = RuleCodec.decode(json)
        assertEquals(listOf(rule), decoded)
    }

    @Test
    fun `unknown fields on a known node are ignored, not fatal`() {
        val json = """
            [{
              "id": "r1", "name": "n", "enabled": true,
              "trigger": {"type": "messageReceived", "extraField": 42},
              "conditions": {"type": "hasOtp", "somethingNew": "x"},
              "actions": [{"type": "archive", "unexpected": true}],
              "createdAt": 1, "updatedAt": 2, "schemaVersion": 1,
              "futureTopLevelField": "ignored"
            }]
        """.trimIndent()
        val decoded = RuleCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals(Condition.HasOtp, decoded[0].conditions)
        assertEquals(listOf(ActionSpec.Archive), decoded[0].actions)
    }

    @Test
    fun `unknown trigger, condition and action types are preserved, not fatal`() {
        val json = """
            [{
              "id": "r2", "name": "future rule", "enabled": true,
              "trigger": {"type": "geofenceEntered", "lat": 1.0, "lng": 2.0},
              "conditions": {
                "type": "any",
                "children": [
                  {"type": "hasOtp"},
                  {"type": "weatherIsRaining", "mm": 5}
                ]
              },
              "actions": [
                {"type": "postToMastodon", "handle": "@me"},
                {"type": "archive"}
              ],
              "createdAt": 1, "updatedAt": 2
            }]
        """.trimIndent()
        val decoded = RuleCodec.decode(json)
        val rule = decoded.single()

        val trigger = assertIs<Trigger.Unknown>(rule.trigger)
        assertEquals("geofenceEntered", trigger.type)

        val conditions = assertIs<Condition.Any>(rule.conditions)
        assertEquals(Condition.HasOtp, conditions.children[0])
        val unknownCondition = assertIs<Condition.Unknown>(conditions.children[1])
        assertEquals("weatherIsRaining", unknownCondition.type)

        val unknownAction = assertIs<ActionSpec.Unknown>(rule.actions[0])
        assertEquals("postToMastodon", unknownAction.type)
        assertEquals(ActionSpec.Archive, rule.actions[1])

        // And it re-encodes without losing the unknown nodes (or crashing).
        val reencoded = RuleCodec.encode(listOf(rule))
        assertTrue(reencoded.contains("geofenceEntered"))
        assertTrue(reencoded.contains("weatherIsRaining"))
        assertTrue(reencoded.contains("postToMastodon"))
    }

    @Test
    fun `default conditions is an always-true All`() {
        val json = """
            [{
              "id": "r3", "name": "n",
              "trigger": {"type": "keyword", "keyword": "OTP"},
              "actions": [{"type": "archive"}],
              "createdAt": 1, "updatedAt": 2
            }]
        """.trimIndent()
        val decoded = RuleCodec.decode(json).single()
        assertEquals(Condition.All(emptyList()), decoded.conditions)
    }
}
