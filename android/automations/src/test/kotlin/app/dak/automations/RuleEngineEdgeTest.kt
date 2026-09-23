package app.dak.automations

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Condition
import app.dak.automations.rule.RelayChannel
import app.dak.automations.rule.Rule
import app.dak.automations.rule.RuleCodec
import app.dak.automations.rule.Trigger
import app.dak.automations.rule.activeWindow
import app.dak.automations.rule.isExpired
import app.dak.automations.rule.isNotStartedYet
import app.dak.automations.rule.withRestartedWindow
import app.dak.core.model.Category
import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.OtpInfo
import app.dak.core.model.TransactionDirection
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Boundary and fail-safe behaviour of the rule engine, beyond the happy paths in [RuleEngineTest]. */
class RuleEngineEdgeTest {

    private fun event(
        body: String = "hello",
        address: String = "+919876543210",
        mergeKey: String? = null,
        dateMillis: Long = 0L,
        subId: Int = 1,
        slot: Int? = null,
        category: Category = Category.PERSONAL,
        otp: OtpInfo? = null,
        transaction: ExtractedTransaction? = null,
        conversationId: String? = null,
    ) = MessageEvent("sms:1", address, mergeKey, body, dateMillis, subId, slot, category, otp, transaction, conversationId)

    private fun rule(
        conditions: Condition = Condition.All(emptyList()),
        trigger: Trigger = Trigger.MessageReceived(),
        actions: List<ActionSpec> = listOf(ActionSpec.Archive),
        id: String = "r",
        enabled: Boolean = true,
    ) = Rule(id = id, name = id, enabled = enabled, trigger = trigger, conditions = conditions, actions = actions, createdAt = 0, updatedAt = 0)

    private fun matches(c: Condition, e: MessageEvent) = RuleEngine.evaluateCondition(c, e)

