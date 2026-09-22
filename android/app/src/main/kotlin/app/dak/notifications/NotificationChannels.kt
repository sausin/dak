package app.dak.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationManagerCompat
import app.dak.R
import app.dak.core.model.Category
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification channels, one per category plus operational ones. Channel ids are stable API: other modules
 * (e.g. :core-telephony for send failures) may post to [FAILURES] and [MMS] by id.
 */
@Singleton
class NotificationChannels @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        const val PERSONAL = "personal"
        const val OTP = "otp"
        const val TRANSACTIONS = "transactions"
        const val PROMOTIONS = "promotions"
        const val OTHER = "other"
        const val SPAM = "spam_silent"
        const val OTP_CONSUMED = "otp_consumed_silent"
        const val FAILURES = "failures"
        const val MMS = "mms_downloads"
        const val SELF_TEST = "self_test"
        const val RELIABILITY = "reliability"

        private const val GROUP_MESSAGES = "messages"
        private const val GROUP_APP = "app"

        /** Channels that must be enabled for the core promise (OTPs and people reach the user). */
        val critical: List<String> = listOf(PERSONAL, OTP, TRANSACTIONS)

        /** Channel for an incoming message of [category]. */
        fun forCategory(category: Category): String = when (category) {
            Category.PERSONAL -> PERSONAL
            Category.OTP -> OTP
            Category.TRANSACTION -> TRANSACTIONS
            Category.PROMOTION -> PROMOTIONS
            Category.SPAM -> SPAM
            Category.UNKNOWN -> OTHER
        }
    }

    /** Creates (or updates the names of) every channel. Idempotent; user-changed importance is preserved. */
    fun ensureCreated() {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannelGroupsCompat(
            listOf(
                NotificationChannelGroupCompat.Builder(GROUP_MESSAGES).setName(context.getString(R.string.channel_group_messages)).build(),
                NotificationChannelGroupCompat.Builder(GROUP_APP).setName(context.getString(R.string.channel_group_app)).build(),
            ),
        )
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audio = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .build()

        fun channel(id: String, importance: Int, name: Int, description: Int, group: String, silent: Boolean = false) =
            NotificationChannelCompat.Builder(id, importance)
                .setName(context.getString(name))
                .setDescription(context.getString(description))
                .setGroup(group)
                .apply {
                    if (silent) {
                        setSound(null, null)
                        setVibrationEnabled(false)
                    } else {
                        setSound(sound, audio)
                        setVibrationEnabled(true)
                    }
                    setShowBadge(!silent)
                }
                .build()

        val high = NotificationManagerCompat.IMPORTANCE_HIGH
        val default = NotificationManagerCompat.IMPORTANCE_DEFAULT
        val low = NotificationManagerCompat.IMPORTANCE_LOW
        val min = NotificationManagerCompat.IMPORTANCE_MIN
        manager.createNotificationChannelsCompat(
            listOf(
                channel(PERSONAL, high, R.string.channel_personal, R.string.channel_personal_desc, GROUP_MESSAGES),
                channel(OTP, high, R.string.channel_otp, R.string.channel_otp_desc, GROUP_MESSAGES),
                channel(TRANSACTIONS, default, R.string.channel_transactions, R.string.channel_transactions_desc, GROUP_MESSAGES),
                channel(PROMOTIONS, low, R.string.channel_promotions, R.string.channel_promotions_desc, GROUP_MESSAGES, silent = true),
                channel(OTHER, default, R.string.channel_other, R.string.channel_other_desc, GROUP_MESSAGES),
                channel(SPAM, min, R.string.channel_spam, R.string.channel_spam_desc, GROUP_MESSAGES, silent = true),
                channel(OTP_CONSUMED, low, R.string.channel_otp_consumed, R.string.channel_otp_consumed_desc, GROUP_MESSAGES, silent = true),
                channel(FAILURES, high, R.string.channel_failures, R.string.channel_failures_desc, GROUP_APP),
                channel(MMS, low, R.string.channel_mms, R.string.channel_mms_desc, GROUP_APP, silent = true),
                channel(SELF_TEST, high, R.string.channel_self_test, R.string.channel_self_test_desc, GROUP_APP),
                channel(RELIABILITY, default, R.string.channel_reliability, R.string.channel_reliability_desc, GROUP_APP),
            ),
        )
    }
}
