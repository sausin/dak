package app.dak.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import app.dak.R
import app.dak.classify.scam.ScamLevel
import app.dak.classify.scam.ScamVerdict
import app.dak.core.model.Category
import app.dak.core.model.Classification
import app.dak.core.model.ClassifierSource
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.OtpInfo
import app.dak.di.AndroidContactLookup
import app.dak.index.enrich.ConversationIds
import app.dak.navigation.IntentRoutes
import app.dak.navigation.Routes
import app.dak.safety.FakeCreditCheck
import app.dak.security.AppLockNotifications
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.IncomingMessageHandler
import app.dak.telephony.SimRepository
import app.dak.ui.common.text.BidiText
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the notification for every incoming message (priority 0: runs before indexing). Classification comes
 * straight from :classify so the notification never waits for the index.
 *
 * - OTP: code large and bold (custom view), copied to the clipboard on arrival ([OtpClipboard], setting "Copy OTPs
 *   automatically") and marked "Copied", else a Copy action; Mark read / Delete; in-call security warning; and the
 *   quiet path for OTPs an app already consumed (Notifications → Advanced → Consumed OTP handling), looked up in the
 *   index's persisted app-hash table ([NotifierIndexLookups]; no package scan on this path).
 * - Muted conversations: still shown, but silently (no sound, vibration or heads-up), OTPs included.
 * - Personal and unknown senders: MessagingStyle per conversation with inline Reply (RemoteInput; only when the
 *   sender has a number to reply to) and Mark read.
 * - Alerts (transactions): sender or brand as title, Mark read / Delete. Promotions: Mark read only (the channel is
 *   silent). Spam: blocked by default, Mark read / Delete. Likely fake credit alerts: a warning on Alerts.
 * - Actions are standard notification actions with short labels, an icon and a semantic action, primary action
 *   first and Delete last: the shade draws them as text in the system style (light and dark), watches, cars and
 *   assistants use the icons and semantics.
 * - Channels: [ChannelRouting] picks the category channel from the classifier output; per-SIM copies on multi-SIM
 *   devices and per-conversation custom channels ([NotificationChannels], [ConversationChannels]); sound and
 *   vibration always come from the channel, never from code.
 * - Repeats ([RepeatCollapse]): an identical (personal) or same-template (others) message within the window
 *   updates the existing notification with "×N" instead of stacking, quietly; a resent OTP replaces the old one
 *   with the latest code. More than three active notifications in a category get an InboxStyle summary
 *   ([NotificationSummaries]).
 * Lock-screen privacy follows the registry setting.
 */
@Singleton
class MessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val classifier: NotificationClassifier,
    private val contacts: AndroidContactLookup,
    private val lookups: NotifierIndexLookups,
    private val callState: CallStateDetector,
    private val settings: SettingsStore,
    private val sims: SimRepository,
    private val selfTest: SelfTestMonitor,
    private val channels: NotificationChannels,
    private val conversationChannels: ConversationChannels,
    private val summaries: NotificationSummaries,
    private val fakeCredit: FakeCreditCheck,
) : IncomingMessageHandler {

    override val priority: Int = 0

    override suspend fun onIncoming(message: Message) {
        if (message.box != MessageBox.INBOX) return
        val classification = classifier.classify(message)
        val otp = classification.otp
        val consumer = if (classification.category == Category.OTP && otp != null) lookups.consumerOf(otp) else null
        val muted = lookups.isMuted(message.address, message.threadId)
        val incognito = lookups.isIncognito(message.address, message.threadId)
        val posted = try {
            if (incognito) postIncognito(message, muted) else post(message, classification, consumer, muted)
        } catch (e: SecurityException) {
            false // POST_NOTIFICATIONS revoked between the check and notify()
        }
        selfTest.onNotified(message, posted)
    }

    /**
     * Posts (or updates) the notification for [message]. Returns false when notifications are blocked; true also
     * when the message's channel is spam and blocked on purpose (spam is in-app only by default).
     *
     * @param otpConsumer the app that auto-read the OTP (quiet path), if any.
     * @param muted the conversation is muted: post silently (no sound, vibration or heads-up).
     * @param incognito an incognito chat ([postIncognito]): no "Mark read" action.
     */
    suspend fun post(
        message: Message,
        classification: Classification,
        otpConsumer: String? = null,
        muted: Boolean = false,
        incognito: Boolean = false,
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        channels.ensureCreated()
        val sender = senderName(message, classification)
        val otp = classification.otp
        val category = classification.category
        val custom = conversationChannels.channelFor(message.address, message.threadId)
        // Likely fake credit alerts get a warning instead of a transaction/conversation notification (spam stays spam).
        val scam = if (!incognito && category != Category.OTP && category != Category.SPAM) fakeCredit.verdictFor(message) else ScamVerdict.None
        val built = if (scam.level == ScamLevel.LIKELY_SCAM) {
            buildScamWarning(message, sender, muted)
        } else if (category == Category.OTP && otp != null) {
            val quiet = otpConsumer != null && settings.get(DakSettings.consumedOtpHandling) != "normal"
            val channel = (if (quiet) null else custom?.first)
                ?: channels.channelFor(ChannelRouting.baseChannel(category, otpConsumed = quiet), message.subId, sims.sims.value)
            // Copied before posting, so the notification can say so. Not for a code an app already read, nor while
            // the user has this OTP channel turned off (no clipboard changes the user is not told about).
            val copied = !quiet && settings.get(DakSettings.otpAutoCopy) && !channels.isBlocked(channel) &&
                OtpClipboard.copyFromBackground(context, otp.code)
            buildOtp(message, otp, sender, channel, otpConsumer, quiet, copied, muted)
        } else if (custom != null ||
            ((category == Category.PERSONAL || category == Category.UNKNOWN) && !ChannelRouting.isInvestmentAlert(category, classification.labels))
        ) {
            buildConversation(message, category, sender, custom, muted, incognito)
        } else {
            // Investment labels pick the channel: a demat security alert is loud, a routine fund / broker update quiet.
            buildInformational(message, category, sender, muted, classification.labels)
        }
        if (channels.isBlocked(built.channelId)) return category == Category.SPAM
        manager.notify(built.target.tag, built.target.id, built.notification)
        summaries.refresh(category)
        return true
    }

    /** Cancels every notification belonging to provider thread [threadId] (call when the thread is read). */
    fun cancelForThread(threadId: Long) {
        val platform = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val mine = platform.activeNotifications
            .filter { it.notification.extras.getLong(EXTRA_THREAD, Long.MIN_VALUE) == threadId }
        if (mine.isEmpty()) return
        mine.forEach { platform.cancel(it.tag, it.id) }
        summaries.refreshAll()
    }

    /** Re-evaluates category summaries after notifications were cancelled outside [cancelForThread]. */
    fun refreshSummaries() = summaries.refreshAll()

    /** A notification ready to post, with the channel it resolves to. */
    private class Built(val target: NotificationActions.Target, val notification: Notification, val channelId: String)

    // ------------------------------------------------------------------------------------------------ OTP

    private fun buildOtp(
        message: Message,
        otp: OtpInfo,
        sender: String,
        channel: String,
        consumer: String?,
        quiet: Boolean,
        copied: Boolean,
        muted: Boolean,
    ): Built {
        val shownBody = NotificationText.body(message.body)
        val template = RepeatCollapse.template(shownBody)
        val now = System.currentTimeMillis()
        // A resent / duplicated OTP from the same thread replaces the previous one with the latest code.
        val previous = active().firstOrNull { sbn ->
            sbn.tag?.startsWith("otp:") == true &&
                sbn.notification.extras.getLong(EXTRA_THREAD, Long.MIN_VALUE) == message.threadId &&
                sbn.notification.extras.getString(EXTRA_REPEAT_KEY) == template
        }
        val repeat = previous != null && RepeatCollapse.isRepeat(
            previous.notification.extras.getString(EXTRA_REPEAT_KEY), previous.notification.extras.getLong(EXTRA_REPEAT_AT), template, now,
        )
        val count = if (repeat) previous.notification.extras.getInt(EXTRA_REPEAT_COUNT, 1) + 1 else 1
        val sameCode = repeat && previous.notification.extras.getString(EXTRA_OTP_CODE) == otp.code
        val target = NotificationActions.Target(tag = if (repeat) previous.tag else "otp:${message.key}", id = ID_OTP)
        val handling = settings.get(DakSettings.consumedOtpHandling)
        val inCall = callState.isInCall()
        val large = settings.get(DakSettings.otpDisplaySize) == "large"
        val warning = if (inCall) context.getString(R.string.otp_in_call_warning) else null
        val usedBy = consumer?.let { context.getString(R.string.otp_used_by, labelOf(it)) }
        // Isolated (FSI…PDI): the sender name sits next to the code and "Used by …" in one line.
        val shownSender = RepeatCollapse.withCount(BidiText.isolate(sender), count)
        val copiedLabel = if (copied) context.getString(R.string.otp_copied) else null

        val collapsed = RemoteViews(context.packageName, R.layout.notification_otp).apply {
            setTextViewText(R.id.otp_code, otp.code)
            setTextViewTextSize(R.id.otp_code, TypedValue.COMPLEX_UNIT_SP, if (large) 32f else 24f)
            copiedLabel?.let { showOtpStatus(this, it) }
            setTextViewText(R.id.otp_sender, warning ?: listOfNotNull(shownSender, usedBy).joinToString(" · "))
        }
        val expanded = RemoteViews(context.packageName, R.layout.notification_otp_big).apply {
            setTextViewText(R.id.otp_code, otp.code)
            setTextViewTextSize(R.id.otp_code, TypedValue.COMPLEX_UNIT_SP, if (large) 40f else 28f)
            copiedLabel?.let { showOtpStatus(this, it) }
            if (warning != null) {
                setViewVisibility(R.id.otp_warning, View.VISIBLE)
                setTextViewText(R.id.otp_warning, warning)
            }
            setTextViewText(R.id.otp_sender, listOfNotNull(shownSender, usedBy).joinToString(" · "))
            setTextViewText(R.id.otp_body, shownBody)
        }

        // The title is what watches, summaries and screen readers show; the custom views draw the code themselves.
        val title = if (copied) context.getString(R.string.otp_code_copied, otp.code) else otp.code
        val builder = baseBuilder(channel = channel, message = message, smallIcon = R.drawable.ic_stat_otp, category = Category.OTP)
            .setContentTitle("$title · $shownSender")
            .setContentText(warning ?: shownBody)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (quiet || muted) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            // An exact duplicate (same code) updates quietly; a new code alerts as the channel says.
            .setOnlyAlertOnce(sameCode)
        // Already on the clipboard: no Copy button. Otherwise (setting off, quiet path, copy refused) it leads.
        if (!copied) {
            builder.addAction(
                action(R.drawable.ic_action_copy, R.string.action_copy_code, NotificationActions.copyCode(context, target, otp.code)),
            )
        }
        builder.addAction(markReadAction(target, message)).addAction(deleteAction(target, message))

        if (usedBy != null) builder.setSubText(usedBy)
        if (inCall) builder.setColor(ContextCompat.getColor(context, R.color.dak_notification_warning))
        otpTimeoutMillis(quiet && handling == "silentAutoDelete")?.let { builder.setTimeoutAfter(it) }
        builder.extras.putString(EXTRA_REPEAT_KEY, template)
        builder.extras.putLong(EXTRA_REPEAT_AT, now)
        builder.extras.putInt(EXTRA_REPEAT_COUNT, count)
        builder.extras.putString(EXTRA_OTP_CODE, otp.code)
        applyLockScreenPrivacy(builder, message, sender, isOtp = true)
        if (muted) builder.setSilent(true)
        return Built(target, builder.build(), channel)
    }

    /** The notification disappears when the OTP is due to be auto-deleted. */
    private fun otpTimeoutMillis(consumedAutoDelete: Boolean): Long? {
        if (consumedAutoDelete) {
            val minutes = settings.get(DakSettings.consumedOtpWindowMinutes).coerceAtLeast(5)
            return minutes * 60_000L
        }
        return when (settings.get(DakSettings.otpAutoDelete)) {
            "1h" -> 60 * 60_000L
            "24h" -> 24 * 60 * 60_000L
            else -> null
        }
    }

    // ------------------------------------------------------------------------------------------------ Personal

    private fun buildConversation(
        message: Message,
        category: Category,
        sender: String,
        custom: Pair<String, String>?,
        muted: Boolean,
        incognito: Boolean = false,
    ): Built {
        val target = threadTarget(message)
        val contact = contacts.find(message.address)
        val me = Person.Builder().setName(context.getString(R.string.notification_you)).build()
        val from = Person.Builder()
            .setName(sender)
            .setKey(message.address)
            .apply { contact?.lookupKey?.let { setUri("content://com.android.contacts/contacts/lookup/$it") } }
            .build()
        val previous = activeTarget(target)
        val style = previous?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification) }
            ?: NotificationCompat.MessagingStyle(me)
        val isGroup = message.address.contains(' ')
        style.setGroupConversation(isGroup)
        if (isGroup) style.setConversationTitle(context.getString(R.string.notification_group_title))

        // Identical text from the same sender within the window: bump "×N" on the last line instead of a new one.
        val body = displayBody(message)
        val key = RepeatCollapse.exact(body)
        val now = System.currentTimeMillis()
        val extras = previous?.notification?.extras
        val repeat = extras != null &&
            extras.getString(EXTRA_REPEAT_SENDER) == message.address &&
            RepeatCollapse.isRepeat(extras.getString(EXTRA_REPEAT_KEY), extras.getLong(EXTRA_REPEAT_AT), key, now) &&
            removeLastMessage(style)
        val count = if (repeat) extras.getInt(EXTRA_REPEAT_COUNT, 1) + 1 else 1
        val shown = RepeatCollapse.withCount(body, count)
        style.addMessage(shown, message.dateMillis, from)

        val conversationId = custom?.second ?: ConversationChannels.conversationIdFor(message.address, message.threadId)
        val channel = custom?.first ?: channels.channelFor(ChannelRouting.baseChannel(category), message.subId, sims.sims.value)
        val builder = baseBuilder(channel, message, R.drawable.ic_stat_dak, category)
            .setStyle(style)
            .setContentTitle(sender)
            .setContentText(shown)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (muted) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(repeat)
        // Personal threads and custom channels are conversations: long-lived shortcut + shortcut/locus id on the
        // notification, so Android 11+ lists them under Conversations (priority, per-conversation settings).
        if (category == Category.PERSONAL || custom != null) {
            val title = if (isGroup) context.getString(R.string.notification_group_title) else sender
            conversationChannels.publishShortcut(conversationId, title, message.address.takeUnless { isGroup })
            val shortcutId = ConversationChannels.shortcutIdFor(conversationId)
            builder.setShortcutId(shortcutId).setLocusId(LocusIdCompat(shortcutId))
        }
        builder.extras.putString(EXTRA_REPEAT_SENDER, message.address)
        builder.extras.putString(EXTRA_REPEAT_KEY, key)
        builder.extras.putLong(EXTRA_REPEAT_AT, now)
        builder.extras.putInt(EXTRA_REPEAT_COUNT, count)

        val replyable = settings.get(DakSettings.quickActions) && canReply(message.address)
        if (replyable && AppLockNotifications.actionsNeedUnlock(settings)) {
            // App lock on: no inline reply from the notification; "Reply" opens the conversation after unlocking.
            builder.addAction(
                action(R.drawable.ic_action_reply, R.string.action_reply, AppLockNotifications.openInApp(context, message, "reply"), opensApp = true),
            )
        } else if (replyable) {
            val reply = NotificationCompat.Action.Builder(
                IconCompat.createWithResource(context, R.drawable.ic_action_reply),
                context.getString(R.string.action_reply),
                NotificationActions.reply(context, target, message.address, message.subId, message.threadId),
            )
                .addRemoteInput(NotificationActions.replyRemoteInput(context.getString(R.string.action_reply_hint)))
                .setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build()
            builder.addAction(reply)
        }
        if (!incognito) builder.addAction(markReadAction(target, message))
        applyLockScreenPrivacy(builder, message, sender, isOtp = false)
        if (muted) builder.setSilent(true)
        return Built(target, builder.build(), channel)
    }

    /** Drops the newest message of [style] (to re-add it with a count). False when the list cannot be edited. */
    private fun removeLastMessage(style: NotificationCompat.MessagingStyle): Boolean = try {
        val messages = style.messages
        if (messages.isEmpty()) false else {
            messages.removeAt(messages.lastIndex)
            true
        }
    } catch (e: UnsupportedOperationException) {
        false
    }

    // ------------------------------------------------------------------------------------------------ Fake credit

    /**
     * Warning for a likely fake credit alert (docs/security/fake-credit-scams.md): never styled or channelled as a
     * transaction; posted on Alerts. Report opens the fraud-help screen for this message, Block adds the sender to the
     * system list.
     */
    private fun buildScamWarning(message: Message, sender: String, muted: Boolean): Built {
        val target = NotificationActions.Target(tag = "scam:${message.key}", id = ID_CONVERSATION)
        val channel = channels.channelFor(ChannelRouting.baseChannel(Category.UNKNOWN, likelyScam = true), message.subId, sims.sims.value)
        val text = context.getString(R.string.scam_notification_text)
        val route = Routes.fraudHelp(message.key.toString())
        val report = PendingIntent.getActivity(
            context,
            route.hashCode(),
            IntentRoutes.open(context, route),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = baseBuilder(channel, message, R.drawable.ic_stat_dak, Category.UNKNOWN)
            .setContentTitle(context.getString(R.string.scam_notification_title, BidiText.isolate(sender)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\n\n" + displayBody(message)))
            .setColor(ContextCompat.getColor(context, R.color.dak_notification_warning))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (muted) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .addAction(action(R.drawable.ic_action_report, R.string.scam_action_report, report, opensApp = true))
            .addAction(action(R.drawable.ic_action_block, R.string.scam_action_block, NotificationActions.block(context, target, message.address)))
        applyLockScreenPrivacy(builder, message, sender, isOtp = false)
        if (muted) builder.setSilent(true)
        return Built(target, builder.build(), channel)
    }

    // ------------------------------------------------------------------------------------------------ Other

    private fun buildInformational(message: Message, category: Category, sender: String, muted: Boolean, labels: Set<String> = emptySet()): Built {
        val target = threadTarget(message)
        val body = displayBody(message)
        val now = System.currentTimeMillis()
        val state = RepeatCollapse.next(readState(activeTarget(target)?.notification?.extras), body, RepeatCollapse.template(body), now)
        val latest = RepeatCollapse.withCount(body, state.count)
        val channel = channels.channelFor(ChannelRouting.baseChannel(category, labels = labels), message.subId, sims.sims.value)
        val style = if (state.lines.size > 1) {
            NotificationCompat.InboxStyle()
                .setBigContentTitle(sender)
                .setSummaryText(context.resources.getQuantityString(R.plurals.ch_messages_count, state.total, state.total))
                .also { inbox -> state.displayLines().forEach { inbox.addLine(it) } }
        } else {
            NotificationCompat.BigTextStyle().bigText(latest)
        }
        val builder = baseBuilder(channel, message, R.drawable.ic_stat_dak, category)
            .setContentTitle(sender)
            .setContentText(latest)
            .setStyle(style)
            .setNumber(state.total)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(
                when (category) {
                    Category.SPAM -> NotificationCompat.PRIORITY_MIN
                    Category.PROMOTION -> NotificationCompat.PRIORITY_LOW
                    else -> when {
                        muted -> NotificationCompat.PRIORITY_LOW
                        category == Category.TRANSACTION -> NotificationCompat.PRIORITY_HIGH
                        else -> NotificationCompat.PRIORITY_DEFAULT
                    }
                },
            )
            .setOnlyAlertOnce(state.isRepeat)
            .addAction(markReadAction(target, message))
        // Promotions stay light and quiet: Mark read only.
        if (settings.get(DakSettings.quickActions) && category != Category.PROMOTION) {
            builder.addAction(deleteAction(target, message))
        }
        writeState(builder.extras, state)
        applyLockScreenPrivacy(builder, message, sender, isOtp = false)
        if (muted) builder.setSilent(true)
        return Built(target, builder.build(), channel)
    }

    private fun readState(extras: Bundle?): RepeatCollapse.State? {
        extras ?: return null
        val lines = extras.getStringArray(EXTRA_LINES)?.toList() ?: return null
        val counts = extras.getIntArray(EXTRA_LINE_COUNTS)?.toList() ?: List(lines.size) { 1 }
        return RepeatCollapse.State(
            key = extras.getString(EXTRA_REPEAT_KEY).orEmpty(),
            count = extras.getInt(EXTRA_REPEAT_COUNT, 1),
            atMillis = extras.getLong(EXTRA_REPEAT_AT),
            lines = lines,
            counts = counts,
            total = extras.getInt(EXTRA_TOTAL, lines.size),
        )
    }

    private fun writeState(extras: Bundle, state: RepeatCollapse.State) {
        extras.putString(EXTRA_REPEAT_KEY, state.key)
        extras.putInt(EXTRA_REPEAT_COUNT, state.count)
        extras.putLong(EXTRA_REPEAT_AT, state.atMillis)
        extras.putStringArray(EXTRA_LINES, state.lines.toTypedArray())
        extras.putIntArray(EXTRA_LINE_COUNTS, state.counts.toIntArray())
        extras.putInt(EXTRA_TOTAL, state.total)
    }

    // ------------------------------------------------------------------------------------------------ Shared

    private fun baseBuilder(channel: String, message: Message, smallIcon: Int, category: Category): NotificationCompat.Builder {
        val route = Routes.conversation(ConversationIds.forThread(message.threadId), highlight = message.key.toString())
        val open = PendingIntent.getActivity(
            context,
            route.hashCode(),
            IntentRoutes.open(context, route),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(smallIcon)
            .setColor(ContextCompat.getColor(context, R.color.dak_notification_accent))
            .setWhen(message.dateMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setGroup(NotificationSummaries.groupKey(category))
        builder.extras.putLong(EXTRA_THREAD, message.threadId)
        simLabel(message.subId)?.let { builder.setSubText(it) }
        return builder
    }

    /**
     * A standard action with a short label and an icon. Most phones draw only the label (the default template has
     * ignored action icons since Android 7), in the system's own style for light and dark; watches, Android Auto and
     * OEM templates that draw icons use [icon], and assistants use [semantic].
     */
    private fun action(
        icon: Int,
        label: Int,
        intent: PendingIntent,
        semantic: Int = NotificationCompat.Action.SEMANTIC_ACTION_NONE,
        opensApp: Boolean = false,
    ): NotificationCompat.Action =
        NotificationCompat.Action.Builder(IconCompat.createWithResource(context, icon), context.getString(label), intent)
            .setSemanticAction(semantic)
            .setShowsUserInterface(opensApp)
            .build()

    private fun markReadAction(target: NotificationActions.Target, message: Message): NotificationCompat.Action =
        action(
            R.drawable.ic_action_mark_read,
            R.string.action_mark_read,
            NotificationActions.markRead(context, target, message.key, message.threadId),
            NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ,
        )

    /** Delete in the background, or, with the app lock on, open the message so the user unlocks first. */
    private fun deleteAction(target: NotificationActions.Target, message: Message): NotificationCompat.Action {
        val locked = AppLockNotifications.actionsNeedUnlock(settings)
        val intent = if (locked) {
            AppLockNotifications.openInApp(context, message, "delete")
        } else {
            NotificationActions.delete(context, target, message.key)
        }
        return action(R.drawable.ic_action_delete, R.string.action_delete, intent, NotificationCompat.Action.SEMANTIC_ACTION_DELETE, opensApp = locked)
    }

    /** Shows [label] ("Copied") beside the code in an OTP layout. */
    private fun showOtpStatus(views: RemoteViews, label: String) {
        views.setTextViewText(R.id.otp_status, label)
        views.setViewVisibility(R.id.otp_status, View.VISIBLE)
    }

    /**
     * SMS replies need a number: senders without a digit are alphanumeric ids ("VM-HDFCBK", "Amazon") that cannot
     * receive one (same rule as the conversation screen's business check).
     */
    private fun canReply(address: String): Boolean = address.any { it.isDigit() }

    private fun applyLockScreenPrivacy(builder: NotificationCompat.Builder, message: Message, sender: String, isOtp: Boolean) {
        when (settings.get(DakSettings.lockScreenPrivacy)) {
            "full" -> builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            "hideAll" -> builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
                NotificationCompat.Builder(context, NotificationChannels.OTHER)
                    .setSmallIcon(R.drawable.ic_stat_dak)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.notification_new_message))
                    .setWhen(message.dateMillis)
                    .build(),
            )
            else -> builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
                NotificationCompat.Builder(context, NotificationChannels.OTHER)
                    .setSmallIcon(if (isOtp) R.drawable.ic_stat_otp else R.drawable.ic_stat_dak)
                    .setContentTitle(sender)
                    .setContentText(
                        context.getString(if (isOtp) R.string.notification_new_code else R.string.notification_new_message),
                    )
                    .setWhen(message.dateMillis)
                    .build(),
            )
        }
    }

    private fun active(): List<StatusBarNotification> {
        val platform = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return emptyList()
        return runCatching { platform.activeNotifications.toList() }.getOrDefault(emptyList())
    }

    private fun activeTarget(target: NotificationActions.Target): StatusBarNotification? =
        active().firstOrNull { it.tag == target.tag && it.id == target.id }

    /**
     * An incognito chat's message: a plain conversation notification that never carries the text, a code or an
     * attachment preview (it must be read in the thread, where it then vanishes), and no "Mark read" (reading it
     * anywhere else would not count as viewing it).
     */
    private suspend fun postIncognito(message: Message, muted: Boolean): Boolean {
        val masked = message.copy(body = context.getString(R.string.inc_notification_body), attachments = emptyList())
        return post(masked, Classification(Category.PERSONAL, 1f, ClassifierSource.NONE), muted = muted, incognito = true)
    }

    private fun threadTarget(message: Message) = NotificationActions.Target(tag = "thread:${message.threadId}", id = ID_CONVERSATION)

    private fun senderName(message: Message, classification: Classification): String =
        BidiText.sanitizeDisplayName(
            contacts.displayName(message.address) ?: classification.canonicalSender ?: message.address,
        )

    private fun displayBody(message: Message): String = when {
        message.body.isNotBlank() -> NotificationText.body(message.body)
        message.attachments.any { it.mimeType.startsWith("image/") } -> context.getString(R.string.notification_photo)
        message.attachments.isNotEmpty() -> context.getString(R.string.notification_attachment)
        else -> context.getString(R.string.notification_new_message)
    }

    /** Human-readable label of [packageName] (for "Used by Google Pay"), falling back to the package name. */
    private fun labelOf(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    /** SIM label only matters with more than one SIM. */
    private fun simLabel(subId: Int): String? {
        if (sims.sims.value.size < 2) return null
        val sim = sims.sim(subId) ?: return null
        return sim.displayName.ifBlank { context.getString(R.string.sim_n, (sim.slotIndex + 1).toString()) }
    }

    private companion object {
        const val ID_CONVERSATION = 1
        const val ID_OTP = 2
        const val EXTRA_THREAD = "app.dak.notification.THREAD_ID"
        const val EXTRA_REPEAT_KEY = "app.dak.notification.REPEAT_KEY"
        const val EXTRA_REPEAT_AT = "app.dak.notification.REPEAT_AT"
        const val EXTRA_REPEAT_COUNT = "app.dak.notification.REPEAT_COUNT"
        const val EXTRA_REPEAT_SENDER = "app.dak.notification.REPEAT_SENDER"
        const val EXTRA_OTP_CODE = "app.dak.notification.OTP_CODE"
        const val EXTRA_LINES = "app.dak.notification.LINES"
        const val EXTRA_LINE_COUNTS = "app.dak.notification.LINE_COUNTS"
        const val EXTRA_TOTAL = "app.dak.notification.TOTAL"
    }
}
