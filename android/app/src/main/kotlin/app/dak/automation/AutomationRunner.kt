package app.dak.automation

import android.content.Context
import android.util.Log
import app.dak.R
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
import app.dak.automations.action.ActionResult
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Rule
import app.dak.classify.scam.ScamLabels
import app.dak.classify.scam.ScamLevel
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
import app.dak.safety.FakeCreditCheck
import app.dak.telephony.IncomingMessageHandler
import app.dak.telephony.SimRepository
import dagger.hilt.android.qualifiers.ApplicationContext
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
 *
 * Time-boxed rules whose window has ended are disabled here, lazily, before evaluation ([DailyHousekeeping.expire]);
 * each incoming message also gives [DailyHousekeeping.runIfDue] its once-a-day chance. Forwards go through
 * [AndroidSmsForwarder] (rate-limited), are audit-logged by the registry, and a successful forward by a
 * forwarding rule also labels the source message "Fwd → <recipient>".
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
    private val housekeeping: DailyHousekeeping,
    private val fakeCredit: FakeCreditCheck,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) : IncomingMessageHandler {

    override val priority: Int get() = PRIORITY

    override suspend fun onIncoming(message: Message) {
        if (message.box != MessageBox.INBOX) return
        scope.launch {
            runCatching { run(message) }.onFailure { Log.w(TAG, "automation run failed for ${message.key}", it) }
            runCatching { housekeeping.runIfDue() }.onFailure { Log.w(TAG, "housekeeping failed", it) }
        }
    }

    /** Evaluates all enabled rules against [message] and executes what they plan. */
    suspend fun run(message: Message) {
        val enabled = housekeeping.expire(rules.enabledRules(), System.currentTimeMillis())
        if (enabled.isEmpty()) return
        val item = withTimeoutOrNull(INDEX_WAIT_MILLIS) { conversations.message(message.key).filterNotNull().first() }
        val event = eventOf(message, item)
        val scam = scamLevelOf(message, item)
        val byId = enabled.associateBy(Rule::id)
        for (planned in RuleEngine.evaluate(event, enabled)) {
            val rule = byId[planned.ruleId] ?: continue
            if (blockedByScamFlag(scam, planned.action)) {
                audit.log("rule:${rule.name}", "automation.skipped", event.messageKey, "skipped: possible fake credit")
                continue
            }
            if (planned.requiresBiometricConfirmation && !confirmations.isConfirmed(rule)) {
                audit.log("rule:${rule.name}", "automation.skipped", event.messageKey, "OTP forwarding not confirmed")
                continue
            }
            val result = registry.execute(planned, event, contextFor(planned, event))
            if (result is ActionResult.Success) labelForward(rule, planned.action, event.messageKey)
        }
    }

    /**
     * Fake-credit level of [message] (docs/security/fake-credit-scams.md): from the index labels when indexed (a
     * "Not a scam" dismissal clears it), else straight from the detector.
     */
    private fun scamLevelOf(message: Message, item: MessageItem?): ScamLevel =
        if (item != null) ScamLabels.fromLabels(item.labels)?.level ?: ScamLevel.NONE
        else fakeCredit.verdictFor(message).level

    /** Likely fakes run no automation at all; suspicious ones never leave the device (forward, reply, relay). */
    private fun blockedByScamFlag(level: ScamLevel, action: ActionSpec): Boolean = when (level) {
        ScamLevel.LIKELY_SCAM -> true
        ScamLevel.SUSPICIOUS -> action is ActionSpec.ForwardSms || action is ActionSpec.ScheduleReply ||
            action is ActionSpec.Webhook || action is ActionSpec.RelayToWebClient
        ScamLevel.NONE -> false
    }

    /** Marks a message forwarded by a forwarding rule with a persistent label naming the recipient. */
    private suspend fun labelForward(rule: Rule, action: ActionSpec, messageKey: String) {
        if (action !is ActionSpec.ForwardSms || !ForwardingSpec.isForwarding(rule)) return
        val recipient = ForwardingSpec.fromRule(rule)?.recipients?.firstOrNull { it.number == action.to }?.label ?: action.to
        runCatching { labeler.label(messageKey, context.getString(R.string.fw_label_forwarded, recipient)) }
    }

    private fun eventOf(message: Message, item: MessageItem?): MessageEvent = MessageEvent(
        conversationId = item?.conversationId,
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
