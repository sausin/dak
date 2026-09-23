package app.dak.telephony.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.dak.telephony.internal.runAsync

/**
 * `android.provider.Telephony.SMS_DELIVER` receiver (default SMS app only; guarded by BROADCAST_SMS in the
 * manifest). Work runs under `goAsync()` with a budget well under the 10 s broadcast limit.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        runAsync(context) { entry -> entry.incomingSmsProcessor().process(intent) }
    }
}
