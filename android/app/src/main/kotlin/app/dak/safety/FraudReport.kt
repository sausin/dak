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
     * [labels] supplies the translated field names. The date is `dd/MM/yyyy HH:mm` (as Indian portals expect) unless a
     * [locale] is given, in which case it is that locale's short date-time format (e.g. `9/21/26, 10:15 AM` in the US).
     */
    fun detailsText(
        text: String,
        sender: String,
        dateMillis: Long,
        simLabel: String?,
        labels: DetailLabels,
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale? = null,
    ): String {
        val format = if (locale == null) {
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        } else {
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, locale)
        }
        val date = format.apply { this.timeZone = timeZone }.format(Date(dateMillis))
        return buildString {
            append(labels.line(labels.sender, sender.trim())).append('\n')
            append(labels.line(labels.received, date)).append('\n')
            if (!simLabel.isNullOrBlank()) append(labels.line(labels.sim, simLabel)).append('\n')
            append(labels.block(labels.message, text.trim()))
        }
    }

    /**
     * Field names for [detailsText], and how a field is joined to its value in the app language: [lineFormat] for a
     * one-line field (`%1$s` label, `%2$s` value; "Sender: X", French "Expéditeur : X"), [blockFormat] for the message
     * text on the lines below its label.
     */
    data class DetailLabels(
        val sender: String,
        val received: String,
        val sim: String,
        val message: String,
        val lineFormat: String = FraudReport.DEFAULT_LINE,
        val blockFormat: String = FraudReport.DEFAULT_BLOCK,
    ) {
        internal fun line(label: String, value: String): String = fill(lineFormat, FraudReport.DEFAULT_LINE, label, value)

        internal fun block(label: String, value: String): String = fill(blockFormat, FraudReport.DEFAULT_BLOCK, label, value)

        /** [format] with the label and value; the English default when a translation lost a placeholder. */
        private fun fill(format: String, fallback: String, label: String, value: String): String {
            val usable = format.takeIf { "%1\$s" in it && "%2\$s" in it } ?: fallback
            // Replaced literally (not String.format): the message text may itself contain '%'.
            return usable.replace("%1\$s", label).replace("%2\$s", value)
        }
    }

    /** "Label: value" in English, the format [DetailLabels.lineFormat] translates. */
    const val DEFAULT_LINE = "%1\$s: %2\$s"

    /** "Label:" then the value on the next line. */
    const val DEFAULT_BLOCK = "%1\$s:\n%2\$s"

    private val DATE_PLACEHOLDER = Regex("\\{date:([^}]+)\\}")
}
