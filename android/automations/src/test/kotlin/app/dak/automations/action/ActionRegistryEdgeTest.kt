package app.dak.automations.action

import app.dak.automations.MessageEvent
import app.dak.automations.PlannedAction
import app.dak.automations.audit.AuditLogEntry
import app.dak.automations.audit.AuditSink
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.RelayChannel
import app.dak.automations.undo.UndoToken
import app.dak.core.model.Category
import app.dak.premium.Entitlements
import app.dak.premium.Feature
import app.dak.premium.PremiumGateway
import app.dak.premium.StaticEntitlements
import app.dak.premium.WebhookRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Failure paths of [DefaultActionRegistry]: every executor can say no, throw, or be cancelled. */
class ActionRegistryEdgeTest {

    /** A context whose every executor answers [ok] (or throws [boom]) and records what it was asked. */
    private class ScriptedContext(
        val ok: Boolean = true,
        val boom: Throwable? = null,
        override val entitlements: Entitlements = StaticEntitlements(Feature.entries.toSet()),
    ) : ActionContext {
        val calls = mutableListOf<String>()
        val audit = mutableListOf<AuditLogEntry>()
        val webhooks = mutableListOf<WebhookRequest>()
        val relays = mutableListOf<Pair<String, String>>()

        private fun step(name: String): Boolean {
            calls += name
            boom?.let { throw it }
            return ok
        }

        override val premiumGateway = object : PremiumGateway {
            override val isAvailable = true
            override suspend fun sendWebhook(request: WebhookRequest): Boolean { webhooks += request; return step("webhook") }
            override suspend fun relayCiphertext(pairingId: String, ciphertext: ByteArray): Boolean {
                relays += pairingId to ciphertext.toString(Charsets.UTF_8); return step("relay")
            }
        }
        override val smsForwarder = object : SmsForwarder {
            override suspend fun forward(to: String, subId: Int?, text: String) = step("sms:$to:$subId:$text")
        }
        override val notifier = object : Notifier {
            override suspend fun notify(title: String?, text: String?) = step("notify")
        }
        override val labeler = object : Labeler {
            override suspend fun label(messageKey: String, label: String) = step("label")
        }
        override val archiver = object : Archiver {
            override suspend fun archive(messageKey: String): UndoToken { step("archive"); return UndoToken(messageKey, "a", "a", 0) }
        }
        override val binner = object : Binner {
            override suspend fun delete(messageKey: String, deletedBy: String): UndoToken { step("delete"); return UndoToken(messageKey, "d", "d", 0) }
        }
        override val intentLauncher = object : IntentLauncher {
            override suspend fun launch(uri: String) = step("launch:$uri")
        }
        override val replyScheduler = object : ReplyScheduler {
            override suspend fun scheduleReply(to: String, subId: Int?, text: String, atMillis: Long) = step("reply:$to:$atMillis")
        }
        override val auditSink = object : AuditSink {
            override suspend fun record(entry: AuditLogEntry) { audit += entry }
        }
        override val clockMillis: () -> Long = { 1_000L }
        override val zoneId: String = "UTC"
    }

    private val registry = DefaultActionRegistry()
    private val event = MessageEvent(
        messageKey = "sms:9", address = "+15550100", body = "pay \"now\" & {otp} #x\n\u0000", dateMillis = 0, subId = 1,
        category = Category.PERSONAL,
    )

    private suspend fun run(action: ActionSpec, ctx: ScriptedContext) = registry.execute(PlannedAction("r", "Rule", action), event, ctx)

    private val everyAction = listOf(
        ActionSpec.Label("x"), ActionSpec.Notify(), ActionSpec.ForwardSms("+1"), ActionSpec.ScheduleReply("t", 1),
        ActionSpec.LaunchIntent("app://x"), ActionSpec.Webhook("https://h", secretRef = "k"), ActionSpec.RelayToWebClient("p"),
        ActionSpec.RelayRule("+1", RelayChannel.SMS), ActionSpec.RelayRule("+1", RelayChannel.WHATSAPP_ONE_TAP),
        ActionSpec.RelayRule("pair", RelayChannel.WEBHOOK),
    )

    @Test
    fun `an executor answering false is a Failed result, and forwards are still audited`() = runTest {
        for (action in everyAction) {
            val ctx = ScriptedContext(ok = false)
            assertIs<ActionResult.Failed>(run(action, ctx), "$action")
        }
        val ctx = ScriptedContext(ok = false)
        run(ActionSpec.ForwardSms("+1"), ctx)
        assertEquals("failed: forward failed", ctx.audit.single().outcome)
    }

    @Test
    fun `archive and delete succeed whatever their executor returns`() = runTest {
        assertEquals(ActionResult.Success, run(ActionSpec.Archive, ScriptedContext(ok = false)))
        assertEquals(ActionResult.Success, run(ActionSpec.Delete, ScriptedContext(ok = false)))
    }

