package app.dak.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ShortcutInfo
import android.os.Build
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.dak.R
import app.dak.index.enrich.ConversationIds
import app.dak.index.enrich.SenderGrouping
import app.dak.navigation.IntentRoutes
import app.dak.navigation.Routes
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-conversation ("custom notifications") channels and the long-lived conversation shortcuts Android 11+ needs
 * to list a conversation in the shade's Conversations section (priority, bubbles, per-conversation settings).
 *
 * - [enable] creates channel `conv:<conversationId>` (seeded from the category channel's current settings), marks it
 *   as a conversation channel of that parent via `setConversationId` on API 30+, and publishes the shortcut.
 * - [channelFor] tells [MessageNotifier] which custom channel (if any) an incoming message belongs to. Matching uses
 *   the conversation id and the raw addresses recorded at enable time, so a folded sender group (`m:<key>`) keeps
 *   working even for senders the user merged in by hand.
 * - [publishShortcut] is also called for every personal notification, so plain MessagingStyle notifications
 *   qualify as conversations too.
 *
 * The system's channel list is the source of truth for which custom channels exist; only the address mapping is
 * kept in private SharedPreferences.
 */
@Singleton
class ConversationChannels @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: NotificationChannels,
) {

    companion object {
        private const val CHANNEL_PREFIX = "conv:"
        private const val SHORTCUT_PREFIX = "c:"
        private const val PREFS = "dak_conversation_channels"
        private const val KEY_MAP = "addresses"

        fun channelIdFor(conversationId: String): String = CHANNEL_PREFIX + conversationId

        /** Conversation id of a per-conversation channel id, else null. */
        fun conversationIdOf(channelId: String): String? =
            if (channelId.startsWith(CHANNEL_PREFIX)) channelId.substring(CHANNEL_PREFIX.length).takeIf { it.isNotEmpty() } else null

        /** Shortcut (and locus) id of a conversation; also the channel's conversation id on API 30+. */
        fun shortcutIdFor(conversationId: String): String = SHORTCUT_PREFIX + conversationId

        /** Conversation id a message from [address] in provider thread [threadId] is shown under (no user aliases). */
        fun conversationIdFor(address: String, threadId: Long): String =
            SenderGrouping.conversationId(address, threadId, emptyMap())
    }

    /** A custom conversation channel as the system has it. */
    data class Custom(val conversationId: String, val channelId: String, val title: String, val blocked: Boolean)

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val manager: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

    /** Shortcut ids pushed in this process → label, to skip redundant pushes. */
    private val pushed = ConcurrentHashMap<String, String>()

    /** address alias key → conversation id, loaded lazily from prefs. */
    @Volatile private var addressIndex: Map<String, String>? = null

    /**
     * Conversation ids that have a custom channel. Only Dak creates or deletes these channels (users can block but
     * not delete them), so the set is loaded once from the system and kept in step by [enable] / [remove].
     */
    @Volatile private var customIndex: Set<String>? = null

    /**
     * Creates (or keeps) the custom channel for [conversationId] titled [title], recording [addresses] for matching.
     * Returns the channel id; open it with [NotificationChannels.settingsIntent] and [shortcutIdFor].
     */
    fun enable(conversationId: String, title: String, addresses: List<String>): String {
        channels.ensureCreated()
        val nm = manager
        val channelId = channelIdFor(conversationId)
        val shortcutId = shortcutIdFor(conversationId)
        val label = title.ifBlank { addresses.firstOrNull().orEmpty() }.ifBlank { conversationId }
        publishShortcut(conversationId, label, addresses.firstOrNull())
        if (nm != null && nm.getNotificationChannel(channelId) == null) {
            val parentId = parentOf(conversationId)
            val parent = nm.getNotificationChannel(parentId)
            val channel = NotificationChannel(channelId, label, parent?.importance ?: NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.ch_conversation_desc)
                group = NotificationChannels.GROUP_CONVERSATIONS
                if (parent != null) {
                    setSound(parent.sound, parent.audioAttributes)
                    enableVibration(parent.shouldVibrate())
                    parent.vibrationPattern?.let { vibrationPattern = it }
                    setShowBadge(parent.canShowBadge())
                    lockscreenVisibility = parent.lockscreenVisibility
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setConversationId(parentId, shortcutId)
            }
            nm.createNotificationChannel(channel)
        }
        saveAddresses(conversationId, addresses)
        customIndex = null
        return channelId
    }

    /** Deletes the custom channel of [conversationId]; its messages go back to the category channels. */
    fun remove(conversationId: String) {
        manager?.deleteNotificationChannel(channelIdFor(conversationId))
        saveAddresses(conversationId, emptyList())
        customIndex = null
    }

    /** Every custom conversation channel currently on the system. */
    fun list(): List<Custom> {
        val nm = manager ?: return emptyList()
        return nm.notificationChannels.mapNotNull { channel ->
            val id = conversationIdOf(channel.id) ?: return@mapNotNull null
            Custom(id, channel.id, channel.name?.toString().orEmpty(), channel.importance == NotificationManager.IMPORTANCE_NONE)
        }.sortedBy { it.title.lowercase() }
    }

    fun isEnabled(conversationId: String): Boolean = manager?.getNotificationChannel(channelIdFor(conversationId)) != null

    /**
     * The custom channel for a message from [address] in thread [threadId], with its conversation id, or null when
     * the conversation has none. In-memory lookups only.
     */
    fun channelFor(address: String, threadId: Long): Pair<String, String>? {
        val custom = customIds()
        if (custom.isEmpty()) return null
        val conversationId = sequenceOf(
            ConversationIds.forThread(threadId),
            conversationIdFor(address, threadId),
            addresses()[SenderGrouping.aliasKey(address)],
        ).firstOrNull { it != null && it in custom } ?: return null
        return channelIdFor(conversationId) to conversationId
    }

    /**
     * Publishes (or refreshes) the long-lived dynamic shortcut for [conversationId] with a [Person], locus id and the
     * conversation category, so notifications carrying [shortcutIdFor] count as conversations on Android 11+.
     */
    fun publishShortcut(conversationId: String, title: String, address: String?) {
        val shortcutId = shortcutIdFor(conversationId)
        if (pushed[shortcutId] == title) return
        val person = Person.Builder()
            .setName(title)
            .apply { if (address != null) setKey(address) }
            .build()
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(title)
            .setLongLabel(title)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(IntentRoutes.open(context, Routes.conversation(conversationId)))
            .setLongLived(true)
            .setPerson(person)
            .setLocusId(LocusIdCompat(shortcutId))
            .setCategories(setOf(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION))
            .build()
        val ok = runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }.isSuccess
        if (ok) pushed[shortcutId] = title
    }

    // ------------------------------------------------------------------------------------------------ internals

    private fun parentOf(conversationId: String): String =
        if (ConversationIds.isMergeGroup(conversationId)) NotificationChannels.OTHER else NotificationChannels.PERSONAL

    private fun customIds(): Set<String> {
        customIndex?.let { return it }
        val ids = manager?.notificationChannels?.mapNotNullTo(HashSet()) { conversationIdOf(it.id) } ?: return emptySet()
        customIndex = ids
        return ids
    }

    private fun addresses(): Map<String, String> {
        addressIndex?.let { return it }
        val index = HashMap<String, String>()
        val raw = prefs.getString(KEY_MAP, null)
        if (raw != null) {
            runCatching {
                val json = JSONObject(raw)
                json.keys().forEach { conversationId ->
                    val list = json.optJSONArray(conversationId) ?: return@forEach
                    for (i in 0 until list.length()) index[SenderGrouping.aliasKey(list.optString(i))] = conversationId
                }
            }
        }
        addressIndex = index
        return index
    }

    @Synchronized
    private fun saveAddresses(conversationId: String, list: List<String>) {
        val json = runCatching { JSONObject(prefs.getString(KEY_MAP, null) ?: "{}") }.getOrDefault(JSONObject())
        if (list.isEmpty()) json.remove(conversationId) else json.put(conversationId, JSONArray(list.filter { it.isNotBlank() }))
        prefs.edit().putString(KEY_MAP, json.toString()).apply()
        addressIndex = null
    }
}
