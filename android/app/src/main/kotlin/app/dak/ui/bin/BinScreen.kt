package app.dak.ui.bin

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.dak.navigation.DakNavigator
import app.dak.ui.common.PlaceholderScreen

/** Placeholder written by the shell agent; the screens agent replaces this body (signature is pinned). */
@Composable
fun BinScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Recycle bin", navigator = navigator, modifier = modifier)
}
