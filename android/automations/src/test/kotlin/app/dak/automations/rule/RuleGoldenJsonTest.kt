package app.dak.automations.rule

import app.dak.automations.MessageEvent
import app.dak.automations.RuleEngine
import app.dak.core.model.Category
import app.dak.core.model.TransactionDirection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Rules are persisted as JSON (the rules store, exports, backups, presets). These golden fixtures pin the wire
 * format of every node type, so a renamed `type` tag, a renamed field or a changed default fails a test instead of
 * silently orphaning users' saved rules. Fixtures live in `src/test/resources/golden/`.
 *
 * If a golden test fails because you changed the format on purpose: keep the old JSON decoding (add a migration),
 * then add a new fixture rather than editing the old one.
 */
class RuleGoldenJsonTest {

    /** One rule exercising every trigger, condition, action, schedule and recurrence type this build knows. */
    private fun everyNodeRules(): List<Rule> = listOf(
        Rule(
            id = "r-all-conditions",
            name = "Every condition",
            enabled = true,
            trigger = Trigger.MessageReceived(predicates = Condition.CategoryIs(Category.TRANSACTION)),
            conditions = Condition.All(
                listOf(
                    Condition.Any(listOf(Condition.SenderIs("VM-HDFCBK"), Condition.SenderMatches("(?i)hdfc"))),
                    Condition.Not(Condition.HasOtp),
                    Condition.SimIs(subId = 3, slot = 1),
                    Condition.BodyContains("debited", ignoreCase = false),
                    Condition.BodyMatches("Rs\\.? ?\\d+"),
                    Condition.AmountAtLeast(10_000, currency = "INR"),
                    Condition.AmountAtMost(5_000_000),
                    Condition.TimeWindow(22 * 60, 6 * 60, zoneId = "Asia/Kolkata"),
                    Condition.DirectionIs(TransactionDirection.DEBIT),
                    Condition.ActiveBetween(1_700_000_000_000, 1_800_000_000_000),
                    Condition.SenderInGroups(mergeKeys = listOf("HDFCBK"), conversationIds = listOf("t:42", "m:HDFCBK"), addresses = listOf("+919876543210")),
                ),
            ),
            actions = listOf(
                ActionSpec.Label("Bank"),
                ActionSpec.Archive,
                ActionSpec.Notify(title = "Big spend", text = "{amount}"),
                ActionSpec.ForwardSms(to = "+919876543210", subId = 2, template = "Fwd from {sender}: {body}"),
                ActionSpec.ScheduleReply(text = "Got it", delayMinutes = 5, subId = null),
                ActionSpec.LaunchIntent(uri = "https://example.com/x"),
                ActionSpec.Delete,
                ActionSpec.Webhook(url = "https://example.com/hook", secretRef = "secret-1"),
                ActionSpec.RelayToWebClient(pairingId = "pair-1"),
                ActionSpec.RelayRule(recipient = "+15550100", channel = RelayChannel.WHATSAPP_ONE_TAP),
            ),
            createdAt = 1_000,
            updatedAt = 2_000,
            meta = mapOf("kind" to "forwarding"),
        ),
        Rule(
            id = "r-keyword",
            name = "Keyword",
            enabled = false,
            trigger = Trigger.Keyword("OTP", ignoreCase = true),
            actions = listOf(ActionSpec.Notify()),
            createdAt = 1,
            updatedAt = 1,
        ),
        Rule(
            id = "r-schedules",
            name = "Schedules",
            trigger = Trigger.Schedule(ScheduleSpec.OneShot(1_750_000_000_000)),
            actions = listOf(ActionSpec.RelayRule(recipient = "https://x", channel = RelayChannel.WEBHOOK, template = "{otp}")),
            createdAt = 1,
            updatedAt = 1,
        ),
        Rule(
            id = "r-daily",
            name = "Daily",
            trigger = Trigger.Schedule(ScheduleSpec.Recurring(Recurrence.Daily(9, 30, "Asia/Kolkata"))),
            actions = listOf(ActionSpec.RelayRule(recipient = "+1", channel = RelayChannel.SMS)),
            createdAt = 1,
            updatedAt = 1,
        ),
        Rule(
            id = "r-weekly",
            name = "Weekly",
            trigger = Trigger.Schedule(ScheduleSpec.Recurring(Recurrence.Weekly(7, 23, 59, "Europe/London"))),
            actions = listOf(ActionSpec.Archive),
            createdAt = 1,
            updatedAt = 1,
        ),
        Rule(
            id = "r-monthly",
            name = "Monthly",
            trigger = Trigger.Schedule(ScheduleSpec.Recurring(Recurrence.Monthly(31, 0, 0, "UTC"))),
            actions = listOf(ActionSpec.Archive),
            createdAt = 1,
            updatedAt = 1,
        ),
    )

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/golden/$name")) { "missing fixture $name" }.readText().trim()

