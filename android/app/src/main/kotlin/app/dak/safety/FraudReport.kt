package app.dak.safety

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure builders for the texts a fraud/spam report needs: the TRAI 1909 complaint SMS and the details block the
 * user pastes into Chakshu / cybercrime.gov.in.
 *
 * TRAI format (TCCCPR 2018 consumer guidance, to be re-checked before release — see the helplines bundle's
 * `verificationNote`): `<message text>,<sender number or header>,<date dd/mm/yy>`. The complaint must reach the
 * operator within the reporting window counted from the message date.
 */
object FraudReport {
    /** Default body template of the 1909 complaint, used when the helplines bundle does not provide one. */
    const val DEFAULT_TRAI_FORMAT = "{text},{sender},{date:dd/MM/yy}"

    /** Longest message excerpt put in a complaint (keeps the complaint to a few SMS parts). */
    const val MAX_TEXT_CHARS = 400

    /**
     * The 1909 complaint body for a message from [sender] received at [dateMillis], rendered with [format]
     * (placeholders `{text}`, `{sender}`, `{date:<SimpleDateFormat pattern>}`). Line breaks are flattened and the
     * text is cut to [MAX_TEXT_CHARS]. Indian numbers are reduced to their 10 national digits, headers are kept.
     */
    fun traiComplaintBody(
        text: String,
        sender: String,
        dateMillis: Long,
        format: String? = null,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val flat = text.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT_CHARS)
        val template = format?.takeIf { it.contains("{text}") } ?: DEFAULT_TRAI_FORMAT
        val withDate = DATE_PLACEHOLDER.replace(template) { match ->
            val pattern = match.groupValues[1]
            runCatching { SimpleDateFormat(pattern, Locale.US).apply { this.timeZone = timeZone }.format(Date(dateMillis)) }
                .getOrDefault("")
        }
        return withDate.replace("{sender}", complaintSender(sender)).replace("{text}", flat)
    }

    /** Sender as TRAI expects it: a 10-digit number for Indian mobiles (`+91`/`0` prefixes removed), else as is. */
    fun complaintSender(sender: String): String {
        val trimmed = sender.trim()
        if (trimmed.any { it.isLetter() }) return trimmed
        val digits = trimmed.filter { it.isDigit() }
        return when {
            digits.length == 12 && digits.startsWith("91") -> digits.substring(2)
            digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
            else -> digits.ifEmpty { trimmed }
        }
    }

    /**
     * A plain-text summary to paste into a reporting portal: sender, received date/time, SIM and the full text.
     * [labels] supplies the translated field names.
     */
    fun detailsText(
        text: String,
        sender: String,
        dateMillis: Long,
        simLabel: String?,
        labels: DetailLabels,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { this.timeZone = timeZone }.format(Date(dateMillis))
        return buildString {
            append(labels.sender).append(": ").append(sender.trim()).append('\n')
            append(labels.received).append(": ").append(date).append('\n')
            if (!simLabel.isNullOrBlank()) append(labels.sim).append(": ").append(simLabel).append('\n')
            append(labels.message).append(":\n").append(text.trim())
        }
    }

    /** Field names for [detailsText]. */
    data class DetailLabels(val sender: String, val received: String, val sim: String, val message: String)

    private val DATE_PLACEHOLDER = Regex("\\{date:([^}]+)\\}")
}