    private fun at(zone: String, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    // --- boolean composition ---

    @Test
    fun `empty All is true and empty Any is false`() {
        assertTrue(matches(Condition.All(emptyList()), event()))
        assertFalse(matches(Condition.Any(emptyList()), event()))
        assertFalse(matches(Condition.Not(Condition.All(emptyList())), event()))
        assertTrue(matches(Condition.Not(Condition.Not(Condition.All(emptyList()))), event()))
    }

    @Test
    fun `deeply nested trees evaluate`() {
        var c: Condition = Condition.BodyContains("hello")
        repeat(200) { c = Condition.Not(Condition.Not(Condition.All(listOf(Condition.Any(listOf(c)))))) }
        assertTrue(matches(c, event()))
        assertFalse(matches(c, event(body = "bye")))
    }

    // --- fail closed on things this build does not understand ---

    @Test
    fun `an Unknown condition never matches on its own`() {
        val unknown = Condition.Unknown("batteryBelow", JsonObject(emptyMap()))
        assertFalse(matches(unknown, event()))
        assertTrue(RuleEngine.evaluate(event(), listOf(rule(unknown))).isEmpty())
    }

    @Test
    fun `a rule with an Unknown condition under Not does not fire (fail closed)`() {
        // Made by a newer build: "forward everything except <new condition>". Read naively the Not(Unknown=false)
        // is true, so an older build would forward every message, OTPs included.
        val json = """[{"id":"r","name":"n","trigger":{"type":"messageReceived"},
            "conditions":{"type":"not","child":{"type":"senderIsSpamListed"}},
            "actions":[{"type":"forwardSms","to":"+15550100"}],"createdAt":1,"updatedAt":1}]"""
        val rules = RuleCodec.decode(json)
        assertTrue(RuleEngine.evaluate(event(otp = OtpInfo("123456"), category = Category.OTP), rules).isEmpty())
        // Same for an Unknown hiding in the trigger's predicates or under Any.
        val unknown = Condition.Unknown("x", JsonObject(emptyMap()))
        assertTrue(RuleEngine.evaluate(event(), listOf(rule(trigger = Trigger.MessageReceived(Condition.Not(unknown))))).isEmpty())
        assertTrue(RuleEngine.evaluate(event(), listOf(rule(Condition.Any(listOf(Condition.All(emptyList()), unknown))))).isEmpty())
    }

    @Test
    fun `an Unknown trigger never fires and other rules still do`() {
        val rules = listOf(
            rule(trigger = Trigger.Unknown("geofence", JsonObject(emptyMap())), id = "a"),
            rule(id = "b"),
        )
        assertEquals(listOf("b"), RuleEngine.evaluate(event(), rules).map { it.ruleId })
    }

    // --- sender ---

    @Test
    fun `SenderIs is exact (case-insensitive), not a substring or phone-normalised match`() {
        assertTrue(matches(Condition.SenderIs("VM-HDFCBK"), event(address = "vm-hdfcbk")))
        assertFalse(matches(Condition.SenderIs("HDFCBK"), event(address = "VM-HDFCBK")))
        assertFalse(matches(Condition.SenderIs("9876543210"), event(address = "+919876543210")))
        assertTrue(matches(Condition.SenderIs("hdfc bank"), event(address = "VM-HDFCBK", mergeKey = "HDFC Bank")))
    }

    @Test
    fun `SenderMatches uses find semantics and refuses catastrophic patterns`() {
        assertTrue(matches(Condition.SenderMatches("HDFC"), event(address = "VM-HDFCBK")))
        assertFalse(matches(Condition.SenderMatches("^HDFC"), event(address = "VM-HDFCBK")))
        // (a+)+ backtracks exponentially on a non-matching tail; the engine must not even try it.
        val start = System.nanoTime()
        assertFalse(matches(Condition.SenderMatches("(a+)+$"), event(address = "a".repeat(40) + "!")))
        assertTrue(System.nanoTime() - start < 2_000_000_000L)
    }

    @Test
    fun `a regex match right at the input cap is still found`() {
        val cap = RuleEngine.MAX_REGEX_INPUT_LENGTH
        val body = "x".repeat(cap - 6) + "NEEDLE" + "tail"
        assertTrue(matches(Condition.BodyMatches("NEEDLE"), event(body = body)))
        assertFalse(matches(Condition.BodyMatches("NEEDLEt"), event(body = body)))
    }

    @Test
    fun `SenderInGroups matches any of merge key, conversation, or phone number on last ten digits`() {
        val c = Condition.SenderInGroups(mergeKeys = listOf("hdfcbk"), conversationIds = listOf("t:7"), addresses = listOf("098765 43210"))
        assertTrue(matches(c, event(address = "VM-X", mergeKey = "HDFCBK")))
        assertTrue(matches(c, event(address = "VM-X", conversationId = "t:7")))
        assertFalse(matches(c, event(address = "VM-X", conversationId = "T:7")), "conversation ids are exact")
        assertTrue(matches(c, event(address = "+91 98765-43210")))
        assertFalse(matches(c, event(address = "+91 98765 43211")))
        assertFalse(matches(Condition.SenderInGroups(), event()))
        // Short codes and alphanumeric ids compare as text, never by digits.
        assertFalse(matches(Condition.SenderInGroups(addresses = listOf("56161")), event(address = "AD-56161")))
        assertTrue(matches(Condition.SenderInGroups(addresses = listOf(" ad-hdfcbk ")), event(address = "AD-HDFCBK")))
    }

    // --- SIM / category / body ---

    @Test
    fun `SimIs with neither field never matches and a slot needs a known slot`() {
        assertFalse(matches(Condition.SimIs(), event(subId = 1, slot = 0)))
        assertFalse(matches(Condition.SimIs(slot = 0), event(slot = null)))
        assertTrue(matches(Condition.SimIs(subId = 1, slot = 5), event(subId = 1, slot = 0)), "either field is enough")
    }

    @Test
    fun `BodyContains honours ignoreCase and an empty keyword matches everything`() {
        assertTrue(matches(Condition.BodyContains("HELLO"), event()))
        assertFalse(matches(Condition.BodyContains("HELLO", ignoreCase = false), event()))
        assertTrue(matches(Condition.BodyContains(""), event(body = "")))
        assertTrue(matches(Condition.BodyContains("ß"), event(body = "Straße")))
    }

    // --- amounts ---

    @Test
    fun `amount thresholds are inclusive and currency is case-insensitive`() {
        val tx = ExtractedTransaction(TransactionDirection.DEBIT, 10_000, "INR")
        assertTrue(matches(Condition.AmountAtLeast(10_000, "inr"), event(transaction = tx)))
        assertTrue(matches(Condition.AmountAtMost(10_000), event(transaction = tx)))
        assertFalse(matches(Condition.AmountAtLeast(10_001), event(transaction = tx)))
        assertFalse(matches(Condition.AmountAtMost(9_999), event(transaction = tx)))
        assertFalse(matches(Condition.AmountAtMost(Long.MAX_VALUE, "USD"), event(transaction = tx)))
        assertFalse(matches(Condition.Not(Condition.DirectionIs(TransactionDirection.DEBIT)), event(transaction = tx)))
        assertTrue(matches(Condition.Not(Condition.DirectionIs(TransactionDirection.DEBIT)), event()), "no transaction")
    }

    // --- time of day ---

    @Test
    fun `time window bounds are inclusive to the minute`() {
        val w = Condition.TimeWindow(9 * 60, 17 * 60)
        val day = 1_700_006_400_000L // 2023-11-15T00:00:00Z
        fun utc(h: Int, m: Int, s: Int = 0) = day + ((h * 60 + m) * 60 + s) * 1000L
        assertFalse(matches(w, event(dateMillis = utc(8, 59, 59))))
        assertTrue(matches(w, event(dateMillis = utc(9, 0))))
        assertTrue(matches(w, event(dateMillis = utc(17, 0, 59))), "the whole 'to' minute counts")
        assertFalse(matches(w, event(dateMillis = utc(17, 1))))
    }

    @Test
    fun `a wrapping window covers both sides of midnight and nothing in between`() {
        val w = Condition.TimeWindow(22 * 60, 6 * 60)
        val day = 1_700_006_400_000L
        fun utc(h: Int, m: Int) = day + (h * 60 + m) * 60_000L
        for ((h, m, expected) in listOf(Triple(21, 59, false), Triple(22, 0, true), Triple(23, 59, true), Triple(0, 0, true), Triple(6, 0, true), Triple(6, 1, false), Triple(12, 0, false))) {
            assertEquals(expected, matches(w, event(dateMillis = utc(h, m))), "$h:$m")
        }
        // from == to is a one-minute window, not "all day".
        val oneMinute = Condition.TimeWindow(12 * 60, 12 * 60)
        assertTrue(matches(oneMinute, event(dateMillis = utc(12, 0))))
        assertFalse(matches(oneMinute, event(dateMillis = utc(12, 1))))
    }

    @Test
    fun `a zoned window uses that zone's wall clock`() {
        val w = Condition.TimeWindow(9 * 60, 10 * 60, zoneId = "Asia/Kolkata")
        assertTrue(matches(w, event(dateMillis = at("Asia/Kolkata", 2026, 3, 1, 9, 30))))
        // 09:30 UTC is 15:00 in Kolkata.
        assertFalse(matches(w, event(dateMillis = at("UTC", 2026, 3, 1, 9, 30))))
        // Without a zone (legacy rules), the window is UTC.
        assertTrue(matches(w.copy(zoneId = null), event(dateMillis = at("UTC", 2026, 3, 1, 9, 30))))
        // A zone id this runtime does not know falls back to UTC instead of throwing.
        assertTrue(matches(w.copy(zoneId = "Mars/Olympus"), event(dateMillis = at("UTC", 2026, 3, 1, 9, 30))))
    }

    @Test
    fun `a zoned window follows daylight saving time`() {
        // 08:00-09:00 New York local, on both sides of the 2026-03-08 spring-forward.
        val w = Condition.TimeWindow(8 * 60, 9 * 60, zoneId = "America/New_York")
        val beforeDst = at("America/New_York", 2026, 3, 7, 8, 30) // 13:30 UTC
        val afterDst = at("America/New_York", 2026, 3, 9, 8, 30) // 12:30 UTC
        assertTrue(matches(w, event(dateMillis = beforeDst)))
        assertTrue(matches(w, event(dateMillis = afterDst)))
        assertEquals(60 * 60_000L, (beforeDst + 2 * 86_400_000L) - afterDst, "the UTC offset really moved")
        // The skipped local hour (02:00-03:00 on the switch day) never matches a window inside it.
        val gap = Condition.TimeWindow(2 * 60 + 15, 2 * 60 + 45, zoneId = "America/New_York")
        val switchDayStart = at("America/New_York", 2026, 3, 8, 0, 0)
        val hits = (0 until 24 * 60).count { matches(gap, event(dateMillis = switchDayStart + it * 60_000L)) }
        assertEquals(0, hits)
        // The repeated hour on fall-back matches twice (both 01:30s are 01:30 local).
        val fallBack = Condition.TimeWindow(90, 90, zoneId = "America/New_York")
        val fallDayStart = at("America/New_York", 2026, 11, 1, 0, 0)
        val fallHits = (0 until 25 * 60).count { matches(fallBack, event(dateMillis = fallDayStart + it * 60_000L)) }
        assertEquals(2, fallHits)
    }

    @Test
    fun `evaluation does not depend on the JVM default time zone`() {
        val original = java.util.TimeZone.getDefault()
        val w = Condition.TimeWindow(22 * 60, 6 * 60)
        val e = event(dateMillis = 1_700_006_400_000L + 23 * 3_600_000L)
        try {
            val results = listOf("UTC", "Asia/Kolkata", "America/Los_Angeles", "Pacific/Kiritimati").map {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(it))
                matches(w, e)
            }
            assertEquals(listOf(true, true, true, true), results)
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    // --- validity window / expiry ---

    @Test
    fun `ActiveBetween is inclusive at both ends and open-ended without an end`() {
        val c = Condition.ActiveBetween(100, 200)
        assertFalse(matches(c, event(dateMillis = 99)))
        assertTrue(matches(c, event(dateMillis = 100)))
        assertTrue(matches(c, event(dateMillis = 200)))
        assertFalse(matches(c, event(dateMillis = 201)))
        assertTrue(matches(Condition.ActiveBetween(100), event(dateMillis = Long.MAX_VALUE)))
    }

    @Test
    fun `expiry reads only a top-level window`() {
        val top = rule(Condition.All(listOf(Condition.All(listOf(Condition.ActiveBetween(100, 200))), Condition.HasOtp)))
        assertEquals(Condition.ActiveBetween(100, 200), top.activeWindow())
        assertFalse(top.isExpired(200))
        assertTrue(top.isExpired(201))
        assertTrue(top.isNotStartedYet(99))
        assertFalse(top.isNotStartedYet(100))

        val nested = rule(Condition.Any(listOf(Condition.ActiveBetween(100, 200), Condition.HasOtp)))
        assertNull(nested.activeWindow())
        assertFalse(nested.isExpired(Long.MAX_VALUE))
        assertFalse(nested.isNotStartedYet(0))

        val openEnded = rule(Condition.ActiveBetween(100, null))
        assertFalse(openEnded.isExpired(Long.MAX_VALUE))
    }

    @Test
    fun `restarting a window keeps its length and the rest of the rule`() {
        val r = rule(Condition.All(listOf(Condition.ActiveBetween(1_000, 4_000), Condition.HasOtp)))
        val restarted = r.withRestartedWindow(10_000)
        assertEquals(Condition.ActiveBetween(10_000, 13_000), restarted.activeWindow())
        assertEquals(10_000, restarted.updatedAt)
        assertTrue(Condition.HasOtp in (restarted.conditions as Condition.All).children)
        assertEquals(r.actions, restarted.actions)
        val noWindow = rule()
        assertEquals(noWindow, noWindow.withRestartedWindow(10_000))
    }

    // --- planning ---

    @Test
    fun `rules keep list order and a non-matching rule contributes nothing`() {
        val rules = listOf(
            rule(id = "1", actions = listOf(ActionSpec.Label("a"), ActionSpec.Label("b"))),
            rule(Condition.HasOtp, id = "2"),
            rule(id = "3", actions = listOf(ActionSpec.Delete)),
            rule(id = "4", enabled = false),
        )
        val planned = RuleEngine.evaluate(event(), rules)
        assertEquals(listOf("1" to ActionSpec.Label("a"), "1" to ActionSpec.Label("b"), "3" to ActionSpec.Delete), planned.map { it.ruleId to it.action })
        assertTrue(RuleEngine.evaluate(event(), emptyList()).isEmpty())
    }

    @Test
    fun `a rule with no actions plans nothing`() {
        assertTrue(RuleEngine.evaluate(event(), listOf(rule(actions = emptyList()))).isEmpty())
    }

    @Test
    fun `loop guard drops only the looping forward, not the rule's other actions`() {
        val r = rule(
            actions = listOf(
                ActionSpec.ForwardSms(to = "+91 98765 43210"),
                ActionSpec.RelayRule(recipient = "9876543210", channel = RelayChannel.SMS),
                ActionSpec.RelayRule(recipient = "9876543210", channel = RelayChannel.WHATSAPP_ONE_TAP),
                ActionSpec.Label("kept"),
            ),
        )
        val planned = RuleEngine.evaluate(event(address = "+919876543210"), listOf(r)).map { it.action }
        assertEquals(listOf(ActionSpec.RelayRule("9876543210", RelayChannel.WHATSAPP_ONE_TAP), ActionSpec.Label("kept")), planned)
    }

    @Test
    fun `every forwarding action type gets the biometric flag when OTPs can match`() {
        val forwards = listOf(
            ActionSpec.ForwardSms(to = "+15550100"),
            ActionSpec.Webhook(url = "https://x", secretRef = "s"),
            ActionSpec.RelayToWebClient("p"),
            ActionSpec.RelayRule("+15550100", RelayChannel.WHATSAPP_ONE_TAP),
        )
        val otpEvent = event(category = Category.OTP, otp = OtpInfo("1"))
        val planned = RuleEngine.evaluate(otpEvent, listOf(rule(actions = forwards + ActionSpec.Notify())))
        assertEquals(listOf(true, true, true, true, false), planned.map { it.requiresBiometricConfirmation })
        // With OTPs excluded, nothing needs it.
        val safe = rule(Condition.All(app.dak.automations.rule.otpExclusion()), actions = forwards)
        assertTrue(RuleEngine.evaluate(event(), listOf(safe)).none { it.requiresBiometricConfirmation })
    }

    @Test
    fun `evaluation is deterministic for identical inputs`() {
        val rules = RuleCodec.decode(RuleCodec.encode(List(20) { rule(Condition.BodyMatches("h.l+o"), id = "r$it") }))
        val a = RuleEngine.evaluate(event(), rules)
        val b = RuleEngine.evaluate(event(), rules)
        assertEquals(a, b)
        assertEquals(20, a.size)
    }
}
