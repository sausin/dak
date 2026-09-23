package app.dak.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import app.dak.R
import app.dak.index.MessageItem

/** Gesture wiring for one bubble: TalkBack custom actions, the long-press label and the optional double-tap. */
internal class BubbleGestures(
    val accessibilityActions: List<CustomAccessibilityAction>,
    val longPressLabel: String,
    /** Null when there is nothing to quick-copy, so single taps are not delayed waiting for a second tap. */
    val onDoubleTap: (() -> Unit)?,
)

/**
 * Swipe-to-reply and double-tap-to-copy are invisible to TalkBack and switch access, so each gets a named custom
 * accessibility action ("Reply", "Copy code" / "Copy amount") alongside the gesture.
 */
@Composable
internal fun rememberBubbleGestures(item: MessageItem, actions: BubbleActions, canReply: Boolean): BubbleGestures {
    val reply = stringResource(R.string.ux_action_reply)
    val copyCode = stringResource(R.string.action_copy_code)
    val copyAmount = stringResource(R.string.ux_action_copy_amount)
    val more = stringResource(R.string.ux_action_message_actions)
    return remember(item, actions, canReply, reply, copyCode, copyAmount, more) {
        val quick = QuickCopy.of(item)
        val list = buildList<CustomAccessibilityAction> {
            if (canReply) add(CustomAccessibilityAction(reply) { actions.onReply(item); true })
            when (quick) {
                is QuickCopy.Code -> add(CustomAccessibilityAction(copyCode) { actions.onDoubleTap(item); true })
                is QuickCopy.Amount -> add(CustomAccessibilityAction(copyAmount) { actions.onDoubleTap(item); true })
                null -> Unit
            }
        }
        BubbleGestures(list, more, if (quick != null) ({ actions.onDoubleTap(item) }) else null)
    }
}
