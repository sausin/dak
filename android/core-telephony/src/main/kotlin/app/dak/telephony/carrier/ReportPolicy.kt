package app.dak.telephony.carrier

/**
 * Which delivery / read reports Dak asks for, sends and allows, from the user's settings AND the carrier's
 * `enable*Reports` config (as AOSP does: a report the carrier's SMSC / MMSC does not support is never requested, and
 * the UI says the setting has no effect there). Pure, JVM-tested.
 *
 * - SMS delivery reports: user "Delivery reports" AND `enableSMSDeliveryReports` (AOSP default on).
 * - MMS delivery reports (X-Mms-Delivery-Report on m-send-req): the same user setting AND `enableMMSDeliveryReports`
 *   (AOSP default off, since many MMSCs ignore them).
 * - MMS read reports, both directions: user "Read receipts for MMS" (default **off**, for privacy) AND
 *   `enableMMSReadReports`. When on, outgoing MMS carry X-Mms-Read-Report = Yes and received MMS that ask for one
 *   get an m-read-rec-ind once read (see `MmsClientTransactions.readReceipt` for the per-message rules). The setting is
 *   reciprocal on purpose: Dak never asks others for read receipts it would not give.
 * - X-Mms-Report-Allowed on m-notifyresp-ind / m-acknowledge-ind: the user's "Let senders see MMS delivery" (default
 *   on, as AOSP). It does not depend on the carrier: it only tells the MMSC whether it may report delivery to the
 *   sender.
 */
object ReportPolicy {
    fun requestSmsDeliveryReport(userWantsDeliveryReports: Boolean, config: CarrierMessagingConfig): Boolean =
        userWantsDeliveryReports && config.smsDeliveryReportsEnabled

    fun requestMmsDeliveryReport(userWantsDeliveryReports: Boolean, config: CarrierMessagingConfig): Boolean =
        userWantsDeliveryReports && config.mmsDeliveryReportsEnabled

    /** X-Mms-Read-Report = Yes on our m-send-req. */
    fun requestMmsReadReport(userReadReceipts: Boolean, config: CarrierMessagingConfig): Boolean =
        userReadReceipts && config.mmsReadReportsEnabled

    /** Whether m-read-rec-ind may be sent at all on this SIM (the message itself is checked separately). */
    fun sendMmsReadReport(userReadReceipts: Boolean, config: CarrierMessagingConfig): Boolean =
        userReadReceipts && config.mmsReadReportsEnabled

    /** X-Mms-Report-Allowed value for our m-notifyresp-ind / m-acknowledge-ind. */
    fun reportAllowed(userAllowsDeliveryReportsToSenders: Boolean): Boolean = userAllowsDeliveryReportsToSenders
}
