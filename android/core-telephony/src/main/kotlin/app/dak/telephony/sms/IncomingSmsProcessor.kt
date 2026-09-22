package app.dak.telephony.sms

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.telephony.IncomingDispatcher
import app.dak.telephony.UNPERSISTED_PROVIDER_ID
import app.dak.telephony.internal.SubscriptionExtras
import app.dak.telephony.internal.TAG
import app.dak.telephony.provider.IncomingSms
import app.dak.telephony.provider.TelephonyProviderReader
import app.dak.telephony.provider.TelephonyProviderWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles one SMS_DELIVER broadcast: assembles the (possibly multipart) message, writes it verbatim to the inbox
 * immediately, then dispatches the stored message to the [IncomingDispatcher].
 *
 * Blocked senders are not filtered here: since API 24 the platform drops blocked numbers before delivering to
 * the default SMS app.
 */
@Singleton
class IncomingSmsProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val writer: TelephonyProviderWriter,
    private val reader: TelephonyProviderReader,
    private val dispatcher: IncomingDispatcher,
) {
    suspend fun process(intent: Intent) {
        val parts: List<SmsMessage> = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "unreadable SMS_DELIVER intent: ${e.javaClass.simpleName}")
            emptyList()
        }
        if (parts.isEmpty()) return
        // Message-waiting indications flagged "do not store" carry no user content (voicemail lamp control).
        if (parts.all { it.isMwiDontStore }) return

        val first = parts[0]
        val address = first.displayOriginatingAddress ?: first.originatingAddress ?: ""
        val body = parts.joinToString(separator = "") { it.displayMessageBody ?: it.messageBody ?: "" }
        val subId = SubscriptionExtras.subIdFrom(context, intent)
        val now = System.currentTimeMillis()

        val key = writer.insertIncoming(
            IncomingSms(
                address = address,
                body = body,
                dateSentMillis = first.timestampMillis,
                dateMillis = now,
                subId = subId,
                protocol = first.protocolIdentifier,
                serviceCenter = first.serviceCenterAddress,
                replyPathPresent = first.isReplyPathPresent,
                subject = first.pseudoSubject?.takeIf { it.isNotEmpty() },
            ),
        )
        if (key == null) Log.e(TAG, "incoming SMS could not be written to the provider")

        val message = key?.let { reader.message(it) } ?: Message(
            providerId = key?.providerId ?: UNPERSISTED_PROVIDER_ID,
            kind = MessageKind.SMS,
            threadId = -1L,
            address = address,
            body = body,
            dateMillis = now,
            subId = subId,
            box = MessageBox.INBOX,
        )
        dispatcher.dispatch(message)
    }
}