    @Test
    fun `an executor that throws becomes a Failed result with the message, and is audited`() = runTest {
        for (action in everyAction + ActionSpec.Archive + ActionSpec.Delete) {
            val ctx = ScriptedContext(boom = IllegalStateException("radio off"))
            assertEquals(ActionResult.Failed("radio off"), run(action, ctx), "$action")
        }
        val ctx = ScriptedContext(boom = RuntimeException())
        assertEquals(ActionResult.Failed("RuntimeException"), run(ActionSpec.RelayRule("+1", RelayChannel.SMS), ctx))
        assertEquals("failed: RuntimeException", ctx.audit.single().outcome)
        assertEquals("SMS", ctx.audit.single().channel)
    }

    @Test
    fun `cancellation propagates instead of being recorded as a failed run`() = runTest {
        val ctx = ScriptedContext(boom = CancellationException("worker stopped"))
        assertFailsWith<CancellationException> { run(ActionSpec.ForwardSms("+1"), ctx) }
        assertTrue(ctx.audit.isEmpty(), "a cancelled forward must not be logged as failed")
    }

    @Test
    fun `each premium action is locked by its own feature`() = runTest {
        val expected = mapOf(
            ActionSpec.Webhook("https://h", secretRef = "k") to Feature.WEBHOOKS,
            ActionSpec.RelayToWebClient("p") to Feature.WEB_CLIENT,
            ActionSpec.RelayRule("+1", RelayChannel.SMS) to Feature.RELAY_RULES,
        )
        for ((action, feature) in expected) {
            val onlyOthers = ScriptedContext(entitlements = StaticEntitlements(Feature.entries.toSet() - feature))
            assertEquals(ActionResult.Locked(feature), run(action, onlyOthers))
            assertTrue(onlyOthers.calls.isEmpty())
        }
    }

    @Test
    fun `webhook body is valid JSON whatever the message contains`() = runTest {
        val ctx = ScriptedContext()
        run(ActionSpec.Webhook("https://h", template = "{body}", secretRef = "sekret"), ctx)
        val req = ctx.webhooks.single()
        val obj = Json.parseToJsonElement(req.jsonBody).jsonObject
        assertEquals(setOf("messageKey", "sender", "text"), obj.keys)
        assertEquals(event.body, obj["text"]!!.jsonPrimitive.content)
        assertEquals("+15550100", obj["sender"]!!.jsonPrimitive.content)
        assertEquals("sekret", req.signingSecret.toString(Charsets.UTF_8))
    }

    @Test
    fun `whatsapp relay cannot be redirected by the message text`() = runTest {
        val ctx = ScriptedContext()
        val hostile = event.copy(body = "hi&phone=+19999999999#frag ?x=1")
        registry.execute(PlannedAction("r", "Rule", ActionSpec.RelayRule("+1 555 0100", RelayChannel.WHATSAPP_ONE_TAP)), hostile, ctx)
        val uri = ctx.calls.single().removePrefix("launch:")
        assertTrue(uri.startsWith("whatsapp://send?phone="))
        val query = uri.substringAfter('?')
        assertEquals(2, query.split('&').size, uri)
        assertTrue('#' !in uri && ' ' !in uri && '+' !in query.substringAfter("text="), uri)
        val params = query.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("+1 555 0100", params["phone"])
        assertEquals(hostile.body, params["text"])
    }

    @Test
    fun `relay to web client needs a pairing and relay rule webhook uses the recipient as pairing`() = runTest {
        val ctx = ScriptedContext()
        assertEquals(ActionResult.Failed("no pairing configured"), run(ActionSpec.RelayToWebClient(pairingId = null), ctx))
        assertTrue(ctx.relays.isEmpty())
        assertEquals(ActionResult.Success, run(ActionSpec.RelayRule("pair-7", RelayChannel.WEBHOOK, template = "[{sender}]"), ctx))
        assertEquals("pair-7" to "[+15550100]", ctx.relays.single())
        assertEquals("pair-7", ctx.audit.last().recipient)
        assertEquals("WEBHOOK", ctx.audit.last().channel)
    }

    @Test
    fun `non-forwarding actions are never audited`() = runTest {
        val ctx = ScriptedContext()
        listOf(ActionSpec.Label("x"), ActionSpec.Archive, ActionSpec.Notify(), ActionSpec.Delete, ActionSpec.LaunchIntent("a://b"), ActionSpec.ScheduleReply("t", 0))
            .forEach { run(it, ctx) }
        assertTrue(ctx.audit.isEmpty())
    }

    @Test
    fun `audit entries carry the rule, message and clock`() = runTest {
        val ctx = ScriptedContext()
        run(ActionSpec.RelayToWebClient("p1"), ctx)
        val e = ctx.audit.single()
        assertEquals("r", e.ruleId)
        assertEquals("Rule", e.ruleName)
        assertEquals("RelayToWebClient", e.actionType)
        assertEquals("p1", e.recipient)
        assertEquals("WEB_CLIENT", e.channel)
        assertEquals("sms:9", e.messageKey)
        assertEquals(1_000L, e.atMillis)
        assertEquals("success", e.outcome)
    }

    @Test
    fun `scheduled replies go to the sender, never to a template`() = runTest {
        val ctx = ScriptedContext()
        run(ActionSpec.ScheduleReply("On my way {body}", delayMinutes = 0), ctx)
        assertEquals("reply:+15550100:1000", ctx.calls.single())
    }
}
