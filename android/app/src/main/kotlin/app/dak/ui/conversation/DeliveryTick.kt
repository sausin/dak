package app.dak.ui.conversation

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.index.MessageItem

/** The tick state of a message (null for incoming messages). */
val MessageItem.tickState: TickState? get() = DeliveryTicks.stateOf(box, deliveryStatus)

/**
 * Status glyph of an outgoing message: clock (sending), single check (sent), double check in the accent colour
 * (delivered), red error (failed; the bubble itself is the tap-to-retry target). Colours are theme tokens drawn on
 * the conversation background: `onSurfaceVariant`, `primary` and `error` all meet AA there in every Dak theme.
 */
@Composable
fun DeliveryTick(state: TickState, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    val scheme = MaterialTheme.colorScheme
    val icon = when (state) {
        TickState.SENDING -> Icons.Outlined.Schedule
        TickState.SENT -> Icons.Outlined.Done
        TickState.DELIVERED -> Icons.Outlined.DoneAll
        TickState.FAILED -> Icons.Outlined.ErrorOutline
    }
    val tint = when (state) {
        TickState.SENDING, TickState.SENT -> scheme.onSurfaceVariant
        TickState.DELIVERED -> scheme.primary
        TickState.FAILED -> scheme.error
    }
    val description = stringResource(
        when (state) {
            TickState.SENDING -> R.string.tick_sending
            TickState.SENT -> R.string.tick_sent
            TickState.DELIVERED -> R.string.tick_delivered
            TickState.FAILED -> R.string.tick_failed
        },
    )
    Icon(icon, contentDescription = description, tint = tint, modifier = modifier.size(size))
}
