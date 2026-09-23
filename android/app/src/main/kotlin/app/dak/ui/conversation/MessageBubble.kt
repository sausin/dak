package app.dak.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.R
import app.dak.classify.ExtractedLink
import app.dak.core.model.Attachment
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.core.model.SimInfo
import app.dak.index.MessageItem
import app.dak.telephony.MmsDownloadState
import app.dak.ui.common.SimChip
import app.dak.ui.common.TokenChip
import app.dak.ui.common.rememberRelativeTimeFormatter
import app.dak.ui.common.text.BidiText
import app.dak.ui.theme.DakTheme
import app.dak.ui.theme.TonalColors
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.Flow

/** Everything one bubble needs beyond the message itself. */
data class BubbleDecor(
    val sim: SimInfo?,
    /** Sender name above the bubble (group MMS), or null. */
    val senderName: String?,
    val highlighted: Boolean,
    val fontScale: Float,
    /** Outgoing bubble colours (per-thread choice or theme default). */
    val outgoingColors: TonalColors?,
    val forwardedTo: String?,
    val labels: Set<String>,
    /** Raw sender header/number shown as a small "via …" chip in a folded conversation, or null. */
    val channelLabel: String? = null,
    /** SIMs, to label each copy of a repeated message. */
    val sims: List<SimInfo> = emptyList(),
    /** Swipe-to-reply is offered (false when the thread cannot be replied to, e.g. an alphanumeric sender ID). */
    val canReply: Boolean = true,
)

/** Callbacks from a bubble. */
interface BubbleActions {
    fun onLongPress(item: MessageItem)
    fun onLink(item: MessageItem, link: ExtractedLink)
    fun onCopyOtp(item: MessageItem, code: String)
    fun onDeleteOtp(item: MessageItem)
    fun onRetrySend(item: MessageItem)
    fun onRetryMms(item: MessageItem)
    fun mmsState(item: MessageItem): Flow<MmsDownloadState>

    /** Every copy of a collapsed repeated message ([MessageItem.repeatCount] > 1), newest first. */
    suspend fun repeatsOf(item: MessageItem): List<MessageItem> = emptyList()

    /** Opens [route] (compose, search, passbook) from a smart-entity action ([EntityActionSheet]). */
    fun onNavigate(route: String) {}

    /** Swipe-to-reply (or the "Reply" accessibility action): quote this message in the composer. */
    fun onReply(item: MessageItem) {}

    /** Double-tap: copy the message's [QuickCopy] (OTP code, else amount). Only wired when there is one. */
    fun onDoubleTap(item: MessageItem) {}
}

private val OUTGOING_BOXES = setOf(MessageBox.SENT, MessageBox.OUTBOX, MessageBox.QUEUED, MessageBox.FAILED, MessageBox.DRAFT)

/** True for messages the user sent (or is sending). */
val MessageItem.isOutgoing: Boolean get() = box in OUTGOING_BOXES

