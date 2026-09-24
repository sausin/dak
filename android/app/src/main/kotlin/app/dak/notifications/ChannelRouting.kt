package app.dak.notifications

import app.dak.classify.InvestmentLabels
import app.dak.core.model.Category

/**
 * Which base channel an incoming message notifies on, from the classifier's output ([NotificationClassifier]).
 * Pure, so it is unit-tested; [NotificationChannels.channelFor] then picks the per-SIM copy, and a conversation's
 * custom channel ([ConversationChannels]) overrides the result for personal and OTP notifications.
 *
 * | Classifier output                                  | Channel        | Default importance        |
 * |----------------------------------------------------|----------------|---------------------------|
 * | Personal                                           | Messages       | High (conversation)       |
 * | OTP                                                | OTP codes      | High                      |
 * | OTP an app already read (quiet path)               | OTPs used      | Low, silent               |
 * | Transaction, likely fake credit                    | Alerts         | High                      |
 * | Investment security alert (`investment-alert`)     | Alerts         | High                      |
 * | Routine investment update (`investment-update`)    | General        | Default                   |
 * | Transaction                                        | Alerts         | High                      |
 * | Promotion                                          | Promotions     | Low, silent               |
 * | Spam                                               | Spam           | Off                       |
 * | Unknown                                            | General        | Default                   |
 *
 * Investment labels come from the template bundle ([InvestmentLabels]). A demat security alert (shares debited,
 * pledge created / invoked, e-DIS) moves no money but needs the user's attention now, so it is loud whatever else
 * the message is. A fund's allotment, redemption, IDCW, NAV, valuation or CAS and a broker's contract note are
 * routine: the money itself was already alerted loudly by the bank's own debit / credit SMS, so they notify on
 * General (default importance, no heads-up). OTPs and spam keep their own channels.
 */
object ChannelRouting {

    /**
     * @param likelyScam the fake-credit check flagged the message: it is shown as a fraud warning on Alerts, never
     *   as the transaction or conversation it pretends to be.
     * @param otpConsumed an app already read this OTP and the user chose quiet handling for such codes.
     * @param labels the classification's labels (see [InvestmentLabels]).
     */
    fun baseChannel(
        category: Category,
        likelyScam: Boolean = false,
        otpConsumed: Boolean = false,
        labels: Set<String> = emptySet(),
    ): String = when {
        likelyScam -> NotificationChannels.ALERTS
        category == Category.OTP && otpConsumed -> NotificationChannels.OTP_CONSUMED
        category == Category.OTP || category == Category.SPAM -> categoryChannel(category)
        InvestmentLabels.ALERT in labels -> NotificationChannels.ALERTS
        category == Category.TRANSACTION && InvestmentLabels.UPDATE in labels -> NotificationChannels.OTHER
        else -> categoryChannel(category)
    }

    /** Whether [labels] mark an investment security alert, which notifies as an alert whatever its category. */
    fun isInvestmentAlert(category: Category, labels: Set<String>): Boolean =
        InvestmentLabels.ALERT in labels && category != Category.OTP && category != Category.SPAM

    private fun categoryChannel(category: Category): String = when (category) {
        Category.PERSONAL -> NotificationChannels.PERSONAL
        Category.OTP -> NotificationChannels.OTP
        Category.TRANSACTION -> NotificationChannels.ALERTS
        Category.PROMOTION -> NotificationChannels.PROMOTIONS
        Category.SPAM -> NotificationChannels.SPAM
        Category.UNKNOWN -> NotificationChannels.OTHER
    }
}
