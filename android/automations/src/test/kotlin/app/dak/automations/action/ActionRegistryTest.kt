package app.dak.automations.action

import app.dak.automations.MessageEvent
import app.dak.automations.PlannedAction
import app.dak.automations.audit.AuditLogEntry
import app.dak.automations.audit.AuditSink
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.RelayChannel
import app.dak.automations.undo.UndoToken
import app.dak.core.model.Category
import app.dak.premium.Feature
import app.dak.premium.FreeEntitlements
import app.dak.premium.NoOpPremiumGateway
import app.dak.premium.PremiumGateway
import app.dak.premium.StaticEntitlements
import app.dak.premium.WebhookRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeContext(
    override val entitlements: app.dak.premium.Entitlements = FreeEntitlements,
    override val premiumGateway: PremiumGateway = NoOpPremiumGateway,
) : ActionContext {
    var forwardedTo: String? = null
    var forwardedText: String? = null
    var labeled: Pair<String, String>? = null
    var archived = false
    var deleted: Pair<String, String>? = null
    var launchedUri: String? = null
    var notified = false
    var scheduledAt: Long? = null
    val auditEntries = mutableListOf<AuditLogEntry>()

    override val smsForwarder = object : SmsForwarder {
        override suspend fun forward(to: String, subId: Int?, text: String): Boolean {
            forwardedTo = to; forwardedText = text; return true
        }
    }
    override val notifier = object : Notifier {
        override suspend fun notify(title: String?, text: String?): Boolean { notified = true; return true }
    }
    override val labeler = object : Labeler {
        override suspend fun label(messageKey: String, label: String): Boolean { labeled = messageKey to label; return true }
    }
    override val archiver = object : Archiver {
        override suspend fun archive(messageKey: String): UndoToken {
            archived = true
            return UndoToken(messageKey, "archive", "archived", 0)
        }
    }
    override val binner = object : Binner {
        override suspend fun delete(messageKey: String, deletedBy: String): UndoToken {
            deleted = messageKey to deletedBy
            return UndoToken(messageKey, "delete", "deleted", 0)
        }
    }
    override val intentLauncher = object : IntentLauncher {
        override suspend fun launch(uri: String): Boolean { launchedUri = uri; return true }
    }
    override val replyScheduler = object : ReplyScheduler {
        override suspend fun scheduleReply(to: String, subId: Int?, text: String, atMillis: Long): Boolean {
            scheduledAt = atMillis; return true
        }
    }
    override val auditSink = object : AuditSink {
        override suspend fun record(entry: AuditLogEntry) { auditEntries += entry }
    }
    override val clockMillis: () -> Long = { 5000L }
    override val zoneId: String = "UTC"
}

class ActionRegistryTest {

    private val registry = DefaultActionRegistry()
    private val event = MessageEvent(
        messageKey = "sms:1", address = "VM-HDFCBK", mergeKey = "HDFC Bank", body = "hello",
        dateMillis = 0, subId = 1, slot = 0, category = Category.TRANSACTION,
    )

    private fun planned(action: ActionSpec) = PlannedAction("r1", "rule", action)

    @Test
    fun `label, archive, notify, delete delegate to their executors`() = runTest {
        val ctx = FakeContext()
        assertEquals(ActionResult.Success, registry.execute(planned(ActionSpec.Label("x")), event, ctx))
        assertEquals("sms:1" to "x", ctx.labeled)

        assertEquals(ActionResult.Success, registry.execute(planned(ActionSpec.Archive), event, ctx))
        assertTrue(ctx.archived)

        assertEquals(ActionResult.Success, registry.execute(planned(ActionSpec.Notify(text = "hi")), event, ctx))
        assertTrue(ctx.notified)

        assertEquals(ActionResult.Success, registry.execute(planned(ActionSpec.Delete), event, ctx))
        assertEquals("sms:1" to "auto-rule", ctx.deleted)
    }

    @Test
    fun `forwardSms renders the template and audits the recipient`() = runTest {
        val ctx = FakeContext()
        val action = ActionSpec.ForwardSms(to = "+911234567890", template = "msg: {body}")
        assertEquals(ActionResult.Success, registry.execute(planned(action), event, ctx))
        assertEquals("+911234567890", ctx.forwardedTo)
        assertEquals("msg: hello", ctx.forwardedText)
        assertEquals(1, ctx.auditEntries.size)
        assertEquals("+911234567890", ctx.auditEntries[0].recipient)
        assertEquals("SMS", ctx.auditEntries[0].channel)
    }

    @Test
    fun `scheduleReply computes atMillis from the clock plus delay`() = runTest {
        val ctx = FakeContext()
        val action = ActionSpec.ScheduleReply(text = "back soon", delayMinutes = 2)
        registry.execute(planned(action), event, ctx)
        assertEquals(5000L + 2 * 60_000L, ctx.scheduledAt)
    }

    @Test
    fun `premium action is Locked on free entitlements and audited without executing`() = runTest {
        val ctx = FakeContext(entitlements = FreeEntitlements)
        val action = ActionSpec.Webhook(url = "https://example.com", secretRef = "s")
        val result = registry.execute(planned(action), event, ctx)
        assertEquals(ActionResult.Locked(Feature.WEBHOOKS), result)
        assertTrue(ctx.auditEntries.isEmpty()) // never ran, so nothing to audit
    }

    @Test
    fun `webhook executes and is audited when entitled`() = runTest {
        var sent: WebhookRequest? = null
        val gateway = object : PremiumGateway {
            override val isAvailable = true
            override suspend fun sendWebhook(request: WebhookRequest): Boolean { sent = request; return true }
            override suspend fun relayCiphertext(pairingId: String, ciphertext: ByteArray) = true
        }
        val ctx = FakeContext(entitlements = StaticEntitlements(setOf(Feature.WEBHOOKS)), premiumGateway = gateway)
        val action = ActionSpec.Webhook(url = "https://example.com/hook", template = "{body}", secretRef = "s")
        val result = registry.execute(planned(action), event, ctx)
        assertEquals(ActionResult.Success, result)
        assertEquals("https://example.com/hook", sent?.url)
        assertTrue(sent!!.jsonBody.contains("hello"))
        assertEquals(1, ctx.auditEntries.size)
        assertEquals("WEBHOOK", ctx.auditEntries[0].channel)
    }

    @Test
    fun `relayRule over SMS forwards via the SmsForwarder`() = runTest {
        val ctx = FakeContext(entitlements = StaticEntitlements(setOf(Feature.RELAY_RULES)))
        val action = ActionSpec.RelayRule(recipient = "+1000", channel = RelayChannel.SMS, template = "{body}")
        val result = registry.execute(planned(action), event, ctx)
        assertEquals(ActionResult.Success, result)
        assertEquals("+1000", ctx.forwardedTo)
    }

    @Test
    fun `unknown action fails cleanly instead of throwing`() = runTest {
        val ctx = FakeContext()
        val action = ActionSpec.Unknown("futureAction", kotlinx.serialization.json.JsonObject(emptyMap()))
        val result = registry.execute(planned(action), event, ctx)
        assertIs<ActionResult.Failed>(result)
    }
}
