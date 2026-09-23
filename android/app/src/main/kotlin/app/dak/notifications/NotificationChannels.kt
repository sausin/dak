package app.dak.notifications

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.dak.R
import app.dak.core.model.Category
import app.dak.core.model.SimInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dak's notification channels.
 *
 * - Flat channels with stable ids (the constants below) always exist, grouped under "Incoming messages" and "App".
 *   Other modules may post to [FAILURES], [MMS] and [AUTOMATION] by id.
 * - On a multi-SIM device each message category also gets a per-SIM copy (`otp.sim2`, see [SimChannelIds]) inside
 *   a group named "SIM 2 · Airtel", created lazily on the first notification for that SIM. A new per-SIM copy
 *   starts from the flat channel's current settings, so earlier user customisation carries over.
 * - Per-conversation channels live in [ConversationChannels].
 *
 * Channels are created once; afterwards the system owns sound, vibration and importance. Renamed and obsolete ids
 * are migrated / deleted once per [SCHEMA] bump. All work is a few binder calls, done in `Application.onCreate` or
 * on first use. Which channel a message goes to is decided by [ChannelRouting].
 */
@Singleton
class NotificationChannels @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        const val PERSONAL = "personal"
        const val OTP = "otp"
        /** Bank, card and payment alerts, and fake-credit warnings (replaced `transactions` in schema 3). */
        const val ALERTS = "alerts"
        const val PROMOTIONS = "promotions"
        const val OTHER = "other"
        const val SPAM = "spam_silent"
        const val OTP_CONSUMED = "otp_consumed_silent"
        const val FAILURES = "failures"
        const val MMS = "mms_downloads"
        const val SELF_TEST = "self_test"
        const val RELIABILITY = "reliability"

        /** Scheduled sends and automation results ("notify" action, tap-to-open prompts). */
        const val AUTOMATION = "automation"

        const val GROUP_MESSAGES = "messages"
        const val GROUP_APP = "app"
        const val GROUP_CONVERSATIONS = "conversations"

        /** Bump when [ChannelCatalog.renamed] or [ChannelCatalog.obsolete] gain ids or defaults change. */
        private const val SCHEMA = 3
        private const val PREFS = "dak_notification_channels"
        private const val KEY_SCHEMA = "schema"
        private const val KEY_LOCALE = "locale"
        private const val TAG = "DakChannels"

        /** Channels that must be enabled for the core promise (OTPs and people reach the user). */
        val critical: List<String> = ChannelCatalog.all.filter { it.critical }.map { it.id }

        /** Base channel for an incoming message of [category] (see [ChannelRouting] for the full rules). */
        fun forCategory(category: Category): String = ChannelRouting.baseChannel(category)

        /**
         * System settings page for one channel. [conversationShortcutId] targets the conversation's own page on
         * Android 11+ (for channels created by [ConversationChannels]).
         */
        fun settingsIntent(context: Context, channelId: String, conversationShortcutId: String? = null): Intent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                .apply {
                    if (conversationShortcutId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        putExtra(Settings.EXTRA_CONVERSATION_ID, conversationShortcutId)
                    }
                }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val manager: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

    @Volatile private var created = false

    /** Slot → group label last applied, so repeated notifications skip the binder calls. */
    @Volatile private var simGroupsApplied: Map<Int, String> = emptyMap()

    /**
     * Makes sure every flat channel exists (and runs the one-time migration). Cheap after the first call in a
     * process; re-applies names after a locale change. Never touches user-changed settings.
     */
    fun ensureCreated() {
        if (created) return
        synchronized(this) {
            if (created) return
            val nm = manager ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val locale = Locale.getDefault().toLanguageTag()
            val schema = prefs.getInt(KEY_SCHEMA, 0)
            val existing = nm.notificationChannels.mapTo(HashSet()) { it.id }
            val complete = ChannelCatalog.all.all { it.id in existing }
            if (schema != SCHEMA) {
                // Never let a failed migration stop channel creation (the new channels then start from defaults).
                try {
                    migrateRenamed(nm)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "channel migration failed: ${e.javaClass.simpleName}")
                }
                existing.filter { it in ChannelCatalog.obsolete || SimChannelIds.baseOf(it) in ChannelCatalog.obsolete }
                    .forEach { nm.deleteNotificationChannel(it) }
            }
            if (!complete || schema != SCHEMA || prefs.getString(KEY_LOCALE, null) != locale) {
                createBase(nm)
                refreshSimChannelNames(nm)
                prefs.edit().putInt(KEY_SCHEMA, SCHEMA).putString(KEY_LOCALE, locale).apply()
            }
            created = true
        }
    }

    /**
     * Channel to post a message of base channel [baseId] (see [forCategory]) received on [subId]: the per-SIM copy
     * when more than one SIM is active, else the flat channel.
     */
    fun channelFor(baseId: String, subId: Int, sims: List<SimInfo>): String {
        ensureCreated()
        val spec = ChannelCatalog.specOf(baseId)
        if (spec == null || !spec.perSim) return baseId
        val active = activeSlots(sims)
        if (active.size < 2) return baseId
        val slot = sims.firstOrNull { it.subId == subId }?.slotIndex?.takeIf { it in active } ?: return baseId
        ensureSimChannels(sims)
        return SimChannelIds.channelId(baseId, slot)
    }

    /** Creates the per-SIM groups and channels for [sims] when more than one SIM is active. Idempotent and cheap. */
    fun ensureSimChannels(sims: List<SimInfo>) {
        val labels = activeSlots(sims).associateWith { slot -> simGroupName(sims.first { it.slotIndex == slot && it.isActive }) }
        if (labels.size < 2 || labels == simGroupsApplied) return
        ensureCreated()
        synchronized(this) {
            if (labels == simGroupsApplied) return
            val nm = manager ?: return
            nm.createNotificationChannelGroups(labels.map { (slot, name) -> NotificationChannelGroup(SimChannelIds.groupId(slot), name) })
            val existing = nm.notificationChannels.associateBy { it.id }
            val toCreate = labels.keys.flatMap { slot ->
                ChannelCatalog.messages.mapNotNull { spec ->
                    val id = SimChannelIds.channelId(spec.id, slot)
                    if (id in existing) null else build(spec, id, SimChannelIds.groupId(slot), seed = existing[spec.id])
                }
            }
            if (toCreate.isNotEmpty()) nm.createNotificationChannels(toCreate)
            simGroupsApplied = labels
        }
    }

    /** True when notifications posted to [channelId] would not be shown (channel or its group blocked). */
    fun isBlocked(channelId: String): Boolean {
        val nm = manager ?: return false
        val channel = nm.getNotificationChannel(channelId) ?: return false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return true
        val group = channel.group ?: return false
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && nm.getNotificationChannelGroup(group)?.isBlocked == true
    }

    /** Current state of every Dak channel (flat, per-SIM and per-conversation), read from the system. */
    fun states(): List<ChannelState> {
        ensureCreated()
        val nm = manager ?: return emptyList()
        val groups = nm.notificationChannelGroups.associateBy { it.id }
        return nm.notificationChannels.map { channel ->
            val spec = ChannelCatalog.specOf(channel.id)
            val group = channel.group?.let { groups[it] }
            val groupBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && group?.isBlocked == true
            ChannelState(
                id = channel.id,
                name = channel.name?.toString().orEmpty(),
                description = channel.description,
                groupId = channel.group,
                groupName = group?.name?.toString(),
                importance = channel.importance,
                blocked = channel.importance == NotificationManager.IMPORTANCE_NONE || groupBlocked,
                critical = spec?.critical == true,
                customised = spec != null && isCustomised(channel, spec),
                simSlot = SimChannelIds.slotOf(channel.id),
                conversationId = ConversationChannels.conversationIdOf(channel.id),
                defaultImportance = spec?.importance,
            )
        }
    }

    /**
     * "Reset all channels": deletes and recreates every Dak category channel the user has not customised (refreshing
     * names, groups and lowered defaults, and dropping per-SIM channels of SIMs no longer present). Customised
     * channels keep the user's settings; their ids are returned so the UI can point at them. Per-conversation
     * channels are untouched.
     */
    fun resetAll(sims: List<SimInfo>): ResetResult {
        ensureCreated()
        val nm = manager ?: return ResetResult(0, emptyList())
        val activeSlots = activeSlots(sims)
        val kept = mutableListOf<String>()
        var recreated = 0
        synchronized(this) {
            for (channel in nm.notificationChannels) {
                val spec = ChannelCatalog.specOf(channel.id) ?: continue
                if (isCustomised(channel, spec)) {
                    kept += channel.id
                    continue
                }
                nm.deleteNotificationChannel(channel.id)
                recreated++
            }
            // Groups of SIMs that are gone and now empty.
            val stillUsed = nm.notificationChannels.mapNotNullTo(HashSet()) { it.group }
            nm.notificationChannelGroups
                .filter { it.id.startsWith("sim") && it.id !in stillUsed }
                .forEach { nm.deleteNotificationChannelGroup(it.id) }
            simGroupsApplied = emptyMap()
            createBase(nm)
        }
        if (activeSlots.size >= 2) ensureSimChannels(sims)
        return ResetResult(recreated, kept)
    }

    /**
     * Heuristic for "the user changed this channel" (Android does not expose its user-locked fields): importance,
     * sound, vibration, lock-screen visibility or DND bypass differ from Dak's defaults.
     */
    internal fun isCustomised(channel: NotificationChannel, spec: ChannelSpec): Boolean {
        if (channel.importance != spec.importance) return true
        if (channel.canBypassDnd()) return true
        if (channel.lockscreenVisibility != VISIBILITY_NO_OVERRIDE) return true
        if (channel.shouldVibrate() == spec.silent) return true
        val sound = channel.sound
        return if (spec.silent) sound != null else sound != Settings.System.DEFAULT_NOTIFICATION_URI
    }

    // ------------------------------------------------------------------------------------------------ internals

    /**
     * Creates the replacement of every channel whose id was retired ([ChannelCatalog.renamed]), flat and per-SIM,
     * before the old one is deleted: a channel the user changed seeds the new one with its settings (importance,
     * sound, vibration, lock-screen visibility, blocked state), an untouched one gets the new defaults. DND bypass
     * cannot be carried over (only the user can grant it). Deleting the old channel also removes notifications still
     * showing on it, once, on the first start after the update.
     */
    private fun migrateRenamed(nm: NotificationManager) {
        val current = nm.notificationChannels
        val existing = current.mapTo(HashSet()) { it.id }
        val toCreate = current.mapNotNull { old ->
            val newId = ChannelCatalog.renamedTarget(old.id) ?: return@mapNotNull null
            if (newId in existing) return@mapNotNull null
            val renamed = ChannelCatalog.renamed[SimChannelIds.baseOf(old.id) ?: old.id] ?: return@mapNotNull null
            val spec = ChannelCatalog.specOf(renamed.to) ?: return@mapNotNull null
            build(spec, newId, old.group ?: GROUP_MESSAGES, seed = old.takeIf { isCustomised(it, renamed.defaults) })
        }
        if (toCreate.isEmpty()) return
        createGroups(nm) // the old channels' groups exist already; this covers a flat channel that had none
        nm.createNotificationChannels(toCreate)
    }

    private fun createGroups(nm: NotificationManager) {
        nm.createNotificationChannelGroups(
            listOf(
                NotificationChannelGroup(GROUP_MESSAGES, context.getString(R.string.channel_group_messages)),
                NotificationChannelGroup(GROUP_APP, context.getString(R.string.channel_group_app)),
                NotificationChannelGroup(GROUP_CONVERSATIONS, context.getString(R.string.ch_group_conversations)),
            ),
        )
    }

    private fun createBase(nm: NotificationManager) {
        createGroups(nm)
        // For existing ids this only refreshes name/description (and lowers importance of untouched channels,
        // which is how spam became blocked-by-default for users who never changed it).
        nm.createNotificationChannels(
            ChannelCatalog.messages.map { build(it, it.id, GROUP_MESSAGES, seed = null) } +
                ChannelCatalog.app.map { build(it, it.id, GROUP_APP, seed = null) },
        )
    }

    /** Re-applies localized names to existing per-SIM channels without changing any setting. */
    private fun refreshSimChannelNames(nm: NotificationManager) {
        val updates = nm.notificationChannels.mapNotNull { channel ->
            val base = SimChannelIds.baseOf(channel.id) ?: return@mapNotNull null
            val spec = ChannelCatalog.specOf(base) ?: return@mapNotNull null
            NotificationChannel(channel.id, context.getString(spec.name), channel.importance).apply {
                description = context.getString(spec.description)
                group = channel.group
            }
        }
        if (updates.isNotEmpty()) nm.createNotificationChannels(updates)
        simGroupsApplied = emptyMap()
    }

    private fun build(spec: ChannelSpec, id: String, groupId: String, seed: NotificationChannel?): NotificationChannel =
        NotificationChannel(id, context.getString(spec.name), seed?.importance ?: spec.importance).apply {
            description = context.getString(spec.description)
            group = groupId
            if (seed != null) {
                setSound(seed.sound, seed.audioAttributes)
                enableVibration(seed.shouldVibrate())
                seed.vibrationPattern?.let { vibrationPattern = it }
                enableLights(seed.shouldShowLights())
                lightColor = seed.lightColor
                setShowBadge(seed.canShowBadge())
                lockscreenVisibility = seed.lockscreenVisibility
            } else if (spec.silent) {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            } else {
                setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes(spec.perSim))
                enableVibration(true)
                setShowBadge(true)
            }
        }

    private fun audioAttributes(message: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(if (message) AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT else AudioAttributes.USAGE_NOTIFICATION)
        .build()

    private fun activeSlots(sims: List<SimInfo>): Set<Int> =
        sims.filter { it.isActive && it.slotIndex >= 0 }.mapTo(sortedSetOf()) { it.slotIndex }

    private fun simGroupName(sim: SimInfo): String {
        val carrier = sim.displayName.ifBlank { sim.carrierName.orEmpty() }.trim()
        val number = sim.slotIndex + 1
        return if (carrier.isEmpty()) context.getString(R.string.ch_group_sim, number)
        else context.getString(R.string.ch_group_sim_carrier, number, carrier)
    }
}

/** `NotificationManager.VISIBILITY_NO_OVERRIDE` is hidden from the public SDK; this is its platform value. */
private const val VISIBILITY_NO_OVERRIDE = -1000

/** One channel as the system currently has it. */
data class ChannelState(
    val id: String,
    val name: String,
    val description: String?,
    val groupId: String?,
    val groupName: String?,
    /** `NotificationManager.IMPORTANCE_*`; `IMPORTANCE_NONE` when blocked. */
    val importance: Int,
    /** True when the channel or its group is blocked. */
    val blocked: Boolean,
    val critical: Boolean,
    /** True when the user changed it away from Dak's defaults (see [NotificationChannels.isCustomised]). */
    val customised: Boolean,
    /** 0-based slot for per-SIM channels. */
    val simSlot: Int?,
    /** Conversation id for per-conversation channels. */
    val conversationId: String?,
    val defaultImportance: Int?,
)

/** Outcome of [NotificationChannels.resetAll]. */
data class ResetResult(val recreated: Int, val keptCustomised: List<String>)
