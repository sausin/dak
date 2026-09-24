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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.dak.R
import app.dak.incognito.FailedSendStep
import app.dak.incognito.IncognitoVanisher
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shown the first time incognito is turned on: what vanishes, and, set apart so it cannot be missed, what cannot (the
 * other person's copy). Later it turns on with a one-line reminder instead.
 */
@Composable
fun IncognitoConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
        title = { Text(stringResource(R.string.inc_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.inc_intro_other_side_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.inc_intro_other_side_body), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(stringResource(R.string.inc_dialog_body))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.inc_dialog_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Under a failed incognito send: Retry / Delete ([FailedSendStep.FIRST_FAILURE]) or, after a retry failed too,
 * Keep / Delete ([FailedSendStep.SECOND_FAILURE]). Unanswered for [IncognitoVanisher.FAILED_PROMPT_MILLIS] (while on
 * screen), the message is deleted, as incognito promises; the countdown is shown so that is never a surprise.
 */
@Composable
fun FailedSendPrompt(key: Any, step: FailedSendStep, onRetry: () -> Unit, onKeep: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val remaining = remember(key, step) { Animatable(1f) }
    val timeout by rememberUpdatedState(onDelete)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(key, step) {
        // The clock only runs while the thread is on screen: nothing is deleted while the user is elsewhere.
        var done = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (done) return@repeatOnLifecycle
            val left = (remaining.value * IncognitoVanisher.FAILED_PROMPT_MILLIS).toInt()
            remaining.animateTo(0f, tween(left, easing = LinearEasing))
            done = true
            timeout()
        }
    }
    val seconds = ((remaining.value * IncognitoVanisher.FAILED_PROMPT_MILLIS) / 1000f).toInt() + 1
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(start = 64.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (step == FailedSendStep.SECOND_FAILURE) R.string.inc_failed_again else R.string.inc_failed_first),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                pluralStringResource(R.plurals.inc_failed_deletes_in, seconds, seconds),
                style = MaterialTheme.typography.labelSmall,
            )
            LinearProgressIndicator(progress = { remaining.value }, modifier = Modifier.fillMaxWidth().height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.inc_failed_delete)) }
                if (step == FailedSendStep.SECOND_FAILURE) {
                    FilledTonalButton(onClick = onKeep) { Text(stringResource(R.string.inc_failed_keep)) }
                } else {
                    FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.inc_failed_retry)) }
                }
            }
        }
    }
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
