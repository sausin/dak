package app.dak.ui.conversation

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.incognito.IncognitoVanisher
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Explains incognito before turning it on: what vanishes, what cannot (the other phone's copy). */
@Composable
fun IncognitoConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
        title = { Text(stringResource(R.string.inc_dialog_title)) },
        text = { Text(stringResource(R.string.inc_dialog_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.inc_dialog_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Shown at the top of an incognito thread, with a one-tap way out. */
@Composable
fun IncognitoBanner(onTurnOff: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.inc_banner_title), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.inc_banner_body), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onTurnOff) { Text(stringResource(R.string.inc_turn_off)) }
        }
    }
}

/**
 * Wraps a bubble that may vanish: while [vanishing] it lifts, shrinks, blurs (Android 12+) and fades, and scatters
 * into drifting specks, over [IncognitoVanisher.ANIMATION_MILLIS] (the delete lands right after).
 */
@Composable
fun VanishingBubble(vanishing: Boolean, seed: Int, content: @Composable () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (vanishing) 1f else 0f,
        animationSpec = tween(durationMillis = (IncognitoVanisher.ANIMATION_MILLIS - 150).toInt(), easing = FastOutSlowInEasing),
        label = "vanish",
    )
    if (progress == 0f) {
        content()
        return
    }
    val specks = remember(seed) { Speck.scatter(seed) }
    val color = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.inc_vanishing)
    Box(Modifier.semantics { contentDescription = description }) {
        Box(
            Modifier
                .graphicsLayer {
                    alpha = 1f - progress
                    scaleX = 1f - 0.12f * progress
                    scaleY = 1f - 0.12f * progress
                    translationY = -18.dp.toPx() * progress
                }
                .let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) it.blur((10 * progress).dp, BlurredEdgeTreatment.Unbounded) else it },
        ) { content() }
        Canvas(Modifier.matchParentSize()) {
            for (speck in specks) {
                val travel = speck.speed * size.minDimension * progress
                val center = Offset(
                    x = speck.x * size.width + cos(speck.angle) * travel,
                    y = speck.y * size.height + sin(speck.angle) * travel - 24.dp.toPx() * progress,
                )
                drawCircle(color = color, radius = speck.radius.dp.toPx() * (1f - progress * 0.6f), center = center, alpha = (1f - progress) * speck.alpha)
            }
        }
    }
}

/**
 * The reading window of a received incognito message: a thin bar that empties over
 * [IncognitoVanisher.READ_WINDOW_MILLIS]; [onViewed] runs when it first shows, [onElapsed] when it runs out.
 */
@Composable
fun VanishCountdown(key: Any, onViewed: () -> Unit, onElapsed: () -> Unit, modifier: Modifier = Modifier) {
    val remaining = remember(key) { Animatable(1f) }
    val viewed by rememberUpdatedState(onViewed)
    val elapsed by rememberUpdatedState(onElapsed)
    LaunchedEffect(key) {
        viewed()
        remaining.animateTo(0f, tween(IncognitoVanisher.READ_WINDOW_MILLIS.toInt(), easing = LinearEasing))
        elapsed()
    }
    val description = stringResource(R.string.inc_countdown)
    Row(
        modifier.padding(start = 16.dp, bottom = 4.dp).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { remaining.value }, modifier = Modifier.width(56.dp).height(3.dp))
    }
}

/** One dust particle of [VanishingBubble]: start point (fractions of the bubble), direction, speed and look. */
private class Speck(val x: Float, val y: Float, val angle: Float, val speed: Float, val radius: Float, val alpha: Float) {
    companion object {
        fun scatter(seed: Int, count: Int = 28): List<Speck> {
            val random = Random(seed)
            return List(count) {
                Speck(
                    x = random.nextFloat(),
                    y = random.nextFloat(),
                    angle = (random.nextFloat() * 2 * Math.PI).toFloat(),
                    speed = 0.15f + random.nextFloat() * 0.45f,
                    radius = 1.2f + random.nextFloat() * 2.2f,
                    alpha = 0.5f + random.nextFloat() * 0.5f,
                )
            }
        }
    }
}
