package app.dak.telephony.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dak.telephony.internal.runAsync

/** Internal (non-exported) receiver for our SMS sent / delivered PendingIntents. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = resultCode
        when (intent.action) {
            ACTION_SENT -> runAsync(context) { it.smsStatusProcessor().onSent(intent, code) }
            ACTION_DELIVERED -> runAsync(context) { it.smsStatusProcessor().onDelivered(intent) }
        }
    }

    companion object {
        const val ACTION_SENT = "app.dak.telephony.action.SMS_SENT"
        const val ACTION_DELIVERED = "app.dak.telephony.action.SMS_DELIVERED"
        internal const val EXTRA_MESSAGE_ID = "app.dak.telephony.extra.MESSAGE_ID"
        internal const val EXTRA_PART = "app.dak.telephony.extra.PART"
        internal const val EXTRA_PART_COUNT = "app.dak.telephony.extra.PART_COUNT"
        internal const val EXTRA_ATTEMPT = "app.dak.telephony.extra.ATTEMPT"
        internal const val EXTRA_DELIVERY_REQUESTED = "app.dak.telephony.extra.DELIVERY_REQUESTED"
    }
}
