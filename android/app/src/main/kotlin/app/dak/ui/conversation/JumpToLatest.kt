package app.dak.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.dak.R
import kotlinx.coroutines.launch

/** Beyond this many bubbles, jumping snaps instead of animating (a long animated scroll pages in every bubble). */
private const val ANIMATE_LIMIT = 30

/**
 * "Jump to latest" button for a reverse-layout thread list, shown once the user has scrolled a few bubbles away
 * from the newest message. Sits above the composer, in the thumb zone.
 */
@Composable
fun JumpToLatest(listState: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val show by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    AnimatedVisibility(visible = show, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(), modifier = modifier) {
        SmallFloatingActionButton(
            onClick = {
                scope.launch {
                    if (listState.firstVisibleItemIndex > ANIMATE_LIMIT) listState.scrollToItem(0) else listState.animateScrollToItem(0)
                }
            },
        ) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.ux_jump_latest))
        }
    }
}
