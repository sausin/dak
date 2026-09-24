package app.dak.automation

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.dak.R
import app.dak.automations.birthdays.WishTag
import app.dak.birthdays.BirthdayStore
import app.dak.broadcast.BroadcastStore
import app.dak.di.ApplicationScope
import app.dak.index.ContactLookup
import app.dak.index.repo.ScheduledSend
import app.dak.index.repo.ScheduledSendStatus
import app.dak.index.repo.ScheduledSendStore
import app.dak.navigation.IntentRoutes
import app.dak.navigation.Routes
import app.dak.notifications.NotificationChannels
import app.dak.settings.DakSettings
import app.dak.settings.ScheduledHeadsUpLead
import app.dak.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "heads-up before a scheduled message goes out" notifications, on their own channel
 * ([NotificationChannels.SCHEDULED], default importance, in the app group; Do Not Disturb applies as the user set it).
 *
 * [refresh] is the one entry point: it reads the pending sends, lets [HeadsUpPlanner] decide what to post, remove and
 * summarise, applies that, and arms ONE wakeup for the next heads-up due, the same way [ScheduledSendScheduler] arms a
 * send (an exact alarm when permitted, else inexact, to [ScheduledSendHeadsUpReceiver], plus a WorkManager job,
 * [ScheduledSendHeadsUpWorker], as the safety net). It runs whenever a send is scheduled, moved or cancelled, after
 * every executor run, when the lead-time setting changes, and after a reboot / clock change (through
 * [ScheduledSendScheduler.rearmPending]), so it survives reboots without state of its own beyond what was announced.
 *
 * One heads-up per send (per broadcast for broadcasts), with a stable tag + id ([HeadsUpKeys]) so updates replace
 * instead of stacking. With two or more showing they are grouped under an InboxStyle summary ("To Mom · 9:00 AM");
 * beyond [HeadsUpPlanner.MAX_INDIVIDUAL] the rest are listed in the summary only. Actions (Send now, Delay, Cancel)
 * go to [ScheduledSendHeadsUpReceiver] (explicit, not exported) with immutable PendingIntents carrying only the
 * scheduled-send id; tapping opens the scheduled list on that send. The lock-screen version follows the app's
 * lock-screen privacy setting (never the message text unless the user chose "Full message").
 */
