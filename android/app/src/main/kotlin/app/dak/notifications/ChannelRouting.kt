package app.dak.notifications

import app.dak.core.model.Category

/**
 * Which base channel an incoming message notifies on, from the classifier's output ([NotificationClassifier]).
 * Pure, so it is unit-tested; [NotificationChannels.channelFor] then picks the per-SIM copy, and a conversation's
 * custom channel ([ConversationChannels]) overrides the result for personal and OTP notifications.
 *
 * | Classifier output                    | Channel        | Default importance        |
 * |--------------------------------------|----------------|---------------------------|
 * | Personal                             | Messages       | High (conversation)       |
 * | OTP                                  | OTP codes      | High                      |
 * | OTP an app already read (quiet path) | OTPs used      | Low, silent               |
 * | Transaction, likely fake credit      | Alerts         | High                      |
 * | Promotion                            | Promotions     | Low, silent               |
 * | Spam                                 | Spam           | Off                       |
 * | Unknown                              | General        | Default                   |
 */
object ChannelRouting {

    /**
     * @param likelyScam the fake-credit check flagged the message: it is shown as a fraud warning on Alerts, never
     *   as the transaction or conversation it pretends to be.
     * @param otpConsumed an app already read this OTP and the user chose quiet handling for such codes.
     */
    fun baseChannel(category: Category, likelyScam: Boolean = false, otpConsumed: Boolean = false): String = when {
        likelyScam -> NotificationChannels.ALERTS
        category == Category.OTP && otpConsumed -> NotificationChannels.OTP_CONSUMED
        else -> when (category) {
            Category.PERSONAL -> NotificationChannels.PERSONAL
            Category.OTP -> NotificationChannels.OTP
            Category.TRANSACTION -> NotificationChannels.ALERTS
            Category.PROMOTION -> NotificationChannels.PROMOTIONS
            Category.SPAM -> NotificationChannels.SPAM
            Category.UNKNOWN -> NotificationChannels.OTHER
        }
    }
}
