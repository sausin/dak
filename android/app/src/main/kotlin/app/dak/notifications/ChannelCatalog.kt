package app.dak.notifications

import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import app.dak.R

/**
 * The defaults Dak creates each of its own channels with. After creation the system owns the channel: Dak never
 * re-applies sound, vibration or importance (Android only lets an app lower importance of a channel the user has
 * not touched, and Dak does not even do that outside [NotificationChannels.resetAll]).
 */
data class ChannelSpec(
    /** Base (flat) channel id; per-SIM copies are `<id>.sim<slot+1>`. */
    val id: String,
    val importance: Int,
    @StringRes val name: Int,
    @StringRes val description: Int,
    /** True for message categories, which get a per-SIM copy on multi-SIM devices. */
    val perSim: Boolean,
    val silent: Boolean = false,
    /** Blocked channels in [NotificationChannels.critical] raise a warning (OTPs and people must reach the user). */
    val critical: Boolean = false,
)

/** Every channel Dak owns, in the order the channel screen lists them. */
internal object ChannelCatalog {
    private const val HIGH = NotificationManagerCompat.IMPORTANCE_HIGH
    private const val DEFAULT = NotificationManagerCompat.IMPORTANCE_DEFAULT
    private const val LOW = NotificationManagerCompat.IMPORTANCE_LOW
    private const val NONE = NotificationManagerCompat.IMPORTANCE_NONE

    val messages: List<ChannelSpec> = listOf(
        ChannelSpec(NotificationChannels.PERSONAL, HIGH, R.string.channel_personal, R.string.channel_personal_desc, perSim = true, critical = true),
        // High so the code pops up, but no DND bypass: the user decides that in system settings.
        ChannelSpec(NotificationChannels.OTP, HIGH, R.string.channel_otp, R.string.channel_otp_desc, perSim = true, critical = true),
        // Bank, card and payment alerts and fake-credit warnings: time-critical, so they pop up like OTPs.
        ChannelSpec(NotificationChannels.ALERTS, HIGH, R.string.channel_alerts, R.string.channel_alerts_desc, perSim = true, critical = true),
        ChannelSpec(NotificationChannels.PROMOTIONS, LOW, R.string.channel_promotions, R.string.channel_promotions_desc, perSim = true, silent = true),
        ChannelSpec(NotificationChannels.OTHER, DEFAULT, R.string.channel_other, R.string.channel_other_desc, perSim = true),
        // Created blocked: spam is visible in-app only unless the user turns the channel on.
        ChannelSpec(NotificationChannels.SPAM, NONE, R.string.channel_spam, R.string.ch_spam_desc, perSim = true, silent = true),
        ChannelSpec(NotificationChannels.OTP_CONSUMED, LOW, R.string.channel_otp_consumed, R.string.channel_otp_consumed_desc, perSim = true, silent = true),
    )

    val app: List<ChannelSpec> = listOf(
        ChannelSpec(NotificationChannels.FAILURES, HIGH, R.string.channel_failures, R.string.channel_failures_desc, perSim = false),
        ChannelSpec(NotificationChannels.AUTOMATION, DEFAULT, R.string.ch_automation, R.string.ch_automation_desc, perSim = false),
        ChannelSpec(NotificationChannels.MMS, LOW, R.string.channel_mms, R.string.channel_mms_desc, perSim = false, silent = true),
        ChannelSpec(NotificationChannels.SELF_TEST, HIGH, R.string.channel_self_test, R.string.channel_self_test_desc, perSim = false),
        ChannelSpec(NotificationChannels.RELIABILITY, DEFAULT, R.string.channel_reliability, R.string.channel_reliability_desc, perSim = false),
    )

    val all: List<ChannelSpec> = messages + app

    private val byId: Map<String, ChannelSpec> = all.associateBy { it.id }

    /** The spec of a base id or a per-SIM id (`otp.sim2` → `otp`), else null (conversation or foreign channel). */
    fun specOf(channelId: String): ChannelSpec? = byId[channelId] ?: byId[SimChannelIds.baseOf(channelId)]

    /**
     * Base ids retired in favour of a new id, with the defaults Dak created them with. Android never lets an app
     * raise a channel's importance, so a default that goes up needs a new id. [NotificationChannels] migrates each
     * old channel (flat and per-SIM) once: a copy the user changed seeds the new channel with its settings, an
     * untouched one gets the new defaults, then the old channel is deleted.
     */
    val renamed: Map<String, Renamed> = listOf(
        // Schema 3: "Transactions" (default importance) became "Alerts" (high).
        Renamed(
            to = NotificationChannels.ALERTS,
            defaults = ChannelSpec(
                LEGACY_TRANSACTIONS, DEFAULT, R.string.channel_alerts, R.string.channel_alerts_desc, perSim = true, critical = true,
            ),
        ),
    ).associateBy { it.defaults.id }

    /** The id an old flat or per-SIM channel moves to (`transactions.sim2` → `alerts.sim2`), else null. */
    fun renamedTarget(channelId: String): String? {
        renamed[channelId]?.let { return it.to }
        val base = SimChannelIds.baseOf(channelId) ?: return null
        val slot = SimChannelIds.slotOf(channelId) ?: return null
        return renamed[base]?.let { SimChannelIds.channelId(it.to, slot) }
    }

    /**
     * Base ids used by earlier schema versions and no longer created. [NotificationChannels] deletes them (and their
     * per-SIM copies) once per schema bump; ids in [all] are never listed here (their user settings must survive).
     */
    val obsolete: Set<String> = renamed.keys

    private const val LEGACY_TRANSACTIONS = "transactions"
}

/** A retired channel id: channels created from [defaults] move to base id [to]. */
internal data class Renamed(val to: String, val defaults: ChannelSpec)

/** Id scheme of per-SIM channels and groups (keyed by 0-based slot so ids survive carrier changes). */
object SimChannelIds {
    private const val SIM_SUFFIX = ".sim"
    private const val GROUP_PREFIX = "sim"

    fun channelId(baseId: String, slotIndex: Int): String = baseId + SIM_SUFFIX + (slotIndex + 1)

    fun groupId(slotIndex: Int): String = GROUP_PREFIX + (slotIndex + 1)

    /** Base id of a per-SIM channel id, or null when [channelId] is not per-SIM. */
    fun baseOf(channelId: String): String? {
        val at = channelId.lastIndexOf(SIM_SUFFIX)
        if (at <= 0) return null
        val slot = channelId.substring(at + SIM_SUFFIX.length)
        return if (slot.isNotEmpty() && slot.all { it.isDigit() }) channelId.substring(0, at) else null
    }

    /** 0-based slot of a per-SIM channel id, or null. */
    fun slotOf(channelId: String): Int? {
        if (baseOf(channelId) == null) return null
        return channelId.substring(channelId.lastIndexOf(SIM_SUFFIX) + SIM_SUFFIX.length).toIntOrNull()?.minus(1)
    }
}