@Singleton
class ScheduledSendHeadsUp @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ScheduledSendStore,
    private val settings: SettingsStore,
    private val contacts: ContactLookup,
    private val birthdays: BirthdayStore,
    private val broadcasts: BroadcastStore,
    private val channels: NotificationChannels,
) {
    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Brings the heads-up notifications in line with the pending sends and arms the next wakeup. */
    suspend fun refresh(nowMillis: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        mutex.withLock { refreshLocked(nowMillis) }
    }

    /**
     * The executor looked at these sends: their heads-up is done. It is removed, and a send the executor moved (held
     * for the default-SMS-app role or the rate limit) is not announced again at its new time. Broadcast heads-ups go
     * away with the first copy that is handled.
     */
    suspend fun markHandled(sendIds: Collection<Long>) = withContext(Dispatchers.IO) {
        if (sendIds.isEmpty()) return@withContext
        mutex.withLock {
            val announced = loadAnnounced().toMutableMap()
            for (id in sendIds) {
                val row = store.get(id) ?: continue
                val key = HeadsUpKeys.of(row.id, row.ruleId)
                cancelChild(key)
                val version = when {
                    HeadsUpKeys.broadcastIdOf(key) != null -> broadcastStart(key, row)
                    row.status == ScheduledSendStatus.PENDING -> row.sendAtMillis
                    else -> null
                }
                if (version != null) announced[key] = HeadsUpAnnounced(version, collapsed = false) else announced.remove(key)
            }
            saveAnnounced(announced)
        }
    }

    /** Removes [sendId]'s heads-up at once (e.g. an automatic wish that became an "Ask me first" prompt). */
    fun dismiss(sendId: Long, ruleId: String?) {
        cancelChild(HeadsUpKeys.of(sendId, ruleId))
    }

    /** Swaps the actions of [sendId]'s heads-up for the delay choices (silently, same notification). */
    suspend fun showDelayChoices(sendId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val row = store.get(sendId) ?: return@withLock
            val key = HeadsUpKeys.of(row.id, row.ruleId)
            val candidate = candidates(store.pending().first(), leadMinutes(), ZoneId.systemDefault())[key] ?: return@withLock
            if (canNotify()) notify(HeadsUpKeys.tagOf(key), HeadsUpKeys.notificationId(key), buildChild(candidate, silent = true, delayChoices = true))
        }
    }

    /**
     * Tells the user once that a legacy scheduled text to an emergency number was cancelled instead of sent (see
     * [ScheduledEmergencyPolicy]). Tapping opens the composer with the text, to send it now if still needed.
     */
    fun postEmergencyCancelled(send: ScheduledSend, emergencyAddress: String) {
        if (!canNotify()) return
        channels.ensureCreated()
        val title = context.getString(R.string.sched_emergency_cancelled_title, emergencyAddress)
        val text = context.getString(R.string.sched_emergency_cancelled_text)
        val open = PendingIntent.getActivity(
            context,
            HeadsUpKeys.notificationId(HeadsUpKeys.forSend(send.id)),
            IntentRoutes.open(context, Routes.compose(to = send.addresses.joinToString(","), body = send.body)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.SCHEDULED)
            .setSmallIcon(R.drawable.ic_stat_dak)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            // No message text here, so the notification may show as is on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        notify(EMERGENCY_TAG_PREFIX + send.id, HeadsUpKeys.notificationId(HeadsUpKeys.forSend(send.id)), notification)
    }

    // ------------------------------------------------------------------------------------------------ internals

    private suspend fun refreshLocked(nowMillis: Long) {
        val zone = ZoneId.systemDefault()
        val lead = leadMinutes()
        val pending = runCatching { store.pending().first() }.getOrElse {
            Log.w(TAG, "pending sends unreadable", it)
            return
        }
        val candidates = candidates(pending, lead, zone)
        val plan = HeadsUpPlanner.plan(
            items = candidates.values.map { it.item },
            announced = loadAnnounced(),
            visible = visibleKeys(),
            nowMillis = nowMillis,
        )
        plan.cancel.forEach { cancelChild(it) }
        val allowed = canNotify()
        if (allowed && plan.post.isNotEmpty()) channels.ensureCreated()
        if (allowed) {
            for (post in plan.post) {
                val candidate = candidates[post.key] ?: continue
                notify(HeadsUpKeys.tagOf(post.key), HeadsUpKeys.notificationId(post.key), buildChild(candidate, post.silent, delayChoices = false))
            }
        }
        if (allowed && plan.summaryKeys.isNotEmpty()) {
            channels.ensureCreated()
            notify(HeadsUpKeys.SUMMARY_TAG, HeadsUpKeys.SUMMARY_ID, buildSummary(plan.summaryKeys.mapNotNull { candidates[it] }, plan.summaryAlerts, nowMillis))
        } else {
            NotificationManagerCompat.from(context).cancel(HeadsUpKeys.SUMMARY_TAG, HeadsUpKeys.SUMMARY_ID)
        }
        saveAnnounced(plan.announced)
        armNext(plan.nextWakeAtMillis, nowMillis)
    }

    private fun leadMinutes(): Int? =
        runCatching { ScheduledHeadsUpLead.minutesOf(settings.get(DakSettings.scheduledHeadsUp)) }.getOrDefault(null)

    /** One candidate per key: a single send, or a broadcast with its earliest pending copy. */
    private fun candidates(pending: List<ScheduledSend>, lead: Int?, zone: ZoneId): Map<String, Candidate> =
        pending.filter { it.status == ScheduledSendStatus.PENDING }
            .groupBy { HeadsUpKeys.of(it.id, it.ruleId) }
            .mapValues { (key, rows) ->
                val first = rows.minWith(compareBy<ScheduledSend>({ it.sendAtMillis }, { it.id }))
                val subject = HeadsUpSubject.of(ScheduledSendOrigin.of(first.ruleId))
                val at = if (subject == HeadsUpSubject.BROADCAST) broadcastStart(key, first) else first.sendAtMillis
                Candidate(
                    item = HeadsUpItem(key, at, version = at, headsUpAtMillis = HeadsUpTiming.headsUpAt(at, lead, subject, zone)),
                    first = first,
                    subject = subject,
                    copies = rows.size,
                )
            }

    /** A broadcast's scheduled start (its "send time" for the heads-up); an immediate broadcast's is its creation. */
    private fun broadcastStart(key: String, first: ScheduledSend): Long {
        val record = HeadsUpKeys.broadcastIdOf(key)?.let { runCatching { broadcasts.record(it) }.getOrNull() }
        return record?.scheduledAtMillis ?: record?.createdAt ?: first.sendAtMillis
    }

    private fun buildChild(candidate: Candidate, silent: Boolean, delayChoices: Boolean): Notification {
        val sendId = candidate.first.id
        val time = formatTime(candidate.item.sendAtMillis, System.currentTimeMillis())
        val who = recipientLabel(candidate)
        val title = context.getString(
            when (candidate.subject) {
                HeadsUpSubject.AUTOMATIC_BIRTHDAY -> R.string.sched_hu_title_birthday
                HeadsUpSubject.BROADCAST -> R.string.sched_hu_title_broadcast
                else -> R.string.sched_hu_title
            },
            who,
            time,
        )
        val preview = if (delayChoices) context.getString(R.string.sched_hu_delay_prompt) else preview(candidate.first.body)
        val broadcast = candidate.subject == HeadsUpSubject.BROADCAST
        val builder = NotificationCompat.Builder(context, NotificationChannels.SCHEDULED)
            .setSmallIcon(R.drawable.ic_stat_dak)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setAutoCancel(true)
            .setWhen(candidate.item.sendAtMillis)
            .setShowWhen(true)
            .setContentIntent(openList(candidate, pickTime = false))
        if (delayChoices) {
            builder.addAction(0, context.getString(R.string.sched_hu_action_delay_hour), action(HeadsUpAction.DELAY_HOUR, sendId))
            builder.addAction(0, context.getString(R.string.sched_hu_action_delay_tomorrow), action(HeadsUpAction.DELAY_TOMORROW, sendId))
            // "Pick time" opens the scheduled list with the date and time pickers (single sends; a broadcast is moved
            // as a whole from here, keeping the pacing between its copies).
            if (!broadcast) builder.addAction(0, context.getString(R.string.sched_hu_action_pick_time), openList(candidate, pickTime = true))
        } else {
            builder.addAction(0, context.getString(R.string.sched_hu_action_send_now), action(HeadsUpAction.SEND_NOW, sendId))
            builder.addAction(0, context.getString(R.string.sched_hu_action_delay), action(HeadsUpAction.DELAY_MENU, sendId))
            builder.addAction(0, context.getString(R.string.sched_hu_action_cancel), action(HeadsUpAction.CANCEL, sendId))
        }
        applyLockScreenPrivacy(builder, title, time, candidate.item.sendAtMillis)
        return builder.build()
    }

    private fun buildSummary(members: List<Candidate>, alert: Boolean, nowMillis: Long): Notification {
        val title = context.resources.getQuantityString(R.plurals.sched_hu_summary_title, members.size, members.size)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        members.take(MAX_SUMMARY_LINES).forEach { member ->
            style.addLine(context.getString(R.string.sched_hu_summary_line, recipientLabel(member), formatTime(member.item.sendAtMillis, nowMillis)))
        }
        val more = members.size - MAX_SUMMARY_LINES
        if (more > 0) style.setSummaryText(context.resources.getQuantityString(R.plurals.sched_hu_summary_more, more, more))
        val builder = NotificationCompat.Builder(context, NotificationChannels.SCHEDULED)
            .setSmallIcon(R.drawable.ic_stat_dak)
            .setContentTitle(title)
            .setContentText(members.joinToString { recipientLabel(it) })
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            // The summary only alerts for heads-ups that went straight into it (beyond the individual limit).
            .setGroupAlertBehavior(if (alert) NotificationCompat.GROUP_ALERT_SUMMARY else NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    HeadsUpKeys.SUMMARY_ID,
                    IntentRoutes.open(context, Routes.scheduledSends()),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        applyLockScreenPrivacy(builder, title, null, null)
        return builder.build()
    }

    /**
     * Like [app.dak.notifications.MessageNotifier]: "Full message" shows the notification as is on the lock screen;
     * "Sender only" shows who and when but never the text; "Hide sender and message" shows a generic line.
     */
    private fun applyLockScreenPrivacy(builder: NotificationCompat.Builder, title: String, time: String?, whenMillis: Long?) {
        val mode = runCatching { settings.get(DakSettings.lockScreenPrivacy) }.getOrDefault(PRIVACY_HIDE_CONTENT)
        if (mode == PRIVACY_FULL) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            return
        }
        val public = NotificationCompat.Builder(context, NotificationChannels.SCHEDULED)
            .setSmallIcon(R.drawable.ic_stat_dak)
            .setGroup(GROUP_KEY)
        if (mode == PRIVACY_HIDE_ALL) {
            public.setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.sched_hu_public_text))
        } else {
            public.setContentTitle(title)
                .setContentText(time?.let { context.getString(R.string.sched_hu_public_text_at, it) } ?: context.getString(R.string.sched_hu_public_text))
        }
        whenMillis?.let { public.setWhen(it).setShowWhen(true) }
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public.build())
    }

    /** "Mom", "Mom, Ravi", "Family (12 people)" or, for a birthday, the name the Birthdays screen uses. */
    private fun recipientLabel(candidate: Candidate): String {
        val send = candidate.first
        if (candidate.subject == HeadsUpSubject.BROADCAST) {
            val record = HeadsUpKeys.broadcastIdOf(candidate.item.key)?.let { runCatching { broadcasts.record(it) }.getOrNull() }
            val count = record?.recipients?.size ?: candidate.copies
            val name = record?.listName?.takeIf { it.isNotBlank() } ?: nameOf(send.addresses.firstOrNull().orEmpty())
            return context.resources.getQuantityString(R.plurals.sched_hu_broadcast_recipients, count, name, count)
        }
        if (candidate.subject == HeadsUpSubject.AUTOMATIC_BIRTHDAY) {
            WishTag.decode(send.ruleId)?.let { tag -> birthdays.config(tag.contactId, tag.kind)?.name?.takeIf { it.isNotBlank() } }
                ?.let { return it }
        }
        return send.addresses.joinToString(", ") { nameOf(it) }
    }

    private fun nameOf(address: String): String =
        runCatching { contacts.displayName(address) }.getOrNull()?.takeIf { it.isNotBlank() } ?: address

    /** Time only for today, else date and time. */
    private fun formatTime(millis: Long, nowMillis: Long): String {
        val zone = ZoneId.systemDefault()
        val sameDay = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val time = DateFormat.getTimeFormat(context).format(Date(millis))
        return if (sameDay) time else ForwardingStatusNotifier.formatInstant(context, millis)
    }

    private fun preview(body: String): String {
        val flat = body.replace('\n', ' ').trim()
        return if (flat.length <= PREVIEW_CHARS) flat else flat.take(PREVIEW_CHARS).trimEnd() + "…"
    }

    private fun openList(candidate: Candidate, pickTime: Boolean): PendingIntent {
        val route = if (candidate.subject == HeadsUpSubject.BROADCAST) {
            HeadsUpKeys.broadcastIdOf(candidate.item.key)
                ?.let { runCatching { broadcasts.record(it) }.getOrNull() }
                ?.let { Routes.broadcast(it.listId) }
                ?: Routes.scheduledSends(candidate.first.id)
        } else {
            Routes.scheduledSends(candidate.first.id, pickTime)
        }
        return PendingIntent.getActivity(
            context,
            HeadsUpKeys.notificationId(candidate.item.key),
            IntentRoutes.open(context, route),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Explicit, immutable, carrying only the scheduled-send id (the action is in the intent's action string). */
    private fun action(action: HeadsUpAction, sendId: Long): PendingIntent {
        val intent = Intent(context, ScheduledSendHeadsUpReceiver::class.java)
            .setAction(ScheduledSendHeadsUpReceiver.ACTION_PREFIX + action.wire)
            // A distinct data URI per send keeps each send's PendingIntents apart (extras do not count).
            .setData(Uri.parse("dak://scheduled-send/$sendId"))
            .putExtra(ScheduledSendHeadsUpReceiver.EXTRA_ID, sendId)
        return PendingIntent.getBroadcast(
            context,
            HeadsUpKeys.notificationId(HeadsUpKeys.forSend(sendId)),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelChild(key: String) {
        NotificationManagerCompat.from(context).cancel(HeadsUpKeys.tagOf(key), HeadsUpKeys.notificationId(key))
    }

    private fun notify(tag: String, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        } catch (e: SecurityException) {
            // Notifications revoked between the check and the call; the scheduled list still shows the sends.
        }
    }

    private fun visibleKeys(): Set<String> {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return emptySet()
        return runCatching { manager.activeNotifications.mapNotNullTo(HashSet()) { HeadsUpKeys.keyOfTag(it.tag) } }
            .getOrDefault(emptySet())
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // ------------------------------------------------------------------------------------------------ wakeup

    /** One wakeup for the next heads-up due, armed like a scheduled send (alarm + WorkManager safety net). */
    private fun armNext(atMillis: Long?, nowMillis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val wake = wakeIntent()
        if (atMillis == null) {
            alarms?.cancel(wake)
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
            return
        }
        if (alarms != null) {
            try {
                if (canScheduleExact(alarms)) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, wake)
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, wake)
                }
            } catch (e: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, wake)
            }
        }
        val request = OneTimeWorkRequestBuilder<ScheduledSendHeadsUpWorker>()
            .setInitialDelay((atMillis - nowMillis).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(ScheduledSendHeadsUpWorker.TAG)
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request) }
            .onFailure { Log.w(TAG, "could not enqueue heads-up fallback", it) }
    }

    private fun canScheduleExact(alarms: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    private fun wakeIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        WAKE_REQUEST_CODE,
        Intent(context, ScheduledSendHeadsUpReceiver::class.java).setAction(ScheduledSendHeadsUpReceiver.ACTION_HEADS_UP_DUE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ------------------------------------------------------------------------------------------------ state

    /** Announced heads-ups as `key|version|collapsed` strings (keys may contain ':' but never '|'). */
    private fun loadAnnounced(): Map<String, HeadsUpAnnounced> =
        prefs.getStringSet(KEY_ANNOUNCED, emptySet()).orEmpty().mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size != 3) return@mapNotNull null
            val version = parts[1].toLongOrNull() ?: return@mapNotNull null
            parts[0] to HeadsUpAnnounced(version, collapsed = parts[2] == "1")
        }.toMap()

    private fun saveAnnounced(announced: Map<String, HeadsUpAnnounced>) {
        val set = announced.mapTo(HashSet()) { (key, state) -> "$key|${state.version}|${if (state.collapsed) 1 else 0}" }
        prefs.edit().putStringSet(KEY_ANNOUNCED, set).apply()
    }

    private class Candidate(val item: HeadsUpItem, val first: ScheduledSend, val subject: HeadsUpSubject, val copies: Int)

    private companion object {
        const val TAG = "DakHeadsUp"
        const val PREFS = "dak_scheduled_heads_up"
        const val KEY_ANNOUNCED = "announced"
        const val GROUP_KEY = "app.dak.SCHEDULED_HEADS_UP"
        const val WORK_NAME = "scheduled-heads-up"
        const val WAKE_REQUEST_CODE = 69_998
        const val EMERGENCY_TAG_PREFIX = "scheduled-emergency:"
        const val MAX_SUMMARY_LINES = 5
        const val PREVIEW_CHARS = 120
        const val PRIVACY_FULL = "full"
        const val PRIVACY_HIDE_ALL = "hideAll"
        const val PRIVACY_HIDE_CONTENT = "hideContent"
    }
}

/**
 * Re-plans the heads-ups when the lead-time setting changes (off / 5 / 15 / 60 minutes), so a new choice applies to
 * sends already queued. Started from `Application.onCreate`; everything is resolved lazily on a background scope and
 * nothing is read while no scheduled send may be pending.
 */
@Singleton
class ScheduledHeadsUpSettingsWatcher @Inject constructor(
    private val settings: dagger.Lazy<SettingsStore>,
    private val headsUp: dagger.Lazy<ScheduledSendHeadsUp>,
    private val scheduler: dagger.Lazy<ScheduledSendScheduler>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching {
                settings.get().observe(DakSettings.scheduledHeadsUp).distinctUntilChanged().drop(1).collect {
                    if (scheduler.get().mightHavePending()) headsUp.get().refresh()
                }
            }.onFailure { Log.w("DakHeadsUp", "lead-time setting not watched", it) }
        }
    }
}
