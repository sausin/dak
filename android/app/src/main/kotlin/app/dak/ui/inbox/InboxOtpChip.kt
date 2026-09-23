package app.dak.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.classify.OtpExtractor
import app.dak.core.model.Category
import app.dak.core.model.MessageBox
import app.dak.index.ConversationSummary
import app.dak.ui.theme.DakTheme
import kotlinx.coroutines.delay

/** How long an OTP counts as fresh enough for the inbox "Copy code" chip. */
internal const val FRESH_OTP_MILLIS = 10 * 60_000L

/**
 * The code of a fresh (< 10 min) incoming OTP that is the newest message of [conversation], or null. Uses the
 * on-device extractor on the row's preview, so no extra query runs per row.
 */
internal fun freshOtpCode(conversation: ConversationSummary, nowMillis: Long): String? {
    if (!conversation.enriched || conversation.category != Category.OTP || conversation.lastBox != MessageBox.INBOX) return null
    val age = nowMillis - conversation.dateMillis
    if (age > FRESH_OTP_MILLIS || age < -CLOCK_SKEW_MILLIS) return null
    return OtpExtractor.extract(conversation.snippet)?.code
}

private const val CLOCK_SKEW_MILLIS = 60_000L

/**
 * The code with a copy affordance, right on the inbox row, so a user waiting for an OTP never has to open the
 * thread. After a tap it reads "Copied" for two seconds; the change is a polite live region for TalkBack.
 */
@Composable
internal fun InboxOtpChip(code: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    val haptics = LocalHapticFeedback.current
    val copyLabel = stringResource(R.string.ux_inbox_copy_code_description, code)
    val copiedLabel = stringResource(R.string.ux_inbox_code_copied)
    val click = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onCopy()
        copied = true
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DakTheme.colors.otpHighlight,
        contentColor = DakTheme.colors.onOtpHighlight,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = click)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = if (copied) copiedLabel else copyLabel
                liveRegion = LiveRegionMode.Polite
                onClick(label = copyLabel) { click(); true }
            },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (copied) copiedLabel else code, style = MaterialTheme.typography.labelLarge)
            Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}