    @Test
    fun `every node type encodes exactly as the golden fixture`() {
        assertEquals(resource("rules-every-node.json"), RuleCodec.encode(everyNodeRules()))
    }

    @Test
    fun `golden fixture decodes to the same model`() {
        assertEquals(everyNodeRules(), RuleCodec.decode(resource("rules-every-node.json")))
    }

    @Test
    fun `type tags are pinned`() {
        fun tags(json: String): Set<String> = Regex("\"type\":\"(\\w+)\"").findAll(json).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                // triggers
                "messageReceived", "keyword", "schedule",
                // schedules and recurrences
                "oneShot", "recurring", "daily", "weekly", "monthly",
                // conditions
                "all", "any", "not", "senderIs", "senderMatches", "categoryIs", "simIs", "bodyContains", "bodyMatches",
                "amountAtLeast", "amountAtMost", "timeWindow", "directionIs", "hasOtp", "activeBetween", "senderInGroups",
                // actions
                "label", "archive", "notify", "forwardSms", "scheduleReply", "launchIntent", "delete", "webhook",
                "relayToWebClient", "relayRule",
            ),
            tags(RuleCodec.encode(everyNodeRules())),
        )
        assertEquals(listOf("SMS", "WHATSAPP_ONE_TAP", "WEBHOOK"), RelayChannel.entries.map { it.name })
    }

    /** JSON as the first release stored it: no meta, no schemaVersion, no timeWindow zone, defaults omitted. */
    @Test
    fun `rules stored by older builds still decode with the same meaning`() {
        val rules = RuleCodec.decode(resource("rules-legacy-v1.json"))
        assertEquals(3, rules.size)
        val (otp, quiet, scheduled) = rules
        assertEquals(CURRENT_RULE_SCHEMA_VERSION, otp.schemaVersion)
        assertEquals(emptyMap(), otp.meta)
        assertEquals(true, otp.enabled)
        assertEquals(
            listOf(ActionSpec.ForwardSms(to = "+919999999999", subId = null, template = "{body}")),
            otp.actions,
        )
        val window = assertIs<Condition.TimeWindow>(quiet.conditions)
        assertEquals(Condition.TimeWindow(1320, 360, zoneId = null), window)
        assertEquals(Condition.BodyContains("sale", ignoreCase = true), (quiet.trigger as Trigger.MessageReceived).predicates)
        assertEquals(ActionSpec.Webhook(url = "https://example.com", template = "{body}", secretRef = "s"), quiet.actions.single())
        assertEquals(Trigger.Schedule(ScheduleSpec.OneShot(5)), scheduled.trigger)
        // A legacy zone-less window keeps its old (UTC) evaluation.
        val at2330Utc = 1_700_004_600_000L // 2023-11-14T23:30:00Z (05:00 the next day in Kolkata)
        assertTrue(RuleEngine.evaluateCondition(window, event(at2330Utc)))
    }

    @Test
    fun `a known node with a value this build cannot read is preserved as Unknown, not fatal`() {
        // A newer build might add a category or a relay channel; the whole rule list must still load.
        val json = """[{"id":"r","name":"n","trigger":{"type":"messageReceived"},
            "conditions":{"type":"all","children":[{"type":"categoryIs","category":"BILLS"},{"type":"hasOtp"}]},
            "actions":[{"type":"relayRule","recipient":"x","channel":"TELEGRAM"},{"type":"archive"},{"type":"label"}],
            "createdAt":1,"updatedAt":2}]"""
        val rule = RuleCodec.decode(json).single()
        val children = assertIs<Condition.All>(rule.conditions).children
        val unknown = assertIs<Condition.Unknown>(children[0])
        assertEquals("categoryIs", unknown.type)
        assertEquals(Condition.HasOtp, children[1])
        assertEquals("relayRule", assertIs<ActionSpec.Unknown>(rule.actions[0]).type)
        assertEquals(ActionSpec.Archive, rule.actions[1])
        assertEquals("label", assertIs<ActionSpec.Unknown>(rule.actions[2]).type, "missing required field")
        // Re-encoding keeps the original node verbatim so the newer build gets it back intact.
        val again = Json.parseToJsonElement(RuleCodec.encode(listOf(rule))).let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject }
        val reChildren = again["conditions"]!!.jsonObject["children"] as kotlinx.serialization.json.JsonArray
        assertEquals(Json.parseToJsonElement("""{"type":"categoryIs","category":"BILLS"}"""), reChildren[0])
    }

    @Test
    fun `unknown nodes of every kind round-trip byte for byte`() {
        val json = """[{"id":"r","name":"n","enabled":true,"trigger":{"type":"schedule","spec":{"type":"recurring","recurrence":{"type":"yearly","month":2,"nested":{"a":[1,2]}}}},""" +
            """"conditions":{"type":"not","child":{"type":"batteryBelow","pct":10}},"actions":[{"type":"speak","voice":"x"}],""" +
            """"createdAt":1,"updatedAt":2,"schemaVersion":1,"meta":{}}]"""
        val rule = RuleCodec.decode(json).single()
        val spec = assertIs<ScheduleSpec.Recurring>(assertIs<Trigger.Schedule>(rule.trigger).spec)
        assertEquals("yearly", assertIs<Recurrence.Unknown>(spec.recurrence).type)
        assertEquals(Json.parseToJsonElement(json), Json.parseToJsonElement(RuleCodec.encode(listOf(rule))))
        // Unknown schedule spec and trigger too.
        val t = """{"type":"cron","expr":"* * * * *"}"""
        val s = """{"type":"schedule","spec":{"type":"sunrise","lat":1.5}}"""
        assertEquals(Json.parseToJsonElement(t), RuleCodec.json.encodeToJsonElement(TriggerSerializer, RuleCodec.json.decodeFromString(TriggerSerializer, t)))
        val decodedS = RuleCodec.json.decodeFromString(TriggerSerializer, s)
        assertEquals("sunrise", assertIs<ScheduleSpec.Unknown>(assertIs<Trigger.Schedule>(decodedS).spec).type)
        assertEquals(Json.parseToJsonElement(s), RuleCodec.json.encodeToJsonElement(TriggerSerializer, decodedS))
    }

    @Test
    fun `a node without a type tag or that is not an object is Unknown`() {
        val c1 = RuleCodec.json.decodeFromString(ConditionSerializer, """{"keyword":"x"}""")
        assertEquals("unknown", assertIs<Condition.Unknown>(c1).type)
        val c2 = RuleCodec.json.decodeFromString(ConditionSerializer, "\"hasOtp\"")
        assertIs<Condition.Unknown>(c2)
        val a = RuleCodec.json.decodeFromString(ActionSpecSerializer, "[]")
        assertIs<ActionSpec.Unknown>(a)
        val r = RuleCodec.json.decodeFromString(RecurrenceSerializer, "42")
        assertIs<Recurrence.Unknown>(r)
        assertEquals("UTC", r.zoneId)
        assertIs<ScheduleSpec.Unknown>(RuleCodec.json.decodeFromString(ScheduleSpecSerializer, "null"))
    }

    @Test
    fun `malformed rule JSON is an error, not a silent empty list`() {
        assertFailsWith<kotlinx.serialization.SerializationException> { RuleCodec.decode("[{") }
        assertFailsWith<kotlinx.serialization.SerializationException> { RuleCodec.decode("""[{"id":"r"}]""") }
        assertFailsWith<IllegalArgumentException> { RuleCodec.decodeOne("{}") }
    }

    @Test
    fun `single rule codec agrees with the list codec`() {
        everyNodeRules().forEach { rule ->
            val one = RuleCodec.encode(rule)
            assertEquals(rule, RuleCodec.decodeOne(one))
            assertEquals("[$one]", RuleCodec.encode(listOf(rule)))
        }
    }

    private fun event(dateMillis: Long) = MessageEvent(
        messageKey = "sms:1", address = "+1", body = "b", dateMillis = dateMillis, subId = 1, category = Category.PERSONAL,
    )
}
