package app.dak.automations

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders the editable forward/webhook/relay templates ("Payment of {amount} received from {payer} at
 * {time}") against a [MessageEvent]. A template with no placeholders (or the default `"{body}"`) is
 * effectively "the raw message", per the relay-rules spec.
 *
 * Recognised placeholders: `{amount}`, `{payer}`, `{merchant}`, `{time}`, `{sender}`, `{body}`, `{otp}`,
 * `{sim}`. A placeholder with nothing to fill it renders as an empty string; anything else in the
 * template (including an unrecognised `{placeholder}`) passes through unchanged.
 */
public object TemplateRenderer {

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.US)

    public fun render(template: String, event: MessageEvent, zoneId: String = "UTC"): String {
        val values = placeholderValues(event, zoneId)
        val builder = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val open = template.indexOf('{', i)
            if (open < 0) {
                builder.append(template, i, template.length)
                break
            }
            val close = template.indexOf('}', open)
            if (close < 0) {
                builder.append(template, i, template.length)
                break
            }
            builder.append(template, i, open)
            val key = template.substring(open + 1, close)
            val value = values[key]
            if (value != null) builder.append(value) else builder.append('{').append(key).append('}')
            i = close + 1
        }
        return builder.toString()
    }

    private fun placeholderValues(event: MessageEvent, zoneId: String): Map<String, String> {
        val tx = event.transaction
        val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("UTC"))
        val time = TIME_FORMAT.withZone(zone).format(Instant.ofEpochMilli(event.dateMillis))
        val amount = tx?.let { formatAmount(it.amountMinor, it.currency) }.orEmpty()
        // The AST does not yet distinguish "who paid" from "who was paid"; both templates map to the
        // same extracted merchant/counterparty name until that split exists.
        val counterparty = tx?.merchant.orEmpty()
        val sim = event.slot?.let { "SIM ${it + 1}" } ?: "sub ${event.subId}"
        return mapOf(
            "amount" to amount,
            "payer" to counterparty,
            "merchant" to counterparty,
            "time" to time,
            "sender" to (event.mergeKey ?: event.address),
            "body" to event.body,
            "otp" to (event.otp?.code.orEmpty()),
            "sim" to sim,
        )
    }

    private fun formatAmount(amountMinor: Long, currency: String): String {
        val major = amountMinor / 100.0
        return "%s %.2f".format(Locale.US, currency, major)
    }
}
