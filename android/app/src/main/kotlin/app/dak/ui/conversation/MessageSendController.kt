package app.dak.ui.conversation

import android.net.Uri
import android.telephony.SmsMessage
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.MessageSender
import app.dak.telephony.NumberNormalizer
import app.dak.telephony.OutgoingMms
import app.dak.telephony.OutgoingMmsPart
import app.dak.telephony.OutgoingSms
import app.dak.telephony.SendResult
import app.dak.telephony.SimRepository
import javax.inject.Inject
import javax.inject.Singleton

/** One item in the composer's attachment tray. Either [uri] (read lazily) or [bytes] (already in memory). */
data class ComposerAttachment(
    val id: String,
    val mimeType: String,
    val name: String?,
    val uri: Uri? = null,
    val bytes: ByteArray? = null,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    override fun equals(other: Any?): Boolean = other is ComposerAttachment && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/** SMS segment arithmetic for the counter. */
data class SegmentInfo(val segments: Int, val remainingInSegment: Int)

/** Why a send did not go out. */
enum class SendProblem { NO_RECIPIENT, NO_SIM, ATTACHMENT_TOO_LARGE, ATTACHMENT_UNREADABLE, PLATFORM }

sealed interface SendOutcome {
    data object Sent : SendOutcome
    data class Failed(val problem: SendProblem, val detail: String? = null) : SendOutcome
}

/**
 * The one send path behind every composer: picks SMS or MMS automatically (media, group recipients or very long
 * text go as MMS; everything else as SMS, concatenated when long), normalises recipients to E.164 with the sending
 * SIM's home country when enabled, and compresses media to the carrier limit off the main thread.
 */
@Singleton
class MessageSendController @Inject constructor(
    private val sender: MessageSender,
    private val normalizer: NumberNormalizer,
    private val settings: SettingsStore,
    private val compressor: MmsMediaCompressor,
    private val sims: SimRepository,
) {
    /** True when this message will go as MMS (shown as the "MMS" chip on the send button). */
    fun isMms(recipientCount: Int, text: String, attachments: List<ComposerAttachment>): Boolean =
        attachments.isNotEmpty() || recipientCount > 1 || segments(text).segments > MMS_TEXT_SEGMENT_THRESHOLD

    fun segments(text: String): SegmentInfo {
        if (text.isEmpty()) return SegmentInfo(0, 0)
        val parts = runCatching { SmsMessage.calculateLength(text, false) }.getOrNull() ?: return SegmentInfo(1, 0)
        return SegmentInfo(segments = parts[0], remainingInSegment = parts[2])
    }

    /** The address as it will actually be sent from [subId] (for the "sent as +91…" hint). */
    fun normalized(address: String, subId: Int): String =
        if (settings.get(DakSettings.numberNormalization)) normalizer.normalize(address, subId) else address

    suspend fun send(
        addresses: List<String>,
        text: String,
        attachments: List<ComposerAttachment>,
        subId: Int,
        threadId: Long?,
    ): SendOutcome {
        val recipients = addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (recipients.isEmpty()) return SendOutcome.Failed(SendProblem.NO_RECIPIENT)
        // No system default SMS SIM (dual-SIM "ask every time"): send from the first active SIM instead of failing.
        val sendSubId = if (subId >= 0) subId else sims.sims.value.firstOrNull { it.isActive }?.subId
            ?: return SendOutcome.Failed(SendProblem.NO_SIM)
        val targets = recipients.map { normalized(it, sendSubId) }
        val result = if (!isMms(targets.size, text, attachments)) {
            sender.sendSms(OutgoingSms(targets, text, sendSubId, threadId, requestDeliveryReport = settings.get(DakSettings.deliveryReports)))
        } else {
            val parts = buildParts(text, attachments, sendSubId) ?: return SendOutcome.Failed(SendProblem.ATTACHMENT_TOO_LARGE)
            sender.sendMms(OutgoingMms(targets, text.ifEmpty { null }, sendSubId, parts, threadId = threadId))
        }
        return when (result) {
            is SendResult.Queued -> SendOutcome.Sent
            is SendResult.Failed -> SendOutcome.Failed(SendProblem.PLATFORM, result.reason)
        }
    }

    private suspend fun buildParts(text: String, attachments: List<ComposerAttachment>, subId: Int): List<OutgoingMmsPart>? {
        if (attachments.isEmpty()) return emptyList()
        val limit = (compressor.messageLimitBytes(subId) * HEADROOM).toInt() - text.toByteArray(Charsets.UTF_8).size
        val budget = (limit / attachments.size).coerceAtLeast(MIN_PART_BUDGET)
        return attachments.mapIndexed { index, a ->
            val prepared = when {
                a.bytes != null && a.isImage -> {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size)
                    bitmap?.let { compressor.prepare(it, budget) }?.let { MmsMediaCompressor.Prepared("image/jpeg", it) }
                }
                a.bytes != null -> if (a.bytes.size <= budget) MmsMediaCompressor.Prepared(a.mimeType, a.bytes) else null
                a.uri != null -> compressor.prepare(a.uri, a.mimeType, budget)
                else -> null
            } ?: return null
            OutgoingMmsPart(prepared.mimeType, fileNameFor(index, a, prepared.mimeType), prepared.bytes)
        }
    }

    private fun fileNameFor(index: Int, a: ComposerAttachment, mime: String): String {
        val base = a.name?.substringBeforeLast('.')?.filter { it.isLetterOrDigit() || it == '_' || it == '-' }?.take(40)
            ?.ifBlank { null } ?: "part${index + 1}"
        val ext = when {
            mime == "image/jpeg" -> "jpg"
            mime == "image/png" -> "png"
            mime == "image/gif" -> "gif"
            mime.startsWith("video/") -> "mp4"
            mime.contains("vcard") -> "vcf"
            else -> a.name?.substringAfterLast('.', "")?.ifBlank { null } ?: "bin"
        }
        return "$base.$ext"
    }

    private companion object {
        /** Beyond this many segments carriers usually convert anyway; sending MMS keeps the text in one piece. */
        const val MMS_TEXT_SEGMENT_THRESHOLD = 10
        const val HEADROOM = 0.9
        const val MIN_PART_BUDGET = 16 * 1024
    }
}
