package app.dak.ui.conversation

import android.content.Context
import android.net.Uri
import android.telephony.SmsMessage
import app.dak.safety.SendCostGuard
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.MessageSender
import app.dak.telephony.NumberNormalizer
import app.dak.telephony.OutgoingMms
import app.dak.telephony.OutgoingMmsPart
import app.dak.telephony.OutgoingSms
import app.dak.telephony.SendResult
import app.dak.telephony.SimRepository
import app.dak.telephony.carrier.CarrierConfigRepository
import app.dak.telephony.carrier.CarrierMessagingConfig
import app.dak.telephony.carrier.SendBlock
import app.dak.telephony.carrier.SendMode
import app.dak.telephony.carrier.SendModePolicy
import app.dak.telephony.carrier.SendPlan
import app.dak.telephony.cost.CostVerdict
import app.dak.telephony.cost.EmergencyNumberCheck
import app.dak.telephony.role.SmsRoleMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import app.dak.telephony.R as TelephonyR

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
enum class SendProblem { NO_RECIPIENT, NO_SIM, ATTACHMENT_TOO_LARGE, ATTACHMENT_UNREADABLE, PLATFORM, NOT_DEFAULT_APP, CARRIER_LIMIT }

sealed interface SendOutcome {
    data object Sent : SendOutcome
    data class Failed(val problem: SendProblem, val detail: String? = null) : SendOutcome
}

/**
 * The one send path behind every composer: picks SMS or MMS from the carrier's rules ([SendModePolicy] over the SIM's
 * [CarrierMessagingConfig]: media, group recipients when the carrier allows group MMS, or text past the carrier's
 * SMS→MMS threshold go as MMS; everything else as SMS, concatenated when long; a group without group MMS goes as
 * individual messages), normalises recipients to E.164 with the sending SIM's home country when enabled, and
 * compresses media to the carrier's size and image-dimension limits off the main thread. E-mail recipients go as MMS,
 * or through the carrier's e-mail gateway number for a plain text to one address. Cost confirmations (premium /
 * short codes / international / roaming) are asked by the composer via [costWarnings] before [send].
 *
 * While Dak is not the default SMS app ([isDefaultSmsApp]) only texts to emergency numbers are sent; the composer is
 * read-only otherwise.
 */
