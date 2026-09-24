package app.dak.telephony.sms

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import app.dak.telephony.OutgoingSms
import app.dak.telephony.SendResult
import app.dak.telephony.di.TelephonyEntryPoints
import app.dak.telephony.internal.SubscriptionExtras
import app.dak.telephony.internal.TAG
import kotlinx.coroutines.launch

/**
 * Quick replies from the in-call "Respond via message" screen (`TelephonyManager.ACTION_RESPOND_VIA_MESSAGE`),
 * required of a default SMS app. Guarded by SEND_RESPOND_VIA_MESSAGE; sends on the subscription the call used.
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            ?: RespondViaMessage.body(intent.data?.encodedSchemeSpecificPart)
        val recipients = RespondViaMessage.recipients(intent.data?.encodedSchemeSpecificPart)
        if (text.isNullOrBlank() || recipients.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val subId = SubscriptionExtras.subIdFrom(this, intent)
        val entry = TelephonyEntryPoints.get(this)
        entry.telephonyScope().launch {
            try {
                val result = entry.messageSender().sendSms(OutgoingSms(addresses = recipients, body = text, subId = subId))
                if (result is SendResult.Failed) Log.w(TAG, "respond-via-message failed: ${result.reason}")
            } finally {
                Handler(Looper.getMainLooper()).post { stopSelf(startId) }
            }
        }
        return START_NOT_STICKY
    }
}
