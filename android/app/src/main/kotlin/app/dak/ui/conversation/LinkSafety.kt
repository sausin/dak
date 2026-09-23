package app.dak.ui.conversation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.classify.ExtractedLink
import app.dak.classify.LinkRisk
import app.dak.classify.LinkVerdict
import app.dak.classify.LookalikeDomainChecker
import app.dak.ui.theme.DakTheme

/** A link the user tapped that needs a warning before it opens. */
data class LinkWarning(
    val verdict: LinkVerdict,
    val unknownSender: Boolean,
    /** Key of the message carrying the link, so the dialog can offer "Report" (Report fraud screen). */
    val messageKey: String? = null,
)

/**
 * Link safety on bubbles: every tapped link is checked against the bundled official-domain, shortener and
 * suspicious-TLD lists; risky links (and any non-official link from an unknown numeric sender) get a warning and
 * need a second tap to open.
 */
object LinkSafety {
    private val checker = LookalikeDomainChecker()

    /** Label :classify puts on numeric, non-contact senders whose message carries a link. */
    const val UNKNOWN_SENDER_LINK_LABEL = "unknown-sender-link"

    /** Null when the link can open straight away. */
    fun warningFor(link: ExtractedLink, unknownSender: Boolean): LinkWarning? {
        val verdict = checker.check(link)
        val risky = verdict.risk == LinkRisk.LOOKALIKE || verdict.risk == LinkRisk.SUSPICIOUS_TLD || verdict.risk == LinkRisk.SHORTENED
        return if (risky || (unknownSender && verdict.risk != LinkRisk.OFFICIAL)) LinkWarning(verdict, unknownSender) else null
    }

    /**
     * Opens [raw] in the browser; false when nothing can handle it or it is not a web link. Only `http`/`https`
     * ever open (a bare `www.` link gets `https://`), so a message can never launch `intent:`, `content:`, `file:`
     * or `javascript:` URIs, whatever text surrounds them. For a message link pass [ExtractedLink.url], which also
     * gives scheme-less links (`bit.ly/x`) their `https://`.
     */
    fun open(context: Context, raw: String): Boolean {
        val uri = webUriOrNull(raw) ?: return false
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** `http(s)://…` as-is, `www.…` with `https://` prefixed; null for any other scheme or an unparsable link. */
    internal fun webUriOrNull(raw: String): Uri? {
        val candidate = normalizedWebLink(raw) ?: return null
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return uri
    }

    /** Longest link Dak will hand to a browser. */
    internal const val MAX_LINK_CHARS: Int = 4_096

    /**
     * The exact string that is opened for [raw], or null when it is not a plain web link. Pure (no Android types), so
     * it is unit-tested directly.
     *
     * Backslashes become `/`: browsers (WHATWG URL parsing) treat `\` as a path separator in http(s) URLs, and so
     * does the link checker, but `android.net.Uri` does not. Without this, `https://bank.example\@evil.example`
     * would be checked (and opened by the browser) as `bank.example`, while Android's intent resolution — which picks
     * the app that handles the link — would see host `evil.example`. Whitespace or control characters, and absurdly
     * long links, are refused.
     */
    internal fun normalizedWebLink(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LINK_CHARS) return null
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        val candidate = (if (trimmed.startsWith("www.", ignoreCase = true)) "https://$trimmed" else trimmed).replace('\\', '/')
        val separator = candidate.indexOf("://")
        if (separator <= 0) return null
        val scheme = candidate.substring(0, separator).lowercase()
        if (scheme != "http" && scheme != "https") return null
        return candidate
    }
}

/** Warning dialog shown before a risky link opens; "Open anyway" is the deliberate second tap. */
@Composable
fun LinkWarningDialog(warning: LinkWarning, onOpen: () -> Unit, onDismiss: () -> Unit, onReport: (() -> Unit)? = null) {
    val verdict = warning.verdict
    val reason = when (verdict.risk) {
        LinkRisk.LOOKALIKE -> stringResource(R.string.scr_link_lookalike, verdict.matchedBrand ?: verdict.link.host.orEmpty())
        LinkRisk.SUSPICIOUS_TLD -> stringResource(R.string.scr_link_suspicious_tld)
        LinkRisk.SHORTENED -> stringResource(R.string.scr_link_shortened)
        else -> stringResource(R.string.scr_link_unknown_sender)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = DakTheme.colors.warning.accent) },
        title = { Text(stringResource(R.string.scr_link_warning_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reason)
                Text(verdict.link.raw, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Homographs (Cyrillic "а" in "hdfcbаnk.com") and "brand.com@evil.xyz" links: show the host that will
                // actually open, in its punycode form, so the difference is visible.
                val realHost = verdict.link.asciiHost ?: verdict.link.host
                if (realHost != null && (verdict.link.isIdn || verdict.link.hasUserInfo)) {
                    Text(stringResource(R.string.sec_link_real_host, realHost), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                if (warning.unknownSender) Text(stringResource(R.string.scr_link_never_share), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.scr_link_open_anyway)) } },
        dismissButton = {
            Row {
                if (onReport != null) TextButton(onClick = onReport) { Text(stringResource(R.string.safe_link_report)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.scr_action_dont_open)) }
            }
        },
    )
}