@Singleton
class MessageSendController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sender: MessageSender,
    private val normalizer: NumberNormalizer,
    private val settings: SettingsStore,
    private val compressor: MmsMediaCompressor,
    private val sims: SimRepository,
    private val costGuard: SendCostGuard,
    private val carrierConfig: CarrierConfigRepository,
    private val role: SmsRoleMonitor,
    private val emergency: EmergencyNumberCheck,
) {
    /** True while Dak holds the default SMS role. */
    val isDefaultSmsApp: StateFlow<Boolean> get() = role.isDefault

    /** Re-reads the role (after the system role dialog, on resume). */
    fun refreshRole() {
        role.refresh()
    }

    /**
     * While Dak is not the default SMS app: true when a text to [addresses] may still be sent, i.e. every recipient
     * is an emergency number (texts to emergency services are never held). Call off the main thread.
     */
    fun sendableWithoutRole(addresses: List<String>, subId: Int): Boolean {
        val sendSubId = sendSubIdFor(subId) ?: return false
        return runCatching { emergency.allEmergency(addresses.map { it.trim() }.filter { it.isNotEmpty() }, sendSubId) }.getOrDefault(false)
    }

    /** The carrier rules of the SIM a send from [subId] uses. */
    fun carrierConfig(subId: Int): CarrierMessagingConfig =
        sendSubIdFor(subId)?.let { carrierConfig.forSubscription(it) } ?: CarrierMessagingConfig.DEFAULTS

    /** How a message would go out (SMS, group MMS or one MMS each) and whether the carrier refuses it as composed. */
    fun plan(recipientCount: Int, text: String, attachments: List<ComposerAttachment>, subId: Int, emailRecipients: Int = 0): SendPlan =
        SendModePolicy.plan(
            recipientCount = recipientCount,
            segments = segments(text).segments,
            textLength = text.length,
            textBytes = text.toByteArray(Charsets.UTF_8).size,
            hasAttachments = attachments.isNotEmpty(),
            config = carrierConfig(subId),
            emailRecipients = emailRecipients,
        )

    /** [plan] for these [recipients]: e-mail addresses need MMS, or the carrier's e-mail gateway for a plain text. */
    fun plan(recipients: List<String>, text: String, attachments: List<ComposerAttachment>, subId: Int): SendPlan =
        plan(recipients.size, text, attachments, subId, recipients.count(SendModePolicy::isEmailAddress))

    /** True when this message will go as MMS (shown as the "MMS" chip on the send button). */
    fun isMms(recipientCount: Int, text: String, attachments: List<ComposerAttachment>, subId: Int = -1): Boolean =
        plan(recipientCount, text, attachments, subId).isMms

    fun segments(text: String): SegmentInfo {
        if (text.isEmpty()) return SegmentInfo(0, 0)
        val parts = runCatching { SmsMessage.calculateLength(text, false) }.getOrNull() ?: return SegmentInfo(1, 0)
        return SegmentInfo(segments = parts[0], remainingInSegment = parts[2])
    }

    /** The address as it will actually be sent from [subId] (for the "sent as +91…" hint). */
    fun normalized(address: String, subId: Int): String =
        if (settings.get(DakSettings.numberNormalization)) normalizer.normalize(address, subId) else address

    /**
     * Destinations of a send to [addresses] from [subId] that need a cost confirmation first (premium-rate or
     * unknown short codes, international numbers, roaming abroad, alphanumeric ids), loudest first. Empty when the
     * send can go straight out or "Warn before costly SMS" is off. Call off the main thread.
     */
    fun costWarnings(addresses: List<String>, subId: Int): List<CostVerdict> {
        val sendSubId = sendSubIdFor(subId) ?: return emptyList()
        val targets = addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { normalized(it, sendSubId) }
        return runCatching { costGuard.toConfirm(targets, sendSubId) }.getOrDefault(emptyList())
    }

    /** "Don't ask again" for [verdicts] (as returned by [costWarnings] for the same [subId]). */
    fun approveCost(verdicts: List<CostVerdict>, subId: Int) {
        val sendSubId = sendSubIdFor(subId) ?: return
        costGuard.approve(verdicts, sendSubId)
    }

    /** The SIM a send from [subId] actually uses: [subId], or the first active SIM when none is chosen. */
    private fun sendSubIdFor(subId: Int): Int? =
        if (subId >= 0) subId else sims.sims.value.firstOrNull { it.isActive }?.subId

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
        val sendSubId = sendSubIdFor(subId) ?: return SendOutcome.Failed(SendProblem.NO_SIM)
        val targets = recipients.map { normalized(it, sendSubId) }
        val deliveryReports = settings.get(DakSettings.deliveryReports)
        if (!role.isDefaultNow()) {
            // Only emergency texts go out without the role (the sender hands them straight to the platform).
            if (attachments.isNotEmpty() || !sendableWithoutRole(recipients, sendSubId)) {
                return SendOutcome.Failed(SendProblem.NOT_DEFAULT_APP, context.getString(TelephonyR.string.dak_telephony_not_default_title))
            }
            return outcomeOf(sender.sendSms(OutgoingSms(targets, text, sendSubId, threadId, requestDeliveryReport = deliveryReports)))
        }
        val config = carrierConfig.forSubscription(sendSubId)
        val plan = plan(targets, text, attachments, sendSubId)
        plan.block?.let { return SendOutcome.Failed(SendProblem.CARRIER_LIMIT, blockText(it, config)) }
        plan.emailGateway?.let { gateway ->
            // One e-mail recipient, plain text, carrier e-mail gateway: SMS "<address> <text>" to the gateway, filed in
            // the e-mail conversation.
            val body = SendModePolicy.emailGatewayBody(targets.single(), text)
            return outcomeOf(sender.sendSms(OutgoingSms(listOf(gateway), body, sendSubId, threadId, requestDeliveryReport = deliveryReports)))
        }
        return when (plan.mode) {
            SendMode.SMS ->
                outcomeOf(sender.sendSms(OutgoingSms(targets, text, sendSubId, threadId, requestDeliveryReport = deliveryReports)))
            SendMode.MMS -> {
                val parts = buildParts(text, attachments, config) ?: return SendOutcome.Failed(SendProblem.ATTACHMENT_TOO_LARGE)
                outcomeOf(sender.sendMms(OutgoingMms(targets, text.ifEmpty { null }, sendSubId, parts, threadId = threadId, requestDeliveryReport = deliveryReports)))
            }
            SendMode.MMS_PER_RECIPIENT -> {
                // Carrier without group MMS: one MMS per recipient, each filed in its own 1:1 thread.
                val parts = buildParts(text, attachments, config) ?: return SendOutcome.Failed(SendProblem.ATTACHMENT_TOO_LARGE)
                val failures = targets.mapNotNull { to ->
                    val result = sender.sendMms(OutgoingMms(listOf(to), text.ifEmpty { null }, sendSubId, parts, threadId = null, requestDeliveryReport = deliveryReports))
                    (result as? SendResult.Failed)?.reason
                }
                if (failures.isEmpty()) SendOutcome.Sent else SendOutcome.Failed(SendProblem.PLATFORM, failures.first())
            }
        }
    }

    private fun outcomeOf(result: SendResult): SendOutcome = when (result) {
        is SendResult.Queued -> SendOutcome.Sent
        is SendResult.Failed -> SendOutcome.Failed(SendProblem.PLATFORM, result.reason)
    }

    private fun blockText(block: SendBlock, config: CarrierMessagingConfig): String = when (block) {
        SendBlock.MMS_DISABLED -> context.getString(TelephonyR.string.dak_telephony_mms_disabled)
        SendBlock.TOO_MANY_RECIPIENTS -> context.getString(TelephonyR.string.dak_telephony_too_many_recipients, config.recipientLimit ?: 0)
        SendBlock.TEXT_TOO_LONG -> context.getString(TelephonyR.string.dak_telephony_mms_text_too_long)
    }

    private suspend fun buildParts(text: String, attachments: List<ComposerAttachment>, config: CarrierMessagingConfig): List<OutgoingMmsPart>? {
        if (attachments.isEmpty()) return emptyList()
        // The SIM's carrier MMS config (CarrierConfigManager): maxMessageSize for the byte budget, and
        // maxImageWidth / maxImageHeight for photos.
        val limits = MmsPartBudget.of(config, text.toByteArray(Charsets.UTF_8).size, attachments.size)
        val budget = limits.perPartBytes
        val box = limits.imageBox
        return attachments.mapIndexed { index, a ->
            val prepared = when {
                a.bytes != null && a.isImage -> {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size)
                    bitmap?.let { compressor.prepare(it, budget, box) }?.let { MmsMediaCompressor.Prepared("image/jpeg", it) }
                }
                a.bytes != null -> if (a.bytes.size <= budget) MmsMediaCompressor.Prepared(a.mimeType, a.bytes) else null
                a.uri != null -> compressor.prepare(a.uri, a.mimeType, budget, box)
                else -> null
            } ?: return null
            OutgoingMmsPart(prepared.mimeType, fileNameFor(index, a, prepared.mimeType), prepared.bytes)
        }
    }

    private fun fileNameFor(index: Int, a: ComposerAttachment, mime: String): String = MmsPartNames.fileName(index, a.name, mime)
}

