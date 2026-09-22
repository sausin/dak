package app.dak.ui.conversation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.classify.ExtractedLink
import app.dak.classify.LinkRisk
import app.dak.classify.LinkVerdict
import app.dak.classify.LookalikeDomainChecker
import app.dak.ui.theme.DakTheme

/** A link the user tapped that needs a warning before it opens. */
data class LinkWarning(val verdict: LinkVerdict, val unknownSender: Boolean)

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

    /** Opens [raw] in the browser; false when nothing can handle it. */
    fun open(context: Context, raw: String): Boolean {
        val url = if (raw.contains("://")) raw else "https://$raw"
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}

/** Warning dialog shown before a risky link opens; "Open anyway" is the deliberate second tap. */
@Composable
fun LinkWarningDialog(warning: LinkWarning, onOpen: () -> Unit, onDismiss: () -> Unit) {
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
                if (warning.unknownSender) Text(stringResource(R.string.scr_link_never_share), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.scr_link_open_anyway)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scr_action_dont_open)) } },
    )
}