/**
 * One message bubble: themed via tokens (incoming/outgoing/failed), SIM chip, group sender name, OTP highlight
 * with tap-to-copy and a quick "delete now", inline images (Coil, content URIs), MMS download state with tap to
 * retry, delivery status, link-safety-checked links, labels and "forwarded to" note.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(item: MessageItem, decor: BubbleDecor, actions: BubbleActions, modifier: Modifier = Modifier) {
    val outgoing = item.isOutgoing
    val colors = DakTheme.colors
    val failed = item.box == MessageBox.FAILED
    val container: Color
    val content: Color
    when {
        failed -> { container = colors.bubbleFailed; content = colors.onBubbleFailed }
        outgoing && decor.outgoingColors != null -> { container = decor.outgoingColors.container; content = decor.outgoingColors.content }
        outgoing -> { container = colors.bubbleOutgoing; content = colors.onBubbleOutgoing }
        else -> { container = colors.bubbleIncoming; content = colors.onBubbleIncoming }
    }
    val rowBackground = if (decor.highlighted) colors.focusHighlight else Color.Transparent
    val bodyStyle = MaterialTheme.typography.bodyLarge.let { base ->
        if (decor.fontScale == 1f) base else base.copy(fontSize = base.fontSize * decor.fontScale, lineHeight = base.lineHeight * decor.fontScale)
    }
    val linkColor = if (outgoing) content else MaterialTheme.colorScheme.primary
    val textColors = MessageTextColors(
        otpBackground = colors.otpHighlight,
        otpContent = colors.onOtpHighlight,
        highlightBackground = colors.focusHighlight,
        link = linkColor,
    )
    val annotated = remember(item.body, item.otp?.code, textColors) {
        annotateMessage(
            body = item.body,
            colors = textColors,
            otpCode = item.otp?.code,
            onOtp = { code -> actions.onCopyOtp(item, code) },
            onLink = { link -> actions.onLink(item, link) },
        )
    }

    Column(
        modifier = modifier.fillMaxWidth().background(rowBackground).padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        if (decor.senderName != null && !outgoing) {
            Text(
                BidiText.displaySafe(decor.senderName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
            )
        }
        val canReply = decor.canReply && item.body.isNotEmpty()
        val gestures = rememberBubbleGestures(item, actions, canReply)
        SwipeToReply(enabled = canReply, onReply = { actions.onReply(item) }) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (outgoing) 18.dp else 4.dp,
                    bottomEnd = if (outgoing) 4.dp else 18.dp,
                ),
                color = container,
                contentColor = content,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .semantics { customActions = gestures.accessibilityActions }
                    .combinedClickable(
                        onClick = { if (failed) actions.onRetrySend(item) },
                        onLongClick = { actions.onLongPress(item) },
                        onLongClickLabel = gestures.longPressLabel,
                        onDoubleClick = gestures.onDoubleTap,
                    ),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.attachments.isNotEmpty()) AttachmentList(item.attachments)
                    if (item.kind() == MessageKind.MMS && !outgoing) MmsDownloadRow(item, actions)
                    if (item.body.isNotEmpty()) {
                        EntityMessageText(item, annotated, bodyStyle, content, actions::onNavigate) { link -> actions.onLink(item, link) }
                    }
                }
            }
        }
        item.otp?.let { otp -> OtpRow(item, otp.code, otp.consumedBy, actions) }
        MetaRow(item, decor, outgoing)
        if (item.repeatCount > 1) RepeatRow(item, decor, actions)
    }
}

/** "×3 · last 10:42" under a collapsed repeated message; tap lists each copy (time, SIM). */
@Composable
private fun RepeatRow(item: MessageItem, decor: BubbleDecor, actions: BubbleActions) {
    var expanded by rememberSaveable(item.key.toString()) { mutableStateOf(false) }
    val formatter = rememberRelativeTimeFormatter()
    val last = remember(item.dateMillis, formatter) { formatter.format(item.dateMillis) }
    val description = stringResource(R.string.fold_repeat_chip_description, item.repeatCount, formatter.formatAbsolute(item.dateMillis))
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .semantics { contentDescription = description },
    ) {
        Text(
            if (expanded) stringResource(R.string.fold_repeat_hide) else stringResource(R.string.fold_repeat_chip, item.repeatCount, last),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
    if (expanded) {
        val copies by produceState(initialValue = emptyList<MessageItem>(), item.key, item.repeatCount) {
            value = actions.repeatsOf(item)
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (copy in copies) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    decor.sims.firstOrNull { it.subId == copy.subId }?.let { SimChip(sim = it, compact = true) }
                    Text(
                        stringResource(R.string.fold_repeat_occurrence, formatter.formatAbsolute(copy.dateMillis)),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                    if (copy.address != item.address) {
                        Text(BidiText.displaySafe(copy.address), style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                }
            }
        }
    }
}

private fun MessageItem.kind(): MessageKind = key.kind

@Composable
private fun AttachmentList(attachments: List<Attachment>) {
    val context = LocalContext.current
    for (a in attachments) {
        when {
            a.mimeType.startsWith("image/") -> AsyncImage(
                model = Uri.parse(a.uri),
                contentDescription = a.name ?: stringResource(R.string.notification_photo),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { openAttachment(context, a) },
            )
            else -> {
                val isCard = a.mimeType.contains("vcard", ignoreCase = true)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clickable { openAttachment(context, a) }.padding(vertical = 4.dp),
                ) {
                    Icon(if (isCard) Icons.Outlined.ContactPage else Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        a.name ?: stringResource(if (isCard) R.string.scr_bubble_contact_card else R.string.notification_attachment),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MmsDownloadRow(item: MessageItem, actions: BubbleActions) {
    val flow = remember(item.key) { actions.mmsState(item) }
    val state by flow.collectAsStateWithLifecycle(initialValue = MmsDownloadState.Done)
    when (val s = state) {
        MmsDownloadState.Done -> Unit
        MmsDownloadState.Pending, MmsDownloadState.Downloading -> Text(
            stringResource(R.string.scr_bubble_mms_downloading),
            style = MaterialTheme.typography.bodySmall,
        )
        is MmsDownloadState.Failed -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clickable { actions.onRetryMms(item) },
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Column {
                Text(stringResource(R.string.scr_bubble_mms_failed), style = MaterialTheme.typography.bodyMedium)
                Text(s.reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OtpRow(item: MessageItem, code: String, consumedBy: String?, actions: BubbleActions) {
    val colors = DakTheme.colors
    Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.otpHighlight,
            contentColor = colors.onOtpHighlight,
            modifier = Modifier.clickable { actions.onCopyOtp(item, code) },
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(code, style = DakTheme.typography.otpCode.copy(fontSize = DakTheme.typography.otpCode.fontSize * 0.7f))
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy_code), modifier = Modifier.size(18.dp))
            }
        }
        if (!item.isOutgoing) {
            TextButton(onClick = { actions.onDeleteOtp(item) }) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.action_delete_now), modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (consumedBy != null) {
            Text(
                stringResource(R.string.otp_used_by, consumedBy),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetaRow(item: MessageItem, decor: BubbleDecor, outgoing: Boolean) {
    val formatter = rememberRelativeTimeFormatter()
    val time = remember(item.dateMillis, formatter) { formatter.formatAbsolute(item.dateMillis) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        decor.sim?.let { SimChip(sim = it, compact = true) }
        decor.channelLabel?.let {
            Text(stringResource(R.string.fold_chip_channel, BidiText.displaySafe(it)), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
        }
        if (item.starred) Icon(Icons.Outlined.Star, contentDescription = stringResource(R.string.scr_starred), modifier = Modifier.size(14.dp), tint = muted)
        Text(time, style = MaterialTheme.typography.labelSmall, color = muted)
        if (outgoing) {
            val status = when (item.box) {
                MessageBox.OUTBOX, MessageBox.QUEUED -> stringResource(R.string.scr_status_sending)
                MessageBox.FAILED -> stringResource(R.string.scr_status_failed)
                MessageBox.SENT -> stringResource(R.string.scr_status_sent)
                else -> null
            }
            if (status != null) {
                if (item.box == MessageBox.FAILED) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                }
                Text(status, style = MaterialTheme.typography.labelSmall, color = if (item.box == MessageBox.FAILED) MaterialTheme.colorScheme.error else muted)
            }
        }
        for (label in decor.labels) TokenChip(label = label, colors = DakTheme.colors.unknownCategory)
        decor.forwardedTo?.let {
            Text(stringResource(R.string.scr_bubble_forwarded_to, it), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

/** Sender-chosen MIME types never pick the handler: see [AttachmentOpener]. */
private fun openAttachment(context: Context, attachment: Attachment) = AttachmentOpener.open(context, attachment)
