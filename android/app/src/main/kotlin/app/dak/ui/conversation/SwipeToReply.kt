package app.dak.ui.conversation

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** How far a bubble must be dragged towards the end edge to trigger a reply. */
private val REPLY_THRESHOLD = 64.dp

/**
 * Drag [content] towards the end edge (right in LTR) past a threshold to reply: a reply icon fades in behind it, a
 * haptic tick marks the point of no return and releasing there calls [onReply]. The bubble springs back either way.
 * Only horizontal drags are consumed, so the list still scrolls, and the system back gesture keeps the screen
 * edges (bubbles never start at the edge).
 */
@Composable
fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val haptics = LocalHapticFeedback.current
    val thresholdPx = with(LocalDensity.current) { REPLY_THRESHOLD.toPx() }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val scope = rememberCoroutineScope()
    val currentOnReply by rememberUpdatedState(onReply)
    var drag by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) } // past the threshold in this gesture

    fun settle() {
        val from = drag
        armed = false
        scope.launch { animate(from, 0f) { value, _ -> drag = value } }
    }

    Box(
        modifier.pointerInput(direction, thresholdPx) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (armed) currentOnReply()
                    settle()
                },
                onDragCancel = { settle() },
                onHorizontalDrag = { change, dragAmount ->
                    val next = (drag + dragAmount * direction).coerceIn(0f, thresholdPx * 1.4f)
                    if (next != drag) change.consume()
                    drag = next
                    if (!armed && next >= thresholdPx) {
                        armed = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else if (armed && next < thresholdPx) {
                        armed = false
                    }
                },
            )
        },
    ) {
        if (drag > 0f) {
            Icon(
                Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(24.dp)
                    .graphicsLayer { alpha = (drag / thresholdPx).coerceIn(0f, 1f) },
            )
        }
        Box(Modifier.graphicsLayer { translationX = drag * direction }) { content() }
    }
}