/**
 * File names for outgoing MMS parts (Content-Location / name). The extension follows the part's **final** content
 * type, after compression or transcoding (a voice note recorded as `.amr` but transcoded to AAC in MP4 goes out as
 * `.m4a`): receiving phones pick a player by extension as often as by MIME type. The original extension is used only
 * for types not listed here. Pure, for tests.
 */
internal object MmsPartNames {
    fun fileName(index: Int, originalName: String?, mimeType: String): String {
        val base = originalName?.substringBeforeLast('.')?.filter { it.isLetterOrDigit() || it == '_' || it == '-' }?.take(40)
            ?.ifBlank { null } ?: "part${index + 1}"
        val ext = extensionFor(mimeType)
            ?: originalName?.takeIf { '.' in it }?.substringAfterLast('.')?.filter { it.isLetterOrDigit() }?.take(8)?.lowercase()?.ifBlank { null }
            ?: "bin"
        return "$base.$ext"
    }

    /** Extension for a content type (parameters such as `; codecs=` ignored), or null when not a known MMS type. */
    fun extensionFor(mimeType: String): String? = when (mimeType.substringBefore(';').trim().lowercase()) {
        "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        "image/bmp", "image/x-ms-bmp" -> "bmp"
        "image/vnd.wap.wbmp" -> "wbmp"
        "video/mp4" -> "mp4"
        "video/3gpp" -> "3gp"
        "video/3gpp2" -> "3g2"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/mp4a-latm" -> "m4a"
        "audio/aac", "audio/aacp" -> "aac"
        "audio/amr" -> "amr"
        "audio/amr-wb" -> "awb"
        "audio/3gpp" -> "3gp"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/midi", "audio/mid", "audio/sp-midi" -> "mid"
        "text/x-vcard", "text/vcard" -> "vcf"
        "text/x-vcalendar" -> "vcs"
        "text/calendar" -> "ics"
        "text/plain" -> "txt"
        "application/pdf" -> "pdf"
        else -> null
    }
}
