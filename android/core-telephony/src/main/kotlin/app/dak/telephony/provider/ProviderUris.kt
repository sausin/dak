package app.dak.telephony.provider

import android.content.ContentUris
import android.net.Uri
import android.provider.Telephony

/** Telephony provider URIs used by this module. */
internal object ProviderUris {
    val SMS: Uri = Telephony.Sms.CONTENT_URI
    val SMS_INBOX: Uri = Telephony.Sms.Inbox.CONTENT_URI
    val SMS_OUTBOX: Uri = Telephony.Sms.Outbox.CONTENT_URI
    val MMS: Uri = Telephony.Mms.CONTENT_URI
    val MMS_INBOX: Uri = Telephony.Mms.Inbox.CONTENT_URI
    val MMS_OUTBOX: Uri = Telephony.Mms.Outbox.CONTENT_URI
    val MMS_SENT: Uri = Telephony.Mms.Sent.CONTENT_URI
    val MMS_PART: Uri = Uri.parse("content://mms/part")

    /** Root of the combined provider; observing it (with descendants) catches SMS, MMS and thread changes. */
    val MMS_SMS: Uri = Uri.parse("content://mms-sms/")
    val THREADS_SIMPLE: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
    val CANONICAL_ADDRESSES: Uri = Uri.parse("content://mms-sms/canonical-addresses")

    fun sms(id: Long): Uri = ContentUris.withAppendedId(SMS, id)
    fun mms(id: Long): Uri = ContentUris.withAppendedId(MMS, id)
    fun mmsParts(mmsId: Long): Uri = Uri.parse("content://mms/$mmsId/part")
    fun mmsAddresses(mmsId: Long): Uri = Uri.parse("content://mms/$mmsId/addr")
    fun mmsPart(partId: Long): Uri = Uri.parse("content://mms/part/$partId")
}
