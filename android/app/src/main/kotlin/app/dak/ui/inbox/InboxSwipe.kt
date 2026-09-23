package app.dak.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.index.ConversationSummary
import app.dak.ui.theme.DakTheme
import app.dak.ui.theme.TonalColors
import app.dak.ui.ux.InboxSwipeAction

/**
 * A conversation row with the two user-configurable swipe actions ([right] / [left] are physical directions, mapped
 * to start/end for RTL). The row always snaps back: the list updates itself and destructive actions offer undo.
 * A swipe set to [InboxSwipeAction.NONE] is disabled. Committing a swipe gives a haptic tick.
 */
@Composable
internal fun SwipeableRow(
    conversation: ConversationSummary,
    right: InboxSwipeAction,
    left: InboxSwipeAction,
    onAction: (InboxSwipeAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val startToEnd = if (rtl) left else right
    val endToStart = if (rtl) right else left
    val haptics = LocalHapticFeedback.current
    // The state (and its confirm callback) outlives recompositions and, in an unkeyed paged list, even the row's
    // conversation: always read the latest actions.
    val currentStartToEnd by rememberUpdatedState(startToEnd)
    val currentEndToStart by rememberUpdatedState(endToStart)
    val currentOnAction by rememberUpdatedState(onAction)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentStartToEnd
                SwipeToDismissBoxValue.EndToStart -> currentEndToStart
                SwipeToDismissBoxValue.Settled -> InboxSwipeAction.NONE
            }
            if (action != InboxSwipeAction.NONE) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnAction(action)
            }
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = startToEnd != InboxSwipeAction.NONE,
        enableDismissFromEndToStart = endToStart != InboxSwipeAction.NONE,
        backgroundContent = {
            val direction = state.dismissDirection
            val action = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> startToEnd
                SwipeToDismissBoxValue.EndToStart -> endToStart
                SwipeToDismissBoxValue.Settled -> InboxSwipeAction.NONE
            }
            val tones = swipeTones(action)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tones?.container ?: MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (tones != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(swipeIcon(action), contentDescription = null, tint = tones.content)
                        Text(swipeLabel(action, conversation), style = MaterialTheme.typography.labelLarge, color = tones.content)
                    }
                }
            }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) { content() }
    }
}

@Composable
private fun swipeTones(action: InboxSwipeAction): TonalColors? = when (action) {
    InboxSwipeAction.ARCHIVE -> DakTheme.colors.success
    InboxSwipeAction.DELETE -> DakTheme.colors.spam
    InboxSwipeAction.MARK_READ -> DakTheme.colors.personal
    InboxSwipeAction.PIN -> DakTheme.colors.warning
    InboxSwipeAction.NONE -> null
}

internal fun swipeIcon(action: InboxSwipeAction): ImageVector = when (action) {
    InboxSwipeAction.ARCHIVE -> Icons.Outlined.Archive
    InboxSwipeAction.DELETE -> Icons.Outlined.Delete
    InboxSwipeAction.MARK_READ -> Icons.Outlined.CheckCircle
    InboxSwipeAction.PIN, InboxSwipeAction.NONE -> Icons.Outlined.PushPin
}

/** The action's label for [conversation] ("Unarchive" on an archived one, "Unpin" on a pinned one). */
@Composable
internal fun swipeLabel(action: InboxSwipeAction, conversation: ConversationSummary): String = stringResource(
    when (action) {
        InboxSwipeAction.ARCHIVE -> if (conversation.archived) R.string.scr_action_unarchive else R.string.scr_action_archive
        InboxSwipeAction.DELETE -> R.string.scr_action_delete_to_bin
        InboxSwipeAction.MARK_READ -> R.string.action_mark_read
        InboxSwipeAction.PIN, InboxSwipeAction.NONE -> if (conversation.pinned) R.string.scr_action_unpin else R.string.scr_action_pin
    },
)
