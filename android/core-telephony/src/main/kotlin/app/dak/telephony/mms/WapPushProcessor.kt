package app.dak.telephony.mms

import android.content.Context
import android.content.Intent
import android.util.Log
import app.dak.mms.pdu.DeliveryInd
import app.dak.mms.pdu.MmsPduDecoder
import app.dak.mms.pdu.NotificationInd
import app.dak.mms.pdu.PduDecodeResult
import app.dak.mms.pdu.ReadOrigInd
import app.dak.mms.pdu.ReadStatus
import app.dak.telephony.internal.SubscriptionExtras
import app.dak.telephony.internal.TAG
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles one WAP_PUSH_DELIVER broadcast. The platform strips the WSP push headers and passes the MMS PDU in the
 * `data` extra: notifications start a download, delivery and read reports update our sent message.
 */
@Singleton
class WapPushProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: MmsDownloadManager,
    private val persister: MmsPersister,
) {
    suspend fun process(intent: Intent) {
        val data = intent.getByteArrayExtra(EXTRA_DATA) ?: return
        val subId = SubscriptionExtras.subIdFrom(context, intent)
        when (val result = MmsPduDecoder.decode(data)) {
            is PduDecodeResult.Failure -> {
                Log.w(TAG, "unreadable WAP push: ${result.error.message}")
                // A damaged notification is answered "Unrecognised" (MMS-CTR) when it carries a transaction id.
                downloads.onUndecodable(data, subId)
            }
            is PduDecodeResult.Success -> when (val pdu = result.pdu) {
                is NotificationInd -> downloads.onNotification(pdu, subId)
                is DeliveryInd -> persister.applyDeliveryReport(pdu.messageId, pdu.status, pdu.to)
                is ReadOrigInd -> pdu.messageId?.let { persister.applyReadReport(it, pdu.readStatus ?: ReadStatus.READ) }
                else -> Log.i(TAG, "ignoring WAP push of type 0x%02X".format(pdu.messageType))
            }
        }
    }

    private companion object {
        const val EXTRA_DATA = "data"
    }
}
