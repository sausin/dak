package app.dak.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.dak.R
import app.dak.core.model.Category
import app.dak.core.model.Classification
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.OtpInfo
import app.dak.di.ContactLookup
import app.dak.index.enrich.ConversationIds
import app.dak.navigation.IntentRoutes
import app.dak.navigation.Routes
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.IncomingMessageHandler
import app.dak.telephony.SimRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the notification for every incoming message (priority 0: runs before indexing). Classification comes
 * straight from :classify so the notification never waits for the index.
 *
 * - OTP: code large and bold (custom view), Copy / Delete now / Mark read, in-call security warning, and the quiet
 *   path for OTPs an app already consumed (Notifications → Advanced → Consumed OTP handling).
 * - Personal: MessagingStyle per conversation with inline Reply (RemoteInput) and Mark read.
 * - Other categories: their own channel (promotions quiet, spam silent) with Mark read / Delete.
 * Lock-screen privacy follows the registry setting.
 */
@Singleton
class MessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val classifier: NotificationClassifier,
    private val contacts: ContactLookup,
    private val consumedOtps: ConsumedOtpDetector,
    private val callState: CallStateDetector,
    private val settings: SettingsStore,
    private val sims: SimRepository,
    private val selfTest: SelfTestMonitor,
) : IncomingMessageHandler {

    override val priority: Int = 0

    override suspend fun onIncoming(message: Message) {
        if (message.box != MessageBox.INBOX) return
        val classification = classifier.classify(message)
        val posted = try {
            post(message, classification)
        } catch (e: SecurityException) {
            false // POST_NOTIFICATIONS revoked between the check and notify()
        }
        selfTest.onNotified(message, posted)
    }

    /** Posts (or updates) the notification for [message]. Returns false when notifications are blocked. */
    fun post(message: Message, classification: Classification): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val sender = senderName(message, classification)
        val otp = classification.otp
        val (target, notification) = if (classification.category == Category.OTP && otp != null) {
            buildOtp(message, otp, sender)
        } else if (classification.category == Category.PERSONAL || classification.category == Category.UNKNOWN) {
            buildConversation(message, classification.category, sender)
        } else {
            buildInformational(message, classification.category, sender)
        }
        manager.notify(target.tag, target.id, notification)
        return true
    }

    /** Cancels every notification belonging to provider thread [threadId] (call when the thread is read). */
    fun cancelForThread(threadId: Long) {
        val platform = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        platform.activeNotifications
            .filter { it.notification.extras.getLong(EXTRA_THREAD, Long.MIN_VALUE) == threadId }
            .forEach { platform.cancel(it.tag, it.id) }
    }

    // ------------------------------------------------------------------------------------------------ OTP

    private fun buildOtp(message: Message, otp: OtpInfo, sender: String): Pair<NotificationActions.Target, Notification> {
        val target = NotificationActions.Target(tag = "otp:${message.key}", id = ID_OTP)
        val consumer = consumedOtps.consumerOf(otp)
        val handling = settings.get(DakSettings.consumedOtpHandling)
        val quiet = consumer != null && handling != "normal"
        val inCall = callState.isInCall()
        val large = settings.get(DakSettings.otpDisplaySize) == "large"
        val warning = if (inCall) context.getString(R.string.otp_in_call_warning) else null
        val usedBy = consumer?.let { context.getString(R.string.otp_used_by, consumedOtps.labelOf(it)) }

        val collapsed = RemoteViews(context.packageName, R.layout.notification_otp).apply {
            setTextViewText(R.id.otp_code, otp.code)
            setTextViewTextSize(R.id.otp_code, TypedValue.COMPLEX_UNIT_SP, if (large) 32f else 24f)
            setTextViewText(R.id.otp_sender, warning ?: listOfNotNull(sender, usedBy).joinToString(" · "))
        }
        val expanded = RemoteViews(context.packageName, R.layout.notification_otp_big).apply {
            setTextViewText(R.id.otp_code, otp.code)
            setTextViewTextSize(R.id.otp_code, TypedValue.COMPLEX_UNIT_SP, if (large) 40f else 28f)
            if (warning != null) {
                setViewVisibility(R.id.otp_warning, View.VISIBLE)
                setTextViewText(R.id.otp_warning, warning)
            }
            setTextViewText(R.id.otp_sender, listOfNotNull(sender, usedBy).joinToString(" · "))
            setTextViewText(R.id.otp_body, message.body)
        }

        val builder = baseBuilder(
            channel = if (quiet) NotificationChannels.OTP_CONSUMED else NotificationChannels.OTP,
            message = message,
            smallIcon = R.drawable.ic_stat_otp,
        )
            .setContentTitle("${otp.code} · $sender")
            .setContentText(warning ?: message.body)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSilent(quiet)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .addAction(0, context.getString(R.string.action_copy_code), NotificationActions.copyCode(context, target, otp.code))
            .addAction(0, context.getString(R.string.action_delete_now), NotificationActions.delete(context, target, message.key))
            .addAction(0, context.getString(R.string.action_mark_read), NotificationActions.markRead(context, target, message.key, message.threadId))

        if (usedBy != null) builder.setSubText(usedBy)
        if (inCall) builder.setColor(ContextCompat.getColor(context, R.color.dak_notification_warning))
        otpTimeoutMillis(quiet && handling == "silentAutoDelete")?.let { builder.setTimeoutAfter(it) }
        applyLockScreenPrivacy(builder, message, sender, isOtp = true)
        return target to builder.build()
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

    private fun buildConversation(message: Message, category: Category, sender: String): Pair<NotificationActions.Target, Notification> {
        val target = threadTarget(message)
        val contact = contacts.find(message.address)
        val me = Person.Builder().setName(context.getString(R.string.notification_you)).build()
        val from = Person.Builder()
            .setName(sender)
            .setKey(message.address)
            .apply { contact?.lookupKey?.let { setUri("content://com.android.contacts/contacts/lookup/$it") } }
            .build()
        val style = existingMessagingStyle(target) ?: NotificationCompat.MessagingStyle(me)
        val isGroup = message.address.contains(' ')
        style.setGroupConversation(isGroup)
        if (isGroup) style.setConversationTitle(context.getString(R.string.notification_group_title))
        style.addMessage(displayBody(message), message.dateMillis, from)

        val builder = baseBuilder(NotificationChannels.forCategory(category), message, R.drawable.ic_stat_dak)
            .setStyle(style)
            .setContentTitle(sender)
            .setContentText(displayBody(message))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)

        if (settings.get(DakSettings.quickActions)) {
            val reply = NotificationCompat.Action.Builder(
                IconCompat.createWithResource(context, R.drawable.ic_stat_dak),
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
        builder.addAction(
            NotificationCompat.Action.Builder(
                IconCompat.createWithResource(context, R.drawable.ic_stat_dak),
                context.getString(R.string.action_mark_read),
                NotificationActions.markRead(context, target, message.key, message.threadId),
            )
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build(),
        )
        applyLockScreenPrivacy(builder, message, sender, isOtp = false)
        return target to builder.build()
    }

    private fun existingMessagingStyle(target: NotificationActions.Target): NotificationCompat.MessagingStyle? {
        val platform = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        val active = platform.activeNotifications.firstOrNull { it.tag == target.tag && it.id == target.id } ?: return null
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(active.notification)
    }

    // ------------------------------------------------------------------------------------------------ Other

    private fun buildInformational(message: Message, category: Category, sender: String): Pair<NotificationActions.Target, Notification> {
        val target = threadTarget(message)
        val body = displayBody(message)
        val builder = baseBuilder(NotificationChannels.forCategory(category), message, R.drawable.ic_stat_dak)
            .setContentTitle(sender)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(
                when (category) {
                    Category.SPAM -> NotificationCompat.PRIORITY_MIN
                    Category.PROMOTION -> NotificationCompat.PRIORITY_LOW
                    else -> NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .setSilent(category == Category.SPAM || category == Category.PROMOTION)
            .addAction(0, context.getString(R.string.action_mark_read), NotificationActions.markRead(context, target, message.key, message.threadId))
        if (settings.get(DakSettings.quickActions)) {
            builder.addAction(0, context.getString(R.string.action_delete), NotificationActions.delete(context, target, message.key))
        }
        applyLockScreenPrivacy(builder, message, sender, isOtp = false)
        return target to builder.build()
    }

    // ------------------------------------------------------------------------------------------------ Shared

    private fun baseBuilder(channel: String, message: Message, smallIcon: Int): NotificationCompat.Builder {
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
        builder.extras.putLong(EXTRA_THREAD, message.threadId)
        simLabel(message.subId)?.let { builder.setSubText(it) }
        return builder
    }

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

    private fun threadTarget(message: Message) = NotificationActions.Target(tag = "thread:${message.threadId}", id = ID_CONVERSATION)

    private fun senderName(message: Message, classification: Classification): String =
        contacts.displayName(message.address) ?: classification.canonicalSender ?: message.address

    private fun displayBody(message: Message): String = when {
        message.body.isNotBlank() -> message.body
        message.attachments.any { it.mimeType.startsWith("image/") } -> context.getString(R.string.notification_photo)
        message.attachments.isNotEmpty() -> context.getString(R.string.notification_attachment)
        else -> context.getString(R.string.notification_new_message)
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
    }
}
