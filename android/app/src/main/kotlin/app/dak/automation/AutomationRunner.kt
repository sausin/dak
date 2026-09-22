package app.dak.automation

import android.util.Log
import app.dak.automations.MessageEvent
import app.dak.automations.PlannedAction
import app.dak.automations.RuleEngine
import app.dak.automations.action.ActionContext
import app.dak.automations.action.ActionRegistry
import app.dak.automations.action.Archiver
import app.dak.automations.audit.AuditSink
import app.dak.automations.action.Binner
import app.dak.automations.action.IntentLauncher
import app.dak.automations.action.Labeler
import app.dak.automations.action.Notifier
import app.dak.automations.action.ReplyScheduler
import app.dak.automations.action.SmsForwarder
import app.dak.automations.TemplateRenderer
import app.dak.automations.rule.Rule
import app.dak.core.model.Category
import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.OtpInfo
import app.dak.di.ApplicationScope
import app.dak.index.MessageItem
import app.dak.index.bin.RecycleBin
import app.dak.index.enrich.ConversationIds
import app.dak.index.repo.AuditLogRepository
import app.dak.index.repo.ConversationRepository
import app.dak.premium.Entitlements
import app.dak.premium.PremiumGateway
import app.dak.telephony.IncomingMessageHandler
import app.dak.telephony.SimRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs automation rules on every incoming message. Contributed to :core-telephony's handler set at priority 200,
 * i.e. after the notification (0) and the indexer (100), so the message's category, OTP and transaction are
 * already known. Returns immediately; evaluation and actions run on the application scope.
 *
 * Forwarding/relay actions of rules that can match an OTP only run once the rule was confirmed with a biometric
 * check in the Automations screen ([OtpForwardConfirmations]); otherwise they are skipped and audit-logged.
 */
@Singleton
class AutomationRunner @Inject constructor(
    private val rules: RuleRepository,
    private val registry: ActionRegistry,
    private val conversations: ConversationRepository,
    private val sims: SimRepository,
    private val confirmations: OtpForwardConfirmations,
    private val audit: AuditLogRepository,
    private val entitlements: Entitlements,
    private val premiumGateway: PremiumGateway,
    private val smsForwarder: SmsForwarder,
    private val labeler: Labeler,
    private val replyScheduler: ReplyScheduler,
    private val auditSink: AuditSink,
    private val notifications: AutomationNotifications,
    private val bin: RecycleBin,
    private val undoCenter: AutomationUndoCenter,
    @ApplicationScope private val scope: CoroutineScope,
) : IncomingMessageHandler {

    override val priority: Int get() = PRIORITY

    override suspend fun onIncoming(message: Message) {
        if (message.box != MessageBox.INBOX) return
        scope.launch {
            runCatching { run(message) }.onFailure { Log.w(TAG, "automation run failed for ${message.key}", it) }
        }
    }

    /** Evaluates all enabled rules against [message] and executes what they plan. */
    suspend fun run(message: Message) {
        val enabled = rules.enabledRules()
        if (enabled.isEmpty()) return
        val item = withTimeoutOrNull(INDEX_WAIT_MILLIS) { conversations.message(message.key).filterNotNull().first() }
        val event = eventOf(message, item)
        val byId = enabled.associateBy(Rule::id)
        for (planned in RuleEngine.evaluate(event, enabled)) {
            val rule = byId[planned.ruleId] ?: continue
            if (planned.requiresBiometricConfirmation && !confirmations.isConfirmed(rule)) {
                audit.log("rule:${rule.name}", "automation.skipped", event.messageKey, "OTP forwarding not confirmed")
                continue
            }
            registry.execute(planned, event, contextFor(planned, event))
        }
    }

    private fun eventOf(message: Message, item: MessageItem?): MessageEvent = MessageEvent(
        messageKey = message.key.toString(),
        address = message.address,
        mergeKey = item?.conversationId?.let { ConversationIds.mergeKeyOf(it) },
        body = message.body,
        dateMillis = message.dateMillis,
        subId = message.subId,
        slot = sims.sim(message.subId)?.slotIndex?.takeIf { it >= 0 },
        category = item?.category ?: Category.UNKNOWN,
        otp = item?.otp?.let { OtpInfo(code = it.code, webOtpDomain = it.webOtpDomain) },
        transaction = item?.transaction?.let {
            ExtractedTransaction(
                direction = it.direction,
                amountMinor = it.amountMinor,
                currency = it.currency,
                last4 = it.instrumentLast4,
                merchant = it.merchant,
            )
        },
    )

    private fun contextFor(planned: PlannedAction, event: MessageEvent): ActionContext {
        val clock: () -> Long = { System.currentTimeMillis() }
        val zone = ZoneId.systemDefault().id
        return RunContext(
            entitlements = entitlements,
            premiumGateway = premiumGateway,
            smsForwarder = smsForwarder,
            notifier = object : Notifier {
                override suspend fun notify(title: String?, text: String?): Boolean = notifications.post(
                    title = title?.let { TemplateRenderer.render(it, event, zone) }?.takeIf { it.isNotBlank() } ?: planned.ruleName,
                    text = text?.let { TemplateRenderer.render(it, event, zone) } ?: event.body,
                )
            },
            labeler = labeler,
            archiver = IndexArchiver(conversations, undoCenter, planned.ruleName, clock),
            binner = IndexBinner(bin, undoCenter, planned.ruleName, clock),
            intentLauncher = object : IntentLauncher {
                override suspend fun launch(uri: String): Boolean = notifications.postOpen(uri, planned.ruleName)
            },
            replyScheduler = replyScheduler,
            auditSink = auditSink,
            clockMillis = clock,
            zoneId = zone,
        )
    }

    private class RunContext(
        override val entitlements: Entitlements,
        override val premiumGateway: PremiumGateway,
        override val smsForwarder: SmsForwarder,
        override val notifier: Notifier,
        override val labeler: Labeler,
        override val archiver: Archiver,
        override val binner: Binner,
        override val intentLauncher: IntentLauncher,
        override val replyScheduler: ReplyScheduler,
        override val auditSink: AuditSink,
        override val clockMillis: () -> Long,
        override val zoneId: String,
    ) : ActionContext

    companion object {
        const val PRIORITY = 200
        private const val INDEX_WAIT_MILLIS = 3_000L
        private const val TAG = "DakAutomation"
    }
}
